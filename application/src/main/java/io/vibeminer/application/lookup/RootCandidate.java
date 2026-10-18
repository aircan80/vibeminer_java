package io.vibeminer.application.lookup;

/** Every EN candidate remains visible; unknown encoded size (-1) cannot authorize retention. */
public record RootCandidate(String encodingKey, long encodedBytes, long specificationIndex) {
  public RootCandidate {
    if (encodingKey == null
        || !encodingKey.matches("[0-9a-f]{32}")
        || encodedBytes < -1
        || specificationIndex < -1) throw new IllegalArgumentException("Invalid root candidate");
  }
}
