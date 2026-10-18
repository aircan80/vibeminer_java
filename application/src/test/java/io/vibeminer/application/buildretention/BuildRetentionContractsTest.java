package io.vibeminer.application.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class BuildRetentionContractsTest {
  @Test
  void targetIncludesExactInventoryGenerationAndScope() {
    var target =
        new Target(
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            "d".repeat(64),
            1,
            "all-declared-v1",
            "e".repeat(64));
    var changed =
        new Target(
            target.groupId(),
            target.snapshotId(),
            target.admissionId(),
            target.inventoryId(),
            2,
            target.scopeRevision(),
            target.scopeCommitment());
    assertNotEquals(target.identity(), changed.identity());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Target(
                target.groupId(),
                target.snapshotId(),
                target.admissionId(),
                target.inventoryId(),
                0,
                target.scopeRevision(),
                target.scopeCommitment()));
  }

  @Test
  void costsAndJobResultsNeverTurnUnknownOrFailureIntoSuccess() {
    assertThrows(IllegalArgumentException.class, () -> new Cost(-1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new Policy(1, 1, 1, 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Member(
                0,
                "a".repeat(64),
                "b".repeat(32),
                "c".repeat(32),
                0,
                new Cost(0, 0, 0),
                "d".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Finish(State.SUCCEEDED, Optional.empty(), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Finish(
                State.FAILED,
                Optional.of("a".repeat(64)),
                Optional.of(new Failure(FailureCode.UNAVAILABLE, "404"))));
  }
}
