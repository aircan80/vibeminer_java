package io.vibeminer.infrastructure.item;

import io.vibeminer.application.*;
import io.vibeminer.application.item.*;
import io.vibeminer.application.lookup.*;
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
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;
import io.vibeminer.protocol.tact.blte.EncodedBlteInputStream;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Authored 61-row WDC5/BLTE fixture, production reader/schema/index/service and real CAS/SQLite.
 * Configuration/root/EN association inputs are explicit synthetic proof-port fixtures. This is not
 * official-source authentication, live data or a dependency on test classes/private resources.
 */
public final class ItemProjectionFixtureBackend implements AutoCloseable {
  public static final int ROWS = 61;
  public static final long FIRST_ID = 1001, COPY_ID = 999999;
  private static final CancellationToken NONE = CancellationToken.none();
  private static final Instant ORIGINAL = Instant.parse("2020-01-01T00:00:00Z");
  private static final String CKEY = "1df6b6dbae36878cea020a62b15b9bd6";
  private final Path root;
  private final SqliteCatalog catalog;
  private final FileObjectStore store;
  private final SqliteSelectedFileCatalog files;
  private final List<SelectedFileAssociation> associations = new ArrayList<>();
  private final ExecutorService itemJobs = Executors.newFixedThreadPool(2),
      workspaceJobs = Executors.newSingleThreadExecutor();
  private final DefaultItemProjectionService actualService;
  private final WorkspaceService workspaces;
  public final AtomicInteger prepares = new AtomicInteger(),
      pages = new AtomicInteger(),
      lookups = new AtomicInteger(),
      projects = new AtomicInteger(),
      activeReaders = new AtomicInteger(),
      transportCalls = new AtomicInteger(),
      selectedOpens = new AtomicInteger();
  public final AtomicBoolean holdPreparation = new AtomicBoolean(),
      preparationEntered = new AtomicBoolean();
  public final AtomicBoolean holdQuery = new AtomicBoolean(), queryEntered = new AtomicBoolean();
  public final AtomicInteger queryGates = new AtomicInteger();
  public volatile ItemProjectionException.Code nextPrepareFailure;

