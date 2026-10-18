package io.vibeminer.application.retention;

/** Full bounded accounting view; exceeding a limit fails rather than silently truncates totals. */
public record RetentionLimits(int maximumScopes, int maximumRequirements) {
  public RetentionLimits {
    if (maximumScopes < 1 || maximumRequirements < 1)
      throw new IllegalArgumentException("Limits must be positive");
  }

  public static RetentionLimits defaults() {
    return new RetentionLimits(1024, 8192);
  }
}
