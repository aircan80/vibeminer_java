package io.vibeminer.infrastructure.inventory;

import static io.vibeminer.infrastructure.inventory.SqliteBuildInventoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.content.MetadataContentProof;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.bootstrap.InstallMetadataVerifier;
import io.vibeminer.infrastructure.buildopening.BuildMetadataAdmissionFacts;
import io.vibeminer.infrastructure.buildretention.*;
import io.vibeminer.infrastructure.encodedsource.*;
import io.vibeminer.infrastructure.encoding.SqliteEncodingLookupIndex;
import io.vibeminer.infrastructure.lookup.EncodedSourceTestHttp;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual sealed manifests/CAS/SQLite/source worker; all wire responses are authored in-process. */
class BuildRetentionSourceIntegrationTest {
  @TempDir Path directory;

  @Test
  void ownAdmittedMetadataMemberUsesItsActualMetadataProofWithoutTransferOrConversion()
      throws Exception {
    try (var h = new Harness(true)) {
      var before =
          h.f
              .canonical
              .lookup(new ContentSlotKey("wow", h.member.contentKey()), NONE)
              .orElseThrow();
      var original = (MetadataContentProof) before.proof().orElseThrow();
      assertEquals(47, original.object().length());
      assertEquals(10, original.decodedAttestation().orElseThrow().actualDecodedBytes());
      try (var lease = h.f.store.openRead(original.object().digest(), NONE)) {
        assertTrue(
            new InstallMetadataVerifier()
                .decodeAndParse(
                    lease.stream().readAllBytes(),
                    10,
                    original.actualEncodingKey(),
                    original.contentKey(),
                    NONE)
                .entries()
                .isEmpty());
      }
      var fact =
          h.f.facts.metadata().stream()
              .filter(value -> value.role() == BuildMetadataAdmission.Role.INSTALL)
              .findFirst()
              .orElseThrow();
      h.http.forbidNetwork();
      var proof = h.source.retain(h.target, h.member, h.bulk.demand(), NONE);
      assertEquals(
          SqliteBuildRetentionSourceAssociations.metadataFulfillmentProof(h.target, h.member, fact),
          proof);
      assertEquals(proof, h.source.retain(h.target, h.member, h.bulk.demand(), NONE));
      assertEquals(
          before,
          h.f
              .canonical
              .lookup(new ContentSlotKey("wow", h.member.contentKey()), NONE)
              .orElseThrow());
      assertEquals(0, h.f.count("build_retention_sources", "1=1"));
      assertEquals(0, h.f.count("encoded_file_receipts", "1=1"));
      assertEquals(0, h.http.requests().size());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new BuildMetadataAdmissionFacts.MetadataFact(
                  BuildMetadataAdmission.Role.ROOT,
                  fact.slot(),
                  fact.canonicalReservationToken(),
                  fact.proof()));
      var member = h.member;
      for (boolean changeContent : List.of(false, true)) {
        var forged =
            new BuildRetention.Member(
                member.ordinal(),
                member.memberId(),
                changeContent ? "a".repeat(32) : member.contentKey(),
                changeContent ? member.encodingKey() : "b".repeat(32),
                member.encodedBytes(),
                member.cost(),
                member.membershipCommitment());
        assertThrows(
            BuildRetentionException.class,
            () -> h.source.retain(h.target, forged, h.bulk.demand(), NONE));
      }
      var target = h.target;
      var wrongScope =
          new BuildRetention.Target(
              target.groupId(),
              target.snapshotId(),
              target.admissionId(),
              target.inventoryId(),
              target.inventoryGeneration(),
              "different-scope",
              target.scopeCommitment());
      assertThrows(
          BuildRetentionException.class,
          () -> h.source.retain(wrongScope, member, h.bulk.demand(), NONE));
    }
  }

  @Test
  void missingOrCorruptAdmittedMetadataCannotBecomeAnEncodedDownload() throws Exception {
    for (boolean missing : List.of(false, true))
      try (var h = new Harness(true)) {
        var original =
            (MetadataContentProof)
                h.f
                    .canonical
                    .lookup(new ContentSlotKey("wow", h.member.contentKey()), NONE)
                    .orElseThrow()
                    .proof()
                    .orElseThrow();
        h.inventory.close();
        var digest = original.object().digest().value();
        var path =
            h.owner
                .directory
                .resolve("objects/objects")
                .resolve(digest.substring(0, 2))
                .resolve(digest.substring(2));
        var bytes = Files.readAllBytes(path);
        if (missing) Files.delete(path);
        else {
          var corrupt = bytes.clone();
          corrupt[corrupt.length - 1] ^= 1;
          Files.write(path, corrupt);
        }
        h.http.forbidNetwork();
        try (var fresh = h.newInventory()) {
          var source = h.newSource(fresh);
          assertThrows(
              RuntimeException.class,
              () -> source.retain(h.target, h.member, h.bulk.demand(), NONE));
          assertEquals(0, h.http.requests().size());
          assertEquals(0, h.f.count("build_retention_sources", "1=1"));
          assertEquals(0, h.f.count("encoded_file_receipts", "1=1"));
        } finally {
          Files.write(path, bytes);
        }
      }
  }

  @Test
  void oneSealedPayloadRetainsAndReplaysWithoutFileIdOrAdditionalHttp() throws Exception {
    try (var h = new Harness()) {
      var proof = h.source.retain(h.target, h.member, h.bulk.demand(), NONE);
      assertTrue(proof.matches("[0-9a-f]{64}"));
      assertEquals(1, h.http.requests().stream().filter(r -> r.method().equals("GET")).count());
      assertEquals(1, h.http.requests().stream().filter(r -> r.method().equals("HEAD")).count());
      assertEquals(1, h.f.count("build_retention_sources", "1=1"));
      assertEquals(0, h.f.count("selected_file_associations", "1=1"));
      h.http.forbidNetwork();
      assertEquals(proof, h.source.retain(h.target, h.member, h.bulk.demand(), NONE));
      try (var authority = h.inventory.openSource(h.target, h.member, NONE)) {
        var actual =
            h.files
                .lookup(new ContentSlotKey("wow", h.member.contentKey()), NONE)
                .orElseThrow()
                .representation()
                .orElseThrow();
        assertEquals(
            Optional.of(proof), h.associations.find(authority, h.target, h.member, actual, NONE));
        var target = h.target;
        var forged =
            new BuildRetention.Target(
                "f".repeat(64),
                target.snapshotId(),
                target.admissionId(),
                target.inventoryId(),
                target.inventoryGeneration(),
                target.scopeRevision(),
                target.scopeCommitment());
        assertThrows(
            BuildRetentionException.class,
            () -> h.associations.attach(authority, forged, h.member, actual, NONE));
      }
      var m = h.member;
      var changed =
          new BuildRetention.Member(
              m.ordinal(),
              m.memberId(),
              m.contentKey(),
              "f".repeat(32),
              m.encodedBytes(),
              m.cost(),
              m.membershipCommitment());
      assertThrows(
          BuildRetentionException.class,
          () -> h.source.retain(h.target, changed, h.bulk.demand(), NONE));
      try (var connection = h.f.sql.connect();
          var statement = connection.createStatement()) {
        assertThrows(
            SQLException.class,
            () ->
                statement.executeUpdate(
                    "UPDATE build_retention_sources SET fulfillment_proof='"
                        + "e".repeat(64)
                        + "'"));
        assertThrows(
            SQLException.class,
            () -> statement.executeUpdate("DELETE FROM build_retention_sources"));
      }
      assertEquals(1, h.f.count("build_retention_sources", "1=1"));
    }
  }

  @Test
  void canceledBulkDoesNotFinishWhileItsIgnoredCancellationSourceStillOwnsTheNonce()
      throws Exception {
    try (var h = new Harness()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var caller = new AtomicReference<Thread>();
      h.http.observe(
          request -> {
            if (request.method().equals("GET")
                && request.uri().getPath().endsWith(h.member.encodingKey())) {
              entered.countDown();
              hold(release);
            }
          });
      var operation =
          CompletableFuture.supplyAsync(
              () -> {
                caller.set(Thread.currentThread());
                return h.source.retain(h.target, h.member, h.bulk.demand(), NONE);
              });
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        h.scheduler.cancelBulk(h.bulk);
        awaitRetirementWait(caller);
        assertFalse(operation.isDone());
        try (var connection = h.f.sql.connect();
            var q =
                connection.prepareStatement(
                    "SELECT worker_nonce FROM canonical_content_slots WHERE namespace='wow' AND"
                        + " content_key=?")) {
          q.setString(1, h.member.contentKey());
          try (var rows = q.executeQuery()) {
            assertTrue(rows.next());
            assertNotNull(rows.getString(1));
          }
        }
        assertEquals(0, h.f.count("build_retention_sources", "1=1"));
      } finally {
        release.countDown();
      }
      assertThrows(ExecutionException.class, () -> operation.get(3, TimeUnit.SECONDS));
      assertEquals(0, h.f.count("build_retention_sources", "1=1"));
      try (var connection = h.f.sql.connect();
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT count(*) FROM canonical_content_slots WHERE worker_nonce IS NOT NULL")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getLong(1));
      }
    }
  }

  private static void hold(CountDownLatch release) {
    boolean done = false;
    while (!done)
      try {
        assertTrue(release.await(5, TimeUnit.SECONDS));
        done = true;
      } catch (InterruptedException ignoredCancellation) {
      }
  }

  private static void awaitRetirementWait(AtomicReference<Thread> caller) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      var thread = caller.get();
      if (thread != null
          && Arrays.stream(thread.getStackTrace())
              .anyMatch(frame -> frame.getMethodName().equals("awaitRetirement"))) return;
      Thread.onSpinWait();
    }
    fail("Bulk bridge did not await actual source retirement");
  }

  private final class Harness implements AutoCloseable {
    final SqliteBuildInventoryTest owner = new SqliteBuildInventoryTest();
    final SqliteBuildInventoryTest.Fixture f;
    final EncodedSourceTestHttp http = new EncodedSourceTestHttp();
    final ExecutorService workers = Executors.newSingleThreadExecutor();
    final PrioritySourceScheduler scheduler = new PrioritySourceScheduler(workers);
    final SqliteSelectedFileCatalog files;
    final EncodedSourceAcquisition core;
    final SqliteBuildRetentionInventory inventory;
    final SqliteBuildRetentionSourceAssociations associations;
    final OfficialBuildRetentionSource source;
    final BuildRetention.Target target;
    final BuildRetention.Member member;
    final PrioritySourceScheduler.BulkOwner bulk;

    Harness() throws Exception {
      this(false);
    }

    Harness(boolean metadataMember) throws Exception {
      owner.directory = directory.resolve(UUID.randomUUID().toString());
      f = owner.new Fixture(false, false, false, false, true, metadataMember);
      try (var connection = f.sql.connect()) {
        BuildRetentionBindingSchema.migrate(connection);
        BuildRetentionSourceSchema.install(connection);
      }
      var payload = SelectedFileBudgets.defaults();
      var archives = ArchiveSearchBudgets.defaults();
      http.object(f.payloadE, blte(new byte[100]));
      var clock = Clock.fixed(NOW, ZoneOffset.UTC);
      var whole = new PinnedCdnTransport(http.client(), clock, Duration.ofSeconds(1));
      var ranges = new PinnedCdnRangeTransport(http.client(), clock, Duration.ofSeconds(1));
      var metadata = new CdnMetadataTransport(http.client(), clock, Duration.ofSeconds(1));
      files = new SqliteSelectedFileCatalog(f.sql, f.store, UUID.randomUUID());
      core = new EncodedSourceAcquisition(files, workers, scheduler);
      inventory =
          new SqliteBuildRetentionInventory(
              f.sql,
              f.store,
              f.admissions,
              new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID()),
              budgets(),
              EN_LIMITS,
              new SqliteBuildRetentionInventory.ReservationPolicy(payload, archives));
      var prepared = f.prepare();
      target =
          inventory.issueTarget(
              f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), prepared, NONE);
      try (var opened = inventory.openSealed(target, NONE)) {
        member = opened.members(0, 1, NONE).getFirst();
      }
      associations = new SqliteBuildRetentionSourceAssociations(f.sql, f.store);
      source =
          new OfficialBuildRetentionSource(
              inventory,
              associations,
              f.sql,
              files,
              core,
              new OfficialEncodedSourceLocator(whole, ranges, metadata),
              new OfficialEncodedSourcePublisher(f.store, ranges),
              payload,
              archives);
      bulk = scheduler.newBulkOwner(target.snapshotId());
    }

    SqliteBuildRetentionInventory newInventory() {
      return new SqliteBuildRetentionInventory(
          f.sql,
          f.store,
          f.admissions,
          new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID()),
          budgets(),
          EN_LIMITS,
          new SqliteBuildRetentionInventory.ReservationPolicy(
              SelectedFileBudgets.defaults(), ArchiveSearchBudgets.defaults()));
    }

    OfficialBuildRetentionSource newSource(SqliteBuildRetentionInventory authority) {
      var clock = Clock.fixed(NOW, ZoneOffset.UTC);
      var whole = new PinnedCdnTransport(http.client(), clock, Duration.ofSeconds(1));
      var ranges = new PinnedCdnRangeTransport(http.client(), clock, Duration.ofSeconds(1));
      var metadata = new CdnMetadataTransport(http.client(), clock, Duration.ofSeconds(1));
      return new OfficialBuildRetentionSource(
          authority,
          associations,
          f.sql,
          files,
          core,
          new OfficialEncodedSourceLocator(whole, ranges, metadata),
          new OfficialEncodedSourcePublisher(f.store, ranges),
          SelectedFileBudgets.defaults(),
          ArchiveSearchBudgets.defaults());
    }

    public void close() throws InterruptedException {
      core.close();
      scheduler.close();
      workers.shutdown();
      assertTrue(
          workers.awaitTermination(5, TimeUnit.SECONDS),
          "Actual source worker still owns resources");
      inventory.close();
      f.close();
    }
  }
}
