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
          ContentClaimException.class,
          () -> gateway.replay(plan(setup), LookupBudgets.defaults(), CancellationToken.none()));
      Files.delete(file);
      assertThrows(
          ContentClaimException.class,
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
          ContentClaimException.class,
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

  @Test
  void readInstallDeclarationDerivesEveryKnownBFactWithoutReadingActualA() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var digest = setup.actual().object().digest().value();
      Files.delete(
          directory
              .resolve("objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2)));
      int requests = setup.requests().get();
      var expected = setup.declaration();
      var observed =
          gateway.readDeclaration(
              expected.build(),
              expected.configuration(),
              expected.kind(),
              LookupBudgets.defaults(),
              CancellationToken.none());
      assertEquals(expected, observed);
      assertEquals(requests, setup.requests().get());
      assertEquals(expected.configuration(), observed.configuration());
    }
  }

  @Test
  void classicBetaDeclarationAndActualLocalReuseKeepExactProductAndOriginalProof()
      throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var original = setup.declaration();
      var b = original.build();
      var documents =
          b.evidence().documents().stream()
              .map(
                  d ->
                      new SourceDocument(
                          d.sourceUrl().replace("/products/wow/", "/products/wow_classic_beta/"),
                          d.capturedAt(),
                          d.bytes()))
              .toList();
      var classic =
          new BuildSnapshot(
              "wow_classic_beta",
              "Classic Beta",
              b.channel(),
              b.region(),
              b.versionLabel(),
              b.buildConfig(),
              b.cdnConfig(),
              b.observedAt(),
              b.fixture(),
              new BuildEvidence(
                  b.evidence().upstreamBuildId(), b.evidence().productConfig(), documents));
      int requests = setup.requests().get();
      var declaration =
          gateway.readDeclaration(
              classic,
              original.configuration(),
              original.kind(),
              LookupBudgets.defaults(),
              CancellationToken.none());
      assertEquals(classic, declaration.build());
      assertEquals(original.slot(), declaration.slot());
      var plan =
          new MetadataReusePlan(UUID.randomUUID(), UUID.randomUUID(), declaration, setup.actual());
      var result = gateway.replay(plan, LookupBudgets.defaults(), CancellationToken.none());
      assertEquals(setup.actual(), result);
      assertEquals(requests, setup.requests().get());
      for (var product : List.of("d3", "wow_unknown")) {
        var unsupported =
            new BuildSnapshot(
                product,
                b.editionLabel(),
                b.channel(),
                b.region(),
                b.versionLabel(),
                b.buildConfig(),
                b.cdnConfig(),
                b.observedAt(),
                b.fixture(),
                b.evidence());
        var failure =
            assertThrows(
                ContentClaimException.class,
                () ->
                    gateway.readDeclaration(
                        unsupported,
                        original.configuration(),
                        original.kind(),
                        LookupBudgets.defaults(),
                        CancellationToken.none()));
        assertEquals(ContentClaimException.Code.UNSUPPORTED_REUSE, failure.code());
      }
      assertEquals(requests, setup.requests().get());
    }
  }

  @Test
  void readDeclarationRejectsForeignConfigurationIdentityAndWrongRawMd5() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var d = setup.declaration();
      var original = d.configuration();
      var foreign =
          new VerifiedSourceAlias(
              new ResolvedSourceIdentity(
                  "tact.config.raw-md5", "0".repeat(32), "tact-config-raw-md5-v1"),
              original.object(),
              original.evidence());
      assertThrows(
          ContentClaimException.class,
          () ->
              gateway.readDeclaration(
                  d.build(),
                  foreign,
                  d.kind(),
                  LookupBudgets.defaults(),
                  CancellationToken.none()));
      var wrongBytes =
          new VerifiedSourceAlias(original.source(), setup.actual().object(), original.evidence());
      var failure =
          assertThrows(
              ContentClaimException.class,
              () ->
                  gateway.readDeclaration(
                      d.build(),
                      wrongBytes,
                      d.kind(),
                      LookupBudgets.defaults(),
                      CancellationToken.none()));
      assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, failure.code());
    }
  }

  @Test
  void readDeclarationRechecksCapturedExactBuildAndCancellation() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var d = setup.declaration();
      var b = d.build();
      var changed =
          new BuildSnapshot(
              b.productId(),
              b.editionLabel(),
              b.channel(),
              b.region(),
              "unobserved-version",
              b.buildConfig(),
              b.cdnConfig(),
              b.observedAt(),
              b.fixture(),
              b.evidence());
      assertThrows(
          ContentClaimException.class,
          () ->
              gateway.readDeclaration(
                  changed,
                  d.configuration(),
                  d.kind(),
                  LookupBudgets.defaults(),
                  CancellationToken.none()));
      assertThrows(
          CancellationException.class,
          () ->
              gateway.readDeclaration(
                  b, d.configuration(), d.kind(), LookupBudgets.defaults(), () -> true));
    }
  }

  static io.vibeminer.application.bootstrap.InstallPlan declaredPlan(Setup setup) throws Exception {
    var d = setup.declaration();
    var config = d.configuration();
    try (var lease = setup.store().openRead(config.object().digest(), CancellationToken.none())) {
      var document =
          new SourceDocument(
              config.evidence().sourceUrl(),
              config.evidence().verifiedAt(),
              lease.stream().readAllBytes());
      return new io.vibeminer.application.bootstrap.InstallPlan(
          d.build(),
          document,
          d.slot().contentKey(),
          d.preferredEncodingKey().orElseThrow(),
          d.declaredDecodedBytes().orElseThrow(),
          d.declaredEncodedBytes().orElseThrow());
    }
  }

  static MetadataBuildAssociation association(Setup setup) {
    return new MetadataBuildAssociation(
        setup.declaration(), UUID.randomUUID(), setup.actual(), NOW.plusSeconds(1));
  }

  @Test
  void associatedInstallReturnsDeclaredBAndActualAEntriesWithoutAnyTransferOrWrites()
      throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var plan = declaredPlan(setup);
      var association = association(setup);
      int requests = setup.requests().get();
      long before = objects(directory);
      var result =
          gateway.inspectAssociatedInstall(
              plan, association, LookupBudgets.defaults(), CancellationToken.none());
      assertEquals(plan, result.plan());
      assertEquals(setup.actual().object(), result.object());
      assertEquals(setup.actual().originalAlias().evidence(), result.verification());
      assertEquals(Optional.of(association), result.association());
      assertNotEquals(plan.encodingKey(), association.actualRepresentation().actualEncodingKey());
      assertEquals("Interface/readme.txt", result.entries().getFirst().name());
      assertEquals("Windows", result.entries().getFirst().tags().getFirst().name());
      assertEquals(requests, setup.requests().get());
      assertEquals(before, objects(directory));
    }
  }

  @Test
  void associatedInstallFreshReadPreservesHistoricalCapabilitiesButStrongReuseStillRefuses()
      throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var original = setup.actual();
      var historical =
          new MetadataContentProof(
              original.originalAlias(),
              original.actualEncodingKey(),
              original.contentKey(),
              original.declaredDecodedBytes(),
              Optional.of(
                  new DecodedContentAttestation(
                      original.contentKey(),
                      original.decodedAttestation().orElseThrow().actualDecodedBytes(),
                      "historical-install-decoded-v0")),
              Optional.of(
                  new MetadataStructureAttestation(
                      MetadataStructureAttestation.Kind.INSTALL, "historical-install-v0")));
      var association =
          new MetadataBuildAssociation(
              setup.declaration(), UUID.randomUUID(), historical, NOW.plusSeconds(1));
      int requests = setup.requests().get();
      long before = objects(directory);
      var result =
          gateway.inspectAssociatedInstall(
              declaredPlan(setup), association, LookupBudgets.defaults(), CancellationToken.none());
      assertEquals("Interface/readme.txt", result.entries().getFirst().name());
      assertEquals(Optional.of(association), result.association());
      assertEquals(original.originalAlias().evidence(), result.verification());
      assertEquals(
          "historical-install-decoded-v0",
          result
              .association()
              .orElseThrow()
              .actualRepresentation()
              .decodedAttestation()
              .orElseThrow()
              .verifierRevision());
      assertEquals(
          "historical-install-v0",
          result
              .association()
              .orElseThrow()
              .actualRepresentation()
              .structuralAttestation()
              .orElseThrow()
              .parserRevision());
      var strongPlan =
          new MetadataReusePlan(
              UUID.randomUUID(), UUID.randomUUID(), setup.declaration(), historical);
      assertEquals(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          assertThrows(
                  ContentClaimException.class,
                  () ->
                      gateway.replay(
                          strongPlan, LookupBudgets.defaults(), CancellationToken.none()))
              .code());
      assertEquals(requests, setup.requests().get());
      assertEquals(before, objects(directory));
    }
  }

  @Test
  void associatedInstallStillRejectsUnknownOriginalSourceSemantics() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var p = setup.actual();
      var a = p.originalAlias();
      var unknown =
          new VerifiedSourceAlias(
              new ResolvedSourceIdentity(
                  a.source().namespace(),
                  a.source().immutableSourceKey(),
                  "unknown-source-verifier"),
              a.object(),
              new SourceVerificationEvidence(
                  "unknown-source-verifier", a.evidence().sourceUrl(), a.evidence().verifiedAt()));
      var proof =
          new MetadataContentProof(
              unknown,
              p.actualEncodingKey(),
              p.contentKey(),
              p.declaredDecodedBytes(),
              p.decodedAttestation(),
              p.structuralAttestation());
      var association =
          new MetadataBuildAssociation(
              setup.declaration(), UUID.randomUUID(), proof, NOW.plusSeconds(1));
      assertEquals(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          assertThrows(
                  ContentClaimException.class,
                  () ->
                      gateway.inspectAssociatedInstall(
                          declaredPlan(setup),
                          association,
                          LookupBudgets.defaults(),
                          CancellationToken.none()))
              .code());
    }
  }

  @Test
  void associatedInstallRechecksEKeyAndFullCKeyDespiteHistoricalClaims() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var original = setup.actual();
      var plan = declaredPlan(setup);
      int requests = setup.requests().get();
      for (boolean wrongContentKey : new boolean[] {false, true}) {
        byte[] decoded = manifest();
        if (wrongContentKey) decoded[decoded.length - 1] ^= 1;
        byte[] encoded = encode(decoded);
        var object =
            setup
                .store()
                .publish(
                    new ByteArrayInputStream(encoded),
                    new ExpectedObjectChecksum(
                        ChecksumAlgorithm.SHA256, hash("SHA-256", encoded), encoded.length),
                    encoded.length,
                    CancellationToken.none());
        String ekey = wrongContentKey ? hash("MD5", encoded) : "00000000000000000000000000000000";
        var source =
            new ResolvedSourceIdentity(
                original.originalAlias().source().namespace(),
                original.contentKey() + ";" + ekey + ";" + decoded.length + ";" + encoded.length,
                original.originalAlias().source().verificationSemantics());
        var alias =
            new VerifiedSourceAlias(
                source,
                object,
                new SourceVerificationEvidence(
                    source.verificationSemantics(),
                    CdnRequest.loose(
                            PinnedBuildSource.resolve(plan.build()), ekey, encoded.length, 4 << 20)
                        .uri()
                        .toString(),
                    NOW));
        var claim =
            new MetadataContentProof(
                alias,
                ekey,
                original.contentKey(),
                original.declaredDecodedBytes(),
                original.decodedAttestation(),
                original.structuralAttestation());
        var association =
            new MetadataBuildAssociation(
                setup.declaration(), UUID.randomUUID(), claim, NOW.plusSeconds(1));
        assertEquals(
            ContentClaimException.Code.INTEGRITY_FAILURE,
            assertThrows(
                    ContentClaimException.class,
                    () ->
                        gateway.inspectAssociatedInstall(
                            plan, association, LookupBudgets.defaults(), CancellationToken.none()))
                .code());
      }
      assertEquals(requests, setup.requests().get());
    }
  }

  @Test
  void associatedInstallRejectsWrongPlanContextAndOriginalSource() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var plan = declaredPlan(setup);
      var a = association(setup);
      var wrongDocument =
          new SourceDocument(
              plan.configuration().sourceUrl(),
              plan.configuration().capturedAt(),
              new byte[] {1, 2, 3});
      var wrongPlan =
          new io.vibeminer.application.bootstrap.InstallPlan(
              plan.build(),
              wrongDocument,
              plan.contentKey(),
              plan.encodingKey(),
              plan.decodedBytes(),
              plan.encodedBytes());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              gateway.inspectAssociatedInstall(
                  wrongPlan, a, LookupBudgets.defaults(), CancellationToken.none()));
      var original = setup.actual();
      var alias = original.originalAlias();
      var badAlias =
          new VerifiedSourceAlias(
              alias.source(),
              alias.object(),
              new SourceVerificationEvidence(
                  alias.evidence().verifierSemantics(),
                  "https://example.invalid/not-an-official-data-source",
                  alias.evidence().verifiedAt()));
      var badProof =
          new MetadataContentProof(
              badAlias,
              original.actualEncodingKey(),
              original.contentKey(),
              original.declaredDecodedBytes(),
              original.decodedAttestation(),
              original.structuralAttestation());
      var badAssociation =
          new MetadataBuildAssociation(
              a.declaration(), a.canonicalReservationToken(), badProof, a.associatedAt());
      assertThrows(
          ContentClaimException.class,
          () ->
              gateway.inspectAssociatedInstall(
                  plan, badAssociation, LookupBudgets.defaults(), CancellationToken.none()));
    }
  }

  @Test
  void associatedInstallDoesNotTrustGrammarAndCannotFetchMissingA() throws Exception {
    byte[] invalid = manifest();
    invalid[0] = 'X';
    try (var setup = setup(invalid, true);
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var plan = declaredPlan(setup);
      var a = association(setup);
      int requests = setup.requests().get();
      assertThrows(
          ContentClaimException.class,
          () ->
              gateway.inspectAssociatedInstall(
                  plan, a, LookupBudgets.defaults(), CancellationToken.none()));
      var digest = setup.actual().object().digest().value();
      Files.delete(
          directory
              .resolve("objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2)));
      assertThrows(
          ContentClaimException.class,
          () ->
              gateway.inspectAssociatedInstall(
                  plan, a, LookupBudgets.defaults(), CancellationToken.none()));
      assertEquals(requests, setup.requests().get());
    }
  }

  @Test
  void associatedInstallEnforcesBoundsCancellationAndWholePhysicalIntegrity() throws Exception {
    try (var setup = setup(manifest());
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var plan = declaredPlan(setup);
      var a = association(setup);
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
      assertThrows(
          ContentClaimException.class,
          () -> gateway.inspectAssociatedInstall(plan, a, narrow, CancellationToken.none()));
      assertThrows(
          CancellationException.class,
          () -> gateway.inspectAssociatedInstall(plan, a, b, () -> true));
      var digest = setup.actual().object().digest().value();
      var file =
          directory.resolve("objects").resolve(digest.substring(0, 2)).resolve(digest.substring(2));
      var bytes = Files.readAllBytes(file);
      bytes[bytes.length - 1] ^= 1;
      Files.write(file, bytes);
      int requests = setup.requests().get();
      var failure =
          assertThrows(
              ContentClaimException.class,
              () -> gateway.inspectAssociatedInstall(plan, a, b, CancellationToken.none()));
      assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, failure.code());
      assertEquals(requests, setup.requests().get());
    }
  }

  @Test
  void allLocalPublicBoundariesNormalizeStoreFailuresWithoutChangingCauses() throws Exception {
    try (var setup = setup(manifest())) {
      var plan = declaredPlan(setup);
      var association = association(setup);
      var declaration = setup.declaration();
      var expected =
          Map.of(
              ObjectStoreException.Code.MISSING_OBJECT,
              ContentClaimException.Code.MISSING_LOCAL,
              ObjectStoreException.Code.INTEGRITY_FAILURE,
              ContentClaimException.Code.INTEGRITY_FAILURE,
              ObjectStoreException.Code.BUSY,
              ContentClaimException.Code.BUSY,
              ObjectStoreException.Code.STORE_IN_USE,
              ContentClaimException.Code.BUSY,
              ObjectStoreException.Code.LIMIT_EXCEEDED,
              ContentClaimException.Code.UNSUPPORTED_REUSE,
              ObjectStoreException.Code.IO_FAILURE,
              ContentClaimException.Code.STORAGE_UNAVAILABLE,
              ObjectStoreException.Code.CLOSED,
              ContentClaimException.Code.INVALID_STATE);
      for (var entry : expected.entrySet()) {
        var cause = new ObjectStoreException(entry.getKey(), "synthetic local failure");
        ObjectStore failedStore =
            new ObjectStore() {
              public StoredObject publish(
                  InputStream s, ExpectedObjectChecksum e, long m, CancellationToken c) {
                throw new AssertionError("No publication");
              }

              public StoredObject publishVerified(
                  InputStream s, long l, long m, CompletionVerification v, CancellationToken c) {
                throw new AssertionError("No publication");
              }

              public Optional<StoredObject> verify(ObjectDigest d, CancellationToken c) {
                throw new AssertionError("Lease required");
              }

              public ObjectReadLease openRead(ObjectDigest d, CancellationToken c) {
                throw cause;
              }

              public void close() {}
            };
        try (var gateway = new OfficialMetadataReuseGateway(failedStore)) {
          for (Runnable operation :
              List.<Runnable>of(
                  () ->
                      gateway.readDeclaration(
                          declaration.build(),
                          declaration.configuration(),
                          declaration.kind(),
                          LookupBudgets.defaults(),
                          CancellationToken.none()),
                  () ->
                      gateway.validateDeclaration(
                          declaration, LookupBudgets.defaults(), CancellationToken.none()),
                  () ->
                      gateway.replay(
                          plan(setup), LookupBudgets.defaults(), CancellationToken.none()),
                  () ->
                      gateway.inspectAssociatedInstall(
                          plan, association, LookupBudgets.defaults(), CancellationToken.none()))) {
            var failure = assertThrows(ContentClaimException.class, operation::run);
            assertEquals(entry.getValue(), failure.code());
            assertSame(cause, failure.getCause());
          }
        }
      }
    }
  }
}
