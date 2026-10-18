package io.vibeminer.infrastructure.buildopening;

import static io.vibeminer.application.content.ContentClaimException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.BuildObjectCatalog;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.tact.blte.StreamingDecodedBlteInputStream;
import io.vibeminer.protocol.tact.config.*;
import io.vibeminer.protocol.tact.download.DownloadInventoryReader;
import io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryReader;
import io.vibeminer.protocol.tact.encoding.*;
import java.io.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocking EN/DL/DS source worker, invoked inside the existing actual source Runnable. Borrows the
 * one store/catalog/canonical publisher/transport; it creates no coordinator, client or membership.
 * First pass publishes only encoded bytes after full encoded and decoded authentication. A second
 * leased local pass proves complete supported grammar. No decoded file or persistent range cache.
 * Decode admission reserves both complete passes; EN grammar admission conservatively reserves two
 * complete decoded walks (each page is validated and emitted). Actual parser byte/count limits and
 * BLTE read/inflate limits apply incrementally. The monotonic deadline is cooperative between
 * bounded units; it does not interrupt arbitrary local filesystem I/O. Source-worker ownership
 * lasts until those synchronous operations and their leases have exited.
 */
public final class OfficialBuildMetadataSourceWorker {
  private static final String DECODER = "blte-nz-full-ckey-stream-v1";
  private static final String SEMANTICS = "blte-complete-encoded-and-ckey-stream-v1";
  private final ObjectStore store;
  private final BuildObjectCatalog builds;
  private final PinnedCdnRangeTransport transport;
  private final Clock clock;
  private final MetadataContentAcquisition acquisition;
  private final java.util.function.LongSupplier nanoClock;

  public OfficialBuildMetadataSourceWorker(
      ObjectStore store,
      CanonicalContentRepository canonical,
      BuildObjectCatalog builds,
      PinnedCdnRangeTransport transport,
      Clock clock) {
    this(store, canonical, builds, transport, clock, System::nanoTime);
  }

  OfficialBuildMetadataSourceWorker(
      ObjectStore store,
      CanonicalContentRepository canonical,
      BuildObjectCatalog builds,
      PinnedCdnRangeTransport transport,
      Clock clock,
      java.util.function.LongSupplier nanoClock) {
    this.nanoClock = Objects.requireNonNull(nanoClock);
    this.store = Objects.requireNonNull(store);
    this.builds = Objects.requireNonNull(builds);
    this.transport = Objects.requireNonNull(transport);
    this.clock = Objects.requireNonNull(clock);
    acquisition = new MetadataContentAcquisition(Objects.requireNonNull(canonical));
  }

