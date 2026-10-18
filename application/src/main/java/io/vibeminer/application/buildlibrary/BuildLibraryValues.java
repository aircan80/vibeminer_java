package io.vibeminer.application.buildlibrary;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class BuildLibraryValues {
  private BuildLibraryValues() {}

  static String text(String value, int maximum) {
    Objects.requireNonNull(value);
    if (value.length() > maximum
        || value.isBlank()
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Bounded exact metadata text required");
    return value;
  }

  static String digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("SHA256 identity required");
    return value;
  }

  static String config(String value, boolean optional) {
    Objects.requireNonNull(value);
    if (optional && value.isEmpty()) return value;
    if (!value.matches("[0-9a-fA-F]{32}"))
      throw new IllegalArgumentException("Exact configuration key required");
    return value.toLowerCase(java.util.Locale.ROOT);
  }
}
