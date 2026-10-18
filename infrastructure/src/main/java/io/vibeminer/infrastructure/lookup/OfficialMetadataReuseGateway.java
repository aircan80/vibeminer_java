package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.application.content.ContentClaimException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.application.bootstrap.InstallInspection;
import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.bootstrap.InstallMetadataVerifier;
import io.vibeminer.protocol.tact.config.*;
import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Local config/A replay only. Its root reader has a structurally forbidden transport. */
public final class OfficialMetadataReuseGateway implements MetadataReuseGateway, AutoCloseable {
  private static final String DECODED_REVISION = "blte-nz-full-ckey-v1";
  private static final String INSTALL_SEMANTICS =
      "blte-nz-ekey-header-or-whole-chunk-md5-ckey-md5+in-v1-r1";
  private static final String ROOT_SEMANTICS =
      "blte-nz-ekey-chunks-full-ckey+tsfm-v1v2-structural-eof-r1";
  private final ObjectStore store;
  private final OfficialFileLookupGateway roots;
  private boolean closed;

  public OfficialMetadataReuseGateway(ObjectStore store) {
    this.store = Objects.requireNonNull(store);
    roots =
        new OfficialFileLookupGateway(
            store,
            new LookupTransport() {
              public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
                throw new AssertionError("Local metadata replay cannot acquire configuration");
              }

              public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
                throw new AssertionError("Local metadata replay cannot acquire payload");
              }
            },
            Clock.systemUTC());
  }

  /** Derives declarations from local exact configuration bytes, without EN or payload access. */
  @Override
  public MetadataDeclaration readDeclaration(
      BuildSnapshot build,
      VerifiedSourceAlias retainedConfiguration,
      MetadataStructureAttestation.Kind kind,
      LookupBudgets budgets,
      CancellationToken cancellation) {
    return local(
        () -> readDeclarationInternal(build, retainedConfiguration, kind, budgets, cancellation));
  }

  private MetadataDeclaration readDeclarationInternal(
      BuildSnapshot build,
      VerifiedSourceAlias retainedConfiguration,
      MetadataStructureAttestation.Kind kind,
      LookupBudgets budgets,
      CancellationToken cancellation) {
    ensureOpen();
    Objects.requireNonNull(build);
    Objects.requireNonNull(retainedConfiguration);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(budgets);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    requireConfigurationOrigin(build, retainedConfiguration);
    try (var lease = store.openRead(retainedConfiguration.object().digest(), cancellation)) {
      return readConfigurationDeclaration(
          build, retainedConfiguration, kind, budgets, cancellation, lease);
    }
  }

  @Override
  public void validateDeclaration(
      MetadataDeclaration declaration, LookupBudgets budgets, CancellationToken cancellation) {
    local(
        () -> {
          validateDeclarationInternal(declaration, budgets, cancellation);
          return null;
        });
  }

  private void validateDeclarationInternal(
      MetadataDeclaration declaration, LookupBudgets budgets, CancellationToken cancellation) {
    ensureOpen();
    try (var lease = store.openRead(declaration.configuration().object().digest(), cancellation)) {
      validateConfiguration(declaration, budgets, cancellation, lease);
    }
  }

  @Override
  public InstallInspection inspectAssociatedInstall(
      InstallPlan declaredPlanB,
      MetadataBuildAssociation association,
      LookupBudgets budgets,
      CancellationToken cancellation) {
    return local(
        () -> inspectAssociatedInstallInternal(declaredPlanB, association, budgets, cancellation));
  }

  private InstallInspection inspectAssociatedInstallInternal(
      InstallPlan declaredPlanB,
      MetadataBuildAssociation association,
      LookupBudgets budgets,
      CancellationToken cancellation) {
    ensureOpen();
    Objects.requireNonNull(association);
    Objects.requireNonNull(budgets);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    var actual = association.actualRepresentation();
    var alias = actual.originalAlias();
    // Validate the DTO projection before reading bytes; this temporary shape is never returned.
    new InstallInspection(
        declaredPlanB,
        alias.object(),
        alias.evidence(),
        List.of(),
        List.of(),
        Optional.of(association));
    CapturedBuildSource.resolve(declaredPlanB.build());
    // This explicit read establishes current readability from the complete local bytes.
    // Historical capability revisions remain unchanged and are not replay authority here.
    requireSupportedSource(actual, MetadataStructureAttestation.Kind.INSTALL);
    long decoded = actual.decodedAttestation().orElseThrow().actualDecodedBytes();
    checkInstallBudget(actual, decoded, budgets);
    CapturedBuildSource.validateDataProof(alias.evidence().sourceUrl(), actual.actualEncodingKey());
    try (var configLease =
        store.openRead(association.declaration().configuration().object().digest(), cancellation)) {
      validateConfiguration(association.declaration(), budgets, cancellation, configLease);
      try (var actualLease = store.openRead(alias.object().digest(), cancellation)) {
        byte[] encoded =
            read(
                actualLease,
                alias.object(),
                InstallMetadataVerifier.MAXIMUM_ENCODED_BYTES,
                cancellation);
        var manifest =
            new InstallMetadataVerifier()
                .decodeAndParse(
                    encoded,
                    decoded,
                    actual.actualEncodingKey(),
                    actual.contentKey(),
                    cancellation);
        var entries = new ArrayList<InstallInspection.Entry>();
        for (int i = 0; i < manifest.entries().size(); i++) {
          cancellation.throwIfCancellationRequested();
          var entry = manifest.entries().get(i);
          entries.add(
              new InstallInspection.Entry(
                  entry.name(),
                  entry.contentKey(),
                  entry.decodedSize(),
                  manifest.tagsForEntry(i).stream()
                      .map(tag -> new InstallInspection.Tag(tag.name(), tag.type()))
                      .toList()));
        }
        cancellation.throwIfCancellationRequested();
        return new InstallInspection(
            declaredPlanB,
            alias.object(),
            alias.evidence(),
            entries,
            manifest.tags().stream()
                .map(tag -> new InstallInspection.Tag(tag.name(), tag.type()))
                .toList(),
            Optional.of(association));
      }
    }
  }

  @Override
  public MetadataContentProof replay(
      MetadataReusePlan plan, LookupBudgets budgets, CancellationToken cancellation) {
    return local(() -> replayInternal(plan, budgets, cancellation));
  }

  private MetadataContentProof replayInternal(
      MetadataReusePlan plan, LookupBudgets budgets, CancellationToken cancellation) {
    ensureOpen();
    Objects.requireNonNull(plan);
    Objects.requireNonNull(budgets);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    var declaration = plan.declaration();
    var actual = plan.actualRepresentation();
    requireSupported(actual, declaration.kind());
    // Keep the exact configuration protected until the complete actual representation replay ends.
    try (var configLease =
        store.openRead(declaration.configuration().object().digest(), cancellation)) {
      validateConfiguration(declaration, budgets, cancellation, configLease);
      if (declaration.kind() == MetadataStructureAttestation.Kind.ROOT) {
        long decoded = actual.decodedAttestation().orElseThrow().actualDecodedBytes();
        if (actual.object().length() > budgets.maximumRootEncodedBytes()
            || decoded > budgets.maximumRootDecodedBytes()
            || decoded > budgets.maximumDecodedWorkBytes())
          throw failure(UNSUPPORTED_REUSE, "Root replay exceeds bounded work");
        try (var actualLease = store.openRead(actual.object().digest(), cancellation)) {
          if (!actualLease.object().equals(actual.object()))
            throw failure(INTEGRITY_FAILURE, "Actual root physical metadata changed");
          var observed = new AtomicReference<MetadataContentProof>();
          var result =
              roots.lookupRetained(
                  declaration.build(),
                  declaration.configuration(),
                  actual.originalAlias(),
                  Set.of(),
                  budgets,
                  new MetadataPublicationObserver() {
                    public void beforePromotion(
                        MetadataContentProof proof, CancellationToken token) {
                      throw new AssertionError("Local replay cannot publish");
                    }

                    public void afterStructureValidation(
                        MetadataContentProof proof, CancellationToken token) {
                      if (!observed.compareAndSet(null, proof))
                        throw failure(INTEGRITY_FAILURE, "Repeated root replay attestation");
                    }
                  },
                  cancellation);
          if (!result.root().equals(actual.originalAlias())
              || !result.build().equals(declaration.build())
              || !result.variants().isEmpty())
            throw failure(INTEGRITY_FAILURE, "Root replay changed exact context");
          return finish(actual, observed.get(), cancellation);
        }
      }
      long decoded = actual.decodedAttestation().orElseThrow().actualDecodedBytes();
      checkInstallBudget(actual, decoded, budgets);
      CapturedBuildSource.validateDataProof(
          actual.originalAlias().evidence().sourceUrl(), actual.actualEncodingKey());
      try (var lease = store.openRead(actual.object().digest(), cancellation)) {
        byte[] encoded =
            read(
                lease,
                actual.object(),
                InstallMetadataVerifier.MAXIMUM_ENCODED_BYTES,
                cancellation);
        new InstallMetadataVerifier()
            .decodeAndParse(
                encoded, decoded, actual.actualEncodingKey(), actual.contentKey(), cancellation);
        var replayed =
            actual.withAttestations(
                Optional.of(
                    new DecodedContentAttestation(actual.contentKey(), decoded, DECODED_REVISION)),
                Optional.of(
                    new MetadataStructureAttestation(
                        MetadataStructureAttestation.Kind.INSTALL, "install-manifest-v1-r1")));
        return finish(actual, replayed, cancellation);
      }
    }
  }

  private static void checkInstallBudget(
      MetadataContentProof actual, long decoded, LookupBudgets budgets) {
    if (actual.object().length()
            > Math.min(
                budgets.maximumRootEncodedBytes(), InstallMetadataVerifier.MAXIMUM_ENCODED_BYTES)
        || decoded
            > Math.min(
                budgets.maximumRootDecodedBytes(), InstallMetadataVerifier.MAXIMUM_DECODED_BYTES)
        || decoded > budgets.maximumDecodedWorkBytes())
      throw failure(UNSUPPORTED_REUSE, "Install replay exceeds bounded work");
  }

  private static MetadataContentProof finish(
      MetadataContentProof actual, MetadataContentProof replayed, CancellationToken token) {
    token.throwIfCancellationRequested();
    if (replayed == null || !actual.originalAlias().equals(replayed.originalAlias()))
      throw failure(INTEGRITY_FAILURE, "Local replay changed original actual receipt");
    ContentProofPolicy.requireMonotonicUpgrade(actual, replayed);
    ContentProofPolicy.requirePublicationReady(replayed);
    return replayed;
  }

  private static void requireSupported(
      MetadataContentProof proof, MetadataStructureAttestation.Kind kind) {
    requireSupportedSource(proof, kind);
    String parser =
        kind == MetadataStructureAttestation.Kind.ROOT
            ? "wow-root-tsfm-v1v2-r1"
            : "install-manifest-v1-r1";
    if (!proof.decodedAttestation().orElseThrow().verifierRevision().equals(DECODED_REVISION)
        || !proof.structuralAttestation().orElseThrow().parserRevision().equals(parser))
      throw failure(
          UNSUPPORTED_REUSE, "Historical metadata verification capability is unsupported");
  }

  private static void requireSupportedSource(
      MetadataContentProof proof, MetadataStructureAttestation.Kind kind) {
    String semantics =
        kind == MetadataStructureAttestation.Kind.ROOT ? ROOT_SEMANTICS : INSTALL_SEMANTICS;
    if (!proof.originalAlias().source().verificationSemantics().equals(semantics)
        || !proof.originalAlias().evidence().verifierSemantics().equals(semantics))
      throw failure(UNSUPPORTED_REUSE, "Original metadata source verification is unsupported");
  }

  private static void validateConfiguration(
      MetadataDeclaration declaration,
      LookupBudgets budgets,
      CancellationToken token,
      ObjectReadLease lease) {
    var expected =
        readConfigurationDeclaration(
            declaration.build(),
            declaration.configuration(),
            declaration.kind(),
            budgets,
            token,
            lease);
    if (!expected.equals(declaration))
      throw failure(
          INTEGRITY_FAILURE, "Metadata declaration differs from exact retained configuration");
  }

  private static void requireConfigurationOrigin(
      BuildSnapshot build, VerifiedSourceAlias configuration) {
    if (!build.productId().equals("wow"))
      throw failure(
          UNSUPPORTED_REUSE, "Metadata declarations require the reviewed raw wow namespace");
    var identity =
        new ResolvedSourceIdentity(
            "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1");
    if (!configuration.source().equals(identity))
      throw failure(INTEGRITY_FAILURE, "Retained configuration belongs to a different exact build");
  }

  private static MetadataDeclaration readConfigurationDeclaration(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      MetadataStructureAttestation.Kind kind,
      LookupBudgets budgets,
      CancellationToken token,
      ObjectReadLease lease) {
    requireConfigurationOrigin(build, configuration);
    token.throwIfCancellationRequested();
    CapturedBuildSource.resolve(build);
    CapturedBuildSource.validateConfigurationProof(
        configuration.evidence().sourceUrl(), build.buildConfig());
    int maximum = (int) Math.min(1L << 20, budgets.maximumConfigurationBytes());
    byte[] bytes = read(lease, configuration.object(), maximum, token);
    try {
      var config =
          new TactConfigParser()
              .parse(
                  bytes,
                  build.buildConfig(),
                  new TactConfigParser.Limits(
                      maximum, 8192, 16384, 256, Long.MAX_VALUE, Long.MAX_VALUE),
                  token::isCancellationRequested);
      MetadataDeclaration expected;
      if (kind == MetadataStructureAttestation.Kind.INSTALL) {
        var install = config.installBootstrap();
        expected =
            new MetadataDeclaration(
                build,
                configuration,
                kind,
                new ContentSlotKey("wow", install.contentKey()),
                Optional.of(install.encodingKey()),
                OptionalLong.of(install.encodedSize()),
                OptionalLong.of(install.decodedSize()));
      } else {
        var root = config.values().get("root");
        if (root == null || root.size() != 1 || !root.getFirst().matches("[0-9a-fA-F]{32}"))
          throw failure(UNSUPPORTED_REUSE, "Configuration lacks supported exact root declaration");
        expected =
            new MetadataDeclaration(
                build,
                configuration,
                kind,
                new ContentSlotKey("wow", root.getFirst()),
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.empty());
      }
      token.throwIfCancellationRequested();
      return expected;
    } catch (TactConfigException invalid) {
      throw new ContentClaimException(
          invalid.reason() == TactConfigException.Reason.UNSUPPORTED
              ? UNSUPPORTED_REUSE
              : INTEGRITY_FAILURE,
          "Retained configuration cannot authorize metadata reuse",
          invalid);
    }
  }

  private static byte[] read(
      ObjectReadLease lease, StoredObject expected, int bound, CancellationToken token) {
    if (!lease.object().equals(expected))
      throw failure(INTEGRITY_FAILURE, "Physical metadata changed");
    if (expected.length() > bound)
      throw failure(UNSUPPORTED_REUSE, "Local metadata exceeds read budget");
    try {
      byte[] bytes = lease.stream().readNBytes(bound + 1);
      token.throwIfCancellationRequested();
      if (bytes.length != expected.length())
        throw failure(INTEGRITY_FAILURE, "Local metadata length differs");
      return bytes;
    } catch (IOException invalid) {
      throw new ContentClaimException(STORAGE_UNAVAILABLE, "Cannot read local metadata", invalid);
    }
  }

  private static <T> T local(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (RuntimeException failure) {
      throw normalize(failure);
    }
  }

  private static RuntimeException normalize(RuntimeException failure) {
    if (failure instanceof ContentClaimException || failure instanceof CancellationException)
      return failure;
    ContentClaimException.Code code;
    if (failure instanceof ObjectStoreException stored) {
      code =
          switch (stored.code()) {
            case MISSING_OBJECT -> MISSING_LOCAL;
            case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
            case BUSY, STORE_IN_USE -> BUSY;
            case LIMIT_EXCEEDED -> UNSUPPORTED_REUSE;
            case CLOSED -> INVALID_STATE;
            case IO_FAILURE -> STORAGE_UNAVAILABLE;
          };
    } else if (failure instanceof FileLookupException lookup) {
      code =
          switch (lookup.code()) {
            case MISSING_LOCAL, UNAVAILABLE -> MISSING_LOCAL;
            case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
            case UNSUPPORTED, BUDGET_EXCEEDED -> UNSUPPORTED_REUSE;
            case CAPACITY_EXCEEDED -> BUSY;
            case INVALID_PLAN, INVALID_REQUEST -> INVALID_REQUEST;
            case CLOSED -> INVALID_STATE;
            case STORAGE_FAILURE -> STORAGE_UNAVAILABLE;
          };
    } else if (failure instanceof BootstrapException install) {
      if (install.reason() == BootstrapException.Reason.CANCELLED) {
        var cancelled = new CancellationException(install.getMessage());
        cancelled.initCause(install);
        return cancelled;
      }
      code =
          switch (install.reason()) {
            case MISSING_LOCAL, UNAVAILABLE -> MISSING_LOCAL;
            case INTEGRITY, MALFORMED -> INTEGRITY_FAILURE;
            case UNSUPPORTED, LIMIT -> UNSUPPORTED_REUSE;
            case CAPACITY -> BUSY;
            case CLOSED -> INVALID_STATE;
            case STORAGE, TRANSPORT -> STORAGE_UNAVAILABLE;
            case CANCELLED -> throw new AssertionError("Cancellation classified above");
          };
    } else if (failure instanceof SourceContextException context) {
      code =
          context.reason() == SourceContextException.Reason.UNSUPPORTED
              ? UNSUPPORTED_REUSE
              : INTEGRITY_FAILURE;
    } else return failure;
    return new ContentClaimException(code, failure.getMessage(), failure);
  }

  private void ensureOpen() {
    if (closed) throw failure(INVALID_STATE, "Metadata reuse verifier is closed");
  }

  private static ContentClaimException failure(ContentClaimException.Code code, String message) {
    return new ContentClaimException(code, message);
  }

  @Override
  public void close() {
    closed = true;
    roots.close();
  }
}
