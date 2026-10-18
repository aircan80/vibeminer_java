package io.vibeminer.infrastructure.encoding;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.protocol.tact.encoding.FullEncodingInventory;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EncodingLookupStagingTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final FullEncodingInventory.Limits PARSER =
      new FullEncodingInventory.Limits(
          1000000, 10000, 10000, 100, 65536, 4096, 1000, 255, 100, 65536, 1000);
  static final EncodingLookupStaging.Limits LIMITS =
      new EncodingLookupStaging.Limits(10000, 10000, 10000000, 2, 10000, 10, 100);
  SqliteCatalog catalog;
  EncodingLookupStaging index;

  @BeforeEach
  void setup() throws Exception {
    catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var c = catalog.connect()) {
      EncodingLookupSchema.migrate(c);
    }
    index = new EncodingLookupStaging(catalog);
  }

  @Test
  void orderedDuplicateCandidatesUnknownSizeAndUnsigned40SurviveRestart() throws Exception {
    var source = fixture();
    var verified = new AtomicBoolean();
    var g =
        index.build(
            "a",
            new byte[] {1},
            "owner",
            new ByteArrayInputStream(source),
            PARSER,
            LIMITS,
            () -> verified.set(true),
            NONE);
    assertTrue(verified.get());
    index = new EncodingLookupStaging(catalog);
    var rows = index.lookup(g, List.of(key(1), key(2), key(3)), LIMITS, NONE);
    assertEquals(0x123456789aL, rows.get(0).decodedBytes().orElseThrow());
    assertEquals(
        List.of(key(10), key(10), key(11)),
        rows.get(0).candidates().stream()
            .map(EncodingLookupStaging.Candidate::encodingKey)
            .toList());
    assertEquals(0xffffffffffL, rows.get(0).candidates().getFirst().encodedBytes().orElseThrow());
    assertTrue(rows.get(0).candidates().get(2).encodedBytes().isEmpty());
    assertEquals(0, rows.get(1).decodedBytes().orElseThrow());
    assertTrue(rows.get(2).decodedBytes().isEmpty());
  }

  @Test
  void lateWholeSourceFailureNeverPublishesAndRetiresStaging() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            index.build(
                "a",
                new byte[] {1},
                "owner",
                new ByteArrayInputStream(fixture()),
                PARSER,
                LIMITS,
                () -> {
                  throw new IllegalStateException("late CKey mismatch");
                },
                NONE));
    assertEquals(0, count("en_lookup_artifacts"));
    assertEquals(0, count("en_lookup_candidates"));
  }

  @Test
  void truncatedLateEofAndCancellationCannotPublish() throws Exception {
    var bytes = fixture();
    assertThrows(
        IOException.class,
        () ->
            index.build(
                "a",
                new byte[] {1},
                "owner",
                new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length - 2)),
                PARSER,
                LIMITS,
                () -> {},
                NONE));
    assertEquals(0, count("en_lookup_artifacts"));
    var cancel = new AtomicBoolean();
    assertThrows(
        RuntimeException.class,
        () ->
            index.build(
                "b",
                new byte[] {1},
                "owner",
                new ByteArrayInputStream(bytes),
                PARSER,
                LIMITS,
                () -> cancel.set(true),
                cancel::get));
    assertEquals(0, count("en_lookup_artifacts"));
  }

  @Test
  void quotasRefuseAndPriorReadyGenerationSurvives() throws Exception {
    var first =
        index.build(
            "a",
            new byte[] {1},
            "owner",
            new ByteArrayInputStream(fixture()),
            PARSER,
            LIMITS,
            () -> {},
            NONE);
    var tiny = new EncodingLookupStaging.Limits(1, 100, 100000, 1, 100, 10, 100);
    assertThrows(
        RuntimeException.class,
        () ->
            index.build(
                "a",
                new byte[] {1},
                "owner",
                new ByteArrayInputStream(fixture()),
                PARSER,
                tiny,
                () -> {},
                NONE));
    assertEquals(1, count("en_lookup_artifacts"));
    assertEquals(
        3, index.lookup(first, List.of(key(1)), LIMITS, NONE).getFirst().candidates().size());
  }

  @Test
  void queryBoundsAndStagingGenerationCannotBeRead() throws Exception {
    var g =
        index.build(
            "a",
            new byte[] {1},
            "owner",
            new ByteArrayInputStream(fixture()),
            PARSER,
            LIMITS,
            () -> {},
            NONE);
    var tiny = new EncodingLookupStaging.Limits(100, 100, 100000, 2, 100, 1, 2);
    assertThrows(
        RuntimeException.class, () -> index.lookup(g, List.of(key(1), key(2)), tiny, NONE));
    assertThrows(RuntimeException.class, () -> index.lookup(g, List.of(key(1)), tiny, NONE));
    assertThrows(
        RuntimeException.class,
        () ->
            index.lookup(
                new EncodingLookupStaging.Generation("a", "missing"),
                List.of(key(1)),
                LIMITS,
                NONE));
    assertThrows(
        IllegalArgumentException.class, () -> index.lookup(g, List.of("not-a-key"), LIMITS, NONE));
  }

  @Test
  void competingWriterIsFencedAndCallbackFailureCleansOnlyItsOwnGeneration() throws Exception {
    var busy = new AtomicBoolean();
    var g =
        index.build(
            "a",
            new byte[] {1},
            "owner",
            new ByteArrayInputStream(fixture()),
            PARSER,
            LIMITS,
            () -> {
              assertThrows(
                  RuntimeException.class,
                  () ->
                      index.build(
                          "a",
                          new byte[] {1},
                          "other",
                          new ByteArrayInputStream(fixture()),
                          PARSER,
                          LIMITS,
                          () -> {},
                          NONE));
              busy.set(true);
            },
            NONE);
    assertTrue(busy.get());
    assertEquals(1, count("en_lookup_artifacts"));
    assertFalse(index.lookup(g, List.of(key(1)), LIMITS, NONE).isEmpty());
  }

  @Test
  void crashStagingIsInvisibleAndRecoveryOnlyRetiresExplicitOwner() throws Exception {
    try (var c = catalog.connect();
        var s = c.createStatement()) {
      s.execute(
          "INSERT INTO en_lookup_artifacts(artifact,generation,owner,status,source_proof)"
              + " VALUES('crashed','g','old','STAGING',X'01'),('active','h','other','STAGING',X'01')");
    }
    assertThrows(
        IllegalStateException.class,
        () ->
            index.lookup(
                new EncodingLookupStaging.Generation("crashed", "g"),
                List.of(key(1)),
                LIMITS,
                NONE));
    index.retireStagingOwner("old", NONE);
    assertEquals(1, count("en_lookup_artifacts"));
    assertThrows(
        IllegalStateException.class,
        () ->
            index.build(
                "active",
                new byte[] {1},
                "new",
                new ByteArrayInputStream(fixture()),
                PARSER,
                LIMITS,
                () -> {},
                NONE));
    assertEquals(1, count("en_lookup_artifacts"));
  }

  @Test
  void publishedGenerationIsImmutableAndWrongSourceProofCannotReuse() throws Exception {
    var g =
        index.build(
            "a",
            new byte[] {1},
            "owner",
            new ByteArrayInputStream(fixture()),
            PARSER,
            LIMITS,
            () -> {},
            NONE);
    assertThrows(IllegalStateException.class, () -> index.requireSource(g, new byte[] {2}, NONE));
    try (var c = catalog.connect();
        var s = c.createStatement()) {
      assertThrows(
          java.sql.SQLException.class,
          () -> s.execute("UPDATE en_lookup_encoding SET encoded_bytes=7"));
      assertThrows(
          java.sql.SQLException.class, () -> s.execute("DELETE FROM en_lookup_candidates"));
      assertThrows(
          java.sql.SQLException.class,
          () -> s.execute("UPDATE en_lookup_artifacts SET source_proof=X'02'"));
    }
    assertEquals(g, index.findReady("a", new byte[] {1}, NONE).orElseThrow());
  }

  @Test
  void stagingRowsCannotBeRetargetedIntoAnyReadyChildTable() throws Exception {
    var g =
        index.build(
            "a",
            new byte[] {1},
            "owner",
            new ByteArrayInputStream(fixture()),
            PARSER,
            LIMITS,
            () -> {},
            NONE);
    try (var c = catalog.connect();
        var q = c.createStatement()) {
      q.execute(
          "INSERT INTO en_lookup_artifacts(artifact,generation,owner,status,source_proof)"
              + " VALUES('evil','stage','other','STAGING',X'01')");
      q.execute(
          "INSERT INTO en_lookup_content VALUES('evil','stage','"
              + key(99)
              + "',5,1),('evil','stage','"
              + key(1)
              + "',5,1)");
      q.execute(
          "INSERT INTO en_lookup_candidates VALUES('evil','stage','"
              + key(1)
              + "',4,'"
              + key(99)
              + "')");
      q.execute("INSERT INTO en_lookup_encoding VALUES('evil','stage','" + key(99) + "',5,0)");
      for (var table : List.of("en_lookup_content", "en_lookup_candidates", "en_lookup_encoding")) {
        assertThrows(
            java.sql.SQLException.class,
            () ->
                q.execute(
                    "UPDATE "
                        + table
                        + " SET artifact='a',generation='"
                        + g.generation()
                        + "' WHERE artifact='evil'"),
            table);
      }
    }
    assertTrue(index.lookup(g, List.of(key(99)), LIMITS, NONE).getFirst().decodedBytes().isEmpty());
  }

  @Test
  void emptyReadyParentCannotBeDeleted() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var o = new DataOutputStream(bytes);
    o.writeBytes("EN");
    o.writeByte(1);
    o.writeByte(16);
    o.writeByte(16);
    o.writeShort(1);
    o.writeShort(1);
    o.writeInt(0);
    o.writeInt(0);
    o.writeByte(0);
    o.writeInt(0);
    var g =
        index.build(
            "empty",
            new byte[] {1},
            "owner",
            new ByteArrayInputStream(bytes.toByteArray()),
            PARSER,
            LIMITS,
            () -> {},
            NONE);
    try (var c = catalog.connect();
        var q = c.createStatement()) {
      assertThrows(
          java.sql.SQLException.class,
          () -> q.execute("DELETE FROM en_lookup_artifacts WHERE artifact='empty'"));
    }
    assertEquals(g, index.findReady("empty", new byte[] {1}, NONE).orElseThrow());
  }

  @Test
  void reverseOriginsUseCoveringOrderInsteadOfTemporarySort() throws Exception {
    var g =
        index.build(
            "a",
            new byte[] {1},
            "owner",
            new ByteArrayInputStream(fixture()),
            PARSER,
            LIMITS,
            () -> {},
            NONE);
    try (var c = catalog.connect();
        var q =
            c.prepareStatement(
                "EXPLAIN QUERY PLAN SELECT a.ckey,b.decoded_bytes,a.ordinal FROM"
                    + " en_lookup_candidates a JOIN en_lookup_content b ON a.artifact=b.artifact"
                    + " AND a.generation=b.generation AND a.ckey=b.ckey WHERE a.artifact=? AND"
                    + " a.generation=? AND a.ekey=? ORDER BY a.ckey,a.ordinal LIMIT ?")) {
      q.setString(1, g.artifact());
      q.setString(2, g.generation());
      q.setString(3, key(10));
      q.setInt(4, 128);
      var details = new ArrayList<String>();
      try (var r = q.executeQuery()) {
        while (r.next()) details.add(r.getString("detail"));
      }
      assertTrue(
          details.stream().anyMatch(x -> x.contains("COVERING INDEX en_lookup_reverse_origins")),
          details.toString());
      assertTrue(
          details.stream()
              .noneMatch(x -> x.toUpperCase(java.util.Locale.ROOT).contains("TEMP B-TREE")),
          details.toString());
    }
  }

  long count(String table) throws Exception {
    try (var c = catalog.connect();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  static String key(int v) {
    return HexFormat.of().formatHex(k(v));
  }

  static byte[] k(int v) {
    var b = new byte[16];
    b[0] = (byte) v;
    return b;
  }

  static void u40(DataOutputStream o, long v) throws Exception {
    for (int n = 32; n >= 0; n -= 8) o.writeByte((int) (v >>> n));
  }

  static byte[] cp(int id, long bytes, int... keys) throws Exception {
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.writeByte(keys.length);
    u40(o, bytes);
    o.write(k(id));
    for (int x : keys) o.write(k(x));
    return b.toByteArray();
  }

  static byte[] ep(int id, long size) throws Exception {
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.write(k(id));
    o.writeInt(0);
    u40(o, size);
    return b.toByteArray();
  }

  static byte[] fixture() throws Exception {
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.writeBytes("EN");
    o.writeByte(1);
    o.writeByte(16);
    o.writeByte(16);
    o.writeShort(1);
    o.writeShort(1);
    o.writeInt(2);
    o.writeInt(1);
    o.writeByte(0);
    o.writeInt(2);
    o.writeByte('n');
    o.writeByte(0);
    pages(o, new byte[][] {cp(1, 0x123456789aL, 10, 10, 11), cp(2, 0, 10)}, false);
    pages(o, new byte[][] {ep(10, 0xffffffffffL)}, true);
    o.writeByte('z');
    return b.toByteArray();
  }

  static void pages(DataOutputStream o, byte[][] rows, boolean encoded) throws Exception {
    var pages = new ArrayList<byte[]>();
    for (var row : rows) {
      var p = Arrays.copyOf(row, 1024);
      pages.add(p);
      o.write(p, encoded ? 0 : 6, 16);
      o.write(MessageDigest.getInstance("MD5").digest(p));
    }
    for (var p : pages) o.write(p);
  }
}
