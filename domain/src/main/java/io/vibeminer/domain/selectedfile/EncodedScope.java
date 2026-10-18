package io.vibeminer.domain.selectedfile;

/** Complete encoded-only scopes. Neither establishes decoded CKey or readable content. */
public enum EncodedScope {
  ZERO_HEADER_WHOLE_ENCODED,
  HEADER_AND_ALL_ENCODED_CHUNKS
}
