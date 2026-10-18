package io.vibeminer.infrastructure.acquisition;

import static io.vibeminer.infrastructure.acquisition.AcquisitionException.Reason.*;
import static io.vibeminer.infrastructure.acquisition.PinnedCdnTransportTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class AcquisitionHttpStatusTest {
  @Test
  void legacyConstructorsPreserveReasonMessageCauseAndUnknownStatus() {
    var cause = new IllegalStateException("wire failure");
    var failure = new AcquisitionException(TRANSPORT, "unchanged", cause);
    assertEquals(TRANSPORT, failure.reason());
    assertEquals("unchanged", failure.getMessage());
    assertSame(cause, failure.getCause());
    assertTrue(failure.httpStatus().isEmpty());
    assertTrue(new AcquisitionException(UNAVAILABLE, "unknown").httpStatus().isEmpty());
  }

  @Test
  void knownStatusIsValidatedWithoutChangingCauseOrClassification() {
    var cause = new IllegalStateException("underlying");
    var failure = new AcquisitionException(UNAVAILABLE, "unchanged", cause, 404);
    assertEquals(404, failure.httpStatus().orElseThrow());
    assertSame(cause, failure.getCause());
    assertEquals(UNAVAILABLE, failure.reason());
    assertEquals("unchanged", failure.getMessage());
    for (int status : new int[] {100, 200, 599})
      assertEquals(
          status, new AcquisitionException(TRANSPORT, "known", status).httpStatus().orElseThrow());
    for (int status : new int[] {-1, 0, 99, 600, Integer.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class,
          () -> new AcquisitionException(TRANSPORT, "invalid", status));
  }

  @Test
  void bothWholeObjectExchangePathsPreserveStatusWithoutRetrying() throws Exception {
    for (int status : new int[] {403, 404, 410, 416, 503, 302}) {
      var request = config(new byte[] {1});
      var injected = failure(transport(status, "identity", 0, new byte[0]).fetch(request));
      assertEquals(status, injected.httpStatus().orElseThrow());
      assertEquals(status == 404 || status == 410 ? UNAVAILABLE : TRANSPORT, injected.reason());
      var client = new CountingClient(status);
      var actual =
          failure(new PinnedCdnTransport(client, CLOCK, Duration.ofSeconds(1)).fetch(request));
      assertEquals(status, actual.httpStatus().orElseThrow());
      assertEquals(injected.reason(), actual.reason());
      assertEquals(1, client.calls);
      assertEquals(0, client.demand);
      assertTrue(client.subscriptionCancelled);
    }
  }

  @Test
  void exactRangesAndMetadataDistinguish404410416503AndIgnored200() {
    var range = new CdnRangeRequest(SOURCE, "a".repeat(32), 100, 0, 3, 3);
    var resource = new CdnResource(SOURCE, "a".repeat(32), CdnResource.Kind.INDEX);
    for (int status : new int[] {403, 404, 410, 416, 503, 200, 302}) {
      var expected =
          status == 200
              ? INTEGRITY
              : status == 404 || status == 410 || status == 416 ? UNAVAILABLE : TRANSPORT;
      var rangeClient = new CountingClient(status);
      var ranged =
          failure(
              new PinnedCdnRangeTransport(rangeClient, CLOCK, Duration.ofSeconds(1)).fetch(range));
      assertEquals(status, ranged.httpStatus().orElseThrow());
      assertEquals(expected, ranged.reason());
      assertEquals(1, rangeClient.calls);
      assertEquals(0, rangeClient.demand);
      var footerClient = new CountingClient(status);
      var footer =
          failure(
              new CdnMetadataTransport(footerClient, CLOCK, Duration.ofSeconds(1))
                  .footer(new CdnIndexFooterRequest(resource, 1000)));
      assertEquals(status, footer.httpStatus().orElseThrow());
      assertEquals(expected, footer.reason());
      assertEquals(1, footerClient.calls);
      assertEquals(0, footerClient.demand);
      if (status != 200) {
        var headClient = new CountingClient(status);
        var head =
            failure(
                new CdnMetadataTransport(headClient, CLOCK, Duration.ofSeconds(1))
                    .size(
                        new CdnSizeRequest(
                            new CdnResource(SOURCE, resource.key(), CdnResource.Kind.DATA), 1000)));
        assertEquals(status, head.httpStatus().orElseThrow());
        assertEquals(expected, head.reason());
        assertEquals(1, headClient.calls);
        assertEquals(0, headClient.demand);
      }
    }
  }

  @Test
  void wireFailureHasNoInventedStatus() throws Exception {
    var cause = new java.io.IOException("unknown response");
    var transport =
        new PinnedCdnTransport(
            (request, deadline) -> CompletableFuture.failedFuture(cause),
            CLOCK,
            Duration.ofSeconds(1));
    var error = failure(transport.fetch(config(new byte[] {1})));
    assertTrue(error.httpStatus().isEmpty());
    assertSame(cause, error.getCause());
  }

  @Test
  void concreteSuccessStatusSurvivesRepresentationIntegrityFailure() throws Exception {
    var configFailure =
        failure(transport(200, "identity", 1, new byte[] {2}).fetch(config(new byte[] {1})));
    assertEquals(INTEGRITY, configFailure.reason());
    assertEquals(200, configFailure.httpStatus().orElseThrow());
    assertEquals("Pinned configuration MD5 mismatch", configFailure.getMessage());
    var range = new CdnRangeRequest(SOURCE, "a".repeat(32), 100, 0, 3, 3);
    var client =
        new CdnHttpExchangeTest.ControlledClient(
            206,
            Map.of("Content-Range", List.of("bytes 999999999999999999999999999-2/100")),
            new byte[0],
            false);
    var rangeFailure =
        failure(new PinnedCdnRangeTransport(client, CLOCK, Duration.ofSeconds(1)).fetch(range));
    assertEquals(INTEGRITY, rangeFailure.reason());
    assertEquals(206, rangeFailure.httpStatus().orElseThrow());
    assertInstanceOf(NumberFormatException.class, rangeFailure.getCause());
  }

  private static final class CountingClient extends CdnHttpExchangeTest.ControlledClient {
    int calls;

    CountingClient(int status) {
      super(status, Map.of(), new byte[0], false);
    }

    @Override
    public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
        java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler) {
      calls++;
      return super.sendAsync(request, handler);
    }
  }

  private static AcquisitionException failure(CompletableFuture<?> future) {
    return assertInstanceOf(
        AcquisitionException.class,
        assertThrows(CompletionException.class, future::join).getCause());
  }
}
