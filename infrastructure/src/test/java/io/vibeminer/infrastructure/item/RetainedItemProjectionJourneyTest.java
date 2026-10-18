package io.vibeminer.infrastructure.item;

import static io.vibeminer.application.item.ItemProjectionException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.item.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.ContentWorkerFinishOutcome;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real CAS, memberships, retained BLTE/WDC5/schema projection and derived SQL composition. Only
 * existing root/EN semantic replay is an explicit proof-port fixture; no official-source
 * authentication is claimed here and every network-capable gateway operation is forbidden.
 */
class RetainedItemProjectionJourneyTest {
  @TempDir Path directory;
  private static final CancellationToken NONE = CancellationToken.none();
  private static final ItemProjectionLimits LIMITS =
      new ItemProjectionLimits(10000, 4096, 100, 100, 4096, 4096);

  @Test
  void preparePageShareAcrossExactBuildsAndRestartWithoutNetworkOrDuplicateRows() throws Exception {
    PreparedItemProjection first, second;
    try (var f = new Fixture(true)) {
      assertEquals(Map.of("artifacts", 0L, "rows", 0L, "copies", 0L, "contexts", 0L), f.counts());
      f.requireReadyAtClose = true;
      first = f.backend.prepare(request(f.associations.get(0)), NONE);
      assertEquals(0, f.leases.get());
      assertEquals(1, f.projects.get());
      assertTrue(first.evidence().fullDecoded().isEmpty());
      assertEquals(2, first.coverage().physicalRows());
      assertEquals(1, first.coverage().unindexedCopyDeclarations());
      var page = f.backend.page(first, Optional.empty(), 1, NONE);
      assertEquals(List.of(101L), page.rows().stream().map(ItemPhysicalRow::recordId).toList());
      assertEquals(3, page.rows().getFirst().values().getFirst().candidateValue());
      var next = f.backend.page(first, page.nextCursor(), 1, NONE);
      assertEquals(List.of(202L), next.rows().stream().map(ItemPhysicalRow::recordId).toList());
      assertTrue(next.nextCursor().isEmpty());
      assertEquals(
          ItemLookupResult.Outcome.PHYSICAL_MATCH, f.backend.lookup(first, 101, NONE).outcome());
      var copy = f.backend.lookup(first, 303, NONE);
      assertEquals(ItemLookupResult.Outcome.COPY_UNINDEXED, copy.outcome());
      assertEquals(101, copy.copySourceId().orElseThrow());
      assertEquals(
          ItemLookupResult.Outcome.NO_PHYSICAL_MATCH, f.backend.lookup(first, 999, NONE).outcome());
      PreparedItemProjection unpreparedContext;
      try (var opened = f.actual.open(request(f.associations.get(1)), NONE)) {
        unpreparedContext =
            new PreparedItemProjection(opened.definition(), opened.evidence(), first.coverage());
      }
      assertCode(NOT_READY, () -> f.backend.page(unpreparedContext, Optional.empty(), 1, NONE));
      assertEquals(1, f.counts().get("contexts"));
      assertEquals(
          1, f.projects.get(), "A query cannot implicitly prepare another consumer context");
      assertEquals(0, f.leases.get());
      second = f.backend.prepare(request(f.associations.get(1)), NONE);
      assertEquals(first.artifactId(), second.artifactId());
      assertNotEquals(first.snapshotId(), second.snapshotId());
      assertNotEquals(
          first.evidence().association().configuration(),
          second.evidence().association().configuration());
      assertEquals(1, f.projects.get(), "A shared READY artifact must not project its rows again");
      assertEquals(Map.of("artifacts", 1L, "rows", 2L, "copies", 1L, "contexts", 2L), f.counts());
      assertCode(STALE_CURSOR, () -> f.backend.page(second, page.nextCursor(), 1, NONE));
      assertEquals(0, f.leases.get());
      assertEquals(0, f.transportCalls.get());
    }
    try (var f = new Fixture(false)) {
      var before = f.projectionState();
      assertEquals(
          List.of(101L, 202L),
          f.backend.page(first, Optional.empty(), 10, NONE).rows().stream()
              .map(ItemPhysicalRow::recordId)
              .toList());
      assertEquals(
          ItemLookupResult.Outcome.PHYSICAL_MATCH, f.backend.lookup(second, 202, NONE).outcome());
      assertEquals(0, f.projects.get(), "Queries cannot implicitly prepare after restart");
      assertEquals(before, f.projectionState());
      assertEquals(0, f.leases.get());
      assertEquals(0, f.transportCalls.get());
    }
  }

