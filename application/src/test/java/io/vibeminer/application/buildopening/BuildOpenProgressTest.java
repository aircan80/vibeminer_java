package io.vibeminer.application.buildopening;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class BuildOpenProgressTest {
  @Test
  void unmeasuredNetworkCounterIsUnknownRatherThanZero() {
    var progress =
        new BuildOpenProgress(
            BuildOpenProgress.Stage.ACQUIRING_METADATA,
            OptionalLong.empty(),
            OptionalLong.empty(),
            2,
            OptionalInt.of(7));
    assertTrue(progress.transferredWireBytes().isEmpty());
    assertEquals(OptionalLong.of(0), BuildOpenProgress.initial().transferredWireBytes());
  }

  @Test
  void expectedTotalRequiresMeasuredCompatibleTransferredCounter() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildOpenProgress(
                BuildOpenProgress.Stage.ACQUIRING_METADATA,
                OptionalLong.empty(),
                OptionalLong.of(10),
                0,
                OptionalInt.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildOpenProgress(
                BuildOpenProgress.Stage.ACQUIRING_METADATA,
                OptionalLong.of(11),
                OptionalLong.of(10),
                0,
                OptionalInt.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildOpenProgress(
                BuildOpenProgress.Stage.ACQUIRING_METADATA,
                OptionalLong.of(-1),
                OptionalLong.empty(),
                0,
                OptionalInt.empty()));
    assertEquals(
        OptionalLong.of(5),
        new BuildOpenProgress(
                BuildOpenProgress.Stage.VERIFYING_METADATA,
                OptionalLong.of(5),
                OptionalLong.of(10),
                0,
                OptionalInt.empty())
            .transferredWireBytes());
  }

  @Test
  void completeRequirementsDoNotInventWireMeasurements() {
    var progress =
        new BuildOpenProgress(
            BuildOpenProgress.Stage.COMPLETE,
            OptionalLong.empty(),
            OptionalLong.empty(),
            7,
            OptionalInt.of(7));
    assertTrue(progress.transferredWireBytes().isEmpty());
    assertEquals(7, progress.verifiedRequirements());
  }
}
