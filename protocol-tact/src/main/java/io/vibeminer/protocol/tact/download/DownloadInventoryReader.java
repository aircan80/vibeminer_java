package io.vibeminer.protocol.tact.download;

import static io.vibeminer.protocol.tact.download.DownloadInventoryException.Reason.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Pure DL-v1/v2/v3 enumeration with complete16-byte EKeys and caller-owned decoded input. */
public final class DownloadInventoryReader {
  public record Limits(
      long maxDecodedBytes,
      long maxEntries,
      int maxTags,
      int maxNameBytes,
      int maxBitmapBytes,
      long maxAggregateTagWorkBytes,
      int maxFlagBytes) {
    public Limits {
      if (maxDecodedBytes < 11
          || maxEntries < 0
          || maxTags < 0
          || maxNameBytes < 0
          || maxNameBytes > Integer.MAX_VALUE - 8
          || maxBitmapBytes < 0
          || maxBitmapBytes > Integer.MAX_VALUE - 8
          || maxAggregateTagWorkBytes < 0
          || maxFlagBytes < 0
          || maxFlagBytes > 255)
        throw new IllegalArgumentException("Invalid download inventory limits");
    }
  }

  public record Header(
      int version,
      int checksumPresentRaw,
      long entryCount,
      int tagCount,
      int flagBytes,
      OptionalInt basePriorityRaw,
      byte[] unknownVersion3Bytes) {
    public Header {
      Objects.requireNonNull(basePriorityRaw);
      unknownVersion3Bytes = unknownVersion3Bytes.clone();
    }

    @Override
    public byte[] unknownVersion3Bytes() {
      return unknownVersion3Bytes.clone();
    }

    public OptionalInt basePrioritySigned() {
      return basePriorityRaw.isPresent()
          ? OptionalInt.of((byte) basePriorityRaw.getAsInt())
          : OptionalInt.empty();
    }
  }

  public record Entry(
      String encodingKey, long encodedBytes, int priorityRaw, OptionalLong checksum, byte[] flags) {
    public Entry {
      Objects.requireNonNull(encodingKey);
      Objects.requireNonNull(checksum);
      flags = flags.clone();
    }

    @Override
    public byte[] flags() {
      return flags.clone();
    }

    public int prioritySigned() {
      return (byte) priorityRaw;
    }
  }

  public record Tag(String name, int rawType, long entryCount, byte[] bitmap) {
    public Tag {
      Objects.requireNonNull(name);
      bitmap = bitmap.clone();
    }

    @Override
    public byte[] bitmap() {
      return bitmap.clone();
    }

    public boolean includesEntry(long ordinal) {
      if (ordinal < 0 || ordinal >= entryCount)
        throw new IndexOutOfBoundsException("Entry ordinal outside tag scope");
      return (bitmap[Math.toIntExact(ordinal >>> 3)] & (0x80 >>> (int) (ordinal & 7))) != 0;
    }
  }

  /**
   * Every callback is provisional until read returns a Completion. Sink failures propagate intact.
   */
  public interface Consumer {
    void header(Header header);

    void entry(long ordinal, Entry entry);

    void tag(int ordinal, Tag tag);
  }

  /** Complete supported framing/count/EOF validation only, never a decoded or encoded checksum. */
  public record Completion(int version, long entries, int tags, long consumedBytes) {}

  /**
   * Retains one bounded entry/name/bitmap at a time; never closes input. The EOF check can probe
   * one byte beyond the admitted body ceiling, solely to reject trailing data. No partial result is
   * returned after any failure; consumers must stage their provisional callbacks themselves.
   */
  public Completion read(
      InputStream input, Limits limits, Consumer consumer, BooleanSupplier cancelled)
      throws DownloadInventoryException {
    Objects.requireNonNull(input);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(consumer);
    Objects.requireNonNull(cancelled);
    var r = new Reader(input, limits, cancelled);
    if (r.u8() != 'D' || r.u8() != 'L')
      throw error(UNSUPPORTED, "Unsupported download manifest signature");
    int version = r.u8();
    if (version < 1 || version > 3)
      throw error(UNSUPPORTED, "Unsupported download manifest version");
    if (r.u8() != 16) throw error(UNSUPPORTED, "Complete16-byte download EKeys required");
    int checksum = r.u8();
    long entries = r.unsigned(4);
    int tags = (int) r.unsigned(2);
    int flags = version >= 2 ? r.u8() : 0;
    OptionalInt base = version == 3 ? OptionalInt.of(r.u8()) : OptionalInt.empty();
    byte[] unknown = version == 3 ? r.bytes(3) : new byte[0];
    if (entries > limits.maxEntries() || tags > limits.maxTags() || flags > limits.maxFlagBytes())
      throw error(LIMIT, "Download header exceeds entry/tag/flag policy");
    long bitmapBytes = (entries + 7) / 8;
    if (tags > 0 && bitmapBytes > limits.maxBitmapBytes())
      throw error(LIMIT, "Tag bitmap exceeds policy");
    long entryWidth = 22L + (checksum != 0 ? 4 : 0) + flags;
    long minimumBytes =
        add(r.consumed, add(multiply(entries, entryWidth), multiply(tags, add(3, bitmapBytes))));
    if (minimumBytes > limits.maxDecodedBytes())
      throw error(LIMIT, "Declared minimum inventory exceeds byte policy");
    long minimumTagWork = multiply(tags, add(1, bitmapBytes));
    if (minimumTagWork > limits.maxAggregateTagWorkBytes())
      throw error(LIMIT, "Declared tag work exceeds policy");
    var header = new Header(version, checksum, entries, tags, flags, base, unknown);
    r.checkCancelled();
    consumer.header(header);
    r.checkCancelled();
    for (long ordinal = 0; ordinal < entries; ordinal++) {
      String key = HexFormat.of().formatHex(r.bytes(16));
      long size = r.unsigned(5);
      int priority = r.u8();
      OptionalLong value = checksum != 0 ? OptionalLong.of(r.unsigned(4)) : OptionalLong.empty();
      byte[] rawFlags = r.bytes(flags);
      r.checkCancelled();
      consumer.entry(ordinal, new Entry(key, size, priority, value, rawFlags));
      r.checkCancelled();
    }
    for (int ordinal = 0; ordinal < tags; ordinal++) {
      String name = r.name();
      int type = (int) r.unsigned(2);
      r.chargeTagWork(bitmapBytes);
      byte[] bitmap = r.bytes(Math.toIntExact(bitmapBytes));
      r.checkCancelled();
      consumer.tag(ordinal, new Tag(name, type, entries, bitmap));
      r.checkCancelled();
    }
    r.exactEof();
    r.checkCancelled();
    return new Completion(version, entries, tags, r.consumed);
  }

