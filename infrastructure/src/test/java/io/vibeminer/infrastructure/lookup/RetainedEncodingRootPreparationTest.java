package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.protocol.tact.blte.*;
import io.vibeminer.protocol.tact.encoding.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedEncodingRootPreparationTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final Clock CLOCK = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
  static final StreamingDecodedBlteInputStream.Limits BLTE =
      new StreamingDecodedBlteInputStream.Limits(
          65536, 100, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 31);
  static final FullEncodingInventory.Limits EN =
      new FullEncodingInventory.Limits(
          1_000_000, 10000, 10000, 100, 65536, 4096, 1000, 255, 100, 65536, 1000);

  @Test
  void fullyVerifiedLocalEncodingIssuesGenuinePlanWithoutAnyNetwork() throws Exception {
    var fixture = new Fixture(directory, true, true);
    try (fixture) {
      fixture.wire.rejectNetwork = true;
      var selected =
          fixture.gateway.prepareRootFromRetainedEncoding(
              fixture.build,
              fixture.config,
              fixture.encoding,
              Set.of("f".repeat(32)),
              BLTE,
              EN,
              LookupBudgets.defaults(),
              NONE);
      assertEquals(fixture.wire.rootCKey, selected.root().contentKey());
      assertEquals(2, selected.root().candidates().size());
      assertEquals(-1, selected.root().candidates().getLast().encodedBytes());
      assertFalse(selected.contents().containsKey("f".repeat(32)));
      assertEquals(1, selected.contents().size());
      fixture.gateway.validateSelection(
          new RootSelectionPlan(selected.root(), selected.root().candidates().getFirst()), NONE);
      assertTrue(fixture.wire.requests.isEmpty());
      assertEquals(0, fixture.wire.configurationRequests.get());
    }
  }

  @Test
  void genuineIssuedPlanCanAcquireRootWithoutAnotherEncodingRequest() throws Exception {
    try (var fixture = new Fixture(directory, false, false)) {
      fixture.wire.rejectNetwork = true;
      var result =
          fixture.gateway.prepareRootFromRetainedEncoding(
              fixture.build,
              fixture.config,
              fixture.encoding,
              Set.of(),
              BLTE,
              EN,
              LookupBudgets.defaults(),
              NONE);
      fixture.wire.rejectNetwork = false;
      var root =
          fixture.gateway.acquireRoot(
              new RootSelectionPlan(result.root(), result.root().candidates().getFirst()), NONE);
      assertEquals(fixture.wire.encodedRoot.length, root.object().length());
      assertTrue(
          fixture.wire.requests.stream()
              .allMatch(request -> request.encodedKey().equals(fixture.wire.rootEKey)));
      fixture.wire.rejectNetwork = true;
      assertEquals(
          5,
          fixture
              .gateway
              .lookupRetained(
                  fixture.build, fixture.config, root, Set.of(), LookupBudgets.defaults(), NONE)
              .scannedRecords());
    }
  }

  @Test
  void completeAndKindSpecificCapabilitiesAreRequiredButNeverTrustedInsteadOfBytes()
      throws Exception {
    try (var fixture = new Fixture(directory, false, false)) {
      fixture.wire.rejectNetwork = true;
      var proof = fixture.encoding;
      var partial =
          new MetadataContentProof(
              proof.originalAlias(),
              proof.actualEncodingKey(),
              proof.contentKey(),
              proof.declaredDecodedBytes(),
              proof.decodedAttestation(),
              Optional.empty());
      assertEquals(
          FileLookupException.Code.UNSUPPORTED,
          assertThrows(
                  FileLookupException.class,
                  () ->
                      fixture.gateway.prepareRootFromRetainedEncoding(
                          fixture.build,
                          fixture.config,
                          partial,
                          Set.of(),
                          BLTE,
                          EN,
                          LookupBudgets.defaults(),
                          NONE))
              .code());
      var old =
          new MetadataContentProof(
              proof.originalAlias(),
              proof.actualEncodingKey(),
              proof.contentKey(),
              proof.declaredDecodedBytes(),
              Optional.of(
                  new DecodedContentAttestation(
                      proof.contentKey(),
                      proof.decodedAttestation().orElseThrow().actualDecodedBytes(),
                      "unknown-decoder")),
              proof.structuralAttestation());
      assertEquals(
          FileLookupException.Code.UNSUPPORTED,
          assertThrows(
                  FileLookupException.class,
                  () ->
                      fixture.gateway.prepareRootFromRetainedEncoding(
                          fixture.build,
                          fixture.config,
                          old,
                          Set.of(),
                          BLTE,
                          EN,
                          LookupBudgets.defaults(),
                          NONE))
              .code());
      String digest = proof.object().digest().value();
      Path file =
          directory.resolve("objects").resolve(digest.substring(0, 2)).resolve(digest.substring(2));
      byte[] bytes = java.nio.file.Files.readAllBytes(file);
      bytes[bytes.length - 1] ^= 1;
      java.nio.file.Files.write(file, bytes);
      assertEquals(
          FileLookupException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  FileLookupException.class,
                  () ->
                      fixture.gateway.prepareRootFromRetainedEncoding(
                          fixture.build,
                          fixture.config,
                          proof,
                          Set.of(),
                          BLTE,
                          EN,
                          LookupBudgets.defaults(),
                          NONE))
              .code());
      assertTrue(fixture.wire.requests.isEmpty());
    }
  }

  @Test
  void queryCandidateAndParserLimitsRefuseWithoutIssuingOrFetching() throws Exception {
    try (var fixture = new Fixture(directory, true, false)) {
      fixture.wire.rejectNetwork = true;
      var keys = new HashSet<String>();
      for (int i = 0; i < 33; i++) keys.add(String.format("%032x", i));
      assertEquals(
          FileLookupException.Code.BUDGET_EXCEEDED,
          assertThrows(
                  FileLookupException.class,
                  () ->
                      fixture.gateway.prepareRootFromRetainedEncoding(
                          fixture.build,
                          fixture.config,
                          fixture.encoding,
                          keys,
                          BLTE,
                          EN,
                          LookupBudgets.defaults(),
                          NONE))
              .code());
      var b = LookupBudgets.defaults();
      var small =
          new LookupBudgets(
              b.maximumConfigurationBytes(),
              b.maximumMetadataWireBytes(),
              b.maximumMetadataRequests(),
              b.maximumMetadataDecodedBytes(),
              b.maximumIndexBytes(),
              b.maximumPageBytes(),
              1,
              b.maximumRootEncodedBytes(),
              b.maximumRootDecodedBytes(),
              b.maximumHeaderBytes(),
              b.maximumChunks(),
              b.maximumEncodedChunkBytes(),
              b.maximumDecodedChunkBytes(),
              b.maximumDecodedWorkBytes(),
              b.maximumSelectedIds(),
              b.maximumMatches(),
              b.maximumRootRecords());
      assertEquals(
          FileLookupException.Code.BUDGET_EXCEEDED,
          assertThrows(
                  FileLookupException.class,
                  () ->
                      fixture.gateway.prepareRootFromRetainedEncoding(
                          fixture.build,
                          fixture.config,
                          fixture.encoding,
                          Set.of(),
                          BLTE,
                          EN,
                          small,
                          NONE))
              .code());
      var restricted =
          new FullEncodingInventory.Limits(1_000_000, 100, 100, 0, 0, 4096, 100, 255, 10, 100, 100);
      assertEquals(
          FileLookupException.Code.BUDGET_EXCEEDED,
          assertThrows(
                  FileLookupException.class,
                  () ->
                      fixture.gateway.prepareRootFromRetainedEncoding(
                          fixture.build,
                          fixture.config,
                          fixture.encoding,
                          Set.of(),
                          BLTE,
                          restricted,
                          b,
                          NONE))
              .code());
      assertEquals(
          FileLookupException.Code.INVALID_REQUEST,
          assertThrows(
                  FileLookupException.class,
                  () ->
                      fixture.gateway.prepareRootFromRetainedEncoding(
                          fixture.build,
                          fixture.config,
                          fixture.encoding,
                          Set.of("BAD"),
                          BLTE,
                          EN,
                          b,
                          NONE))
              .code());
      assertTrue(fixture.wire.requests.isEmpty());
    }
  }

  @Test
  void untrustedSourceReceiptAndCancellationCannotAuthorizeAPlan() throws Exception {
    try (var fixture = new Fixture(directory, false, false)) {
      fixture.wire.rejectNetwork = true;
      var proof = fixture.encoding;
      var original = proof.originalAlias();
      var altered =
          new VerifiedSourceAlias(
              original.source(),
              original.object(),
              new SourceVerificationEvidence(
                  original.evidence().verifierSemantics(),
                  "https://evil.example/source",
                  CLOCK.instant()));
      var bad =
          new MetadataContentProof(
              altered,
              proof.actualEncodingKey(),
              proof.contentKey(),
              proof.declaredDecodedBytes(),
              proof.decodedAttestation(),
              proof.structuralAttestation());
      assertEquals(
          FileLookupException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  FileLookupException.class,
                  () ->
                      fixture.gateway.prepareRootFromRetainedEncoding(
                          fixture.build,
                          fixture.config,
                          bad,
                          Set.of(),
                          BLTE,
                          EN,
                          LookupBudgets.defaults(),
                          NONE))
              .code());
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () ->
              fixture.gateway.prepareRootFromRetainedEncoding(
                  fixture.build,
                  fixture.config,
                  proof,
                  Set.of(),
                  BLTE,
                  EN,
                  LookupBudgets.defaults(),
                  () -> true));
      assertTrue(fixture.wire.requests.isEmpty());
    }
  }

  @Test
  void claimedFullCapabilitiesCannotHideAnAuthenticatedBadEnPage() throws Exception {
    try (var fixture =
        new Fixture(directory, false, false, bytes -> bytes[bytes.length - 1] ^= 1)) {
      fixture.wire.rejectNetwork = true;
      assertEquals(
          FileLookupException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  FileLookupException.class,
                  () ->
                      fixture.gateway.prepareRootFromRetainedEncoding(
                          fixture.build,
                          fixture.config,
                          fixture.encoding,
                          Set.of(),
                          BLTE,
                          EN,
                          LookupBudgets.defaults(),
                          NONE))
              .code());
      assertTrue(fixture.wire.requests.isEmpty());
    }
  }

  @Test
  void orderedDuplicateCandidatesRemainDistinctReferences() throws Exception {
    try (var fixture =
        new Fixture(
            directory,
            true,
            false,
            bytes -> {
              // Full EN:22header+4ESpec+32Cindex; C-record keys follow count/u40/CKey.
              int page = 22 + 4 + 32;
              System.arraycopy(bytes, page + 22, bytes, page + 38, 16);
              byte[] hash;
              try {
                hash =
                    java.security.MessageDigest.getInstance("MD5")
                        .digest(Arrays.copyOfRange(bytes, page, page + 1024));
              } catch (Exception impossible) {
                throw new AssertionError(impossible);
              }
              System.arraycopy(hash, 0, bytes, 22 + 4 + 16, 16);
            })) {
      fixture.wire.rejectNetwork = true;
      var result =
          fixture.gateway.prepareRootFromRetainedEncoding(
              fixture.build,
              fixture.config,
              fixture.encoding,
              Set.of(),
              BLTE,
              EN,
              LookupBudgets.defaults(),
              NONE);
      assertEquals(2, result.root().candidates().size());
      assertEquals(result.root().candidates().getFirst(), result.root().candidates().getLast());
      assertEquals(1, result.encodings().size());
    }
  }

  @Test
  void cancellationDuringActualLocalReadsClosesTheEncodingLease() throws Exception {
    try (var fixture = new Fixture(directory, false, false)) {
      fixture.wire.rejectNetwork = true;
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var store =
          (ObjectStore)
              java.lang.reflect.Proxy.newProxyInstance(
                  ObjectStore.class.getClassLoader(),
                  new Class<?>[] {ObjectStore.class},
                  (proxy, method, args) -> {
                    Object result;
                    try {
                      result = method.invoke(fixture.store, args);
                    } catch (java.lang.reflect.InvocationTargetException wrapped) {
                      throw wrapped.getCause();
                    }
                    if (!method.getName().equals("openRead")
                        || !args[0].equals(fixture.encoding.object().digest())) return result;
                    var lease = (ObjectReadLease) result;
                    var stream =
                        new FilterInputStream(lease.stream()) {
                          @Override
                          public int read(byte[] bytes, int offset, int length) throws IOException {
                            int count = super.read(bytes, offset, length);
                            if (calls.incrementAndGet() == 2) cancelled.set(true);
                            return count;
                          }
                        };
                    return new ObjectReadLease() {
                      public StoredObject object() {
                        return lease.object();
                      }

                      public InputStream stream() {
                        return stream;
                      }

                      public void close() {
                        lease.close();
                      }
                    };
                  });
      try (var gateway = new OfficialFileLookupGateway(store, fixture.wire, CLOCK)) {
        assertThrows(
            java.util.concurrent.CancellationException.class,
            () ->
                gateway.prepareRootFromRetainedEncoding(
                    fixture.build,
                    fixture.config,
                    fixture.encoding,
                    Set.of(),
                    BLTE,
                    EN,
                    LookupBudgets.defaults(),
                    cancelled::get));
      }
      assertEquals(2, calls.get());
      assertTrue(fixture.wire.requests.isEmpty());
    }
  }

  static final class Fixture implements AutoCloseable {
    final FileObjectStore store;
    final RootLookupFixture wire;
    final OfficialFileLookupGateway gateway;
    final BuildSnapshot build;
    final VerifiedSourceAlias config;
    final MetadataContentProof encoding;

    Fixture(Path path, boolean multiple, boolean missing) throws Exception {
      this(path, multiple, missing, bytes -> {});
    }

    Fixture(
        Path path,
        boolean multiple,
        boolean missing,
        java.util.function.Consumer<byte[]> mutateDecoded)
        throws Exception {
      store = new FileObjectStore(path);
      wire = new RootLookupFixture(false, false, multiple, missing);
      byte[] old = wire.objects.get(wire.encodingEKey);
      byte[] decoded;
      try (var stream =
          new StreamingDecodedBlteInputStream(
              new ByteArrayInputStream(old),
              wire.encodingEKey,
              configToken(wire.config, "encoding", 0),
              old.length,
              Long.parseLong(configToken(wire.config, "encoding-size", 0)),
              BLTE,
              () -> false)) {
        decoded = stream.readAllBytes();
      }
      // Old selected-page fixture deliberately deferred ESpec validation. This full-source fixture
      // independently supplies both referenced specification strings.
      byte[] specs = new byte[] {'z', 0, 'n', 0};
      byte[] full = new byte[decoded.length + specs.length];
      System.arraycopy(decoded, 0, full, 0, 22);
      ByteBuffer.wrap(full).putInt(18, specs.length);
      System.arraycopy(specs, 0, full, 22, specs.length);
      System.arraycopy(decoded, 22, full, 26, decoded.length - 22);
      mutateDecoded.accept(full);
      byte[] encoded = RootLookupFixture.blte(full);
      String ck = RootLookupFixture.hash("MD5", full), ek = RootLookupFixture.ekey(encoded);
      byte[] raw =
          ("root = "
                  + wire.rootCKey
                  + "\nencoding = "
                  + ck
                  + " "
                  + ek
                  + "\nencoding-size = "
                  + full.length
                  + " "
                  + encoded.length
                  + "\n")
              .getBytes(StandardCharsets.UTF_8);
      String configKey = RootLookupFixture.hash("MD5", raw);
      var oldBuild = wire.build("us.cdn.blizzard.com", "retained-EN");
      var documents =
          oldBuild.evidence().documents().stream()
              .map(
                  document -> {
                    byte[] bytes = document.bytes();
                    if (document.sourceUrl().endsWith("/versions"))
                      bytes =
                          new String(bytes, StandardCharsets.UTF_8)
                              .replace(oldBuild.buildConfig(), configKey)
                              .getBytes(StandardCharsets.UTF_8);
                    return new SourceDocument(document.sourceUrl(), document.capturedAt(), bytes);
                  })
              .toList();
      build =
          new BuildSnapshot(
              oldBuild.productId(),
              oldBuild.editionLabel(),
              oldBuild.channel(),
              oldBuild.region(),
              oldBuild.versionLabel(),
              configKey,
              oldBuild.cdnConfig(),
              oldBuild.observedAt(),
              false,
              new BuildEvidence("", "", documents));
      var configObject = publish(store, raw);
      config =
          new VerifiedSourceAlias(
              new ResolvedSourceIdentity(
                  "tact.config.raw-md5", configKey, "tact-config-raw-md5-v1"),
              configObject,
              new SourceVerificationEvidence(
                  "tact-config-raw-md5-v1",
                  "https://us.cdn.blizzard.com/tpr/wow/config/"
                      + configKey.substring(0, 2)
                      + "/"
                      + configKey.substring(2, 4)
                      + "/"
                      + configKey,
                  CLOCK.instant()));
      String semantics = "blte-complete-encoded-and-ckey-stream-v1";
      var source =
          new ResolvedSourceIdentity(
              "tact.encoding.v1",
              ck + ";" + ek + ";" + full.length + ";" + encoded.length,
              semantics);
      var alias =
          new VerifiedSourceAlias(
              source,
              publish(store, encoded),
              new SourceVerificationEvidence(
                  semantics,
                  "https://us.cdn.blizzard.com/tpr/wow/data/"
                      + ek.substring(0, 2)
                      + "/"
                      + ek.substring(2, 4)
                      + "/"
                      + ek,
                  CLOCK.instant()));
      encoding =
          new MetadataContentProof(
              alias,
              ek,
              ck,
              OptionalLong.of(full.length),
              Optional.of(
                  new DecodedContentAttestation(ck, full.length, "blte-nz-full-ckey-stream-v1")),
              Optional.of(
                  new MetadataStructureAttestation(
                      MetadataStructureAttestation.Kind.ENCODING, "encoding-v1-full-r1")));
      gateway = new OfficialFileLookupGateway(store, wire, CLOCK);
    }

    public void close() {
      gateway.close();
      store.close();
    }
  }

  static String configToken(byte[] raw, String field, int index) {
    return new String(raw, StandardCharsets.UTF_8)
        .lines()
        .filter(line -> line.startsWith(field + " = "))
        .findFirst()
        .orElseThrow()
        .split(" = ")[1]
        .split(" ")[index];
  }

  static StoredObject publish(FileObjectStore store, byte[] raw) throws Exception {
    return store.publish(
        new ByteArrayInputStream(raw),
        new ExpectedObjectChecksum(
            ChecksumAlgorithm.SHA256, RootLookupFixture.hash("SHA-256", raw), raw.length),
        raw.length,
        NONE);
  }
}