  @Test
  void missingOrCorruptSourceNeverRepairsOrRepreparesReadyRows() throws Exception {
    try (var f = new Fixture(true)) {
      var prepared = f.backend.prepare(request(f.associations.getFirst()), NONE);
      var before = f.projectionState();
      var path = f.sourcePath(prepared.evidence().localVerification().object().digest());
      byte[] bytes = Files.readAllBytes(path), corrupt = bytes.clone();
      corrupt[corrupt.length - 1] ^= 1;
      Files.write(path, corrupt);
      assertCode(CORRUPT, () -> f.backend.lookup(prepared, 101, NONE));
      assertEquals(before, f.projectionState());
      Files.delete(path);
      assertCode(MISSING_LOCAL, () -> f.backend.page(prepared, Optional.empty(), 1, NONE));
      assertEquals(before, f.projectionState());
      assertEquals(1, f.projects.get());
      assertEquals(0, f.leases.get());
      assertEquals(0, f.transportCalls.get());
      assertFalse(Files.exists(path), "Read failures cannot repair the payload");
    }
  }

  @Test
  void canceledActualRowProjectionDiscardsStagingAndRetiresSourceLeaseBeforeRetry()
      throws Exception {
    try (var f = new Fixture(true)) {
      var cancelled = new AtomicBoolean();
      f.cancelAfterFirstRow = cancelled;
      assertThrows(
          CancellationException.class,
          () -> f.backend.prepare(request(f.associations.getFirst()), cancelled::get));
      assertEquals(Map.of("artifacts", 0L, "rows", 0L, "copies", 0L, "contexts", 0L), f.counts());
      assertEquals(0, f.leases.get());
      f.cancelAfterFirstRow = null;
      var prepared = f.backend.prepare(request(f.associations.getFirst()), NONE);
      assertEquals(2, prepared.coverage().physicalRows());
      assertEquals(2, f.projects.get());
      assertEquals(0, f.leases.get());
      assertEquals(0, f.transportCalls.get());
    }
  }

  private final class Fixture implements AutoCloseable {
    final Path data = directory.resolve("objects");
    final SqliteCatalog catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    final FileObjectStore store = new FileObjectStore(data);
    final SqliteSelectedFileCatalog files;
    final List<SelectedFileAssociation> associations = new ArrayList<>();
    final AtomicInteger leases = new AtomicInteger(),
        projects = new AtomicInteger(),
        transportCalls = new AtomicInteger();
    final SqliteItemProjectionBackend backend;
    final RetainedItemProjectionSourceReader actual;
    boolean requireReadyAtClose;
    AtomicBoolean cancelAfterFirstRow;

