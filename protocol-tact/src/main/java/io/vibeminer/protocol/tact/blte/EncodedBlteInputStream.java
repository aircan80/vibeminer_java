package io.vibeminer.protocol.tact.blte;

import static io.vibeminer.protocol.tact.blte.EncodedBlteException.Reason.*;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Pull-through encoded-only BLTE verification for a single sequential publication pass. Constructor
 * reads/authenticates a bounded header and replays it unchanged to the caller. Completion requires
 * every encoded byte and actual source EOF. No chunk mode is interpreted; no decoded length or CKey
 * is verified. Single-consumer, not thread-safe; source remains caller-owned.
 *
 * <p>EKey hash scopes follow TACT.Net BlockTableStreamWriter at revision
 * 57618c47dc0cfef1bfc0e7585bf335ced7218389. Existing selected-chunk/decode APIs are unchanged.
 */
public final class EncodedBlteInputStream extends InputStream {
  public record Limits(
      int maximumHeaderBytes,
      int maximumChunks,
      long maximumEncodedBytes,
      long maximumChunkEncodedBytes) {
    public Limits {
      if (maximumHeaderBytes < 12
          || maximumChunks < 1
          || maximumEncodedBytes < 9
          || maximumChunkEncodedBytes < 1)
        throw new IllegalArgumentException("Invalid encoded BLTE limits");
    }
  }

  public enum Scope {
    ZERO_HEADER_WHOLE_ENCODED,
    HEADER_AND_ALL_ENCODED_CHUNKS
  }

  /** Only a completing verifier constructs a proof. No decoded capability is implied. */
  public static final class CompletionProof {
    private final String encodingKey, sha256;
    private final long length;
    private final Scope scope;

    private CompletionProof(String key, String sha256, long length, Scope scope) {
      this.encodingKey = key;
      this.sha256 = sha256;
      this.length = length;
      this.scope = scope;
    }

    public String encodingKey() {
      return encodingKey;
    }

    public String physicalSha256() {
      return sha256;
    }

    public long encodedBytes() {
      return length;
    }

    public Scope scope() {
      return scope;
    }

    public String verifierRevision() {
      return "blte-complete-encoded-v1";
    }
  }

  private final InputStream source;
  private final BooleanSupplier cancelled;
  private final long expectedLength;
  private final String expectedKey;
  private final byte[] header;
  private final long[] chunkLengths;
  private final byte[][] chunkHashes;
  private final MessageDigest sha256 = digest("SHA-256"),
      wholeMd5 = digest("MD5"),
      chunkMd5 = digest("MD5");
  private final Scope scope;
  private int replayOffset, chunkIndex;
  private long sourceBytes, chunkConsumed;
  private boolean closed;
  private IOException failure;
  private CompletionProof proof;

  public EncodedBlteInputStream(
      InputStream source,
      String expectedEncodingKey,
      long expectedEncodedBytes,
      Limits limits,
      BooleanSupplier cancelled)
      throws IOException {
    this.source = Objects.requireNonNull(source);
    this.cancelled = Objects.requireNonNull(cancelled);
    Objects.requireNonNull(limits);
    if (expectedEncodingKey == null || !expectedEncodingKey.matches("[0-9a-fA-F]{32}"))
      throw new IllegalArgumentException("Complete EKey required");
    this.expectedKey = expectedEncodingKey.toLowerCase(Locale.ROOT);
    this.expectedLength = expectedEncodedBytes;
    checkCancelled();
    if (expectedEncodedBytes < 9) throw error(MALFORMED, "BLTE object requires framing and body");
    if (expectedEncodedBytes > limits.maximumEncodedBytes)
      throw error(LIMIT, "Encoded object exceeds limit");
    byte[] prefix = new byte[8];
    readHeaderFully(prefix, 0, 8);
    if (prefix[0] != 'B' || prefix[1] != 'L' || prefix[2] != 'T' || prefix[3] != 'E')
      throw error(MALFORMED, "Invalid BLTE signature");
    long headerLength = u32(prefix, 4);
    if (headerLength == 0) {
      if (expectedEncodedBytes - 8 > limits.maximumChunkEncodedBytes) {
        throw error(LIMIT, "Zero-header encoded chunk exceeds limit");
      }
      header = prefix;
      chunkLengths = new long[0];
      chunkHashes = new byte[0][];
      scope = Scope.ZERO_HEADER_WHOLE_ENCODED;
      wholeMd5.update(header);
    } else {
      if (headerLength < 12 || headerLength > expectedEncodedBytes)
        throw error(MALFORMED, "Invalid BLTE header length");
      if (headerLength > limits.maximumHeaderBytes) throw error(LIMIT, "BLTE header exceeds limit");
      header = Arrays.copyOf(prefix, (int) headerLength);
      readHeaderFully(header, 8, header.length - 8);
      if (!HexFormat.of().formatHex(digest("MD5").digest(header)).equals(expectedKey))
        throw error(INTEGRITY, "BLTE header EKey mismatch");
      if (header[8] != 15) throw error(UNSUPPORTED, "Unsupported BLTE chunk table flags");
      int count = (header[9] & 255) << 16 | (header[10] & 255) << 8 | (header[11] & 255);
      if (count < 1 || 12L + count * 24L != headerLength)
        throw error(MALFORMED, "BLTE chunk count/header geometry mismatch");
      if (count > limits.maximumChunks) throw error(LIMIT, "BLTE chunk count exceeds limit");
      chunkLengths = new long[count];
      chunkHashes = new byte[count][];
      long total = headerLength;
      for (int i = 0; i < count; i++) {
        checkCancelled();
        int at = 12 + i * 24;
        long length = u32(header, at);
        if (length < 1) throw error(MALFORMED, "Empty encoded BLTE chunk");
        if (length > limits.maximumChunkEncodedBytes)
          throw error(LIMIT, "Encoded chunk exceeds limit");
        try {
          total = Math.addExact(total, length);
        } catch (ArithmeticException overflow) {
          throw error(MALFORMED, "Encoded chunk total overflows");
        }
        if (total > expectedEncodedBytes)
          throw error(MALFORMED, "Encoded chunks exceed pinned object length");
        chunkLengths[i] = length;
        chunkHashes[i] = Arrays.copyOfRange(header, at + 8, at + 24);
      }
      if (total != expectedEncodedBytes)
        throw error(MALFORMED, "Encoded chunk total differs from pinned length");
      scope = Scope.HEADER_AND_ALL_ENCODED_CHUNKS;
    }
    sha256.update(header);
  }

