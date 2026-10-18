package io.vibeminer.protocol.tact.downloadsize;

import static io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryException.Reason.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Pure streaming DS-v1 enumeration; metadata-source authentication belongs to the caller. */
public final class DownloadSizeInventoryReader {
  public record Limits(
      long maxDecodedBytes,
      long maxEntries,
      int maxTags,
      int maxNameBytes,
      int maxBitmapBytes,
      long maxTotalTagBytes) {
    public Limits {
      if (maxDecodedBytes < 15
          || maxEntries < 0
          || maxTags < 0
          || maxNameBytes < 0
          || maxBitmapBytes < 0
          || maxTotalTagBytes < 0)
        throw new IllegalArgumentException("Invalid DS inventory limits");
    }
  }

  public record Header(
      int version, int keyBytes, long entryCount, int tagCount, long declaredTotalEncodedBytes) {}

  /** Exact bytes: a nine-byte prefix never acquires a manufactured sixteen-byte identity. */
  public record KeyReference(int byteLength, String hex) {
    public KeyReference {
      if ((byteLength != 9 && byteLength != 16) || hex == null || hex.length() != byteLength * 2)
        throw new IllegalArgumentException("Exact admitted-width encoded key reference required");
      for (int i = 0; i < hex.length(); i++) {
        char c = hex.charAt(i);
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
          throw new IllegalArgumentException("Lowercase exact hexadecimal key reference required");
      }
    }

    public Optional<String> fullEncodingKey() {
      return byteLength == 16 ? Optional.of(hex) : Optional.empty();
    }
  }

  public record Entry(KeyReference encodedKey, long encodedBytes) {
    public Entry {
      Objects.requireNonNull(encodedKey);
      if (encodedBytes < 0 || encodedBytes > 0xffffffffL)
        throw new IllegalArgumentException("Unsigned32 DS encoded size required");
    }
  }

  /** Immutable tag; raw padding is preserved but never treated as extra entries. */
  public static final class Tag {
    private final String name;
    private final int type;
    private final long entries;
    private final byte[] membership;

    private Tag(String name, int type, long entries, byte[] ownedMembership) {
      this.name = name;
      this.type = type;
      this.entries = entries;
      this.membership = ownedMembership;
    }

    public String name() {
      return name;
    }

    public int type() {
      return type;
    }

    public long entryCount() {
      return entries;
    }

    public byte[] membershipBytes() {
      return membership.clone();
    }

    public boolean includesEntry(long index) {
      Objects.checkIndex(index, entries);
      return (membership[(int) (index / 8)] & (0x80 >>> (index % 8))) != 0;
    }
  }

  /** Structural complete stream, not physical-source proof or unique storage size. */
  public record Completion(
      Header header,
      long observedEntries,
      int observedTags,
      long measuredEncodedBytes,
      long decodedBytesRead) {}

  @FunctionalInterface
  public interface Consumer {
    default void header(Header header) {}

    default void tag(long ordinal, Tag tag) {}

    /** All callbacks are provisional until read returns and the outer source is authenticated. */
    void entry(long ordinal, Entry entry);
  }

  /**
   * One entry/bitmap at a time, fixed8KiB input buffer; never closes input or catches sink
   * failures.
   */
  public Completion read(
      InputStream input, Limits limits, Consumer consumer, BooleanSupplier cancelled)
      throws DownloadSizeInventoryException {
    Objects.requireNonNull(input);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(consumer);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    var reader = new Reader(input, limits.maxDecodedBytes(), cancelled);
    if (reader.u8() != 'D' || reader.u8() != 'S')
      throw reader.fail(UNSUPPORTED, "Unsupported DS signature");
    int version = reader.u8(), keyBytes = reader.u8();
    if (version != 1 || (keyBytes != 9 && keyBytes != 16))
      throw reader.fail(UNSUPPORTED, "Unsupported DS version or encoded-key width");
    long entries = reader.u32();
    int tags = reader.u16();
    long declared = reader.u40();
    if (entries > limits.maxEntries() || tags > limits.maxTags())
      throw reader.fail(LIMIT, "DS declared counts exceed limits");
    long bitmap = (entries + 7) / 8;
    if (tags > 0 && bitmap > limits.maxBitmapBytes())
      throw reader.fail(LIMIT, "DS tag bitmap exceeds limit");
    long minimumTagWork = (3 + bitmap) * tags;
    if (minimumTagWork > limits.maxTotalTagBytes())
      throw reader.fail(LIMIT, "DS minimum aggregate tag work exceeds limit");
    reader.requireBudget(entries * (keyBytes + 4L) + minimumTagWork);
    var header = new Header(version, keyBytes, entries, tags, declared);
    consumer.header(header);
    check(cancelled);
    long tagWork = 0;
    for (int t = 0; t < tags; t++) {
      check(cancelled);
      var name = reader.name(limits.maxNameBytes());
      int type = reader.u16();
      long work = name.bytes + 3L + bitmap;
      if (work > limits.maxTotalTagBytes() - tagWork)
        throw reader.fail(LIMIT, "DS aggregate tag work exceeds limit");
      tagWork += work;
      byte[] membership = reader.bytes((int) bitmap);
      consumer.tag(t, new Tag(name.value, type, entries, membership));
      check(cancelled);
    }
    long sum = 0;
    for (long n = 0; n < entries; n++) {
      check(cancelled);
      var key = new KeyReference(keyBytes, HexFormat.of().formatHex(reader.bytes(keyBytes)));
      long size = reader.u32();
      try {
        sum = Math.addExact(sum, size);
      } catch (ArithmeticException ex) {
        throw reader.fail(MALFORMED, "DS encoded size sum overflows supported accounting");
      }
      consumer.entry(n, new Entry(key, size));
      check(cancelled);
    }
    if (reader.next() != -1) throw reader.fail(MALFORMED, "Trailing DS data");
    check(cancelled);
    if (sum != declared)
      throw reader.fail(MALFORMED, "DS measured encoded size sum differs from declared total");
    return new Completion(header, entries, tags, sum, reader.position);
  }