    Fixture(boolean seed) throws Exception {
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
      files = new SqliteSelectedFileCatalog(catalog, store, store.contentSession());
      if (seed) seed();
      var gateway =
          (SelectedFileGateway)
              Proxy.newProxyInstance(
                  SelectedFileGateway.class.getClassLoader(),
                  new Class<?>[] {SelectedFileGateway.class},
                  (proxy, method, args) -> {
                    if (!method.getName().equals("inspectRetained")) {
                      transportCalls.incrementAndGet();
                      throw new AssertionError(
                          "Transport-capable operation forbidden: " + method.getName());
                    }
                    var association = (SelectedFileAssociation) args[0];
                    ((CancellationToken) args[2]).throwIfCancellationRequested();
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
                  ObjectStore.class.getClassLoader(),
                  new Class<?>[] {ObjectStore.class},
                  (proxy, method, args) -> {
                    if (!method.getName().equals("openRead"))
                      throw new AssertionError(
                          "Source reader cannot mutate CAS: " + method.getName());
                    var lease = store.openRead((ObjectDigest) args[0], (CancellationToken) args[1]);
                    leases.incrementAndGet();
                    return new ObjectReadLease() {
                      boolean closed;

                      public StoredObject object() {
                        return lease.object();
                      }

                      public InputStream stream() {
                        return lease.stream();
                      }

                      public void close() {
                        if (!closed) {
                          closed = true;
                          try {
                            lease.close();
                          } finally {
                            leases.decrementAndGet();
                          }
                        }
                      }
                    };
                  });
      actual =
          new RetainedItemProjectionSourceReader(
              tracked,
              files,
              gateway,
              Clock.fixed(Instant.ofEpochSecond(seed ? 100 : 200), ZoneOffset.UTC));
      ItemProjectionSourceReader reader =
          new ItemProjectionSourceReader() {
            public OpenedProjection open(ItemProjectionRequest request, CancellationToken token) {
              var opened = actual.open(request, token);
              assertEquals(1, leases.get());
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
                  projects.incrementAndGet();
                  return opened.project(
                      row -> {
                        sink.accept(row);
                        if (cancelAfterFirstRow != null) cancelAfterFirstRow.set(true);
                      },
                      cancellation);
                }

                public void close() {
                  try {
                    assertEquals(1, leases.get());
                    if (requireReadyAtClose)
                      assertEquals(1, count("item_projection_artifacts WHERE status='READY'"));
                  } catch (Exception failure) {
                    throw new AssertionError(failure);
                  } finally {
                    opened.close();
                  }
                }
              };
            }

            public VerificationLease verify(
                PreparedItemProjection handle, CancellationToken token) {
              var lease = actual.verify(handle, token);
              assertEquals(1, leases.get());
              return lease;
            }
          };
      backend = new SqliteItemProjectionBackend(catalog, store.contentSession(), reader);
    }

