package io.vibeminer.infrastructure.bootstrap;

import static io.vibeminer.infrastructure.bootstrap.BootstrapWorkflowIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.lookup.OfficialMetadataReuseGateway;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallMetadataReuseGatewayTest {
  @TempDir Path directory;

  static byte[] alternative(byte[] decoded) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("BLTE");
    out.writeInt(0);
    out.writeByte('Z');
    try (var zip = new DeflaterOutputStream(bytes)) {
      zip.write(decoded);
    }
    return bytes.toByteArray();
  }

  record Setup(
      FileObjectStore store,
      MetadataContentProof actual,
      MetadataDeclaration declaration,
      AtomicInteger requests)
      implements AutoCloseable {
    public void close() {
      store.close();
    }
  }

  Setup setup(byte[] decoded) throws Exception {
    return setup(decoded, false);
  }

  Setup setup(byte[] decoded, boolean syntheticHistoricalClaim) throws Exception {
    var store = new FileObjectStore(directory);
    byte[] encoded = encode(decoded),
        configA = config(decoded, encoded),
        encodedB = alternative(decoded),
        configB = config(decoded, encodedB);
    var requests = new AtomicInteger();
    BootstrapTransfer transfer =
        r -> {
          requests.incrementAndGet();
          byte[] bytes = r.kind() == CdnRequest.Kind.CONFIG ? configA : encoded;
          return CompletableFuture.completedFuture(
              new AcquiredBytes(
                  r, NOW, bytes, hash("SHA-256", bytes), r.kind() == CdnRequest.Kind.CONFIG));
        };
    var old =
        new OfficialBootstrapArtifactGateway(store, transfer, Clock.fixed(NOW, ZoneOffset.UTC));
    var plan = old.inspectConfiguration(build(configA), CancellationToken.none());
    var captured = new AtomicReference<MetadataContentProof>();
    if (syntheticHistoricalClaim) {
      var physical =
          store.publish(
              new ByteArrayInputStream(encoded),
              new ExpectedObjectChecksum(
                  ChecksumAlgorithm.SHA256, hash("SHA-256", encoded), encoded.length),
              encoded.length,
              CancellationToken.none());
      var semantics = "blte-nz-ekey-header-or-whole-chunk-md5-ckey-md5+in-v1-r1";
      var receipt =
          new VerifiedSourceAlias(
              old.installIdentity(plan),
              physical,
              new SourceVerificationEvidence(
                  semantics,
                  CdnRequest.loose(
                          PinnedBuildSource.resolve(plan.build()),
                          plan.encodingKey(),
                          encoded.length,
                          4 << 20)
                      .uri()
                      .toString(),
                  NOW));
      captured.set(
          new MetadataContentProof(
              receipt,
              plan.encodingKey(),
              plan.contentKey(),
              OptionalLong.of(decoded.length),
              Optional.of(
                  new DecodedContentAttestation(
                      plan.contentKey(), decoded.length, "blte-nz-full-ckey-v1")),
              Optional.of(
                  new MetadataStructureAttestation(
                      MetadataStructureAttestation.Kind.INSTALL, "install-manifest-v1-r1"))));
    } else {
      old.acquireInstall(
          plan,
          new MetadataPublicationObserver() {
            public void beforePromotion(MetadataContentProof p, CancellationToken t) {
              captured.set(p);
            }

            public void afterStructureValidation(MetadataContentProof p, CancellationToken t) {
              fail("Unexpected install callback");
            }
          },
          CancellationToken.none());
    }
    var object =
        store.publish(
            new ByteArrayInputStream(configB),
            new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, hash("MD5", configB), configB.length),
            configB.length,
            CancellationToken.none());
    var buildB = build(configB);
    var alias =
        new VerifiedSourceAlias(
            old.configurationIdentity(buildB),
            object,
            new SourceVerificationEvidence(
                "tact-config-raw-md5-v1",
                CdnRequest.configuration(PinnedBuildSource.resolve(buildB), buildB.buildConfig())
                    .uri()
                    .toString(),
                NOW));
    var declaration =
        new MetadataDeclaration(
            buildB,
            alias,
            MetadataStructureAttestation.Kind.INSTALL,
            new ContentSlotKey("wow", hash("MD5", decoded)),
            Optional.of(hash("MD5", encodedB)),
            OptionalLong.of(encodedB.length),
            OptionalLong.of(decoded.length));
    assertNotEquals(
        declaration.preferredEncodingKey().orElseThrow(), captured.get().actualEncodingKey());
    return new Setup(store, captured.get(), declaration, requests);
  }

  static MetadataReusePlan plan(Setup setup) {
    return new MetadataReusePlan(
        UUID.randomUUID(), UUID.randomUUID(), setup.declaration(), setup.actual());
  }

  static long objects(Path dir) throws Exception {
    try (var paths = Files.walk(dir.resolve("objects"))) {
      return paths.filter(Files::isRegularFile).count();
    }
  }

  @Test
  void newConfigurationDeclaresBButActualAReplaysLocallyWithoutWritesOrNewReceipt()
      throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      long before = objects(directory);
      int requests = setup.requests().get();
      gateway.validateDeclaration(
          setup.declaration(), LookupBudgets.defaults(), CancellationToken.none());
      var actual = gateway.replay(plan(setup), LookupBudgets.defaults(), CancellationToken.none());
      assertEquals(setup.actual(), actual);
      assertEquals(requests, setup.requests().get());
      assertEquals(before, objects(directory));
      assertEquals(NOW, actual.originalAlias().evidence().verifiedAt());
    }
  }

  @Test
  void omittedOrChangedKnownDeclarationDoesNotPassExactConfigValidation() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var d = setup.declaration();
      for (var forged :
          List.of(
              new MetadataDeclaration(
                  d.build(),
                  d.configuration(),
                  d.kind(),
                  d.slot(),
                  Optional.empty(),
                  OptionalLong.empty(),
                  OptionalLong.empty()),
              new MetadataDeclaration(
                  d.build(),
                  d.configuration(),
                  d.kind(),
                  d.slot(),
                  Optional.of("f".repeat(32)),
                  d.declaredEncodedBytes(),
                  d.declaredDecodedBytes())))
        assertThrows(
            ContentClaimException.class,
            () ->
                gateway.validateDeclaration(
                    forged, LookupBudgets.defaults(), CancellationToken.none()));
    }
  }

  @Test
  void missingAndCorruptActualBytesFailWithoutAutofetch() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var digest = setup.actual().object().digest().value();
      var file =
          directory.resolve("objects").resolve(digest.substring(0, 2)).resolve(digest.substring(2));
      var original = Files.readAllBytes(file);
      var corrupt = original.clone();
      corrupt[corrupt.length - 1] ^= 1;
      Files.write(file, corrupt);
      int requests = setup.requests().get();
      assertThrows(
          ObjectStoreException.class,
          () -> gateway.replay(plan(setup), LookupBudgets.defaults(), CancellationToken.none()));
      Files.delete(file);
      assertThrows(
          ObjectStoreException.class,
          () -> gateway.replay(plan(setup), LookupBudgets.defaults(), CancellationToken.none()));
      assertEquals(requests, setup.requests().get());
    }
  }

  @Test
  void cancelledReplayCannotProduceFreshCapability() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      assertThrows(
          CancellationException.class,
          () -> gateway.replay(plan(setup), LookupBudgets.defaults(), () -> true));
    }
  }

  @Test
  void authenticHashWithUnsupportedGrammarCannotAcquireReuseCapability() throws Exception {
    byte[] invalid = manifest();
    invalid[0] = 'X';
    try (var setup = setup(invalid, true);
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      assertThrows(
          io.vibeminer.application.bootstrap.BootstrapException.class,
          () -> gateway.replay(plan(setup), LookupBudgets.defaults(), CancellationToken.none()));
    }
  }

  @Test
  void physicalConfigShaCannotSubstituteForItsDeclaredMd5() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var d = setup.declaration();
      var alias =
          new VerifiedSourceAlias(
              d.configuration().source(), setup.actual().object(), d.configuration().evidence());
      var forged =
          new MetadataDeclaration(
              d.build(),
              alias,
              d.kind(),
              d.slot(),
              d.preferredEncodingKey(),
              d.declaredEncodedBytes(),
              d.declaredDecodedBytes());
      var failure =
          assertThrows(
              ContentClaimException.class,
              () ->
                  gateway.validateDeclaration(
                      forged, LookupBudgets.defaults(), CancellationToken.none()));
      assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, failure.code());
    }
  }

  @Test
  void replayReadsWhileBothConfigurationAndActualRepresentationLeasesAreHeld() throws Exception {
    try (var setup = setup(manifest())) {
      var active = new AtomicInteger();
      var maximum = new AtomicInteger();
      var actualReads = new AtomicInteger();
      ObjectStore local =
          new ObjectStore() {
            public StoredObject publish(
                InputStream s, ExpectedObjectChecksum e, long m, CancellationToken t) {
              throw new AssertionError("No publication");
            }

            public StoredObject publishVerified(
                InputStream s, long e, long m, CompletionVerification v, CancellationToken t) {
              throw new AssertionError("No publication");
            }

            public Optional<StoredObject> verify(ObjectDigest d, CancellationToken t) {
              throw new AssertionError("Lease required");
            }

            public ObjectReadLease openRead(ObjectDigest digest, CancellationToken token) {
              var lease = setup.store().openRead(digest, token);
              maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
              return new ObjectReadLease() {
                public StoredObject object() {
                  return lease.object();
                }

                public InputStream stream() {
                  if (digest.equals(setup.actual().object().digest())) {
                    assertEquals(2, active.get());
                    actualReads.incrementAndGet();
                  }
                  return lease.stream();
                }

                public void close() {
                  try {
                    lease.close();
                  } finally {
                    active.decrementAndGet();
                  }
                }
              };
            }

            public void close() {}
          };
      try (var gateway = new OfficialMetadataReuseGateway(local)) {
        assertEquals(
            setup.actual(),
            gateway.replay(plan(setup), LookupBudgets.defaults(), CancellationToken.none()));
        assertEquals(2, maximum.get());
        assertEquals(0, active.get());
        assertEquals(1, actualReads.get());
      }
    }
  }

  @Test
  void declaredWorkBoundStopsBeforeActualPayloadRead() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var b = LookupBudgets.defaults();
      var narrow =
          new LookupBudgets(
              b.maximumConfigurationBytes(),
              b.maximumMetadataWireBytes(),
              b.maximumMetadataRequests(),
              b.maximumMetadataDecodedBytes(),
              b.maximumIndexBytes(),
              b.maximumPageBytes(),
              b.maximumCandidates(),
              1,
              b.maximumRootDecodedBytes(),
              b.maximumHeaderBytes(),
              b.maximumChunks(),
              b.maximumEncodedChunkBytes(),
              b.maximumDecodedChunkBytes(),
              b.maximumDecodedWorkBytes(),
              b.maximumSelectedIds(),
              b.maximumMatches(),
              b.maximumRootRecords());
      var failure =
          assertThrows(
              ContentClaimException.class,
              () -> gateway.replay(plan(setup), narrow, CancellationToken.none()));
      assertEquals(ContentClaimException.Code.UNSUPPORTED_REUSE, failure.code());
    }
  }
}