  public VerifiedSourceAlias acquire(
      MetadataContentRequest request,
      MetadataStreamBudgets budgets,
      CancellationToken cancellation) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(budgets);
    Objects.requireNonNull(cancellation);
    var operation = new Operation(budgets, cancellation, nanoClock);
    operation.token.throwIfCancellationRequested();
    if (request.kind() == MetadataStructureAttestation.Kind.ROOT
        || request.kind() == MetadataStructureAttestation.Kind.INSTALL)
      throw failure(UNSUPPORTED_REUSE, "This source worker admits only EN, DL and DS");
    if (request.declaredDecodedBytes().isEmpty())
      throw failure(INTEGRITY_FAILURE, "Exact config-declared decoded size is required");
    long decoded = request.declaredDecodedBytes().getAsLong();
    preflight(request, decoded, budgets);
    // Mark before local source I/O, canonical admission, or payload acquisition.
    store.publisherStarted();
    var captured = CapturedBuildSource.resolve(request.build());
    authenticateConfiguration(request, budgets, operation.token);
    var source =
        new ResolvedSourceIdentity(
            namespace(request.kind()),
            request.slot().contentKey()
                + ";"
                + request.requestedEncodingKey()
                + ";"
                + decoded
                + ";"
                + request.requestedEncodedBytes(),
            SEMANTICS);
    return acquisition.acquire(
        request,
        source,
        () -> builds.findVerifiedSourceAlias(source, operation.token),
        (observer, token) ->
            transfer(request, source, captured, budgets, operation, observer, null),
        (alias, observer, token) -> replay(request, source, alias, budgets, operation, observer),
        (original, observer, token) ->
            transfer(request, source, captured, budgets, operation, observer, original),
        operation.token);
  }

  /**
   * Strict readonly replay for an existing admission. Does not consult/recreate canonical claims,
   * mark a producer, acquire network bytes or publish objects. SQL authority is deliberately the
   * caller's separate recheck; this method proves only current complete bytes and supported
   * grammar, preserving the exact historical receipt and current capability inventory.
   */
  public MetadataContentProof verifyRetained(
      MetadataContentRequest request,
      MetadataContentProof expected,
      MetadataStreamBudgets budgets,
      CancellationToken cancellation) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(expected);
    Objects.requireNonNull(budgets);
    Objects.requireNonNull(cancellation);
    var operation = new Operation(budgets, cancellation, nanoClock);
    operation.token.throwIfCancellationRequested();
    if (request.kind() == MetadataStructureAttestation.Kind.ROOT
        || request.kind() == MetadataStructureAttestation.Kind.INSTALL)
      throw failure(UNSUPPORTED_REUSE, "Readonly streaming replay admits only EN, DL and DS");
    if (request.declaredDecodedBytes().isEmpty())
      throw failure(INTEGRITY_FAILURE, "Exact decoded declaration required for readonly replay");
    long decoded = request.declaredDecodedBytes().getAsLong();
    preflight(request, decoded, budgets);
    CapturedBuildSource.resolve(request.build());
    authenticateConfiguration(request, budgets, operation.token);
    var source =
        new ResolvedSourceIdentity(
            namespace(request.kind()),
            request.slot().contentKey()
                + ";"
                + request.requestedEncodingKey()
                + ";"
                + decoded
                + ";"
                + request.requestedEncodedBytes(),
            SEMANTICS);
    var alias = expected.originalAlias();
    if (!alias.source().equals(source)
        || alias.object().length() != request.requestedEncodedBytes()
        || !expected.equals(proof(request, alias, true)))
      throw failure(
          UNSUPPORTED_REUSE, "Readonly metadata differs from exact supported original proof");
    CapturedBuildSource.validateDataProof(
        alias.evidence().sourceUrl(), request.requestedEncodingKey());
    validateLocal(request, alias, budgets, operation);
    operation.token.throwIfCancellationRequested();
    return expected;
  }

  private VerifiedSourceAlias transfer(
      MetadataContentRequest request,
      ResolvedSourceIdentity source,
      CdnRequest.Source captured,
      MetadataStreamBudgets budgets,
      Operation operation,
      MetadataPublicationObserver observer,
      MetadataContentProof original) {
    boolean[] gateEntered = {false};
    try {
      var resource =
          new CdnResource(captured, request.requestedEncodingKey(), CdnResource.Kind.DATA);
      if (original != null
          && !original.originalAlias().evidence().sourceUrl().equals(resource.uri().toString()))
        throw failure(UNSUPPORTED_REUSE, "Initial continuation requires the original pinned route");
      var receipt = new AtomicReference<PinnedRangeInputStream.RangeObservation>();
      var prospective = new AtomicReference<MetadataContentProof>();
      try (var wire =
              new PinnedRangeInputStream(
                  transport,
                  resource,
                  request.requestedEncodedBytes(),
                  0,
                  request.requestedEncodedBytes(),
                  budgets.rangeSliceBytes(),
                  new PinnedRangeInputStream.Limits(
                      budgets.maximumWireBytes(), budgets.maximumRequests(), budgets.timeout()),
                  operation.token::isCancellationRequested,
                  observation -> {
                    var prior = receipt.get();
                    if (prior != null
                        && !prior.actualSourceUri().equals(observation.actualSourceUri()))
                      throw failure(INTEGRITY_FAILURE, "Metadata ranges changed actual source");
                    receipt.set(observation);
                  });
          var encoded =
              new StreamingDecodedBlteInputStream.EncodedPublicationSource(
                  wire,
                  request.requestedEncodingKey(),
                  request.slot().contentKey(),
                  request.requestedEncodedBytes(),
                  request.declaredDecodedBytes().orElseThrow(),
                  budgets.blte(),
                  operation.token::isCancellationRequested)) {
        var published =
            store.publishVerified(
                encoded,
                request.requestedEncodedBytes(),
                budgets.blte().maximumEncodedBytes(),
                token -> {
                  operation.token.throwIfCancellationRequested();
                  StreamingDecodedBlteInputStream.CompletionProof complete;
                  try {
                    complete = encoded.completionProof();
                  } catch (IOException invalid) {
                    throw failure(INTEGRITY_FAILURE, "Incomplete metadata stream", invalid);
                  }
                  var observed = receipt.get();
                  if (observed == null || !resource.uri().equals(observed.actualSourceUri()))
                    throw failure(INTEGRITY_FAILURE, "Missing exact source observation");
                  var object =
                      new StoredObject(
                          new ObjectDigest(complete.encodedProof().physicalSha256()),
                          complete.encodedProof().encodedBytes());
                  if (original != null
                      && (!original.object().equals(object)
                          || !original
                              .originalAlias()
                              .evidence()
                              .sourceUrl()
                              .equals(observed.actualSourceUri().toString())))
                    throw failure(
                        INTEGRITY_FAILURE,
                        "Initial continuation differs from original physical bytes or source");
                  var alias =
                      original != null
                          ? original.originalAlias()
                          : new VerifiedSourceAlias(
                              source,
                              object,
                              new SourceVerificationEvidence(
                                  SEMANTICS,
                                  observed.actualSourceUri().toString(),
                                  clock.instant()));
                  var proof = original != null ? original : proof(request, alias, false);
                  prospective.set(proof);
                  gateEntered[0] = true;
                  observer.beforePromotion(proof, operation.token);
                },
                operation.token);
        operation.token.throwIfCancellationRequested();
        var proof = prospective.get();
        if (proof == null || !proof.object().equals(published))
          throw failure(INTEGRITY_FAILURE, "CAS publication changed completed metadata bytes");
        validateLocal(request, proof.originalAlias(), budgets, operation);
        observer.afterStructureValidation(
            proof(request, proof.originalAlias(), true), operation.token);
        operation.token.throwIfCancellationRequested();
        return proof.originalAlias();
      }
    } catch (IOException invalid) {
      throw scoped(gateEntered[0], operation.map(protocolIo(invalid)));
    } catch (RuntimeException failed) {
      RuntimeException mapped = failed;
      if (failed instanceof ObjectStoreException storage
          && storage.code() == ObjectStoreException.Code.IO_FAILURE
          && recognizedStreamFailure(storage.getCause()))
        mapped = protocolIo((IOException) storage.getCause());
      throw scoped(gateEntered[0], operation.map(mapped));
    }
  }

  private void replay(
      MetadataContentRequest request,
      ResolvedSourceIdentity source,
      VerifiedSourceAlias alias,
      MetadataStreamBudgets budgets,
      Operation operation,
      MetadataPublicationObserver observer) {
    if (!alias.source().equals(source)
        || alias.object().length() != request.requestedEncodedBytes())
      throw failure(UNSUPPORTED_REUSE, "Retained metadata has another exact source capability");
    CapturedBuildSource.validateDataProof(
        alias.evidence().sourceUrl(), request.requestedEncodingKey());
    validateLocal(request, alias, budgets, operation);
    observer.afterStructureValidation(proof(request, alias, true), operation.token);
    operation.token.throwIfCancellationRequested();
  }

  private void validateLocal(
      MetadataContentRequest request,
      VerifiedSourceAlias alias,
      MetadataStreamBudgets budgets,
      Operation operation) {
    try (var lease = store.openRead(alias.object().digest(), operation.token)) {
      if (!lease.object().equals(alias.object()))
        throw failure(INTEGRITY_FAILURE, "Retained metadata geometry changed");
      try (var decoded =
          new StreamingDecodedBlteInputStream(
              lease.stream(),
              request.requestedEncodingKey(),
              request.slot().contentKey(),
              alias.object().length(),
              request.declaredDecodedBytes().orElseThrow(),
              budgets.blte(),
              operation.token::isCancellationRequested)) {
        switch (request.kind()) {
          case ENCODING ->
              new FullEncodingInventory()
                  .read(
                      decoded,
                      budgets.encoding(),
                      DISCARD_ENCODING,
                      operation.token::isCancellationRequested);
          case DOWNLOAD ->
              new DownloadInventoryReader()
                  .read(
                      decoded,
                      budgets.download(),
                      DISCARD_DOWNLOAD,
                      operation.token::isCancellationRequested);
          case DOWNLOAD_SIZE ->
              new DownloadSizeInventoryReader()
                  .read(
                      decoded,
                      budgets.downloadSize(),
                      (ordinal, entry) -> {},
                      operation.token::isCancellationRequested);
          default -> throw failure(UNSUPPORTED_REUSE, "Unsupported streaming metadata grammar");
        }
        var completed = decoded.completionProof();
        if (!completed.encodedProof().physicalSha256().equals(alias.object().digest().value())
            || completed.encodedProof().encodedBytes() != alias.object().length())
          throw failure(INTEGRITY_FAILURE, "Local metadata physical proof changed");
        operation.token.throwIfCancellationRequested();
      }
    } catch (io.vibeminer.protocol.tact.encoding.FullEncodingInventoryException invalid) {
      throw operation.map(
          protocol(invalid.reason().name(), "Encoding grammar verification failed", invalid));
    } catch (io.vibeminer.protocol.tact.download.DownloadInventoryException invalid) {
      throw operation.map(
          protocol(invalid.reason().name(), "Download grammar verification failed", invalid));
    } catch (io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryException invalid) {
      throw operation.map(
          protocol(invalid.reason().name(), "Download-size grammar verification failed", invalid));
    } catch (IOException invalid) {
      throw operation.map(protocolIo(invalid));
    }
  }

  private void authenticateConfiguration(
      MetadataContentRequest request, MetadataStreamBudgets budgets, CancellationToken token) {
    var alias = request.configuration();
    CapturedBuildSource.validateConfigurationProof(
        alias.evidence().sourceUrl(), request.build().buildConfig());
    if (alias.object().length() < 1 || alias.object().length() > budgets.configuration().maxBytes())
      throw limit("Configuration exceeds admitted byte bound");
    try (var lease = store.openRead(alias.object().digest(), token)) {
      if (!lease.object().equals(alias.object()))
        throw failure(INTEGRITY_FAILURE, "Config geometry changed");
      byte[] raw = lease.stream().readNBytes((int) alias.object().length());
      token.throwIfCancellationRequested();
      if (raw.length != alias.object().length()
          || lease.stream().read() != -1
          || !sha256(raw).equals(alias.object().digest().value()))
        throw failure(INTEGRITY_FAILURE, "Exact configuration physical bytes changed");
      var config =
          new TactConfigParser()
              .parse(
                  raw,
                  request.build().buildConfig(),
                  budgets.configuration(),
                  token::isCancellationRequested);
      String field =
          switch (request.kind()) {
            case ENCODING -> "encoding";
            case DOWNLOAD -> "download";
            case DOWNLOAD_SIZE -> "size";
            default -> throw failure(UNSUPPORTED_REUSE, "Unsupported metadata declaration");
          };
      var keys = config.values().get(field);
      var sizes = config.values().get(field + "-size");
      if (keys == null
          || sizes == null
          || keys.size() != 2
          || sizes.size() != 2
          || !keys.get(0).equalsIgnoreCase(request.slot().contentKey())
          || !keys.get(1).equalsIgnoreCase(request.requestedEncodingKey())
          || parseSize(sizes.get(0)) != request.declaredDecodedBytes().orElseThrow()
          || parseSize(sizes.get(1)) != request.requestedEncodedBytes())
        throw failure(
            INTEGRITY_FAILURE, "Request differs from authenticated direct metadata declaration");
      token.throwIfCancellationRequested();
    } catch (TactConfigException invalid) {
      if (invalid.reason() == TactConfigException.Reason.CANCELLED)
        token.throwIfCancellationRequested();
      throw protocol(
          invalid.reason().name(), "Configuration declaration verification failed", invalid);
    } catch (IOException invalid) {
      throw failure(INTEGRITY_FAILURE, "Configuration replay failed", invalid);
    }
  }

  private static long parseSize(String value) {
    if (!value.matches("[0-9]{1,20}"))
      throw failure(INTEGRITY_FAILURE, "Invalid config metadata size");
    try {
      long result = Long.parseLong(value);
      if (result > 0) return result;
    } catch (NumberFormatException overflow) {
      /* fail below */
    }
    throw failure(INTEGRITY_FAILURE, "Metadata declaration requires positive signed-long sizes");
  }

  private static void preflight(
      MetadataContentRequest request, long decoded, MetadataStreamBudgets budgets) {
    long decodeWork, grammarWork;
    try {
      decodeWork = MetadataStreamBudgets.requiredDecodeWork(decoded);
      grammarWork = MetadataStreamBudgets.requiredGrammarWork(request.kind(), decoded);
    } catch (IllegalArgumentException overflow) {
      throw limit("Metadata work overflows", overflow);
    }
    long requests = (request.requestedEncodedBytes() - 1) / budgets.rangeSliceBytes() + 1;
    long parserLimit =
        switch (request.kind()) {
          case ENCODING -> budgets.encoding().maxDecodedBytes();
          case DOWNLOAD -> budgets.download().maxDecodedBytes();
          case DOWNLOAD_SIZE -> budgets.downloadSize().maxDecodedBytes();
          default -> 0;
        };
    if (request.requestedEncodedBytes() > budgets.maximumWireBytes()
        || request.requestedEncodedBytes() > budgets.blte().maximumEncodedBytes()
        || requests > budgets.maximumRequests()
        || decoded > budgets.blte().maximumDecodedBytes()
        || decoded > parserLimit
        || decoded > budgets.configuration().maxBootstrapDecodedBytes()
        || request.requestedEncodedBytes() > budgets.configuration().maxBootstrapEncodedBytes()
        || decodeWork > budgets.maximumDecodedWorkBytes()
        || grammarWork > budgets.maximumGrammarWorkBytes())
      throw limit("Whole metadata work exceeds explicit admission budgets");
  }

  private static MetadataContentProof proof(
      MetadataContentRequest request, VerifiedSourceAlias alias, boolean grammar) {
    return new MetadataContentProof(
        alias,
        request.requestedEncodingKey(),
        request.slot().contentKey(),
        request.declaredDecodedBytes(),
        Optional.of(
            new DecodedContentAttestation(
                request.slot().contentKey(),
                request.declaredDecodedBytes().orElseThrow(),
                DECODER)),
        grammar
            ? Optional.of(
                new MetadataStructureAttestation(request.kind(), parserRevision(request.kind())))
            : Optional.empty());
  }

  private static String namespace(MetadataStructureAttestation.Kind kind) {
    return switch (kind) {
      case ENCODING -> "tact.encoding.v1";
      case DOWNLOAD -> "tact.download.v1v2v3";
      case DOWNLOAD_SIZE -> "tact.download-size.v1";
      default -> throw failure(UNSUPPORTED_REUSE, "Unsupported source kind");
    };
  }

  private static String parserRevision(MetadataStructureAttestation.Kind kind) {
    return switch (kind) {
      case ENCODING -> "encoding-v1-full-r1";
      case DOWNLOAD -> "download-v1v2v3-r1";
      case DOWNLOAD_SIZE -> "download-size-v1-r1";
      default -> throw failure(UNSUPPORTED_REUSE, "Unsupported parser kind");
    };
  }

  private static MetadataAcquisitionFailure scoped(boolean gate, RuntimeException failed) {
    return new MetadataAcquisitionFailure(
        gate
            ? MetadataAcquisitionFailure.Publication.UNCERTAIN
            : MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
        failed);
  }

  private static String sha256(byte[] raw) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static ContentClaimException failure(ContentClaimException.Code code, String message) {
    return new ContentClaimException(code, message);
  }

  private static ContentClaimException failure(
      ContentClaimException.Code code, String message, Throwable cause) {
    return new ContentClaimException(code, message, cause);
  }

  private static boolean recognizedStreamFailure(Throwable failure) {
    return failure instanceof io.vibeminer.protocol.tact.blte.StreamingDecodedBlteException
        || failure instanceof io.vibeminer.protocol.tact.blte.EncodedBlteException
        || failure instanceof java.io.InterruptedIOException
        || failure instanceof IOException io && io.getCause() instanceof AcquisitionException;
  }

  private static RuntimeException protocolIo(IOException invalid) {
    if (invalid.getCause() instanceof AcquisitionException remote) return remote;
    if (invalid instanceof java.io.InterruptedIOException) {
      var stopped = new java.util.concurrent.CancellationException("Metadata stream interrupted");
      stopped.initCause(invalid);
      return stopped;
    }
    if (invalid instanceof io.vibeminer.protocol.tact.blte.StreamingDecodedBlteException blte)
      return protocol(blte.reason().name(), "Streaming BLTE verification failed", invalid);
    if (invalid instanceof io.vibeminer.protocol.tact.blte.EncodedBlteException encoded)
      return protocol(
          encoded.reason().name(), "Complete encoded BLTE verification failed", invalid);
    return failure(INTEGRITY_FAILURE, "Metadata stream I/O failed", invalid);
  }

  private static RuntimeException protocol(String reason, String message, Exception cause) {
    return switch (reason) {
      case "LIMIT" -> limit(message, cause);
      case "UNSUPPORTED" -> failure(UNSUPPORTED_REUSE, message, cause);
      case "CANCELLED" -> {
        var stopped = new java.util.concurrent.CancellationException(message);
        stopped.initCause(cause);
        yield stopped;
      }
      default -> failure(INTEGRITY_FAILURE, message, cause);
    };
  }

  private static MetadataSourceException limit(String message) {
    return new MetadataSourceException(MetadataSourceException.Reason.LIMIT, message);
  }

  private static MetadataSourceException limit(String message, Throwable cause) {
    return new MetadataSourceException(MetadataSourceException.Reason.LIMIT, message, cause);
  }

  private static final class Operation {
    final CancellationToken token;

    Operation(
        MetadataStreamBudgets budgets,
        CancellationToken caller,
        java.util.function.LongSupplier nanoClock) {
      long started = nanoClock.getAsLong(), maximum = budgets.timeout().toNanos();
      token =
          new CancellationToken() {
            public boolean isCancellationRequested() {
              return caller.isCancellationRequested() || nanoClock.getAsLong() - started >= maximum;
            }

            @Override
            public void throwIfCancellationRequested() {
              caller.throwIfCancellationRequested();
              if (nanoClock.getAsLong() - started >= maximum)
                throw new MetadataSourceException(
                    MetadataSourceException.Reason.DEADLINE,
                    "Metadata source operation deadline exceeded");
            }
          };
    }

    RuntimeException map(RuntimeException failure) {
      if (!(failure instanceof java.util.concurrent.CancellationException)) return failure;
      try {
        token.throwIfCancellationRequested();
      } catch (RuntimeException stopped) {
        return stopped;
      }
      return failure;
    }
  }

  private static final FullEncodingInventory.Consumer DISCARD_ENCODING =
      new FullEncodingInventory.Consumer() {
        public void header(IndexedEncodingParser.Layout layout) {}

        public void specification(int index, String value) {}

        public void content(int page, long ordinal, EncodingManifest.ContentEntry entry) {}

        public void encoding(int page, long ordinal, EncodingManifest.EncodedEntry entry) {}

        public void trailer(String value) {}
      };
  private static final DownloadInventoryReader.Consumer DISCARD_DOWNLOAD =
      new DownloadInventoryReader.Consumer() {
        public void header(DownloadInventoryReader.Header header) {}

        public void entry(long ordinal, DownloadInventoryReader.Entry entry) {}

        public void tag(int ordinal, DownloadInventoryReader.Tag tag) {}
      };
}
