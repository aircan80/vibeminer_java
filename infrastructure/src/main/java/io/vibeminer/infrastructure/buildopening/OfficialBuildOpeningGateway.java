package io.vibeminer.infrastructure.buildopening;

import static io.vibeminer.application.buildopening.BuildOpeningException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.acquisition.*;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.buildopening.BuildMetadataAdmission.Role;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.bootstrap.OfficialBootstrapArtifactGateway;
import io.vibeminer.infrastructure.lookup.OfficialFileLookupGateway;
import io.vibeminer.protocol.tact.config.*;
import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * Actual exact-build Open composition for the finite ROOT/direct/loose profile. Borrows all byte,
 * canonical, transport and executor resources. One long-lived existing coalescer uses the shared
 * priority scheduler; only the distinct orchestration worker waits. Offline admission replay never
 * calls a transfer-capable gateway. This class does not enumerate payload inventory or create a
 * second store, HTTP client, scheduler, canonical publisher or permanent decoded cache.
 */
public final class OfficialBuildOpeningGateway implements BuildOpeningGateway, AutoCloseable {
  private final DiscoveryHistoryRepository history;
  private final BuildMetadataAdmissionRepository admissions;
  private final ObjectStore store;
  private final BuildObjectCatalog builds;
  private final CanonicalContentRepository canonical;
  private final MetadataContentAcquisition acquisition;
  private final OfficialBuildMetadataSourceWorker metadata;
  private final OfficialBootstrapArtifactGateway installs;
  private final OfficialFileLookupGateway roots;
  private final PinnedCdnTransport configurations;
  private final BuildOpeningPolicy policy;
  private final LongSupplier usableBytes;
  private final CoalescingAcquisitionService sources;
  private final Map<ResolvedSourceIdentity, Context> contexts = new HashMap<>();
  private boolean closed;

  @FunctionalInterface
  private interface Work {
    VerifiedSourceAlias run(CancellationToken token);
  }

  private static final class Context {
    final Work work;
    int consumers;
    boolean started, finished;

    Context(Work work) {
      this.work = work;
    }
  }

  public OfficialBuildOpeningGateway(
      DiscoveryHistoryRepository history,
      BuildMetadataAdmissionRepository admissions,
      ObjectStore store,
      BuildObjectCatalog builds,
      CanonicalContentRepository canonical,
      OfficialBuildMetadataSourceWorker metadata,
      OfficialBootstrapArtifactGateway installs,
      OfficialFileLookupGateway roots,
      PinnedCdnTransport configurations,
      Executor sourceExecutor,
      PrioritySourceScheduler scheduler,
      BuildOpeningPolicy policy,
      LongSupplier usableBytes) {
    this.history = Objects.requireNonNull(history);
    this.admissions = Objects.requireNonNull(admissions);
    this.store = Objects.requireNonNull(store);
    this.builds = Objects.requireNonNull(builds);
    this.canonical = Objects.requireNonNull(canonical);
    this.metadata = Objects.requireNonNull(metadata);
    this.installs = Objects.requireNonNull(installs);
    this.roots = Objects.requireNonNull(roots);
    this.configurations = Objects.requireNonNull(configurations);
    this.policy = Objects.requireNonNull(policy);
    this.usableBytes = Objects.requireNonNull(usableBytes);
    acquisition = new MetadataContentAcquisition(canonical);
    sources =
        new CoalescingAcquisitionService(
            store,
            this::runSource,
            Objects.requireNonNull(sourceExecutor),
            16,
            32,
            1,
            false,
            Objects.requireNonNull(scheduler));
  }