  public ItemProjectionFixtureBackend(Path root, boolean seed) throws Exception {
    this.root = root;
    Files.createDirectories(root);
    catalog = new SqliteCatalog(root.resolve("catalog.sqlite"));
    store = new FileObjectStore(root.resolve("store"));
    SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
    files = new SqliteSelectedFileCatalog(catalog, store, store.contentSession());
    if (seed) seed();
    else restore();
    var workspaceRepository = new SqliteWorkspaceRepository(catalog);
    if (seed) {
      workspaceRepository.save(
          Workspace.create(builds().get(0), "enUS", "Synthetic Item A", ORIGINAL));
      workspaceRepository.save(
          Workspace.create(builds().get(1), "enUS", "Synthetic Item B", ORIGINAL));
      workspaceRepository.save(
          Workspace.create(builds().get(0), "deDE", "Synthetic unsupported locale", ORIGINAL));
    }
    workspaces =
        new DefaultWorkspaceService(
            workspaceRepository, workspaceJobs, Clock.fixed(ORIGINAL, ZoneOffset.UTC));
    var proofPort =
        (SelectedFileGateway)
            Proxy.newProxyInstance(
                SelectedFileGateway.class.getClassLoader(),
                new Class<?>[] {SelectedFileGateway.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("inspectRetained")) {
                    transportCalls.incrementAndGet();
                    throw new AssertionError(
                        "Forbidden network-capable method: " + method.getName());
                  }
                  var association = (SelectedFileAssociation) args[0];
                  ((CancellationToken) args[2]).throwIfCancellationRequested();
                  if (!files
                      .findOriginalAssociation(association.descriptor(), NONE)
                      .orElseThrow()
                      .equals(association))
                    throw new AssertionError("Proof-port fixture changed association");
                  return association.representation();
                });
    var actualReader =
        new RetainedItemProjectionSourceReader(
            store,
            files,
            proofPort,
            Clock.fixed(ORIGINAL.plusSeconds(seed ? 100 : 200), ZoneOffset.UTC));
    ItemProjectionSourceReader reader =
        new ItemProjectionSourceReader() {
          public OpenedProjection open(ItemProjectionRequest request, CancellationToken token) {
            var opened = actualReader.open(request, token);
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
                projects.incrementAndGet();
                preparationEntered.set(true);
                while (holdPreparation.get()) {
                  cancellation.throwIfCancellationRequested();
                  try {
                    Thread.sleep(10);
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                  }
                }
                cancellation.throwIfCancellationRequested();
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

          public VerificationLease verify(PreparedItemProjection handle, CancellationToken token) {
            queryGates.incrementAndGet();
            try {
              queryEntered.set(true);
              while (holdQuery.get()) {
                token.throwIfCancellationRequested();
                try {
                  Thread.sleep(10);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new CancellationException();
                }
              }
              token.throwIfCancellationRequested();
            } finally {
              queryGates.decrementAndGet();
            }
            var lease = actualReader.verify(handle, token);
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
    actualService =
        new DefaultItemProjectionService(
            new SqliteItemProjectionBackend(catalog, store.contentSession(), reader), itemJobs);
  }

  public List<BuildSnapshot> builds() {
    return associations.stream().map(a -> a.descriptor().association().build()).toList();
  }

  public WorkspaceService workspaces() {
    return workspaces;
  }

  public ItemProjectionService service() {
    return new ItemProjectionService() {
      public CompletionStage<PreparedItemProjection> prepare(ItemProjectionRequest request) {
        prepares.incrementAndGet();
        var failure = nextPrepareFailure;
        nextPrepareFailure = null;
        return failure == null
            ? actualService.prepare(request)
            : CompletableFuture.failedFuture(
                new ItemProjectionException(failure, "Synthetic UI fault injection"));
      }

      public CompletionStage<ItemPage> page(
          PreparedItemProjection handle, Optional<ItemPageCursor> cursor, int size) {
        pages.incrementAndGet();
        return actualService.page(handle, cursor, size);
      }

      public CompletionStage<ItemLookupResult> lookup(PreparedItemProjection handle, long id) {
        lookups.incrementAndGet();
        return actualService.lookup(handle, id);
      }

      public void close() {}
    };
  }

  public FileLookupService rootService() {
    return new FileLookupService() {
      public CompletionStage<RootPlan> planRoot(BuildSnapshot build, LookupBudgets budgets) {
        throw new AssertionError("Root is a synthetic retained-only proof-port fixture");
      }

      public CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan plan) {
        throw new AssertionError("No root acquisition");
      }

      public CompletionStage<RootLookupResult> lookupOffline(
          BuildSnapshot build, Set<Long> ids, LookupBudgets budgets) {
        var association = association(build);
        var v = association.descriptor().association();
        var rows =
            ids.contains(v.fileDataId())
                ? List.of(
                    new RootVariant(
                        v.fileDataId(),
                        v.contentKey(),
                        v.localeFlags(),
                        v.contentFlags(),
                        v.rawFlagsFirst(),
                        v.rawFlagsSecond(),
                        v.rawFlagsThird(),
                        v.nameHash()))
                : List.<RootVariant>of();
        return CompletableFuture.completedFuture(
            new RootLookupResult(build, association.root(), ids, rows, 1));
      }

      public void close() {}
    };
  }

  public SelectedFileService selectedService() {
    return new SelectedFileService() {
      public CompletionStage<CandidatePlan> planCandidates(
          BuildSnapshot b, FileVariantAssociation v, SelectedFileBudgets budgets) {
        throw new AssertionError("No candidate metadata needed for retained Item fixture");
      }

      public CompletionStage<FileSelectionProbe> probeLoose(CandidateChoice c) {
        throw new AssertionError("No locator operation");
      }

      public CompletionStage<FileSelectionSearch> searchArchives(
          Loose404Permit p, ArchiveSearchBudgets b) {
        throw new AssertionError("No archive search");
      }

      public CompletionStage<SelectedFileAssociation> retain(SelectedFilePlan p) {
        throw new AssertionError("No selected-file acquisition");
      }

      public CompletionStage<SelectedFileAssociation> inspectOffline(
          FileVariantAssociation v, SelectedFileBudgets budgets) {
        selectedOpens.incrementAndGet();
        try {
          var association = association(v.build());
          if (!association.descriptor().association().equals(v))
            throw new SelectedFileException(
                SelectedFileException.Code.NOT_FOUND, "Fixture exact variant absent");
          verifyEncoded(association);
          return CompletableFuture.completedFuture(association);
        } catch (RuntimeException e) {
          return CompletableFuture.failedFuture(e);
        }
      }

      public CompletionStage<SelectedFileAssociation> inspectOffline(
          SelectedFileDescriptor d, SelectedFileBudgets b) {
        return inspectOffline(d.association(), b);
      }

      public void close() {}
    };
  }

  private SelectedFileAssociation association(BuildSnapshot b) {
    return associations.stream()
        .filter(a -> a.snapshotId().equals(b.snapshotId()))
        .findFirst()
        .orElseThrow();
  }

  public SelectedFileAssociation association() {
    return associations.getFirst();
  }

  public Path sourcePath() {
    String sha = association().representation().encoded().object().digest().value();
    return root.resolve("store/objects").resolve(sha.substring(0, 2)).resolve(sha.substring(2));
  }

  public long storedRows() throws Exception {
    try (var c = catalog.connect();
        var r = c.createStatement().executeQuery("SELECT count(*) FROM item_projection_rows")) {
      if (!r.next()) throw new AssertionError("Missing row count");
      return r.getLong(1);
    }
  }

  public long readyArtifacts() throws Exception {
    try (var c = catalog.connect();
        var r =
            c.createStatement()
                .executeQuery(
                    "SELECT count(*) FROM item_projection_artifacts WHERE status='READY'")) {
      if (!r.next()) throw new AssertionError("Missing artifact count");
      return r.getLong(1);
    }
  }

  private void seed() throws Exception {
    byte[] encoded = encoded();
    var base = baseAssociation(encoded);
    var reservation =
        files.reserve(
            base.representation().slot(),
            base.descriptor().actualEncodingKey(),
            encoded.length,
            NONE);
    var worker = files.claimWorker(reservation.token(), NONE);
    files.markPublishing(worker, NONE);
    files.recordVerifiedCompletion(worker, base.representation(), NONE);
    if (!publish(encoded).equals(base.representation().encoded().object()))
      throw new AssertionError("Encoded fixture object differs");
    var representation = files.completePublication(worker, base.representation(), NONE);
    files.finishWorker(worker, ContentWorkerFinishOutcome.RETAINED, NONE);
    var objects = new SqliteBuildObjectCatalog(catalog, store);
    for (int index = 0; index < 2; index++) {
      var build = build(index);
      objects.registerSnapshot(build);
      var config =
          new VerifiedSourceAlias(
              new ResolvedSourceIdentity("config", build.buildConfig(), "synthetic-v1"),
              publish(("authored config " + index).getBytes(StandardCharsets.US_ASCII)),
              new SourceVerificationEvidence(
                  "synthetic-v1", "https://fixture.example/config-" + index, ORIGINAL));
      var rootAlias =
          new VerifiedSourceAlias(
              base.root().source(),
              publish("authored root proof-port fixture".getBytes(StandardCharsets.US_ASCII)),
              base.root().evidence());
      attach(objects, build, "BUILD_CONFIG", config);
      attach(objects, build, "ROOT_MANIFEST", rootAlias);
      var v = base.descriptor().association();
      var variant =
          new FileVariantAssociation(
              build,
              rootAlias.source(),
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
              ContentRepresentation.VERIFIER_SEMANTICS);
      var association =
          new SelectedFileAssociation(descriptor, config, rootAlias, base.proof(), representation);
      var scope =
          new RetentionScope(
              build.snapshotId(),
              ScopeKind.ENCODED_FILE,
              List.of(
                  requirement("BUILD_CONFIG", config),
                  requirement("ROOT_MANIFEST", rootAlias),
                  requirement(descriptor.role(), representation.encoded())));
      associations.add(files.attachAssociation(association, scope, NONE));
    }
  }

  private void restore() {
    for (int i = 0; i < 2; i++) {
      var b = build(i);
      var base = baseAssociation(encoded());
      var v = base.descriptor().association();
      var variant =
          new FileVariantAssociation(
              b,
              v.rootSource(),
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
              ContentRepresentation.VERIFIER_SEMANTICS);
      associations.add(files.findOriginalAssociation(descriptor, NONE).orElseThrow());
    }
  }

  private StoredObject publish(byte[] bytes) throws Exception {
    return store.publish(
        new ByteArrayInputStream(bytes),
        new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, hash("SHA-256", bytes), bytes.length),
        bytes.length,
        NONE);
  }

