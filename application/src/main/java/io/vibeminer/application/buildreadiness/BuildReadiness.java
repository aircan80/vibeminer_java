package io.vibeminer.application.buildreadiness;

import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.buildoverview.BuildOverview;
import io.vibeminer.application.buildretention.BuildRetention;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Dated chooser facts, never a capability or current physical assessment. */
public final class BuildReadiness {
  private BuildReadiness() {}

  public static final int MAXIMUM_CONTEXTS = 100;

  public enum AdmissionPresence {
    ABSENT,
    PRESENT
  }

  public record Context(
      BuildLibraryReference reference,
      String locale,
      String metadataScopeRevision,
      String inventoryScopeRevision) {
    public Context {
      Objects.requireNonNull(reference);
      if (locale == null || !locale.matches("[a-z]{2}[A-Z]{2}"))
        throw new IllegalArgumentException("Exact locale required");
      text(metadataScopeRevision);
      text(inventoryScopeRevision);
    }

    /** Event receipts are provenance; this key describes the same exact regional build/policies. */
    public List<String> key() {
      return List.of(
          reference.key().identity(),
          reference.snapshotId(),
          locale,
          metadataScopeRevision,
          inventoryScopeRevision);
    }
  }

  public record Origin(
      UUID eventId,
      long observationSequence,
      int versionsRowOrdinal,
      String groupId,
      String snapshotId,
      String region) {
    public Origin {
      Objects.requireNonNull(eventId);
      digest(groupId);
      digest(snapshotId);
      if (observationSequence < 1
          || versionsRowOrdinal < 0
          || versionsRowOrdinal >= 10000
          || region == null
          || !region.matches("[a-z]{2,8}"))
        throw new IllegalArgumentException("Exact original observation reference required");
    }

    static Origin from(BuildLibraryReference ref) {
      return new Origin(
          ref.eventId(),
          ref.observationSequence(),
          ref.versionsRowOrdinal(),
          ref.key().identity(),
          ref.snapshotId(),
          ref.region());
    }
  }

  public record Observation(
      BuildOverview.Readiness readiness,
      Instant measuredAt,
      String overviewRevision,
      Origin origin,
      Optional<BuildRetention.Target> target) {
    public Observation {
      Objects.requireNonNull(readiness);
      Objects.requireNonNull(measuredAt);
      digest(overviewRevision);
      Objects.requireNonNull(origin);
      Objects.requireNonNull(target);
      if (readiness == BuildOverview.Readiness.FULL && target.isEmpty())
        throw new IllegalArgumentException("Historical Full must identify its assessed target");
      if (target.isPresent()
          && (!target.get().groupId().equals(origin.groupId())
              || !target.get().snapshotId().equals(origin.snapshotId())))
        throw new IllegalArgumentException("Historical target differs from original source");
    }
  }

  public record Status(
      Context context, AdmissionPresence admission, Optional<Observation> lastChecked) {
    public Status {
      Objects.requireNonNull(context);
      Objects.requireNonNull(admission);
      Objects.requireNonNull(lastChecked);
      lastChecked.ifPresent(
          value -> {
            if (!value.origin().groupId().equals(context.reference().key().identity())
                || !value.origin().snapshotId().equals(context.reference().snapshotId())
                || !value.origin().region().equals(context.reference().region())
                || value.target().isPresent()
                    && !value
                        .target()
                        .get()
                        .scopeRevision()
                        .equals(context.inventoryScopeRevision()))
              throw new IllegalArgumentException("Historical readiness changed exact context");
          });
    }
  }

  static List<Context> contexts(List<Context> values) {
    if (values == null || values.size() > MAXIMUM_CONTEXTS)
      throw new IllegalArgumentException("At most100 visible exact contexts required");
    var copy = List.copyOf(values);
    var keys = new HashSet<List<String>>();
    for (var context : copy)
      if (!keys.add(context.key())) throw new IllegalArgumentException("Duplicate exact context");
    return copy;
  }

  private static void text(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Bounded policy revision required");
  }

  private static void digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Exact immutable digest required");
  }
}
