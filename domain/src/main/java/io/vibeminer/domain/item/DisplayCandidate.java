package io.vibeminer.domain.item;

import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.Objects;

/** Unnormalized fifth inline string candidate, with physical coordinates and bounded prefix. */
public record DisplayCandidate(
    String value,
    long physicalRecordId,
    int physicalOrdinal,
    int sectionOrdinal,
    long decodedOffset,
    int recordBytes,
    int prefixBytes) {
  public DisplayCandidate {
    Objects.requireNonNull(value);
    ItemValues.id(physicalRecordId);
    ItemValues.extent(decodedOffset, recordBytes);
    if (physicalRecordId == 0
        || physicalOrdinal < 0
        || sectionOrdinal < 0
        || recordBytes > 65535
        || prefixBytes < 5
        || prefixBytes > recordBytes
        || value.length() > 65530
        || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid bounded physical Display candidate");
    if (utf8Bytes(value) > prefixBytes - 5)
      throw new IllegalArgumentException("Display bytes exceed five-string prefix geometry");
  }

  public int utf8Bytes() {
    return utf8Bytes(value);
  }

  private static int utf8Bytes(String value) {
    try {
      return StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(value))
          .remaining();
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException(
          "Display candidate is not strict encodable UTF-8", invalid);
    }
  }
}