  private static ScopeRequirement requirement(String role, VerifiedSourceAlias a) {
    return new ScopeRequirement(
        role, Optional.of(a.source()), OptionalLong.of(a.object().length()), OptionalLong.empty());
  }

  private static void attach(
      SqliteBuildObjectCatalog objects, BuildSnapshot build, String role, VerifiedSourceAlias a) {
    objects.attachVerifiedObject(
        build.snapshotId(), role, a.source(), a.object(), a.evidence(), NONE);
  }

  private void verifyEncoded(SelectedFileAssociation a) {
    try (var lease = store.openRead(a.representation().encoded().object().digest(), NONE);
        var stream =
            new EncodedBlteInputStream(
                lease.stream(),
                a.descriptor().actualEncodingKey(),
                a.representation().encoded().object().length(),
                new EncodedBlteInputStream.Limits(131072, 4096, 1 << 20, 1 << 20),
                () -> false)) {
      stream.transferTo(OutputStream.nullOutputStream());
      if (!stream
          .completionProof()
          .physicalSha256()
          .equals(a.representation().encoded().object().digest().value()))
        throw new AssertionError("Encoded fixture proof changed");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static BuildSnapshot build(int index) {
    return new BuildSnapshot(
        "wow",
        "Synthetic Item fixture",
        "current",
        "us",
        "12.1.5.70448",
        (index == 0 ? "c" : "b").repeat(32),
        "d".repeat(32),
        ORIGINAL,
        true);
  }

  private static SelectedFileAssociation baseAssociation(byte[] bytes) {
    var build = build(0);
    String key = hash("MD5", Arrays.copyOf(bytes, 36));
    var rootSource = new ResolvedSourceIdentity("root", "authored-Item-root", "synthetic-v1");
    var config =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity("config", build.buildConfig(), "synthetic-v1"),
            new StoredObject(new ObjectDigest("1".repeat(64)), 10),
            new SourceVerificationEvidence(
                "synthetic-v1", "https://fixture.example/config", ORIGINAL));
    var root =
        new VerifiedSourceAlias(
            rootSource,
            new StoredObject(new ObjectDigest("2".repeat(64)), 20),
            new SourceVerificationEvidence(
                "synthetic-v1", "https://fixture.example/root", ORIGINAL));
    var variant =
        new FileVariantAssociation(
            build, rootSource, 841626, CKEY, 2, 0, 0, 0, 0, Optional.empty());
    var descriptor =
        new SelectedFileDescriptor(variant, key, ContentRepresentation.VERIFIER_SEMANTICS);
    var alias =
        new VerifiedSourceAlias(
            ContentRepresentation.sourceIdentity(key),
            new StoredObject(new ObjectDigest(hash("SHA-256", bytes)), bytes.length),
            new SourceVerificationEvidence(
                ContentRepresentation.VERIFIER_SEMANTICS,
                "https://fixture.example/encoded-item",
                ORIGINAL));
    var receipt =
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.LOOSE,
            alias.evidence().sourceUrl(),
            key,
            bytes.length,
            0,
            bytes.length,
            ORIGINAL,
            1,
            bytes.length,
            "3".repeat(64),
            Optional.empty());
    var representation =
        new ContentRepresentation(
            variant.slot(), alias, EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS, receipt);
    var proof =
        new FileAssociationProof(
            rootSource,
            new ResolvedSourceIdentity("encoding", "authored-Item-EN", "synthetic-v1"),
            "synthetic-association-v2",
            "4".repeat(64),
            OptionalLong.of(decoded().length),
            List.of(
                new EncodedCandidate(key, OptionalLong.of(bytes.length), OptionalLong.empty())));
    return new SelectedFileAssociation(descriptor, config, root, proof, representation);
  }

