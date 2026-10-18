package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.item.ItemProjectionException;
import org.junit.jupiter.api.Test;

class ItemSourceReadinessTest {
  @Test
  void evidenceFailureSurvivesNavigationAndLocaleModelResetUntilFreshVerification() {
    var health = new ItemSourceReadiness();
    var source = ItemPresentationFixture.handle().evidence().association();
    health.failed(source, ItemProjectionException.Code.CORRUPT);
    assertEquals(
        ItemProjectionException.Code.CORRUPT,
        health.failureFor(source.descriptor().association().build()).orElseThrow());
    health.observe(null);
    assertTrue(health.failureFor(source.descriptor().association().build()).isPresent());
    health.observe(source);
    assertTrue(health.failureFor(source.descriptor().association().build()).isPresent());
    health.verified(source);
    assertTrue(health.failureFor(source.descriptor().association().build()).isEmpty());
  }

  @Test
  void anotherBuildVerificationCannotClearOriginalContextFailure() {
    var health = new ItemSourceReadiness();
    var a = ItemPresentationFixture.association("12.1.5.70448", java.time.Instant.EPOCH);
    var b = ItemPresentationFixture.association("other", java.time.Instant.EPOCH);
    health.failed(a, ItemProjectionException.Code.MISSING_LOCAL);
    health.verified(b);
    assertEquals(
        ItemProjectionException.Code.MISSING_LOCAL,
        health.failureFor(a.descriptor().association().build()).orElseThrow());
    assertTrue(health.failureFor(b.descriptor().association().build()).isEmpty());
  }

  @Test
  void ordinaryQueryErrorsDoNotInventBadSourceEvidence() {
    var health = new ItemSourceReadiness();
    var a = ItemPresentationFixture.handle().evidence().association();
    health.failed(a, ItemProjectionException.Code.STALE_CURSOR);
    assertTrue(health.failureFor(a.descriptor().association().build()).isEmpty());
    health.failed(a, ItemProjectionException.Code.CORRUPT);
    health.failed(a, ItemProjectionException.Code.BUSY);
    assertEquals(
        ItemProjectionException.Code.CORRUPT,
        health.failureFor(a.descriptor().association().build()).orElseThrow());
  }

  @Test
  void scopedLanguageDoesNotClaimWhichDependencyOrPhysicalFileFailed() {
    assertEquals(
        "Item evidence missing",
        ItemSourceReadiness.message(ItemProjectionException.Code.MISSING_LOCAL));
    assertEquals(
        "Item evidence verification failed",
        ItemSourceReadiness.message(ItemProjectionException.Code.CORRUPT));
  }
}
