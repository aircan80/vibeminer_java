package io.vibeminer.domain.item;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Preserved, case-sensitive scalar substring; no normalization, folding or wildcard semantics. */
public record ItemNameLiteralQuery(String text) {
  public static final String MODE_REVISION = "display-literal-scalars-case-sensitive-v1";
  public static final int MAX_SCALARS = 128;
  public static final int MAX_UTF8_BYTES = 512;

  public ItemNameLiteralQuery {
    if (text == null
        || text.isEmpty()
        || text.length() > MAX_SCALARS * 2
        || text.indexOf('\0') >= 0
        || text.codePointCount(0, text.length()) > MAX_SCALARS)
      throw new IllegalArgumentException(
          "Literal query requires 1 to 128 preserved Unicode scalars");
    if (strictUtf8(text).length > MAX_UTF8_BYTES)
      throw new IllegalArgumentException("Literal query exceeds 512 UTF-8 bytes");
  }

  /** Complete candidate construction already guarantees strict UTF-8 and no NUL. */
  public boolean matches(DisplayCandidate candidate) {
    return Objects.requireNonNull(candidate).value().contains(text);
  }

  /**
   * Query identity only; never an artifact identity, readiness proof or persisted result ledger.
   */
  public String queryId() {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        for (var value : List.of("vibeminer-item-name-literal-query-v1", MODE_REVISION, text)) {
          var encoded = strictUtf8(value);
          out.writeInt(encoded.length);
          out.write(encoded);
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] strictUtf8(String value) {
    try {
      var buffer =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      var bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      return bytes;
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException("Literal text must be strict encodable Unicode", invalid);
    }
  }
}