  @Override
  public BuildMetadataAdmission open(
      BuildLibraryReference reference,
      String locale,
      ProgressSink progress,
      CancellationToken cancellation) {
    if (reference == null
        || locale == null
        || !locale.matches("[a-z]{2}[A-Z]{2}")
        || progress == null
        || cancellation == null)
      throw fail(
          INVALID_REQUEST, "Exact registered build, research locale and opening context required");
    var state = new State(policy, cancellation, progress);
    try {
      synchronized (contexts) {
        if (closed) throw fail(CLOSED, "Build opening gateway is closed");
      }
      state.update(BuildOpenProgress.Stage.VALIDATING_CONTEXT, 0, OptionalInt.empty());
      var retained =
          admissions.find(reference, locale, BuildOpeningPolicy.SCOPE_REVISION, state.ledger.token);
      var event =
          history
              .read(reference.eventId(), state.ledger.token)
              .orElseThrow(() -> fail(MISSING_METADATA, "Exact discovery observation is missing"));
      if (!BuildLibraryReference.from(event, reference.observationSequence()).contains(reference))
        throw fail(
            CORRUPT_METADATA, "Selected source receipts differ from the registered observation");
      var build =
          event.builds().stream()
              .filter(row -> row.versionsRowOrdinal() == reference.versionsRowOrdinal())
              .map(DiscoveryEvent.ObservedBuild::build)
              .findFirst()
              .orElseThrow(() -> fail(CORRUPT_METADATA, "Exact observed build row is missing"));
      CapturedBuildSource.resolve(build);
      if (retained.isPresent()) return reopen(retained.get(), build, state);
      state.unmeasured = true;
      builds.registerSnapshot(build);
      var config = configuration(build, build.buildConfig(), state);
      var declaration = classify(build, config, state.ledger.token);
      requireDirectProfile(declaration);
      int required = 2 + declaration.requiredSources().size();
      state.update(BuildOpenProgress.Stage.RESOLVING_REQUIREMENTS, 1, OptionalInt.of(required));
      var cdn = configuration(build, build.cdnConfig(), state);
      state.update(BuildOpenProgress.Stage.VERIFYING_METADATA, 2, OptionalInt.of(required));
      var facts = new ArrayList<BuildMetadataAdmissionFacts.MetadataFact>();
      // All directly declared source costs are reserved before the first large EN transfer.
      for (var declared : declaration.requiredSources())
        if (declared.kind() != Kind.ROOT)
          reserveSource(
              declared.kind(),
              declared.decodedBytes().orElseThrow(),
              declared.encodedBytes().orElseThrow(),
              state.ledger);
      var encoding = declaration.source(Kind.ENCODING);
      var encodingAlias = streaming(declaration, encoding, state);
      var encodingFact =
          fact(Kind.ENCODING, encoding.contentKey(), encodingAlias, state.ledger.token);
      facts.add(encodingFact);
      attach(build, Kind.ENCODING, encodingAlias, state.ledger.token);
      state.update(BuildOpenProgress.Stage.VERIFYING_METADATA, 3, OptionalInt.of(required));
      var selectedKeys = new HashSet<String>();
      for (var declared : declaration.requiredSources())
        if (declared.kind() != Kind.ENCODING) selectedKeys.add(declared.contentKey());
      var resolution =
          roots.prepareRootFromRetainedEncoding(
              build,
              config,
              encodingFact.proof(),
              selectedKeys,
              policy.metadata().blte(),
              policy.metadata().encoding(),
              policy.root(),
              state.ledger.token);
      checkDirectMappings(declaration, resolution);
      var rootPlan = resolution.root();
      var candidate =
          rootPlan.candidates().stream()
              .filter(
                  value ->
                      value.encodedBytes() > 0
                          && value.encodedBytes() <= policy.root().maximumRootEncodedBytes())
              .findFirst()
              .orElseThrow(() -> fail(NOT_READY, "No admissible known-cost ROOT candidate"));
      reserveSource(Kind.ROOT, rootPlan.decodedBytes(), candidate.encodedBytes(), state.ledger);
      var selection = new RootSelectionPlan(rootPlan, candidate);
      var rootSource = roots.rootIdentity(selection);
      var rootRequest =
          request(
              build,
              config,
              Kind.ROOT,
              rootPlan.contentKey(),
              candidate.encodingKey(),
              rootPlan.decodedBytes(),
              candidate.encodedBytes());
      Duration rootTime = state.ledger.remaining();
      state.update(
          BuildOpenProgress.Stage.ACQUIRING_METADATA, facts.size() + 2, OptionalInt.of(required));
      var rootAlias =
          source(
              rootSource,
              token -> {
                var bounded = bounded(token, rootTime);
                diskIfNew(rootRequest, candidate.encodedBytes(), bounded);
                return acquisition.acquire(
                    rootRequest,
                    rootSource,
                    () -> builds.findVerifiedSourceAlias(rootSource, bounded),
                    (observer, t) -> roots.acquireRoot(selection, observer, t),
                    (alias, observer, t) ->
                        roots.lookupRetained(
                            build, config, alias, Set.of(), policy.root(), observer, t),
                    bounded);
              },
              state.ledger);
      facts.add(fact(Kind.ROOT, rootPlan.contentKey(), rootAlias, state.ledger.token));
      attach(build, Kind.ROOT, rootAlias, state.ledger.token);
      state.update(
          BuildOpenProgress.Stage.VERIFYING_METADATA, facts.size() + 2, OptionalInt.of(required));
      var install = installs.restoreConfiguration(build, config, state.ledger.token);
      var installSource = installs.installIdentity(install);
      var installRequest =
          request(
              build,
              config,
              Kind.INSTALL,
              install.contentKey(),
              install.encodingKey(),
              install.decodedBytes(),
              install.encodedBytes());
      Duration installTime = state.ledger.remaining();
      state.update(
          BuildOpenProgress.Stage.ACQUIRING_METADATA, facts.size() + 2, OptionalInt.of(required));
      var installAlias =
          source(
              installSource,
              token -> {
                var bounded = bounded(token, installTime);
                diskIfNew(installRequest, install.encodedBytes(), bounded);
                return acquisition.acquire(
                    installRequest,
                    installSource,
                    () -> builds.findVerifiedSourceAlias(installSource, bounded),
                    (observer, t) -> {
                      var alias = installs.acquireInstall(install, observer, t);
                      installs.inspectRetained(install, alias, observer, t);
                      return alias;
                    },
                    (alias, observer, t) -> installs.inspectRetained(install, alias, observer, t),
                    bounded);
              },
              state.ledger);
      facts.add(fact(Kind.INSTALL, install.contentKey(), installAlias, state.ledger.token));
      attach(build, Kind.INSTALL, installAlias, state.ledger.token);
      state.update(
          BuildOpenProgress.Stage.VERIFYING_METADATA, facts.size() + 2, OptionalInt.of(required));
      for (var kind : List.of(Kind.DOWNLOAD, Kind.DOWNLOAD_SIZE)) {
        var declared =
            declaration.requiredSources().stream()
                .filter(value -> value.kind() == kind)
                .findFirst();
        if (declared.isEmpty()) continue;
        var alias = streaming(declaration, declared.get(), state);
        facts.add(fact(kind, declared.get().contentKey(), alias, state.ledger.token));
        attach(build, kind, alias, state.ledger.token);
        state.update(
            BuildOpenProgress.Stage.VERIFYING_METADATA, facts.size() + 2, OptionalInt.of(required));
      }
      var complete =
          new BuildMetadataAdmissionFacts(
              reference,
              locale,
              BuildOpeningPolicy.SCOPE_REVISION,
              declaration,
              List.of(
                  new BuildMetadataAdmissionFacts.ConfigurationFact(Role.BUILD_CONFIG, config),
                  new BuildMetadataAdmissionFacts.ConfigurationFact(Role.CDN_CONFIG, cdn)),
              facts);
      return commit(complete, state);
    } catch (RuntimeException failed) {
      throw mapped(failed);
    }
  }

