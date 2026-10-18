package io.vibeminer.infrastructure.item;

import io.vibeminer.application.*;
import io.vibeminer.application.item.*;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.ContentWorkerFinishOutcome;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.itemnames.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.protocol.tact.blte.EncodedBlteInputStream;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Native-tool authored Item/Sparse fixture: real retained readers, CAS, catalog, SQL and services.
 * Root/config/EN semantics use an explicit synthetic proof port; this is not official TACT
 * authentication, complete decoded CKey verification or live content. No test/private imports.
 */
public final class ItemNamesFixtureBackend implements AutoCloseable {
  public enum Corpus {
    BROWSE,
    LITERAL_SEARCH
  }

  private final Corpus corpus;
  public static final long FIRST_ID = 1001,
      EMPTY_ID = 1002,
      COPY_ID = ItemNamesFixtureData.COPY_ID,
      MISSING_ID = ItemNamesFixtureData.MISSING_ID,
      SPARSE_ONLY_ID = ItemNamesFixtureData.SPARSE_ONLY_ID;
  public static final ItemProjectionLimits ITEM_LIMITS =
      new ItemProjectionLimits(1 << 20, 4 << 20, 1000, 1000, 131072, 131072);
  public static final SparseNameLimits NAME_LIMITS =
      new SparseNameLimits(
          1 << 20,
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
          16384,
          65535,
          32L << 20,
          128L << 20,
          32);
  private static final CancellationToken NONE = CancellationToken.none();
  private static final Instant ORIGINAL = Instant.parse("2020-01-01T00:00:00Z");
  private final ItemProjectionFixtureBackend items;
  private final SqliteCatalog catalog;
  private final FileObjectStore store;
  private final SqliteSelectedFileCatalog files;
  private final List<SelectedFileAssociation> sparse = new ArrayList<>();
  private final ExecutorService jobs = Executors.newFixedThreadPool(2);
  private final DefaultItemNamesService actualService;
  public final AtomicInteger prepares = new AtomicInteger(),
      pages = new AtomicInteger(),
      lookups = new AtomicInteger(),
      searches = new AtomicInteger(),
      projects = new AtomicInteger(),
      queryVerifications = new AtomicInteger(),
      activeReaders = new AtomicInteger(),
      transportCalls = new AtomicInteger();
  public final AtomicBoolean holdPreparation = new AtomicBoolean(),
      preparationEntered = new AtomicBoolean(),
      holdQuery = new AtomicBoolean(),
      queryEntered = new AtomicBoolean();

  public ItemNamesFixtureBackend(Path root, boolean seed) throws Exception {
    this(root, seed, Corpus.BROWSE);
  }

