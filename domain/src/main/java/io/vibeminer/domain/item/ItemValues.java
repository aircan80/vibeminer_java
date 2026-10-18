package io.vibeminer.domain.item;

final class ItemValues {
  private ItemValues() {}

  static String text(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 4096
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Bounded interpretation fact required");
    return value;
  }

  static String digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("SHA-256 identity required");
    return value;
  }

  static String key(String value) {
    if (value == null || !value.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Complete lowercase 16-byte key required");
    return value;
  }

  static String locale(String value) {
    if (value == null || !value.matches("[a-z]{2}[A-Z]{2}"))
      throw new IllegalArgumentException("Exact locale required");
    return value;
  }

  static long id(long value) {
    if (value < 0 || value > 0xffffffffL)
      throw new IllegalArgumentException("Unsigned 32-bit ID required");
    return value;
  }

  static void extent(long offset, long length) {
    if (offset < 0 || length < 1 || offset > Long.MAX_VALUE - length)
      throw new IllegalArgumentException("Invalid bounded byte extent");
  }
}
