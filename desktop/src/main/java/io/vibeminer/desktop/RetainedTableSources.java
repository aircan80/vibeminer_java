package io.vibeminer.desktop;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;

/** Session-only positive source observations, separate from history and prepared query handles. */
public final class RetainedTableSources {
  private record Slot(String snapshot, long file) {}

  private final Map<Slot, SelectedFileAssociation> current = new HashMap<>();
  private final Map<Slot, SelectedFileAssociation> history = new HashMap<>();

  private static boolean table(long file) {
    return file == 841626 || file == 1572924;
  }

  public void verified(SelectedFileAssociation source) {
    if (source == null || !table(source.descriptor().association().fileDataId())) return;
    var key = new Slot(source.snapshotId(), source.descriptor().association().fileDataId());
    current.put(key, source);
    history.put(key, source);
  }

  public void selectVariant(FileVariantAssociation variant) {
    if (variant == null || !table(variant.fileDataId())) return;
    var key = new Slot(variant.build().snapshotId(), variant.fileDataId());
    var old = current.get(key);
    if (old != null) {
      var selected =
          new SelectedFileDescriptor(
              variant,
              old.descriptor().actualEncodingKey(),
              old.descriptor().encodedVerifierSemantics());
      if (!selected.role().equals(old.role())) current.remove(key);
    }
  }

  public Optional<SelectedFileAssociation> current(BuildSnapshot build, long file) {
    return build == null
        ? Optional.empty()
        : Optional.ofNullable(current.get(new Slot(build.snapshotId(), file)));
  }

  public Optional<SelectedFileAssociation> history(BuildSnapshot build, long file) {
    return build == null
        ? Optional.empty()
        : Optional.ofNullable(history.get(new Slot(build.snapshotId(), file)));
  }

  public void invalidate(String snapshot, long file) {
    current.remove(new Slot(snapshot, file));
  }

  public void invalidateDependencies(String snapshot) {
    invalidate(snapshot, 841626);
    invalidate(snapshot, 1572924);
  }
}