  private static long add(long a, long b) throws DownloadInventoryException {
    try {
      return Math.addExact(a, b);
    } catch (ArithmeticException overflow) {
      throw error(LIMIT, "Download count arithmetic overflow");
    }
  }

  private static long multiply(long a, long b) throws DownloadInventoryException {
    try {
      return Math.multiplyExact(a, b);
    } catch (ArithmeticException overflow) {
      throw error(LIMIT, "Download size arithmetic overflow");
    }
  }

  private static DownloadInventoryException error(
      DownloadInventoryException.Reason reason, String message) {
    return new DownloadInventoryException(reason, message);
  }

  private static final class Reader {
    final InputStream input;
    final Limits limits;
    final BooleanSupplier cancelled;
    long consumed, tagWork;

    Reader(InputStream input, Limits limits, BooleanSupplier cancelled) {
      this.input = input;
      this.limits = limits;
      this.cancelled = cancelled;
    }

    void checkCancelled() throws DownloadInventoryException {
      if (cancelled.getAsBoolean()) throw error(CANCELLED, "Download inventory canceled");
    }

    void requireBytes(long count) throws DownloadInventoryException {
      if (count < 0 || count > limits.maxDecodedBytes() - consumed)
        throw error(LIMIT, "Download bytes exceed admitted ceiling");
    }

    int u8() throws DownloadInventoryException {
      checkCancelled();
      requireBytes(1);
      try {
        int b = input.read();
        checkCancelled();
        if (b < 0) throw error(MALFORMED, "Truncated download inventory");
        consumed++;
        return b;
      } catch (IOException failure) {
        throw new DownloadInventoryException(IO, "Cannot read download inventory", failure);
      }
    }

    long unsigned(int width) throws DownloadInventoryException {
      long result = 0;
      for (int i = 0; i < width; i++) result = (result << 8) | u8();
      return result;
    }

    byte[] bytes(int count) throws DownloadInventoryException {
      checkCancelled();
      requireBytes(count);
      byte[] result = new byte[count];
      int position = 0;
      while (position < count) {
        checkCancelled();
        int n;
        try {
          n = input.read(result, position, Math.min(8192, count - position));
        } catch (IOException failure) {
          throw new DownloadInventoryException(IO, "Cannot read download inventory", failure);
        }
        checkCancelled();
        if (n < 0) throw error(MALFORMED, "Truncated download inventory array");
        if (n == 0) {
          result[position++] = (byte) u8();
        } else {
          position += n;
          consumed += n;
        }
      }
      return result;
    }

    void chargeTagWork(long count) throws DownloadInventoryException {
      if (count < 0 || count > limits.maxAggregateTagWorkBytes() - tagWork)
        throw error(LIMIT, "Download bitmap/name work exceeds policy");
      tagWork += count;
    }

    String name() throws DownloadInventoryException {
      byte[] bytes = new byte[Math.min(64, limits.maxNameBytes())];
      int count = 0;
      while (true) {
        chargeTagWork(1);
        int b = u8();
        if (b == 0) break;
        if (count == limits.maxNameBytes()) throw error(LIMIT, "Download tag name exceeds policy");
        if (count == bytes.length) {
          int next = (int) Math.min(limits.maxNameBytes(), Math.max(1L, bytes.length * 2L));
          bytes = Arrays.copyOf(bytes, next);
        }
        bytes[count++] = (byte) b;
      }
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, count))
            .toString();
      } catch (CharacterCodingException malformed) {
        throw new DownloadInventoryException(
            MALFORMED, "Download tag name is not strict UTF-8", malformed);
      }
    }

    void exactEof() throws DownloadInventoryException {
      checkCancelled();
      int trailing;
      try {
        trailing = input.read();
      } catch (IOException failure) {
        throw new DownloadInventoryException(IO, "Cannot verify download EOF", failure);
      }
      checkCancelled();
      if (trailing != -1)
        throw error(
            consumed >= limits.maxDecodedBytes() ? LIMIT : MALFORMED,
            "Trailing download inventory data");
    }
  }
}
