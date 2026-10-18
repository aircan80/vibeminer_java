package io.vibeminer.infrastructure.acquisition;

import static io.vibeminer.infrastructure.acquisition.PinnedCdnTransportTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PinnedRangeObservationTest {
  @Test
  void observationsAreMetadataOnlyOncePerActualSliceAndNeverFromAvailableOrEof() throws Exception {
    var client =
        new CdnHttpExchangeTest.ControlledClient(
            206,
            Map.of("Content-Range", List.of("bytes 100-102/1000")),
            new byte[] {1, 2, 3},
            false);
    var observations = new ArrayList<PinnedRangeInputStream.RangeObservation>();
    var resource = new CdnResource(SOURCE, "a".repeat(32), CdnResource.Kind.DATA);
    try (var stream =
        new PinnedRangeInputStream(
            new PinnedCdnRangeTransport(client, CLOCK, Duration.ofSeconds(1)),
            resource,
            1000,
            100,
            3,
            3,
            new PinnedRangeInputStream.Limits(3, 1, Duration.ofSeconds(2)),
            () -> false,
            observations::add)) {
      assertEquals(0, stream.available());
      assertTrue(observations.isEmpty());
      assertEquals(1, stream.read());
      assertArrayEquals(new byte[] {2, 3}, stream.readAllBytes());
      assertEquals(-1, stream.read());
      assertEquals(1, observations.size());
      var observed = observations.getFirst();
      assertEquals(resource.uri(), observed.actualSourceUri());
      assertEquals(CLOCK.instant(), observed.receivedAt());
      assertEquals(3, observed.responseBytes());
      assertEquals(100, observed.request().start());
      assertEquals(1000, observed.request().wholeEncodedLength());
      assertEquals(64, observed.rangeSha256().length());
      assertTrue(
          Arrays.stream(PinnedRangeInputStream.RangeObservation.class.getRecordComponents())
              .noneMatch(f -> f.getType() == byte[].class));
    }
  }

  @Test
  void observerFailureIsTerminalBeforeBytesAreExposed() throws Exception {
    var client =
        new CdnHttpExchangeTest.ControlledClient(
            206, Map.of("Content-Range", List.of("bytes 0-2/3")), new byte[] {1, 2, 3}, false);
    var calls = new AtomicInteger();
    var cause = new IllegalStateException("synthetic receipt digest failure");
    try (var stream =
        new PinnedRangeInputStream(
            new PinnedCdnRangeTransport(client, CLOCK, Duration.ofSeconds(1)),
            new CdnResource(SOURCE, "a".repeat(32), CdnResource.Kind.DATA),
            3,
            0,
            3,
            3,
            new PinnedRangeInputStream.Limits(3, 1, Duration.ofSeconds(2)),
            () -> false,
            observation -> {
              calls.incrementAndGet();
              throw cause;
            })) {
      var target = new byte[] {9, 9, 9};
      var error = assertThrows(IOException.class, () -> stream.read(target));
      assertSame(cause, error.getCause());
      assertArrayEquals(new byte[] {9, 9, 9}, target);
      assertSame(error, assertThrows(IOException.class, stream::read));
      assertEquals(1, calls.get());
    }
  }
}