  private BuildMetadataAdmission reopen(
      BuildMetadataAdmissionFacts facts, BuildSnapshot build, State state) {
    state.update(
        BuildOpenProgress.Stage.VERIFYING_RETAINED,
        0,
        OptionalInt.of(facts.admission().required().size()));
    try (var leases = leaseAll(facts, state.ledger.token)) {
      var config =
          facts.configurations().stream()
              .filter(value -> value.role() == Role.BUILD_CONFIG)
              .findFirst()
              .orElseThrow()
              .originalAlias();
      var declaration = classify(build, config, state.ledger.token);
      requireDirectProfile(declaration);
      for (var value : facts.metadata()) {
        var kind = value.proof().structuralAttestation().orElseThrow().kind();
        reserveSource(
            kind,
            value.proof().decodedAttestation().orElseThrow().actualDecodedBytes(),
            value.proof().encodedBytes(),
            state.ledger);
      }
      int verified = 2, required = facts.admission().required().size();
      state.update(BuildOpenProgress.Stage.VERIFYING_METADATA, verified, OptionalInt.of(required));
      for (var value : facts.metadata()) {
        state.ledger.check();
        var proof = value.proof();
        var kind = proof.structuralAttestation().orElseThrow().kind();
        switch (kind) {
          case ROOT ->
              roots.lookupRetained(
                  build,
                  config,
                  proof.originalAlias(),
                  Set.of(),
                  policy.root(),
                  state.ledger.token);
          case INSTALL ->
              installs.inspectRetained(
                  installs.restoreConfiguration(build, config, state.ledger.token),
                  proof.originalAlias(),
                  state.ledger.token);
          case ENCODING, DOWNLOAD, DOWNLOAD_SIZE ->
              metadata.verifyRetained(
                  metadataRequest(declaration, declaration.source(kind)),
                  proof,
                  policy.metadata(),
                  state.ledger.token);
        }
        state.update(
            BuildOpenProgress.Stage.VERIFYING_METADATA, ++verified, OptionalInt.of(required));
      }
      admissions.validateCurrentAuthority(facts, state.ledger.token);
    }
    // The repository opens its own complete physical leases and rechecks authority for commit.
    // Close these grammar-pass leases before that boundary, so their cleanup cannot relabel a
    // known committed result. SHA identity binds the parser pass to the repository's fresh bytes.
    return commit(facts, state);
  }

