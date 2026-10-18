package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.discoveryhistory.DiscoveryEvent;
import io.vibeminer.application.discoveryhistory.DiscoveryEventSummary.DocumentReceipt;
import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BuildOpenPresentationModelTest {
  static final class Operation implements BuildOpeningService.OpenOperation {
    final CompletableFuture<BuildMetadataAdmission> done = new CompletableFuture<>();
    BuildOpenProgress progress = BuildOpenProgress.initial();
    int canceled;

    public CompletionStage<BuildMetadataAdmission> completion() {
      return done;
    }

    public BuildOpenProgress progress() {
      return progress;
    }

    public void requestCancellation() {
      canceled++;
    }
  }

  static final class Service implements BuildOpeningService {
    final List<Operation> operations = new ArrayList<>();
    BuildLibraryReference reference;
    String locale;
    int closed;

    public OpenOperation open(BuildLibraryReference reference, String locale) {
      this.reference = reference;
      this.locale = locale;
      var operation = new Operation();
      operations.add(operation);
      return operation;
    }

    public void close() {
      closed++;
    }
  }

  @Test
  void selectionDoesNotOpenAndExplicitActionUsesExactReceiptAndLocale() {
    var service = new Service();
    var model = new BuildOpenPresentationModel(service, Runnable::run, s -> {});
    var reference = reference("a");
    model.select(reference, "enUS");
    assertTrue(service.operations.isEmpty());
    model.open();
    model.open();
    assertEquals(1, service.operations.size());
    assertSame(reference, service.reference);
    assertEquals("enUS", service.locale);
    assertTrue(model.state().busy());
    service.operations.getFirst().done.complete(admission(reference));
    assertFalse(model.state().busy());
    assertNotNull(model.state().admission());
  }

  @Test
  void canceledAndChangedContextFenceEvenCommittedLateSuccess() {
    var service = new Service();
    var model = new BuildOpenPresentationModel(service, Runnable::run, s -> {});
    var first = reference("a");
    model.select(first, "enUS");
    model.open();
    var old = service.operations.getFirst();
    model.select(reference("b"), "enUS");
    assertEquals(1, old.canceled);
    old.done.complete(admission(first));
    assertNull(model.state().admission());
    assertEquals("b".repeat(32), model.state().reference().key().buildConfig());
    model.open();
    model.cancel();
    service.operations.getLast().done.complete(admission(model.state().reference()));
    assertNull(model.state().admission());
    assertFalse(model.state().busy());
  }

  @Test
  void failureAllowsRetryButWrongReceiptNeverEntersBuildHome() {
    var service = new Service();
    var model = new BuildOpenPresentationModel(service, Runnable::run, s -> {});
    model.select(reference("a"), "enUS");
    model.open();
    service
        .operations
        .getFirst()
        .done
        .completeExceptionally(new IllegalStateException("Unavailable"));
    assertFalse(model.state().error().isBlank());
    model.open();
    service.operations.getLast().done.complete(admission(reference("b")));
    assertNull(model.state().admission());
    assertFalse(model.state().error().isBlank());
  }

  @Test
  void unknownWireAndKnownRequirementCountsStayDistinctAndPollingDoesNotOpen() {
    var service = new Service();
    var model = new BuildOpenPresentationModel(service, Runnable::run, s -> {});
    model.select(reference("a"), "enUS");
    model.open();
    service.operations.getFirst().progress =
        new BuildOpenProgress(
            BuildOpenProgress.Stage.ACQUIRING_METADATA,
            OptionalLong.empty(),
            OptionalLong.empty(),
            0,
            OptionalInt.of(7));
    model.pollProgress();
    assertTrue(model.state().progress().transferredWireBytes().isEmpty());
    assertEquals(7, model.state().progress().requiredRequirements().getAsInt());
    assertEquals(1, service.operations.size());
  }

  @Test
  void queuedCompletionAfterBackOrCloseDoesNotPublishAndServiceIsBorrowed() {
    var service = new Service();
    Queue<Runnable> ui = new ArrayDeque<>();
    var model = new BuildOpenPresentationModel(service, ui::add, s -> {});
    var ref = reference("a");
    model.select(ref, "enUS");
    model.open();
    service.operations.getFirst().done.complete(admission(ref));
    model.clear();
    ui.remove().run();
    assertNull(model.state().admission());
    assertNull(model.state().reference());
    model.select(ref, "enUS");
    model.open();
    model.close();
    service.operations.getLast().done.complete(admission(ref));
    ui.remove().run();
    assertNull(model.state().admission());
    assertEquals(0, service.closed);
  }

  @Test
  void settingsDefersHomeWithoutCancelingAndReturnRevealsSameAdmission() {
    var service = new Service();
    var model = new BuildOpenPresentationModel(service, Runnable::run, s -> {});
    var reference = reference("a");
    model.select(reference, "enUS");
    model.open();
    model.setDiscoveryActive(false);
    service.operations.getFirst().done.complete(admission(reference));
    assertNotNull(model.state().admission());
    assertFalse(model.overviewRequested());
    assertEquals(0, service.operations.getFirst().canceled);
    model.setDiscoveryActive(true);
    assertTrue(model.overviewRequested());
    assertEquals(1, service.operations.size());
  }

  @Test
  void sameExactContextPreservesOperationButLocaleChangeRetiresIt() {
    var service = new Service();
    var model = new BuildOpenPresentationModel(service, Runnable::run, s -> {});
    var reference = reference("a");
    model.select(reference, "enUS");
    model.open();
    model.select(reference, "enUS");
    assertTrue(model.state().busy());
    assertEquals(0, service.operations.getFirst().canceled);
    model.select(reference, "deDE");
    assertEquals(1, service.operations.getFirst().canceled);
    service.operations.getFirst().done.complete(admission(reference));
    assertNull(model.state().admission());
    assertEquals("deDE", model.state().locale());
  }

  @Test
  void observerContextChangeDuringBusyNotificationCannotResurrectOldCompletion() {
    var service = new Service();
    var holder = new BuildOpenPresentationModel[1];
    var replacement = reference("b");
    holder[0] =
        new BuildOpenPresentationModel(
            service,
            Runnable::run,
            state -> {
              if (state.busy()) holder[0].select(replacement, "enUS");
            });
    var first = reference("a");
    holder[0].select(first, "enUS");
    holder[0].open();
    service.operations.getFirst().done.complete(admission(first));
    assertEquals(1, service.operations.getFirst().canceled);
    assertSame(replacement, holder[0].state().reference());
    assertNull(holder[0].state().admission());
    assertFalse(holder[0].overviewRequested());
  }

  @Test
  void synchronousServiceRejectionLeavesSelectionAvailableForRetry() {
    var service =
        new BuildOpeningService() {
          public OpenOperation open(BuildLibraryReference reference, String locale) {
            throw new IllegalStateException("Not ready");
          }

          public void close() {
            fail("Borrowed service must not be closed");
          }
        };
    var model = new BuildOpenPresentationModel(service, Runnable::run, s -> {});
    var reference = reference("a");
    model.select(reference, "enUS");
    model.open();
    assertSame(reference, model.state().reference());
    assertFalse(model.state().busy());
    assertNull(model.state().admission());
    assertFalse(model.state().error().isBlank());
    model.close();
  }

  @Test
  void admittedOverviewUsesActualIdentityAndUnknownInventoryWithoutBulkClaims() {
    var reference = reference("a");
    var admission = admission(reference);
    var view = AtlasApplication.admittedOverview(admission, Instant.parse("2026-10-18T12:00:00Z"));
    assertEquals(reference.key().productId(), view.identity().productId());
    assertEquals(reference.key().versionLabel(), view.identity().exactBuild());
    assertEquals(reference.key().channel(), view.identity().channel());
    assertEquals(reference.region(), view.identity().region());
    assertEquals(admission.locale(), view.identity().locale());
    assertEquals(admission.scopeRevision(), view.scope().revision());
    assertTrue(view.scope().description().contains("All declared languages and platforms"));
    assertEquals(BuildOverviewPane.Readiness.PARTIAL, view.readiness());
    assertEquals(BuildOverviewPane.MetadataState.READY, view.metadata());
    assertEquals(BuildOverviewPane.DownloadMode.ON_DEMAND, view.mode());
    assertFalse(view.metrics().denominatorComplete());
    assertTrue(view.metrics().plannedEncodedBytes().isEmpty());
    assertTrue(view.metrics().retainedScopeEncodedBytes().isEmpty());
    assertTrue(view.metrics().distinctFileIds().isEmpty());
    assertTrue(view.metrics().variantMemberships().isEmpty());
    assertTrue(view.metrics().localPhysicalBytes().isEmpty());
    assertTrue(view.metrics().sharedPhysicalBytes().isEmpty());
    assertTrue(view.metrics().metadataDerivedBytes().isEmpty());
    assertTrue(view.jobSummary().isBlank());
    assertFalse(view.capabilities().modeAvailable());
    assertFalse(view.capabilities().busy());
  }

  @Test
  void serviceReentrancyCannotResurrectAnOperationReturnedAfterContextRetirement() {
    var replacement = reference("b");
    var operation = new Operation();
    var holder = new BuildOpenPresentationModel[1];
    var service =
        new BuildOpeningService() {
          public OpenOperation open(BuildLibraryReference reference, String locale) {
            holder[0].select(replacement, locale);
            return operation;
          }

          public void close() {}
        };
    holder[0] = new BuildOpenPresentationModel(service, Runnable::run, s -> {});
    holder[0].select(reference("a"), "enUS");
    holder[0].open();
    assertSame(replacement, holder[0].state().reference());
    assertFalse(holder[0].state().busy());
    assertEquals(1, operation.canceled);
  }

  @Test
  void cancellationAdapterFailureCannotPreventLocalRetirementOrClose() {
    var holder =
        new BuildOpeningService.OpenOperation() {
          public CompletionStage<BuildMetadataAdmission> completion() {
            return new CompletableFuture<>();
          }

          public BuildOpenProgress progress() {
            return BuildOpenProgress.initial();
          }

          public void requestCancellation() {
            throw new IllegalStateException("Retirement adapter failed");
          }
        };
    var service =
        new BuildOpeningService() {
          public OpenOperation open(BuildLibraryReference reference, String locale) {
            return holder;
          }

          public void close() {}
        };
    var model = new BuildOpenPresentationModel(service, Runnable::run, s -> {});
    model.select(reference("a"), "enUS");
    model.open();
    assertDoesNotThrow(model::clear);
    assertNull(model.state().reference());
    assertFalse(model.state().busy());
    model.select(reference("a"), "enUS");
    model.open();
    assertDoesNotThrow(model::close);
    assertFalse(model.overviewRequested());
  }

  @Test
  void retiredPaneSourceAndBackCannotActOnReplacementAdmissionOrRerenderedPane() {
    var service = new Service();
    var model = new BuildOpenPresentationModel(service, Runnable::run, s -> {});
    var first = reference("a");
    model.select(first, "enUS");
    model.open();
    var firstAdmission = admission(first);
    service.operations.getFirst().done.complete(firstAdmission);
    var events = new ArrayList<String>();
    var oldPane =
        model.bindOverviewActions(
            firstAdmission, () -> events.add("old source"), () -> events.add("old back"));
    var rerendered =
        model.bindOverviewActions(
            firstAdmission, () -> events.add("source A"), () -> events.add("back A"));
    oldPane.source().run();
    oldPane.back().run();
    assertTrue(events.isEmpty());
    rerendered.source().run();
    assertEquals(List.of("source A"), events);
    events.clear();
    var replacement = reference("b");
    model.select(replacement, "enUS");
    model.open();
    var nextAdmission = admission(replacement);
    service.operations.getLast().done.complete(nextAdmission);
    var current =
        model.bindOverviewActions(
            nextAdmission, () -> events.add("source B"), () -> events.add("back B"));
    rerendered.source().run();
    rerendered.back().run();
    assertTrue(events.isEmpty());
    current.source().run();
    current.back().run();
    assertEquals(List.of("source B", "back B"), events);
    model.clear();
    current.source().run();
    current.back().run();
    assertEquals(List.of("source B", "back B"), events);
  }

  @Test
  void closeRejectsWorkReenteredFromCancellationAdapter() {
    var model = new BuildOpenPresentationModel[1];
    var calls = new int[1];
    var service =
        new BuildOpeningService() {
          public OpenOperation open(BuildLibraryReference reference, String locale) {
            calls[0]++;
            return new OpenOperation() {
              public CompletionStage<BuildMetadataAdmission> completion() {
                return new CompletableFuture<>();
              }

              public BuildOpenProgress progress() {
                return BuildOpenProgress.initial();
              }

              public void requestCancellation() {
                model[0].clear();
                model[0].select(reference, locale);
                model[0].open();
              }
            };
          }

          public void close() {
            fail("Borrowed service");
          }
        };
    model[0] = new BuildOpenPresentationModel(service, Runnable::run, state -> {});
    model[0].select(reference("a"), "enUS");
    model[0].open();
    model[0].close();
    assertEquals(1, calls[0]);
    assertFalse(model[0].overviewRequested());
  }

  static BuildLibraryReference reference(String config) {
    var now = Instant.parse("2026-10-18T12:00:00Z");
    var key =
        new BuildReferenceGroupKey(
            "wow",
            "Published",
            "12.1.5.70448",
            config.repeat(32),
            "c".repeat(32),
            "70448",
            "",
            Map.of());
    String base = "https://us.version.battle.net/v2/products/wow";
    return new BuildLibraryReference(
        key,
        UUID.randomUUID(),
        1,
        key.snapshotIdFor("us"),
        0,
        "us",
        now,
        new DocumentReceipt(base + "/versions", now, "c".repeat(64), 32),
        new DocumentReceipt(base + "/cdns", now, "d".repeat(64), 32),
        new DiscoveryEvent.CdnRow(
            0, Map.of("Name", "us", "Path", "tpr/wow", "Hosts", "cdn.blizzard.com")));
  }

  static BuildMetadataAdmission admission(BuildLibraryReference reference) {
    var required =
        List.of(
                BuildMetadataAdmission.Role.BUILD_CONFIG,
                BuildMetadataAdmission.Role.CDN_CONFIG,
                BuildMetadataAdmission.Role.ENCODING,
                BuildMetadataAdmission.Role.ROOT,
                BuildMetadataAdmission.Role.INSTALL)
            .stream()
            .map(r -> new BuildMetadataAdmission.Requirement(r, r.name()))
            .toList();
    var verified =
        required.stream()
            .map(
                r ->
                    new BuildMetadataAdmission.VerifiedRequirement(
                        r,
                        new StoredObject(new ObjectDigest("c".repeat(64)), 10),
                        new BuildMetadataAdmission.ProofIdentity(r.role(), "d".repeat(64))))
            .toList();
    return new BuildMetadataAdmission(
        reference,
        "enUS",
        "authored-all-variants-v1",
        required,
        new BuildMetadataAdmission.DownloadDeclaration(
            BuildMetadataAdmission.DownloadApplicability.AUTHENTICATED_NOT_PUBLISHED,
            "e".repeat(64)),
        new BuildMetadataAdmission.SizeDeclaration(
            BuildMetadataAdmission.SizeApplicability.AUTHENTICATED_NOT_PUBLISHED, "9".repeat(64)),
        new BuildMetadataAdmission.LocatorRequirements(List.of(), "f".repeat(64)),
        verified);
  }
}
