package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.infrastructure.testing.PublisherTrackingStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LookupPublisherAdmissionTest {
  @TempDir Path directory;

  @Test
  void rootWorkerBlockedBeforeAnyPayloadCasUseExcludesLateStartup() throws Exception {
    var fixture = new RootLookupFixture();
    var entered = new CountDownLatch(1);
    var pending = new CompletableFuture<AcquiredRange>();
    var cancelled = new AtomicBoolean();
    try (var actual = new FileObjectStore(directory.resolve("bytes"));
        var fence = new FileObjectStore(directory.resolve("fence"));
        var worker = Executors.newSingleThreadExecutor()) {
      var tracked = new PublisherTrackingStore(actual, fence);
      LookupTransport transport =
          new LookupTransport() {
            public CompletableFuture<AcquiredBytes> configuration(CdnRequest r) {
              return fixture.configuration(r);
            }

            public CompletableFuture<AcquiredRange> range(CdnRangeRequest r) {
              if (r.encodedKey().equals(fixture.rootEKey)) {
                entered.countDown();
                return pending;
              }
              return fixture.range(r);
            }
          };
      try (var gateway = new OfficialFileLookupGateway(tracked, transport, Clock.systemUTC())) {
        var plan =
            gateway.prepareRoot(
                fixture.build("us.cdn.blizzard.com", "1.0"),
                Optional.empty(),
                LookupBudgets.defaults(),
                CancellationToken.none());
        assertEquals(0, tracked.starts.get());
        var job =
            worker.submit(
                () ->
                    gateway.acquireRoot(
                        new RootSelectionPlan(plan, plan.candidates().getFirst()), cancelled::get));
        try {
          assertTrue(entered.await(2, TimeUnit.SECONDS));
          assertEquals(1, tracked.starts.get());
          assertThrows(
              IllegalStateException.class,
              () -> fence.contentSession().recoverAtStartup(() -> fail("late recovery")));
        } finally {
          cancelled.set(true);
          pending.cancel(true);
        }
        assertThrows(ExecutionException.class, () -> job.get(2, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void selectedGatewayMarksBeforeItsFirstPayloadRequest() throws Exception {
    try (var setup =
            new OfficialSelectedFileGatewayTest.Setup(directory.resolve("fixture"), false);
        var fence = new FileObjectStore(directory.resolve("fence"))) {
      var tracked = new PublisherTrackingStore(setup.store, fence);
      var client = setup.fixture.client();
      var clock = Clock.systemUTC();
      try (var gateway =
          new OfficialSelectedFileGateway(
              tracked,
              new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
              new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1)),
              new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
              clock)) {
        var candidates =
            gateway.prepareCandidates(
                setup.build,
                setup.config,
                setup.root,
                setup.variant,
                SelectedFileBudgets.defaults(),
                CancellationToken.none());
        var plan =
            gateway
                .probeLoose(
                    OfficialSelectedFileGatewayTest.choice(candidates, setup.fixture.selectedEKey),
                    CancellationToken.none())
                .located()
                .orElseThrow();
        assertEquals(0, tracked.starts.get());
        var observed = new AtomicBoolean();
        setup.fixture.requestObserver =
            r -> {
              if (r.uri().getPath().endsWith(setup.fixture.selectedEKey)) {
                observed.set(true);
                assertEquals(1, tracked.starts.get());
                assertThrows(
                    IllegalStateException.class,
                    () -> fence.contentSession().recoverAtStartup(() -> fail("late recovery")));
              }
            };
        gateway.acquireEncoded(plan, p -> {}, CancellationToken.none());
        assertTrue(observed.get());
        assertEquals(1, tracked.starts.get());
      }
    }
  }
}
