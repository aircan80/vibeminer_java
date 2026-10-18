package io.vibeminer.infrastructure.discoveryhistory;

import io.vibeminer.application.buildlibrary.BuildReferenceGroupKey;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;

/** Strict body-free encoding of a region-independent presentation group identity. */
public final class BuildLibraryRecords {
  public static final int MAX_KEY_BYTES = 2 * 1024 * 1024;
  private static final int MAX_TEXT_BYTES = 65536;
  private static final String MAGIC = "VMGK1";

  private BuildLibraryRecords() {}

  /** Encoder admission refusal; malformed persisted records always use ordinary IAE. */
  static final class LimitException extends IllegalArgumentException {
    LimitException() {
      super("Build library key exceeds codec budget");
    }
  }

  public static byte[] key(BuildReferenceGroupKey key) {
    try {
      Objects.requireNonNull(key);
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      text(out, MAGIC);
      text(out, key.identity());
      for (var scalar :
          List.of(
              key.productId(),
              key.channel(),
              key.versionLabel(),
              key.buildConfig(),
              key.cdnConfig(),
              key.upstreamBuildId(),
              key.productConfig())) text(out, scalar);
      out.writeInt(key.additionalReferences().size());
      for (var name : new TreeSet<>(key.additionalReferences().keySet())) {
        text(out, name);
        text(out, key.additionalReferences().get(name));
        if (bytes.size() > MAX_KEY_BYTES) throw new LimitException();
      }
      if (bytes.size() > MAX_KEY_BYTES) throw new LimitException();
      return bytes.toByteArray();
    } catch (LimitException refusal) {
      throw refusal;
    } catch (IOException | RuntimeException failure) {
      throw invalid(failure);
    }
  }

  public static BuildReferenceGroupKey key(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_KEY_BYTES) throw invalid(null);
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (!MAGIC.equals(text(in))) throw invalid(null);
      var identity = text(in);
      var product = text(in);
      var channel = text(in);
      var version = text(in);
      var buildConfig = text(in);
      var cdnConfig = text(in);
      var buildId = text(in);
      var productConfig = text(in);
      int count = in.readInt();
      if (count < 0 || count > 64) throw invalid(null);
      var extra = new LinkedHashMap<String, String>();
      String previous = null;
      for (int i = 0; i < count; i++) {
        var name = text(in);
        if (previous != null && previous.compareTo(name) >= 0) throw invalid(null);
        extra.put(name, text(in));
        previous = name;
      }
      if (in.read() != -1) throw invalid(null);
      var key =
          new BuildReferenceGroupKey(
              product, channel, version, buildConfig, cdnConfig, buildId, productConfig, extra);
      if (!identity.equals(key.identity())
          || !buildConfig.equals(key.buildConfig())
          || !cdnConfig.equals(key.cdnConfig())
          || !productConfig.equals(key.productConfig())) throw invalid(null);
      return key;
    } catch (IOException | RuntimeException failure) {
      throw invalid(failure);
    }
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    var encoded =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value));
    if (encoded.remaining() > MAX_TEXT_BYTES) throw new LimitException();
    out.writeInt(encoded.remaining());
    while (encoded.hasRemaining()) out.writeByte(encoded.get());
  }

  private static String text(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 0 || size > MAX_TEXT_BYTES || size > in.available()) throw invalid(null);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(in.readNBytes(size)))
        .toString();
  }

  private static IllegalArgumentException invalid(Throwable cause) {
    return new IllegalArgumentException("Invalid bounded build library key", cause);
  }
}
