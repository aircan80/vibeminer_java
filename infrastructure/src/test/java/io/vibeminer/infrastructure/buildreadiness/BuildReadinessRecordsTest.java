package io.vibeminer.infrastructure.buildreadiness;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildoverview.BuildOverview;
import io.vibeminer.application.buildreadiness.BuildReadiness;
import io.vibeminer.application.buildretention.BuildRetention;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuildReadinessRecordsTest {
  static final String CONTEXT = "f".repeat(64), ADMISSION = "a".repeat(64);

  static BuildReadiness.Observation observation(BuildOverview.Readiness readiness, boolean target) {
    var origin =
        new BuildReadiness.Origin(
            UUID.fromString("7a351607-b536-4ec2-b949-500f98f17e3d"),
            17,
            1,
            "b".repeat(64),
            "c".repeat(64),
            "us");
    return new BuildReadiness.Observation(
        readiness,
        Instant.parse("2026-10-18T12:00:00.123456789Z"),
        "d".repeat(64),
        origin,
        target
            ? Optional.of(
                new BuildRetention.Target(
                    origin.groupId(),
                    origin.snapshotId(),
                    ADMISSION,
                    "e".repeat(64),
                    3,
                    "inventory-😀-v1",
                    "1".repeat(64)))
            : Optional.empty());
  }

  @Test
  void allDatedStatusesPreserveOriginalOriginNanosAndTarget() {
    for (var readiness : BuildOverview.Readiness.values()) {
      var value = observation(readiness, readiness == BuildOverview.Readiness.FULL);
      var bytes = BuildReadinessRecords.encode(CONTEXT, Optional.of(ADMISSION), value);
      var restored = BuildReadinessRecords.decode(CONTEXT, bytes);
      assertEquals(value, restored.observation());
      assertEquals(Optional.of(ADMISSION), restored.admission());
      assertArrayEquals(
          bytes,
          BuildReadinessRecords.encode(CONTEXT, restored.admission(), restored.observation()));
    }
  }

  @Test
  void truncationTrailingWrongContextAndOversizeAreNeverAccepted() {
    var bytes =
        BuildReadinessRecords.encode(
            CONTEXT, Optional.empty(), observation(BuildOverview.Readiness.PARTIAL, true));
    for (int n = 0; n < bytes.length; n++) {
      var prefix = Arrays.copyOf(bytes, n);
      assertThrows(
          IllegalArgumentException.class, () -> BuildReadinessRecords.decode(CONTEXT, prefix));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> BuildReadinessRecords.decode(CONTEXT, Arrays.copyOf(bytes, bytes.length + 1)));
    assertThrows(
        IllegalArgumentException.class, () -> BuildReadinessRecords.decode("0".repeat(64), bytes));
    assertThrows(
        IllegalArgumentException.class,
        () -> BuildReadinessRecords.decode(CONTEXT, new byte[BuildReadinessRecords.MAX_BYTES + 1]));
  }

  @Test
  void booleanTargetMarkerIsStrictAndMalformedUtf8CannotNormalize() {
    var value = observation(BuildOverview.Readiness.PARTIAL, true);
    var bytes = BuildReadinessRecords.encode(CONTEXT, Optional.of(ADMISSION), value);
    int marker =
        BuildReadinessRecords.encode(
                    CONTEXT,
                    Optional.of(ADMISSION),
                    observation(BuildOverview.Readiness.PARTIAL, false))
                .length
            - 1;
    assertEquals(1, bytes[marker]);
    var badMarker = bytes.clone();
    badMarker[marker] = 2;
    assertThrows(
        IllegalArgumentException.class, () -> BuildReadinessRecords.decode(CONTEXT, badMarker));
    var badUtf8 = bytes.clone();
    boolean altered = false;
    for (int n = 0; n < badUtf8.length - 1; n++)
      if ((badUtf8[n] & 255) == 0xf0) {
        badUtf8[n + 1] = (byte) 0xff;
        altered = true;
        break;
      }
    assertTrue(altered);
    assertThrows(
        IllegalArgumentException.class, () -> BuildReadinessRecords.decode(CONTEXT, badUtf8));
  }

  @Test
  void invalidDigestInputAndMissingRecordAreRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BuildReadinessRecords.encode(
                "bad", Optional.empty(), observation(BuildOverview.Readiness.PARTIAL, false)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BuildReadinessRecords.encode(
                CONTEXT, Optional.of("bad"), observation(BuildOverview.Readiness.PARTIAL, false)));
    assertThrows(IllegalArgumentException.class, () -> BuildReadinessRecords.decode(CONTEXT, null));
  }
}
