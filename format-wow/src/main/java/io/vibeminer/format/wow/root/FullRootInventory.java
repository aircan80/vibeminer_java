package io.vibeminer.format.wow.root;

import static io.vibeminer.format.wow.root.StreamingRootException.Reason.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Pure full-membership enumeration of the existing explicit TSFM v1/v2 subset. */
public final class FullRootInventory {
  /** Working-byte bound covers packed CKeys and long FDIDs; the fixed I/O buffer is 8192 bytes. */
  public record Limits(
      long maxDecodedBytes,
      long maxRecords,
      int maxBlocks,
      int maxEntriesPerBlock,
      long maxBlockWorkingBytes) {
    public Limits {
      if (maxDecodedBytes < 20
          || maxRecords < 0
          || maxBlocks < 0
          || maxEntriesPerBlock < 0
          || maxBlockWorkingBytes < 0)
        throw new IllegalArgumentException("Invalid full root inventory bounds");
    }
  }

  /**
   * Structural EOF/count validation only. Source authenticity and publication belong to the caller.
   */
  public record Completion(
      int headerSize,
      int version,
      long membershipCount,
      long namedMembershipCount,
      long blockCount,
      long decodedBytesRead,
      long peakBlockWorkingBytes) {}

  @FunctionalInterface
  public interface EntrySink {
    /** Provisional until read returns completion AND the caller authenticates its source. */
    void accept(long blockOrdinal, long entryOrdinal, WowRootManifest.Entry entry);
  }

  /**
   * Does not close input, including on failure. Retains one bounded block's ID/key arrays only;
   * never filters variants or keeps a whole-root collection. Sink exceptions propagate unchanged.
   */
  public Completion read(
      InputStream input, Limits limits, EntrySink sink, BooleanSupplier cancelled)
      throws StreamingRootException {
    Objects.requireNonNull(input);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(sink);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    var reader = new Reader(input, limits.maxDecodedBytes(), cancelled);
    if (reader.u8() != 'T' || reader.u8() != 'S' || reader.u8() != 'F' || reader.u8() != 'M')
      throw reader.fail(UNSUPPORTED, "Unsupported root signature or legacy format");
    long header = reader.u32(), version = reader.u32(), total = reader.u32(), named = reader.u32();
    if (version != 1 && version != 2) throw reader.fail(UNSUPPORTED, "Unsupported root version");
    if ((header != 20 && header != 24) || (version == 2 && header != 24))
      throw reader.fail(UNSUPPORTED, "Unsupported root header size");
    if (header == 24 && reader.u32() != 0)
      throw reader.fail(UNSUPPORTED, "Unsupported root header extension");
    if (total > limits.maxRecords())
      throw reader.fail(LIMIT, "Declared root memberships exceed limit");
    if (named > total) throw reader.fail(MALFORMED, "Named memberships exceed total");
    long observed = 0, observedNamed = 0, blocks = 0, peak = 0;
    for (; ; ) {
      check(cancelled);
      long count = reader.u32OrEof();
      if (count < 0) break;
      if (blocks >= limits.maxBlocks()) throw reader.fail(LIMIT, "Root block count exceeds limit");
      long locale, first, second;
      int third;
      if (version == 2) {
        locale = reader.u32();
        first = reader.u32();
        second = reader.u32();
        third = reader.u8();
      } else {
        first = reader.u32();
        locale = reader.u32();
        second = 0;
        third = 0;
      }
      reader.context =
          "version="
              + version
              + ", block="
              + blocks
              + ", rawFlags="
              + first
              + "/"
              + second
              + "/"
              + third;
      if (count > limits.maxEntriesPerBlock() || count > limits.maxRecords() - observed)
        throw reader.fail(LIMIT, "Root block memberships exceed limit");
      long working = count * 24L;
      if (working > limits.maxBlockWorkingBytes() || count > Integer.MAX_VALUE / 16)
        throw reader.fail(LIMIT, "Root block working buffers exceed limit");
      long flags = first | second | ((long) third << 17);
      boolean hasNames = (flags & WowRootParser.NO_NAMES) == 0;
      reader.requireBudget(count * (hasNames ? 28L : 20L));
      check(cancelled);
      int entries = (int) count;
      long[] ids = new long[entries];
      byte[] keys = new byte[entries * 16];
      peak = Math.max(peak, working);
      long nextBase = 0;
      for (int i = 0; i < entries; i++) {
        check(cancelled);
        long id = nextBase + reader.u32();
        if (id > 0xffffffffL)
          throw reader.fail(MALFORMED, "Root FileDataID delta overflows unsigned32");
        ids[i] = id;
        nextBase = id + 1;
      }
      for (int i = 0; i < entries; i++) {
        check(cancelled);
        for (int n = 0; n < 16; n++) keys[i * 16 + n] = (byte) reader.u8();
      }
      var raw = new WowRootManifest.RawContentFlags(first, second, third);
      for (int i = 0; i < entries; i++) {
        check(cancelled);
        Optional<String> name =
            hasNames ? Optional.of(HexFormat.of().toHexDigits(reader.u64())) : Optional.empty();
        sink.accept(
            blocks,
            i,
            new WowRootManifest.Entry(
                ids[i],
                HexFormat.of().formatHex(keys, i * 16, i * 16 + 16),
                locale,
                flags,
                name,
                raw));
        check(cancelled);
      }
      observed += count;
      if (hasNames) observedNamed += count;
      blocks++;
    }
    if (observed != total || observedNamed != named)
      throw reader.fail(MALFORMED, "Declared and observed root counts disagree");
    check(cancelled);
    return new Completion(
        (int) header, (int) version, observed, observedNamed, blocks, reader.position, peak);
  }

