package io.vibeminer.infrastructure.inventory;

import static io.vibeminer.infrastructure.inventory.SqliteBuildInventoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.buildoverview.SqliteBuildOverviewBackend;
import io.vibeminer.infrastructure.buildretention.*;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.encodedsource.*;
import io.vibeminer.infrastructure.encoding.SqliteEncodingLookupIndex;
import io.vibeminer.infrastructure.lookup.EncodedSourceTestHttp;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.InjectedPublicationFailureStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Injected atomic-promotion ENOSPC-like I/O; no physical volume is filled. */
class BuildRetentionPublicationFaultTest {
  @TempDir Path directory;

  @Test
  void promotionIoFailurePreservesDurableModeChargesAndOriginalObjectsWithoutPhantomFulfillment()
      throws Exception {
    publicationFailure(false);
  }

  @Test
  void exceptionAfterAtomicMoveRemainsPartialUntilAnExplicitChargedLocalRetry() throws Exception {
    publicationFailure(true);
  }

  void publicationFailure(boolean moved) throws Exception {
    var fault = new InjectedPublicationFailureStore(moved);
    var owner = new SqliteBuildInventoryTest();
    owner.directory = directory;
    var f =
        owner
        .new Fixture(
            false,
            false,
            false,
            false,
            true,
            false,
            path -> {
              var store = fault.create(path);
              SqliteCanonicalContentRepository.recoverAtStartup(
                  new SqliteCatalog(directory.resolve("catalog.sqlite")), store.contentSession());
              return store;
            });
    {
      var sources = Executors.newFixedThreadPool(4);
      var jobs = Executors.newFixedThreadPool(2);
      var scheduler = new PrioritySourceScheduler(sources);
      var files = new SqliteSelectedFileCatalog(f.sql, f.store, f.store.contentSession());
      var core = new EncodedSourceAcquisition(files, sources, scheduler);
      var payload = SelectedFileBudgets.defaults();
      var archives = ArchiveSearchBudgets.defaults();
      var inventory =
          new SqliteBuildRetentionInventory(
              f.sql,
              f.store,
              f.admissions,
              new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID()),
              budgets(),
              EN_LIMITS,
              new SqliteBuildRetentionInventory.ReservationPolicy(payload, archives));
      var http = new EncodedSourceTestHttp();
      http.object(f.payloadE, blte(new byte[100]));
      var clock = Clock.fixed(NOW, ZoneOffset.UTC);
      var whole = new PinnedCdnTransport(http.client(), clock, Duration.ofSeconds(1));
      var ranges = new PinnedCdnRangeTransport(http.client(), clock, Duration.ofSeconds(1));
      var metadata = new CdnMetadataTransport(http.client(), clock, Duration.ofSeconds(1));
      var publisher = new OfficialEncodedSourcePublisher(f.store, ranges);
      var actual =
          new OfficialBuildRetentionSource(
              inventory,
              new SqliteBuildRetentionSourceAssociations(f.sql, f.store),
              f.sql,
              files,
              core,
              new OfficialEncodedSourceLocator(whole, ranges, metadata),
              publisher,
              payload,
              archives);
      var observed = new AtomicReference<RuntimeException>();
      BuildRetentionSource observing =
          (target, member, demand, token) -> {
            try {
              return actual.retain(target, member, demand, token);
            } catch (RuntimeException failed) {
              observed.set(failed);
              throw failed;
            }
          };
      var ledger =
          new SqliteBuildRetentionRepository(f.sql, f.store, f.store.contentSession(), inventory);
      var pump = new BuildRetentionPump(ledger, observing, scheduler, jobs);
      try {
        var prepared = f.prepare();
        var target =
            inventory.issueTarget(
                f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), prepared, NONE);
        BuildRetention.Member member;
        try (var opened = inventory.openSealed(target, NONE)) {
          member = opened.members(0, 1, NONE).getFirst();
        }
        var previous = physicalObjects();
        var intent =
            ledger.setMode(
                target,
                0,
                BuildRetention.Mode.DOWNLOAD_ALL,
                new BuildRetention.Policy(1 << 20, 64L << 20, 1 << 20, 8192, 3),
                NOW,
                NONE);
        fault.enable();
        pump.resumeDownloadAll(intent);
        pump.completion(target, intent.generation())
            .orElseThrow()
            .toCompletableFuture()
            .get(8, TimeUnit.SECONDS);
        assertEquals(1, fault.failures());
        assertEquals(member.encodedBytes(), fault.stagedBytes());
        assertNotNull(observed.get());
        var typed = findCause(observed.get(), EncodedSourceException.class);
        assertEquals(EncodedSourceException.Code.STORAGE_UNAVAILABLE, typed.code());
        assertTrue(
            findCause(observed.get(), java.nio.file.FileSystemException.class)
                .getReason()
                .contains("ENOSPC"));
        var current = ledger.readIntent(target, NONE).orElseThrow();
        assertEquals(BuildRetention.Mode.DOWNLOAD_ALL, current.mode());
        assertEquals(member.cost(), current.reserved());
        var failed = ledger.pageJobs(target, -1, 10, NONE).getFirst();
        assertEquals(BuildRetention.State.FAILED, failed.state());
        assertEquals(BuildRetention.FailureCode.STORAGE, failed.failure().orElseThrow().code());
        assertTrue(failed.lease().isEmpty());
        assertTrue(failed.fulfillmentProof().isEmpty());
        var slot = files.lookup(new ContentSlotKey("wow", member.contentKey()), NONE).orElseThrow();
        assertEquals(
            io.vibeminer.domain.selectedfile.CanonicalReservation.Status.RECOVERY, slot.status());
        assertTrue(slot.representation().isPresent());
        assertEquals(
            moved,
            f.store
                .verify(slot.representation().orElseThrow().encoded().object().digest(), NONE)
                .isPresent());
        assertEquals(0, f.count("canonical_content_slots", "worker_nonce IS NOT NULL"));
        assertEquals(0, f.count("build_retention_sources", "1=1"));
        assertEquals(0, f.count("selected_file_associations", "1=1"));
        assertEquals(0, f.count("encoded_file_receipts", "1=1"));
        assertUnchanged(previous, moved);
        try (var stages = Files.list(directory.resolve("objects/work"))) {
          assertEquals(0, stages.count());
        }
        var limits =
            new EncodedSourcePlan.Limits(
                payload.lookup().maximumHeaderBytes(),
                payload.lookup().maximumChunks(),
                payload.maximumEncodedFileBytes(),
                payload.maximumPayloadRangeBytes(),
                payload.maximumPayloadRequests(),
                payload.maximumPayloadWireBytes(),
                payload.maximumPayloadDuration());
        var backend =
            new SqliteBuildOverviewBackend(
                f.sql, f.store, f.admissions, inventory, publisher, limits, clock);
        var request =
            new io.vibeminer.application.buildoverview.BuildOverview.Request(
                f.facts.reference(),
                f.facts.locale(),
                f.facts.scopeRevision(),
                new io.vibeminer.application.buildoverview.BuildOverview.Limits(
                    10000, 10000000, 128, 10000000, Duration.ofSeconds(30)),
                Optional.of(target));
        int requests = http.requests().size();
        var snapshot = backend.read(request, progress -> {}, NONE);
        assertEquals(
            io.vibeminer.application.buildoverview.BuildOverview.Readiness.PARTIAL,
            snapshot.readiness());
        assertEquals(0, snapshot.measurements().retainedUnits().orElseThrow());
        assertEquals(0, snapshot.measurements().retainedScopeEncodedBytes().orElseThrow());
        assertEquals(1, snapshot.retention().orElseThrow().failed().orElseThrow());
        assertEquals(0, snapshot.retention().orElseThrow().running().orElseThrow());
        assertEquals(0, snapshot.retention().orElseThrow().succeeded().orElseThrow());
        assertEquals(requests, http.requests().size());
        // No implicit retry: a second activation cannot refund or turn historical failure into
        // Full.
        fault.disable();
        pump.resumeDownloadAll(current);
        pump.completion(target, current.generation())
            .orElseThrow()
            .toCompletableFuture()
            .get(8, TimeUnit.SECONDS);
        assertEquals(requests, http.requests().size());
        assertEquals(member.cost(), ledger.readIntent(target, NONE).orElseThrow().reserved());
        assertEquals(
            BuildRetention.State.FAILED, ledger.pageJobs(target, -1, 10, NONE).getFirst().state());
        assertUnchanged(previous, moved);
        if (moved) {
          long payloadRequests =
              http.requests().stream().filter(r -> r.method().equals("GET")).count();
          var original = slot.representation().orElseThrow();
          ledger.retryFailed(target, current.generation(), member.memberId(), NONE);
          pump.resumeDownloadAll(current);
          pump.completion(target, current.generation())
              .orElseThrow()
              .toCompletableFuture()
              .get(8, TimeUnit.SECONDS);
          assertEquals(
              payloadRequests,
              http.requests().stream().filter(r -> r.method().equals("GET")).count());
          var succeeded = ledger.pageJobs(target, -1, 10, NONE).getFirst();
          assertEquals(BuildRetention.State.SUCCEEDED, succeeded.state());
          assertEquals(2, succeeded.attempts());
          assertEquals(
              new BuildRetention.Cost(
                  member.cost().wireBytes() * 2,
                  member.cost().additionalBytes() * 2,
                  member.cost().requests() * 2),
              ledger.readIntent(target, NONE).orElseThrow().reserved());
          assertEquals(
              original,
              files
                  .lookup(new ContentSlotKey("wow", member.contentKey()), NONE)
                  .orElseThrow()
                  .representation()
                  .orElseThrow());
          assertEquals(
              io.vibeminer.application.buildoverview.BuildOverview.Readiness.FULL,
              backend.read(request, progress -> {}, NONE).readiness());
          assertEquals(1, f.count("build_retention_sources", "1=1"));
          assertUnchanged(previous, true);
        }
      } finally {
        pump.close();
        core.close();
        scheduler.close();
        jobs.shutdown();
        sources.shutdown();
        assertTrue(
            jobs.awaitTermination(5, TimeUnit.SECONDS), "Actual bulk jobs still own storage");
        assertTrue(
            sources.awaitTermination(5, TimeUnit.SECONDS),
            "Actual source workers still own storage");
        inventory.close();
        f.close();
      }
    }
  }

  Map<Path, byte[]> physicalObjects() throws Exception {
    var result = new HashMap<Path, byte[]>();
    try (var paths = Files.walk(directory.resolve("objects/objects"))) {
      for (var path : paths.filter(Files::isRegularFile).toList())
        result.put(path, Files.readAllBytes(path));
    }
    return result;
  }

  void assertUnchanged(Map<Path, byte[]> expected, boolean payloadAdded) throws Exception {
    var actual = physicalObjects();
    assertEquals(expected.size() + (payloadAdded ? 1 : 0), actual.size());
    assertTrue(actual.keySet().containsAll(expected.keySet()));
    expected.forEach((path, bytes) -> assertArrayEquals(bytes, actual.get(path)));
  }

  static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
    for (var current = failure; current != null; current = current.getCause())
      if (type.isInstance(current)) return type.cast(current);
    throw new AssertionError("Missing failure type " + type.getName(), failure);
  }
}