  public static byte[] decoded() {
    byte[] a = new byte[664 + ROWS * 15 + 8];
    a[0] = 'W';
    a[1] = 'D';
    a[2] = 'C';
    a[3] = '5';
    var b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(4, 5);
    b.putInt(136, ROWS);
    b.putInt(140, 15);
    b.putInt(144, 11);
    b.putInt(152, 0x50238ec2);
    b.putInt(156, 0x996192aa);
    b.putInt(160, (int) FIRST_ID);
    b.putInt(164, (int) (FIRST_ID + ROWS - 1));
    b.putShort(172, (short) 4);
    b.putShort(174, (short) 0xffff);
    b.putInt(176, 15);
    b.putInt(188, 360);
    b.putInt(200, 1);
    b.putInt(212, 664);
    b.putInt(216, ROWS);
    b.putInt(228, ROWS * 4);
    b.putInt(240, 1);
    for (int i = 0; i < 15; i++) {
      int at = 304 + 24 * i;
      b.putShort(at + 2, (short) 8);
      b.putInt(at + 8, 1);
      b.putInt(at + 16, 8);
    }
    for (int i = 0; i < ROWS; i++) {
      a[664 + i * 11] = (byte) (3 + i);
      b.putInt(664 + ROWS * 11 + i * 4, (int) (FIRST_ID + i));
    }
    b.putInt(664 + ROWS * 15, (int) COPY_ID);
    b.putInt(668 + ROWS * 15, (int) FIRST_ID);
    return a;
  }