  private static void check(BooleanSupplier cancelled) throws StreamingRootException {
    if (cancelled.getAsBoolean())
      throw new StreamingRootException(CANCELLED, "Full root inventory cancelled");
  }

  private static final class Reader {
    final InputStream input;
    final long limit;
    final BooleanSupplier cancelled;
    final byte[] buffer = new byte[8192];
    int at, end;
    long received, position;
    String context = "";

    Reader(InputStream input, long limit, BooleanSupplier cancelled) {
      this.input = input;
      this.limit = limit;
      this.cancelled = cancelled;
    }

    int next() throws StreamingRootException {
      if (at == end) {
        check(cancelled);
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
          throw new StreamingRootException(
              IO, "Unable to read root stream [decodedOffset=" + position + "]", ex);
        }
        if (n < 0) return -1;
        if (n > remaining) throw fail(LIMIT, "Root decoded byte limit exceeded");
        received += n;
        at = 0;
        end = n;
      }
      position++;
      return buffer[at++] & 255;
    }

    int u8() throws StreamingRootException {
      int n = next();
      if (n < 0) throw fail(MALFORMED, "Truncated root stream");
      return n;
    }

    long u32() throws StreamingRootException {
      return (long) u8() | ((long) u8() << 8) | ((long) u8() << 16) | ((long) u8() << 24);
    }

    long u32OrEof() throws StreamingRootException {
      int first = next();
      if (first < 0) return -1;
      return first | ((long) u8() << 8) | ((long) u8() << 16) | ((long) u8() << 24);
    }

    long u64() throws StreamingRootException {
      long low = u32();
      return low | (u32() << 32);
    }

    void requireBudget(long bytes) throws StreamingRootException {
      if (bytes < 0 || bytes > limit - position)
        throw fail(LIMIT, "Declared root framing exceeds decoded byte bound");
    }

    StreamingRootException fail(StreamingRootException.Reason reason, String message) {
      return new StreamingRootException(
          reason,
          message
              + " [decodedOffset="
              + position
              + (context.isEmpty() ? "" : ", " + context)
              + "]");
    }
  }
}
