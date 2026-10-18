package io.vibeminer.infrastructure.buildretention;

import static io.vibeminer.application.buildretention.BuildRetentionException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.AcquisitionException;
import io.vibeminer.infrastructure.encodedsource.*;
import io.vibeminer.infrastructure.inventory.SqliteBuildRetentionInventory;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

/** Exact sealed members share the selected-file actual publisher without inventing a file ID. */
public final class OfficialBuildRetentionSource implements BuildRetentionSource {
  private final SqliteBuildRetentionInventory inventory;
  private final SqliteBuildRetentionSourceAssociations associations;
  private final SqliteCatalog catalog;
  private final EncodedSourceCatalog files;
  private final EncodedSourceAcquisition sources;
  private final OfficialEncodedSourceLocator locator;
  private final OfficialEncodedSourcePublisher publisher;
  private final SelectedFileBudgets payload;
  private final ArchiveSearchBudgets archives;

  public OfficialBuildRetentionSource(
      SqliteBuildRetentionInventory inventory,
      SqliteBuildRetentionSourceAssociations associations,
      SqliteCatalog catalog,
      EncodedSourceCatalog files,
      EncodedSourceAcquisition sources,
      OfficialEncodedSourceLocator locator,
      OfficialEncodedSourcePublisher publisher,
      SelectedFileBudgets payload,
      ArchiveSearchBudgets archives) {
    this.inventory = Objects.requireNonNull(inventory);
    this.associations = Objects.requireNonNull(associations);
    this.catalog = Objects.requireNonNull(catalog);
    this.files = Objects.requireNonNull(files);
    this.sources = Objects.requireNonNull(sources);
    this.locator = Objects.requireNonNull(locator);
    this.publisher = Objects.requireNonNull(publisher);
    this.payload = Objects.requireNonNull(payload);
    this.archives = Objects.requireNonNull(archives);
  }

  @Override
  public String retain(
      BuildRetention.Target target,
      BuildRetention.Member member,
      PrioritySourceScheduler.Demand demand,
      CancellationToken cancellation) {
    CancellationToken token =
        () -> cancellation.isCancellationRequested() || demand.isCancellationRequested();
    token.throwIfCancellationRequested();
    requireReservedCost(member);
    try (var authority = inventory.openSource(target, member, token)) {
      if (!authority.reservationPolicy().payload().equals(payload)
          || !authority.reservationPolicy().archives().equals(archives))
        throw new BuildRetentionException(
            LIMIT_EXCEEDED, "Locator policy differs from sealed reservation");
      var metadata = associations.findMetadata(authority, target, member, token);
      if (metadata.isPresent()) return metadata.get();
      var build = authority.metadata().declaration().build();
      var slot = new ContentSlotKey("wow", member.contentKey());
      var limits =
          new EncodedSourcePlan.Limits(
              payload.lookup().maximumHeaderBytes(),
              payload.lookup().maximumChunks(),
              payload.maximumEncodedFileBytes(),
              payload.maximumPayloadRangeBytes(),
              payload.maximumPayloadRequests(),
              payload.maximumPayloadWireBytes(),
              payload.maximumPayloadDuration());
      ContentRepresentation retained = null;
      var current = files.lookup(slot, token);
      if (current.isPresent()
          && current.get().status() == CanonicalReservation.Status.RETAINED
          && current.get().actualEncodingKey().equals(member.encodingKey())) {
        var original = current.get().representation().orElseThrow();
        try {
          retained = publisher.inspect(original, limits, token);
        } catch (EncodedSourceException missing) {
          if (missing.code() != EncodedSourceException.Code.MISSING_LOCAL) throw missing;
        }
      }
      final boolean localOnly = retained != null;
      var location = localOnly ? retainedLocation(retained) : locate(build, member, token);
      var issued =
          new EncodedSourcePlan(
              UUID.randomUUID().toString(),
              build,
              slot,
              member.encodingKey(),
              member.encodedBytes(),
              location,
              new EncodedSourcePlan.Cost(member.encodedBytes(), member.encodedBytes()),
              limits);
      EncodedSourceGateway gateway =
          new EncodedSourceGateway() {
            public void validate(EncodedSourcePlan plan, CancellationToken token) {
              token.throwIfCancellationRequested();
              if (!issued.equals(plan))
                throw new EncodedSourceException(
                    EncodedSourceException.Code.INVALID_PLAN,
                    "Changed issued inventory source plan");
              try (var connection = catalog.connect()) {
                connection.setAutoCommit(false);
                authority.validateInTransaction(connection, token);
                connection.rollback();
              } catch (SQLException failure) {
                throw new BuildRetentionException(
                    STORAGE_UNAVAILABLE, "Cannot replay sealed source authority", failure);
              }
            }

            public ContentRepresentation acquire(
                EncodedSourcePlan plan, Observer observer, CancellationToken token) {
              validate(plan, token);
              if (localOnly)
                throw new EncodedSourceGateway.AcquisitionFailure(
                    EncodedSourceGateway.AcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                    new EncodedSourceException(
                        EncodedSourceException.Code.MISSING_LOCAL,
                        "Retained bytes changed; explicit locator retry required"));
              return publisher.acquire(plan, observer, token);
            }

            public ContentRepresentation inspectAvailable(
                EncodedSourcePlan plan, ContentRepresentation actual, CancellationToken token) {
              validate(plan, token);
              return publisher.inspect(actual, plan.limits(), token);
            }
          };
      ContentRepresentation actual;
      var consumer = sources.enter(slot, member.encodingKey());
      try (consumer) {
        try {
          actual = sources.acquire(consumer, issued, gateway, demand, token);
        } finally {
          consumer.close();
          awaitRetirement(consumer);
        }
      }
      token.throwIfCancellationRequested();
      return associations.attach(authority, target, member, actual, token);
    }
  }