  private BuildMetadataAdmission commit(BuildMetadataAdmissionFacts facts, State state) {
    state.ledger.check();
    state.update(
        BuildOpenProgress.Stage.COMMITTING_ADMISSION,
        facts.admission().required().size(),
        OptionalInt.of(facts.admission().required().size()));
    var committed = admissions.commit(facts, state.ledger.token);
    // No token, clock or disk recheck may relabel a known committed admission as cancellation.
    state.update(
        BuildOpenProgress.Stage.COMPLETE,
        committed.required().size(),
        OptionalInt.of(committed.required().size()));
    return committed;
  }

  private VerifiedSourceAlias streaming(
      BuildMetadataDeclarationPlan plan,
      BuildMetadataDeclarationPlan.Source declared,
      State state) {
    var request = metadataRequest(plan, declared);
    var source =
        new ResolvedSourceIdentity(
            namespace(declared.kind()),
            declared.contentKey()
                + ";"
                + declared.encodingKey().orElseThrow()
                + ";"
                + declared.decodedBytes().orElseThrow()
                + ";"
                + declared.encodedBytes().orElseThrow(),
            "blte-complete-encoded-and-ckey-stream-v1");
    Duration maximum = state.ledger.remaining();
    state.update(BuildOpenProgress.Stage.ACQUIRING_METADATA, state.verified, state.required);
    return source(
        source,
        token -> {
          var bounded = bounded(token, maximum);
          diskIfNew(request, request.requestedEncodedBytes(), bounded);
          return metadata.acquire(request, policy.metadata(), bounded);
        },
        state.ledger);
  }

  private MetadataContentRequest metadataRequest(
      BuildMetadataDeclarationPlan plan, BuildMetadataDeclarationPlan.Source declared) {
    return request(
        plan.build(),
        plan.configuration(),
        declared.kind(),
        declared.contentKey(),
        declared.encodingKey().orElseThrow(),
        declared.decodedBytes().orElseThrow(),
        declared.encodedBytes().orElseThrow());
  }

  private static MetadataContentRequest request(
      BuildSnapshot build,
      VerifiedSourceAlias config,
      Kind kind,
      String ck,
      String ek,
      long decoded,
      long encoded) {
    return new MetadataContentRequest(
        build, config, kind, new ContentSlotKey("wow", ck), ek, encoded, OptionalLong.of(decoded));
  }