  /** Throws INCOMPLETE until an actual EOF read has completed all verification. */
  public CompletionProof completionProof() throws IOException {
    if (failure != null) throw failure;
    if (proof == null) throw error(INCOMPLETE, "Complete encoded verification requires source EOF");
    return proof;
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    if (length == 0) return 0;
    if (failure != null) throw failure;
    if (closed) throw error(CLOSED, "Encoded verifier is closed");
    try {
      checkCancelled();
      if (proof != null) return -1;
      if (replayOffset < header.length) {
        int count = Math.min(length, header.length - replayOffset);
        System.arraycopy(header, replayOffset, bytes, offset, count);
        replayOffset += count;
        return count;
      }
      if (sourceBytes == expectedLength) {
        finishAtSourceEof();
        return -1;
      }
      int count = (int) Math.min(Math.min(length, 8192), expectedLength - sourceBytes);
      if (scope == Scope.HEADER_AND_ALL_ENCODED_CHUNKS)
        count = (int) Math.min(count, chunkLengths[chunkIndex] - chunkConsumed);
      int received = readSource(bytes, offset, count);
      if (received < 0) throw error(TRUNCATED, "BLTE body is truncated");
      sourceBytes += received;
      sha256.update(bytes, offset, received);
      if (scope == Scope.ZERO_HEADER_WHOLE_ENCODED) wholeMd5.update(bytes, offset, received);
      else {
        chunkMd5.update(bytes, offset, received);
        chunkConsumed += received;
        if (chunkConsumed == chunkLengths[chunkIndex]) {
          if (!MessageDigest.isEqual(chunkMd5.digest(), chunkHashes[chunkIndex]))
            throw error(INTEGRITY, "Encoded BLTE chunk MD5 mismatch");
          chunkIndex++;
          chunkConsumed = 0;
        }
      }
      return received;
    } catch (IOException failed) {
      failure = failed;
      throw failed;
    }
  }

  private void finishAtSourceEof() throws IOException {
    checkCancelled();
    if (source.read() != -1) throw error(TRAILING_BYTES, "Encoded source has trailing bytes");
    checkCancelled();
    if (scope == Scope.ZERO_HEADER_WHOLE_ENCODED
        && !HexFormat.of().formatHex(wholeMd5.digest()).equals(expectedKey))
      throw error(INTEGRITY, "Whole encoded zero-header EKey mismatch");
    if (scope == Scope.HEADER_AND_ALL_ENCODED_CHUNKS && chunkIndex != chunkLengths.length)
      throw error(TRUNCATED, "Not all encoded chunks completed");
    proof =
        new CompletionProof(
            expectedKey, HexFormat.of().formatHex(sha256.digest()), expectedLength, scope);
  }

  private void readHeaderFully(byte[] bytes, int offset, int length) throws IOException {
    int read = 0;
    while (read < length) {
      checkCancelled();
      int count = readSource(bytes, offset + read, Math.min(8192, length - read));
      if (count < 0) throw error(TRUNCATED, "BLTE header is truncated");
      read += count;
      sourceBytes += count;
    }
  }

  private int readSource(byte[] bytes, int offset, int length) throws IOException {
    checkCancelled();
    int count = source.read(bytes, offset, length);
    if (count != 0) return count;
    checkCancelled();
    int single = source.read();
    if (single < 0) return -1;
    bytes[offset] = (byte) single;
    return 1;
  }

  /** Closing this wrapper never closes its caller-owned source and never completes verification. */
  @Override
  public void close() {
    closed = true;
  }

  private void checkCancelled() throws EncodedBlteException {
    if (cancelled.getAsBoolean()) throw error(CANCELLED, "Encoded verification cancelled");
  }

  private static long u32(byte[] bytes, int offset) {
    long value = 0;
    for (int i = 0; i < 4; i++) value = (value << 8) | (bytes[offset + i] & 255);
    return value;
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static EncodedBlteException error(EncodedBlteException.Reason reason, String message) {
    return new EncodedBlteException(reason, message);
  }
}
