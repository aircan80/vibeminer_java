package io.vibeminer.protocol.tact.blte;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

class StreamingDecodedBlteInputStreamTest {
  private static final StreamingDecodedBlteInputStream.Limits LIMITS =
      new StreamingDecodedBlteInputStream.Limits(
          4096, 64, 1L << 30, 1L << 30, 1L << 30, 1L << 30, 1_000_000, 1024);

  @Test
  void mixedChunksCompleteOnlyAfterExactEof() throws Exception {
    byte[] a = "plain content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] b =
        "compressed content compressed content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] encoded = fixture(new byte[][] {a, b}, new char[] {'N', 'Z'});
    byte[] expected = join(a, b);
    try (var stream = open(encoded, expected)) {
      assertThrows(IOException.class, stream::completionProof);
      assertArrayEquals(expected, stream.readAllBytes());
      assertEquals(-1, stream.read());
      assertEquals(-1, stream.read(new byte[3]));
      assertEquals(md5(expected), stream.completionProof().contentKey());
      assertEquals(expected.length, stream.completionProof().decodedBytes());
      assertEquals(encoded.length, stream.completionProof().encodedProof().encodedBytes());
    }
  }

  @Test
  void fragmentedAndZeroReadsPreserveDecodedBytesAndSourceOwnership() throws Exception {
    byte[] expected = new byte[20_000];
    new java.util.Random(42).nextBytes(expected);
    byte[] bytes = fixture(new byte[][] {expected}, new char[] {'Z'});
    var closed = new java.util.concurrent.atomic.AtomicBoolean();
    InputStream source =
        new ByteArrayInputStream(bytes) {
          int calls;

          @Override
          public synchronized int read(byte[] b, int o, int n) {
            if (++calls % 3 == 0) return 0;
            return super.read(b, o, Math.min(3, n));
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    try (var stream =
        new StreamingDecodedBlteInputStream(
            source,
            key(bytes),
            md5(expected),
            bytes.length,
            expected.length,
            LIMITS,
            () -> false)) {
      assertArrayEquals(expected, stream.readAllBytes());
      assertEquals(expected.length, stream.completionProof().decodedBytes());
    }
    assertFalse(closed.get());
  }

  @Test
  void earlyCloseAndCancellationCannotComplete() throws Exception {
    byte[] expected = new byte[10_000];
    byte[] bytes = fixture(new byte[][] {expected}, new char[] {'Z'});
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    try (var stream =
        new StreamingDecodedBlteInputStream(
            new ByteArrayInputStream(bytes),
            key(bytes),
            md5(expected),
            bytes.length,
            expected.length,
            LIMITS,
            cancelled::get)) {
      assertEquals(0, stream.read());
      cancelled.set(true);
      assertEquals(
          StreamingDecodedBlteException.Reason.CANCELLED,
          assertThrows(StreamingDecodedBlteException.class, stream::read).reason());
      assertThrows(IOException.class, stream::completionProof);
    }
    var stream = open(bytes, expected);
    stream.read();
    stream.close();
    assertEquals(
        StreamingDecodedBlteException.Reason.INCOMPLETE,
        assertThrows(StreamingDecodedBlteException.class, stream::completionProof).reason());
  }

  @Test
  void everyTruncatedPrefixAndExtraSourceByteReject() throws Exception {
    byte[] expected = "test compression and identity".getBytes();
    byte[] bytes = fixture(new byte[][] {expected}, new char[] {'Z'});
    for (int n = 0; n < bytes.length; n++) {
      byte[] prefix = java.util.Arrays.copyOf(bytes, n);
      assertThrows(
          IOException.class,
          () -> {
            try (var stream =
                new StreamingDecodedBlteInputStream(
                    new ByteArrayInputStream(prefix),
                    key(bytes),
                    md5(expected),
                    bytes.length,
                    expected.length,
                    LIMITS,
                    () -> false)) {
              stream.transferTo(OutputStream.nullOutputStream());
            }
          },
          "prefix " + n);
    }
    try (var stream =
        new StreamingDecodedBlteInputStream(
            new ByteArrayInputStream(join(bytes, new byte[1])),
            key(bytes),
            md5(expected),
            bytes.length,
            expected.length,
            LIMITS,
            () -> false)) {
      assertThrows(IOException.class, () -> stream.transferTo(OutputStream.nullOutputStream()));
      assertThrows(IOException.class, stream::completionProof);
    }
  }

  @Test
  void authenticatedUnsupportedModesAndCompressedTailReject() throws Exception {
    byte[] expected = {1, 2, 3};
    for (char mode : new char[] {'E', 'F', 'Q'}) {
      byte[] bytes = fixture(new byte[][] {expected}, new char[] {mode});
      try (var stream = open(bytes, expected)) {
        assertEquals(
            StreamingDecodedBlteException.Reason.UNSUPPORTED,
            assertThrows(StreamingDecodedBlteException.class, stream::read).reason());
      }
    }
    byte[] bytes = fixture(new byte[][] {expected}, new char[] {'Z'});
    byte[] tail = java.util.Arrays.copyOf(bytes, bytes.length + 1);
    java.nio.ByteBuffer.wrap(tail).putInt(12, bytes.length - 36 + 1);
    byte[] hash =
        MessageDigest.getInstance("MD5")
            .digest(java.util.Arrays.copyOfRange(tail, 36, tail.length));
    System.arraycopy(hash, 0, tail, 20, 16);
    try (var stream = open(tail, expected)) {
      assertEquals(
          StreamingDecodedBlteException.Reason.TRAILING_BYTES,
          assertThrows(
                  StreamingDecodedBlteException.class,
                  () -> stream.transferTo(OutputStream.nullOutputStream()))
              .reason());
    }
  }

  @Test
  void ckeyMismatchLateChunkCorruptionAndZlibChecksumReject() throws Exception {
    byte[] expected = {1, 2, 3};
    byte[] bytes = fixture(new byte[][] {expected}, new char[] {'Z'});
    try (var stream =
        new StreamingDecodedBlteInputStream(
            new ByteArrayInputStream(bytes),
            key(bytes),
            "00000000000000000000000000000000",
            bytes.length,
            expected.length,
            LIMITS,
            () -> false)) {
      assertThrows(IOException.class, () -> stream.transferTo(OutputStream.nullOutputStream()));
      assertThrows(IOException.class, stream::completionProof);
    }
    byte[] corrupt = bytes.clone();
    corrupt[corrupt.length - 1] ^= 1;
    try (var stream = open(corrupt, expected)) {
      assertThrows(IOException.class, () -> stream.transferTo(OutputStream.nullOutputStream()));
    }
    byte[] hash =
        MessageDigest.getInstance("MD5")
            .digest(java.util.Arrays.copyOfRange(corrupt, 36, corrupt.length));
    System.arraycopy(hash, 0, corrupt, 20, 16);
    try (var stream = open(corrupt, expected)) {
      assertEquals(
          StreamingDecodedBlteException.Reason.INTEGRITY,
          assertThrows(
                  StreamingDecodedBlteException.class,
                  () -> stream.transferTo(OutputStream.nullOutputStream()))
              .reason());
    }
  }

  @Test
  void preflightLengthsAndInflationBombAndWorkAreBounded() throws Exception {
    byte[] expected = new byte[100_000];
    byte[] bytes = fixture(new byte[][] {expected}, new char[] {'Z'});
    var small =
        new StreamingDecodedBlteInputStream.Limits(
            4096, 64, 1L << 30, 1L << 30, 1L << 30, 99_999, 1_000_000, 1024);
    assertEquals(
        StreamingDecodedBlteException.Reason.LIMIT,
        assertThrows(
                StreamingDecodedBlteException.class,
                () ->
                    new StreamingDecodedBlteInputStream(
                        new ByteArrayInputStream(bytes),
                        key(bytes),
                        md5(expected),
                        bytes.length,
                        expected.length,
                        small,
                        () -> false))
            .reason());
    byte[] lie = bytes.clone();
    java.nio.ByteBuffer.wrap(lie).putInt(16, 1);
    try (var stream =
        new StreamingDecodedBlteInputStream(
            new ByteArrayInputStream(lie),
            key(lie),
            md5(expected),
            lie.length,
            1,
            LIMITS,
            () -> false)) {
      assertEquals(0, stream.read());
      assertEquals(
          StreamingDecodedBlteException.Reason.LIMIT,
          assertThrows(StreamingDecodedBlteException.class, stream::read).reason());
    }
    var work =
        new StreamingDecodedBlteInputStream.Limits(
            4096, 64, 1L << 30, 1L << 30, 1L << 30, 1L << 30, 5, 1024);
    try (var stream =
        new StreamingDecodedBlteInputStream(
            new ByteArrayInputStream(bytes),
            key(bytes),
            md5(expected),
            bytes.length,
            expected.length,
            work,
            () -> false)) {
      assertEquals(
          StreamingDecodedBlteException.Reason.LIMIT,
          assertThrows(StreamingDecodedBlteException.class, stream::read).reason());
    }
  }

  @Test
  void encodedPublicationPreservesExactBytesWhileRequiringFullDecode() throws Exception {
    byte[] a = new byte[20_000];
    new java.util.Random(11).nextBytes(a);
    byte[] b = new byte[35_000];
    byte[] bytes = fixture(new byte[][] {a, b}, new char[] {'N', 'Z'});
    try (var stream =
        new StreamingDecodedBlteInputStream.EncodedPublicationSource(
            new ByteArrayInputStream(bytes),
            key(bytes),
            md5(join(a, b)),
            bytes.length,
            a.length + b.length,
            LIMITS,
            () -> false)) {
      assertThrows(IOException.class, stream::completionProof);
      var copy = new ByteArrayOutputStream();
      byte[] buffer = new byte[7];
      int count;
      while ((count = stream.read(buffer)) != -1) copy.write(buffer, 0, count);
      assertArrayEquals(bytes, copy.toByteArray());
      assertEquals(md5(join(a, b)), stream.completionProof().contentKey());
      assertEquals(
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
          stream.completionProof().encodedProof().physicalSha256());
    }
    try (var stream =
        new StreamingDecodedBlteInputStream.EncodedPublicationSource(
            new ByteArrayInputStream(bytes),
            key(bytes),
            "00000000000000000000000000000000",
            bytes.length,
            a.length + b.length,
            LIMITS,
            () -> false)) {
      assertThrows(IOException.class, () -> stream.transferTo(OutputStream.nullOutputStream()));
      assertThrows(IOException.class, stream::completionProof);
    }
  }

  @Test
  void zeroHeaderNAndZUseWholeEncodedEkeyAndFullCkey() throws Exception {
    byte[] expected = "zero header payload".getBytes();
    for (char mode : new char[] {'N', 'Z'}) {
      byte[] full = fixture(new byte[][] {expected}, new char[] {mode});
      byte[] zero =
          join(
              new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0},
              java.util.Arrays.copyOfRange(full, 36, full.length));
      try (var stream =
          new StreamingDecodedBlteInputStream(
              new ByteArrayInputStream(zero),
              md5(zero),
              md5(expected),
              zero.length,
              expected.length,
              LIMITS,
              () -> false)) {
        assertArrayEquals(expected, stream.readAllBytes());
        assertEquals(
            EncodedBlteInputStream.Scope.ZERO_HEADER_WHOLE_ENCODED,
            stream.completionProof().encodedProof().scope());
      }
      try (var stream =
          new StreamingDecodedBlteInputStream.EncodedPublicationSource(
              new ByteArrayInputStream(zero),
              md5(zero),
              md5(expected),
              zero.length,
              expected.length,
              LIMITS,
              () -> false)) {
        assertArrayEquals(zero, stream.readAllBytes());
        assertEquals(expected.length, stream.completionProof().decodedBytes());
      }
    }
  }

  @Test
  void generatedLargeChunkUsesOnlyBoundedReadsInBothViews() throws Exception {
    int payloadLength = 32 * 1024 * 1024;
    byte[] block = new byte[8192];
    java.util.Arrays.fill(block, (byte) 7);
    var chunkHash = MessageDigest.getInstance("MD5");
    chunkHash.update((byte) 'N');
    var contentHash = MessageDigest.getInstance("MD5");
    for (int n = 0; n < payloadLength; n += block.length) {
      chunkHash.update(block);
      contentHash.update(block);
    }
    var headerBytes = new ByteArrayOutputStream();
    var header = new DataOutputStream(headerBytes);
    header.writeBytes("BLTE");
    header.writeInt(36);
    header.writeByte(15);
    header.writeByte(0);
    header.writeShort(1);
    header.writeInt(payloadLength + 1);
    header.writeInt(payloadLength);
    header.write(chunkHash.digest());
    byte[] prefix = headerBytes.toByteArray();
    String ekey = md5(prefix), ckey = HexFormat.of().formatHex(contentHash.digest());
    for (boolean publication : new boolean[] {false, true}) {
      var source =
          new InputStream() {
            long at;
            int maximumRequest;

            @Override
            public int read() {
              return at >= prefix.length + 1L + payloadLength ? -1 : next();
            }

            private int next() {
              int value =
                  at < prefix.length ? prefix[(int) at] & 255 : at == prefix.length ? 'N' : 7;
              at++;
              return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
              maximumRequest = Math.max(maximumRequest, length);
              if (at >= prefix.length + 1L + payloadLength) return -1;
              int count = (int) Math.min(length, prefix.length + 1L + payloadLength - at);
              for (int i = 0; i < count; i++) bytes[offset + i] = (byte) next();
              return count;
            }
          };
      InputStream stream =
          publication
              ? new StreamingDecodedBlteInputStream.EncodedPublicationSource(
                  source,
                  ekey,
                  ckey,
                  prefix.length + 1L + payloadLength,
                  payloadLength,
                  LIMITS,
                  () -> false)
              : new StreamingDecodedBlteInputStream(
                  source,
                  ekey,
                  ckey,
                  prefix.length + 1L + payloadLength,
                  payloadLength,
                  LIMITS,
                  () -> false);
      try (stream) {
        assertEquals(
            publication ? prefix.length + 1L + payloadLength : payloadLength,
            stream.transferTo(OutputStream.nullOutputStream()));
        assertTrue(source.maximumRequest <= LIMITS.ioBufferBytes());
        var proof =
            publication
                ? ((StreamingDecodedBlteInputStream.EncodedPublicationSource) stream)
                    .completionProof()
                : ((StreamingDecodedBlteInputStream) stream).completionProof();
        assertEquals(ckey, proof.contentKey());
      }
    }
  }

  @Test
  void sourceFailureIsPreservedAndCancellationAfterEofProbePreventsProof() throws Exception {
    byte[] expected = new byte[3000];
    byte[] bytes = fixture(new byte[][] {expected}, new char[] {'N'});
    var failure = new IOException("authored IO failure");
    InputStream source =
        new ByteArrayInputStream(bytes) {
          @Override
          public synchronized int read(byte[] b, int o, int n) throws RuntimeException {
            if (pos >= 36) throw new java.io.UncheckedIOException(failure);
            return super.read(b, o, n);
          }
        };
    // Checked I/O identity is tested with an adapter rather than ByteArrayInputStream's narrowed
    // signature.
    InputStream checked =
        new InputStream() {
          final InputStream delegate = source;

          @Override
          public int read() throws IOException {
            return delegate.read();
          }

          @Override
          public int read(byte[] b, int o, int n) throws IOException {
            try {
              return delegate.read(b, o, n);
            } catch (java.io.UncheckedIOException e) {
              throw e.getCause();
            }
          }
        };
    try (var stream =
        new StreamingDecodedBlteInputStream(
            checked,
            key(bytes),
            md5(expected),
            bytes.length,
            expected.length,
            LIMITS,
            () -> false)) {
      assertSame(failure, assertThrows(IOException.class, stream::read));
      assertSame(failure, assertThrows(IOException.class, stream::completionProof));
    }
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    InputStream eofCancels =
        new ByteArrayInputStream(bytes) {
          @Override
          public synchronized int read() {
            int value = super.read();
            if (value < 0) cancelled.set(true);
            return value;
          }
        };
    try (var stream =
        new StreamingDecodedBlteInputStream(
            eofCancels,
            key(bytes),
            md5(expected),
            bytes.length,
            expected.length,
            LIMITS,
            cancelled::get)) {
      assertThrows(IOException.class, () -> stream.transferTo(OutputStream.nullOutputStream()));
      assertThrows(IOException.class, stream::completionProof);
    }
  }

  @Test
  void allAuthenticatedCompressedTruncationsAndEmptyChunksAreStrict() throws Exception {
    byte[] expected = new byte[1024];
    byte[] original = fixture(new byte[][] {expected}, new char[] {'Z'});
    for (int length = 1; length < original.length - 36; length++) {
      byte[] shortZ = java.util.Arrays.copyOf(original, 36 + length);
      java.nio.ByteBuffer.wrap(shortZ).putInt(12, length);
      byte[] hash =
          MessageDigest.getInstance("MD5")
              .digest(java.util.Arrays.copyOfRange(shortZ, 36, shortZ.length));
      System.arraycopy(hash, 0, shortZ, 20, 16);
      try (var stream = open(shortZ, expected)) {
        assertThrows(IOException.class, () -> stream.transferTo(OutputStream.nullOutputStream()));
        assertThrows(IOException.class, stream::completionProof);
      }
    }
    byte[] empty =
        fixture(new byte[][] {new byte[0], new byte[0], expected}, new char[] {'N', 'Z', 'N'});
    try (var stream = open(empty, expected)) {
      assertArrayEquals(expected, stream.readAllBytes());
      assertEquals(expected.length, stream.completionProof().decodedBytes());
    }
  }

  private static String key(byte[] bytes) throws Exception {
    int header = java.nio.ByteBuffer.wrap(bytes).getInt(4);
    return md5(header == 0 ? bytes : java.util.Arrays.copyOf(bytes, header));
  }

  private static StreamingDecodedBlteInputStream open(byte[] bytes, byte[] decoded)
      throws Exception {
    return new StreamingDecodedBlteInputStream(
        new ByteArrayInputStream(bytes),
        md5(
            java.util.Arrays.copyOf(
                bytes,
                12 + 24 * ((bytes[9] & 255) << 16 | (bytes[10] & 255) << 8 | bytes[11] & 255))),
        md5(decoded),
        bytes.length,
        decoded.length,
        LIMITS,
        () -> false);
  }

  static byte[] fixture(byte[][] decoded, char[] modes) throws Exception {
    byte[][] chunks = new byte[decoded.length][];
    for (int i = 0; i < chunks.length; i++) {
      var data = new ByteArrayOutputStream();
      data.write(modes[i]);
      if (modes[i] == 'Z') {
        try (var z = new DeflaterOutputStream(data)) {
          z.write(decoded[i]);
        }
      } else data.write(decoded[i]);
      chunks[i] = data.toByteArray();
    }
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("BLTE");
    out.writeInt(12 + chunks.length * 24);
    out.writeByte(15);
    out.writeByte(chunks.length >>> 16);
    out.writeShort(chunks.length);
    for (int i = 0; i < chunks.length; i++) {
      out.writeInt(chunks[i].length);
      out.writeInt(decoded[i].length);
      out.write(MessageDigest.getInstance("MD5").digest(chunks[i]));
    }
    for (byte[] chunk : chunks) out.write(chunk);
    return bytes.toByteArray();
  }

  static String md5(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
  }

  static byte[] join(byte[] a, byte[] b) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(a);
    out.write(b);
    return out.toByteArray();
  }
}
