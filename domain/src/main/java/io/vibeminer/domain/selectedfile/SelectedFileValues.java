package io.vibeminer.domain.selectedfile;

import java.util.Locale;

final class SelectedFileValues {
  private SelectedFileValues() {}

  static String key(String value) {
    if (value == null || !value.matches("[0-9a-fA-F]{32}"))
      throw new IllegalArgumentException("Complete 16-byte key required");
    return value.toLowerCase(Locale.ROOT);
  }

  static String digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("SHA-256 identity required");
    return value;
  }

  static String revision(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 256
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Bounded verifier revision required");
    return value;
  }
}