  private static void check(BooleanSupplier token) throws DownloadSizeInventoryException {
    if (token.getAsBoolean())
      throw new DownloadSizeInventoryException(CANCELLED, "DS inventory cancelled");
  }

  private record Name(String value, int bytes) {}

  private static final class Reader {
    final InputStream input;
    final long limit;
    final BooleanSupplier token;
    final byte[] buffer = new byte[8192];
    int at, end;
    long received, position;

    Reader(InputStream input, long limit, BooleanSupplier token) {
      this.input = input;
      this.limit = limit;
      this.token = token;
    }

    int next() throws DownloadSizeInventoryException {
      if (at == end) {
        check(token);
        long remaining = limit - received;
        int request = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
        int n;
        try {
          n = input.read(buffer, 0, request);
          if (n == 0) {
            int value = input.read();
            if (value < 0) n = -1;
            else {
              buffer[0] = (byte) value;
              n = 1;
            }
          }
        } catch (IOException ex) {
          throw new DownloadSizeInventoryException(
              IO, "Unable to read DS stream [decodedOffset=" + position + "]", ex);
        }
        if (n < 0) return -1;
        if (n > remaining) throw fail(LIMIT, "DS decoded byte limit exceeded");
        received += n;
        at = 0;
        end = n;
      }
      position++;
      return buffer[at++] & 255;
    }

    int u8() throws DownloadSizeInventoryException {
      int n = next();
      if (n < 0) throw fail(MALFORMED, "Truncated DS stream");
      return n;
    }

    int u16() throws DownloadSizeInventoryException {
      return (u8() << 8) | u8();
    }

    long u32() throws DownloadSizeInventoryException {
      return ((long) u16() << 16) | u16();
    }

    long u40() throws DownloadSizeInventoryException {
      return ((long) u8() << 32) | u32();
    }

    byte[] bytes(int count) throws DownloadSizeInventoryException {
      requireBudget(count);
      byte[] bytes = new byte[count];
      for (int i = 0; i < count; i++) {
        if ((i & 8191) == 0) check(token);
        bytes[i] = (byte) u8();
      }
      return bytes;
    }

    Name name(int maximum) throws DownloadSizeInventoryException {
      var out = new ByteArrayOutputStream(Math.min(maximum, 256));
      for (; ; ) {
        check(token);
        int next = u8();
        if (next == 0) break;
        if (out.size() >= maximum) throw fail(LIMIT, "DS tag name exceeds byte limit");
        out.write(next);
      }
      try {
        return new Name(
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString(),
            out.size());
      } catch (CharacterCodingException ex) {
        throw new DownloadSizeInventoryException(
            MALFORMED, "DS tag name is not strict UTF-8 [decodedOffset=" + position + "]", ex);
      }
    }

    void requireBudget(long bytes) throws DownloadSizeInventoryException {
      if (bytes < 0 || bytes > limit - position)
        throw fail(LIMIT, "DS declared framing exceeds decoded byte limit");
    }

    DownloadSizeInventoryException fail(
        DownloadSizeInventoryException.Reason reason, String message) {
      return new DownloadSizeInventoryException(
          reason, message + " [decodedOffset=" + position + "]");
    }
  }
}
