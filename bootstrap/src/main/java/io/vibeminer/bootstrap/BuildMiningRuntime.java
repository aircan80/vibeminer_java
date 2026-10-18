package io.vibeminer.bootstrap;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildoverview.*;
import io.vibeminer.application.buildreadiness.RecordingBuildOverviewService;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.content.CanonicalContentRepository;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.application.inventory.*;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.buildopening.*;
import io.vibeminer.infrastructure.buildoverview.SqliteBuildOverviewBackend;
import io.vibeminer.infrastructure.buildreadiness.SqliteBuildReadinessRepository;
import io.vibeminer.infrastructure.buildretention.*;
import io.vibeminer.infrastructure.encodedsource.*;
import io.vibeminer.infrastructure.encoding.SqliteEncodingLookupIndex;
import io.vibeminer.infrastructure.inventory.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.time.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Build services borrowing the content runtime's storage, source scheduler and existing workers.
 */
final class BuildMiningRuntime implements AutoCloseable {
  record Dependencies(
      java.nio.file.Path catalogPath,
      SqliteCatalog catalog,
      FileObjectStore store,
      CanonicalContentRepository canonical,
      BuildMetadataAdmissionRepository admissions,
      SqliteSelectedFileCatalog files,
      EncodedSourceAcquisition sources,
      PrioritySourceScheduler scheduler,
      PinnedCdnTransport configurations,
      PinnedCdnRangeTransport ranges,
      CdnMetadataTransport metadata,
      Executor sourceJobs,
      Executor localJobs,
      Executor controls,
      Clock clock) {}

  private final SqliteBuildRetentionInventory authority;
  private final SqliteBuildRetentionRepository ledger;
  private final BuildRetentionPump pump;
  private final BuildInventoryPreparationService preparation;
  private final BuildOverviewService overview;
  private final BuildOverviewService actualOverview;
  private final BuildRetentionModeService mode;
  private final Executor controls;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicBoolean startupScheduled = new AtomicBoolean();

  private BuildMiningRuntime(
      SqliteBuildRetentionInventory authority,
      SqliteBuildRetentionRepository ledger,
      BuildRetentionPump pump,
      BuildInventoryPreparationService preparation,
      BuildOverviewService overview,
      BuildOverviewService actualOverview,
      BuildRetentionModeService mode,
      Executor controls) {
    this.authority = authority;
    this.ledger = ledger;
    this.pump = pump;
    this.preparation = preparation;
    this.overview = overview;
    this.actualOverview = actualOverview;
    this.mode = mode;
    this.controls = controls;
  }

