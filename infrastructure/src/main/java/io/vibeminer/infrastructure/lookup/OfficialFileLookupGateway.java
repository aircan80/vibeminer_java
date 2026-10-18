package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.application.lookup.FileLookupException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.format.wow.root.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.tact.config.*;
import io.vibeminer.protocol.tact.encoding.*;
import java.io.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exact-root source/protocol adapter. No current pointers, target payloads, archives or UI
 * dependencies.
 */
public final class OfficialFileLookupGateway implements FileLookupGateway, AutoCloseable {
  private static final String CONFIG_SEMANTICS = "tact-config-raw-md5-v1";
  private static final String ROOT_SEMANTICS =
      "blte-nz-ekey-chunks-full-ckey+tsfm-v1v2-structural-eof-r1";
  private static final String ROOT_NAMESPACE = "wow.root.tsfm.v1v2";
  private final ObjectStore store;
  private final LookupTransport transport;
  private final Clock clock;
  private final Map<String, RootPlan> sessions = new LinkedHashMap<>();
  private boolean closed;

  public OfficialFileLookupGateway(
      ObjectStore store,
      PinnedCdnTransport configurations,
      PinnedCdnRangeTransport ranges,
      Clock clock) {
    this(
        store,
        new LookupTransport() {
          public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
            return configurations.fetch(request);
          }

          public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
            return ranges.fetch(request);
          }
        },
        clock);
  }

  OfficialFileLookupGateway(ObjectStore store, LookupTransport transport, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.transport = Objects.requireNonNull(transport);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public ResolvedSourceIdentity configurationIdentity(BuildSnapshot build) {
    return new ResolvedSourceIdentity("tact.config.raw-md5", build.buildConfig(), CONFIG_SEMANTICS);
  }

  @Override
  public RootPlan prepareRoot(
      BuildSnapshot build,
      Optional<VerifiedSourceAlias> retainedConfiguration,
      LookupBudgets budgets,
      CancellationToken cancellation) {
    try {
      ensureOpen();
      var work = new LookupWork(budgets, cancellation, true);
      work.check();
      var route = CapturedBuildSource.resolve(build);
      VerifiedSourceAlias configuration =
          retainedConfiguration.orElseGet(() -> acquireConfiguration(build, route, budgets, work));
      var config = configuration(build, configuration, budgets, work);
      String contentKey = rootContentKey(config);
      var encoding = config.encodingBootstrap();
      var object =
          new RemoteBlteObject(
              route,
              encoding.encodingKey(),
              encoding.encodedSize(),
              encoding.decodedSize(),
              transport,
              budgets,
              work,
              true);
      if (budgets.maximumPageBytes() < 1024)
        throw LookupWork.fail(BUDGET_EXCEEDED, "EN minimum page exceeds selected-page budget");
      var limits =
          new IndexedEncodingParser.Limits(
              100000,
              budgets.maximumIndexBytes(),
              budgets.maximumPageBytes(),
              65536,
              Math.min(255, budgets.maximumCandidates()));
      var parser = new IndexedEncodingParser();
      var layout = parser.parseHeader(object.authenticatedWindow(0, 22), limits);
      if (layout.minimumDecodedBytes() > encoding.decodedSize())
        throw LookupWork.fail(INTEGRITY_FAILURE, "EN layout exceeds pinned decoded source");
      var content =
          index(parser, layout, IndexedEncodingParser.Table.CONTENT, object, limits, work);
      var encoded =
          index(parser, layout, IndexedEncodingParser.Table.ENCODING, object, limits, work);
      var page =
          content
              .pageFor(contentKey)
              .orElseThrow(() -> LookupWork.fail(UNAVAILABLE, "Root CKey has no selected EN page"));
      var entry =
          parser
              .lookupContent(
                  content,
                  page,
                  object.selectedPage(page.decodedOffset(), page.length()),
                  contentKey,
                  limits,
                  work::cancelled)
              .orElseThrow(
                  () ->
                      LookupWork.fail(
                          UNAVAILABLE,
                          "Root CKey is absent from selected supported EN" + " scope"));
      var candidates = new ArrayList<RootCandidate>();
      for (String key : entry.encodingKeys()) {
        work.check();
        var reference = encoded.pageFor(key);
        if (reference.isEmpty()) {
          candidates.add(new RootCandidate(key, -1, -1));
          continue;
        }
        var selected = reference.get();
        var metadata =
            parser.lookupEncoding(
                encoded,
                selected,
                object.selectedPage(selected.decodedOffset(), selected.length()),
                key,
                limits,
                work::cancelled);
        candidates.add(
            metadata
                .map(value -> new RootCandidate(key, value.encodedSize(), value.specIndex()))
                .orElseGet(() -> new RootCandidate(key, -1, -1)));
      }
      var plan =
          new RootPlan(
              UUID.randomUUID().toString(),
              build,
              configuration,
              contentKey,
              entry.decodedSize(),
              candidates,
              budgets);
      synchronized (sessions) {
        ensureOpen();
        while (sessions.size() >= 16) sessions.remove(sessions.keySet().iterator().next());
        sessions.put(plan.sessionId(), plan);
      }
      return plan;
    } catch (Exception failed) {
      throw LookupWork.failure(failed);
    }
  }

  /** Bounded selected facts from a complete local EN pass, never a complete EN inventory map. */
  public record RetainedEncodingResolution(
      RootPlan root,
      Map<String, EncodingManifest.ContentEntry> contents,
      Map<String, EncodingManifest.EncodedEntry> encodings) {
    public RetainedEncodingResolution {
      Objects.requireNonNull(root);
      contents = Map.copyOf(contents);
      encodings = Map.copyOf(encodings);
    }
  }

  /**
   * Issues a genuine session on this gateway from one complete retained EN scan. The caller may
   * group a bounded set of other required metadata CKeys with ROOT. Missing CKeys and missing
   * E-table facts remain different: the former is absent from contents, the latter from encodings.
   * Candidate order and duplicate references are preserved. No HTTP, current-pointer read, durable
   * derived index, or second gateway is used. All selected facts remain private until complete EN
   * grammar, decoded CKey, encoded EKey/chunk hashes, physical SHA and source EOF succeed.
   *
   * <p>The caller budgets this additional local decode and the parser's two page grammar walks in
   * its whole Open operation. The supplied BLTE and EN limits apply incrementally; existing root
   * budgets govern the issued root plan and bound selected-key/candidate memory.
   */
  public RetainedEncodingResolution prepareRootFromRetainedEncoding(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      MetadataContentProof encodingProof,
      Set<String> selectedContentKeys,
      io.vibeminer.protocol.tact.blte.StreamingDecodedBlteInputStream.Limits blteLimits,
      FullEncodingInventory.Limits encodingLimits,
      LookupBudgets budgets,
      CancellationToken cancellation) {
    try {
      ensureOpen();
      Objects.requireNonNull(encodingProof);
      Objects.requireNonNull(selectedContentKeys);
      Objects.requireNonNull(blteLimits);
      Objects.requireNonNull(encodingLimits);
      Objects.requireNonNull(budgets);
      var work = new LookupWork(budgets, cancellation, false);
      work.check();
      var config = configuration(build, configuration, budgets, work);
      String rootKey = rootContentKey(config);
      var declaration = config.encodingBootstrap();
      var selected = new HashSet<String>();
      int maximumSelectedKeys = Math.min(32, budgets.maximumSelectedIds());
      if (selectedContentKeys.size() > maximumSelectedKeys)
        throw LookupWork.fail(BUDGET_EXCEEDED, "Too many selected metadata CKeys");
      for (String key : selectedContentKeys) {
        work.check();
        if (key == null || !key.matches("[0-9a-f]{32}"))
          throw LookupWork.fail(
              INVALID_REQUEST, "Complete lowercase selected metadata CKeys required");
        selected.add(key);
      }
      selected.add(rootKey);
      if (selected.size() > maximumSelectedKeys)
        throw LookupWork.fail(BUDGET_EXCEEDED, "ROOT and selected metadata exceed CKey bound");
      long maximumCandidates;
      try {
        maximumCandidates = Math.multiplyExact((long) selected.size(), budgets.maximumCandidates());
      } catch (ArithmeticException overflow) {
        throw LookupWork.fail(BUDGET_EXCEEDED, "Selected metadata candidate product overflows");
      }
      var alias = encodingProof.originalAlias();
      var expectedSource =
          new ResolvedSourceIdentity(
              "tact.encoding.v1",
              declaration.contentKey()
                  + ";"
                  + declaration.encodingKey()
                  + ";"
                  + declaration.decodedSize()
                  + ";"
                  + declaration.encodedSize(),
              "blte-complete-encoded-and-ckey-stream-v1");
      if (!alias.source().equals(expectedSource)
          || alias.object().length() != declaration.encodedSize()
          || !encodingProof.declaredContentKey().equals(declaration.contentKey())
          || !encodingProof.actualEncodingKey().equals(declaration.encodingKey())
          || encodingProof.decodedAttestation().isEmpty()
          || !encodingProof
              .decodedAttestation()
              .get()
              .verifierRevision()
              .equals("blte-nz-full-ckey-stream-v1")
          || encodingProof.structuralAttestation().isEmpty()
          || encodingProof.structuralAttestation().get().kind()
              != MetadataStructureAttestation.Kind.ENCODING
          || !encodingProof
              .structuralAttestation()
              .get()
              .parserRevision()
              .equals("encoding-v1-full-r1"))
        throw LookupWork.fail(
            UNSUPPORTED, "Retained EN lacks the exact admitted source and full capabilities");
      CapturedBuildSource.validateDataProof(
          alias.evidence().sourceUrl(), declaration.encodingKey());
      var contents = new LinkedHashMap<String, EncodingManifest.ContentEntry>();
      var needed = new HashSet<String>();
      var encodings = new LinkedHashMap<String, EncodingManifest.EncodedEntry>();
      try (var lease = store.openRead(alias.object().digest(), work::cancelled)) {
        if (!lease.object().equals(alias.object()))
          throw LookupWork.fail(INTEGRITY_FAILURE, "Retained EN physical geometry changed");
        try (var decoded =
            new io.vibeminer.protocol.tact.blte.StreamingDecodedBlteInputStream(
                lease.stream(),
                declaration.encodingKey(),
                declaration.contentKey(),
                declaration.encodedSize(),
                declaration.decodedSize(),
                blteLimits,
                work::cancelled)) {
          new FullEncodingInventory()
              .read(
                  decoded,
                  encodingLimits,
                  new FullEncodingInventory.Consumer() {
                    public void header(IndexedEncodingParser.Layout layout) {}

                    public void specification(int index, String value) {}

                    public void trailer(String value) {}

                    public void content(
                        int page, long ordinal, EncodingManifest.ContentEntry entry) {
                      work.check();
                      if (!selected.contains(entry.contentKey())) return;
                      if (entry.encodingKeys().size() > budgets.maximumCandidates())
                        throw LookupWork.fail(
                            BUDGET_EXCEEDED, "Selected metadata candidate list exceeds bound");
                      contents.put(entry.contentKey(), entry);
                      needed.addAll(entry.encodingKeys());
                      if (needed.size() > maximumCandidates)
                        throw LookupWork.fail(
                            BUDGET_EXCEEDED, "Selected metadata EKey product exceeds bound");
                    }

                    public void encoding(
                        int page, long ordinal, EncodingManifest.EncodedEntry entry) {
                      work.check();
                      if (needed.contains(entry.encodingKey()))
                        encodings.put(entry.encodingKey(), entry);
                    }
                  },
                  work::cancelled);
          var completed = decoded.completionProof();
          if (!completed.encodedProof().physicalSha256().equals(alias.object().digest().value())
              || completed.encodedProof().encodedBytes() != alias.object().length())
            throw LookupWork.fail(INTEGRITY_FAILURE, "Retained EN physical proof changed");
        }
      }
      work.check();
      var root = contents.get(rootKey);
      if (root == null)
        throw LookupWork.fail(UNAVAILABLE, "ROOT CKey absent from complete retained EN");
      var candidates = new ArrayList<RootCandidate>();
      for (String key : root.encodingKeys()) {
        var fact = encodings.get(key);
        candidates.add(
            fact == null
                ? new RootCandidate(key, -1, -1)
                : new RootCandidate(key, fact.encodedSize(), fact.specIndex()));
      }
      var plan =
          new RootPlan(
              UUID.randomUUID().toString(),
              build,
              configuration,
              rootKey,
              root.decodedSize(),
              candidates,
              budgets);
      synchronized (sessions) {
        ensureOpen();
        work.check();
        while (sessions.size() >= 16) sessions.remove(sessions.keySet().iterator().next());
        sessions.put(plan.sessionId(), plan);
      }
      return new RetainedEncodingResolution(plan, contents, encodings);
    } catch (FullEncodingInventoryException protocol) {
      throw retainedEncodingFailure(protocol.reason().name(), protocol);
    } catch (io.vibeminer.protocol.tact.blte.StreamingDecodedBlteException protocol) {
      throw retainedEncodingFailure(protocol.reason().name(), protocol);
    } catch (io.vibeminer.protocol.tact.blte.EncodedBlteException protocol) {
      throw retainedEncodingFailure(protocol.reason().name(), protocol);
    } catch (Exception failed) {
      throw LookupWork.failure(failed);
    }
  }

  private static RuntimeException retainedEncodingFailure(String reason, Exception cause) {
    if (reason.equals("CANCELLED")) {
      var stopped = new java.util.concurrent.CancellationException(cause.getMessage());
      stopped.initCause(cause);
      return stopped;
    }
    return new FileLookupException(
        switch (reason) {
          case "LIMIT" -> BUDGET_EXCEEDED;
          case "UNSUPPORTED" -> UNSUPPORTED;
          case "IO" -> STORAGE_FAILURE;
          default -> INTEGRITY_FAILURE;
        },
        cause.getMessage(),
        cause);
  }

  @Override
  public void validateSelection(RootSelectionPlan selection, CancellationToken cancellation) {
    try {
      ensureOpen();
      cancellation.throwIfCancellationRequested();
      RootPlan canonical;
      synchronized (sessions) {
        canonical = sessions.get(selection.plan().sessionId());
      }
      if (canonical == null
          || !canonical.equals(selection.plan())
          || !canonical.candidates().contains(selection.candidate()))
        throw LookupWork.fail(INVALID_PLAN, "Unknown, changed or evicted canonical root plan");
      checkRootBudget(
          selection.plan().decodedBytes(),
          selection.candidate().encodedBytes(),
          selection.plan().budgets(),
          true);
      var work = new LookupWork(canonical.budgets(), cancellation, true);
      if (!rootContentKey(
              configuration(
                  canonical.build(), canonical.configuration(), canonical.budgets(), work))
          .equals(canonical.contentKey()))
        throw LookupWork.fail(
            INVALID_PLAN, "Canonical configuration no longer matches root source");
    } catch (Exception failed) {
      throw LookupWork.failure(failed);
    }
  }

  @Override
  public ResolvedSourceIdentity rootIdentity(RootSelectionPlan selection) {
    return identity(
        selection.plan().contentKey(),
        selection.candidate().encodingKey(),
        selection.plan().decodedBytes(),
        selection.candidate().encodedBytes());
  }

  @Override
  public VerifiedSourceAlias acquireRoot(
      RootSelectionPlan selection, CancellationToken cancellation) {
    return acquireRootInternal(selection, MetadataPublicationObserver.none(), cancellation, false);
  }

  @Override
  public VerifiedSourceAlias acquireRoot(
      RootSelectionPlan selection,
      MetadataPublicationObserver observer,
      CancellationToken cancellation) {
    return acquireRootInternal(selection, Objects.requireNonNull(observer), cancellation, true);
  }

  private VerifiedSourceAlias acquireRootInternal(
      RootSelectionPlan selection,
      MetadataPublicationObserver observer,
      CancellationToken cancellation,
      boolean guarded) {
    boolean[] gateReturned = {false};
    try {
      store.publisherStarted();
      validateSelection(selection, cancellation);
      var plan = selection.plan();
      var candidate = selection.candidate();
      var budgets = plan.budgets();
      var work = new LookupWork(budgets, cancellation, false);
      var route = CapturedBuildSource.resolve(plan.build());
      var remote =
          new RemoteBlteObject(
              route,
              candidate.encodingKey(),
              candidate.encodedBytes(),
              plan.decodedBytes(),
              transport,
              budgets,
              work,
              false);
      for (var chunk : remote.plan.chunks()) remote.checkChunk(chunk);
      StoredObject object;
      var prospective = new AtomicReference<MetadataContentProof>();
      var physical = MessageDigest.getInstance("SHA-256");
      try (var encoded = new VerifiedRootStreams.Encoded(remote, plan.contentKey(), work);
          var digesting = new DigestInputStream(encoded, physical)) {
        object =
            store.publishVerified(
                digesting,
                candidate.encodedBytes(),
                budgets.maximumRootEncodedBytes(),
                token -> {
                  encoded.verifyComplete(token);
                  var physicalObject =
                      new StoredObject(
                          new ObjectDigest(HexFormat.of().formatHex(physical.digest())),
                          candidate.encodedBytes());
                  var alias =
                      new VerifiedSourceAlias(
                          rootIdentity(selection),
                          physicalObject,
                          new SourceVerificationEvidence(
                              ROOT_SEMANTICS,
                              new CdnResource(route, candidate.encodingKey(), CdnResource.Kind.DATA)
                                  .uri()
                                  .toString(),
                              clock.instant()));
                  var proof = rootProof(alias, false);
                  prospective.set(proof);
                  observer.beforePromotion(proof, token);
                  gateReturned[0] = true;
                },
                cancellation);
      }
      var receipt = prospective.get().originalAlias();
      if (!receipt.object().equals(object))
        throw LookupWork.fail(
            INTEGRITY_FAILURE, "Published physical metadata differs from prospective receipt");
      inspect(plan.build(), plan.configuration(), receipt, Set.of(), budgets, cancellation, work);
      // Preserve the original actual source receipt; only capability grows after parsing.
      observer.afterStructureValidation(rootProof(receipt, true), cancellation);
      return receipt;
    } catch (Exception failed) {
      var classified = LookupWork.failure(failed);
      if (!guarded) throw classified;
      throw new MetadataAcquisitionFailure(
          gateReturned[0]
              ? MetadataAcquisitionFailure.Publication.UNCERTAIN
              : MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
          classified);
    }
  }

  private static MetadataContentProof rootProof(VerifiedSourceAlias alias, boolean structural) {
    var tuple = alias.source().immutableSourceKey().split(";", -1);
    long decoded = Long.parseLong(tuple[2]);
    return new MetadataContentProof(
        alias,
        tuple[1],
        tuple[0],
        OptionalLong.of(decoded),
        Optional.of(new DecodedContentAttestation(tuple[0], decoded, "blte-nz-full-ckey-v1")),
        structural
            ? Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.ROOT, "wow-root-tsfm-v1v2-r1"))
            : Optional.empty());
  }

  @Override
  public RootLookupResult lookupRetained(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      Set<Long> ids,
      LookupBudgets budgets,
      MetadataPublicationObserver observer,
      CancellationToken cancellation) {
    var result = lookupRetained(build, configuration, root, ids, budgets, cancellation);
    cancellation.throwIfCancellationRequested();
    observer.afterStructureValidation(rootProof(root, true), cancellation);
    return result;
  }

  @Override
  public RootLookupResult lookupRetained(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      Set<Long> ids,
      LookupBudgets budgets,
      CancellationToken cancellation) {
    try {
      return inspect(
          build,
          configuration,
          root,
          ids,
          budgets,
          cancellation,
          new LookupWork(budgets, cancellation, false));
    } catch (Exception failed) {
      throw LookupWork.failure(failed);
    }
  }

  private RootLookupResult inspect(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      Set<Long> ids,
      LookupBudgets budgets,
      CancellationToken cancellation,
      LookupWork work)
      throws Exception {
    ensureOpen();
    work.check();
    if (ids.size() > budgets.maximumSelectedIds())
      throw LookupWork.fail(BUDGET_EXCEEDED, "Selected FileDataID count exceeds budget");
    var config = configuration(build, configuration, budgets, work);
    String ckey = rootContentKey(config);
    var tuple = parseRootIdentity(root, ckey);
    checkRootBudget(tuple.decoded(), tuple.encoded(), budgets, false);
    if (root.object().length() != tuple.encoded())
      throw LookupWork.fail(
          INTEGRITY_FAILURE, "Retained root encoded length differs from pinned identity");
    CapturedBuildSource.validateDataProof(root.evidence().sourceUrl(), tuple.ekey());
    try (var lease = store.openRead(root.object().digest(), cancellation);
        var decoded =
            new VerifiedRootStreams.Decoded(
                lease.stream(),
                tuple.ekey(),
                ckey,
                tuple.encoded(),
                tuple.decoded(),
                budgets,
                work)) {
      if (!lease.object().equals(root.object()))
        throw LookupWork.fail(INTEGRITY_FAILURE, "Retained root physical metadata changed");
      var parserLimits =
          new SelectedRootLookup.Limits(
              budgets.maximumRootDecodedBytes(),
              budgets.maximumRootRecords(),
              100000,
              budgets.maximumRootRecords(),
              budgets.maximumSelectedIds(),
              budgets.maximumMatches());
      var memberships = new SelectedRootLookup().read(decoded, ids, parserLimits, work::cancelled);
      decoded.requireComplete();
      var variants = new ArrayList<RootVariant>();
      for (long id : ids.stream().sorted().toList())
        for (var entry : memberships.findFileDataId(id))
          variants.add(
              new RootVariant(
                  entry.fileDataId(),
                  entry.contentKey(),
                  entry.localeFlags(),
                  entry.contentFlags(),
                  entry.rawContentFlags().first(),
                  entry.rawContentFlags().second(),
                  entry.rawContentFlags().third(),
                  entry.nameHash()));
      return new RootLookupResult(build, root, ids, variants, memberships.totalRecordCount());
    }
  }

  private VerifiedSourceAlias acquireConfiguration(
      BuildSnapshot build, CdnRequest.Source route, LookupBudgets budgets, LookupWork work) {
    int maximum = (int) Math.min(1L << 20, budgets.maximumConfigurationBytes());
    var request = new CdnRequest(route, CdnRequest.Kind.CONFIG, build.buildConfig(), maximum, -1);
    work.reserve(maximum);
    var acquired = work.await(transport.configuration(request));
    byte[] raw = acquired.bytes();
    if (!request.equals(acquired.request()) || raw.length > maximum)
      throw LookupWork.fail(
          INTEGRITY_FAILURE, "Configuration receipt does not match pinned request");
    work.settle(maximum, raw.length);
    var object =
        store.publish(
            new ByteArrayInputStream(raw),
            new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, build.buildConfig(), raw.length),
            maximum,
            work::cancelled);
    return new VerifiedSourceAlias(
        configurationIdentity(build),
        object,
        new SourceVerificationEvidence(
            CONFIG_SEMANTICS, request.uri().toString(), acquired.receivedAt()));
  }

  private TactConfig configuration(
      BuildSnapshot build, VerifiedSourceAlias receipt, LookupBudgets budgets, LookupWork work)
      throws Exception {
    CapturedBuildSource.resolve(build);
    if (!receipt.source().equals(configurationIdentity(build)))
      throw LookupWork.fail(INTEGRITY_FAILURE, "Configuration receipt has wrong exact identity");
    CapturedBuildSource.validateConfigurationProof(
        receipt.evidence().sourceUrl(), build.buildConfig());
    int maximum = (int) Math.min(1L << 20, budgets.maximumConfigurationBytes());
    if (receipt.object().length() > maximum)
      throw LookupWork.fail(BUDGET_EXCEEDED, "Configuration exceeds metadata byte cap");
    try (var lease = store.openRead(receipt.object().digest(), work::cancelled)) {
      byte[] bytes = lease.stream().readNBytes(maximum + 1);
      work.check();
      if (!lease.object().equals(receipt.object()) || bytes.length != receipt.object().length())
        throw LookupWork.fail(INTEGRITY_FAILURE, "Configuration physical length changed");
      return new TactConfigParser()
          .parse(
              bytes,
              build.buildConfig(),
              new TactConfigParser.Limits(maximum, 20000, maximum, 20000, 2L << 30, 2L << 30),
              work::cancelled);
    }
  }

  private static String rootContentKey(TactConfig config) {
    var values = config.values().get("root");
    if (values == null || values.size() != 1 || !values.getFirst().matches("[0-9a-fA-F]{32}"))
      throw LookupWork.fail(UNSUPPORTED, "Configuration lacks a supported exact root CKey");
    return values.getFirst().toLowerCase(Locale.ROOT);
  }

  private static IndexedEncodingParser.PageIndex index(
      IndexedEncodingParser parser,
      IndexedEncodingParser.Layout layout,
      IndexedEncodingParser.Table table,
      RemoteBlteObject object,
      IndexedEncodingParser.Limits limits,
      LookupWork work)
      throws EncodingManifestException {
    long size = layout.pageCount(table) * 32;
    if (size > limits.maxIndexBytes())
      throw LookupWork.fail(BUDGET_EXCEEDED, "EN index exceeds bounded metadata scope");
    return parser.parseIndex(
        layout,
        table,
        object.authenticatedWindow(layout.indexOffset(table), (int) size),
        limits,
        work::cancelled);
  }

  private static void checkRootBudget(
      long decoded, long encoded, LookupBudgets budgets, boolean twoPasses) {
    if (encoded < 13
        || encoded > budgets.maximumRootEncodedBytes()
        || decoded < 20
        || decoded > budgets.maximumRootDecodedBytes()
        || decoded > budgets.maximumDecodedWorkBytes() / (twoPasses ? 2 : 1))
      throw LookupWork.fail(
          BUDGET_EXCEEDED, "Complete root retention/read scope exceeds explicit byte/work bounds");
  }

  private record RootTuple(String ekey, long decoded, long encoded) {}

  private static RootTuple parseRootIdentity(VerifiedSourceAlias root, String expectedCKey) {
    var source = root.source();
    String[] parts = source.immutableSourceKey().split(";", -1);
    if (!source.namespace().equals(ROOT_NAMESPACE)
        || !source.verificationSemantics().equals(ROOT_SEMANTICS)
        || parts.length != 4
        || !parts[0].equals(expectedCKey)
        || !parts[1].matches("[0-9a-f]{32}")
        || !parts[2].matches("[1-9][0-9]*")
        || !parts[3].matches("[1-9][0-9]*"))
      throw LookupWork.fail(
          INTEGRITY_FAILURE,
          "Retained root receipt does not match exact configuration or verifier");
    try {
      return new RootTuple(parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[3]));
    } catch (NumberFormatException bad) {
      throw LookupWork.fail(INTEGRITY_FAILURE, "Retained root sizes are invalid");
    }
  }

  private static ResolvedSourceIdentity identity(
      String ckey, String ekey, long decoded, long encoded) {
    return new ResolvedSourceIdentity(
        ROOT_NAMESPACE,
        String.join(";", ckey, ekey, Long.toString(decoded), Long.toString(encoded)),
        ROOT_SEMANTICS);
  }

  private void ensureOpen() {
    synchronized (sessions) {
      if (closed) throw LookupWork.fail(CLOSED, "File lookup source adapter is closed");
    }
  }

  @Override
  public void close() {
    synchronized (sessions) {
      closed = true;
      sessions.clear();
    }
  }
}
