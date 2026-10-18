package io.vibeminer.infrastructure.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.domain.item.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteItemNamesSearchTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  SqliteItemNamesBackendTest.Fixture fixture() throws Exception {
    var owner = new SqliteItemNamesBackendTest();
    owner.directory = directory.resolve(UUID.randomUUID().toString());
    return owner.new Fixture();
  }

  @Test
  void literalMatchesOnlyPhysicalItemAnchorsAndCursorUsesLastVisibleMatch() throws Exception {
    try (var f = fixture()) {
      var b = f.backend();
      var pair = b.prepare(f.request(), NONE);
      pair = names(f, pair, List.of("n", "n", "needleX"));
      var query = new ItemNameLiteralQuery("n");
      var first = b.search(pair, query, Optional.empty(), 1, NONE);
      assertEquals(List.of(101L), ids(first));
      assertEquals(101, first.nextCursor().orElseThrow().afterRecordId());
      var second = b.search(pair, query, first.nextCursor(), 1, NONE);
      assertEquals(List.of(202L), ids(second));
      assertTrue(second.nextCursor().isEmpty());
      assertEquals(first.generationFingerprint(), second.generationFingerprint());
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
      assertTrue(
          b.search(pair, new ItemNameLiteralQuery("needle"), Optional.empty(), 1, NONE)
              .rows()
              .isEmpty(),
          "Sparse-only505 cannot supply a match");
    }
  }

  @Test
  void noSqlWildcardsCasefoldOrNormalizationAndScalarPredicateAgreesWithOracle() throws Exception {
    try (var f = fixture()) {
      var b = f.backend();
      var initial = b.prepare(f.request(), NONE);
      for (var value : List.of("%_'\\", "e\u0301", "é", "a🚀z", "a👩\u200d🔬b", " A ")) {
        var pair = names(f, initial, List.of(value, "", "orphan"));
        for (var query :
            List.of(
                "%",
                "_",
                "'",
                "\\",
                "e",
                "é",
                "\u0301",
                "🚀",
                "\u200d",
                " A ",
                "a",
                "A",
                "does-not-match")) {
          var page = b.search(pair, new ItemNameLiteralQuery(query), Optional.empty(), 10, NONE);
          assertEquals(
              oracle(value, query) ? List.of(101L) : List.of(), ids(page), value + " / " + query);
        }
      }
    }
  }

  @Test
  void corruptedNonmatchingNameCannotBeHiddenByTextFiltering() throws Exception {
    try (var f = fixture()) {
      var b = f.backend();
      var pair = b.prepare(f.request(), NONE);
      try (var c = f.catalog.connect();
          var q =
              c.prepareStatement(
                  "UPDATE sparse_name_rows SET record_sha256=? WHERE record_id=101")) {
        q.setString(1, "0".repeat(64));
        q.executeUpdate();
      }
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(
                  ItemNamesException.class,
                  () ->
                      b.search(
                          pair,
                          new ItemNameLiteralQuery("not-a-match"),
                          Optional.empty(),
                          10,
                          NONE))
              .code());
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  @Test
  void staleQuerySizeAndGenerationFailWithoutContinuingAnotherSnapshot() throws Exception {
    try (var f = fixture()) {
      var b = f.backend();
      var pair = names(f, b.prepare(f.request(), NONE), List.of("n", "n", "needleX"));
      var query = new ItemNameLiteralQuery("n");
      var first = b.search(pair, query, Optional.empty(), 1, NONE);
      var cursor = first.nextCursor();
      assertEquals(
          ItemNamesException.Code.STALE_CURSOR,
          assertThrows(
                  ItemNamesException.class,
                  () -> b.search(pair, new ItemNameLiteralQuery("N"), cursor, 1, NONE))
              .code());
      assertEquals(
          ItemNamesException.Code.STALE_CURSOR,
          assertThrows(ItemNamesException.class, () -> b.search(pair, query, cursor, 2, NONE))
              .code());
      var old = cursor.orElseThrow();
      var forged =
          new ItemNamesSearchCursor(
              old.itemArtifactId(),
              old.namesArtifactId(),
              old.snapshotId(),
              old.itemSourceRole(),
              old.namesSourceRole(),
              old.locale(),
              old.query(),
              old.queryRevision(),
              "0".repeat(64),
              old.afterRecordId(),
              old.pageSize());
      assertEquals(
          ItemNamesException.Code.STALE_CURSOR,
          assertThrows(
                  ItemNamesException.class,
                  () -> b.search(pair, query, Optional.of(forged), 1, NONE))
              .code());
    }
  }

  @Test
  void nonmatchingItemAndCopyTuplesAreAuthenticatedBeforeExclusion() throws Exception {
    for (var table : List.of("item_projection_rows", "sparse_name_copies")) {
      try (var f = fixture()) {
        var b = f.backend();
        var pair = b.prepare(f.request(), NONE);
        String column = table.equals("item_projection_rows") ? "row_sha256" : "record_sha256";
        try (var c = f.catalog.connect();
            var q = c.prepareStatement("UPDATE " + table + " SET " + column + "=?")) {
          q.setString(1, "0".repeat(64));
          q.executeUpdate();
        }
        assertEquals(
            ItemNamesException.Code.CORRUPT,
            assertThrows(
                    ItemNamesException.class,
                    () ->
                        b.search(
                            pair, new ItemNameLiteralQuery("no-match"), Optional.empty(), 10, NONE))
                .code());
        assertEquals(0, f.itemLeases);
        assertEquals(0, f.sparseLeases);
      }
    }
  }

  @Test
  void lookaheadStopsBeforeUnexaminedCorruptionAndContinuationDetectsIt() throws Exception {
    try (var f = fixture()) {
      var b = f.backend();
      var pair = names(f, b.prepare(f.request(), NONE), List.of("n", "n", "orphan"));
      try (var c = f.catalog.connect();
          var q = c.prepareStatement("UPDATE sparse_name_copies SET record_sha256=?")) {
        q.setString(1, "0".repeat(64));
        q.executeUpdate();
      }
      var query = new ItemNameLiteralQuery("n");
      var first = b.search(pair, query, Optional.empty(), 1, NONE);
      assertEquals(List.of(101L), ids(first));
      assertTrue(first.nextCursor().isPresent());
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(
                  ItemNamesException.class,
                  () -> b.search(pair, query, first.nextCursor(), 1, NONE))
              .code());
    }
  }

  @Test
  void cursorSurvivesBackendRecreationButNotActualArtifactGenerationChange() throws Exception {
    try (var f = fixture()) {
      var b = f.backend();
      var pair = names(f, b.prepare(f.request(), NONE), List.of("n", "n", "orphan"));
      var query = new ItemNameLiteralQuery("n");
      var first = b.search(pair, query, Optional.empty(), 1, NONE);
      assertEquals(
          List.of(202L), ids(f.backend().search(pair, query, first.nextCursor(), 1, NONE)));
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        q.execute("PRAGMA foreign_keys=OFF");
        String generation = UUID.randomUUID().toString();
        for (var table : List.of("sparse_name_artifacts", "sparse_name_rows", "sparse_name_copies"))
          q.executeUpdate("UPDATE " + table + " SET generation='" + generation + "'");
      }
      assertEquals(
          ItemNamesException.Code.STALE_CURSOR,
          assertThrows(
                  ItemNamesException.class,
                  () -> b.search(pair, query, first.nextCursor(), 1, NONE))
              .code());
    }
  }

  @Test
  void cancellationAfterSourceReplayClosesBothLeasesAndDoesNotReturnPartialRows() throws Exception {
    try (var f = fixture()) {
      var b = f.backend();
      var pair = b.prepare(f.request(), NONE);
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      f.beforeVerify =
          () -> {
            assertEquals(1, f.itemLeases);
            assertEquals(1, f.sparseLeases);
            cancelled.set(true);
          };
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () ->
              b.search(pair, new ItemNameLiteralQuery("α"), Optional.empty(), 10, cancelled::get));
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
      f.beforeVerify = () -> {};
      assertEquals(
          List.of(101L),
          ids(b.search(pair, new ItemNameLiteralQuery("α"), Optional.empty(), 10, NONE)));
    }
  }

  @Test
  void invalidSizeAndPreCancelledRequestsDoNotReplaySources() throws Exception {
    try (var f = fixture()) {
      var b = f.backend();
      var pair = b.prepare(f.request(), NONE);
      int before = f.verifies, itemBefore = f.itemVerifies;
      for (int size : new int[] {0, 501})
        assertEquals(
            ItemNamesException.Code.INVALID_REQUEST,
            assertThrows(
                    ItemNamesException.class,
                    () ->
                        b.search(pair, new ItemNameLiteralQuery("x"), Optional.empty(), size, NONE))
                .code());
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> b.search(pair, new ItemNameLiteralQuery("x"), Optional.empty(), 10, () -> true));
      assertEquals(before, f.verifies);
      assertEquals(itemBefore, f.itemVerifies);
    }
  }

  static List<Long> ids(ItemNamesSearchPage page) {
    return page.rows().stream().map(r -> r.item().recordId()).toList();
  }

  static boolean oracle(String value, String query) {
    int[] haystack = value.codePoints().toArray(), needle = query.codePoints().toArray();
    outer:
    for (int p = 0; p <= haystack.length - needle.length; p++) {
      for (int n = 0; n < needle.length; n++) if (haystack[p + n] != needle[n]) continue outer;
      return true;
    }
    return false;
  }

  static PreparedItemNames names(
      SqliteItemNamesBackendTest.Fixture f, PreparedItemNames pair, List<String> values)
      throws Exception {
    long bytes = 0;
    String generation;
    try (var c = f.catalog.connect()) {
      c.setAutoCommit(false);
      try (var q =
          c.prepareStatement("SELECT generation FROM sparse_name_artifacts WHERE artifact_id=?")) {
        q.setString(1, pair.names().artifactId());
        try (var r = q.executeQuery()) {
          generation = r.getString(1);
        }
      }
      try (var q = c.prepareStatement("DELETE FROM sparse_name_rows WHERE artifact_id=?")) {
        q.setString(1, pair.names().artifactId());
        q.executeUpdate();
      }
      long[] ids = {101, 202, 505};
      try (var q = c.prepareStatement("INSERT INTO sparse_name_rows VALUES(?,?,?,?,?,?,?)")) {
        for (int i = 0; i < 3; i++) {
          int size = values.get(i).getBytes(StandardCharsets.UTF_8).length;
          bytes += size;
          var row =
              new DisplayCandidate(values.get(i), ids[i], i, 0, 100 + i * 200, 5 + size, 5 + size);
          var record = SparseNameRecords.candidate(row);
          q.setString(1, pair.names().artifactId());
          q.setString(2, generation);
          q.setLong(3, ids[i]);
          q.setInt(4, i);
          q.setInt(5, size);
          q.setBytes(6, record);
          q.setString(
              7, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(record)));
          q.addBatch();
        }
        q.executeBatch();
      }
      try (var q =
          c.prepareStatement(
              "UPDATE sparse_name_artifacts SET projected_utf8_bytes=? WHERE artifact_id=?")) {
        q.setLong(1, bytes);
        q.setString(2, pair.names().artifactId());
        q.executeUpdate();
      }
      c.commit();
    }
    var old = pair.names();
    var cov = old.coverage();
    return new PreparedItemNames(
        pair.item(),
        new PreparedSparseNames(
            old.definition(),
            old.evidence(),
            new PreparedSparseNames.Coverage(
                cov.sectionOrdinal(),
                cov.physicalRows(),
                cov.unindexedCopyDeclarations(),
                cov.excludedSections(),
                bytes)));
  }
}
