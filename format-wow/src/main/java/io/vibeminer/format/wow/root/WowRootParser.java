package io.vibeminer.format.wow.root;

import static io.vibeminer.format.wow.root.WowRootException.Reason.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Pure bounded explicit-header TSFM v1/v2 subset. No legacy sniffing or locale/platform filtering.
 */
public final class WowRootParser {
  public static final long NO_NAMES = 0x10000000L;

  // Content flags are opaque uint metadata. Only NO_NAMES affects array framing.
  public record Limits(int maxBytes, int maxEntries, int maxBlocks, int maxEntriesPerBlock) {
    public Limits {
      if (maxBytes < 20 || maxEntries < 0 || maxBlocks < 0 || maxEntriesPerBlock < 0)
        throw new IllegalArgumentException("Invalid root parser limits");
    }
  }

  /** Caller verifies the outer decoded content identity and keeps input unchanged while parsing. */
  public WowRootManifest parse(byte[] decoded, Limits limits, BooleanSupplier cancelled)
      throws WowRootException {
    Objects.requireNonNull(decoded);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancelled);
    checkCancelled(cancelled);
    if (decoded.length > limits.maxBytes()) throw error(LIMIT, "Root input byte limit exceeded");
    if (decoded.length < 4) throw error(MALFORMED, "Truncated root signature");
    if (decoded[0] != 'T' || decoded[1] != 'S' || decoded[2] != 'F' || decoded[3] != 'M')
      throw error(UNSUPPORTED, "Unsupported root signature or legacy format");
    if (decoded.length < 20) throw error(MALFORMED, "Truncated explicit root header");
    var b = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN);
    long headerSize = u32(b, 4), version = u32(b, 8), total = u32(b, 12), named = u32(b, 16);
    if (version != 1 && version != 2)
      throw metadataError(UNSUPPORTED, "Unsupported root header/block version", version, 8);
    if (headerSize != 20 && headerSize != 24)
      throw metadataError(UNSUPPORTED, "Unsupported explicit root header size", version, 4);
    if (version == 2 && headerSize != 24)
      throw metadataError(UNSUPPORTED, "Unsupported version-2 root header size", version, 4);
    if (headerSize > decoded.length) throw error(MALFORMED, "Truncated root header extension");
    if (headerSize == 24 && u32(b, 20) != 0)
      throw metadataError(UNSUPPORTED, "Unsupported root header extension data", version, 20);
    if (total > limits.maxEntries()) throw error(LIMIT, "Root declared record count exceeds limit");
    if (named > total) throw error(MALFORMED, "Root named count exceeds total count");
    int offset = (int) headerSize, blocks = 0;
    long observed = 0, observedNamed = 0;
    var entries = new LinkedHashMap<Long, List<WowRootManifest.Entry>>();
    while (offset < decoded.length) {
      checkCancelled(cancelled);
      if (blocks >= limits.maxBlocks()) throw error(LIMIT, "Root block count exceeds limit");
      blocks++;
      int blockHeaderBytes = version == 2 ? 17 : 12;
      if (decoded.length - offset < blockHeaderBytes)
        throw metadataError(MALFORMED, "Truncated root block header", version, offset);
      long count = u32(b, offset), locale, flags1, flags2;
      int flags3;
      if (version == 2) {
        locale = u32(b, offset + 4);
        flags1 = u32(b, offset + 8);
        flags2 = u32(b, offset + 12);
        flags3 = decoded[offset + 16] & 255;
      } else {
        flags1 = u32(b, offset + 4);
        locale = u32(b, offset + 8);
        flags2 = 0;
        flags3 = 0;
      }
      offset += blockHeaderBytes;
      // Pinned readers normalize this way, but the OR loses raw provenance, so retain both.
      long flags = flags1 | flags2 | ((long) flags3 << 17);
      var rawFlags = new WowRootManifest.RawContentFlags(flags1, flags2, flags3);
      if (count > limits.maxEntriesPerBlock() || count > limits.maxEntries() - observed)
        throw blockError(LIMIT, "Root block record count exceeds limit", version, offset, rawFlags);
      boolean hasNames = (flags & NO_NAMES) == 0;
      long blockBytes = count * (hasNames ? 28L : 20L);
      if (blockBytes > decoded.length - offset)
        throw blockError(
            MALFORMED, "Root block arrays exceed available input", version, offset, rawFlags);
      int idsAt = offset,
          keysAt = (int) (offset + count * 4),
          hashesAt = (int) (offset + count * 20);
      long nextBase = 0;
      for (int i = 0; i < count; i++) {
        checkCancelled(cancelled);
        long id = nextBase + u32(b, idsAt + 4 * i);
        if (id > 0xffffffffL)
          throw blockError(
              MALFORMED,
              "Root FileDataID delta overflows unsigned 32-bit",
              version,
              idsAt + 4L * i,
              rawFlags);
        nextBase = id + 1;
        String contentKey =
            HexFormat.of().formatHex(decoded, keysAt + 16 * i, keysAt + 16 * i + 16);
        Optional<String> nameHash =
            hasNames
                ? Optional.of(HexFormat.of().toHexDigits(b.getLong(hashesAt + 8 * i)))
                : Optional.empty();
        var entry = new WowRootManifest.Entry(id, contentKey, locale, flags, nameHash, rawFlags);
        entries.computeIfAbsent(id, ignored -> new ArrayList<>()).add(entry);
      }
      observed += count;
      if (hasNames) observedNamed += count;
      offset += (int) blockBytes;
    }
    if (observed != total || observedNamed != named)
      throw metadataError(
          MALFORMED, "Root declared and observed record counts disagree", version, offset);
    checkCancelled(cancelled);
    return new WowRootManifest((int) headerSize, (int) version, observed, observedNamed, entries);
  }

  private static long u32(ByteBuffer b, int offset) {
    return Integer.toUnsignedLong(b.getInt(offset));
  }

  private static void checkCancelled(BooleanSupplier cancelled) throws WowRootException {
    if (cancelled.getAsBoolean()) throw error(CANCELLED, "Root parsing cancelled");
  }

  private static WowRootException metadataError(
      WowRootException.Reason reason, String message, long version, long decodedOffset) {
    return error(
        reason, message + " [version=" + version + ", decodedOffset=" + decodedOffset + "]");
  }

  private static WowRootException blockError(
      WowRootException.Reason reason,
      String message,
      long version,
      long decodedOffset,
      WowRootManifest.RawContentFlags flags) {
    return error(
        reason,
        message
            + " [version="
            + version
            + ", decodedOffset="
            + decodedOffset
            + ", rawFlags1="
            + flags.first()
            + ", rawFlags2="
            + flags.second()
            + ", rawFlags3="
            + flags.third()
            + "]");
  }

  private static WowRootException error(WowRootException.Reason reason, String message) {
    return new WowRootException(reason, message);
  }
}
