package io.vibeminer.infrastructure.acquisition;

import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static io.vibeminer.infrastructure.acquisition.AcquisitionException.Reason.*;

class PinnedCdnTransportTest {
    static final CdnRequest.Source SOURCE = new CdnRequest.Source("us.cdn.blizzard.com", "tpr/wow", "tpr/configs/data", List.of("us.cdn.blizzard.com"));
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    static String md5(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value)); }
    static CdnRequest config(byte[] value) throws Exception { return CdnRequest.configuration(SOURCE, md5(value)); }
    static PinnedCdnTransport transport(int status, String encoding, long length, byte[] bytes) {
        return new PinnedCdnTransport((request, deadline) -> CompletableFuture.completedFuture(
                new PinnedCdnTransport.Response(status, encoding, length, bytes)), CLOCK, Duration.ofSeconds(1));
    }
    static void reason(AcquisitionException.Reason expected, CompletableFuture<?> future) {
        var failure = assertThrows(CompletionException.class, future::join);
        assertEquals(expected, assertInstanceOf(AcquisitionException.class, failure.getCause()).reason());
    }
    @Test void pinnedRoutesRetainConfigPathWithoutGuessingIt() {
        var request = CdnRequest.configuration(SOURCE, "ABCDEF0123456789ABCDEF0123456789");
        assertEquals("https://us.cdn.blizzard.com/tpr/wow/config/ab/cd/abcdef0123456789abcdef0123456789", request.uri().toString());
        assertEquals("tpr/configs/data", request.source().configPath());
        assertEquals("/tpr/wow/data/ab/cd/abcdef0123456789abcdef0123456789", CdnRequest.loose(SOURCE, request.key(), 20, 30).uri().getPath());
    }
    @Test void rejectsUnadvertisedHostsTraversalAndArbitraryUrls() {
        for (String host : List.of("evil.example", "us.cdn.blizzard.com.evil.example", "US.CDN.BLIZZARD.COM", "us.cdn.blizzard.com:443", "user@us.cdn.blizzard.com"))
            assertThrows(IllegalArgumentException.class, () -> new CdnRequest.Source(host, "tpr/wow", "tpr/configs/data", List.of(host)));
        assertThrows(IllegalArgumentException.class, () -> new CdnRequest.Source("us.cdn.blizzard.com", "tpr/wow", "tpr/configs/data", List.of("level3.blizzard.com")));
        for (String path : List.of("../wow", "tpr/../wow", "tpr/wow?query", "tpr/wow/extra", "https://evil.example", "tpr/%2e%2e"))
            assertThrows(IllegalArgumentException.class, () -> new CdnRequest.Source(SOURCE.host(), path, SOURCE.configPath(), SOURCE.advertisedHosts()));
        for (String key : List.of("../data", "a".repeat(31), "g".repeat(32), "a".repeat(32) + "?x"))
            assertThrows(IllegalArgumentException.class, () -> CdnRequest.configuration(SOURCE, key));
    }
    @Test void rejectsUnknownOrOverBudgetLooseSizesAndOverPolicyConfig() {
        for (long size : new long[] {-1, 0, 101, Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> CdnRequest.loose(SOURCE, "a".repeat(32), size, 100));
        assertThrows(IllegalArgumentException.class, () -> new CdnRequest(SOURCE, CdnRequest.Kind.CONFIG, "a".repeat(32), 1048577, -1));
    }
    @Test void verifiesExactConfigBytesPreservesSourceAndDefendsCopies() throws Exception {
        byte[] value = "encoding = a b\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = config(value);
        var acquired = transport(200, "identity", value.length, value).fetch(request).join();
        assertTrue(acquired.configurationMd5Verified()); assertEquals(request, acquired.request()); assertEquals(CLOCK.instant(), acquired.receivedAt());
        assertEquals(64, acquired.sha256().length());
        value[0] = 0; assertNotEquals(0, acquired.bytes()[0]);
        var copy = acquired.bytes(); copy[0] = 0; assertNotEquals(0, acquired.bytes()[0]);
    }
    @Test void rejectsConfigMismatchWithoutNormalizingNewlines() throws Exception {
        byte[] bytes = "x\r\n".getBytes();
        reason(INTEGRITY, transport(200, "identity", 2, "x\n".getBytes()).fetch(config(bytes)));
    }
    @Test void doesNotClaimWholeFileMd5VerifiesAnEncodedKey() {
        byte[] bytes = {1,2,3};
        var acquired = transport(200, "identity", 3, bytes).fetch(CdnRequest.loose(SOURCE, "a".repeat(32), 3, 3)).join();
        assertFalse(acquired.configurationMd5Verified()); assertArrayEquals(bytes, acquired.bytes());
    }
    @Test void classifiesUnavailableRedirectEncodingShortAndOversize() throws Exception {
        byte[] bytes = {1,2,3}; var request = config(bytes);
        reason(UNAVAILABLE, transport(404, "identity", 0, new byte[0]).fetch(request));
        reason(UNAVAILABLE, transport(410, "identity", 0, new byte[0]).fetch(request));
        for (int code : new int[] {301, 302, 307, 500, 206}) reason(TRANSPORT, transport(code, "identity", 3, bytes).fetch(request));
        reason(INTEGRITY, transport(200, "gzip", 3, bytes).fetch(request));
        reason(INTEGRITY, transport(200, "identity", 4, bytes).fetch(request));
        reason(INTEGRITY, transport(200, "identity", -1, bytes).fetch(CdnRequest.loose(SOURCE, "a".repeat(32), 4, 5)));
        reason(LIMIT, transport(200, "identity", 3, bytes).fetch(CdnRequest.loose(SOURCE, "a".repeat(32), 2, 2)));
    }
    @Test void cancellationPropagatesToPendingExchange() throws Exception {
        var pending = new CompletableFuture<PinnedCdnTransport.Response>();
        var transport = new PinnedCdnTransport((request, deadline) -> pending, CLOCK, Duration.ofSeconds(1));
        var future = transport.fetch(config(new byte[] {1})); assertTrue(future.cancel(true)); assertTrue(pending.isCancelled());
    }
    @Test void deadlineCancelsStalledExchange() throws Exception {
        var pending = new CompletableFuture<PinnedCdnTransport.Response>();
        var transport = new PinnedCdnTransport((request, deadline) -> pending, CLOCK, Duration.ofMillis(30));
        reason(TRANSPORT, transport.fetch(config(new byte[] {1})));
        assertThrows(CancellationException.class, () -> pending.get(1, TimeUnit.SECONDS));
    }
    @Test void classifiesWireFailureAndCancellation() throws Exception {
        var bytes = new byte[] {1};
        var transport = new PinnedCdnTransport((request, deadline) -> CompletableFuture.failedFuture(new java.io.IOException()), CLOCK, Duration.ofSeconds(1));
        reason(TRANSPORT, transport.fetch(config(bytes)));
        var cancelled = new PinnedCdnTransport((request, deadline) -> CompletableFuture.failedFuture(new CancellationException()), CLOCK, Duration.ofSeconds(1));
        reason(CANCELLED, cancelled.fetch(config(bytes)));
    }
    @Test void rejectsRedirectFollowingClients() {
        assertThrows(IllegalArgumentException.class, () -> new PinnedCdnTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(), CLOCK, Duration.ofSeconds(1)));
    }
    @Test void subscriberBoundsMultipleBuffersAndCancelsAtLimit() {
        var cancelled = new AtomicBoolean();
        var body = new PinnedCdnTransport.LimitedBody(3);
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled.set(true); } });
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {1,2}), ByteBuffer.wrap(new byte[] {3,4})));
        assertTrue(cancelled.get()); reason(LIMIT, body.getBody().toCompletableFuture());
    }
    @Test void subscriberCombinesChunksAndRejectsDuplicateSubscription() {
        var duplicateCancelled = new AtomicBoolean();
        var body = new PinnedCdnTransport.LimitedBody(3);
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() {} });
        body.onSubscribe(new Flow.Subscription() { public void request(long n) { fail(); } public void cancel() { duplicateCancelled.set(true); } });
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {1}), ByteBuffer.wrap(new byte[] {2,3})));
        body.onComplete(); assertArrayEquals(new byte[] {1,2,3}, body.getBody().toCompletableFuture().join()); assertTrue(duplicateCancelled.get());
    }
}
