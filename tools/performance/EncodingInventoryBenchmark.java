package io.vibeminer.infrastructure.encoding;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.protocol.tact.encoding.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Offline authored-input benchmark; never reads installations, live catalogs or official CDN data.
 */
public class EncodingInventoryBenchmark {
  static int candidateCount = 1;

  static int contentPerPage() {
    return candidateCount == 1 ? 26 : 17;
  }

  static byte[] key(long n) {
    return ByteBuffer.allocate(16).putLong(8, n).array();
  }

  static String hex(long n) {
    return HexFormat.of().formatHex(key(n));
  }

  static byte[] page(int p, boolean e) {
    var b = ByteBuffer.allocate(1024);
    int count = e ? 40 : contentPerPage();
    for (int i = 0; i < count; i++) {
      long n = (long) p * count + i + 1;
      if (e) {
        b.put(key(n));
        b.putInt(0);
        b.put(new byte[] {0, 0, 0, 0, 32});
      } else {
        b.put((byte) candidateCount);
        b.put(new byte[] {0, 0, 0, 0, 16});
        b.put(key(n));
        for (int k = 0; k < candidateCount; k++) b.put(key(n + k));
      }
    }
    return b.array();
  }

  static Path fixture(Path dir, int pages) throws Exception {
    Path path = dir.resolve("authored.en");
    try (var o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
      o.writeBytes("EN");
      o.write(new byte[] {1, 16, 16});
      o.writeShort(1);
      o.writeShort(1);
      o.writeInt(pages);
      o.writeInt(pages);
      o.writeByte(0);
      o.writeInt(2);
      o.write(new byte[] {'n', 0});
      for (boolean e : new boolean[] {false, true}) {
        int count = e ? 40 : contentPerPage();
        for (int p = 0; p < pages; p++) {
          o.write(key((long) p * count + 1));
          o.write(MessageDigest.getInstance("MD5").digest(page(p, e)));
        }
        for (int p = 0; p < pages; p++) o.write(page(p, e));
      }
    }
    return path;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4)
      throw new IllegalArgumentException("output-directory pages batch candidate-fanout");
    Path dir = Path.of(args[0]);
    int pages = Integer.parseInt(args[1]), batch = Integer.parseInt(args[2]);
    int fanout = Integer.parseInt(args[3]);
    if (pages < 1 || pages > 20000 || batch < 1 || batch > 4096 || fanout < 1 || fanout > 2)
      throw new IllegalArgumentException("Expected pages1..20000, batch1..4096, candidates1..2");
    candidateCount = fanout;
    dir = dir.toAbsolutePath().normalize();
    if (dir.getParent() != null) Files.createDirectories(dir.getParent());
    Files.createDirectory(dir); // Atomic refusal of every existing directory, including empty ones.
    var source = fixture(dir, pages);
    Path db = dir.resolve("catalog.sqlite");
    var catalog = new SqliteCatalog(db);
    try (var c = catalog.connect();
        var s = c.createStatement()) {
      try (var r = s.executeQuery("PRAGMA synchronous")) {
        r.next();
        if (r.getInt(1) != 2) throw new AssertionError();
      }
      try (var r =
          s.executeQuery(
              "EXPLAIN QUERY PLAN SELECT DISTINCT ekey FROM en_lookup_candidates WHERE"
                  + " artifact='benchmark' AND generation='g' AND ekey BETWEEN '0' AND 'f' ORDER BY"
                  + " ekey LIMIT 10")) {
        while (r.next()) System.out.println("PLAN " + r.getString(4));
      }
    }
    var index = new EncodingLookupStaging(catalog);
    var limits =
        new EncodingLookupStaging.Limits(
            100000000, 100000000, 10000000000L, batch, 10000000, 10, 100);
    var parser =
        new FullEncodingInventory.Limits(
            Files.size(source),
            10000000,
            10000000,
            2 * pages,
            pages * 64,
            1024,
            100,
            255,
            100,
            65536,
            100);
    var running = new AtomicBoolean(true);
    var peakJournal = new AtomicLong();
    Thread monitor =
        Thread.ofPlatform()
            .daemon()
            .start(
                () -> {
                  while (running.get()) {
                    try {
                      Path journal = Path.of(db + "-journal");
                      if (Files.exists(journal))
                        peakJournal.accumulateAndGet(Files.size(journal), Math::max);
                      Thread.sleep(5);
                    } catch (Exception ignored) {
                    }
                  }
                });
    long start = System.nanoTime();
    EncodingLookupStaging.Generation g;
    try (var in = Files.newInputStream(source)) {
      g =
          index.build(
              "b".repeat(64),
              new byte[] {1},
              "owner",
              in,
              parser,
              limits,
              () -> {},
              CancellationToken.none());
    }
    long elapsed = System.nanoTime() - start;
    running.set(false);
    monitor.join();
    var result =
        index.lookup(
            g,
            List.of(hex(1), hex((long) pages * contentPerPage())),
            limits,
            CancellationToken.none());
    if (result.size() != 2
        || result.stream()
            .anyMatch(
                x ->
                    x.candidates().size() != candidateCount
                        || x.decodedBytes().orElseThrow() != 16
                        || x.candidates().getFirst().encodedBytes().orElseThrow() != 32))
      throw new AssertionError("forward proof");
    var reverse =
        index.reverseBatch(
            g,
            List.of(SqliteEncodingLookupIndex.ReverseQuery.exact(hex(2))),
            new SqliteEncodingLookupIndex.ReverseLimits(10, 10, 10, 10, 10, 100000),
            c -> index.requireSourceInTransaction(c, g, new byte[] {1}, CancellationToken.none()),
            CancellationToken.none());
    var origins = reverse.getFirst().matches().getFirst().origins();
    var expectedOrigins =
        candidateCount == 1
            ? List.of(new SqliteEncodingLookupIndex.Origin(hex(2), 16, 0))
            : List.of(
                new SqliteEncodingLookupIndex.Origin(hex(1), 16, 1),
                new SqliteEncodingLookupIndex.Origin(hex(2), 16, 0));
    if (!origins.equals(expectedOrigins)) throw new AssertionError("ordered reverse origins");
    if (pages >= 2) {
      try {
        index.reverseBatch(
            g,
            List.of(SqliteEncodingLookupIndex.ReverseQuery.prefix9(hex(2).substring(0, 18))),
            new SqliteEncodingLookupIndex.ReverseLimits(10, 10, 10, 10, 10, 100000),
            c -> {},
            CancellationToken.none());
        throw new AssertionError("Ambiguous prefix must respect query limit");
      } catch (EncodingLookupException expected) {
        if (expected.code() != EncodingLookupException.Code.LIMIT) throw expected;
      }
    }
    try (var c = catalog.connect();
        var s = c.createStatement()) {
      for (String table : List.of("content", "encoding", "candidates")) {
        try (var r = s.executeQuery("SELECT count(*) FROM en_lookup_" + table)) {
          r.next();
          long expected =
              (long) pages
                  * (table.equals("encoding")
                      ? 40
                      : table.equals("candidates")
                          ? contentPerPage() * candidateCount
                          : contentPerPage());
          if (r.getLong(1) != expected) throw new AssertionError("count " + table);
        }
      }
      try (var r =
          s.executeQuery(
              "SELECT count(*) FROM en_lookup_candidates a JOIN en_lookup_content b"
                  + " USING(artifact,generation,ckey) JOIN en_lookup_encoding e"
                  + " USING(artifact,generation,ekey) WHERE a.ordinal=0 AND b.decoded_bytes=16 AND"
                  + " e.encoded_bytes=32")) {
        r.next();
        if (r.getLong(1) != (long) pages * contentPerPage())
          throw new AssertionError("reverse equivalence");
      }
      try (var r = s.executeQuery("PRAGMA foreign_key_check")) {
        if (r.next()) throw new AssertionError("FK");
      }
    }
    System.out.printf(
        Locale.ROOT,
        "RESULT pages=%d contents=%d encodings=%d candidates=%d batch=%d"
            + " estimatedDataBatchCommits=%d candidateFanout=%d seconds=%.3f dbBytes=%d"
            + " peakJournal=%d%n",
        pages,
        pages * contentPerPage(),
        pages * 40,
        pages * contentPerPage() * candidateCount,
        batch,
        ((long) pages * (40L + contentPerPage() * (1 + candidateCount)) + batch - 1) / batch,
        candidateCount,
        elapsed / 1e9,
        Files.size(db),
        peakJournal.get());
  }
}
