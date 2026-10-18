package io.vibeminer.domain.retention;

import io.vibeminer.domain.ResolvedSourceIdentity;
import java.util.*;

/** Unknown identity/size is explicit. Logical size describes this metadata object decoded. */
public record ScopeRequirement(
    String role,
    Optional<ResolvedSourceIdentity> source,
    OptionalLong encodedBytes,
    OptionalLong logicalDecodedBytes) {
  public ScopeRequirement {
    if (role == null
        || role.isBlank()
        || !role.equals(role.strip())
        || role.length() > 128
        || role.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid retention role");
    Objects.requireNonNull(source);
    Objects.requireNonNull(encodedBytes);
    Objects.requireNonNull(logicalDecodedBytes);
    if ((encodedBytes.isPresent() && encodedBytes.getAsLong() < 0)
        || (logicalDecodedBytes.isPresent() && logicalDecodedBytes.getAsLong() < 0))
      throw new IllegalArgumentException("Sizes must be nonnegative or unknown");
  }
}