  private EncodedSourcePlan.Location locate(
      io.vibeminer.domain.BuildSnapshot build,
      BuildRetention.Member member,
      CancellationToken token) {
    var policy =
        new OfficialEncodedSourceLocator.Policy(
            (int) payload.lookup().maximumConfigurationBytes(),
            payload.lookup().maximumIndexBytes(),
            payload.maximumEncodedFileBytes(),
            archives.maximumArchives(),
            archives.maximumArchiveBytes(),
            archives.maximumWireBytes(),
            archives.maximumRequests(),
            archives.maximumDuration());
    try {
      return locator.probeLoose(
          build, member.encodingKey(), OptionalLong.of(member.encodedBytes()), policy, token);
    } catch (RuntimeException failure) {
      Throwable cause = failure;
      while ((cause instanceof CompletionException || cause instanceof ExecutionException)
          && cause.getCause() != null) cause = cause.getCause();
      if (!(cause instanceof AcquisitionException acquisition)
          || acquisition.httpStatus().orElse(-1) != 404) throw failure;
    }
    var search =
        locator.searchArchives(
            build, member.encodingKey(), OptionalLong.of(member.encodedBytes()), policy, token);
    return search
        .located()
        .orElseThrow(
            () ->
                new EncodedSourceException(
                    search.complete()
                        ? EncodedSourceException.Code.UNAVAILABLE
                        : EncodedSourceException.Code.BUDGET_EXCEEDED,
                    "No located encoding within checked archive scope"));
  }

  private void requireReservedCost(BuildRetention.Member member) {
    long requests = 1 + (member.encodedBytes() - 1) / payload.maximumPayloadRangeBytes();
    if (member.encodedBytes() > payload.maximumEncodedFileBytes()
        || member.encodedBytes() > payload.maximumPayloadWireBytes()
        || requests > payload.maximumPayloadRequests()
        || member.cost().wireBytes()
            < Math.addExact(member.encodedBytes(), archives.maximumWireBytes())
        || member.cost().additionalBytes() < member.encodedBytes()
        || member.cost().requests()
            < Math.addExact(requests, Math.addExact(1, archives.maximumRequests())))
      throw new BuildRetentionException(
          LIMIT_EXCEEDED, "Member exceeds its reserved locator and payload policy");
  }

  private static EncodedSourcePlan.Location retainedLocation(ContentRepresentation original) {
    var actual = original.sourceReceipt();
    return new EncodedSourcePlan.Location(
        EncodedSourcePlan.LocationKind.valueOf(actual.kind().name()),
        actual.actualResourceUrl(),
        actual.resourceKey(),
        actual.parentWholeBytes(),
        actual.childOffset(),
        actual.childEncodedBytes(),
        actual.archiveMapping());
  }

  private static void awaitRetirement(EncodedSourceAcquisition.ConsumerLease consumer) {
    boolean interrupted = false;
    try {
      for (; ; )
        try {
          consumer.retirement().toCompletableFuture().get();
          return;
        } catch (InterruptedException waiting) {
          interrupted = true;
        } catch (ExecutionException impossible) {
          throw new IllegalStateException("Actual source retirement failed", impossible.getCause());
        }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