    void seed() throws Exception {
      var base = ItemSourceFixture.association("12.1.5.70448", Instant.EPOCH);
      var objects = new SqliteBuildObjectCatalog(catalog, store);
      var slot = base.representation().slot();
      var reservation =
          files.reserve(
              slot,
              base.descriptor().actualEncodingKey(),
              ItemSourceFixture.encoded().length,
              NONE);
      var worker = files.claimWorker(reservation.token(), NONE);
      files.markPublishing(worker, NONE);
      files.recordVerifiedCompletion(worker, base.representation(), NONE);
      assertEquals(base.representation().encoded().object(), publish(ItemSourceFixture.encoded()));
      var representation = files.completePublication(worker, base.representation(), NONE);
      files.finishWorker(worker, ContentWorkerFinishOutcome.RETAINED, NONE);
      for (int index = 0; index < 2; index++) {
        var b = base.descriptor().association().build();
        var build =
            new BuildSnapshot(
                b.productId(),
                b.editionLabel(),
                b.channel(),
                b.region(),
                b.versionLabel(),
                (index == 0 ? "c" : "b").repeat(32),
                b.cdnConfig(),
                b.observedAt(),
                true);
        objects.registerSnapshot(build);
        var config =
            new VerifiedSourceAlias(
                new ResolvedSourceIdentity("config", build.buildConfig(), "fixture-v1"),
                publish(
                    ("authored config " + index)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                new SourceVerificationEvidence(
                    "fixture-v1",
                    "https://us.cdn.blizzard.com/fixture/config-" + index,
                    Instant.EPOCH));
        var root =
            new VerifiedSourceAlias(
                base.root().source(),
                publish(
                    "authored root proof-port fixture"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                base.root().evidence());
        attach(objects, build, "BUILD_CONFIG", config);
        attach(objects, build, "ROOT_MANIFEST", root);
        var v = base.descriptor().association();
        var variant =
            new FileVariantAssociation(
                build,
                root.source(),
                v.fileDataId(),
                v.contentKey(),
                v.localeFlags(),
                v.contentFlags(),
                v.rawFlagsFirst(),
                v.rawFlagsSecond(),
                v.rawFlagsThird(),
                v.nameHash());
        var descriptor =
            new SelectedFileDescriptor(
                variant,
                base.descriptor().actualEncodingKey(),
                base.descriptor().encodedVerifierSemantics());
        var association =
            new SelectedFileAssociation(descriptor, config, root, base.proof(), representation);
        var scope =
            new RetentionScope(
                build.snapshotId(),
                ScopeKind.ENCODED_FILE,
                List.of(
                    requirement("BUILD_CONFIG", config),
                    requirement("ROOT_MANIFEST", root),
                    requirement(descriptor.role(), representation.encoded())));
        associations.add(files.attachAssociation(association, scope, NONE));
      }
    }

    StoredObject publish(byte[] bytes) throws Exception {
      var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      return store.publish(
          new ByteArrayInputStream(bytes),
          new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, sha, bytes.length),
          bytes.length,
          NONE);
    }

    Path sourcePath(ObjectDigest digest) {
      return data.resolve("objects")
          .resolve(digest.value().substring(0, 2))
          .resolve(digest.value().substring(2));
    }

    long count(String table) throws Exception {
      try (var connection = catalog.connect();
          var rows = connection.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
        assertTrue(rows.next());
        return rows.getLong(1);
      }
    }

    Map<String, Long> counts() throws Exception {
      return Map.of(
          "artifacts",
          count("item_projection_artifacts"),
          "rows",
          count("item_projection_rows"),
          "copies",
          count("item_projection_copies"),
          "contexts",
          count("item_projection_contexts"));
    }

    List<String> projectionState() throws Exception {
      var state = new ArrayList<String>();
      try (var connection = catalog.connect()) {
        for (String table :
            List.of(
                "item_projection_artifacts",
                "item_projection_rows",
                "item_projection_copies",
                "item_projection_contexts")) {
          try (var rows = connection.createStatement().executeQuery("SELECT * FROM " + table)) {
            while (rows.next()) {
              var row = new ArrayList<String>();
              for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                var value = rows.getObject(i);
                row.add(
                    value instanceof byte[] bytes
                        ? HexFormat.of().formatHex(bytes)
                        : String.valueOf(value));
              }
              state.add(table + row);
            }
          }
        }
      }
      state.sort(String::compareTo);
      return state;
    }

    public void close() {
      assertEquals(0, leases.get());
      store.close();
    }
  }

  private static ItemProjectionRequest request(SelectedFileAssociation association) {
    return new ItemProjectionRequest(association.descriptor(), "enUS", LIMITS);
  }

  private static ScopeRequirement requirement(String role, VerifiedSourceAlias alias) {
    return new ScopeRequirement(
        role,
        Optional.of(alias.source()),
        OptionalLong.of(alias.object().length()),
        OptionalLong.empty());
  }

  private static void attach(
      SqliteBuildObjectCatalog objects,
      BuildSnapshot build,
      String role,
      VerifiedSourceAlias alias) {
    objects.attachVerifiedObject(
        build.snapshotId(), role, alias.source(), alias.object(), alias.evidence(), NONE);
  }

  private static void assertCode(
      ItemProjectionException.Code expected, org.junit.jupiter.api.function.Executable action) {
    assertEquals(expected, assertThrows(ItemProjectionException.class, action).code());
  }
}
