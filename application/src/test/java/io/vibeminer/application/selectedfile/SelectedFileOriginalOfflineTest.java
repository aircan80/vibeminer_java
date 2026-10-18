package io.vibeminer.application.selectedfile;

import static io.vibeminer.application.selectedfile.DefaultSelectedFileServiceTest.*;
import static io.vibeminer.application.selectedfile.SelectedFileException.Code.*;
import static io.vibeminer.application.selectedfile.SelectedFileFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.selectedfile.*;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SelectedFileOriginalOfflineTest {
  @Test
  void exactOriginalReadBypassesSelectionConflictWithoutChangingAnyIntent() throws Exception {
    var f = new SelectedFileFixture();
    var original = retained(f);
    var reads = new AtomicInteger();
    f.gateway.forbidSourceActions = true;
    try (var service = originalReader(f, reads, false)) {
      assertEquals(original, await(service.inspectOffline(original.descriptor(), BUDGETS)));
      assertEquals(1, reads.get());
      assertEquals(
          INTEGRITY_FAILURE,
          failure(service.inspectOffline(original.descriptor().association(), BUDGETS)));
      assertEquals(1, reads.get(), "Variant-only lookup must retain its canonical guard");
      assertEquals(1, f.gateway.acquisitions);
      assertEquals(1, f.pins.scopes.size());
      assertEquals(1, f.files.associations.size());
      assertEquals(1, f.files.slots.size());
      assertEquals(
          original.representation(),
          f.files.slots.values().iterator().next().representation().orElseThrow());
    }
  }

  @Test
  void changedDescriptorCannotBorrowOriginalAndWrongStoredRecordFailsIntegrity() throws Exception {
    var f = new SelectedFileFixture();
    var original = retained(f);
    var v = original.descriptor().association();
    var changed =
        new FileVariantAssociation(
            v.build(),
            v.rootSource(),
            43,
            v.contentKey(),
            v.localeFlags(),
            v.contentFlags(),
            v.rawFlagsFirst(),
            v.rawFlagsSecond(),
            v.rawFlagsThird(),
            v.nameHash());
    var descriptor =
        new SelectedFileDescriptor(changed, A, ContentRepresentation.VERIFIER_SEMANTICS);
    f.gateway.forbidSourceActions = true;
    try (var service = originalReader(f, new AtomicInteger(), false)) {
      assertEquals(MISSING_LOCAL, failure(service.inspectOffline(descriptor, BUDGETS)));
    }
    try (var service = originalReader(f, new AtomicInteger(), true)) {
      assertEquals(INTEGRITY_FAILURE, failure(service.inspectOffline(descriptor, BUDGETS)));
    }
    assertEquals(0, f.gateway.inspections);
  }

  @Test
  void missingExactDependencyStopsBeforePhysicalInterpretation() throws Exception {
    var f = new SelectedFileFixture();
    var original = retained(f);
    f.builds.refs.remove(original.snapshotId() + "ROOT_MANIFEST");
    f.gateway.forbidSourceActions = true;
    try (var service = originalReader(f, new AtomicInteger(), false)) {
      assertEquals(MISSING_LOCAL, failure(service.inspectOffline(original.descriptor(), BUDGETS)));
      assertEquals(0, f.gateway.inspections);
    }
  }

  @Test
  void originalReadStillRequiresLocalPhysicalIntegrityAndNeverDownloads() throws Exception {
    var f = new SelectedFileFixture();
    var original = retained(f);
    f.gateway.forbidSourceActions = true;
    try (var service = originalReader(f, new AtomicInteger(), false)) {
      f.gateway.missingPhysical = true;
      assertEquals(MISSING_LOCAL, failure(service.inspectOffline(original.descriptor(), BUDGETS)));
      f.gateway.missingPhysical = false;
      f.gateway.corruptPhysical = true;
      assertEquals(
          INTEGRITY_FAILURE, failure(service.inspectOffline(original.descriptor(), BUDGETS)));
      assertEquals(1, f.gateway.acquisitions);
      assertEquals(1, f.pins.scopes.size());
      assertEquals(1, f.files.associations.size());
    }
  }

  private static SelectedFileAssociation retained(SelectedFileFixture f) throws Exception {
    var variant = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var candidates = await(service.planCandidates(variant.build(), variant, BUDGETS));
      return await(service.retain(located(service, candidates, 0)));
    }
  }

  private static DefaultSelectedFileService originalReader(
      SelectedFileFixture f, AtomicInteger reads, boolean wrongRecord) {
    var catalog =
        (SelectedFileCatalog)
            Proxy.newProxyInstance(
                SelectedFileCatalog.class.getClassLoader(),
                new Class<?>[] {SelectedFileCatalog.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("lookup")) throw error(INTEGRITY_FAILURE);
                  if (!method.getName().equals("findOriginalAssociation"))
                    throw new AssertionError(
                        "Original inspection cannot select or mutate: " + method.getName());
                  reads.incrementAndGet();
                  var descriptor = (SelectedFileDescriptor) arguments[0];
                  return wrongRecord
                      ? Optional.of(f.files.associations.values().iterator().next())
                      : Optional.ofNullable(
                          f.files.associations.get(
                              descriptor.association().build().snapshotId() + descriptor.role()));
                });
    return new DefaultSelectedFileService(
        f.gateway, f.builds, catalog, f.pins, Runnable::run, Runnable::run);
  }
}
