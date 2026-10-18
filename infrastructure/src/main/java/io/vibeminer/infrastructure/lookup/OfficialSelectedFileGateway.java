package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.application.selectedfile.SelectedFileException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.application.lookup.FileLookupException;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisition.CapturedBuildSource;
import io.vibeminer.infrastructure.encodedsource.OfficialEncodedSourceLocator;
import io.vibeminer.infrastructure.encodedsource.OfficialEncodedSourcePublisher;
import io.vibeminer.protocol.tact.archive.*;
import io.vibeminer.protocol.tact.blte.*;
import io.vibeminer.protocol.tact.config.*;
import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/** Exact selected-file mechanics. Caller owns canonical slots, catalog, workers and transports. */
public final class OfficialSelectedFileGateway implements SelectedFileGateway, AutoCloseable {
  private record Session<T>(T value, long created) {}

  private final ObjectStore store;
  private final PinnedCdnTransport configurations;
  private final PinnedCdnRangeTransport ranges;
  private final CdnMetadataTransport metadata;
  private final Clock clock;
  private final LongSupplier nanoClock;
  private final OfficialEncodedSourcePublisher publisher;
  private final OfficialEncodedSourceLocator sourceLocator;
  private final OfficialFileLookupGateway roots;
  private final SelectedFileCandidates candidates;
  private final Map<String, Session<CandidatePlan>> plans = new LinkedHashMap<>();
  private final Map<String, Session<Loose404Permit>> permits = new LinkedHashMap<>();
  private final Map<String, Session<LocatedFilePlan>> located = new LinkedHashMap<>();
  private boolean closed;

  public OfficialSelectedFileGateway(
      ObjectStore store,
      PinnedCdnTransport configurations,
      PinnedCdnRangeTransport ranges,
      CdnMetadataTransport metadata,
      Clock clock) {
    this(store, configurations, ranges, metadata, clock, System::nanoTime);
  }

