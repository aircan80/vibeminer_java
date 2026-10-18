package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.*;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import org.junit.jupiter.api.Test;

/**
 * Action policy tests need no toolkit; native workflow exercises the actual controls separately.
 */
class MetadataRepairPaneTest {
  private MetadataRepairPresentationModel.State state(
      MetadataRepairPresentationModel.Stage stage,
      ContentClaimException.Code code,
      MetadataAcquisitionFailure.Publication publication) {
    return new MetadataRepairPresentationModel.State(
        MetadataRepairFixture.build("one"),
        "enUS",
        Kind.ROOT,
        stage,
        null,
        null,
        "Authored state",
        code,
        null,
        publication);
  }

  @Test
  void uncertainFailureRequiresLocalVerificationEvenWhenNestedCodeSuggestsRetry() {
    for (var stage : MetadataRepairPresentationModel.Stage.values())
      assertEquals(
          "verify",
          MetadataRepairPane.actionFor(
              state(
                  stage,
                  ContentClaimException.Code.MISSING_LOCAL,
                  MetadataAcquisitionFailure.Publication.UNCERTAIN)));
  }

  @Test
  void noRepairOrOpenActionIsOfferedWithoutTheCorrespondingExplicitStage() {
    for (var stage : MetadataRepairPresentationModel.Stage.values()) {
      String action = MetadataRepairPane.actionFor(state(stage, null, null));
      if (action.equals("download"))
        assertEquals(MetadataRepairPresentationModel.Stage.PLAN_READY, stage);
      if (action.equals("open"))
        assertTrue(
            stage == MetadataRepairPresentationModel.Stage.REPAIRED
                || stage == MetadataRepairPresentationModel.Stage.VERIFIED_LOCAL);
    }
    assertEquals(
        "status",
        MetadataRepairPane.actionFor(
            state(
                MetadataRepairPresentationModel.Stage.FAILED,
                ContentClaimException.Code.REPLAN_REQUIRED,
                null)));
    assertEquals(
        "check",
        MetadataRepairPane.actionFor(
            state(
                MetadataRepairPresentationModel.Stage.FAILED,
                null,
                MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED)));
  }

  @Test
  void adjacentConsentKeepsExactOriginalWireAndExpiryWithFullLimitsSeparately() {
    var plan = MetadataRepairFixture.plan(MetadataRepairFixture.build("one"), Kind.ROOT);
    String text = MetadataRepairPane.consentText(plan);
    assertTrue(text.contains(plan.originalProof().actualEncodingKey()));
    assertTrue(text.contains("Maximum download: 90 bytes"));
    assertEquals(3, text.lines().count());
    String limits = MetadataRepairPane.limitsText(plan);
    assertTrue(limits.contains("Additional physical storage ceiling: 90 bytes"));
    assertTrue(
        limits.contains(
            "Local decoding/verification work ceiling: "
                + plan.cost().maximumDecodedWorkBytes()
                + " bytes"));
    assertTrue(text.contains(plan.expiresAt().toString()));
  }

  @Test
  void staleDisplayedConsentCannotDownloadReplacementPlan() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m =
        new MetadataRepairPresentationModel(
            f,
            Kind.ROOT,
            Runnable::run,
            v -> {},
            v -> {},
            new MetadataRepairFixture.MutableClock())) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      var shown = MetadataRepairFixture.plan(b, Kind.ROOT);
      f.checking.complete(shown);
      f.checking = new java.util.concurrent.CompletableFuture<>();
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      var replacement = MetadataRepairFixture.plan(b, Kind.ROOT);
      f.checking.complete(replacement);
      assertNotSame(shown, replacement);
      MetadataRepairPane.downloadDisplayed(m, shown);
      assertEquals(0, f.executions);
      MetadataRepairPane.downloadDisplayed(m, replacement);
      assertEquals(1, f.executions);
    }
  }
}
