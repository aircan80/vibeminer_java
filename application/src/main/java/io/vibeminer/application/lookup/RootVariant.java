package io.vibeminer.application.lookup;

import java.util.Optional;

/** All observed variants, without invented filename, locale preference or flag normalization. */
public record RootVariant(
    long fileDataId,
    String contentKey,
    long localeFlags,
    long contentFlags,
    long rawFlagsFirst,
    long rawFlagsSecond,
    int rawFlagsThird,
    Optional<String> nameHash) {
  public RootVariant {
    if (fileDataId < 0
        || fileDataId > 0xffffffffL
        || contentKey == null
        || !contentKey.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Invalid root variant");
    nameHash = java.util.Objects.requireNonNull(nameHash);
  }
}
