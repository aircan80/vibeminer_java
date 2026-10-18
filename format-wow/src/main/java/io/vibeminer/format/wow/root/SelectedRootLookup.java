package io.vibeminer.format.wow.root;

import static io.vibeminer.format.wow.root.StreamingRootException.Reason.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Bounded streaming lookup of selected FileDataIDs; same explicit v1/v2 subset as WowRootParser.
 */
public final class SelectedRootLookup {
  public record Limits(
      long maxDecodedBytes,
      long maxRecords,
      int maxBlocks,
      long maxEntriesPerBlock,
      int maxRequestedIds,
      int maxMatches) {
    public Limits {
      if (maxDecodedBytes < 20
          || maxRecords < 0
          || maxBlocks < 0
          || maxEntriesPerBlock < 0
          || maxRequestedIds < 0
          || maxMatches < 0) throw new IllegalArgumentException("Invalid streaming root bounds");
    }
  }

  /**
   * Does not close input. Reads complete EOF before returning any result, including absence. Fixed
   * work buffer is 8192 bytes; retained positions/keys/rows are bounded by maxMatches. No source
   * authenticity is established by this structural parser.
   */
  public SelectedRootMemberships read(
      InputStream input, Set<Long> requestedIds, Limits limits, BooleanSupplier cancelled)
      throws StreamingRootException {
    Objects.requireNonNull(input);
    Objects.requireNonNull(requestedIds);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancelled);
    checkCancelled(cancelled);
    if (requestedIds.size() > limits.maxRequestedIds())
      throw error(LIMIT, "Requested FileDataID count exceeds limit");
    var requested = new HashSet<Long>();
    for (Long id : requestedIds) {
      if (id == null || id < 0 || id > 0xffffffffL)
        throw new IllegalArgumentException("Requested FileDataID must be unsigned 32-bit");
      if (requested.size() >= limits.maxRequestedIds())
        throw error(LIMIT, "Requested FileDataID count exceeds limit");
      requested.add(id);
    }
    var reader = new Reader(input, limits.maxDecodedBytes(), cancelled);
    int magic0 = reader.u8();
    int magic1 = reader.u8();
    int magic2 = reader.u8();
    int magic3 = reader.u8();
    if (magic0 != 'T' || magic1 != 'S' || magic2 != 'F' || magic3 != 'M')
      throw error(UNSUPPORTED, "Unsupported root signature or legacy format");
    long headerSize = reader.u32();
    long version = reader.u32();
    long total = reader.u32();
    long named = reader.u32();
    reader.context = "version=" + version;
    if (version != 1 && version != 2)
      throw reader.failure(UNSUPPORTED, "Unsupported root header/block version");
    if ((headerSize != 20 && headerSize != 24) || (version == 2 && headerSize != 24))
      throw reader.failure(UNSUPPORTED, "Unsupported explicit root header size");
    if (headerSize == 24 && reader.u32() != 0)
      throw reader.failure(UNSUPPORTED, "Unsupported root header extension data");
    if (total > limits.maxRecords()) throw error(LIMIT, "Root declared record count exceeds limit");
    if (named > total) throw error(MALFORMED, "Root named count exceeds total count");
    var entries = new LinkedHashMap<Long, List<WowRootManifest.Entry>>();
    long observed = 0, observedNamed = 0;
    int blocks = 0, retained = 0;
    while (true) {
      checkCancelled(cancelled);
      reader.context = "version=" + version + ", blockOffset=" + reader.position;
      long count = reader.u32OrEof();
      if (count < 0) break;
      if (blocks >= limits.maxBlocks()) throw error(LIMIT, "Root block count exceeds limit");
      blocks++;
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
      long flags = first | second | ((long) third << 17);
      // Preserve all raw bits; no enum-derived semantic policy controls metadata framing.
      reader.context += ", rawFlags1=" + first + ", rawFlags2=" + second + ", rawFlags3=" + third;
      if (count > limits.maxEntriesPerBlock() || count > limits.maxRecords() - observed)
        throw reader.failure(LIMIT, "Root block record count exceeds limit");
      boolean hasNames = (flags & WowRootParser.NO_NAMES) == 0;
      reader.checkBudget(count * (hasNames ? 28L : 20L));
      var rawFlags = new WowRootManifest.RawContentFlags(first, second, third);
      var matches = new ArrayList<Match>();
      long nextBase = 0;
      for (long index = 0; index < count; index++) {
        checkCancelled(cancelled);
        long id = nextBase + reader.u32();
        if (id > 0xffffffffL)
          throw reader.failure(MALFORMED, "Root FileDataID delta overflows unsigned 32-bit");
        nextBase = id + 1;
        if (requested.contains(id)) {
          if (matches.size() >= limits.maxMatches() - retained)
            throw error(LIMIT, "Retained root membership count exceeds limit");
          matches.add(new Match(index, id));
        }
      }
      long nextIndex = 0;
      for (Match match : matches) {
        reader.discard((match.index - nextIndex) * 16);
        match.contentKey = reader.key();
        nextIndex = match.index + 1;
      }
      reader.discard((count - nextIndex) * 16);
      if (hasNames) {
        nextIndex = 0;
        for (Match match : matches) {
          reader.discard((match.index - nextIndex) * 8);
          match.nameHash = Optional.of(HexFormat.of().toHexDigits(reader.u64()));
          nextIndex = match.index + 1;
        }
        reader.discard((count - nextIndex) * 8);
      }
      for (Match match : matches) {
        var entry =
            new WowRootManifest.Entry(
                match.id, match.contentKey, locale, flags, match.nameHash, rawFlags);
        entries.computeIfAbsent(match.id, ignored -> new ArrayList<>()).add(entry);
      }
      retained += matches.size();
      observed += count;
      if (hasNames) observedNamed += count;
    }
    if (observed != total || observedNamed != named)
      throw reader.failure(MALFORMED, "Root declared and observed record counts disagree");
    checkCancelled(cancelled);
    return new SelectedRootMemberships(
        (int) headerSize,
        (int) version,
        observed,
        observedNamed,
        reader.position,
        requested,
        entries,
        retained);
  }

  private static final class Match {
    final long index, id;
    String contentKey;
    Optional<String> nameHash = Optional.empty();

    Match(long index, long id) {
      this.index = index;
      this.id = id;
    }
  }

  private static final class Reader {
    final InputStream input;
    final long limit;
    final BooleanSupplier cancelled;
    final byte[] buffer = new byte[8192];
    int offset, end;
    long physicalBytes, position;
    String context = "";

    Reader(InputStream input, long limit, BooleanSupplier cancelled) {
      this.input = input;
      this.limit = limit;
      this.cancelled = cancelled;
    }

    boolean refill() throws StreamingRootException {
      checkCancelled(cancelled);
      long remaining = limit - physicalBytes;
      int request = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
      int count;
      try {
        count = input.read(buffer, 0, request);
        if (count == 0) {
          int value = input.read();
          if (value < 0) count = -1;
          else {
            buffer[0] = (byte) value;
            count = 1;
          }
        }
      } catch (IOException ex) {
        throw new StreamingRootException(IO, diagnostic("Unable to read root stream"), ex);
      }
      if (count < 0) return false;
      if (count > limit - physicalBytes) throw failure(LIMIT, "Root decoded byte limit exceeded");
      physicalBytes += count;
      offset = 0;
      end = count;
      return true;
    }

    int next() throws StreamingRootException {
      if (offset == end && !refill()) return -1;
      position++;
      return buffer[offset++] & 255;
    }

    int u8() throws StreamingRootException {
      int value = next();
      if (value < 0) throw failure(MALFORMED, "Truncated root stream");
      return value;
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

    String key() throws StreamingRootException {
      byte[] bytes = new byte[16];
      for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) u8();
      return HexFormat.of().formatHex(bytes);
    }

    String diagnostic(String message) {
      return message
          + " [decodedOffset="
          + position
          + (context.isEmpty() ? "" : ", " + context)
          + "]";
    }

    StreamingRootException failure(StreamingRootException.Reason reason, String message) {
      return error(reason, diagnostic(message));
    }

    void checkBudget(long count) throws StreamingRootException {
      if (count < 0 || count > limit - position)
        throw failure(LIMIT, "Declared root framing exceeds decoded byte bound");
    }

    void discard(long count) throws StreamingRootException {
      checkBudget(count);
      while (count > 0) {
        checkCancelled(cancelled);
        if (offset == end && !refill()) throw failure(MALFORMED, "Truncated skipped root data");
        int step = (int) Math.min(count, end - offset);
        offset += step;
        position += step;
        count -= step;
      }
    }
  }

  private static void checkCancelled(BooleanSupplier cancelled) throws StreamingRootException {
    if (cancelled.getAsBoolean()) throw error(CANCELLED, "Streaming root lookup cancelled");
  }

  private static StreamingRootException error(
      StreamingRootException.Reason reason, String message) {
    return new StreamingRootException(reason, message);
  }
}