  OfficialSelectedFileGateway(
      ObjectStore store,
      PinnedCdnTransport configurations,
      PinnedCdnRangeTransport ranges,
      CdnMetadataTransport metadata,
      Clock clock,
      LongSupplier nanoClock) {
    this.nanoClock = Objects.requireNonNull(nanoClock);
    this.store = Objects.requireNonNull(store);
    this.configurations = Objects.requireNonNull(configurations);
    this.ranges = Objects.requireNonNull(ranges);
    this.metadata = Objects.requireNonNull(metadata);
    this.clock = Objects.requireNonNull(clock);
    this.publisher = new OfficialEncodedSourcePublisher(store, ranges, nanoClock);
    this.sourceLocator =
        new OfficialEncodedSourceLocator(configurations, ranges, metadata, nanoClock);
    roots = new OfficialFileLookupGateway(store, configurations, ranges, clock);
    candidates =
        new SelectedFileCandidates(
            store,
            roots,
            new LookupTransport() {
              public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
                return configurations.fetch(request);
              }

              public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
                return ranges.fetch(request);
              }
            });
  }

  @Override
  public CandidatePlan prepareCandidates(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      FileVariantAssociation variant,
      SelectedFileBudgets budgets,
      CancellationToken cancellation) {
    var stage = stage(budgets.maximumMetadataDuration(), cancellation);
    var token = stage.token();
    try {
      ensureOpen();
      var plan = candidates.resolve(build, configuration, root, variant, budgets, token);
      remember(plans, plan.sessionId(), plan);
      return plan;
    } catch (Exception failure) {
      throw classify(failure);
    }
  }

  @Override
  public void validateCandidatePlan(CandidatePlan plan, CancellationToken cancellation) {
    var token = stage(plan.budgets().maximumMetadataDuration(), cancellation).token();
    try {
      canonical(plans, plan.sessionId(), plan);
      token.throwIfCancellationRequested();
      candidates.replay(
          plan.variant().build(),
          plan.configuration(),
          plan.root(),
          plan.variant(),
          plan.budgets(),
          token);
    } catch (Exception failure) {
      throw classify(failure);
    }
  }

  private void validateChoice(CandidateChoice choice, CancellationToken cancellation) {
    validateCandidatePlan(choice.plan(), cancellation);
    if (!choice.plan().candidates().contains(choice.candidate()))
      throw fail(
          INVALID_PLAN, "Chosen representation is not in canonical authenticated EN candidates");
  }

  @Override
  public LooseProbe probeLoose(CandidateChoice choice, CancellationToken cancellation) {
    var plan = choice.plan();
    var work =
        new Work(
            plan.budgets().lookup().maximumMetadataWireBytes(),
            plan.budgets().lookup().maximumMetadataRequests(),
            plan.budgets().maximumMetadataDuration(),
            cancellation,
            nanoClock);
    validateChoice(choice, work.token());
    try {
      var locator =
          sourceLocator.probeLoose(
              plan.variant().build(),
              choice.candidate().encodingKey(),
              choice.candidate().encodedBytes(),
              locatorPolicy(
                  plan.budgets(),
                  ArchiveSearchBudgets.defaults(),
                  plan.budgets().lookup().maximumMetadataWireBytes(),
                  plan.budgets().lookup().maximumMetadataRequests(),
                  plan.budgets().maximumMetadataDuration()),
              locatorCancellation(work));
      return new LooseProbe(
          Optional.of(located(choice, selectedLocator(locator))), Optional.empty());
    } catch (Exception error) {
      var cause = unwrap(error);
      if (cause instanceof AcquisitionException acquisition
          && acquisition.httpStatus().orElse(-1) == 404) {
        var permit = new Loose404Permit(UUID.randomUUID().toString(), choice);
        remember(permits, permit.sessionId(), permit);
        return new LooseProbe(Optional.empty(), Optional.of(permit));
      }
      throw classify(error);
    }
  }

  @Override
  public void validateLoose404Permit(Loose404Permit permit, CancellationToken cancellation) {
    canonical(permits, permit.sessionId(), permit);
    validateChoice(permit.choice(), cancellation);
  }

  @Override
  public ArchiveSearchResult searchArchives(
      Loose404Permit permit, ArchiveSearchBudgets budgets, CancellationToken cancellation) {
    var work =
        new Work(
            budgets.maximumWireBytes(),
            budgets.maximumRequests(),
            budgets.maximumDuration(),
            cancellation,
            nanoClock);
    validateLoose404Permit(permit, work.token());
    var choice = permit.choice();
    try {
      var result =
          sourceLocator.searchArchives(
              choice.plan().variant().build(),
              choice.candidate().encodingKey(),
              choice.candidate().encodedBytes(),
              locatorPolicy(
                  choice.plan().budgets(),
                  budgets,
                  budgets.maximumWireBytes(),
                  budgets.maximumRequests(),
                  budgets.maximumDuration()),
              locatorCancellation(work));
      return new ArchiveSearchResult(
          result.located().map(value -> located(choice, selectedLocator(value))),
          result.checkedArchives(),
          result.advertisedArchives(),
          result.complete());
    } catch (Exception failure) {
      throw classify(failure);
    }
  }

  private static CancellationToken locatorCancellation(Work work) {
    return () -> {
      try {
        work.check();
        return false;
      } catch (SelectedFileException bounded) {
        if (bounded.code() == BUDGET_EXCEEDED)
          throw new EncodedSourceException(
              EncodedSourceException.Code.BUDGET_EXCEEDED, bounded.getMessage(), bounded);
        throw bounded;
      }
    };
  }

  private static OfficialEncodedSourceLocator.Policy locatorPolicy(
      SelectedFileBudgets selected,
      ArchiveSearchBudgets archives,
      long wire,
      int requests,
      Duration duration) {
    return new OfficialEncodedSourceLocator.Policy(
        (int) selected.lookup().maximumConfigurationBytes(),
        selected.lookup().maximumIndexBytes(),
        selected.maximumEncodedFileBytes(),
        archives.maximumArchives(),
        archives.maximumArchiveBytes(),
        wire,
        requests,
        duration);
  }

  private static FileLocator selectedLocator(EncodedSourcePlan.Location location) {
    return new FileLocator(
        FileLocator.Kind.valueOf(location.kind().name()),
        location.resourceUrl(),
        location.resourceKey(),
        location.resourceBytes(),
        location.childOffset(),
        location.childLength(),
        location.archiveProof());
  }

  private LocatedFilePlan located(CandidateChoice choice, FileLocator locator) {
    requireCandidateLength(choice, locator.childLength());
    var budget = choice.plan().budgets();
    long requests = 1 + (locator.childLength() - 1) / budget.maximumPayloadRangeBytes();
    if (locator.childLength() > budget.maximumPayloadWireBytes()
        || requests > budget.maximumPayloadRequests())
      throw fail(BUDGET_EXCEEDED, "Known payload exceeds explicit wire/request budget");
    var plan =
        new LocatedFilePlan(
            UUID.randomUUID().toString(),
            choice,
            locator,
            locator.childLength(),
            new AcquisitionCost(locator.childLength(), locator.childLength()));
    remember(located, plan.sessionId(), plan);
    return plan;
  }

  private static void requireCandidateLength(CandidateChoice choice, long length) {
    if (length > choice.plan().budgets().maximumEncodedFileBytes())
      throw fail(BUDGET_EXCEEDED, "Selected encoded file exceeds payload size budget");
    if (choice.candidate().encodedBytes().isPresent()
        && choice.candidate().encodedBytes().getAsLong() != length)
      throw fail(
          INTEGRITY_FAILURE,
          "Observed selected encoding size conflicts with authenticated EN size");
  }

  @Override
  public void validateLocatedPlan(LocatedFilePlan plan, CancellationToken cancellation) {
    canonical(located, plan.sessionId(), plan);
    validateChoice(plan.choice(), cancellation);
    requireCandidateLength(plan.choice(), plan.encodedBytes());
  }

  @Override
  public ContentRepresentation acquireEncoded(
      LocatedFilePlan plan, EncodedPublicationObserver observer, CancellationToken cancellation) {
    try {
      var token =
          stage(plan.choice().plan().budgets().maximumPayloadDuration(), cancellation).token();
      var authorized = authorizeEncodedSource(plan, token);
      return publisher.acquire(authorized.plan(), observer::verifiedBeforePromotion, token);
    } catch (EncodedSourceGateway.AcquisitionFailure failure) {
      throw new EncodedAcquisitionFailure(
          EncodedAcquisitionFailure.Publication.valueOf(failure.publication().name()),
          classify(failure.failure()));
    } catch (Exception refused) {
      throw new EncodedAcquisitionFailure(
          EncodedAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, classify(refused));
    }
  }

  @Override
  public ContentRepresentation inspectRetained(
      SelectedFileAssociation association,
      SelectedFileBudgets budgets,
      CancellationToken cancellation) {
    var token = stage(budgets.maximumPayloadDuration(), cancellation).token();
    try {
      ensureOpen();
      var variant = association.descriptor().association();
      var retained = association.representation();
      var config =
          candidates.replay(
              variant.build(),
              association.configuration(),
              association.root(),
              variant,
              budgets,
              token);
      var proof = association.proof();
      var expectedBootstrap =
          new ResolvedSourceIdentity(
              "tact.encoding.bootstrap",
              config.encodingBootstrap().encodingKey(),
              "blte-authenticated-selected-en-v1");
      if (!proof.verifierRevision().equals(SelectedFileCandidates.ASSOCIATION_REVISION)
          || !proof.encodingBootstrapSource().equals(expectedBootstrap)
          || proof.declaredDecodedBytes().isEmpty()
          || proof.authenticatedCandidates().isEmpty()
          || !proof
              .proofDigest()
              .equals(
                  SelectedFileCandidates.proofDigest(
                      variant,
                      association.configuration(),
                      association.root(),
                      expectedBootstrap,
                      proof.declaredDecodedBytes(),
                      proof.authenticatedCandidates())))
        throw fail(INTEGRITY_FAILURE, "Retained root/EN association attestation is inconsistent");
      var candidate =
          proof.authenticatedCandidates().stream()
              .filter(
                  value -> value.encodingKey().equals(association.descriptor().actualEncodingKey()))
              .findFirst()
              .orElseThrow(
                  () ->
                      fail(
                          INTEGRITY_FAILURE,
                          "Retained actual EKey lacks the original authenticated candidate"
                              + " declaration"));
      if (candidate.encodedBytes().isPresent()
          && candidate.encodedBytes().getAsLong() != retained.encoded().object().length())
        throw fail(
            INTEGRITY_FAILURE,
            "Retained actual encoded length conflicts with original EN declaration");
      publisher.inspect(retained, sourceLimits(budgets), token);
      return retained;
    } catch (Exception failure) {
      throw classify(failure);
    }
  }

  private static EncodedSourcePlan.Limits sourceLimits(SelectedFileBudgets budget) {
    return new EncodedSourcePlan.Limits(
        budget.lookup().maximumHeaderBytes(),
        budget.lookup().maximumChunks(),
        budget.maximumEncodedFileBytes(),
        budget.maximumPayloadRangeBytes(),
        budget.maximumPayloadRequests(),
        budget.maximumPayloadWireBytes(),
        budget.maximumPayloadDuration());
  }

  private CdnResource resource(CandidateChoice choice, String key) {
    return new CdnResource(
        CapturedBuildSource.resolve(choice.plan().variant().build()), key, CdnResource.Kind.DATA);
  }

  private synchronized <T> void remember(Map<String, Session<T>> map, String id, T value) {
    ensureOpen();
    while (map.size() >= 16) map.remove(map.keySet().iterator().next());
    map.put(id, new Session<>(value, nanoClock.getAsLong()));
  }

  private synchronized <T> void canonical(Map<String, Session<T>> map, String id, T value) {
    ensureOpen();
    var session = map.get(id);
    if (session == null
        || !session.value().equals(value)
        || nanoClock.getAsLong() - session.created() >= Duration.ofMinutes(10).toNanos())
      throw fail(
          INVALID_PLAN, "Plan is forged, changed, expired or evicted; explicitly plan again");
  }

  private synchronized void ensureOpen() {
    if (closed) throw fail(CLOSED, "Selected-file gateway is closed");
  }

  @Override
  public synchronized void close() {
    closed = true;
    plans.clear();
    permits.clear();
    located.clear();
    roots.close();
  }

  static SelectedFileException fail(SelectedFileException.Code code, String message) {
    return new SelectedFileException(code, message);
  }

  static SelectedFileException fail(
      SelectedFileException.Code code, String message, Throwable cause) {
    return new SelectedFileException(code, message, cause);
  }

  private static Throwable unwrap(Throwable error) {
    while ((error instanceof CompletionException
            || error instanceof ExecutionException
            || error instanceof IOException && !(error instanceof InterruptedIOException))
        && error.getCause() != null) error = error.getCause();
    return error;
  }

  private static SelectedFileException classify(Throwable original) {
    var error = unwrap(original);
    if (error instanceof SelectedFileException typed) return typed;
    if (error instanceof EncodedSourceException source)
      return fail(
          SelectedFileException.Code.valueOf(source.code().name()), source.getMessage(), source);
    if (error instanceof CancellationException || error instanceof InterruptedIOException)
      return fail(CANCELLED, "Selected-file operation cancelled", error);
    if (error instanceof AcquisitionException acquisition)
      return fail(
          switch (acquisition.reason()) {
            case UNAVAILABLE, TRANSPORT -> UNAVAILABLE;
            case LIMIT -> BUDGET_EXCEEDED;
            case CANCELLED -> CANCELLED;
            case INTEGRITY -> INTEGRITY_FAILURE;
          },
          acquisition.getMessage(),
          acquisition);
    if (error instanceof EncodedBlteException protocol)
      return fail(
          switch (protocol.reason()) {
            case LIMIT -> BUDGET_EXCEEDED;
            case UNSUPPORTED -> UNSUPPORTED;
            case CANCELLED -> CANCELLED;
            case CLOSED -> CLOSED;
            default -> INTEGRITY_FAILURE;
          },
          protocol.getMessage(),
          protocol);
    if (error instanceof ObjectStoreException storage
        && storage.code() == ObjectStoreException.Code.IO_FAILURE
        && storage.getCause() != null) {
      Throwable nested = unwrap(storage.getCause());
      if (nested instanceof EncodedBlteException
          || nested instanceof AcquisitionException
          || nested instanceof CancellationException
          || nested instanceof SelectedFileException) return classify(nested);
    }
    if (error instanceof ObjectStoreException storage)
      return fail(
          switch (storage.code()) {
            case MISSING_OBJECT -> MISSING_LOCAL;
            case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
            case LIMIT_EXCEEDED -> BUDGET_EXCEEDED;
            default -> STORAGE_UNAVAILABLE;
          },
          storage.getMessage(),
          storage);
    if (error instanceof FileLookupException root && root.getCause() != null) {
      var nested = unwrap(root.getCause());
      if (nested instanceof SelectedFileException || nested instanceof CancellationException)
        return classify(nested);
    }
    if (error instanceof FileLookupException root)
      return fail(
          switch (root.code()) {
            case BUDGET_EXCEEDED -> BUDGET_EXCEEDED;
            case CAPACITY_EXCEEDED -> CAPACITY_EXCEEDED;
            case MISSING_LOCAL -> MISSING_LOCAL;
            case UNSUPPORTED -> UNSUPPORTED;
            case INVALID_PLAN, INVALID_REQUEST -> INVALID_PLAN;
            case UNAVAILABLE -> UNAVAILABLE;
            case STORAGE_FAILURE -> STORAGE_UNAVAILABLE;
            case CLOSED -> CLOSED;
            case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
          },
          root.getMessage(),
          root);
    return fail(INTEGRITY_FAILURE, "Selected-file source/protocol operation failed", error);
  }

  private Work stage(Duration duration, CancellationToken cancellation) {
    return new Work(Long.MAX_VALUE, Integer.MAX_VALUE, duration, cancellation, nanoClock);
  }

  private static final class Work {
    final long maximumWire;
    final int maximumRequests;
    final long deadline;
    final long started;
    final LongSupplier nanos;
    final CancellationToken cancellation;
    long wire;
    int requests;

    Work(
        long wire,
        int requests,
        Duration duration,
        CancellationToken cancellation,
        LongSupplier nanos) {
      this.nanos = nanos;
      this.started = nanos.getAsLong();
      this.maximumWire = wire;
      this.maximumRequests = requests;
      this.deadline = duration.toNanos();
      this.cancellation = cancellation;
    }

    void check() {
      cancellation.throwIfCancellationRequested();
      if (nanos.getAsLong() - started >= deadline)
        throw fail(BUDGET_EXCEEDED, "Selected-file metadata operation deadline exceeded");
    }

    boolean cancelled() {
      check();
      return false;
    }

    CancellationToken token() {
      return this::cancelled;
    }

    void reserve(long bytes) {
      check();
      if (bytes < 0 || bytes > maximumWire - wire || requests >= maximumRequests)
        throw fail(BUDGET_EXCEEDED, "Next selected-file metadata request exceeds explicit scope");
      wire += bytes;
      requests++;
    }

    void settle(long reserved, long actual) {
      if (actual < 0 || actual > reserved)
        throw fail(INTEGRITY_FAILURE, "Metadata response exceeds reservation");
      wire -= reserved - actual;
      check();
    }

    <T> T await(CompletableFuture<T> future) {
      try {
        while (true) {
          check();
          try {
            T completed = future.get(25, TimeUnit.MILLISECONDS);
            check();
            return completed;
          } catch (TimeoutException waiting) {
          }
        }
      } catch (InterruptedException interrupted) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw new CancellationException();
      } catch (ExecutionException failure) {
        throw new CompletionException(failure.getCause());
      } catch (RuntimeException failure) {
        future.cancel(true);
        throw failure;
      }
    }
  }
}
