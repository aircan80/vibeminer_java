package io.vibeminer.infrastructure.bootstrap;

import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.tact.blte.*;
import io.vibeminer.protocol.tact.config.*;
import io.vibeminer.protocol.tact.install.*;
import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Bounded official-source/config/BLTE/IN adapter. No job, catalog, membership or retention policy. */
public final class OfficialBootstrapArtifactGateway implements BootstrapArtifactGateway {
    public static final int MAXIMUM_ENCODED_BYTES = 4 * 1024 * 1024;
    public static final int MAXIMUM_DECODED_BYTES = 16 * 1024 * 1024;
    private static final String CONFIG_SEMANTICS = "tact-config-raw-md5-v1";
    private static final String INSTALL_SEMANTICS = "blte-nz-ekey-header-or-whole-chunk-md5-ckey-md5+in-v1-r1";
    private static final TactConfigParser.Limits CONFIG_LIMITS = new TactConfigParser.Limits(1024 * 1024, 8192, 16384, 256, MAXIMUM_DECODED_BYTES, MAXIMUM_ENCODED_BYTES);
    private static final BlteDecoder.Limits BLTE_LIMITS = new BlteDecoder.Limits(MAXIMUM_ENCODED_BYTES, MAXIMUM_DECODED_BYTES, 4096);
    private static final InstallManifestParser.Limits INSTALL_LIMITS = new InstallManifestParser.Limits(MAXIMUM_DECODED_BYTES, 50000, 512, 16384);
    private final ObjectStore store;
    private final BootstrapTransfer transport;
    private final Clock clock;
    public OfficialBootstrapArtifactGateway(ObjectStore store, PinnedCdnTransport transport, Clock clock) {
        this(store, transport::fetch, clock);
    }
    OfficialBootstrapArtifactGateway(ObjectStore store, BootstrapTransfer transport, Clock clock) {
        this.store = Objects.requireNonNull(store); this.transport = Objects.requireNonNull(transport); this.clock = Objects.requireNonNull(clock);
    }
    @Override public InstallPlan inspectConfiguration(BuildSnapshot build, CancellationToken token) {
        var request = CdnRequest.configuration(PinnedBuildSource.resolve(build), build.buildConfig());
        var acquired = fetch(request, token);
        return parsePlan(build, new SourceDocument(request.uri().toString(), acquired.receivedAt(), acquired.bytes()), token);
    }
    @Override public void validatePlan(InstallPlan plan, CancellationToken token) {
        var expected = CdnRequest.configuration(PinnedBuildSource.resolve(plan.build()), plan.build().buildConfig()).uri().toString();
        if (!plan.configuration().sourceUrl().equals(expected)) throw failure(BootstrapException.Reason.INTEGRITY, "Fresh plan proof does not match selected captured source");
        if (!parsePlan(plan.build(), plan.configuration(), token).equals(plan))
            throw failure(BootstrapException.Reason.INTEGRITY, "Install plan does not match pinned configuration");
    }
    @Override public VerifiedSourceAlias retainConfiguration(InstallPlan plan, CancellationToken token) {
        validatePlan(plan, token);
        var object = publish(plan.configuration().bytes(), plan.build().buildConfig(), ChecksumAlgorithm.MD5, token);
        return new VerifiedSourceAlias(configurationIdentity(plan.build()), object, new SourceVerificationEvidence(CONFIG_SEMANTICS, plan.configuration().sourceUrl(), plan.configuration().capturedAt()));
    }
    @Override public InstallPlan restoreConfiguration(BuildSnapshot build, VerifiedSourceAlias artifact, CancellationToken token) {
        if (!artifact.source().equals(configurationIdentity(build))) throw failure(BootstrapException.Reason.INTEGRITY, "Configuration artifact identity mismatch");
        var evidence = artifact.evidence();
        return parsePlan(build, new SourceDocument(evidence.sourceUrl(), evidence.verifiedAt(), read(artifact.object(), 1024 * 1024, token)), token);
    }
    @Override public VerifiedSourceAlias acquireInstall(InstallPlan plan, CancellationToken token) {
        // Application has authorized either a fresh selected route or an exact canonical retained receipt.
        if (!parsePlan(plan.build(), plan.configuration(), token).equals(plan)) throw failure(BootstrapException.Reason.INTEGRITY, "Install plan content mismatch");
        var request = CdnRequest.loose(PinnedBuildSource.resolve(plan.build()), plan.encodingKey(), plan.encodedBytes(), MAXIMUM_ENCODED_BYTES);
        var acquired = fetch(request, token); var bytes = acquired.bytes();
        decodeAndParse(bytes, plan.decodedBytes(), plan.encodingKey(), plan.contentKey(), token);
        token.throwIfCancellationRequested();
        var object = publish(bytes, acquired.sha256(), ChecksumAlgorithm.SHA256, token);
        return new VerifiedSourceAlias(installIdentity(plan), object, new SourceVerificationEvidence(INSTALL_SEMANTICS, request.uri().toString(), clock.instant()));
    }
    @Override public InstallInspection inspectRetained(InstallPlan plan, VerifiedSourceAlias artifact, CancellationToken token) {
        var object = artifact.object();
        if (!artifact.source().equals(installIdentity(plan)) || !artifact.evidence().verifierSemantics().equals(INSTALL_SEMANTICS))
            throw failure(BootstrapException.Reason.UNSUPPORTED, "Unknown retained verification semantics");
        if (object.length() != plan.encodedBytes()) throw failure(BootstrapException.Reason.INTEGRITY, "Retained encoded size mismatch");
        var manifest = decodeAndParse(read(object, MAXIMUM_ENCODED_BYTES, token), plan.decodedBytes(), plan.encodingKey(), plan.contentKey(), token);
        var entries = new ArrayList<InstallInspection.Entry>();
        for (int i = 0; i < manifest.entries().size(); i++) {
            token.throwIfCancellationRequested(); var entry = manifest.entries().get(i);
            entries.add(new InstallInspection.Entry(entry.name(), entry.contentKey(), entry.decodedSize(),
                    manifest.tagsForEntry(i).stream().map(tag -> new InstallInspection.Tag(tag.name(), tag.type())).toList()));
        }
        return new InstallInspection(plan, object, artifact.evidence(), entries, manifest.tags().stream().map(tag -> new InstallInspection.Tag(tag.name(), tag.type())).toList());
    }
    private InstallPlan parsePlan(BuildSnapshot build, SourceDocument document, CancellationToken token) {
        PinnedBuildSource.resolve(build);
        PinnedBuildSource.validateConfigurationProof(document.sourceUrl(), build.buildConfig());
        try {
            var config = new TactConfigParser().parse(document.bytes(), build.buildConfig(), CONFIG_LIMITS, token::isCancellationRequested);
            var bootstrap = config.installBootstrap();
            return new InstallPlan(build, document, bootstrap.contentKey(), bootstrap.encodingKey(), bootstrap.decodedSize(), bootstrap.encodedSize());
        } catch (TactConfigException error) { throw protocol(error.reason().name(), error); }
        catch (IllegalArgumentException error) { throw new BootstrapException(BootstrapException.Reason.MALFORMED, "Invalid pinned install-manifest plan", error); }
    }
    private InstallManifest decodeAndParse(byte[] encoded, long decodedSize, String encodingKey, String contentKey, CancellationToken token) {
        try {
            var decoded = new BlteDecoder().decode(encoded, decodedSize, HexFormat.of().parseHex(encodingKey), HexFormat.of().parseHex(contentKey), BLTE_LIMITS, token::isCancellationRequested);
            var manifest = new InstallManifestParser().parse(decoded, INSTALL_LIMITS, token::isCancellationRequested);
            long relationships = 0;
            for (int i = 0; i < manifest.entries().size(); i++) {
                token.throwIfCancellationRequested();
                for (var tag : manifest.tags()) if (tag.includesEntry(i) && ++relationships > 250000)
                    throw failure(BootstrapException.Reason.LIMIT, "Install tag memberships exceed inspection budget");
            }
            return manifest;
        } catch (BlteException error) { throw protocol(error.reason().name(), error); }
        catch (InstallManifestException error) { throw protocol(error.reason().name(), error); }
    }
    private AcquiredBytes fetch(CdnRequest request, CancellationToken token) {
        var wire = transport.fetch(request);
        var acquired = await(wire, token, () -> wire.cancel(true));
        if (!request.equals(acquired.request()) || acquired.bytes().length > request.maximumBytes()
                || (request.exactBytes() >= 0 && acquired.bytes().length != request.exactBytes()))
            throw failure(BootstrapException.Reason.INTEGRITY, "Transport returned unrelated or incorrectly sized content");
        return acquired;
    }
    private StoredObject publish(byte[] bytes, String expected, ChecksumAlgorithm algorithm, CancellationToken token) {
        return store.publish(new ByteArrayInputStream(bytes), new ExpectedObjectChecksum(algorithm, expected, bytes.length), bytes.length, token);
    }
    private byte[] read(StoredObject object, int bound, CancellationToken token) {
        if (object.length() > bound) throw failure(BootstrapException.Reason.LIMIT, "Retained object exceeds scope budget");
        try (var lease = store.openRead(object.digest(), token)) {
            byte[] bytes = lease.stream().readNBytes(bound + 1); token.throwIfCancellationRequested();
            if (bytes.length != object.length()) throw failure(BootstrapException.Reason.INTEGRITY, "Retained object length mismatch");
            return bytes;
        } catch (IOException error) { throw new BootstrapException(BootstrapException.Reason.STORAGE, "Could not read retained scope", error); }
    }
    @Override public ResolvedSourceIdentity configurationIdentity(BuildSnapshot build) { return new ResolvedSourceIdentity("tact.config.raw-md5", build.buildConfig(), CONFIG_SEMANTICS); }
    @Override public ResolvedSourceIdentity installIdentity(InstallPlan plan) { return new ResolvedSourceIdentity("tact.install.v1", String.join(";", plan.contentKey(), plan.encodingKey(), Long.toString(plan.decodedBytes()), Long.toString(plan.encodedBytes())), INSTALL_SEMANTICS); }
    private static <T> T await(CompletableFuture<T> future, CancellationToken token, Runnable cancel) {
        try {
            while (true) {
                token.throwIfCancellationRequested();
                try { return future.get(100, TimeUnit.MILLISECONDS); } catch (TimeoutException waiting) { /* bounded cancellation observation */ }
            }
        } catch (InterruptedException interrupted) { cancel.run(); Thread.currentThread().interrupt(); throw new CancellationException("Install scope cancelled"); }
        catch (CancellationException cancelled) { cancel.run(); throw cancelled; }
        catch (ExecutionException error) { throw classify(error.getCause()); }
    }
    private static BootstrapException protocol(String reason, Exception error) {
        var mapped = reason.equals("MISSING_FIELD") ? BootstrapException.Reason.UNSUPPORTED : BootstrapException.Reason.valueOf(reason);
        return new BootstrapException(mapped, error.getMessage(), error);
    }
    private static RuntimeException classify(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        if (error instanceof BootstrapException scoped) return scoped;
        if (error instanceof CancellationException) return new BootstrapException(BootstrapException.Reason.CANCELLED, "Install scope cancelled", error);
        if (error instanceof io.vibeminer.infrastructure.acquisition.AcquisitionException source) {
            var reason = switch (source.reason()) { case UNAVAILABLE -> BootstrapException.Reason.UNAVAILABLE; case LIMIT -> BootstrapException.Reason.LIMIT; case INTEGRITY -> BootstrapException.Reason.INTEGRITY; case CANCELLED -> BootstrapException.Reason.CANCELLED; default -> BootstrapException.Reason.TRANSPORT; };
            return new BootstrapException(reason, source.getMessage(), source);
        }
        if (error instanceof ObjectStoreException storage) return new BootstrapException(storage.code() == ObjectStoreException.Code.INTEGRITY_FAILURE ? BootstrapException.Reason.INTEGRITY : BootstrapException.Reason.STORAGE, storage.getMessage(), storage);
        return new BootstrapException(BootstrapException.Reason.MALFORMED, "Pinned install scope could not be interpreted", error);
    }
    private static BootstrapException failure(BootstrapException.Reason reason, String message) { return new BootstrapException(reason, message); }
}
