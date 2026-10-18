package io.vibeminer.application.buildopening;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DefaultBuildOpeningServiceTest {
  @Test
  void cachedAdmissionReturnsExactReferenceWithKnownZeroWireAndUnknownInventory() {
    var request = reference();
    var admission = admission(request);
    try (var service =
        new DefaultBuildOpeningService(
            (reference, locale, progress, token) -> {
              progress.update(complete(admission));
              return admission;
            },
            Runnable::run)) {
      var operation = service.open(request, "enUS");
      assertSame(admission, operation.completion().toCompletableFuture().join());
      assertEquals(OptionalLong.of(0), operation.progress().transferredWireBytes());
      assertEquals(OptionalLong.of(0), operation.progress().expectedWireBytes());
      assertEquals(BuildOpenProgress.Stage.COMPLETE, operation.progress().stage());
    }
  }

  @Test
  void commitWinsLateCancellationAndDerivedCancellationCannotEraseResult() {
    var queue = new Queue();
    var reference = reference();
    var admission = admission(reference);
    var operation = new AtomicReference<BuildOpeningService.OpenOperation>();
    try (var service =
        new DefaultBuildOpeningService(
            (r, l, p, t) -> {
              operation.get().requestCancellation();
              p.update(complete(admission));
              return admission;
            },
            queue)) {
      var open = service.open(reference, "enUS");
      operation.set(open);
      open.completion().toCompletableFuture().cancel(true);
      queue.run();
      assertSame(admission, open.completion().toCompletableFuture().join());
    }
  }

  @Test
  void fourQueuedOperationsHoldCapacityUntilTheirActualWorkersRetire() {
    var queue = new Queue();
    var calls = new AtomicInteger();
    var service =
        new DefaultBuildOpeningService(
            (r, l, p, t) -> {
              calls.incrementAndGet();
              throw new AssertionError();
            },
            queue);
    var reference = reference();
    var opens = new ArrayList<BuildOpeningService.OpenOperation>();
    for (int i = 0; i < 4; i++) opens.add(service.open(reference, "enUS"));
    opens.forEach(BuildOpeningService.OpenOperation::requestCancellation);
    assertCode(BuildOpeningException.Code.BUSY, service.open(reference, "enUS").completion());
    queue.run();
    assertFalse(service.open(reference, "enUS").completion().toCompletableFuture().isDone());
    service.close();
    while (!queue.tasks.isEmpty()) queue.run();
    assertEquals(0, calls.get());
    for (var open : opens)
      assertThrows(RuntimeException.class, () -> open.completion().toCompletableFuture().join());
  }

  @Test
  void requiredRoleBijectionAndExplicitDownloadLocatorApplicabilityCannotBeOmitted() {
    var admission = admission(reference());
    var withoutEn =
        admission.required().stream()
            .filter(r -> r.role() != BuildMetadataAdmission.Role.ENCODING)
            .toList();
    var withoutEnProof =
        admission.verified().stream()
            .filter(r -> r.requirement().role() != BuildMetadataAdmission.Role.ENCODING)
            .toList();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmission(
                admission.reference(),
                "enUS",
                admission.scopeRevision(),
                withoutEn,
                admission.download(),
                admission.size(),
                admission.locators(),
                withoutEnProof));
    var requiredDl =
        new BuildMetadataAdmission.DownloadDeclaration(
            BuildMetadataAdmission.DownloadApplicability.REQUIRED, "a".repeat(64));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmission(
                admission.reference(),
                "enUS",
                admission.scopeRevision(),
                admission.required(),
                requiredDl,
                admission.size(),
                admission.locators(),
                admission.verified()));
    var requiredSize =
        new BuildMetadataAdmission.SizeDeclaration(
            BuildMetadataAdmission.SizeApplicability.REQUIRED, "8".repeat(64));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmission(
                admission.reference(),
                "enUS",
                admission.scopeRevision(),
                admission.required(),
                admission.download(),
                requiredSize,
                admission.locators(),
                admission.verified()));
    var index =
        new BuildMetadataAdmission.Requirement(
            BuildMetadataAdmission.Role.LOCATOR_INDEX, "required-metadata-locator");
    var locators = new BuildMetadataAdmission.LocatorRequirements(List.of(index), "b".repeat(64));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmission(
                admission.reference(),
                "enUS",
                admission.scopeRevision(),
                admission.required(),
                admission.download(),
                admission.size(),
                locators,
                admission.verified()));
    var encoding =
        admission.required().stream()
            .filter(r -> r.role() == BuildMetadataAdmission.Role.ENCODING)
            .findFirst()
            .orElseThrow();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmission.VerifiedRequirement(
                encoding,
                admission.verified().getFirst().object(),
                new BuildMetadataAdmission.ProofIdentity(
                    BuildMetadataAdmission.Role.ROOT, "c".repeat(64))));
    var duplicate = new ArrayList<>(admission.verified());
    duplicate.set(1, duplicate.getFirst());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmission(
                admission.reference(),
                "enUS",
                admission.scopeRevision(),
                admission.required(),
                admission.download(),
                admission.size(),
                admission.locators(),
                duplicate));
  }

  @Test
  void publishedDownloadSizeAndRequiredLocatorEachNeedTheirOwnVerifiedRole() {
    var base = admission(reference());
    var required = new ArrayList<>(base.required());
    var verified = new ArrayList<>(base.verified());
    for (var role :
        List.of(
            BuildMetadataAdmission.Role.DOWNLOAD,
            BuildMetadataAdmission.Role.SIZE,
            BuildMetadataAdmission.Role.LOCATOR_INDEX)) {
      var requirement = new BuildMetadataAdmission.Requirement(role, "source-" + role);
      required.add(requirement);
      verified.add(
          new BuildMetadataAdmission.VerifiedRequirement(
              requirement,
              new StoredObject(new ObjectDigest("2".repeat(64)), 20),
              new BuildMetadataAdmission.ProofIdentity(role, "3".repeat(64))));
    }
    var locator =
        required.stream()
            .filter(r -> r.role() == BuildMetadataAdmission.Role.LOCATOR_INDEX)
            .toList();
    var complete =
        new BuildMetadataAdmission(
            base.reference(),
            base.locale(),
            base.scopeRevision(),
            required,
            new BuildMetadataAdmission.DownloadDeclaration(
                BuildMetadataAdmission.DownloadApplicability.REQUIRED, "4".repeat(64)),
            new BuildMetadataAdmission.SizeDeclaration(
                BuildMetadataAdmission.SizeApplicability.REQUIRED, "5".repeat(64)),
            new BuildMetadataAdmission.LocatorRequirements(locator, "6".repeat(64)),
            verified);
    try (var service =
        new DefaultBuildOpeningService(
            (r, l, p, t) -> {
              p.update(complete(complete));
              return complete;
            },
            Runnable::run)) {
      assertEquals(
          8,
          service
              .open(base.reference(), "enUS")
              .completion()
              .toCompletableFuture()
              .join()
              .verified()
              .size());
    }
    var missingSize =
        verified.stream()
            .filter(v -> v.requirement().role() != BuildMetadataAdmission.Role.SIZE)
            .toList();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmission(
                base.reference(),
                base.locale(),
                base.scopeRevision(),
                required,
                complete.download(),
                complete.size(),
                complete.locators(),
                missingSize));
  }

  @Test
  void exactEventReceiptAndLocaleCannotBeReplacedBySameSnapshotOrMissingFinalProgress() {
    var reference = reference();
    var different = admission(reference());
    assertEquals(reference.snapshotId(), different.reference().snapshotId());
    try (var service =
        new DefaultBuildOpeningService(
            (r, l, p, t) -> {
              p.update(complete(different));
              return different;
            },
            Runnable::run)) {
      assertCode(
          BuildOpeningException.Code.CORRUPT_METADATA,
          service.open(reference, "enUS").completion());
    }
    var correct = admission(reference);
    try (var service = new DefaultBuildOpeningService((r, l, p, t) -> correct, Runnable::run)) {
      assertCode(
          BuildOpeningException.Code.CORRUPT_METADATA,
          service.open(reference, "enUS").completion());
    }
    try (var service =
        new DefaultBuildOpeningService(
            (r, l, p, t) -> {
              p.update(complete(correct));
              return correct;
            },
            Runnable::run)) {
      assertCode(
          BuildOpeningException.Code.CORRUPT_METADATA,
          service.open(reference, "deDE").completion());
    }
  }

  @Test
  void progressRetainsTypedUnknownTotalsAndRejectsBackwardsMeasuredBytes() {
    var reference = reference();
    var queue = new Queue();
    var sink = new AtomicReference<BuildOpeningGateway.ProgressSink>();
    var service =
        new DefaultBuildOpeningService(
            (r, l, p, t) -> {
              sink.set(p);
              p.update(
                  new BuildOpenProgress(
                      BuildOpenProgress.Stage.ACQUIRING_METADATA,
                      OptionalLong.of(10),
                      OptionalLong.empty(),
                      1,
                      OptionalInt.empty()));
              p.update(
                  new BuildOpenProgress(
                      BuildOpenProgress.Stage.VERIFYING_METADATA,
                      OptionalLong.of(9),
                      OptionalLong.empty(),
                      1,
                      OptionalInt.empty()));
              throw new AssertionError();
            },
            queue);
    try {
      var operation = service.open(reference, "enUS");
      assertTrue(operation.progress().expectedWireBytes().isEmpty());
      queue.run();
      assertCode(BuildOpeningException.Code.CORRUPT_METADATA, operation.completion());
      assertEquals(OptionalLong.of(10), operation.progress().transferredWireBytes());
      sink.get().update(complete(admission(reference)));
      assertEquals(
          OptionalLong.of(10),
          operation.progress().transferredWireBytes(),
          "Retired sources cannot replace final observed progress");
    } finally {
      service.close();
    }
  }

  @Test
  void ignoredCancellationKeepsActualWorkerAndCloseCannotEraseCommittedAdmission()
      throws Exception {
    var reference = reference();
    var admission = admission(reference);
    var queue = new Queue();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var service =
        new DefaultBuildOpeningService(
            (r, l, p, t) -> {
              entered.countDown();
              await(release);
              p.update(complete(admission));
              return admission;
            },
            queue);
    var first = service.open(reference, "enUS");
    for (int i = 0; i < 3; i++) service.open(reference, "enUS");
    var worker = new Thread(queue::run);
    worker.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      first.requestCancellation();
      assertFalse(first.completion().toCompletableFuture().isDone());
      assertCode(BuildOpeningException.Code.BUSY, service.open(reference, "enUS").completion());
      service.close();
      assertFalse(first.completion().toCompletableFuture().isDone());
      release.countDown();
      worker.join(2000);
      assertFalse(worker.isAlive());
      assertSame(admission, first.completion().toCompletableFuture().join());
      while (!queue.tasks.isEmpty()) queue.run();
    } finally {
      release.countDown();
      worker.join(2000);
      service.close();
    }
  }

  @Test
  void completionCallbacksCanReenterFromAnotherThreadAfterActualRetirement() throws Exception {
    var queue = new Queue();
    var reference = reference();
    var admission = admission(reference);
    try (var service =
        new DefaultBuildOpeningService(
            (r, l, p, t) -> {
              p.update(complete(admission));
              return admission;
            },
            queue)) {
      var first = service.open(reference, "enUS");
      for (int i = 0; i < 3; i++) service.open(reference, "enUS");
      var callback = new CompletableFuture<Void>();
      first
          .completion()
          .whenComplete(
              (value, failure) -> {
                try {
                  var next =
                      CompletableFuture.supplyAsync(() -> service.open(reference, "enUS"))
                          .get(2, TimeUnit.SECONDS);
                  assertFalse(next.completion().toCompletableFuture().isDone());
                  callback.complete(null);
                } catch (Throwable blocked) {
                  callback.completeExceptionally(blocked);
                }
              });
      queue.run();
      callback.get(3, TimeUnit.SECONDS);
      assertEquals(4, queue.tasks.size());
      while (!queue.tasks.isEmpty()) queue.run();
    }
  }

  @Test
  void invalidRejectedClosedAndTypedMissingPrerequisiteDoNotBecomeReady() {
    var calls = new AtomicInteger();
    var reference = reference();
    var service =
        new DefaultBuildOpeningService(
            (r, l, p, t) -> {
              calls.incrementAndGet();
              throw new AssertionError();
            },
            job -> {
              throw new RejectedExecutionException();
            });
    assertCode(BuildOpeningException.Code.INVALID_REQUEST, service.open(null, "enUS").completion());
    assertCode(
        BuildOpeningException.Code.INVALID_REQUEST,
        service.open(reference, "invalid").completion());
    for (int i = 0; i < 6; i++)
      assertCode(BuildOpeningException.Code.BUSY, service.open(reference, "enUS").completion());
    service.close();
    assertCode(BuildOpeningException.Code.CLOSED, service.open(reference, "enUS").completion());
    assertEquals(0, calls.get());
    var missing =
        new BuildOpeningException(
            BuildOpeningException.Code.MISSING_METADATA, "Required DL is not retained");
    try (var actual =
        new DefaultBuildOpeningService(
            (r, l, p, t) -> {
              throw missing;
            },
            Runnable::run)) {
      var failure =
          assertThrows(
              CompletionException.class,
              () -> actual.open(reference, "enUS").completion().toCompletableFuture().join());
      assertSame(missing, failure.getCause());
    }
  }

  static void await(CountDownLatch release) {
    try {
      assertTrue(release.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException failed) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failed);
    }
  }

  static BuildLibraryReference reference() {
    var now = Instant.EPOCH;
    var versions =
        new SourceDocument(
            "https://us.version.battle.net/v2/products/wow/versions", now, new byte[] {1});
    var cdns =
        new SourceDocument(
            "https://us.version.battle.net/v2/products/wow/cdns", now, new byte[] {2});
    var values =
        Map.of(
            "Region",
            "us",
            "BuildConfig",
            "a".repeat(32),
            "CDNConfig",
            "b".repeat(32),
            "VersionsName",
            "authored");
    var build =
        new BuildSnapshot(
            "wow",
            "Unresolved",
            "Published",
            "us",
            "authored",
            "a".repeat(32),
            "b".repeat(32),
            now,
            false,
            new BuildEvidence("", "", List.of(versions, cdns)));
    var event =
        new DiscoveryEvent(
            UUID.randomUUID(),
            "wow",
            "us",
            now,
            "authored-v1",
            new DiscoveryEvent.ParsedSource(
                versions,
                List.of("Region", "BuildConfig", "CDNConfig", "VersionsName"),
                Optional.empty()),
            new DiscoveryEvent.ParsedSource(
                cdns, List.of("Name", "Path", "Hosts"), Optional.empty()),
            new DiscoveryEvent.CdnRow(
                0, Map.of("Name", "us", "Path", "tpr/wow", "Hosts", "authored.invalid")),
            List.of(new DiscoveryEvent.ObservedBuild(build, 0, values)));
    return BuildLibraryReference.from(event, 1).getFirst();
  }

  static BuildMetadataAdmission admission(BuildLibraryReference reference) {
    var required = new ArrayList<BuildMetadataAdmission.Requirement>();
    for (var role :
        List.of(
            BuildMetadataAdmission.Role.BUILD_CONFIG,
            BuildMetadataAdmission.Role.CDN_CONFIG,
            BuildMetadataAdmission.Role.ENCODING,
            BuildMetadataAdmission.Role.ROOT,
            BuildMetadataAdmission.Role.INSTALL))
      required.add(new BuildMetadataAdmission.Requirement(role, role.name()));
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

  @Test
  void unknownTransferDoesNotInventZeroOrForgetEarlierMeasuredLowerBound() {
    try (var service =
        new DefaultBuildOpeningService(
            (reference, locale, progress, token) -> {
              progress.update(
                  new BuildOpenProgress(
                      BuildOpenProgress.Stage.ACQUIRING_METADATA,
                      OptionalLong.of(10),
                      OptionalLong.empty(),
                      0,
                      OptionalInt.empty()));
              progress.update(
                  new BuildOpenProgress(
                      BuildOpenProgress.Stage.VERIFYING_METADATA,
                      OptionalLong.empty(),
                      OptionalLong.empty(),
                      0,
                      OptionalInt.empty()));
              progress.update(
                  new BuildOpenProgress(
                      BuildOpenProgress.Stage.VERIFYING_METADATA,
                      OptionalLong.of(9),
                      OptionalLong.empty(),
                      0,
                      OptionalInt.empty()));
              throw new AssertionError();
            },
            Runnable::run)) {
      var operation = service.open(reference(), "enUS");
      assertCode(BuildOpeningException.Code.CORRUPT_METADATA, operation.completion());
      assertTrue(operation.progress().transferredWireBytes().isEmpty());
    }
  }

  @Test
  void stageOnlyOpeningCanCompleteWithUnknownTransferredBytes() {
    var expected = admission(reference());
    try (var service =
        new DefaultBuildOpeningService(
            (reference, locale, progress, token) -> {
              progress.update(
                  new BuildOpenProgress(
                      BuildOpenProgress.Stage.ACQUIRING_METADATA,
                      OptionalLong.empty(),
                      OptionalLong.empty(),
                      0,
                      OptionalInt.empty()));
              progress.update(
                  new BuildOpenProgress(
                      BuildOpenProgress.Stage.COMPLETE,
                      OptionalLong.empty(),
                      OptionalLong.empty(),
                      expected.required().size(),
                      OptionalInt.of(expected.required().size())));
              return expected;
            },
            Runnable::run)) {
      var operation = service.open(expected.reference(), "enUS");
      assertSame(expected, operation.completion().toCompletableFuture().join());
      assertTrue(operation.progress().transferredWireBytes().isEmpty());
    }
  }

  static BuildOpenProgress complete(BuildMetadataAdmission admission) {
    return new BuildOpenProgress(
        BuildOpenProgress.Stage.COMPLETE,
        OptionalLong.of(0),
        OptionalLong.of(0),
        admission.required().size(),
        OptionalInt.of(admission.required().size()));
  }

  static final class Queue implements Executor {
    final java.util.Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    public void execute(Runnable job) {
      tasks.add(job);
    }

    void run() {
      Objects.requireNonNull(tasks.poll()).run();
    }
  }

  static void assertCode(BuildOpeningException.Code code, CompletionStage<?> result) {
    var failure =
        assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
    assertEquals(code, assertInstanceOf(BuildOpeningException.class, failure.getCause()).code());
  }
}
