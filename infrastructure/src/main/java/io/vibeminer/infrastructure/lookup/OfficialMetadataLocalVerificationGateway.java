package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.application.content.ContentClaimException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.bootstrap.InstallMetadataVerifier;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Full local original-A verification. Its internal source reader cannot perform HTTP. */
public final class OfficialMetadataLocalVerificationGateway
    implements MetadataLocalVerificationGateway, AutoCloseable {
  private final ObjectStore store;
  private final OfficialMetadataReuseGateway declarations;
  private final OfficialFileLookupGateway roots;
  private boolean closed;

  public OfficialMetadataLocalVerificationGateway(ObjectStore store) {
    this.store = Objects.requireNonNull(store);
    declarations = new OfficialMetadataReuseGateway(store);
    roots =
        new OfficialFileLookupGateway(
            store,
            new LookupTransport() {
              public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
                throw new AssertionError(
                    "Local original verification cannot acquire configuration");
              }

              public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
                throw new AssertionError("Local original verification cannot acquire payload");
              }
            },
            Clock.systemUTC());
  }

  @Override
  public MetadataContentProof verifyOriginal(
      MetadataContentRequest request,
      MetadataContentProof original,
      LookupBudgets budgets,
      CancellationToken cancellation) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(original);
    Objects.requireNonNull(budgets);
    Objects.requireNonNull(cancellation).throwIfCancellationRequested();
    if (closed) throw failure(INVALID_STATE, "Local original verifier is closed");
    try {
      requireOriginal(request, original);
      var declaration =
          declarations.readDeclaration(
              request.build(), request.configuration(), request.kind(), budgets, cancellation);
      if (!declaration.slot().equals(request.slot())
          || declaration.preferredEncodingKey().isPresent()
              && !declaration.preferredEncodingKey().get().equals(original.actualEncodingKey())
          || declaration.declaredEncodedBytes().isPresent()
              && declaration.declaredEncodedBytes().getAsLong() != original.object().length()
          || declaration.declaredDecodedBytes().isPresent()
              && declaration.declaredDecodedBytes().getAsLong()
                  != request.declaredDecodedBytes().orElseThrow())
        throw failure(
            INTEGRITY_FAILURE, "Local original request differs from authenticated configuration");
      long decoded = request.declaredDecodedBytes().orElseThrow();
      if (original.object().length() > budgets.maximumRootEncodedBytes()
          || decoded > budgets.maximumRootDecodedBytes()
          || decoded > budgets.maximumDecodedWorkBytes())
        throw failure(UNSUPPORTED_REUSE, "Local original verification exceeds bounded work");
      MetadataContentProof fresh;
      if (request.kind() == MetadataStructureAttestation.Kind.ROOT) {
        var observed = new AtomicReference<MetadataContentProof>();
        var result =
            roots.lookupRetained(
                request.build(),
                request.configuration(),
                original.originalAlias(),
                Set.of(),
                budgets,
                new MetadataPublicationObserver() {
                  public void beforePromotion(MetadataContentProof proof, CancellationToken token) {
                    throw new AssertionError("Local original verification cannot publish");
                  }

                  public void afterStructureValidation(
                      MetadataContentProof proof, CancellationToken token) {
                    if (!observed.compareAndSet(null, proof))
                      throw failure(INTEGRITY_FAILURE, "Repeated local root attestation");
                  }
                },
                cancellation);
        if (!result.root().equals(original.originalAlias()) || !result.variants().isEmpty())
          throw failure(INTEGRITY_FAILURE, "Local root verification changed original scope");
        fresh = Objects.requireNonNull(observed.get());
      } else {
        if (original.object().length() > InstallMetadataVerifier.MAXIMUM_ENCODED_BYTES
            || decoded > InstallMetadataVerifier.MAXIMUM_DECODED_BYTES)
          throw failure(UNSUPPORTED_REUSE, "Local install exceeds bounded verifier");
        try (var lease = store.openRead(original.object().digest(), cancellation)) {
          if (!lease.object().equals(original.object()))
            throw failure(INTEGRITY_FAILURE, "Local original physical identity changed");
          byte[] bytes = lease.stream().readNBytes(Math.toIntExact(original.object().length()) + 1);
          cancellation.throwIfCancellationRequested();
          if (bytes.length != original.object().length())
            throw failure(INTEGRITY_FAILURE, "Local install length changed");
          new InstallMetadataVerifier()
              .decodeAndParse(
                  bytes,
                  decoded,
                  original.actualEncodingKey(),
                  original.contentKey(),
                  cancellation);
        }
        fresh =
            new MetadataContentProof(
                original.originalAlias(),
                original.actualEncodingKey(),
                original.contentKey(),
                original.declaredDecodedBytes(),
                Optional.of(
                    new DecodedContentAttestation(
                        original.contentKey(), decoded, "blte-nz-full-ckey-v1")),
                Optional.of(
                    new MetadataStructureAttestation(
                        MetadataStructureAttestation.Kind.INSTALL, "install-manifest-v1-r1")));
      }
      cancellation.throwIfCancellationRequested();
      // Fresh facts do not replace any historical capability; application owns add-missing policy.
      return new MetadataContentProof(
          original.originalAlias(),
          original.actualEncodingKey(),
          original.contentKey(),
          original.declaredDecodedBytes(),
          fresh.decodedAttestation(),
          fresh.structuralAttestation());
    } catch (RuntimeException failed) {
      throw OfficialMetadataReuseGateway.normalize(failed);
    } catch (IOException failed) {
      throw new ContentClaimException(
          STORAGE_UNAVAILABLE, "Cannot read local original metadata", failed);
    }
  }

  private static void requireOriginal(
      MetadataContentRequest request, MetadataContentProof original) {
    String namespace =
        request.kind() == MetadataStructureAttestation.Kind.ROOT
            ? "wow.root.tsfm.v1v2"
            : "tact.install.v1";
    String semantics =
        request.kind() == MetadataStructureAttestation.Kind.ROOT
            ? "blte-nz-ekey-chunks-full-ckey+tsfm-v1v2-structural-eof-r1"
            : "blte-nz-ekey-header-or-whole-chunk-md5-ckey-md5+in-v1-r1";
    var source = original.originalAlias().source();
    var tuple = source.immutableSourceKey().split(";", -1);
    if (!source.namespace().equals(namespace) || !source.verificationSemantics().equals(semantics))
      throw failure(UNSUPPORTED_REUSE, "Unsupported original metadata verifier scope");
    if (!request.slot().contentKey().equals(original.contentKey())
        || !request.requestedEncodingKey().equals(original.actualEncodingKey())
        || request.requestedEncodedBytes() != original.object().length()
        || request.declaredDecodedBytes().isEmpty()
        || !tuple[2].equals(Long.toString(request.declaredDecodedBytes().getAsLong())))
      throw failure(INTEGRITY_FAILURE, "Local verification must bind exact original A tuple");
    CapturedBuildSource.validateDataProof(
        original.originalAlias().evidence().sourceUrl(), original.actualEncodingKey());
  }

  private static ContentClaimException failure(ContentClaimException.Code code, String message) {
    return new ContentClaimException(code, message);
  }

  @Override
  public void close() {
    closed = true;
    roots.close();
    declarations.close();
  }
}
