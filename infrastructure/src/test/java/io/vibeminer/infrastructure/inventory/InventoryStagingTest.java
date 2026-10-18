package io.vibeminer.infrastructure.inventory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.inventory.ManifestReferenceResolver;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class InventoryStagingTest {
  @TempDir Path directory;
  SqliteCatalog catalog;
  InventoryStaging staging;
  final CancellationToken token = () -> false;
  final String key = "a".repeat(32);
  final InventoryStaging.Limits limits = new InventoryStaging.Limits(100, 100000, 10, 100);

  @BeforeEach
  void setup() throws Exception {
    catalog = new SqliteCatalog(directory.resolve("catalog.db"));
    try (var c = catalog.connect()) {
      InventorySchema.migrate(c);
    }
    staging = new InventoryStaging(catalog);
  }

  String begin() {
    return staging.begin(
        "snapshot",
        "admission",
        "en",
        "generation",
        "all",
        List.of(
            new InventoryStaging.Source("ROOT", new byte[] {1}),
            new InventoryStaging.Source("INSTALL", new byte[] {2})),
        limits,
        token);
  }

  InventoryStaging.Reference ref(String role, long ordinal) {
    return new InventoryStaging.Reference(
        role, ordinal, 0, key, OptionalLong.empty(), "variant=all");
  }

  ManifestReferenceResolver.Resolution good() {
    return new ManifestReferenceResolver.Resolution(
        ManifestReferenceResolver.Outcome.RESOLVED,
        Optional.of(key),
        Optional.of(key),
        OptionalLong.of(100),
        OptionalLong.of(80));
  }

  @Test
  void provisionalSourcesAndUnknownReferencesCannotSeal() {
    String id = begin();
    staging.append(id, List.of(ref("ROOT", 0)), limits, token);
    assertThrows(IllegalStateException.class, () -> staging.seal(id, c -> {}, token));
    staging.complete(id, "ROOT", 1, token);
    staging.complete(id, "INSTALL", 0, token);
    assertThrows(IllegalStateException.class, () -> staging.seal(id, c -> {}, token));
  }

  @Test
  void allOriginsRemainWhilePhysicalTargetsDeduplicateAndReadyIsImmutable() throws Exception {
    String id = begin();
    staging.append(id, List.of(ref("ROOT", 0), ref("ROOT", 1), ref("INSTALL", 0)), limits, token);
    staging.complete(id, "ROOT", 2, token);
    staging.complete(id, "INSTALL", 1, token);
    for (var role : List.of("ROOT", "INSTALL"))
      for (var row : staging.page(id, role, -1, 10, token))
        staging.resolve(id, row.reference(), good(), token);
    assertEquals(1, staging.seal(id, c -> {}, token));
    try (var c = catalog.connect();
        var s = c.createStatement()) {
      try (var r = s.executeQuery("SELECT count(*) FROM build_inventory_references")) {
        r.next();
        assertEquals(3, r.getLong(1));
      }
      assertThrows(
          java.sql.SQLException.class,
          () -> s.executeUpdate("DELETE FROM build_inventory_references"));
    }
    assertThrows(
        IllegalStateException.class,
        () -> staging.append(id, List.of(ref("ROOT", 2)), limits, token));
  }

  @Test
  void lateSourceErrorAndNoncontiguousOrdinalsCannotComplete() {
    String id = begin();
    staging.append(id, List.of(ref("ROOT", 1)), limits, token);
    assertThrows(IllegalStateException.class, () -> staging.complete(id, "ROOT", 1, token));
    assertThrows(IllegalStateException.class, () -> staging.seal(id, c -> {}, token));
  }

  @Test
  void authorityFailureRollsBackMembersAndReady() throws Exception {
    String id = begin();
    staging.complete(id, "ROOT", 0, token);
    staging.complete(id, "INSTALL", 0, token);
    assertThrows(
        IllegalStateException.class,
        () ->
            staging.seal(
                id,
                c -> {
                  throw new IllegalStateException("stale authority");
                },
                token));
    try (var c = catalog.connect();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT status FROM build_inventory_generations")) {
      r.next();
      assertEquals("STAGING", r.getString(1));
    }
    assertEquals(0, staging.seal(id, c -> {}, token));
  }

  @Test
  void quotaAndBatchFailureDoNotLeavePartialRows() {
    String id = begin();
    var tiny = new InventoryStaging.Limits(1, 100000, 10, 1);
    staging.append(id, List.of(ref("ROOT", 0)), tiny, token);
    assertThrows(
        IllegalStateException.class,
        () -> staging.append(id, List.of(ref("ROOT", 1)), tiny, token));
    assertEquals(1, staging.page(id, "ROOT", -1, 10, token).size());
  }

  @Test
  void unsupportedOrMissingFamiliesAreNotVacuousComplete() {
    assertThrows(
        IllegalArgumentException.class,
        () -> staging.begin("s", "a", "e", "g", "all", List.of(), limits, token));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            staging.begin(
                "s",
                "a",
                "e",
                "g",
                "all",
                List.of(new InventoryStaging.Source("TVFS", new byte[] {1})),
                limits,
                token));
  }

  @Test
  void stagingRowsCannotBeRetargetedIntoReadyInventory() throws Exception {
    String ready = begin();
    staging.complete(ready, "ROOT", 0, token);
    staging.complete(ready, "INSTALL", 0, token);
    staging.seal(ready, c -> {}, token);
    String other = begin();
    staging.append(other, List.of(ref("ROOT", 0)), limits, token);
    try (var c = catalog.connect();
        var q =
            c.prepareStatement(
                "UPDATE build_inventory_references SET generation=? WHERE generation=?")) {
      q.setString(1, ready);
      q.setString(2, other);
      assertThrows(java.sql.SQLException.class, q::executeUpdate);
    }
  }

  @Test
  void readyParentDeletionAndSourceRetargetAreBlocked() throws Exception {
    String ready = begin();
    staging.complete(ready, "ROOT", 0, token);
    staging.complete(ready, "INSTALL", 0, token);
    staging.seal(ready, c -> {}, token);
    String other = begin();
    try (var c = catalog.connect();
        var s = c.createStatement()) {
      assertThrows(
          java.sql.SQLException.class,
          () ->
              s.executeUpdate("DELETE FROM build_inventory_generations WHERE id='" + ready + "'"));
      assertThrows(
          java.sql.SQLException.class,
          () ->
              s.executeUpdate(
                  "UPDATE build_inventory_sources SET generation='"
                      + ready
                      + "',role='DOWNLOAD' WHERE generation='"
                      + other
                      + "' AND role='ROOT'"));
    }
  }

  @Test
  void sourceProofTamperingAndGlobalQuotaAreRejected() throws Exception {
    String id = begin();
    try (var c = catalog.connect();
        var q =
            c.prepareStatement(
                "UPDATE build_inventory_sources SET proof=X'09' WHERE generation=? AND"
                    + " role='ROOT'")) {
      q.setString(1, id);
      q.executeUpdate();
      assertThrows(
          IllegalStateException.class,
          () ->
              InventoryStaging.requireContext(
                  c,
                  id,
                  "snapshot",
                  "admission",
                  "en",
                  "generation",
                  "all",
                  List.of(
                      new InventoryStaging.Source("ROOT", new byte[] {1}),
                      new InventoryStaging.Source("INSTALL", new byte[] {2}))));
    }
    var tiny = new InventoryStaging.Limits(100, 1, 10, 100);
    assertThrows(
        IllegalStateException.class,
        () ->
            staging.begin(
                "s",
                "a",
                "e",
                "g",
                "all",
                List.of(
                    new InventoryStaging.Source("ROOT", new byte[] {1}),
                    new InventoryStaging.Source("INSTALL", new byte[] {2})),
                tiny,
                token));
  }

  @Test
  void cancellationInterruptsActualLongSqlAndLeavesGenerationProvisional() throws Exception {
    String id = begin();
    staging.complete(id, "ROOT", 0, token);
    staging.complete(id, "INSTALL", 0, token);
    var polls = new java.util.concurrent.atomic.AtomicInteger();
    CancellationToken canceledDuringSql = () -> polls.incrementAndGet() >= 10;
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            staging.seal(
                id,
                c -> {
                  try (var s = c.createStatement();
                      var r =
                          s.executeQuery(
                              "WITH RECURSIVE numbers(n) AS (VALUES(0) UNION ALL SELECT n+1 FROM"
                                  + " numbers WHERE n<1000000) SELECT sum(n) FROM numbers")) {
                    r.next();
                  }
                },
                canceledDuringSql));
    assertEquals(0, staging.seal(id, c -> {}, token));
  }

  @Test
  void canceledSealNeverPublishes() {
    String id = begin();
    staging.complete(id, "ROOT", 0, token);
    staging.complete(id, "INSTALL", 0, token);
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> staging.seal(id, c -> {}, () -> true));
    assertEquals(0, staging.seal(id, c -> {}, token));
  }

  @Test
  void explicitResolutionConflictBlocksSeal() {
    String id = begin();
    var ref = ref("ROOT", 0);
    staging.append(id, List.of(ref), limits, token);
    staging.complete(id, "ROOT", 1, token);
    staging.complete(id, "INSTALL", 0, token);
    staging.resolve(
        id,
        ref,
        new ManifestReferenceResolver.Resolution(
            ManifestReferenceResolver.Outcome.CANONICAL_REPRESENTATION_CONFLICT,
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty(),
            OptionalLong.empty()),
        token);
    assertThrows(IllegalStateException.class, () -> staging.seal(id, c -> {}, token));
  }
}
