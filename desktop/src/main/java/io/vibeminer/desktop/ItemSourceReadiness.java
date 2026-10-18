package io.vibeminer.desktop;

import io.vibeminer.application.item.ItemProjectionException;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.selectedfile.SelectedFileAssociation;
import java.util.*;

/** Session evidence status keyed by exact source, independent of navigation and locale drafts. */
public final class ItemSourceReadiness {
  private final Map<String, SelectedFileAssociation> contexts = new LinkedHashMap<>();
  private final Map<String, ItemProjectionException.Code> failures = new LinkedHashMap<>();
  private final Set<String> verificationRequired = new HashSet<>();

  public void observe(SelectedFileAssociation source) {
    if (source == null || source.descriptor().association().fileDataId() != 841626) return;
    contexts.put(source.snapshotId(), source);
  }

  public void failed(SelectedFileAssociation source, ItemProjectionException.Code code) {
    if (source == null
        || code != ItemProjectionException.Code.MISSING_LOCAL
            && code != ItemProjectionException.Code.CORRUPT) return;
    observe(source);
    failures.put(source.role(), code);
  }

  public void verified(SelectedFileAssociation source) {
    if (source == null) return;
    observe(source);
    failures.remove(source.role());
    verificationRequired.remove(source.role());
  }

  public Optional<ItemProjectionException.Code> failureFor(BuildSnapshot build) {
    if (build == null) return Optional.empty();
    var context = contexts.get(build.snapshotId());
    return context == null ? Optional.empty() : Optional.ofNullable(failures.get(context.role()));
  }

  public void requiresVerification(SelectedFileAssociation source) {
    if (source == null || source.descriptor().association().fileDataId() != 841626) return;
    observe(source);
    verificationRequired.add(source.role());
  }

  public boolean verificationRequiredFor(BuildSnapshot build) {
    if (build == null) return false;
    var source = contexts.get(build.snapshotId());
    return source != null && verificationRequired.contains(source.role());
  }

  public Optional<SelectedFileAssociation> historicalFor(BuildSnapshot build) {
    return build == null ? Optional.empty() : Optional.ofNullable(contexts.get(build.snapshotId()));
  }

  public static String message(ItemProjectionException.Code code) {
    return code == ItemProjectionException.Code.MISSING_LOCAL
        ? "Item evidence missing"
        : "Item evidence verification failed";
  }
}
