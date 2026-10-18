package io.vibeminer.domain.selectedfile;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.ResolvedSourceIdentity;
import java.util.Objects;
import java.util.Optional;

/** Exact observed root variant. Construction alone does not authenticate its membership. */
public record FileVariantAssociation(
    BuildSnapshot build,
    ResolvedSourceIdentity rootSource,
    long fileDataId,
    String contentKey,
    long localeFlags,
    long contentFlags,
    long rawFlagsFirst,
    long rawFlagsSecond,
    int rawFlagsThird,
    Optional<String> nameHash) {
  public FileVariantAssociation {
    Objects.requireNonNull(build);
    Objects.requireNonNull(rootSource);
    Objects.requireNonNull(nameHash);
    if (fileDataId < 0 || fileDataId > 0xffffffffL)
      throw new IllegalArgumentException("Unsigned FileDataID required");
    if (!build.productId().equals("wow"))
      throw new IllegalArgumentException(
          "Only the explicitly verified retail wow product is currently supported");
    if (localeFlags < 0
        || localeFlags > 0xffffffffL
        || contentFlags < 0
        || contentFlags > 0xffffffffL
        || rawFlagsFirst < 0
        || rawFlagsFirst > 0xffffffffL
        || rawFlagsSecond < 0
        || rawFlagsSecond > 0xffffffffL
        || rawFlagsThird < 0
        || rawFlagsThird > 255) {
      throw new IllegalArgumentException("Root flags must retain their unsigned physical widths");
    }
    contentKey = SelectedFileValues.key(contentKey);
    if (nameHash.isPresent() && !nameHash.get().matches("[0-9a-f]{16}"))
      throw new IllegalArgumentException("Raw 64-bit name hash required");
  }

  public ContentSlotKey slot() {
    return new ContentSlotKey(ContentSlotKey.WOW_NAMESPACE, contentKey);
  }
}
