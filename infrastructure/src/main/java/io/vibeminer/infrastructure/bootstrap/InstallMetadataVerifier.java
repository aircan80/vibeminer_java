package io.vibeminer.infrastructure.bootstrap;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.protocol.tact.blte.*;
import io.vibeminer.protocol.tact.install.*;
import java.util.HexFormat;

/** Shared bounded actual-tuple verifier; no configuration, transport, storage or publication. */
public final class InstallMetadataVerifier {
  public static final int MAXIMUM_ENCODED_BYTES = 4 * 1024 * 1024;
  public static final int MAXIMUM_DECODED_BYTES = 16 * 1024 * 1024;
  private static final BlteDecoder.Limits BLTE_LIMITS =
      new BlteDecoder.Limits(MAXIMUM_ENCODED_BYTES, MAXIMUM_DECODED_BYTES, 4096);
  private static final InstallManifestParser.Limits INSTALL_LIMITS =
      new InstallManifestParser.Limits(MAXIMUM_DECODED_BYTES, 50000, 512, 16384);

  public InstallManifest decodeAndParse(
      byte[] encoded,
      long decodedSize,
      String encodingKey,
      String contentKey,
      CancellationToken token) {
    try {
      var decoded =
          new BlteDecoder()
              .decode(
                  encoded,
                  decodedSize,
                  HexFormat.of().parseHex(encodingKey),
                  HexFormat.of().parseHex(contentKey),
                  BLTE_LIMITS,
                  token::isCancellationRequested);
      var manifest =
          new InstallManifestParser()
              .parse(decoded, INSTALL_LIMITS, token::isCancellationRequested);
      long relationships = 0;
      for (int i = 0; i < manifest.entries().size(); i++) {
        token.throwIfCancellationRequested();
        for (var tag : manifest.tags())
          if (tag.includesEntry(i) && ++relationships > 250000)
            throw failure(
                BootstrapException.Reason.LIMIT,
                "Install tag memberships exceed inspection budget");
      }
      return manifest;
    } catch (BlteException error) {
      throw protocol(error.reason().name(), error);
    } catch (InstallManifestException error) {
      throw protocol(error.reason().name(), error);
    }
  }

  private static BootstrapException protocol(String reason, Exception error) {
    var mapped =
        reason.equals("MISSING_FIELD")
            ? BootstrapException.Reason.UNSUPPORTED
            : BootstrapException.Reason.valueOf(reason);
    return new BootstrapException(mapped, error.getMessage(), error);
  }

  private static BootstrapException failure(BootstrapException.Reason reason, String message) {
    return new BootstrapException(reason, message);
  }
}
