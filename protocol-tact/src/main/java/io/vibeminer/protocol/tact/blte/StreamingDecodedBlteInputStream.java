package io.vibeminer.protocol.tact.blte;

import static io.vibeminer.protocol.tact.blte.StreamingDecodedBlteException.Reason.*;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Sequential full N/Z BLTE decoding with constant-sized I/O buffers. Encoded EKey, all encoded
 * chunk hashes and physical SHA256 are verified by {@link EncodedBlteInputStream}; this layer adds
 * exact decoded chunk lengths and full CKey. Returned bytes remain provisional until EOF and {@link
 * #completionProof()}. No source authenticity, grammar or readability claim is implied.
 * Single-consumer; closing never closes the caller-owned source.
 */
public final class StreamingDecodedBlteInputStream extends InputStream {
  /**
   * Chunk sizes are long admission limits, independent of fixed I/O buffer size. Operations count
   * each caller-source read, each encoded-verifier pull, and each inflate invocation; this bounds
   * fragmented/zero-read work as well as ordinary decoding. Header/table allocation is separately
   * bounded. Accepted output cannot exceed declared per-chunk or complete decoded size. At a
   * compressed output boundary, one scratch byte may be inflated to detect an undeclared suffix;
   * that byte is never returned or accepted into the digest.
   */
  public record Limits(
      int maximumHeaderBytes,
      int maximumChunks,
      long maximumEncodedBytes,
      long maximumDecodedBytes,
      long maximumChunkEncodedBytes,
      long maximumChunkDecodedBytes,
      long maximumReadOperations,
      int ioBufferBytes) {
    public Limits {
      if (maximumHeaderBytes < 12
          || maximumChunks < 1
          || maximumEncodedBytes < 9
          || maximumDecodedBytes < 0
          || maximumChunkEncodedBytes < 1
          || maximumChunkDecodedBytes < 0
          || maximumReadOperations < 1
          || ioBufferBytes < 1
          || ioBufferBytes > 1024 * 1024)
        throw new IllegalArgumentException("Invalid streaming BLTE limits");
    }
  }

  /** Only exact source EOF and both complete hash verifiers can construct this proof. */
  public static final class CompletionProof {
    private final String contentKey;
    private final long decodedBytes;
    private final EncodedBlteInputStream.CompletionProof encodedProof;

    private CompletionProof(
        String key, long bytes, EncodedBlteInputStream.CompletionProof encoded) {
      contentKey = key;
      decodedBytes = bytes;
      encodedProof = encoded;
    }

    public String contentKey() {
      return contentKey;
    }

    public long decodedBytes() {
      return decodedBytes;
    }

    public EncodedBlteInputStream.CompletionProof encodedProof() {
      return encodedProof;
    }

    public String verifierRevision() {
      return "blte-nz-full-ckey-stream-v1";
    }
  }

  private final Engine engine;
  private int decodedOffset, decodedAvailable;

  public StreamingDecodedBlteInputStream(
      InputStream source,
      String encodingKey,
      String contentKey,
      long encodedLength,
      long decodedLength,
      Limits limits,
      BooleanSupplier cancelled)
      throws IOException {
    engine =
        new Engine(
            source, encodingKey, contentKey, encodedLength, decodedLength, limits, cancelled);
  }

  public CompletionProof completionProof() throws IOException {
    return engine.proof();
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
    try {
      engine.check();
      while (decodedOffset == decodedAvailable) {
        decodedOffset = 0;
        int produced = engine.produce(true);
        if (produced < 0) {
          decodedAvailable = 0;
          return -1;
        }
        decodedAvailable = produced;
      }
      int count = Math.min(length, decodedAvailable - decodedOffset);
      System.arraycopy(engine.output, decodedOffset, bytes, offset, count);
      decodedOffset += count;
      return count;
    } catch (IOException failure) {
      throw engine.fail(failure);
    }
  }

  @Override
  public void close() {
    engine.close();
  }

  /**
   * An encoded pull view for CAS publication. It returns the original encoded bytes while decoding
   * each bounded input block into a discarded bounded scratch buffer. Full CKey and encoded proof
   * are available only after the publication consumer has requested exact EOF. It owns neither the
   * source nor a second physical copy. Decode work is real and must be budgeted by the caller.
   */
  public static final class EncodedPublicationSource extends InputStream {
    private final Engine engine;
    private int headerOffset, blockOffset, blockLength;

    public EncodedPublicationSource(
        InputStream source,
        String encodingKey,
        String contentKey,
        long encodedLength,
        long decodedLength,
        Limits limits,
        BooleanSupplier cancelled)
        throws IOException {
      engine =
          new Engine(
              source, encodingKey, contentKey, encodedLength, decodedLength, limits, cancelled);
    }

    public CompletionProof completionProof() throws IOException {
      return engine.proof();
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
      try {
        engine.check();
        if (headerOffset < engine.header.length) {
          int count = Math.min(length, engine.header.length - headerOffset);
          System.arraycopy(engine.header, headerOffset, bytes, offset, count);
          headerOffset += count;
          return count;
        }
        if (blockOffset == blockLength) {
          if (engine.completed != null) return -1;
          if (engine.chunk == engine.encodedLengths.length) {
            engine.finish();
            return -1;
          }
          engine.load(true);
          blockOffset = 0;
          blockLength = engine.inputLength;
          while (engine.produce(false) > 0) {
            /* Decode and hash without retaining output. */
          }
        }
        int count = Math.min(length, blockLength - blockOffset);
        System.arraycopy(engine.input, blockOffset, bytes, offset, count);
        blockOffset += count;
        return count;
      } catch (IOException failure) {
        throw engine.fail(failure);
      }
    }

    @Override
    public void close() {
      engine.close();
    }
  }

  private static final class Engine {
    final EncodedBlteInputStream encoded;
    final Limits limits;
    final BooleanSupplier cancelled;
    final String expectedContentKey;
    final long expectedDecodedBytes;
    final byte[] header, input, output;
    final long[] encodedLengths, decodedLengths;
    final MessageDigest md5 = digest();
    long decodedBytes, chunkDecoded, chunkRemaining, operations;
    int chunk, inputOffset, inputLength, mode = -1;
    Inflater inflater;
    boolean closed;
    IOException failure;
    CompletionProof completed;

    Engine(
        InputStream source,
        String ekey,
        String ckey,
        long encodedLength,
        long decodedLength,
        Limits limits,
        BooleanSupplier cancelled)
        throws IOException {
      this.limits = Objects.requireNonNull(limits);
      this.cancelled = Objects.requireNonNull(cancelled);
      if (ckey == null || !ckey.matches("[0-9a-fA-F]{32}"))
        throw new IllegalArgumentException("Complete CKey required");
      expectedContentKey = ckey.toLowerCase(Locale.ROOT);
      expectedDecodedBytes = decodedLength;
      if (decodedLength < 0) throw error(MALFORMED, "Negative decoded length");
      if (decodedLength > limits.maximumDecodedBytes)
        throw error(LIMIT, "Decoded source exceeds limit");
      Objects.requireNonNull(source);
      InputStream countedSource =
          new InputStream() {
            @Override
            public int read() throws IOException {
              operation();
              int value = source.read();
              check();
              return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
              operation();
              int count = source.read(bytes, offset, length);
              check();
              return count;
            }
          };
      encoded =
          new EncodedBlteInputStream(
              countedSource,
              ekey,
              encodedLength,
              new EncodedBlteInputStream.Limits(
                  limits.maximumHeaderBytes,
                  limits.maximumChunks,
                  limits.maximumEncodedBytes,
                  limits.maximumChunkEncodedBytes),
              cancelled);
      byte[] prefix = new byte[8];
      readFully(prefix, 0, 8);
      long headerBytes = u32(prefix, 4);
      if (headerBytes == 0) {
        header = prefix;
        encodedLengths = new long[] {encodedLength - 8};
        decodedLengths = new long[] {decodedLength};
        if (decodedLength > limits.maximumChunkDecodedBytes)
          throw error(LIMIT, "Decoded chunk exceeds limit");
      } else {
        header = Arrays.copyOf(prefix, (int) headerBytes);
        readFully(header, 8, header.length - 8);
        int count = (header[9] & 255) << 16 | (header[10] & 255) << 8 | header[11] & 255;
        encodedLengths = new long[count];
        decodedLengths = new long[count];
        long decodedTotal = 0;
        for (int i = 0; i < count; i++) {
          check();
          encodedLengths[i] = u32(header, 12 + i * 24);
          decodedLengths[i] = u32(header, 16 + i * 24);
          if (decodedLengths[i] > limits.maximumChunkDecodedBytes)
            throw error(LIMIT, "Decoded chunk exceeds limit");
          try {
            decodedTotal = Math.addExact(decodedTotal, decodedLengths[i]);
          } catch (ArithmeticException overflow) {
            throw error(MALFORMED, "Decoded length overflow");
          }
          if (decodedTotal > decodedLength)
            throw error(MALFORMED, "Decoded table exceeds pinned length");
        }
        if (decodedTotal != decodedLength)
          throw error(MALFORMED, "Decoded table differs from pinned length");
      }
      input = new byte[limits.ioBufferBytes];
      output = new byte[limits.ioBufferBytes];
      chunkRemaining = encodedLengths[0];
    }

    int produce(boolean allowPull) throws IOException {
      for (; ; ) {
        check();
        if (completed != null) return -1;
        if (chunk == encodedLengths.length) {
          if (!allowPull) return 0;
          finish();
          return -1;
        }
        if (mode == -1) {
          if (inputOffset == inputLength && !load(allowPull)) return 0;
          mode = input[inputOffset++] & 255;
          if (mode == 'Z') inflater = new Inflater();
          else if (mode != 'N') throw error(UNSUPPORTED, "Unsupported BLTE chunk mode " + mode);
        }
        if (mode == 'N') {
          if (inputOffset < inputLength) {
            int count = inputLength - inputOffset;
            System.arraycopy(input, inputOffset, output, 0, count);
            inputOffset += count;
            observe(count);
            return count;
          }
          if (chunkRemaining == 0) {
            finishChunk();
            continue;
          }
          if (!load(allowPull)) return 0;
        } else {
          if (inflater.finished()) {
            if (inflater.getRemaining() != 0 || chunkRemaining != 0)
              throw error(TRAILING_BYTES, "Compressed chunk contains trailing bytes");
            finishChunk();
            continue;
          }
          if (inflater.needsDictionary()) throw error(UNSUPPORTED, "Zlib dictionary unsupported");
          if (inflater.needsInput()) {
            if (inputOffset == inputLength) {
              if (chunkRemaining == 0) throw error(TRUNCATED, "Compressed chunk did not finish");
              if (!load(allowPull)) return 0;
            }
            inflater.setInput(input, inputOffset, inputLength - inputOffset);
            inputOffset = inputLength;
          }
          operation();
          int count;
          try {
            // A one-byte overflow probe permits the zlib trailer to finish at an exact decoded
            // boundary without allocating or inflating a whole extra output block.
            long remaining =
                Math.min(decodedLengths[chunk] - chunkDecoded, expectedDecodedBytes - decodedBytes);
            int capacity = (int) Math.min(output.length, remaining == 0 ? 1 : remaining);
            count = inflater.inflate(output, 0, capacity);
          } catch (DataFormatException invalid) {
            throw new StreamingDecodedBlteException(
                INTEGRITY, "Invalid zlib stream/checksum", invalid);
          }
          check();
          if (count > 0) {
            observe(count);
            return count;
          }
          if (!inflater.finished() && !inflater.needsInput() && !inflater.needsDictionary())
            throw error(MALFORMED, "Compressed chunk made no progress");
        }
      }
    }

    boolean load(boolean allowed) throws IOException {
      if (!allowed) return false;
      if (chunkRemaining == 0) throw error(TRUNCATED, "Chunk mode/body missing");
      operation();
      int count = encoded.read(input, 0, (int) Math.min(input.length, chunkRemaining));
      check();
      if (count < 0) throw error(TRUNCATED, "Encoded chunk truncated");
      inputLength = count;
      inputOffset = 0;
      chunkRemaining -= count;
      return true;
    }

    void observe(int count) throws IOException {
      if (count > decodedLengths[chunk] - chunkDecoded
          || count > expectedDecodedBytes - decodedBytes)
        throw error(LIMIT, "Decoded output exceeds declared length");
      chunkDecoded += count;
      decodedBytes += count;
      md5.update(output, 0, count);
    }

    void finishChunk() throws IOException {
      if (chunkDecoded != decodedLengths[chunk])
        throw error(INTEGRITY, "Decoded chunk length mismatch");
      if (inflater != null) {
        inflater.end();
        inflater = null;
      }
      chunk++;
      chunkDecoded = 0;
      mode = -1;
      if (chunk < encodedLengths.length) chunkRemaining = encodedLengths[chunk];
    }

    void finish() throws IOException {
      check();
      operation();
      if (encoded.read() != -1) throw error(TRAILING_BYTES, "Encoded verifier has extra bytes");
      check();
      var encodedProof = encoded.completionProof();
      if (decodedBytes != expectedDecodedBytes)
        throw error(INTEGRITY, "Decoded source length mismatch");
      if (!HexFormat.of().formatHex(md5.digest()).equals(expectedContentKey))
        throw error(INTEGRITY, "Complete decoded CKey mismatch");
      completed = new CompletionProof(expectedContentKey, decodedBytes, encodedProof);
    }

    void readFully(byte[] bytes, int offset, int length) throws IOException {
      int total = 0;
      while (total < length) {
        check();
        operation();
        int count = encoded.read(bytes, offset + total, length - total);
        if (count < 0) throw error(TRUNCATED, "Header replay truncated");
        total += count;
      }
      check();
    }

    void operation() throws IOException {
      check();
      if (operations == limits.maximumReadOperations)
        throw error(LIMIT, "Read/inflate operation limit exceeded");
      operations++;
    }

    void check() throws IOException {
      if (failure != null) throw failure;
      if (closed) throw error(CLOSED, "Streaming decoder closed");
      if (cancelled.getAsBoolean()) throw error(CANCELLED, "Streaming decode cancelled");
    }

    CompletionProof proof() throws IOException {
      if (failure != null) throw failure;
      if (completed == null) throw error(INCOMPLETE, "Full decode requires exact source EOF");
      return completed;
    }

    IOException fail(IOException problem) {
      failure = problem;
      if (inflater != null) {
        inflater.end();
        inflater = null;
      }
      return problem;
    }

    void close() {
      closed = true;
      if (inflater != null) {
        inflater.end();
        inflater = null;
      }
      encoded.close();
    }
  }

  private static long u32(byte[] bytes, int offset) {
    long value = 0;
    for (int i = 0; i < 4; i++) value = value << 8 | bytes[offset + i] & 255;
    return value;
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static StreamingDecodedBlteException error(
      StreamingDecodedBlteException.Reason reason, String message) {
    return new StreamingDecodedBlteException(reason, message);
  }
}
