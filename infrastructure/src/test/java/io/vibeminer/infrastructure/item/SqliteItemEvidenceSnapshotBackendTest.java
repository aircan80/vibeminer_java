package io.vibeminer.infrastructure.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.itemexport.*;
import io.vibeminer.domain.item.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteItemEvidenceSnapshotBackendTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

  SqliteItemNamesBackendTest.Fixture fixture() throws Exception {
    var owner = new SqliteItemNamesBackendTest();
    owner.directory = directory;
    return owner.new Fixture();
  }

  SqliteItemEvidenceSnapshotBackend backend(SqliteItemNamesBackendTest.Fixture f, Clock clock) {
    return new SqliteItemEvidenceSnapshotBackend(
        f.catalog, f.store.contentSession(), f.items(), f.sparse(), clock);
  }

  @Test
  void capturesItemOnlyWithoutOpeningNamesOrRestampingHistoricalProof() throws Exception {
    try (var f = fixture()) {
      var pair = f.backend().prepare(f.request(), NONE);
      int before = f.verifies;
      var selection = new ItemEvidenceSelection(pair.item(), 101, Optional.empty());
      var snapshot = backend(f, Clock.fixed(NOW, ZoneOffset.UTC)).capture(selection, NONE);
      assertSame(selection, snapshot.selection());
      assertEquals(101, snapshot.row().recordId());
      assertTrue(snapshot.name().isEmpty());
      assertEquals(NOW, snapshot.verificationStartedAt());
      assertEquals(NOW, snapshot.verificationCompletedAt());
      assertEquals(pair.item().evidence(), snapshot.selection().item().evidence());
      assertEquals(before, f.verifies);
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
      assertEquals(64, snapshot.generationFingerprint().length());
    }
  }

  @Test
  void preservesPhysicalEmptyCopyAndScopedAbsentNames() throws Exception {
    try (var f = fixture()) {
      var pair = f.backend().prepare(f.request(), NONE);
      var b = backend(f, Clock.fixed(NOW, ZoneOffset.UTC));
      for (long id : new long[] {101, 202, 303, 404}) {
        var snapshot =
            b.capture(new ItemEvidenceSelection(pair.item(), id, Optional.of(pair)), NONE);
        var match = snapshot.name().orElseThrow();
        assertEquals(id, match.requestedId());
        if (id == 101) assertEquals("αZ", match.candidate().orElseThrow().value());
        if (id == 202) assertEquals("", match.candidate().orElseThrow().value());
        if (id == 303) {
          assertEquals(ItemNameMatch.Outcome.COPY_UNINDEXED, match.outcome());
          assertEquals(101, match.copySourceId().orElseThrow());
        }
        if (id == 404) assertEquals(ItemNameMatch.Outcome.NO_PHYSICAL_MATCH, match.outcome());
      }
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  @Test
  void copyOnlyAndMissingItemCannotBecomeNameOnlyExport() throws Exception {
    try (var f = fixture()) {
      var pair = f.backend().prepare(f.request(), NONE);
      var b = backend(f, Clock.fixed(NOW, ZoneOffset.UTC));
      for (long id : new long[] {999, 888})
        assertEquals(
            ItemEvidenceException.Code.STALE_SELECTION,
            assertThrows(
                    ItemEvidenceException.class,
                    () ->
                        b.capture(
                            new ItemEvidenceSelection(pair.item(), id, Optional.of(pair)), NONE))
                .code());
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  @Test
  void finalContextReadOccursAfterReplayWhileBothLeasesAreHeld() throws Exception {
    try (var f = fixture()) {
      var pair = f.backend().prepare(f.request(), NONE);
      f.beforeVerify =
          () -> {
            assertEquals(1, f.itemLeases);
            assertEquals(1, f.sparseLeases);
            try (var c = f.catalog.connect();
                var q = c.createStatement()) {
              q.executeUpdate("DELETE FROM sparse_name_contexts");
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          };
      assertEquals(
          ItemEvidenceException.Code.STALE_SELECTION,
          assertThrows(
                  ItemEvidenceException.class,
                  () ->
                      backend(f, Clock.fixed(NOW, ZoneOffset.UTC))
                          .capture(
                              new ItemEvidenceSelection(pair.item(), 101, Optional.of(pair)), NONE))
              .code());
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  @Test
  void clockRollbackAndCancellationIssueNoSnapshotAndReleaseLeases() throws Exception {
    try (var f = fixture()) {
      var pair = f.backend().prepare(f.request(), NONE);
      var ticks = new java.util.concurrent.atomic.AtomicInteger();
      Clock clock =
          new Clock() {
            public ZoneId getZone() {
              return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
              return this;
            }

            public Instant instant() {
              return ticks.getAndIncrement() == 0 ? NOW : NOW.minusSeconds(1);
            }
          };
      assertEquals(
          ItemEvidenceException.Code.STORAGE_UNAVAILABLE,
          assertThrows(
                  ItemEvidenceException.class,
                  () ->
                      backend(f, clock)
                          .capture(
                              new ItemEvidenceSelection(pair.item(), 101, Optional.of(pair)), NONE))
              .code());
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      f.beforeVerify = () -> cancelled.set(true);
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () ->
              backend(f, Clock.fixed(NOW, ZoneOffset.UTC))
                  .capture(
                      new ItemEvidenceSelection(pair.item(), 101, Optional.of(pair)),
                      cancelled::get));
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  @Test
  void corruptedSelectedDerivedRowFailsAndGenerationsAreObservedWithoutWrites() throws Exception {
    try (var f = fixture()) {
      var pair = f.backend().prepare(f.request(), NONE);
      var selection = new ItemEvidenceSelection(pair.item(), 101, Optional.of(pair));
      var first = backend(f, Clock.fixed(NOW, ZoneOffset.UTC)).capture(selection, NONE);
      assertEquals(
          first.generationFingerprint(),
          backend(f, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC))
              .capture(selection, NONE)
              .generationFingerprint());
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        q.executeUpdate(
            "UPDATE item_projection_rows SET row_sha256='"
                + "0".repeat(64)
                + "' WHERE record_id=101");
      }
      assertEquals(
          ItemEvidenceException.Code.CORRUPT,
          assertThrows(
                  ItemEvidenceException.class,
                  () -> backend(f, Clock.fixed(NOW, ZoneOffset.UTC)).capture(selection, NONE))
              .code());
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  @Test
  void realRetainedReadersCaptureAndRestartWithoutNetworkOrProjection() throws Exception {
    var owner = new RetainedItemNamesJourneyTest();
    owner.directory = directory;
    PreparedItemNames pair;
    String fingerprint;
    try (var f = owner.new Fixture(true)) {
      var item =
          f.itemBackend.prepare(
              new io.vibeminer.application.item.ItemProjectionRequest(
                  f.item.descriptor(), "enUS", RetainedItemNamesJourneyTest.ITEM_LIMITS),
              NONE);
      pair =
          f.backend.prepare(
              new io.vibeminer.application.itemnames.ItemNamesRequest(
                  item, f.sparse.descriptor(), "enUS", RetainedItemNamesJourneyTest.NAME_LIMITS),
              NONE);
      var snapshot =
          realBackend(f)
              .capture(new ItemEvidenceSelection(pair.item(), 101, Optional.of(pair)), NONE);
      assertEquals("Authored α", snapshot.name().orElseThrow().candidate().orElseThrow().value());
      assertEquals(pair.item().evidence(), snapshot.selection().item().evidence());
      fingerprint = snapshot.generationFingerprint();
      assertEquals(0, f.outbound.get());
      assertEquals(1, f.projects.get());
    }
    try (var f = owner.new Fixture(false)) {
      var snapshot =
          realBackend(f)
              .capture(new ItemEvidenceSelection(pair.item(), 101, Optional.of(pair)), NONE);
      assertEquals(fingerprint, snapshot.generationFingerprint());
      assertEquals(0, f.projects.get());
      assertEquals(0, f.outbound.get());
      var path = f.sourcePath(f.sparse.representation().encoded().object().digest());
      var bytes = java.nio.file.Files.readAllBytes(path);
      bytes[bytes.length - 1] ^= 1;
      java.nio.file.Files.write(path, bytes);
      assertEquals(
          ItemEvidenceException.Code.CORRUPT,
          assertThrows(
                  ItemEvidenceException.class,
                  () ->
                      realBackend(f)
                          .capture(
                              new ItemEvidenceSelection(pair.item(), 101, Optional.of(pair)), NONE))
              .code());
      java.nio.file.Files.delete(path);
      assertEquals(
          ItemEvidenceException.Code.MISSING_LOCAL,
          assertThrows(
                  ItemEvidenceException.class,
                  () ->
                      realBackend(f)
                          .capture(
                              new ItemEvidenceSelection(pair.item(), 101, Optional.of(pair)), NONE))
              .code());
      assertEquals(0, f.outbound.get());
      assertEquals(2, f.count("item_projection_rows"));
      assertEquals(2, f.count("sparse_name_rows"));
    }
  }

  @Test
  void finalGenerationSamplingObservesChangesDuringReplayAndMissingItemContextFails()
      throws Exception {
    try (var f = fixture()) {
      var pair = f.backend().prepare(f.request(), NONE);
      var selection = new ItemEvidenceSelection(pair.item(), 101, Optional.of(pair));
      var b = backend(f, Clock.fixed(NOW, ZoneOffset.UTC));
      String before = b.capture(selection, NONE).generationFingerprint();
      f.beforeVerify =
          () -> {
            assertEquals(1, f.itemLeases);
            assertEquals(1, f.sparseLeases);
            try (var c = f.catalog.connect();
                var q = c.createStatement()) {
              q.execute("PRAGMA foreign_keys=OFF");
              String generation = UUID.randomUUID().toString();
              for (var table :
                  List.of("sparse_name_artifacts", "sparse_name_rows", "sparse_name_copies"))
                q.executeUpdate("UPDATE " + table + " SET generation='" + generation + "'");
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          };
      String after = b.capture(selection, NONE).generationFingerprint();
      assertNotEquals(before, after);
      f.beforeVerify = () -> {};
      assertEquals(after, b.capture(selection, NONE).generationFingerprint());
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        q.executeUpdate("DELETE FROM item_projection_contexts");
      }
      assertEquals(
          ItemEvidenceException.Code.STALE_SELECTION,
          assertThrows(
                  ItemEvidenceException.class,
                  () ->
                      b.capture(
                          new ItemEvidenceSelection(pair.item(), 101, Optional.empty()), NONE))
              .code());
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  SqliteItemEvidenceSnapshotBackend realBackend(RetainedItemNamesJourneyTest.Fixture f) {
    var gateway =
        (io.vibeminer.application.selectedfile.SelectedFileGateway)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {io.vibeminer.application.selectedfile.SelectedFileGateway.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("inspectRetained"))
                    throw new AssertionError("No network-capable operation: " + method.getName());
                  var association =
                      (io.vibeminer.domain.selectedfile.SelectedFileAssociation) args[0];
                  assertEquals(
                      association,
                      f.files
                          .findOriginalAssociation(association.descriptor(), NONE)
                          .orElseThrow());
                  return association.representation();
                });
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    return new SqliteItemEvidenceSnapshotBackend(
        f.catalog,
        f.store.contentSession(),
        new RetainedItemProjectionSourceReader(f.store, f.files, gateway, clock),
        new io.vibeminer.infrastructure.itemnames.RetainedSparseNameProjectionSourceReader(
            f.store, f.files, gateway, clock),
        clock);
  }
}