  public ItemNamesFixtureBackend(Path root, boolean seed, Corpus corpus) throws Exception {
    this.corpus = Objects.requireNonNull(corpus);
    Path marker = root.resolve("names-fixture-corpus.txt");
    if (!seed) {
      Corpus stored =
          Files.exists(marker) ? Corpus.valueOf(Files.readString(marker).strip()) : Corpus.BROWSE;
      if (stored != corpus)
        throw new IllegalArgumentException("Restart must use the original explicit corpus");
    }
    items = new ItemProjectionFixtureBackend(root, seed);
    if (seed) Files.writeString(marker, corpus.name());
    catalog = items.fixtureCatalog();
    store = items.fixtureStore();
    files = items.fixtureFiles();
    if (seed) seedSparse();
    else restoreSparseAssociations();
    var proofPort =
        (SelectedFileGateway)
            Proxy.newProxyInstance(
                SelectedFileGateway.class.getClassLoader(),
                new Class<?>[] {SelectedFileGateway.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("inspectRetained")) {
                    transportCalls.incrementAndGet();
                    throw new AssertionError(
                        "Forbidden transport-capable operation " + method.getName());
                  }
                  var a = (SelectedFileAssociation) args[0];
                  ((CancellationToken) args[2]).throwIfCancellationRequested();
                  if (!files.findOriginalAssociation(a.descriptor(), NONE).orElseThrow().equals(a))
                    throw new AssertionError("Synthetic proof-port association changed");
                  return a.representation();
                });
    var clock = Clock.fixed(ORIGINAL.plusSeconds(seed ? 100 : 200), ZoneOffset.UTC);
    var realItem = new RetainedItemProjectionSourceReader(store, files, proofPort, clock);
    ItemProjectionSourceReader itemReader =
        new ItemProjectionSourceReader() {
          public OpenedProjection open(ItemProjectionRequest request, CancellationToken token) {
            throw new AssertionError("Names must not prepare Item implicitly");
          }

          public VerificationLease verify(PreparedItemProjection handle, CancellationToken token) {
            var lease = realItem.verify(handle, token);
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
    var realSparse = new RetainedSparseNameProjectionSourceReader(store, files, proofPort, clock);
    SparseNameProjectionSourceReader nameReader =
        new SparseNameProjectionSourceReader() {
          public OpenedNames open(ItemNamesRequest request, CancellationToken token) {
            var opened = realSparse.open(request, token);
            activeReaders.incrementAndGet();
            return new OpenedNames() {
              public SparseNameArtifactDefinition definition() {
                return opened.definition();
              }

              public List<CopyDeclaration> copies() {
                return opened.copies();
              }

              public PreparedSparseNames project(RowSink sink, CancellationToken cancellation) {
                projects.incrementAndGet();
                preparationEntered.set(true);
                gate(holdPreparation, cancellation);
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

          public VerificationLease verify(PreparedSparseNames handle, CancellationToken token) {
            var lease = realSparse.verify(handle, token);
            activeReaders.incrementAndGet();
            queryVerifications.incrementAndGet();
            queryEntered.set(true);
            try {
              gate(holdQuery, token);
            } catch (RuntimeException e) {
              try {
                lease.close();
              } finally {
                activeReaders.decrementAndGet();
              }
              throw e;
            }
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
        new DefaultItemNamesService(
            new SqliteItemNamesBackend(catalog, store.contentSession(), itemReader, nameReader),
            jobs);
  }

  public ItemProjectionService itemService() {
    return items.service();
  }

  public WorkspaceService workspaces() {
    return items.workspaces();
  }

  public List<BuildSnapshot> builds() {
    return items.builds();
  }

  public SelectedFileAssociation sparseAssociation(int index) {
    return sparse.get(index);
  }

  public ItemProjectionRequest itemRequest(int index) {
    var build = builds().get(index);
    var base = items.association().descriptor();
    var v = base.association();
    var variant =
        new FileVariantAssociation(
            build,
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
            variant, base.actualEncodingKey(), base.encodedVerifierSemantics());
    return new ItemProjectionRequest(
        files.findOriginalAssociation(descriptor, NONE).orElseThrow().descriptor(),
        "enUS",
        ITEM_LIMITS);
  }

  public ItemNamesRequest namesRequest(PreparedItemProjection item, int index) {
    return new ItemNamesRequest(item, sparse.get(index).descriptor(), "enUS", NAME_LIMITS);
  }

  public ItemNamesService namesService() {
    return new ItemNamesService() {
      public CompletionStage<PreparedItemNames> prepare(ItemNamesRequest request) {
        prepares.incrementAndGet();
        return actualService.prepare(request);
      }

      public CompletionStage<ItemNamesPage> page(
          PreparedItemNames pair, Optional<ItemNamesCursor> cursor, int size) {
        pages.incrementAndGet();
        return actualService.page(pair, cursor, size);
      }

      public CompletionStage<ItemNamesLookup> lookup(PreparedItemNames pair, long id) {
        lookups.incrementAndGet();
        return actualService.lookup(pair, id);
      }

      public CompletionStage<ItemNamesSearchPage> search(
          PreparedItemNames pair,
          ItemNameLiteralQuery query,
          Optional<ItemNamesSearchCursor> cursor,
          int size) {
        searches.incrementAndGet();
        return actualService.search(pair, query, cursor, size);
      }

      public void close() {}
    };
  }

  public FileLookupService rootService() {
    return new FileLookupService() {
      public CompletionStage<RootPlan> planRoot(BuildSnapshot b, LookupBudgets l) {
        throw forbidden("root acquisition");
      }

      public CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan p) {
        throw forbidden("root retention");
      }

      public CompletionStage<RootLookupResult> lookupOffline(
          BuildSnapshot b, Set<Long> ids, LookupBudgets l) {
        var a =
            sparse.stream()
                .filter(v -> v.snapshotId().equals(b.snapshotId()))
                .findFirst()
                .orElseThrow();
        var result = new ArrayList<RootVariant>();
        for (var v :
            List.of(
                itemRequest(builds().get(0).snapshotId().equals(b.snapshotId()) ? 0 : 1)
                    .source()
                    .association(),
                a.descriptor().association()))
          if (ids.contains(v.fileDataId()))
            result.add(
                new RootVariant(
                    v.fileDataId(),
                    v.contentKey(),
                    v.localeFlags(),
                    v.contentFlags(),
                    v.rawFlagsFirst(),
                    v.rawFlagsSecond(),
                    v.rawFlagsThird(),
                    v.nameHash()));
        return CompletableFuture.completedFuture(new RootLookupResult(b, a.root(), ids, result, 2));
      }

      public void close() {}
    };
  }

  public SelectedFileService selectedService() {
    return new SelectedFileService() {
      public CompletionStage<CandidatePlan> planCandidates(
          BuildSnapshot b, FileVariantAssociation v, SelectedFileBudgets l) {
        throw forbidden("candidate discovery");
      }

      public CompletionStage<FileSelectionProbe> probeLoose(CandidateChoice c) {
        throw forbidden("loose probe");
      }

      public CompletionStage<FileSelectionSearch> searchArchives(
          Loose404Permit p, ArchiveSearchBudgets b) {
        throw forbidden("archive search");
      }

      public CompletionStage<SelectedFileAssociation> retain(SelectedFilePlan p) {
        throw forbidden("selected acquisition");
      }

      public CompletionStage<SelectedFileAssociation> inspectOffline(
          FileVariantAssociation v, SelectedFileBudgets b) {
        if (v.fileDataId() != 1572924) return items.selectedService().inspectOffline(v, b);
        try {
          var a =
              sparse.stream()
                  .filter(x -> x.descriptor().association().equals(v))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new SelectedFileException(
                              SelectedFileException.Code.NOT_FOUND,
                              "Exact Sparse fixture variant absent"));
          verifyEncoded(a);
          return CompletableFuture.completedFuture(a);
        } catch (RuntimeException e) {
          return CompletableFuture.failedFuture(e);
        }
      }

      public CompletionStage<SelectedFileAssociation> inspectOffline(
          SelectedFileDescriptor d, SelectedFileBudgets b) {
        if (d.association().fileDataId() != 1572924)
          return items.selectedService().inspectOffline(d, b);
        if (sparse.stream().noneMatch(a -> a.role().equals(d.role())))
          return CompletableFuture.failedFuture(
              new SelectedFileException(
                  SelectedFileException.Code.NOT_FOUND, "Exact retained descriptor absent"));
        return inspectOffline(d.association(), b);
      }

      public void close() {}
    };
  }

  private void seedSparse() throws Exception {
    byte[] bytes = ItemNamesFixtureData.encoded(corpus);
    var first = association(0, bytes);
    var reservation =
        files.reserve(
            first.representation().slot(),
            first.descriptor().actualEncodingKey(),
            bytes.length,
            NONE);
    var worker = files.claimWorker(reservation.token(), NONE);
    files.markPublishing(worker, NONE);
    files.recordVerifiedCompletion(worker, first.representation(), NONE);
    if (!publish(bytes).equals(first.representation().encoded().object()))
      throw new AssertionError("Authored Sparse SHA changed");
    var representation = files.completePublication(worker, first.representation(), NONE);
    files.finishWorker(worker, ContentWorkerFinishOutcome.RETAINED, NONE);
    for (int index = 0; index < 2; index++) {
      var a = association(index, bytes);
      a =
          new SelectedFileAssociation(
              a.descriptor(), a.configuration(), a.root(), a.proof(), representation);
      var scope =
          new RetentionScope(
              a.snapshotId(),
              ScopeKind.ENCODED_FILE,
              List.of(
                  requirement("BUILD_CONFIG", a.configuration()),
                  requirement("ROOT_MANIFEST", a.root()),
                  requirement(a.role(), a.representation().encoded())));
      sparse.add(files.attachAssociation(a, scope, NONE));
    }
    verifyEncoded(sparse.getFirst());
  }

  private void restoreSparseAssociations() {
    for (int n = 0; n < 2; n++)
      sparse.add(
          files
              .findOriginalAssociation(
                  association(n, ItemNamesFixtureData.encoded(corpus)).descriptor(), NONE)
              .orElseThrow());
  }

  private SelectedFileAssociation association(int index, byte[] bytes) {
    var item = files.findOriginalAssociation(itemRequest(index).source(), NONE).orElseThrow();
    var build = builds().get(index);
    int header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(4);
    String ekey = ItemNamesFixtureData.hash("MD5", Arrays.copyOf(bytes, header));
    var variant =
        new FileVariantAssociation(
            build,
            item.root().source(),
            1572924,
            "bb0ecbdc2fd4988ddd3c1c5e95c34165",
            0x202,
            0,
            0,
            0,
            0,
            Optional.empty());
    var descriptor =
        new SelectedFileDescriptor(variant, ekey, ContentRepresentation.VERIFIER_SEMANTICS);
    var alias =
        new VerifiedSourceAlias(
            ContentRepresentation.sourceIdentity(ekey),
            new StoredObject(
                new ObjectDigest(ItemNamesFixtureData.hash("SHA-256", bytes)), bytes.length),
            new SourceVerificationEvidence(
                ContentRepresentation.VERIFIER_SEMANTICS,
                "https://fixture.example/encoded-sparse",
                ORIGINAL));
    var receipt =
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.LOOSE,
            alias.evidence().sourceUrl(),
            ekey,
            bytes.length,
            0,
            bytes.length,
            ORIGINAL,
            1,
            bytes.length,
            "5".repeat(64),
            Optional.empty());
    var proof =
        new FileAssociationProof(
            item.root().source(),
            new ResolvedSourceIdentity("encoding", "authored-Sparse-EN", "synthetic-v1"),
            "synthetic-association-v2",
            "6".repeat(64),
            OptionalLong.of(ItemNamesFixtureData.decoded(corpus).length),
            List.of(
                new EncodedCandidate(ekey, OptionalLong.of(bytes.length), OptionalLong.empty())));
    return new SelectedFileAssociation(
        descriptor,
        item.configuration(),
        item.root(),
        proof,
        new ContentRepresentation(
            variant.slot(), alias, EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS, receipt));
  }

  private StoredObject publish(byte[] bytes) {
    return store.publish(
        new ByteArrayInputStream(bytes),
        new ExpectedObjectChecksum(
            ChecksumAlgorithm.SHA256, ItemNamesFixtureData.hash("SHA-256", bytes), bytes.length),
        bytes.length,
        NONE);
  }

  private static ScopeRequirement requirement(String role, VerifiedSourceAlias a) {
    return new ScopeRequirement(
        role, Optional.of(a.source()), OptionalLong.of(a.object().length()), OptionalLong.empty());
  }

  private void verifyEncoded(SelectedFileAssociation a) {
    try (var lease = store.openRead(a.representation().encoded().object().digest(), NONE);
        var encoded =
            new EncodedBlteInputStream(
                lease.stream(),
                a.descriptor().actualEncodingKey(),
                a.representation().encoded().object().length(),
                new EncodedBlteInputStream.Limits(131072, 4096, 1 << 20, 1 << 20),
                () -> false)) {
      encoded.transferTo(OutputStream.nullOutputStream());
      if (!encoded
          .completionProof()
          .physicalSha256()
          .equals(a.representation().encoded().object().digest().value()))
        throw new AssertionError("Sparse encoded proof changed");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public long storedItemRows() throws Exception {
    return items.storedRows();
  }

  public int outboundCalls() {
    return transportCalls.get() + items.transportCalls.get();
  }

  public long storedNameRows() throws Exception {
    return count("sparse_name_rows");
  }

  public long nameArtifacts() throws Exception {
    return count("sparse_name_artifacts WHERE status='READY'");
  }

  private long count(String trustedTable) throws Exception {
    try (var c = catalog.connect();
        var r = c.createStatement().executeQuery("SELECT count(*) FROM " + trustedTable)) {
      if (!r.next()) throw new AssertionError("Missing count");
      return r.getLong(1);
    }
  }

  public Path sparseSourcePath() {
    String sha = sparse.getFirst().representation().encoded().object().digest().value();
    return storePath().resolve(sha.substring(0, 2)).resolve(sha.substring(2));
  }

  private Path storePath() {
    return items.sourcePath().getParent().getParent();
  }

  public void removeSparseSource() throws IOException {
    Files.delete(sparseSourcePath());
  }

  public void restoreSparseSource() {
    publish(ItemNamesFixtureData.encoded(corpus));
  }

  public void corruptItemSource() throws IOException {
    byte[] bytes = Files.readAllBytes(items.sourcePath());
    bytes[bytes.length - 1] ^= 1;
    Files.write(items.sourcePath(), bytes);
  }

  public void restoreItemSource() throws IOException {
    Files.write(items.sourcePath(), ItemProjectionFixtureBackend.encoded());
  }

  private AssertionError forbidden(String operation) {
    transportCalls.incrementAndGet();
    return new AssertionError("No network-capable fixture operation: " + operation);
  }

  private static void gate(AtomicBoolean hold, CancellationToken token) {
    while (hold.get()) {
      token.throwIfCancellationRequested();
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException();
      }
    }
    token.throwIfCancellationRequested();
  }

  public void close() {
    holdPreparation.set(false);
    holdQuery.set(false);
    actualService.close();
    jobs.shutdown();
    try {
      if (!jobs.awaitTermination(10, TimeUnit.SECONDS))
        throw new IllegalStateException("Names workers not retired");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    if (activeReaders.get() != 0) throw new IllegalStateException("Names source lease leaked");
    items.close();
  }
}
