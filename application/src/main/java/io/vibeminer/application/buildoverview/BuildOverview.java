package io.vibeminer.application.buildoverview;

import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.buildretention.BuildRetention;
import java.time.*;
import java.util.*;

/** Immutable observations; constructing these values authenticates neither files nor a closure. */
public final class BuildOverview {
  private BuildOverview() {}

  public enum Readiness {
    NOT_READY,
    PARTIAL,
    FULL
  }

  public enum MetadataState {
    NOT_READY,
    READY,
    NEEDS_ATTENTION
  }

  public enum InventoryState {
    UNKNOWN,
    PREPARING,
    SEALED,
    INCOMPLETE,
    NEEDS_ATTENTION
  }

  public enum Stage {
    VALIDATING_METADATA,
    READING_INVENTORY,
    CHECKING_RETAINED,
    COMPLETE
  }

  public enum ProblemCode {
    METADATA_MISSING,
    METADATA_CORRUPT,
    UNSUPPORTED,
    INVENTORY_UNSEALED,
    RETAINED_MISSING,
    RETAINED_CORRUPT,
    CANONICAL_CONFLICT,
    LIMIT,
    SOURCE_CHANGED,
    RETENTION_STATUS_UNAVAILABLE
  }

  public record Limits(
      long maximumMembers,
      long maximumPayloadVerificationBytes,
      int pageSize,
      long maximumSqlInstructions,
      Duration maximumDuration) {
    public Limits {
      Objects.requireNonNull(maximumDuration);
      if (maximumMembers < 0
          || maximumPayloadVerificationBytes < 0
          || pageSize < 1
          || pageSize > 512
          || maximumSqlInstructions < 1000
          || maximumDuration.isNegative()
          || maximumDuration.isZero()
          || maximumDuration.compareTo(Duration.ofHours(1)) > 0)
        throw new IllegalArgumentException("Explicit bounded overview work required");
    }
  }

  public record Request(
      BuildLibraryReference reference,
      String locale,
      String scopeRevision,
      Limits limits,
      Optional<BuildRetention.Target> target) {
    public Request(BuildLibraryReference ref, String locale, String scope, Limits limits) {
      this(ref, locale, scope, limits, Optional.empty());
    }

    public Request {
      Objects.requireNonNull(reference);
      Objects.requireNonNull(limits);
      validateLocale(locale);
      scopeRevision = text(scopeRevision, 128);
      Objects.requireNonNull(target);
      if (target.isPresent()
          && (!target.get().groupId().equals(reference.key().identity())
              || !target.get().snapshotId().equals(reference.snapshotId())
              || !target.get().scopeRevision().equals(scopeRevision)))
        throw new IllegalArgumentException("Issued target differs from exact overview request");
    }
  }

  public record Problem(ProblemCode code, String message) {
    public Problem {
      Objects.requireNonNull(code);
      message = text(message, 2048);
    }

    public boolean blocksFull() {
      return code != ProblemCode.RETENTION_STATUS_UNAVAILABLE;
    }
  }

  public record Measurements(
      OptionalLong distinctFileIds,
      OptionalLong variantMemberships,
      OptionalLong acquisitionUnits,
      OptionalLong plannedEncodedBytes,
      OptionalLong retainedUnits,
      OptionalLong retainedScopeEncodedBytes,
      boolean denominatorComplete,
      boolean verificationComplete,
      OptionalLong localPhysicalBytes,
      OptionalLong sharedPhysicalBytes,
      OptionalLong metadataDerivedBytes) {
    public Measurements {
      for (var n :
          List.of(
              distinctFileIds,
              variantMemberships,
              acquisitionUnits,
              plannedEncodedBytes,
              retainedUnits,
              retainedScopeEncodedBytes,
              localPhysicalBytes,
              sharedPhysicalBytes,
              metadataDerivedBytes))
        if (n.isPresent() && n.getAsLong() < 0)
          throw new IllegalArgumentException("Negative overview measurement");
      if (!verificationComplete
          && (retainedUnits.isPresent() || retainedScopeEncodedBytes.isPresent()))
        throw new IllegalArgumentException(
            "Exact retained totals require complete assessment; progress carries partial checked"
                + " work");
      if (denominatorComplete && (acquisitionUnits.isEmpty() || plannedEncodedBytes.isEmpty())
          || verificationComplete
              && (!denominatorComplete
                  || retainedUnits.isEmpty()
                  || retainedScopeEncodedBytes.isEmpty()))
        throw new IllegalArgumentException("Complete observations require measured totals");
      if (retainedUnits.isPresent()
              && acquisitionUnits.isPresent()
              && retainedUnits.getAsLong() > acquisitionUnits.getAsLong()
          || denominatorComplete
              && retainedScopeEncodedBytes.isPresent()
              && retainedScopeEncodedBytes.getAsLong() > plannedEncodedBytes.orElseThrow()
          || localPhysicalBytes.isPresent()
              && sharedPhysicalBytes.isPresent()
              && sharedPhysicalBytes.getAsLong() > localPhysicalBytes.getAsLong())
        throw new IllegalArgumentException("Overview subset exceeds measured scope");
    }

    public static Measurements unknown() {
      return new Measurements(
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          false,
          false,
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty());
    }
  }