  private VerifiedSourceAlias configuration(BuildSnapshot build, String key, State state) {
    long maximum = policy.metadata().configuration().maxBytes();
    state.ledger.reserve(maximum, maximum, 1, 0, 0);
    var identity = new ResolvedSourceIdentity("tact.config.raw-md5", key, "tact-config-raw-md5-v1");
    Duration maximumTime = state.ledger.remaining();
    state.update(BuildOpenProgress.Stage.ACQUIRING_METADATA, state.verified, state.required);
    var alias =
        source(
            identity,
            token -> {
              var bounded = bounded(token, maximumTime);
              var retained = builds.findVerifiedSourceAlias(identity, bounded);
              if (retained.isPresent()) {
                readConfiguration(retained.get(), key, bounded);
                return retained.get();
              }
              store.publisherStarted();
              checkDisk(maximum);
              var request = CdnRequest.configuration(CapturedBuildSource.resolve(build), key);
              var pending = configurations.fetch(request);
              var acquired = await(pending, bounded, maximumTime);
              bounded.throwIfCancellationRequested();
              byte[] raw = acquired.bytes();
              if (!request.equals(acquired.request())
                  || !request.uri().equals(acquired.request().uri())
                  || !sha256(raw).equals(acquired.sha256()))
                throw fail(CORRUPT_METADATA, "Configuration transfer receipt changed");
              parseConfiguration(raw, key, bounded);
              checkDisk(raw.length);
              var object =
                  store.publish(
                      new ByteArrayInputStream(raw),
                      new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, key, raw.length),
                      maximum,
                      bounded);
              return new VerifiedSourceAlias(
                  identity,
                  object,
                  new SourceVerificationEvidence(
                      "tact-config-raw-md5-v1", request.uri().toString(), acquired.receivedAt()));
            },
            state.ledger);
    builds.attachVerifiedObject(
        build.snapshotId(),
        key.equals(build.buildConfig()) ? "BUILD_CONFIG" : "CDN_CONFIG",
        alias.source(),
        alias.object(),
        alias.evidence(),
        state.ledger.token);
    return alias;
  }

  private BuildMetadataDeclarationPlan classify(
      BuildSnapshot build, VerifiedSourceAlias config, CancellationToken token) {
    return new BuildMetadataDeclarationClassifier()
        .classify(
            build,
            config,
            readConfiguration(config, build.buildConfig(), token),
            policy.declarations(),
            token);
  }

  private byte[] readConfiguration(VerifiedSourceAlias alias, String key, CancellationToken token) {
    if (!alias
        .source()
        .equals(new ResolvedSourceIdentity("tact.config.raw-md5", key, "tact-config-raw-md5-v1")))
      throw fail(CORRUPT_METADATA, "Wrong exact configuration alias");
    CapturedBuildSource.validateConfigurationProof(alias.evidence().sourceUrl(), key);
    long maximum = policy.metadata().configuration().maxBytes();
    if (alias.object().length() < 1 || alias.object().length() > maximum)
      throw fail(LIMIT_EXCEEDED, "Config exceeds byte policy");
    try (var lease = store.openRead(alias.object().digest(), token)) {
      if (!lease.object().equals(alias.object()))
        throw fail(CORRUPT_METADATA, "Config object length changed");
      byte[] raw = lease.stream().readNBytes((int) alias.object().length());
      token.throwIfCancellationRequested();
      if (raw.length != alias.object().length()
          || lease.stream().read() != -1
          || !sha256(raw).equals(alias.object().digest().value()))
        throw fail(CORRUPT_METADATA, "Config physical bytes changed");
      parseConfiguration(raw, key, token);
      return raw;
    } catch (IOException io) {
      throw fail(STORAGE_UNAVAILABLE, "Unable to read exact configuration", io);
    }
  }

  private void parseConfiguration(byte[] raw, String key, CancellationToken token) {
    try {
      new TactConfigParser()
          .parse(raw, key, policy.metadata().configuration(), token::isCancellationRequested);
    } catch (TactConfigException invalid) {
      token.throwIfCancellationRequested();
      throw fail(
          invalid.reason() == TactConfigException.Reason.LIMIT ? LIMIT_EXCEEDED : CORRUPT_METADATA,
          "Configuration raw MD5 or framing verification failed",
          invalid);
    }
  }

  private static void requireDirectProfile(BuildMetadataDeclarationPlan plan) {
    for (var source : plan.requiredSources())
      if (source.kind() != Kind.ROOT
          && (source.encodingKey().isEmpty() || source.decodedBytes().isEmpty()))
        throw fail(
            UNSUPPORTED_METADATA,
            "Initial opening profile requires direct IN/DL/DS key and size pairs");
  }

  private static void checkDirectMappings(
      BuildMetadataDeclarationPlan plan,
      OfficialFileLookupGateway.RetainedEncodingResolution resolution) {
    for (var source : plan.requiredSources()) {
      if (source.kind() == Kind.ROOT || source.kind() == Kind.ENCODING) continue;
      var content = resolution.contents().get(source.contentKey());
      if (content == null
          || !content.encodingKeys().contains(source.encodingKey().orElseThrow())
          || content.decodedSize() != source.decodedBytes().orElseThrow())
        throw fail(
            CORRUPT_METADATA, "Direct metadata declaration differs from complete retained EN");
      var encoded = resolution.encodings().get(source.encodingKey().orElseThrow());
      if (encoded != null && encoded.encodedSize() != source.encodedBytes().orElseThrow())
        throw fail(CORRUPT_METADATA, "Direct metadata encoded size conflicts with EN");
    }
  }

  private void reserveSource(Kind kind, long decoded, long encoded, Ledger ledger) {
    long requests =
        kind == Kind.ROOT
            ? (long) policy.root().maximumChunks() + 2
            : kind == Kind.INSTALL ? 1 : (encoded - 1) / policy.metadata().rangeSliceBytes() + 1;
    long decode = multiply(decoded, kind == Kind.ENCODING ? 3 : 2);
    long grammar = multiply(decoded, kind == Kind.ENCODING ? 4 : kind == Kind.INSTALL ? 2 : 1);
    ledger.reserve(encoded, encoded, requests, decode, grammar);
  }

  private static long multiply(long value, int factor) {
    try {
      if (value < 0) throw new ArithmeticException();
      return Math.multiplyExact(value, factor);
    } catch (ArithmeticException overflow) {
      throw fail(LIMIT_EXCEEDED, "Metadata cost exceeds signed-long policy", overflow);
    }
  }

  private BuildMetadataAdmissionFacts.MetadataFact fact(
      Kind kind, String ck, VerifiedSourceAlias alias, CancellationToken token) {
    var slot = new ContentSlotKey("wow", ck);
    var claim =
        canonical
            .lookup(slot, token)
            .orElseThrow(() -> fail(NOT_READY, "Completed source has no canonical authority"));
    if (claim.status() != CanonicalReservation.Status.RETAINED
        || claim.proof().isEmpty()
        || !(claim.proof().get() instanceof MetadataContentProof proof)
        || !proof.originalAlias().equals(alias)
        || proof.structuralAttestation().isEmpty()
        || proof.structuralAttestation().get().kind() != kind)
      throw fail(NOT_READY, "Actual metadata source is not completely retained");
    return new BuildMetadataAdmissionFacts.MetadataFact(role(kind), slot, claim.token(), proof);
  }

  private void attach(
      BuildSnapshot build, Kind kind, VerifiedSourceAlias alias, CancellationToken token) {
    builds.attachVerifiedObject(
        build.snapshotId(),
        membership(kind),
        alias.source(),
        alias.object(),
        alias.evidence(),
        token);
  }

  private static String membership(Kind kind) {
    return switch (kind) {
      case ROOT -> "ROOT_MANIFEST";
      case INSTALL -> "INSTALL_MANIFEST";
      case ENCODING -> "ENCODING_MANIFEST";
      case DOWNLOAD -> "DOWNLOAD_MANIFEST";
      case DOWNLOAD_SIZE -> "SIZE_MANIFEST";
    };
  }

  private static Role role(Kind kind) {
    return switch (kind) {
      case ROOT -> Role.ROOT;
      case INSTALL -> Role.INSTALL;
      case ENCODING -> Role.ENCODING;
      case DOWNLOAD -> Role.DOWNLOAD;
      case DOWNLOAD_SIZE -> Role.SIZE;
    };
  }

  private static String namespace(Kind kind) {
    return switch (kind) {
      case ENCODING -> "tact.encoding.v1";
      case DOWNLOAD -> "tact.download.v1v2v3";
      case DOWNLOAD_SIZE -> "tact.download-size.v1";
      default -> throw fail(UNSUPPORTED_METADATA, "Wrong streaming metadata kind");
    };
  }

  private void diskIfNew(MetadataContentRequest request, long maximum, CancellationToken token) {
    if (canonical.lookup(request.slot(), token).flatMap(ContentClaim::proof).isEmpty())
      checkDisk(maximum);
  }

  private void checkDisk(long additional) {
    long usable;
    try {
      usable = usableBytes.getAsLong();
    } catch (RuntimeException unavailable) {
      throw fail(STORAGE_UNAVAILABLE, "App-owned storage capacity is unavailable", unavailable);
    }
    long needed;
    try {
      needed = Math.addExact(additional, policy.freeSpaceHeadroomBytes());
    } catch (ArithmeticException overflow) {
      throw fail(LIMIT_EXCEEDED, "Physical preflight exceeds signed long", overflow);
    }
    if (usable < 0) throw fail(STORAGE_UNAVAILABLE, "App-owned storage capacity is unknown");
    if (usable < needed)
      throw fail(LIMIT_EXCEEDED, "Insufficient app-owned storage capacity and headroom");
  }

  private VerifiedSourceAlias source(ResolvedSourceIdentity identity, Work work, Ledger ledger) {
    Context context;
    synchronized (contexts) {
      if (closed) throw fail(CLOSED, "Build opening gateway is closed");
      context = contexts.get(identity);
      if (context == null) {
        if (contexts.size() >= 16) throw fail(BUSY, "Opening source context capacity reached");
        context = new Context(work);
        contexts.put(identity, context);
      }
      context.consumers++;
    }
    AcquisitionRequest requested = null;
    try {
      ledger.check();
      requested = sources.acquire(identity);
      var pending = requested.completion().toCompletableFuture();
      while (true) {
        ledger.check();
        try {
          var value =
              pending.get(
                  Math.min(ledger.remaining().toNanos(), TimeUnit.MILLISECONDS.toNanos(25)),
                  TimeUnit.NANOSECONDS);
          ledger.check();
          return value;
        } catch (TimeoutException wait) {
          /* Keep actual source ownership in its scheduler wrapper. */
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CancellationException("Opening orchestration interrupted");
    } catch (ExecutionException failed) {
      throw runtime(failed.getCause());
    } finally {
      if (requested != null && !requested.completion().toCompletableFuture().isDone())
        requested.cancel();
      synchronized (contexts) {
        context.consumers--;
        if (context.consumers == 0 && (context.finished || !context.started))
          contexts.remove(identity, context);
      }
    }
  }

  private VerifiedSourceAlias runSource(ResolvedSourceIdentity identity, CancellationToken token) {
    Context context;
    synchronized (contexts) {
      context = contexts.get(identity);
      if (context == null) throw fail(INVALID_REQUEST, "Missing issued opening source context");
      context.started = true;
    }
    try {
      return context.work.run(token);
    } finally {
      synchronized (contexts) {
        context.finished = true;
        if (context.consumers == 0) contexts.remove(identity, context);
      }
    }
  }

  @Override
  public void close() {
    synchronized (contexts) {
      if (closed) return;
      closed = true;
    }
    sources.close();
  }

  private static CancellationToken bounded(CancellationToken source, Duration timeout) {
    long started = System.nanoTime(), maximum = timeout.toNanos();
    return new CancellationToken() {
      public boolean isCancellationRequested() {
        return source.isCancellationRequested() || System.nanoTime() - started >= maximum;
      }

      @Override
      public void throwIfCancellationRequested() {
        source.throwIfCancellationRequested();
        if (System.nanoTime() - started >= maximum)
          throw fail(LIMIT_EXCEEDED, "Metadata source deadline exceeded");
      }
    };
  }

  private static <T> T await(
      CompletableFuture<T> pending, CancellationToken token, Duration timeout) {
    long started = System.nanoTime();
    try {
      while (true) {
        token.throwIfCancellationRequested();
        long remaining = timeout.toNanos() - (System.nanoTime() - started);
        if (remaining <= 0) throw fail(LIMIT_EXCEEDED, "Configuration source deadline exceeded");
        try {
          var value =
              pending.get(
                  Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
          token.throwIfCancellationRequested();
          return value;
        } catch (TimeoutException wait) {
          /* Cooperative cancellation between bounded waits. */
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CancellationException("Configuration source interrupted");
    } catch (ExecutionException failed) {
      throw runtime(failed.getCause());
    } finally {
      if (!pending.isDone()) pending.cancel(true);
    }
  }

  private Leases leaseAll(BuildMetadataAdmissionFacts facts, CancellationToken token) {
    var leases = new Leases();
    try {
      var objects = new LinkedHashSet<StoredObject>();
      for (var config : facts.configurations()) objects.add(config.originalAlias().object());
      for (var metadata : facts.metadata()) objects.add(metadata.proof().object());
      for (var object : objects) {
        var lease = store.openRead(object.digest(), token);
        leases.values.add(lease);
        if (!lease.object().equals(object))
          throw fail(CORRUPT_METADATA, "Admission object geometry changed");
      }
      return leases;
    } catch (RuntimeException failed) {
      try {
        leases.close();
      } catch (RuntimeException cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  private static final class Leases implements AutoCloseable {
    final List<ObjectReadLease> values = new ArrayList<>();

    public void close() {
      RuntimeException failure = null;
      for (int i = values.size() - 1; i >= 0; i--) {
        try {
          values.get(i).close();
        } catch (RuntimeException cleanup) {
          if (failure == null) failure = cleanup;
          else failure.addSuppressed(cleanup);
        }
      }
      if (failure != null) throw failure;
    }
  }

  static final class Ledger {
    final BuildOpeningPolicy policy;
    final CancellationToken caller, token;
    final LongSupplier nanoClock;
    final long started;
    long wire, physical, requests, decoded, grammar;

    Ledger(BuildOpeningPolicy policy, CancellationToken caller, LongSupplier nanoClock) {
      this.policy = policy;
      this.caller = caller;
      this.nanoClock = nanoClock;
      started = nanoClock.getAsLong();
      token =
          new CancellationToken() {
            public boolean isCancellationRequested() {
              return caller.isCancellationRequested() || expired();
            }

            @Override
            public void throwIfCancellationRequested() {
              check();
            }
          };
    }

    void check() {
      caller.throwIfCancellationRequested();
      if (expired()) throw fail(LIMIT_EXCEEDED, "Whole opening deadline exceeded");
    }

    boolean expired() {
      return nanoClock.getAsLong() - started >= policy.timeout().toNanos();
    }

    Duration remaining() {
      caller.throwIfCancellationRequested();
      long nanos = policy.timeout().toNanos() - (nanoClock.getAsLong() - started);
      if (nanos <= 0) throw fail(LIMIT_EXCEEDED, "Whole opening deadline exceeded");
      return Duration.ofNanos(nanos);
    }

    void reserve(
        long nextWire, long nextPhysical, long nextRequests, long nextDecoded, long nextGrammar) {
      check();
      if (nextWire < 0
          || nextPhysical < 0
          || nextRequests < 0
          || nextDecoded < 0
          || nextGrammar < 0
          || nextWire > policy.maximumWireBytes() - wire
          || nextPhysical > policy.maximumAdditionalPhysicalBytes() - physical
          || nextRequests > policy.maximumRequests() - requests
          || nextDecoded > policy.maximumDecodedWorkBytes() - decoded
          || nextGrammar > policy.maximumGrammarWorkBytes() - grammar)
        throw fail(LIMIT_EXCEEDED, "Whole opening reservation exceeds explicit metadata policy");
      wire += nextWire;
      physical += nextPhysical;
      requests += nextRequests;
      decoded += nextDecoded;
      grammar += nextGrammar;
    }
  }

  private static final class State {
    final Ledger ledger;
    final ProgressSink progress;
    boolean unmeasured;
    int verified;
    OptionalInt required = OptionalInt.empty();

    State(BuildOpeningPolicy policy, CancellationToken caller, ProgressSink progress) {
      ledger = new Ledger(policy, caller, System::nanoTime);
      this.progress = progress;
    }

    void update(BuildOpenProgress.Stage stage, int verified, OptionalInt required) {
      this.verified = verified;
      this.required = required;
      progress.update(
          new BuildOpenProgress(
              stage,
              unmeasured ? OptionalLong.empty() : OptionalLong.of(0),
              unmeasured ? OptionalLong.empty() : OptionalLong.of(0),
              verified,
              required));
    }
  }

  private static String sha256(byte[] raw) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static RuntimeException runtime(Throwable failure) {
    if (failure instanceof RuntimeException typed) return typed;
    if (failure instanceof Error fatal) throw fatal;
    return fail(STORAGE_UNAVAILABLE, "Opening source failed", failure);
  }

  private static RuntimeException mapped(RuntimeException failure) {
    if (failure instanceof BuildOpeningException || failure instanceof CancellationException)
      return failure;
    RuntimeException typed =
        failure instanceof MetadataAcquisitionFailure metadata ? metadata.failure() : failure;
    BuildOpeningException.Code code;
    if (typed instanceof CancellationException) {
      var cancelled = new CancellationException("Build opening cancelled");
      cancelled.initCause(failure);
      return cancelled;
    } else if (typed instanceof ContentClaimException claim)
      code =
          switch (claim.code()) {
            case BUSY -> BUSY;
            case MISSING_LOCAL -> MISSING_METADATA;
            case INTEGRITY_FAILURE -> CORRUPT_METADATA;
            case UNSUPPORTED_REUSE -> UNSUPPORTED_METADATA;
            case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
            default -> NOT_READY;
          };
    else if (typed instanceof FileLookupException lookup)
      code =
          switch (lookup.code()) {
            case MISSING_LOCAL -> MISSING_METADATA;
            case INTEGRITY_FAILURE -> CORRUPT_METADATA;
            case UNAVAILABLE -> UNAVAILABLE;
            case UNSUPPORTED -> UNSUPPORTED_METADATA;
            case BUDGET_EXCEEDED -> LIMIT_EXCEEDED;
            case CAPACITY_EXCEEDED -> BUSY;
            case STORAGE_FAILURE -> STORAGE_UNAVAILABLE;
            default -> NOT_READY;
          };
    else if (typed instanceof BootstrapException bootstrap)
      code =
          switch (bootstrap.reason()) {
            case INTEGRITY -> CORRUPT_METADATA;
            case UNSUPPORTED -> UNSUPPORTED_METADATA;
            case LIMIT -> LIMIT_EXCEEDED;
            case UNAVAILABLE -> UNAVAILABLE;
            default -> NOT_READY;
          };
    else if (typed instanceof io.vibeminer.infrastructure.acquisition.AcquisitionException remote)
      code =
          switch (remote.reason()) {
            case INTEGRITY -> CORRUPT_METADATA;
            case UNAVAILABLE -> UNAVAILABLE;
            case LIMIT -> LIMIT_EXCEEDED;
            default -> NOT_READY;
          };
    else if (typed instanceof ObjectStoreException stored)
      code =
          switch (stored.code()) {
            case MISSING_OBJECT -> MISSING_METADATA;
            case INTEGRITY_FAILURE -> CORRUPT_METADATA;
            case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
            case BUSY, STORE_IN_USE -> BUSY;
            default -> STORAGE_UNAVAILABLE;
          };
    else if (typed instanceof MetadataSourceException) code = LIMIT_EXCEEDED;
    else if (typed instanceof io.vibeminer.application.scheduler.PrioritySourceException
        || typed instanceof io.vibeminer.application.acquisition.AcquisitionException) code = BUSY;
    else if (typed instanceof CatalogException) code = STORAGE_UNAVAILABLE;
    else if (typed instanceof SourceContextException source)
      code =
          source.reason() == SourceContextException.Reason.UNSUPPORTED
              ? UNSUPPORTED_METADATA
              : CORRUPT_METADATA;
    else code = STORAGE_UNAVAILABLE;
    return fail(
        code,
        failure.getMessage() == null ? "Build metadata opening failed" : failure.getMessage(),
        failure);
  }

  private static BuildOpeningException fail(BuildOpeningException.Code code, String message) {
    return new BuildOpeningException(code, message);
  }

  private static BuildOpeningException fail(
      BuildOpeningException.Code code, String message, Throwable cause) {
    return new BuildOpeningException(code, message, cause);
  }
}
