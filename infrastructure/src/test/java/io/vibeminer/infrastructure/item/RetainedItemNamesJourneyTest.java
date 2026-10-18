package io.vibeminer.infrastructure.item;

import static org.junit.jupiter.api.Assertions.*;

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
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual CAS, catalog, BLTE/DB2/schema readers, streaming names and SQLite join. Root/EN semantic
 * replay is an explicit authored proof-port fixture; this is not official-source authentication.
 * All network-capable selected-file operations are forbidden, including after restart.
 */
class RetainedItemNamesJourneyTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final ItemProjectionLimits ITEM_LIMITS =
      new ItemProjectionLimits(10000, 4096, 100, 100, 4096, 4096);
  static final SparseNameLimits NAME_LIMITS =
      new SparseNameLimits(
          100000, 10000, 64, 100000, 100000, 10000, 10000, 100, 100, 10000, 65535, 65535, 65535,
          100000, 100000, 1);

  @Test
  void bothActualReadersPrepareJoinAndRestartWithoutNetworkOrDuplicateRows() throws Exception {
    PreparedItemNames first;
    try (var f = new Fixture(true)) {
      var item =
          f.itemBackend.prepare(
              new ItemProjectionRequest(f.item.descriptor(), "enUS", ITEM_LIMITS), NONE);
      first =
          f.backend.prepare(
              new ItemNamesRequest(item, f.sparse.descriptor(), "enUS", NAME_LIMITS), NONE);
      assertSame(item, first.item());
      assertEquals(0, f.leases.get());
      assertEquals(1, f.projects.get());
      assertTrue(first.item().evidence().fullDecoded().isEmpty());
      assertTrue(first.names().evidence().fullDecoded().isEmpty());
      var page = f.backend.page(first, Optional.empty(), 1, NONE);
      assertEquals("Authored α", page.rows().getFirst().name().candidate().orElseThrow().value());
      var next = f.backend.page(first, page.nextCursor(), 1, NONE);
      assertEquals("", next.rows().getFirst().name().candidate().orElseThrow().value());
      assertTrue(next.nextCursor().isEmpty());
      assertEquals(
          ItemLookupResult.Outcome.COPY_UNINDEXED,
          f.backend.lookup(first, 303, NONE).result().item().outcome());
      assertEquals(
          ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
          f.backend.lookup(first, 999, NONE).result().item().outcome());
      assertEquals(1, f.projects.get(), "Queries must not re-enumerate Sparse record area");
      assertEquals(2, f.count("item_projection_rows"));
      assertEquals(2, f.count("sparse_name_rows"));
      assertEquals(0, f.outbound.get());
    }
    try (var f = new Fixture(false)) {
      var page = f.backend.page(first, Optional.empty(), 2, NONE);
      assertEquals(2, page.rows().size());
      assertEquals(first, page.pair());
      assertEquals(0, f.projects.get());
      assertEquals(0, f.outbound.get());
      assertEquals(0, f.leases.get());
      var again =
          f.backend.prepare(
              new ItemNamesRequest(first.item(), f.sparse.descriptor(), "enUS", NAME_LIMITS), NONE);
      assertEquals(first, again);
      assertEquals(1, f.count("sparse_name_artifacts"));
      assertEquals(2, f.count("sparse_name_rows"));
      assertEquals(2, f.count("item_projection_rows"));
      byte[] bad =
          Files.readAllBytes(f.sourcePath(f.sparse.representation().encoded().object().digest()));
      bad[bad.length - 1] ^= 1;
      Files.write(f.sourcePath(f.sparse.representation().encoded().object().digest()), bad);
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(ItemNamesException.class, () -> f.backend.lookup(first, 101, NONE)).code());
      assertEquals(0, f.leases.get());
      assertEquals(0, f.outbound.get());
    }
  }

  final class Fixture implements AutoCloseable {
    final Path data = directory.resolve("objects");
    final FileObjectStore store = new FileObjectStore(data);
    final SqliteCatalog catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    final SqliteSelectedFileCatalog files;
    final SelectedFileAssociation item, sparse;
    final AtomicInteger leases = new AtomicInteger(),
        projects = new AtomicInteger(),
        outbound = new AtomicInteger();
    final SqliteItemProjectionBackend itemBackend;
    final SqliteItemNamesBackend backend;

    Fixture(boolean seed) throws Exception {
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
      files = new SqliteSelectedFileCatalog(catalog, store, store.contentSession());
      var itemBase = ItemSourceFixture.association("12.1.5.70448", Instant.EPOCH);
      var sparseBase = SparseNameSourceFixture.association();
      if (seed) {
        var objects = new SqliteBuildObjectCatalog(catalog, store);
        var build = itemBase.descriptor().association().build();
        objects.registerSnapshot(build);
        var config =
            new VerifiedSourceAlias(
                itemBase.configuration().source(),
                publish("authored config fixture".getBytes()),
                itemBase.configuration().evidence());
        var root =
            new VerifiedSourceAlias(
                itemBase.root().source(),
                publish("authored root proof-port fixture".getBytes()),
                itemBase.root().evidence());
        attach(objects, build, "BUILD_CONFIG", config);
        attach(objects, build, "ROOT_MANIFEST", root);
        retain(itemBase, ItemSourceFixture.encoded(), config, root);
        retain(sparseBase, SparseNameSourceFixture.encoded(), config, root);
      }
      item = files.findOriginalAssociation(itemBase.descriptor(), NONE).orElseThrow();
      sparse = files.findOriginalAssociation(sparseBase.descriptor(), NONE).orElseThrow();
      var gateway =
          (SelectedFileGateway)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SelectedFileGateway.class},
                  (proxy, method, args) -> {
                    if (!method.getName().equals("inspectRetained")) {
                      outbound.incrementAndGet();
                      throw new AssertionError("No network-capable action: " + method.getName());
                    }
                    var association = (SelectedFileAssociation) args[0];
                    assertEquals(
                        association,
                        files
                            .findOriginalAssociation(association.descriptor(), NONE)
                            .orElseThrow());
                    return association.representation();
                  });
      var tracked =
          (ObjectStore)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {ObjectStore.class},
                  (proxy, method, args) -> {
                    if (!method.getName().equals("openRead"))
                      throw new AssertionError("Readers cannot mutate store: " + method);
                    var opened =
                        store.openRead((ObjectDigest) args[0], (CancellationToken) args[1]);
                    leases.incrementAndGet();
                    return new ObjectReadLease() {
                      boolean closed;

                      public StoredObject object() {
                        return opened.object();
                      }

                      public InputStream stream() {
                        return opened.stream();
                      }

                      public void close() {
                        if (!closed) {
                          closed = true;
                          try {
                            opened.close();
                          } finally {
                            leases.decrementAndGet();
                          }
                        }
                      }
                    };
                  });
      var clock = Clock.fixed(Instant.ofEpochSecond(seed ? 100 : 200), ZoneOffset.UTC);
      var actualItem = new RetainedItemProjectionSourceReader(tracked, files, gateway, clock);
      itemBackend = new SqliteItemProjectionBackend(catalog, store.contentSession(), actualItem);
      var actualNames =
          new RetainedSparseNameProjectionSourceReader(tracked, files, gateway, clock);
      var names =
          new SparseNameProjectionSourceReader() {
            public OpenedNames open(ItemNamesRequest request, CancellationToken token) {
              var opened = actualNames.open(request, token);
              assertEquals(2, leases.get());
              return new OpenedNames() {
                public SparseNameArtifactDefinition definition() {
                  return opened.definition();
                }

                public List<CopyDeclaration> copies() {
                  return opened.copies();
                }

                public PreparedSparseNames project(RowSink sink, CancellationToken cancellation) {
                  projects.incrementAndGet();
                  return opened.project(sink, cancellation);
                }

                public void close() {
                  assertEquals(2, leases.get());
                  try {
                    assertEquals(1, count("sparse_name_artifacts WHERE status='READY'"));
                  } catch (Exception failure) {
                    throw new AssertionError(failure);
                  } finally {
                    opened.close();
                  }
                }
              };
            }

            public VerificationLease verify(PreparedSparseNames handle, CancellationToken token) {
              var lease = actualNames.verify(handle, token);
              assertEquals(2, leases.get());
              return () -> {
                assertEquals(2, leases.get());
                lease.close();
              };
            }
          };
      backend = new SqliteItemNamesBackend(catalog, store.contentSession(), actualItem, names);
    }

    void retain(
        SelectedFileAssociation base,
        byte[] bytes,
        VerifiedSourceAlias config,
        VerifiedSourceAlias root) {
      var reservation =
          files.reserve(
              base.representation().slot(),
              base.descriptor().actualEncodingKey(),
              bytes.length,
              NONE);
      var worker = files.claimWorker(reservation.token(), NONE);
      files.markPublishing(worker, NONE);
      files.recordVerifiedCompletion(worker, base.representation(), NONE);
      assertEquals(base.representation().encoded().object(), publish(bytes));
      var actual = files.completePublication(worker, base.representation(), NONE);
      files.finishWorker(worker, ContentWorkerFinishOutcome.RETAINED, NONE);
      var association =
          new SelectedFileAssociation(base.descriptor(), config, root, base.proof(), actual);
      files.attachAssociation(
          association,
          new RetentionScope(
              association.snapshotId(),
              ScopeKind.ENCODED_FILE,
              List.of(
                  requirement("BUILD_CONFIG", config),
                  requirement("ROOT_MANIFEST", root),
                  requirement(association.role(), actual.encoded()))),
          NONE);
    }

    StoredObject publish(byte[] bytes) {
      return store.publish(
          new ByteArrayInputStream(bytes),
          new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, sha(bytes), bytes.length),
          bytes.length,
          NONE);
    }

    Path sourcePath(ObjectDigest digest) {
      return data.resolve("objects")
          .resolve(digest.value().substring(0, 2))
          .resolve(digest.value().substring(2));
    }

    long count(String table) throws Exception {
      try (var c = catalog.connect();
          var q = c.createStatement();
          var r = q.executeQuery("SELECT count(*) FROM " + table)) {
        return r.getLong(1);
      }
    }

    public void close() {
      assertEquals(0, leases.get());
      store.close();
    }
  }

  static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }

  static void attach(
      SqliteBuildObjectCatalog catalog, BuildSnapshot build, String role, VerifiedSourceAlias a) {
    catalog.attachVerifiedObject(
        build.snapshotId(), role, a.source(), a.object(), a.evidence(), NONE);
  }

  static ScopeRequirement requirement(String role, VerifiedSourceAlias a) {
    return new ScopeRequirement(
        role, Optional.of(a.source()), OptionalLong.of(a.object().length()), OptionalLong.empty());
  }
}
