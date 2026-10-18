package io.vibeminer.infrastructure.bootstrap;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.DeflaterOutputStream;

/** Authored install bytes through the actual acquisition observer; tool-only transport seam. */
public final class MetadataReuseInstallSeed {
  public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  public record Seed(MetadataContentProof proof, byte[] declaration, String alternateEncodingKey) {}

  public static Seed acquire(FileObjectStore store, AtomicInteger calls) throws Exception {
    byte[] decoded = manifest(),
        encoded = encode(decoded, false),
        alternate = encode(decoded, true);
    byte[] config = configuration(decoded, encoded);
    BootstrapTransfer transfer =
        request -> {
          calls.incrementAndGet();
          byte[] bytes = request.kind() == CdnRequest.Kind.CONFIG ? config : encoded;
          return CompletableFuture.completedFuture(
              new AcquiredBytes(
                  request,
                  NOW,
                  bytes,
                  hash("SHA-256", bytes),
                  request.kind() == CdnRequest.Kind.CONFIG));
        };
    {
      var gateway =
          new OfficialBootstrapArtifactGateway(store, transfer, Clock.fixed(NOW, ZoneOffset.UTC));
      var plan =
          gateway.inspectConfiguration(
              build(config, "DEMO-ORIGINAL-INSTALL-A"), CancellationToken.none());
      var proof = new AtomicReference<MetadataContentProof>();
      gateway.acquireInstall(
          plan,
          new MetadataPublicationObserver() {
            public void beforePromotion(MetadataContentProof p, CancellationToken t) {
              proof.set(p);
            }

            public void afterStructureValidation(MetadataContentProof p, CancellationToken t) {
              proof.set(p);
            }
          },
          CancellationToken.none());
      if (proof.get() == null || proof.get().structuralAttestation().isEmpty())
        throw new AssertionError("Real install acquisition omitted structure proof");
      return new Seed(proof.get(), configuration(decoded, alternate), hash("MD5", alternate));
    }
  }

  public static byte[] alternateDeclaration() throws Exception {
    byte[] decoded = manifest();
    return configuration(decoded, encode(decoded, true));
  }

  public static OfficialBootstrapArtifactGateway offlineGateway(
      FileObjectStore store, AtomicInteger forbidden) {
    return new OfficialBootstrapArtifactGateway(
        store,
        request -> {
          forbidden.incrementAndGet();
          throw new AssertionError("Metadata reuse UI must not invoke install transport");
        },
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  public static BuildSnapshot build(byte[] config, String version) {
    String key = hash("MD5", config), cdn = "e".repeat(32);
    String versions =
        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|"
            + key
            + "|"
            + cdn
            + "|"
            + version
            + "\n";
    String routes =
        "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n"
            + "us|tpr/wow|us.cdn.blizzard.com|https://us.cdn.blizzard.com/|tpr/configs/data\n";
    return new BuildSnapshot(
        "wow",
        "Synthetic metadata edition",
        "Fixture",
        "us",
        version,
        key,
        cdn,
        NOW,
        true,
        new BuildEvidence(
            "",
            "",
            List.of(
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/versions", NOW, text(versions)),
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/cdns", NOW, text(routes)))));
  }

  public static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  public static String hash(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static byte[] configuration(byte[] decoded, byte[] encoded) {
    return text(
        "install = "
            + hash("MD5", decoded)
            + " "
            + hash("MD5", encoded)
            + "\ninstall-size = "
            + decoded.length
            + " "
            + encoded.length
            + "\n");
  }

  private static byte[] encode(byte[] decoded, boolean compressed) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("BLTE");
    out.writeInt(0);
    out.writeByte(compressed ? 'Z' : 'N');
    if (compressed) {
      try (var zip = new DeflaterOutputStream(bytes)) {
        zip.write(decoded);
      }
    } else out.write(decoded);
    return bytes.toByteArray();
  }

  private static byte[] manifest() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("IN");
    out.writeByte(1);
    out.writeByte(16);
    out.writeShort(1);
    out.writeInt(2);
    out.write(text("enUS"));
    out.writeByte(0);
    out.writeShort(2);
    out.writeByte(0xc0);
    entry(out, "Research/Authored-notes.txt", 0x11, 42);
    entry(out, "Research/Authored-chart.bin", 0x22, 128);
    return bytes.toByteArray();
  }

  private static void entry(DataOutputStream out, String name, int digest, int size)
      throws Exception {
    out.write(text(name));
    out.writeByte(0);
    byte[] key = new byte[16];
    Arrays.fill(key, (byte) digest);
    out.write(key);
    out.writeInt(size);
  }
}
