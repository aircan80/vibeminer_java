package io.vibeminer.protocol.tact.blte;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class EncodedBlteInputStreamTest {
  final EncodedBlteInputStream.Limits limits =
      new EncodedBlteInputStream.Limits(4096, 100, 100000, 100000);

  static byte[] md5(byte[] bytes) throws Exception {
    return MessageDigest.getInstance("MD5").digest(bytes);
  }

  static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  static byte[] zero() {
    return new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'E', 1, 2, 3};
  }

  static byte[] chunked(byte[]... chunks) throws Exception {
    int h = 12 + 24 * chunks.length;
    var out = new ByteArrayOutputStream();
    var b = ByteBuffer.allocate(h);
    b.put(new byte[] {'B', 'L', 'T', 'E'})
        .putInt(h)
        .put((byte) 15)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) chunks.length);
    for (byte[] chunk : chunks) b.putInt(chunk.length).putInt(999).put(md5(chunk));
    out.write(b.array());
    for (byte[] chunk : chunks) out.write(chunk);
    return out.toByteArray();
  }

  static String chunkKey(byte[] bytes) throws Exception {
    return hex(md5(java.util.Arrays.copyOf(bytes, ByteBuffer.wrap(bytes).getInt(4))));
  }

  @Test
  void zeroHeaderNeedsExactSourceEofAndReplaysBytes() throws Exception {
    byte[] bytes = zero();
    var stream =
        new EncodedBlteInputStream(
            new ByteArrayInputStream(bytes), hex(md5(bytes)), bytes.length, limits, () -> false);
    assertThrows(EncodedBlteException.class, stream::completionProof);
    assertArrayEquals(bytes, stream.readNBytes(bytes.length));
    assertThrows(EncodedBlteException.class, stream::completionProof);
    assertEquals(-1, stream.read());
    var proof = stream.completionProof();
    assertEquals(EncodedBlteInputStream.Scope.ZERO_HEADER_WHOLE_ENCODED, proof.scope());
    assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(bytes)), proof.physicalSha256());
  }

  @Test
  void opaqueChunksVerifiedWithoutDecodingAndShortReadsWork() throws Exception {
    byte[] bytes = chunked(new byte[] {'E', 1, 2}, new byte[] {'F'}, new byte[] {'?', 4, 5, 6});
    var source =
        new FilterInputStream(new ByteArrayInputStream(bytes)) {
          public int read(byte[] b, int o, int l) throws IOException {
            return super.read(b, o, Math.min(l, 1));
          }
        };
    var stream =
        new EncodedBlteInputStream(source, chunkKey(bytes), bytes.length, limits, () -> false);
    assertArrayEquals(bytes, stream.readAllBytes());
    assertEquals(
        EncodedBlteInputStream.Scope.HEADER_AND_ALL_ENCODED_CHUNKS,
        stream.completionProof().scope());
  }

  @Test
  void corruptionTrailingTruncationCancellationNeverComplete() throws Exception {
    byte[] bytes = chunked(new byte[] {'E', 1, 2});
    String key = chunkKey(bytes);
    bytes[bytes.length - 1] ^= 1;
    var bad =
        new EncodedBlteInputStream(
            new ByteArrayInputStream(bytes), key, bytes.length, limits, () -> false);
    assertThrows(EncodedBlteException.class, bad::readAllBytes);
    assertThrows(EncodedBlteException.class, bad::completionProof);
    byte[] z = zero();
    var extra =
        new EncodedBlteInputStream(
            new ByteArrayInputStream(java.util.Arrays.copyOf(z, z.length + 1)),
            hex(md5(z)),
            z.length,
            limits,
            () -> false);
    assertThrows(EncodedBlteException.class, extra::readAllBytes);
    var shortStream =
        new EncodedBlteInputStream(
            new ByteArrayInputStream(java.util.Arrays.copyOf(z, z.length - 1)),
            hex(md5(z)),
            z.length,
            limits,
            () -> false);
    assertThrows(EncodedBlteException.class, shortStream::readAllBytes);
    assertThrows(
        EncodedBlteException.class,
        () ->
            new EncodedBlteInputStream(
                new ByteArrayInputStream(z), hex(md5(z)), z.length, limits, () -> true));
  }

  @Test
  void malformedFramingHeaderIdentityAndLimitsFailBeforeBody() throws Exception {
    byte[] valid = chunked(new byte[] {'?', 1});
    assertThrows(
        EncodedBlteException.class,
        () ->
            new EncodedBlteInputStream(
                new ByteArrayInputStream(valid),
                "0".repeat(32),
                valid.length,
                limits,
                () -> false));
    byte[] geometry = valid.clone();
    geometry[11] = 2;
    assertEquals(
        EncodedBlteException.Reason.MALFORMED,
        assertThrows(
                EncodedBlteException.class,
                () ->
                    new EncodedBlteInputStream(
                        new ByteArrayInputStream(geometry),
                        chunkKey(geometry),
                        geometry.length,
                        limits,
                        () -> false))
            .reason());
    byte[] unsupported = valid.clone();
    unsupported[8] = 14;
    assertEquals(
        EncodedBlteException.Reason.UNSUPPORTED,
        assertThrows(
                EncodedBlteException.class,
                () ->
                    new EncodedBlteInputStream(
                        new ByteArrayInputStream(unsupported),
                        chunkKey(unsupported),
                        unsupported.length,
                        limits,
                        () -> false))
            .reason());
    assertEquals(
        EncodedBlteException.Reason.LIMIT,
        assertThrows(
                EncodedBlteException.class,
                () ->
                    new EncodedBlteInputStream(
                        new ByteArrayInputStream(valid),
                        chunkKey(valid),
                        valid.length,
                        new EncodedBlteInputStream.Limits(12, 1, 1000, 1000),
                        () -> false))
            .reason());
    assertThrows(
        EncodedBlteException.class,
        () ->
            new EncodedBlteInputStream(
                new ByteArrayInputStream(valid),
                chunkKey(valid),
                valid.length + 1,
                limits,
                () -> false));
    byte[] empty = valid.clone();
    ByteBuffer.wrap(empty).putInt(12, 0);
    assertThrows(
        EncodedBlteException.class,
        () ->
            new EncodedBlteInputStream(
                new ByteArrayInputStream(empty),
                chunkKey(empty),
                empty.length,
                limits,
                () -> false));
  }

  @Test
  void lateCancellationAndCallerOwnedSourceClose() throws Exception {
    byte[] bytes = zero();
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var sourceClosed = new java.util.concurrent.atomic.AtomicBoolean();
    var source =
        new ByteArrayInputStream(bytes) {
          public void close() {
            sourceClosed.set(true);
          }
        };
    var stream =
        new EncodedBlteInputStream(source, hex(md5(bytes)), bytes.length, limits, cancelled::get);
    assertEquals(8, stream.read(new byte[30]));
    cancelled.set(true);
    assertEquals(
        EncodedBlteException.Reason.CANCELLED,
        assertThrows(EncodedBlteException.class, stream::read).reason());
    cancelled.set(false);
    assertThrows(EncodedBlteException.class, stream::readAllBytes);
    stream.close();
    assertFalse(sourceClosed.get());
    assertThrows(EncodedBlteException.class, stream::completionProof);
  }

  @Test
  void generatedLargeObjectUsesOnePassAndBoundedReadsWithoutChunkBuffer() throws Exception {
    byte[] prefix = {'B', 'L', 'T', 'E', 0, 0, 0, 0};
    int total = 2 * 1024 * 1024 + 8;
    var digest = MessageDigest.getInstance("MD5");
    digest.update(prefix);
    byte[] block = new byte[8192];
    java.util.Arrays.fill(block, (byte) 0xa5);
    for (int n = 8; n < total; n += block.length)
      digest.update(block, 0, Math.min(block.length, total - n));
    var maxRead = new java.util.concurrent.atomic.AtomicInteger();
    var position = new java.util.concurrent.atomic.AtomicInteger();
    InputStream source =
        new InputStream() {
          public int read() {
            int at = position.getAndIncrement();
            return at >= total ? -1 : at < 8 ? prefix[at] & 255 : 0xa5;
          }

          public int read(byte[] bytes, int offset, int length) {
            assertTrue(length <= 8192);
            maxRead.accumulateAndGet(length, Math::max);
            if (position.get() >= total) return -1;
            int count = Math.min(length, total - position.get());
            for (int i = 0; i < count; i++) bytes[offset + i] = (byte) read();
            return count;
          }
        };
    var stream =
        new EncodedBlteInputStream(
            source,
            hex(digest.digest()),
            total,
            new EncodedBlteInputStream.Limits(4096, 100, total, total),
            () -> false);
    byte[] output = new byte[65536];
    long consumed = 0;
    for (int n; (n = stream.read(output)) != -1; ) consumed += n;
    assertEquals(total, consumed);
    assertEquals(total, stream.completionProof().encodedBytes());
    assertEquals(8192, maxRead.get());
    assertEquals(total + 1, position.get());
  }

  @Test
  void zeroReturnReadFallbackAndWrongZeroHeaderDigestCannotComplete() throws Exception {
    byte[] bytes = zero();
    var source =
        new FilterInputStream(new ByteArrayInputStream(bytes)) {
          public int read(byte[] bytes, int offset, int length) {
            return 0;
          }
        };
    var stream =
        new EncodedBlteInputStream(source, hex(md5(bytes)), bytes.length, limits, () -> false);
    assertArrayEquals(bytes, stream.readAllBytes());
    assertNotNull(stream.completionProof());
    var wrong =
        new EncodedBlteInputStream(
            new ByteArrayInputStream(bytes), "0".repeat(32), bytes.length, limits, () -> false);
    assertEquals(
        EncodedBlteException.Reason.INTEGRITY,
        assertThrows(EncodedBlteException.class, wrong::readAllBytes).reason());
    assertThrows(EncodedBlteException.class, wrong::completionProof);
  }

  @Test
  void zeroHeaderSoleChunkHonorsEncodedChunkLimitBeforeBody() throws Exception {
    byte[] bytes = new byte[100];
    System.arraycopy(new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0}, 0, bytes, 0, 8);
    var source = new ByteArrayInputStream(bytes);
    var failure =
        assertThrows(
            EncodedBlteException.class,
            () ->
                new EncodedBlteInputStream(
                    source,
                    hex(md5(bytes)),
                    bytes.length,
                    new EncodedBlteInputStream.Limits(12, 1, 100, 1),
                    () -> false));
    assertEquals(EncodedBlteException.Reason.LIMIT, failure.reason());
    assertEquals(92, source.available(), "No oversized sole-chunk body should be consumed");
  }
}