  /** Job success counts are historical ledger state, never a physical readiness proof. */
  public record Retention(
      BuildRetention.Mode mode,
      long generation,
      OptionalLong queued,
      OptionalLong running,
      OptionalLong succeeded,
      OptionalLong failed) {
    public Retention {
      Objects.requireNonNull(mode);
      if (generation < 1) throw new IllegalArgumentException("Positive intent generation required");
      for (var n : List.of(queued, running, succeeded, failed))
        if (n.isPresent() && n.getAsLong() < 0)
          throw new IllegalArgumentException("Negative ledger count");
    }
  }

  public record Snapshot(
      BuildLibraryReference reference,
      String locale,
      String scopeRevision,
      String revision,
      Instant measuredAt,
      MetadataState metadata,
      InventoryState inventory,
      Readiness readiness,
      Optional<BuildRetention.Target> target,
      Measurements measurements,
      Optional<Retention> retention,
      List<Problem> problems) {
    public Snapshot {
      Objects.requireNonNull(reference);
      validateLocale(locale);
      scopeRevision = text(scopeRevision, 128);
      if (revision == null || !revision.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Exact overview commitment required");
      Objects.requireNonNull(measuredAt);
      Objects.requireNonNull(metadata);
      Objects.requireNonNull(inventory);
      Objects.requireNonNull(readiness);
      Objects.requireNonNull(target);
      Objects.requireNonNull(measurements);
      Objects.requireNonNull(retention);
      problems = List.copyOf(problems);
      if (problems.size() > 64)
        throw new IllegalArgumentException("Overview problem bound exceeded");
      if (target.isPresent()
          && (!target.get().groupId().equals(reference.key().identity())
              || !target.get().snapshotId().equals(reference.snapshotId())
              || !target.get().scopeRevision().equals(scopeRevision)))
        throw new IllegalArgumentException("Overview target changed exact scope");
      if (metadata == MetadataState.READY && readiness == Readiness.NOT_READY
          || measurements.denominatorComplete() && inventory != InventoryState.SEALED)
        throw new IllegalArgumentException(
            "Complete metadata and denominator need their exact admitted readiness scope");
      if (readiness != Readiness.NOT_READY && metadata != MetadataState.READY
          || metadata != MetadataState.READY && readiness != Readiness.NOT_READY
          || inventory == InventoryState.SEALED && target.isEmpty()
          || retention.isPresent() && target.isEmpty())
        throw new IllegalArgumentException("Overview readiness/context mismatch");
      if (readiness == Readiness.FULL
          && (inventory != InventoryState.SEALED
              || !measurements.verificationComplete()
              || !measurements.denominatorComplete()
              || measurements.acquisitionUnits().orElseThrow()
                  != measurements.retainedUnits().orElseThrow()
              || measurements.plannedEncodedBytes().orElseThrow()
                  != measurements.retainedScopeEncodedBytes().orElseThrow()
              || problems.stream().anyMatch(Problem::blocksFull)))
        throw new IllegalArgumentException("Full requires complete current verified closure");
    }
  }

  public record Progress(
      Stage stage,
      long checkedMembers,
      OptionalLong totalMembers,
      long chargedPayloadVerificationBytes,
      Optional<Snapshot> observation) {
    public Progress {
      Objects.requireNonNull(stage);
      Objects.requireNonNull(totalMembers);
      Objects.requireNonNull(observation);
      if (checkedMembers < 0
          || chargedPayloadVerificationBytes < 0
          || totalMembers.isPresent() && totalMembers.getAsLong() < checkedMembers
          || observation.isPresent()
              && observation.get().readiness() == Readiness.FULL
              && stage != Stage.COMPLETE)
        throw new IllegalArgumentException("Invalid bounded overview progress");
    }

    public static Progress initial() {
      return new Progress(Stage.VALIDATING_METADATA, 0, OptionalLong.empty(), 0, Optional.empty());
    }
  }

  private static void validateLocale(String value) {
    if (value == null || !value.matches("[a-z]{2}[A-Z]{2}"))
      throw new IllegalArgumentException("Exact research locale required");
  }

  private static String text(String s, int max) {
    if (s == null
        || s.isBlank()
        || s.length() > max
        || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(s)
        || s.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Bounded valid text required");
    return s;
  }
}
