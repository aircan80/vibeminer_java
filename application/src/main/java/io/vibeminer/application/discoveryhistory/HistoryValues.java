package io.vibeminer.application.discoveryhistory;

import java.nio.charset.StandardCharsets;
import java.util.*;

final class HistoryValues {
  private HistoryValues() {}

  static String text(String value, int maximum) {
    Objects.requireNonNull(value);
    if (value.isBlank()
        || value.length() > maximum
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Bounded exact metadata text required");
    return value;
  }

  static String product(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9_-]{0,63}"))
      throw new IllegalArgumentException("Raw product required");
    return value;
  }

  static String region(String value) {
    if (value == null || !value.matches("[a-z]{2,8}"))
      throw new IllegalArgumentException("Raw region required");
    return value;
  }

  static String digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("SHA256 required");
    return value;
  }

  static void ordinal(int value) {
    if (value < 0 || value >= 10000)
      throw new IllegalArgumentException("Bounded source row ordinal required");
  }

  static Map<String, String> row(Map<String, String> values) {
    Objects.requireNonNull(values);
    if (values.isEmpty() || values.size() > 64)
      throw new IllegalArgumentException("Bounded selected row required");
    long length = values.size() - 1;
    for (var entry : values.entrySet()) {
      column(entry.getKey());
      var value = Objects.requireNonNull(entry.getValue());
      if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)
          || value.indexOf('\0') >= 0
          || value.indexOf('\n') >= 0
          || value.indexOf('|') >= 0)
        throw new IllegalArgumentException("Lossless metadata row values required");
      length += value.length();
    }
    if (length > 16384)
      throw new IllegalArgumentException("Selected row exceeds parser line limit");
    return Map.copyOf(values);
  }

  static String column(String value) {
    if (value == null || value.length() > 16384 || !value.matches("[A-Za-z][A-Za-z0-9_]*"))
      throw new IllegalArgumentException("Metadata column required");
    return value;
  }

  static void pageSize(int size) {
    if (size < 1 || size > 100) throw new IllegalArgumentException("Page size outside1..100");
  }
}