  static BuildMiningRuntime open(Dependencies d) {
    var metadataBounds = BuildOpeningPolicy.initialCohort().metadata();
    var encodingBounds =
        new SqliteEncodingLookupIndex.Limits(
            50_000_000, 100_000_000, 8L << 30, 512, 1_000_000, 256, 65536);
    var index =
        new SqliteEncodingLookupIndex(
            d.catalog(), d.store(), d.admissions(), d.store().contentSession().epoch());
    var policy = reservationPolicy();
    var payload = policy.payload();
    var archives = policy.archives();
    var authority =
        new SqliteBuildRetentionInventory(
            d.catalog(),
            d.store(),
            d.admissions(),
            index,
            metadataBounds,
            encodingBounds,
            policy,
            16);
    try {
      var ledger =
          new SqliteBuildRetentionRepository(
              d.catalog(), d.store(), d.store().contentSession(), authority);
      var publisher = new OfficialEncodedSourcePublisher(d.store(), d.ranges());
      var source =
          new OfficialBuildRetentionSource(
              authority,
              new SqliteBuildRetentionSourceAssociations(d.catalog(), d.store()),
              d.catalog(),
              d.files(),
              d.sources(),
              new OfficialEncodedSourceLocator(d.configurations(), d.ranges(), d.metadata()),
              publisher,
              payload,
              archives);
      var pump = new BuildRetentionPump(ledger, source, d.scheduler(), d.sourceJobs());
      var inventoryBounds = SqliteBuildInventory.Limits.initialCohort();
      var preparation =
          new DefaultBuildInventoryPreparationService(
              new RetainedBuildInventoryPreparationBackend(
                  d.admissions(),
                  new SqliteBuildInventory(
                      d.catalog(), d.store(), d.admissions(), index, d.canonical()),
                  authority,
                  metadataBounds,
                  encodingBounds,
                  inventoryBounds),
              d.localJobs());
      var encodedBounds =
          new EncodedSourcePlan.Limits(
              payload.lookup().maximumHeaderBytes(),
              payload.lookup().maximumChunks(),
              payload.maximumEncodedFileBytes(),
              payload.maximumPayloadRangeBytes(),
              payload.maximumPayloadRequests(),
              payload.maximumPayloadWireBytes(),
              payload.maximumPayloadDuration());
      var actualOverview =
          new DefaultBuildOverviewService(
              new SqliteBuildOverviewBackend(
                  d.catalog(),
                  d.store(),
                  d.admissions(),
                  authority,
                  publisher,
                  encodedBounds,
                  d.clock()),
              d.localJobs());
      var overview =
          new RecordingBuildOverviewService(
              actualOverview,
              new SqliteBuildReadinessRepository(d.catalogPath(), policy),
              d.localJobs(),
              SqliteBuildInventory.REVISION);
      var mode = new DefaultBuildRetentionModeService(ledger, pump, d.controls(), d.clock());
      return new BuildMiningRuntime(
          authority, ledger, pump, preparation, overview, actualOverview, mode, d.controls());
    } catch (RuntimeException | Error failure) {
      try {
        authority.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  static BuildOverview.Limits overviewLimits() {
    return new BuildOverview.Limits(
        50_000_000, 512L << 30, 128, 5_000_000_000L, Duration.ofHours(1));
  }

  /** Pure current policy, shared by source authority and historical chooser filtering. */
  static SqliteBuildRetentionInventory.ReservationPolicy reservationPolicy() {
    return new SqliteBuildRetentionInventory.ReservationPolicy(
        new SelectedFileBudgets(
            io.vibeminer.application.lookup.LookupBudgets.defaults(),
            2L << 30,
            1 << 20,
            4096,
            2L << 30,
            Duration.ofMinutes(10)),
        ArchiveSearchBudgets.defaults());
  }

  BuildInventoryPreparationService preparation() {
    return preparation;
  }

  BuildOverviewService overview() {
    return overview;
  }

  BuildRetentionModeService mode() {
    return mode;
  }

  /** Resume only previously authorized exact scopes; no discovery or latest-build expansion. */
  void resumeAuthorized() {
    if (!startupScheduled.compareAndSet(false, true)) return;
    controls.execute(
        () -> {
          CancellationToken token = cancelled::get;
          try {
            resumeAuthorized(
                (cursor, maximum) -> ledger.pageAuthorizedIntents(cursor, maximum, token),
                pump,
                token,
                System.err::println);
          } catch (java.util.concurrent.CancellationException closing) {
            // Owned cancellation, with no change to durable preferences.
          } catch (RuntimeException failure) {
            System.err.println("VibeMiner: saved download preferences could not be resumed");
          }
        });
  }

  /**
   * Bounded startup activation; extra saved preferences remain available when their build opens.
   */
  static void resumeAuthorized(
      java.util.function.BiFunction<String, Integer, java.util.List<BuildRetention.Intent>>
          authorizedPage,
      BuildRetentionActivation activation,
      CancellationToken token,
      java.util.function.Consumer<String> notice) {
    token.throwIfCancellationRequested();
    int maximum = BuildRetentionPump.MAXIMUM_HANDLES;
    var page = authorizedPage.apply("", maximum);
    for (var intent : page) {
      token.throwIfCancellationRequested();
      try {
        activation.resumeDownloadAll(intent);
      } catch (RuntimeException failure) {
        notice.accept("VibeMiner: retained build background activation needs retry");
      }
    }
    token.throwIfCancellationRequested();
    if (page.size() == maximum
        && !authorizedPage.apply(page.getLast().target().identity(), 1).isEmpty())
      notice.accept(
          "VibeMiner: additional saved Download all scopes await activation (startup limit "
              + maximum
              + "); preferences were kept. Open a build to retry.");
  }

  /** Withdraw observers and owned bulk demands before the borrowed executors begin draining. */
  void cancel() {
    if (!cancelled.compareAndSet(false, true)) return;
    preparation.close();
    overview.close();
    actualOverview.close();
    mode.close();
    pump.close();
  }

  /** Called after every borrowed worker has stopped, so no source loses its held authority. */
  @Override
  public void close() {
    cancel();
    authority.close();
  }
}
