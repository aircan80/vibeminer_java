package io.vibeminer.domain.content;

import java.util.Locale;

final class ContentValues {
  private ContentValues() {}

  static String key(String value) {
    if (value == null || !value.matches("[0-9a-fA-F]{32}"))
      throw new IllegalArgumentException("Complete content/encoding key required");
    return value.toLowerCase(Locale.ROOT);
  }

  static String revision(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 256
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Explicit bounded verification revision required");
    return value;
  }
}
