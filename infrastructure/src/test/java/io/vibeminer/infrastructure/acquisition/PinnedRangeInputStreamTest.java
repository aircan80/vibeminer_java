package io.vibeminer.infrastructure.acquisition;

import static io.vibeminer.infrastructure.acquisition.PinnedCdnTransportTest.SOURCE;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class PinnedRangeInputStreamTest {
  private static final CdnResource RESOURCE =
      new CdnResource(SOURCE, "a".repeat(32), CdnResource.Kind.DATA);
  private static final PinnedRangeInputStream.Limits LIMITS =
      new PinnedRangeInputStream.Limits(100, 20, Duration.ofSeconds(2));

  @Test
  void constructorAvailableAndLogicalEofNeverAcquireOrReadOutsideTheArchiveChild()
      throws Exception {
    var requests = new ArrayList<CdnRangeRequest>();
    try (var stream = stream(requests, 1000, 100, 7, 3, LIMITS)) {
      assertEquals(0, stream.available());
      assertTrue(requests.isEmpty());
      assertEquals(100, stream.read());
      assertEquals(2, stream.available());
      assertArrayEquals(new byte[] {101, 102, 103, 104, 105, 106}, stream.readAllBytes());
      assertEquals(-1, stream.read());
      assertEquals(0, stream.skip(100));
      assertEquals(-1, stream.read());
      assertEquals(
          List.of(100L, 103L, 106L), requests.stream().map(CdnRangeRequest::start).toList());
      assertEquals(List.of(3, 3, 1), requests.stream().map(CdnRangeRequest::count).toList());
      assertTrue(
          requests.stream()
              .allMatch(r -> r.wholeEncodedLength() == 1000 && r.endInclusive() < 107));
    }
  }

  @Test
  void shortCallerReadsReuseOneSliceAndSkipStaysInsideChild() throws Exception {
    var requests = new ArrayList<CdnRangeRequest>();
    try (var stream = stream(requests, 100, 10, 8, 4, LIMITS)) {
      var one = new byte[1];
      assertEquals(1, stream.read(one));
      assertEquals(10, one[0]);
      assertEquals(1, stream.read(one));
      assertEquals(11, one[0]);
      assertEquals(1, requests.size());
      assertEquals(6, stream.skip(Long.MAX_VALUE));
      assertEquals(-1, stream.read());
      assertEquals(2, requests.size());
      assertEquals(4, requests.get(1).count());
      assertEquals(0, stream.read(new byte[1], 0, 0));
    }
  }

  @Test
  void emptySpanIsLazyAndBadGeometryOverflowAndSliceCapsAreRejected() throws Exception {
    var requests = new ArrayList<CdnRangeRequest>();
    try (var stream = stream(requests, 100, 100, 0, 4, LIMITS)) {
      assertEquals(-1, stream.read());
      assertTrue(requests.isEmpty());
    }
    for (long[] span :
        new long[][] {
          {100, -1, 1}, {100, 99, 2}, {Long.MAX_VALUE, Long.MAX_VALUE - 1, 3}, {0, 0, 0}
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> stream(requests, span[0], span[1], span[2], 4, LIMITS));
    for (int cap : new int[] {0, -1, 4 * 1024 * 1024 + 1})
      assertThrows(IllegalArgumentException.class, () -> stream(requests, 100, 0, 1, cap, LIMITS));
  }

  @Test
  void byteRequestAndTimeBudgetsFailBeforeAnotherRequestAndRemainTerminal() throws Exception {
    for (var limits :
        List.of(
            new PinnedRangeInputStream.Limits(3, 20, Duration.ofSeconds(2)),
            new PinnedRangeInputStream.Limits(100, 1, Duration.ofSeconds(2)))) {
      var requests = new ArrayList<CdnRangeRequest>();
      try (var stream = stream(requests, 100, 0, 6, 3, limits)) {
        assertArrayEquals(new byte[] {0, 1, 2}, stream.readNBytes(3));
        assertThrows(IOException.class, stream::read);
        assertThrows(IOException.class, stream::read);
        assertEquals(1, requests.size());
      }
    }
    var clock = new AtomicLong();
    var calls = new AtomicInteger();
    try (var stream =
        new PinnedRangeInputStream(
            r -> {
              calls.incrementAndGet();
              return CompletableFuture.completedFuture(receipt(r));
            },
            RESOURCE,
            100,
            0,
            6,
            3,
            LIMITS,
            () -> false,
            clock::get)) {
      clock.set(Duration.ofSeconds(3).toNanos());
      assertThrows(IOException.class, stream::read);
      assertEquals(0, calls.get());
    }
  }

  @Test
  void mismatchedSourceTotalsSpanAndTruncatedReceiptAreTerminal() throws Exception {
    for (int kind = 0; kind < 4; kind++) {
      int mismatch = kind;
      var calls = new AtomicInteger();
      try (var stream =
          new PinnedRangeInputStream(
              r -> {
                calls.incrementAndGet();
                var resource =
                    mismatch == 0
                        ? new CdnResource(SOURCE, "b".repeat(32), CdnResource.Kind.DATA)
                        : RESOURCE;
                var wrong =
                    new CdnRangeRequest(
                        resource,
                        mismatch == 1 ? 101 : 100,
                        mismatch == 2 ? 1 : 0,
                        mismatch == 3 ? 2 : 3,
                        3);
                return CompletableFuture.completedFuture(receipt(wrong));
              },
              RESOURCE,
              100,
              0,
              3,
              3,
              LIMITS,
              () -> false,
              System::nanoTime)) {
        assertThrows(IOException.class, stream::read);
        assertThrows(IOException.class, stream::read);
        assertEquals(1, calls.get());
      }
    }
  }

  @Test
  void cancellationWhileWaitingCancelsFutureAndCannotRetry() throws Exception {
    var cancelled = new AtomicBoolean();
    var pending = new CompletableFuture<AcquiredRange>();
    var entered = new CountDownLatch(1);
    try (var stream =
            new PinnedRangeInputStream(
                r -> {
                  entered.countDown();
                  return pending;
                },
                RESOURCE,
                100,
                0,
                3,
                3,
                LIMITS,
                cancelled::get,
                System::nanoTime);
        var worker = Executors.newSingleThreadExecutor()) {
      var read = worker.submit(() -> assertThrows(IOException.class, stream::read));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      cancelled.set(true);
      read.get(1, TimeUnit.SECONDS);
      assertTrue(pending.isCancelled());
      assertThrows(IOException.class, stream::read);
    }
  }

  @Test
  void closeCancelsPendingReadWithoutWaitingAndLateRegistrationAlsoCancels() throws Exception {
    for (boolean delayRegistration : new boolean[] {false, true}) {
      var pending = new CompletableFuture<AcquiredRange>();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(delayRegistration ? 1 : 0);
      var stream =
          new PinnedRangeInputStream(
              r -> {
                entered.countDown();
                try {
                  assertTrue(release.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  throw new AssertionError(e);
                }
                return pending;
              },
              RESOURCE,
              100,
              0,
              3,
              3,
              LIMITS,
              () -> false,
              System::nanoTime);
      try (var worker = Executors.newSingleThreadExecutor()) {
        var read = worker.submit(() -> assertThrows(IOException.class, stream::read));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        stream.close();
        release.countDown();
        read.get(1, TimeUnit.SECONDS);
        assertTrue(pending.isCancelled());
        assertThrows(IOException.class, stream::read);
      } finally {
        release.countDown();
        stream.close();
      }
    }
  }

  @Test
  void deadlineWhileWaitingAndCancellationBeforeFirstReadNeverRetry() throws Exception {
    var clock = new AtomicLong();
    var pending = new CompletableFuture<AcquiredRange>();
    var entered = new CountDownLatch(1);
    try (var stream =
            new PinnedRangeInputStream(
                r -> {
                  entered.countDown();
                  return pending;
                },
                RESOURCE,
                100,
                0,
                3,
                3,
                LIMITS,
                () -> false,
                clock::get);
        var worker = Executors.newSingleThreadExecutor()) {
      var read = worker.submit(() -> assertThrows(IOException.class, stream::read));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      clock.set(Duration.ofSeconds(3).toNanos());
      read.get(1, TimeUnit.SECONDS);
      assertTrue(pending.isCancelled());
    }
    var calls = new AtomicInteger();
    try (var stream =
        new PinnedRangeInputStream(
            r -> {
              calls.incrementAndGet();
              return CompletableFuture.completedFuture(receipt(r));
            },
            RESOURCE,
            100,
            0,
            3,
            3,
            LIMITS,
            () -> true,
            System::nanoTime)) {
      assertThrows(IOException.class, stream::read);
      assertEquals(0, calls.get());
    }
  }

  @Test
  void failedOrMalformedCompletionKeepsCauseAndNeverStartsAnotherRequest() throws Exception {
    var original =
        new AcquisitionException(AcquisitionException.Reason.UNAVAILABLE, "exact404", 404);
    for (boolean malformed : new boolean[] {false, true}) {
      var calls = new AtomicInteger();
      try (var stream =
          new PinnedRangeInputStream(
              r -> {
                calls.incrementAndGet();
                return malformed
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(original);
              },
              RESOURCE,
              100,
              0,
              3,
              3,
              LIMITS,
              () -> false,
              System::nanoTime)) {
        var first = assertThrows(IOException.class, stream::read);
        assertSame(first, assertThrows(IOException.class, stream::read));
        assertEquals(1, calls.get());
        if (!malformed) assertSame(original, first.getCause());
      }
    }
  }

  @Test
  void publicTransportConstructorIsLazyAndClosingLeavesSharedTransportUsable() throws Exception {
    var client =
        new CdnHttpExchangeTest.ControlledClient(
            206,
            Map.of("Content-Range", List.of("bytes 100-102/1000")),
            new byte[] {1, 2, 3},
            false);
    var transport =
        new PinnedCdnRangeTransport(client, PinnedCdnTransportTest.CLOCK, Duration.ofSeconds(1));
    try (var stream =
        new PinnedRangeInputStream(transport, RESOURCE, 1000, 100, 3, 3, LIMITS, () -> false)) {
      assertNull(client.request);
      assertEquals(0, stream.available());
      assertNull(client.request);
      assertArrayEquals(new byte[] {1, 2, 3}, stream.readAllBytes());
    }
    assertArrayEquals(
        new byte[] {1, 2, 3},
        transport.fetch(new CdnRangeRequest(RESOURCE, 1000, 100, 3, 3)).join().bytes());
  }

  private static PinnedRangeInputStream stream(
      List<CdnRangeRequest> requests,
      long whole,
      long offset,
      long length,
      int cap,
      PinnedRangeInputStream.Limits limits) {
    return new PinnedRangeInputStream(
        r -> {
          requests.add(r);
          return CompletableFuture.completedFuture(receipt(r));
        },
        RESOURCE,
        whole,
        offset,
        length,
        cap,
        limits,
        () -> false,
        System::nanoTime);
  }

  private static AcquiredRange receipt(CdnRangeRequest request) {
    byte[] body = new byte[request.count()];
    for (int i = 0; i < body.length; i++) body[i] = (byte) (request.start() + i);
    return new AcquiredRange(request, request.uri(), Instant.EPOCH, body, "0".repeat(64));
  }
}
