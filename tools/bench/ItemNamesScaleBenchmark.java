package io.vibeminer.infrastructure.item;

import io.vibeminer.application.*;
import io.vibeminer.application.item.*;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.ContentWorkerFinishOutcome;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.itemnames.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Portable authored scale journey. Root/EN semantic proof port is SYNTHETIC, never official TACT.
 */
public final class ItemNamesScaleBenchmark {
  static final CancellationToken NONE = CancellationToken.none();
  static final Instant NOW = Instant.parse("2020-01-01T00:00:00Z");
  static final SparseNameLimits LIMITS =
      new SparseNameLimits(
          16L << 20,
          131072,
          4096,
          4 << 20,
          4 << 20,
          131072,
          4 << 20,
          200000,
          1000,
          64L << 20,
          65535,
          65535,
          65535,
          64L << 20,
          256L << 20,
          500);

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Supply NEW scratch directory (kept for inspection)");
    Path dir = Path.of(args[0]);
    Files.createDirectories(dir);
    if (Files.exists(dir.resolve("catalog.sqlite")))
      throw new IllegalArgumentException("Use a new directory");
    System.out.printf(
        "scope=AUTHORED_SYNTHETIC_NO_NETWORK java=%s os=%s maxHeap=%d%n",
        System.getProperty("java.version"),
        System.getProperty("os.name"),
        Runtime.getRuntime().maxMemory());
    long start = System.nanoTime();
    var item = ItemNamesScaleData.generate(dir, false);
    var sparse = ItemNamesScaleData.generate(dir, true);
    report("generate", start);
    System.out.printf(
        "itemPhysical=%d sparsePhysical=%d recordArea=%d itemEncoded=%d sparseEncoded=%d"
            + " sparseDecoded=%d%n",
        ItemNamesScaleData.ITEMS,
        ItemNamesScaleData.NAMES,
        ItemNamesScaleData.AREA,
        item.bytes(),
        sparse.bytes(),
        sparse.decodedBytes());
    try (var session = new Session(dir, item, sparse, true)) {
      Files.delete(item.encoded());
      Files.delete(sparse.encoded());
      session.run();
    }
    try (var session = new Session(dir, item, sparse, false)) {
      session.runRestart();
    }
    System.out.println(
        "PASS authored scale correctness; no official chain or full decoded-CKey attestation");
  }

  static void report(String phase, long start) {
    System.out.printf(
        Locale.ROOT,
        "phase=%s elapsedMs=%.3f usedHeapObservation=%d%n",
        phase,
        (System.nanoTime() - start) / 1e6,
        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
  }

  static final class Session implements AutoCloseable {
    final boolean seeded;
    final SqliteCatalog catalog;
    final FileObjectStore store;
    final SqliteSelectedFileCatalog files;
    final SqliteItemProjectionBackend items;
    final SqliteItemNamesBackend names;
    final SelectedFileAssociation item, sparse;
    final AtomicInteger transport = new AtomicInteger(),
        projects = new AtomicInteger(),
        activeReaders = new AtomicInteger();
    final AtomicBoolean cancelProjection = new AtomicBoolean(),
        projectionCancelled = new AtomicBoolean();
    final AtomicInteger cancelRows = new AtomicInteger();

    Session(Path dir, ItemNamesScaleData.Source i, ItemNamesScaleData.Source s, boolean seed)
        throws Exception {
      seeded = seed;
      catalog = new SqliteCatalog(dir.resolve("catalog.sqlite"));
      store = new FileObjectStore(dir.resolve("store"));
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
      files = new SqliteSelectedFileCatalog(catalog, store, store.contentSession());
      var build =
          new BuildSnapshot(
              "wow",
              "Authored scale corpus",
              "current",
              "us",
              "12.1.5.70448",
              "c".repeat(32),
              "d".repeat(32),
              NOW,
              true);
      var objects = new SqliteBuildObjectCatalog(catalog, store);
      var config = alias("config", build.buildConfig(), "Authored config");
      var root = alias("root", "authored-scale-root", "Authored root/EN semantic proof port");
      if (seed) {
        objects.registerSnapshot(build);
        attach(objects, build, "BUILD_CONFIG", config);
        attach(objects, build, "ROOT_MANIFEST", root);
      }
      item = association(build, config, root, i, 841626, "1df6b6dbae36878cea020a62b15b9bd6", 2);
      sparse =
          association(build, config, root, s, 1572924, "bb0ecbdc2fd4988ddd3c1c5e95c34165", 0x202);
      if (seed) {
        retain(item, i);
        retain(sparse, s);
      }
      var port =
          (SelectedFileGateway)
              Proxy.newProxyInstance(
                  SelectedFileGateway.class.getClassLoader(),
                  new Class<?>[] {SelectedFileGateway.class},
                  (p, m, a) -> {
                    if (!m.getName().equals("inspectRetained")) {
                      transport.incrementAndGet();
                      throw new AssertionError("No transport allowed");
                    }
                    var association = (SelectedFileAssociation) a[0];
                    ((CancellationToken) a[2]).throwIfCancellationRequested();
                    if (!files
                        .findOriginalAssociation(association.descriptor(), NONE)
                        .orElseThrow()
                        .equals(association))
                      throw new AssertionError("Authored association changed");
                    return association.representation();
                  });
      var actualItemReader =
          new RetainedItemProjectionSourceReader(
              store, files, port, Clock.fixed(NOW.plusSeconds(100), ZoneOffset.UTC));
      ItemProjectionSourceReader itemReader =
          new ItemProjectionSourceReader() {
            public OpenedProjection open(ItemProjectionRequest request, CancellationToken token) {
              var opened = actualItemReader.open(request, token);
              activeReaders.incrementAndGet();
              return new OpenedProjection() {
                public ItemArtifactDefinition definition() {
                  return opened.definition();
                }

                public ItemProjectionEvidence evidence() {
                  return opened.evidence();
                }

                public List<CopyDeclaration> copies() {
                  return opened.copies();
                }

                public PreparedItemProjection.Coverage project(
                    RowSink sink, CancellationToken cancellation) {
                  return opened.project(sink, cancellation);
                }

                public void close() {
                  try {
                    opened.close();
                  } finally {
                    activeReaders.decrementAndGet();
                  }
                }
              };
            }

            public VerificationLease verify(
                PreparedItemProjection handle, CancellationToken token) {
              var lease = actualItemReader.verify(handle, token);
              activeReaders.incrementAndGet();
              return () -> {
                try {
                  lease.close();
                } finally {
                  activeReaders.decrementAndGet();
                }
              };
            }
          };
      var real =
          new RetainedSparseNameProjectionSourceReader(
              store, files, port, Clock.fixed(NOW.plusSeconds(100), ZoneOffset.UTC));
      SparseNameProjectionSourceReader reader =
          new SparseNameProjectionSourceReader() {
            public OpenedNames open(ItemNamesRequest r, CancellationToken t) {
              var o = real.open(r, t);
              activeReaders.incrementAndGet();
              return new OpenedNames() {
                public SparseNameArtifactDefinition definition() {
                  return o.definition();
                }

                public List<CopyDeclaration> copies() {
                  return o.copies();
                }

                public PreparedSparseNames project(RowSink sink, CancellationToken token) {
                  projects.incrementAndGet();
                  return o.project(
                      row -> {
                        sink.accept(row);
                        if (cancelRows.incrementAndGet() == 1024 && cancelProjection.get())
                          projectionCancelled.set(true);
                      },
                      token);
                }

                public void close() {
                  try {
                    o.close();
                  } finally {
                    activeReaders.decrementAndGet();
                  }
                }
              };
            }

            public VerificationLease verify(PreparedSparseNames h, CancellationToken t) {
              var lease = real.verify(h, t);
              activeReaders.incrementAndGet();
              return () -> {
                try {
                  lease.close();
                } finally {
                  activeReaders.decrementAndGet();
                }
              };
            }
          };
      items = new SqliteItemProjectionBackend(catalog, store.contentSession(), itemReader);
      names = new SqliteItemNamesBackend(catalog, store.contentSession(), itemReader, reader);
    }

    PreparedItemNames prepare() {
      long t = System.nanoTime();
      var i =
          items.prepare(
              new ItemProjectionRequest(
                  item.descriptor(),
                  "enUS",
                  new ItemProjectionLimits(16L << 20, 4 << 20, 200000, 1000, 131072, 131072)),
              NONE);
      report("itemPrepare", t);
      if (seeded && projects.get() == 0) cancelProjection(i);
      t = System.nanoTime();
      var pair = names.prepare(new ItemNamesRequest(i, sparse.descriptor(), "enUS", LIMITS), NONE);
      report("namesPrepare", t);
      if (pair.item().coverage().physicalRows() != ItemNamesScaleData.ITEMS
          || pair.names().coverage().physicalRows() != ItemNamesScaleData.NAMES)
        throw new AssertionError("Projection counts");
      if (pair.names().coverage().projectedUtf8Bytes() > (64L << 20))
        throw new AssertionError("UTF8 corpus bound");
      System.out.printf(
          "projectedNameUtf8Bytes=%d itemArtifact=%s namesArtifact=%s%n",
          pair.names().coverage().projectedUtf8Bytes(),
          pair.item().definition().artifactId(),
          pair.names().definition().artifactId());
      try (var c = catalog.connect();
          var statement = c.createStatement()) {
        for (var expected :
            Map.of(
                    "item_projection_artifacts",
                    1L,
                    "sparse_name_artifacts",
                    1L,
                    "item_projection_rows",
                    (long) ItemNamesScaleData.ITEMS,
                    "sparse_name_rows",
                    (long) ItemNamesScaleData.NAMES)
                .entrySet())
          try (var rs = statement.executeQuery("SELECT count(*) FROM " + expected.getKey())) {
            if (!rs.next() || rs.getLong(1) != expected.getValue())
              throw new AssertionError("Stored shared count " + expected);
          }
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException(e);
      }
      return pair;
    }

    void cancelProjection(PreparedItemProjection item) {
      cancelRows.set(0);
      cancelProjection.set(true);
      projectionCancelled.set(false);
      long started = System.nanoTime();
      boolean observed = false;
      try {
        names.prepare(
            new ItemNamesRequest(item, sparse.descriptor(), "enUS", LIMITS),
            projectionCancelled::get);
      } catch (CancellationException expected) {
        observed = true;
      } finally {
        cancelProjection.set(false);
      }
      if (!observed || cancelRows.get() != 1024 || activeReaders.get() != 0)
        throw new AssertionError("Projection cancellation did not retire: " + cancelRows);
      report("cancelProjectionAfter1024RowsRetired", started);
      try (var c = catalog.connect();
          var statement = c.createStatement();
          var rs =
              statement.executeQuery(
                  "SELECT count(*) FROM sparse_name_artifacts WHERE status='READY'")) {
        if (rs.next() && rs.getLong(1) != 0)
          throw new AssertionError("Canceled projection published READY");
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException(e);
      }
    }

    void run() throws Exception {
      var pair = prepare();
      queries(pair);
      for (String q :
          List.of(
              "sword",
              "Sword",
              "Café",
              "Cafe\u0301",
              "🗡",
              "%_literal",
              "O'Brien",
              "candidate",
              "never-present-authored-token")) search(pair, q);
      AtomicInteger polls = new AtomicInteger();
      long pollStart = System.nanoTime();
      boolean pollCancelled = false;
      try {
        names.search(
            pair,
            new ItemNameLiteralQuery("never-present-authored-token"),
            Optional.empty(),
            50,
            () -> polls.incrementAndGet() >= 10000);
      } catch (CancellationException expected) {
        pollCancelled = true;
      }
      if (!pollCancelled || polls.get() < 10000 || activeReaders.get() != 0)
        throw new AssertionError("Mid-operation cooperative cancellation did not retire");
      report("cancelSearchAt10000TokenPollsRetired", pollStart);
      System.out.println(
          "cancelSearchTokenPolls="
              + polls.get()
              + " (token polls do not identify a decoder/SQL stage)");
      // Wall-time request against actual search; report whether it won before completion.
      AtomicBoolean cancel = new AtomicBoolean();
      var worker = Executors.newSingleThreadExecutor();
      long start = System.nanoTime();
      var future =
          worker.submit(
              () ->
                  names.search(
                      pair,
                      new ItemNameLiteralQuery("never-present-authored-token"),
                      Optional.empty(),
                      50,
                      cancel::get));
      Thread.sleep(5);
      long cancellationRequested = System.nanoTime();
      cancel.set(true);
      boolean retired = false;
      try {
        future.get(30, TimeUnit.SECONDS);
      } catch (ExecutionException e) {
        if (!(e.getCause() instanceof CancellationException)) throw e;
        retired = true;
      } finally {
        worker.shutdown();
        if (!worker.awaitTermination(30, TimeUnit.SECONDS))
          throw new AssertionError("Worker did not retire");
      }
      report("cancelSearchTotal", start);
      report("cancelSearchAfterRequestRetired", cancellationRequested);
      System.out.println(
          "cancellationObserved=" + retired + " (operation may finish before cancellation)");
      if (transport.get() != 0) throw new AssertionError("Transport used");
      if (activeReaders.get() != 0)
        throw new AssertionError("Search cancellation leaked source lease");
    }

    void runRestart() {
      var pair = prepare();
      int before = projects.get();
      queries(pair);
      search(pair, "🗡");
      if (projects.get() != before) throw new AssertionError("Query reprojected");
      if (transport.get() != 0) throw new AssertionError("Restart transport used");
      System.out.println(
          "restartExplicitPrepareThenQueriesNoReprojection=true zeroHTTP="
              + (transport.get() == 0));
    }

    void queries(PreparedItemNames pair) {
      int before = projects.get();
      long t = System.nanoTime();
      var first = names.page(pair, Optional.empty(), 50, NONE);
      report("firstPage", t);
      if (first.rows().size() != 50) throw new AssertionError("Page size");
      t = System.nanoTime();
      var next = names.page(pair, first.nextCursor(), 50, NONE);
      report("nextPage", t);
      if (next.rows().size() != 50) throw new AssertionError("Next page");
      t = System.nanoTime();
      names.lookup(pair, 1001, NONE);
      report("lookup", t);
      if (projects.get() != before) throw new AssertionError("Query reprojected");
    }

    void search(PreparedItemNames pair, String text) {
      int before = projects.get();
      long t = System.nanoTime();
      var result = names.search(pair, new ItemNameLiteralQuery(text), Optional.empty(), 50, NONE);
      report("search:" + text, t);
      List<Long> expected = new ArrayList<>();
      for (int n = 0; n < ItemNamesScaleData.NAMES; n++) {
        int id = ItemNamesScaleData.nameId(n);
        if (id >= 1001 + ItemNamesScaleData.ITEMS) break;
        if (ItemNamesScaleData.name(n).contains(text)) expected.add((long) id);
        if (expected.size() == (text.equals("candidate") ? 100 : 50)) break;
      }
      var actual = result.rows().stream().map(r -> r.item().recordId()).toList();
      if (!expected.subList(0, Math.min(50, expected.size())).equals(actual))
        throw new AssertionError(
            "Search oracle " + text + " expected=" + expected + " actual=" + actual);
      System.out.printf(
          "searchMatchesReturned=%d hasNext=%s%n", actual.size(), result.nextCursor().isPresent());
      if (text.equals("candidate")) {
        t = System.nanoTime();
        var next =
            names.search(pair, new ItemNameLiteralQuery(text), result.nextCursor(), 50, NONE);
        report("searchNextPage", t);
        if (!expected
            .subList(50, 100)
            .equals(next.rows().stream().map(r -> r.item().recordId()).toList()))
          throw new AssertionError("Search continuation oracle");
      }
      if (projects.get() != before) throw new AssertionError("Search reprojected");
    }

    VerifiedSourceAlias alias(String kind, String key, String text) throws Exception {
      byte[] b = text.getBytes(StandardCharsets.UTF_8);
      var object =
          store.publish(
              new ByteArrayInputStream(b),
              new ExpectedObjectChecksum(
                  ChecksumAlgorithm.SHA256,
                  HexFormat.of().formatHex(ItemNamesScaleData.digest("SHA-256", b)),
                  b.length),
              b.length,
              NONE);
      return new VerifiedSourceAlias(
          new ResolvedSourceIdentity(kind, key, "synthetic-v1"),
          object,
          new SourceVerificationEvidence("synthetic-v1", "https://fixture.example/" + kind, NOW));
    }

    void attach(
        SqliteBuildObjectCatalog objects,
        BuildSnapshot build,
        String role,
        VerifiedSourceAlias alias) {
      objects.attachVerifiedObject(
          build.snapshotId(), role, alias.source(), alias.object(), alias.evidence(), NONE);
    }

    SelectedFileAssociation association(
        BuildSnapshot b,
        VerifiedSourceAlias config,
        VerifiedSourceAlias root,
        ItemNamesScaleData.Source source,
        long fdid,
        String ckey,
        long locale) {
      var v =
          new FileVariantAssociation(
              b, root.source(), fdid, ckey, locale, 0, 0, 0, 0, Optional.empty());
      var d =
          new SelectedFileDescriptor(v, source.ekey(), ContentRepresentation.VERIFIER_SEMANTICS);
      var a =
          new VerifiedSourceAlias(
              ContentRepresentation.sourceIdentity(source.ekey()),
              new StoredObject(new ObjectDigest(source.sha256()), source.bytes()),
              new SourceVerificationEvidence(
                  ContentRepresentation.VERIFIER_SEMANTICS,
                  "https://fixture.example/encoded-" + fdid,
                  NOW));
      var receipt =
          new EncodedSourceReceipt(
              EncodedSourceReceipt.Kind.LOOSE,
              a.evidence().sourceUrl(),
              source.ekey(),
              source.bytes(),
              0,
              source.bytes(),
              NOW,
              1,
              source.bytes(),
              "3".repeat(64),
              Optional.empty());
      var p =
          new FileAssociationProof(
              root.source(),
              new ResolvedSourceIdentity("encoding", "authored-scale-EN", "synthetic-v1"),
              "synthetic-association-v2",
              "4".repeat(64),
              OptionalLong.of(source.decodedBytes()),
              List.of(
                  new EncodedCandidate(
                      source.ekey(), OptionalLong.of(source.bytes()), OptionalLong.empty())));
      return new SelectedFileAssociation(
          d,
          config,
          root,
          p,
          new ContentRepresentation(
              v.slot(), a, EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS, receipt));
    }

    void retain(SelectedFileAssociation a, ItemNamesScaleData.Source source) throws Exception {
      var r = a.representation();
      var reservation =
          files.reserve(r.slot(), a.descriptor().actualEncodingKey(), source.bytes(), NONE);
      var worker = files.claimWorker(reservation.token(), NONE);
      files.markPublishing(worker, NONE);
      files.recordVerifiedCompletion(worker, r, NONE);
      try (var in = Files.newInputStream(source.encoded())) {
        store.publish(
            in,
            new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, source.sha256(), source.bytes()),
            source.bytes(),
            NONE);
      }
      files.completePublication(worker, r, NONE);
      files.finishWorker(worker, ContentWorkerFinishOutcome.RETAINED, NONE);
      var scope =
          new RetentionScope(
              a.snapshotId(),
              ScopeKind.ENCODED_FILE,
              List.of(
                  requirement("BUILD_CONFIG", a.configuration()),
                  requirement("ROOT_MANIFEST", a.root()),
                  requirement(a.role(), r.encoded())));
      files.attachAssociation(a, scope, NONE);
    }

    ScopeRequirement requirement(String role, VerifiedSourceAlias a) {
      return new ScopeRequirement(
          role,
          Optional.of(a.source()),
          OptionalLong.of(a.object().length()),
          OptionalLong.empty());
    }

    public void close() {
      if (activeReaders.get() != 0) throw new AssertionError("Sparse lease leak");
      store.close();
    }
  }
}