  public static byte[] encoded() {
    byte[] decoded = decoded(), a = new byte[37 + decoded.length];
    a[0] = 'B';
    a[1] = 'L';
    a[2] = 'T';
    a[3] = 'E';
    var b = ByteBuffer.wrap(a).order(ByteOrder.BIG_ENDIAN);
    b.putInt(4, 36);
    a[8] = 15;
    a[11] = 1;
    b.putInt(12, decoded.length + 1);
    b.putInt(16, decoded.length);
    a[36] = 'N';
    System.arraycopy(decoded, 0, a, 37, decoded.length);
    System.arraycopy(digest("MD5", Arrays.copyOfRange(a, 36, a.length)), 0, a, 20, 16);
    return a;
  }

  private static byte[] digest(String algorithm, byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static String hash(String algorithm, byte[] bytes) {
    return HexFormat.of().formatHex(digest(algorithm, bytes));
  }

  public void close() {
    holdPreparation.set(false);
    holdQuery.set(false);
    actualService.close();
    itemJobs.shutdown();
    workspaceJobs.shutdown();
    try {
      if (!itemJobs.awaitTermination(10, TimeUnit.SECONDS)
          || !workspaceJobs.awaitTermination(10, TimeUnit.SECONDS))
        throw new IllegalStateException("Fixture workers not retired");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    if (activeReaders.get() != 0) throw new IllegalStateException("Fixture read lease leaked");
    store.close();
  }
}
