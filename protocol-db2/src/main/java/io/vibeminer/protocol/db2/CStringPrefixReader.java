package io.vibeminer.protocol.db2;

import static io.vibeminer.protocol.db2.SparseRecordException.Reason.*;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Reads only a requested UTF-8 C-string prefix within an explicitly supplied record slice. */
public final class CStringPrefixReader {
  public record Limits(int maxRecordBytes, int maxStrings, int maxStringBytes, int maxPrefixBytes) {
    public Limits {
      if (maxRecordBytes < 0 || maxStrings < 0 || maxStringBytes < 0 || maxPrefixBytes < 0)
        throw new IllegalArgumentException("Negative C-string prefix bounds");
    }
  }

  public record Prefix(List<String> strings, int bytesConsumed) {
    public Prefix {
      strings = List.copyOf(strings);
    }
  }

  /** Empty strings are valid; bytes after the requested prefix are intentionally uninspected. */
  public Prefix read(byte[] recordSlice, int count, Limits limits, BooleanSupplier cancelled)
      throws SparseRecordException {
    Objects.requireNonNull(recordSlice);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancelled);
    if (count < 0) throw new IllegalArgumentException("Negative string count");
    check(cancelled);
    if (recordSlice.length > limits.maxRecordBytes() || count > limits.maxStrings())
      throw error(LIMIT, "Record/string count exceeds prefix bounds");
    if (count > recordSlice.length)
      throw error(MALFORMED, "Record slice cannot contain requested terminator count");
    if (count > limits.maxPrefixBytes())
      throw error(LIMIT, "Requested terminator count exceeds prefix byte bound");
    var values = new ArrayList<String>(count);
    int cursor = 0;
    for (int i = 0; i < count; i++) {
      int start = cursor;
      while (true) {
        check(cancelled);
        if (cursor >= limits.maxPrefixBytes())
          throw error(LIMIT, "C-string prefix exceeds byte bound");
        if (cursor >= recordSlice.length)
          throw error(MALFORMED, "Requested C-string is unterminated within explicit record slice");
        if (recordSlice[cursor] == 0) break;
        if (cursor - start >= limits.maxStringBytes())
          throw error(LIMIT, "C-string exceeds per-string byte bound");
        cursor++;
      }
      try {
        values.add(
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(recordSlice, start, cursor - start))
                .toString());
      } catch (CharacterCodingException invalid) {
        throw error(MALFORMED, "Requested C-string contains invalid UTF-8");
      }
      cursor++;
    }
    check(cancelled);
    return new Prefix(values, cursor);
  }

  private static void check(BooleanSupplier cancelled) throws SparseRecordException {
    if (cancelled.getAsBoolean()) throw error(CANCELLED, "C-string prefix read cancelled");
  }

  private static SparseRecordException error(SparseRecordException.Reason reason, String message) {
    return new SparseRecordException(reason, message);
  }
}
