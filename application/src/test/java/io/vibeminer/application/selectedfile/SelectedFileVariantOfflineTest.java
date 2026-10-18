package io.vibeminer.application.selectedfile;

import static io.vibeminer.application.selectedfile.DefaultSelectedFileServiceTest.*;
import static io.vibeminer.application.selectedfile.SelectedFileException.Code.*;
import static io.vibeminer.application.selectedfile.SelectedFileFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SelectedFileVariantOfflineTest {
  @Test
  void freshServiceUsesOnlyVariantAndPersistedActualEncodingWithSourceForbidden() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    SelectedFileAssociation retained;
    try (var first = f.service(Runnable::run, Runnable::run)) {
      var plan = await(first.planCandidates(variant.build(), variant, BUDGETS));
      retained = await(first.retain(located(first, plan, 0)));
    }
    f.gateway.plans.clear();
    f.gateway.permits.clear();
    f.gateway.located.clear();
    f.gateway.forbidSourceActions = true;
    try (var restarted = f.service(Runnable::run, Runnable::run)) {
      var result = await(restarted.inspectOffline(variant, BUDGETS));
      assertEquals(retained, result);
      assertEquals(A, result.descriptor().actualEncodingKey());
      assertEquals(
          retained.representation().sourceReceipt(), result.representation().sourceReceipt());
      assertEquals(1, f.gateway.preparations);
      assertEquals(1, f.gateway.probes);
      assertEquals(0, f.gateway.searches);
      assertEquals(1, f.gateway.acquisitions);
      assertEquals(1, f.pins.scopes.size());
    }
  }

  @Test
  void canonicalPointerCannotInventAnotherBuildOrRawVariantAssociation() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    var other = f.add("two", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(variant.build(), variant, BUDGETS));
      await(service.retain(located(service, plan, 0)));
      f.gateway.forbidSourceActions = true;
      assertEquals(NOT_FOUND, failure(service.inspectOffline(other, BUDGETS)));
      var unnamed =
          new FileVariantAssociation(
              variant.build(),
              variant.rootSource(),
              variant.fileDataId(),
              variant.contentKey(),
              variant.localeFlags(),
              variant.contentFlags(),
              variant.rawFlagsFirst(),
              variant.rawFlagsSecond(),
              variant.rawFlagsThird(),
              Optional.empty());
      assertEquals(NOT_FOUND, failure(service.inspectOffline(unnamed, BUDGETS)));
      var wrongRoot =
          new FileVariantAssociation(
              variant.build(),
              new ResolvedSourceIdentity("test.root", "another", "root.v1"),
              variant.fileDataId(),
              variant.contentKey(),
              variant.localeFlags(),
              variant.contentFlags(),
              variant.rawFlagsFirst(),
              variant.rawFlagsSecond(),
              variant.rawFlagsThird(),
              variant.nameHash());
      assertEquals(NOT_FOUND, failure(service.inspectOffline(wrongRoot, BUDGETS)));
      assertEquals(1, f.files.associations.size());
    }
  }

  @Test
  void absentPendingPublishingAndRecoveryAreDistinctAndNeverInvokeSource() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    f.gateway.forbidSourceActions = true;
    try (var service = f.service(Runnable::run, Runnable::run)) {
      assertEquals(NOT_FOUND, failure(service.inspectOffline(variant, BUDGETS)));
      var pending = f.files.reserve(variant.slot(), A, 12, () -> false);
      assertEquals(BUSY, failure(service.inspectOffline(variant, BUDGETS)));
      f.files.status(pending, CanonicalReservation.Status.PUBLISHING, Optional.empty());
      assertEquals(BUSY, failure(service.inspectOffline(variant, BUDGETS)));
      f.files.status(pending, CanonicalReservation.Status.RECOVERY, Optional.empty());
      assertEquals(RECOVERY_REQUIRED, failure(service.inspectOffline(variant, BUDGETS)));
      assertEquals(0, f.gateway.inspections);
      assertTrue(f.pins.scopes.isEmpty());
    }
  }

  @Test
  void retainedPointerWithoutExactAssociationIsNotFoundAndWrongStoredDescriptorIsIntegrity()
      throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(variant.build(), variant, BUDGETS));
      var actual = await(service.retain(located(service, plan, 0)));
      f.gateway.forbidSourceActions = true;
      f.files.associations.clear();
      assertEquals(NOT_FOUND, failure(service.inspectOffline(variant, BUDGETS)));
      var altered =
          new FileVariantAssociation(
              variant.build(),
              variant.rootSource(),
              43,
              variant.contentKey(),
              variant.localeFlags(),
              variant.contentFlags(),
              variant.rawFlagsFirst(),
              variant.rawFlagsSecond(),
              variant.rawFlagsThird(),
              variant.nameHash());
      var wrong =
          new SelectedFileAssociation(
              new SelectedFileDescriptor(altered, A, ContentRepresentation.VERIFIER_SEMANTICS),
              actual.configuration(),
              actual.root(),
              actual.proof(),
              actual.representation());
      f.files.associations.put(actual.snapshotId() + actual.role(), wrong);
      assertEquals(INTEGRITY_FAILURE, failure(service.inspectOffline(variant, BUDGETS)));
    }
  }

  @Test
  void physicallyMissingOrCorruptBytesStayFailuresWithoutDownloadOrPin() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(variant.build(), variant, BUDGETS));
      await(service.retain(located(service, plan, 0)));
      f.gateway.forbidSourceActions = true;
      f.gateway.missingPhysical = true;
      assertEquals(MISSING_LOCAL, failure(service.inspectOffline(variant, BUDGETS)));
      f.gateway.missingPhysical = false;
      f.gateway.corruptPhysical = true;
      assertEquals(INTEGRITY_FAILURE, failure(service.inspectOffline(variant, BUDGETS)));
      assertEquals(1, f.gateway.acquisitions);
      assertEquals(1, f.pins.scopes.size());
    }
  }

  @Test
  void variantConvenienceUsesOneBoundedJobAndQueuedCancellationNeverInspects() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    try (var original = f.service(Runnable::run, Runnable::run)) {
      var plan = await(original.planCandidates(variant.build(), variant, BUDGETS));
      await(original.retain(located(original, plan, 0)));
    }
    f.gateway.forbidSourceActions = true;
    var queue = new LinkedBlockingQueue<Runnable>();
    try (var service = f.service(queue::add, Runnable::run)) {
      var operation = service.inspectOffline(variant, BUDGETS).toCompletableFuture();
      assertEquals(1, queue.size());
      queue.remove().run();
      assertEquals(A, await(operation).descriptor().actualEncodingKey());
      assertTrue(queue.isEmpty(), "No nested job/join on the same executor");
      int before = f.gateway.inspections;
      var pending = new ArrayList<CompletableFuture<SelectedFileAssociation>>();
      for (int i = 0; i < 4; i++)
        pending.add(service.inspectOffline(variant, BUDGETS).toCompletableFuture());
      assertEquals(CAPACITY_EXCEEDED, failure(service.inspectOffline(variant, BUDGETS)));
      pending.forEach(task -> task.cancel(true));
      while (!queue.isEmpty()) queue.remove().run();
      assertEquals(before, f.gateway.inspections);
      service.close();
      assertEquals(CLOSED, failure(service.inspectOffline(variant, BUDGETS)));
    }
  }
}
