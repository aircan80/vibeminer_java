package io.vibeminer.application.encodedsource;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EncodedSourcePlanTest {
  static final String KEY = "a".repeat(32);
  static final BuildSnapshot BUILD =
      new BuildSnapshot(
          "wow",
          "Fixture",
          "Published",
          "us",
          "1",
          "b".repeat(32),
          "c".repeat(32),
          Instant.EPOCH,
          true);

  static EncodedSourcePlan.Limits limits() {
    return new EncodedSourcePlan.Limits(4096, 64, 1024, 256, 8, 1024, Duration.ofSeconds(10));
  }

  static EncodedSourcePlan.Location location() {
    return new EncodedSourcePlan.Location(
        EncodedSourcePlan.LocationKind.LOOSE,
        "https://us.cdn.blizzard.com/tpr/wow/data/aa/aa/" + KEY,
        KEY,
        12,
        0,
        12,
        Optional.empty());
  }

  static EncodedSourcePlan plan() {
    return new EncodedSourcePlan(
        "issued-session",
        BUILD,
        new ContentSlotKey("wow", KEY),
        KEY,
        12,
        location(),
        new EncodedSourcePlan.Cost(12, 12),
        limits());
  }

  @Test
  void exactKnownPayloadHasNoFdidOrInventedDecodedClaim() {
    var plan = plan();
    assertEquals(12, plan.encodedBytes());
    assertEquals(plan.encodingKey(), plan.location().resourceKey());
    assertEquals(12, plan.cost().maximumPayloadWireBytes());
    assertEquals("wow", plan.slot().namespace());
  }

  @Test
  void geometryAndCostCannotContradictKnownObjectOrBudget() {
    var p = plan();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncodedSourcePlan(
                p.sessionId(), BUILD, p.slot(), KEY, 13, location(), p.cost(), limits()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncodedSourcePlan(
                p.sessionId(),
                BUILD,
                p.slot(),
                KEY,
                12,
                location(),
                new EncodedSourcePlan.Cost(11, 12),
                limits()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncodedSourcePlan.Location(
                EncodedSourcePlan.LocationKind.LOOSE, "url", KEY, 12, 1, 11, Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EncodedSourcePlan.Limits(4096, 64, 1024, 256, 8, 1024, Duration.ofMinutes(11)));
  }
}
