package io.vibeminer.domain.retention;

/** Supported metadata coverage only, never a full-game archive claim. */
public enum ScopeKind {
  INSTALL_METADATA,
  ROOT_METADATA,
  LEGACY_RETAINED
}
