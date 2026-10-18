package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.application.content.ContentClaimException.Code.*;

import io.vibeminer.application.*;
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

  @Override
  public void validateDeclaration(
      MetadataDeclaration declaration, LookupBudgets budgets, CancellationToken cancellation) {
    ensureOpen();
    try (var lease = store.openRead(declaration.configuration().object().digest(), cancellation)) {
      validateConfiguration(declaration, budgets, cancellation, lease);
    }
  }

  @Override
  public MetadataContentProof replay(
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
      if (actual.object().length()
              > Math.min(
                  budgets.maximumRootEncodedBytes(), InstallMetadataVerifier.MAXIMUM_ENCODED_BYTES)
          || decoded
              > Math.min(
                  budgets.maximumRootDecodedBytes(), InstallMetadataVerifier.MAXIMUM_DECODED_BYTES)
          || decoded > budgets.maximumDecodedWorkBytes())
        throw failure(UNSUPPORTED_REUSE, "Install replay exceeds bounded work");
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
    String semantics =
        kind == MetadataStructureAttestation.Kind.ROOT ? ROOT_SEMANTICS : INSTALL_SEMANTICS;
    String parser =
        kind == MetadataStructureAttestation.Kind.ROOT
            ? "wow-root-tsfm-v1v2-r1"
            : "install-manifest-v1-r1";
    if (!proof.originalAlias().source().verificationSemantics().equals(semantics)
        || !proof.originalAlias().evidence().verifierSemantics().equals(semantics)
        || !proof.decodedAttestation().orElseThrow().verifierRevision().equals(DECODED_REVISION)
        || !proof.structuralAttestation().orElseThrow().parserRevision().equals(parser))
      throw failure(
          UNSUPPORTED_REUSE, "Historical metadata verification capability is unsupported");
  }

  private static void validateConfiguration(
      MetadataDeclaration declaration,
      LookupBudgets budgets,
      CancellationToken token,
      ObjectReadLease lease) {
    token.throwIfCancellationRequested();
    CapturedBuildSource.resolve(declaration.build());
    CapturedBuildSource.validateConfigurationProof(
        declaration.configuration().evidence().sourceUrl(), declaration.build().buildConfig());
    int maximum = (int) Math.min(1L << 20, budgets.maximumConfigurationBytes());
    byte[] bytes = read(lease, declaration.configuration().object(), maximum, token);
    try {
      var config =
          new TactConfigParser()
              .parse(
                  bytes,
                  declaration.build().buildConfig(),
                  new TactConfigParser.Limits(
                      maximum, 8192, 16384, 256, Long.MAX_VALUE, Long.MAX_VALUE),
                  token::isCancellationRequested);
      MetadataDeclaration expected;
      if (declaration.kind() == MetadataStructureAttestation.Kind.INSTALL) {
        var install = config.installBootstrap();
        expected =
            new MetadataDeclaration(
                declaration.build(),
                declaration.configuration(),
                declaration.kind(),
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
                declaration.build(),
                declaration.configuration(),
                declaration.kind(),
                new ContentSlotKey("wow", root.getFirst()),
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.empty());
      }
      if (!expected.equals(declaration))
        throw failure(
            INTEGRITY_FAILURE, "Metadata declaration differs from exact retained configuration");
      token.throwIfCancellationRequested();
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
