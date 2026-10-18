package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.domain.selectedfile.CanonicalReservation;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.bootstrap.OfficialBootstrapArtifactGateway;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataRepairPlanningTest {
  @TempDir Path directory;

  @Test
  void completedHeadStillMustMeetTheMonotonicDeadline() throws Exception {
    try (Fixture f = new Fixture(directory)) {
      AtomicInteger reads = new AtomicInteger();
      try (var gateway =
          new OfficialMetadataRepairGateway(
              f.installs,
              f.roots,
              f.metadata,
              f.clock,
              () -> reads.incrementAndGet() <= 2 ? 0 : Duration.ofSeconds(16).toNanos())) {
        var failure =
            assertThrows(
                MetadataRepairException.class,
                () -> gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none()));
        assertEquals(MetadataRepairException.Code.LIMIT, failure.code());
        assertEquals(1, f.client.requests.size());
      }
    }
  }

  @Test
  void rootUsesBorrowedCanonicalSessionOneHeadAndPreservesOriginalProof() throws Exception {
    try (var f = new Fixture(directory)) {
      int requests = f.rootFixture.requests.size();
      var plan = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      assertEquals(1, f.client.requests.size());
      assertEquals("HEAD", f.client.requests.getFirst().method());
      assertEquals(
          "identity",
          f.client.requests.getFirst().headers().firstValue("Accept-Encoding").orElseThrow());
      assertEquals(f.rootFixture.encodedRoot.length, plan.cost().encodedTransferBytes());
      assertSame(f.rootClaim.proof().orElseThrow(), plan.originalProof());
      assertTrue(plan.originalProof().decodedAttestation().isEmpty());
      assertEquals(requests, f.rootFixture.requests.size());
      f.gateway.validateRoot(f.selection, plan, CancellationToken.none());
      assertEquals(1, f.client.requests.size(), "validation performs no HEAD");
      try (var another = new OfficialFileLookupGateway(f.store, f.rootFixture, f.clock);
          var issuer =
              new OfficialMetadataRepairGateway(
                  f.installs, another, f.metadata, f.clock, f.nanos::get)) {
        assertThrows(
            FileLookupException.class,
            () -> issuer.prepareRoot(f.selection, f.rootClaim, CancellationToken.none()));
      }
      assertEquals(1, f.client.requests.size(), "another root session cannot be substituted");
    }
  }

  @Test
  void installReplaysRawRetainedConfigurationAndValidatesWithoutHttp() throws Exception {
    try (var f = new Fixture(directory)) {
      f.client.length = f.install.encodedBytes();
      var plan =
          f.gateway.prepareInstall(
              f.install, f.installConfiguration, f.installClaim, CancellationToken.none());
      assertEquals(1, plan.cost().maximumPayloadRequests());
      assertEquals(f.install.encodingKey(), plan.sourceObservation().resourceKey());
      f.gateway.validateInstall(f.install, plan, CancellationToken.none());
      assertEquals(1, f.client.requests.size());
      var bad =
          new InstallPlan(
              f.install.build(),
              f.install.configuration(),
              "e".repeat(32),
              f.install.encodingKey(),
              f.install.decodedBytes(),
              f.install.encodedBytes());
      assertThrows(
          MetadataRepairException.class,
          () ->
              f.gateway.prepareInstall(
                  bad, f.installConfiguration, f.installClaim, CancellationToken.none()));
      var forgedDocument =
          new SourceDocument(
              "https://evil.example/fake",
              f.install.configuration().capturedAt(),
              f.install.configuration().bytes());
      var forged =
          new InstallPlan(
              f.install.build(),
              forgedDocument,
              f.install.contentKey(),
              f.install.encodingKey(),
              f.install.decodedBytes(),
              f.install.encodedBytes());
      assertThrows(
          RuntimeException.class,
          () ->
              f.gateway.prepareInstall(
                  forged, f.installConfiguration, f.installClaim, CancellationToken.none()));
      assertEquals(1, f.client.requests.size());
    }
  }

  @Test
  void forgedExpiredEvictedAndAlreadyRepairBearingPlansNeverRefreshConsent() throws Exception {
    try (var f = new Fixture(directory)) {
      var first = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      var changed =
          new MetadataRepairPlan(
              UUID.randomUUID(),
              first.reservationToken(),
              first.request(),
              first.originalProof(),
              first.sourceObservation(),
              first.cost(),
              first.issuedAt(),
              first.expiresAt());
      assertThrows(
          MetadataRepairException.class,
          () -> f.gateway.validateRoot(f.selection, changed, CancellationToken.none()));
      var base = f.selection.plan();
      var annotated =
          new RootPlan(
              base.sessionId(),
              base.build(),
              base.configuration(),
              base.contentKey(),
              base.decodedBytes(),
              base.candidates(),
              base.budgets(),
              Optional.of(first));
      assertThrows(
          MetadataRepairException.class,
          () ->
              f.gateway.prepareRoot(
                  new RootSelectionPlan(annotated, f.selection.candidate()),
                  f.rootClaim,
                  CancellationToken.none()));
      f.nanos.addAndGet(Duration.ofMinutes(6).toNanos());
      assertThrows(
          MetadataRepairException.class,
          () -> f.gateway.validateRoot(f.selection, first, CancellationToken.none()));
      assertEquals(1, f.client.requests.size());
      var willBeEvicted = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      MetadataRepairPlan newest = null;
      for (int i = 0; i < 16; i++)
        newest = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      var evicted = willBeEvicted;
      assertThrows(
          MetadataRepairException.class,
          () -> f.gateway.validateRoot(f.selection, evicted, CancellationToken.none()));
      f.gateway.validateRoot(f.selection, newest, CancellationToken.none());
    }
  }

  @Test
  void currentSelectionBAndNonmetadataHistoryFailBeforeHead() throws Exception {
    try (var f = new Fixture(directory)) {
      var proof = (MetadataContentProof) f.rootClaim.proof().orElseThrow();
      var differentSource =
          new ResolvedSourceIdentity(
              proof.originalAlias().source().namespace(),
              proof.declaredContentKey()
                  + ";"
                  + "e".repeat(32)
                  + ";"
                  + f.rootFixture.root.length
                  + ";"
                  + f.rootFixture.encodedRoot.length,
              proof.originalAlias().source().verificationSemantics());
      var alias =
          new VerifiedSourceAlias(
              differentSource,
              proof.object(),
              new SourceVerificationEvidence(
                  differentSource.verificationSemantics(),
                  proof.originalAlias().evidence().sourceUrl(),
                  Fixture.NOW));
      var other =
          new MetadataContentProof(
              alias,
              "e".repeat(32),
              proof.declaredContentKey(),
              proof.declaredDecodedBytes(),
              Optional.empty(),
              Optional.empty());
      var b =
          new ContentClaim(
              f.rootClaim.slot(),
              other.actualEncodingKey(),
              other.encodedBytes(),
              UUID.randomUUID(),
              CanonicalReservation.Status.RECOVERY,
              Optional.of(other));
      assertEquals(
          ContentClaimException.Code.REPLAN_REQUIRED,
          assertThrows(
                  ContentClaimException.class,
                  () -> f.gateway.prepareRoot(f.selection, b, CancellationToken.none()))
              .code());
      var pending =
          new ContentClaim(
              f.rootClaim.slot(),
              proof.actualEncodingKey(),
              proof.encodedBytes(),
              UUID.randomUUID(),
              CanonicalReservation.Status.PUBLISHING,
              Optional.empty());
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertThrows(
                  ContentClaimException.class,
                  () -> f.gateway.prepareRoot(f.selection, pending, CancellationToken.none()))
              .code());
      assertTrue(f.client.requests.isEmpty());
    }
  }

  @Test
  void strictHeadRejectsUnavailableRedirectEncodingAndWrongLength() throws Exception {
    try (var f = new Fixture(directory)) {
      for (int status : List.of(404, 302)) {
        f.client.status = status;
        assertThrows(
            MetadataRepairException.class,
            () -> f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none()));
      }
      f.client.status = 200;
      f.client.extra = Map.of("Content-Encoding", List.of("gzip"));
      assertEquals(
          MetadataRepairException.Code.INTEGRITY,
          assertThrows(
                  MetadataRepairException.class,
                  () -> f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none()))
              .code());
      f.client.extra = Map.of();
      f.client.length--;
      assertEquals(
          MetadataRepairException.Code.INTEGRITY,
          assertThrows(
                  MetadataRepairException.class,
                  () -> f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none()))
              .code());
      assertTrue(f.client.requests.stream().allMatch(r -> r.method().equals("HEAD")));
    }
  }

  @Test
  void retainedConfigurationFromAnotherOfficialMirrorIsReplayedWithoutRestamping()
      throws Exception {
    try (var f = new Fixture(directory)) {
      var bytes = f.install.configuration().bytes();
      var mirrorBuild = build(bytes, "kr.cdn.blizzard.com");
      var mirrored =
          f.installs.restoreConfiguration(
              mirrorBuild, f.installConfiguration, CancellationToken.none());
      f.client.length = mirrored.encodedBytes();
      var repair =
          f.gateway.prepareInstall(
              mirrored, f.installConfiguration, f.installClaim, CancellationToken.none());
      assertTrue(
          repair.sourceObservation().resourceUrl().startsWith("https://kr.cdn.blizzard.com/"));
      assertEquals(f.installConfiguration, repair.request().configuration());
      assertTrue(
          repair
              .originalProof()
              .originalAlias()
              .evidence()
              .sourceUrl()
              .startsWith("https://us.cdn.blizzard.com/"));
      f.gateway.validateInstall(mirrored, repair, CancellationToken.none());
      assertEquals(1, f.client.requests.size());
      var tampered = bytes.clone();
      tampered[0] ^= 1;
      var object =
          f.store.publish(
              new ByteArrayInputStream(tampered),
              new ExpectedObjectChecksum(
                  ChecksumAlgorithm.SHA256, hash("SHA-256", tampered), tampered.length),
              tampered.length,
              CancellationToken.none());
      var forged =
          new VerifiedSourceAlias(
              f.installConfiguration.source(), object, f.installConfiguration.evidence());
      assertThrows(
          BootstrapException.class,
          () ->
              f.gateway.prepareInstall(mirrored, forged, f.installClaim, CancellationToken.none()));
      assertEquals(1, f.client.requests.size(), "raw configuration MD5 failure must precede HEAD");
    }
  }

  @Test
  void encodedOnlyHistoryAndUnknownEnDoNotBecomeMetadataRepairAuthority() throws Exception {
    try (var f = new Fixture(directory)) {
      var original = (MetadataContentProof) f.rootClaim.proof().orElseThrow();
      var source = ContentRepresentation.sourceIdentity(original.actualEncodingKey());
      var alias =
          new VerifiedSourceAlias(
              source,
              original.object(),
              new SourceVerificationEvidence(
                  source.verificationSemantics(),
                  original.originalAlias().evidence().sourceUrl(),
                  Fixture.NOW));
      var receipt =
          new EncodedSourceReceipt(
              EncodedSourceReceipt.Kind.LOOSE,
              alias.evidence().sourceUrl(),
              original.actualEncodingKey(),
              original.encodedBytes(),
              0,
              original.encodedBytes(),
              Fixture.NOW,
              1,
              original.encodedBytes(),
              "e".repeat(64),
              Optional.empty());
      var representation =
          new ContentRepresentation(
              f.rootClaim.slot(), alias, EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS, receipt);
      var encoded =
          new ContentClaim(
              f.rootClaim.slot(),
              original.actualEncodingKey(),
              original.encodedBytes(),
              UUID.randomUUID(),
              CanonicalReservation.Status.RETAINED,
              Optional.of(new EncodedContentProof(representation)));
      assertEquals(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          assertThrows(
                  ContentClaimException.class,
                  () -> f.gateway.prepareRoot(f.selection, encoded, CancellationToken.none()))
              .code());
      var root = f.selection.plan();
      var unknown = new RootCandidate(f.selection.candidate().encodingKey(), -1, 0);
      var plan =
          new RootPlan(
              root.sessionId(),
              root.build(),
              root.configuration(),
              root.contentKey(),
              root.decodedBytes(),
              List.of(unknown),
              root.budgets());
      assertThrows(IllegalArgumentException.class, () -> new RootSelectionPlan(plan, unknown));
      assertTrue(f.client.requests.isEmpty());
    }
  }

  @Test
  void ambiguousHeadersBodyAndChangedActualUriCannotIssueConsent() throws Exception {
    try (var f = new Fixture(directory)) {
      f.client.extra =
          Map.of(
              "Content-Length",
              List.of(Long.toString(f.client.length), Long.toString(f.client.length)));
      assertEquals(
          MetadataRepairException.Code.INTEGRITY,
          assertThrows(
                  MetadataRepairException.class,
                  () -> f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none()))
              .code());
      f.client.extra = Map.of();
      f.client.alteredUri = true;
      assertEquals(
          MetadataRepairException.Code.INTEGRITY,
          assertThrows(
                  MetadataRepairException.class,
                  () -> f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none()))
              .code());
      f.client.alteredUri = false;
      f.client.body = new byte[] {1};
      assertThrows(
          MetadataRepairException.class,
          () -> f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none()));
      assertEquals(3, f.client.requests.size());
    }
  }

  @Test
  void cancellationDeadlineAndCloseStopOnlyTheOwnedHead() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      try (var f = new Fixture(directory.resolve(Integer.toString(mode)))) {
        f.client.stalled = true;
        var cancel = new AtomicBoolean();
        var worker = Executors.newSingleThreadExecutor();
        try {
          var request =
              worker.submit(() -> f.gateway.prepareRoot(f.selection, f.rootClaim, cancel::get));
          assertTrue(f.client.started.await(5, TimeUnit.SECONDS));
          if (mode == 0) cancel.set(true);
          else if (mode == 1) f.nanos.addAndGet(Duration.ofSeconds(16).toNanos());
          else f.gateway.close();
          var failure =
              assertThrows(ExecutionException.class, () -> request.get(5, TimeUnit.SECONDS))
                  .getCause();
          if (mode == 1)
            assertEquals(
                MetadataRepairException.Code.LIMIT, ((MetadataRepairException) failure).code());
          else if (mode == 0) assertInstanceOf(CancellationException.class, failure);
          else
            assertTrue(
                failure instanceof CancellationException
                    || failure instanceof MetadataRepairException repair
                        && repair.code() == MetadataRepairException.Code.CLOSED);
          assertTrue(f.client.stopped.await(5, TimeUnit.SECONDS));
          assertEquals(1, f.client.requests.size());
        } finally {
          worker.shutdownNow();
        }
        if (mode == 2) {
          assertEquals(
              MetadataRepairException.Code.CLOSED,
              assertThrows(
                      MetadataRepairException.class,
                      () ->
                          f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none()))
                  .code());
          f.client.stalled = false;
          try (var other =
              new OfficialMetadataRepairGateway(
                  f.installs, f.roots, f.metadata, f.clock, f.nanos::get)) {
            assertNotNull(other.prepareRoot(f.selection, f.rootClaim, CancellationToken.none()));
          }
        }
      }
    }
  }

  @Test
  void alteredFullIssuedTupleCannotReplaceOrInvalidateTheGenuineConsent() throws Exception {
    try (var f = new Fixture(directory)) {
      var plan = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      var less =
          new MetadataRepairCost(
              plan.cost().encodedTransferBytes(),
              plan.cost().maximumAdditionalPhysicalBytes(),
              1,
              plan.cost().maximumDecodedWorkBytes(),
              plan.cost().maximumPayloadDuration());
      var costChanged =
          new MetadataRepairPlan(
              plan.issuanceId(),
              plan.reservationToken(),
              plan.request(),
              plan.originalProof(),
              plan.sourceObservation(),
              less,
              plan.issuedAt(),
              plan.expiresAt());
      var tokenChanged =
          new MetadataRepairPlan(
              plan.issuanceId(),
              UUID.randomUUID(),
              plan.request(),
              plan.originalProof(),
              plan.sourceObservation(),
              plan.cost(),
              plan.issuedAt(),
              plan.expiresAt());
      var proof = plan.originalProof();
      var alias =
          new VerifiedSourceAlias(
              proof.originalAlias().source(),
              proof.object(),
              new SourceVerificationEvidence(
                  proof.originalAlias().source().verificationSemantics(),
                  proof.originalAlias().evidence().sourceUrl(),
                  Instant.EPOCH.plusSeconds(1)));
      var restamped =
          new MetadataContentProof(
              alias,
              proof.actualEncodingKey(),
              proof.declaredContentKey(),
              proof.declaredDecodedBytes(),
              proof.decodedAttestation(),
              proof.structuralAttestation());
      var evidenceChanged =
          new MetadataRepairPlan(
              plan.issuanceId(),
              plan.reservationToken(),
              plan.request(),
              restamped,
              plan.sourceObservation(),
              plan.cost(),
              plan.issuedAt(),
              plan.expiresAt());
      for (var forged : List.of(costChanged, tokenChanged, evidenceChanged)) {
        assertEquals(
            MetadataRepairException.Code.INVALID_PLAN,
            assertThrows(
                    MetadataRepairException.class,
                    () -> f.gateway.validateRoot(f.selection, forged, CancellationToken.none()))
                .code());
        f.gateway.validateRoot(f.selection, plan, CancellationToken.none());
      }
      assertEquals(1, f.client.requests.size());
      f.clock.current = plan.expiresAt();
      assertEquals(
          MetadataRepairException.Code.INVALID_PLAN,
          assertThrows(
                  MetadataRepairException.class,
                  () -> f.gateway.validateRoot(f.selection, plan, CancellationToken.none()))
              .code());
      assertEquals(1, f.client.requests.size());
    }
  }

  @Test
  void evictedBorrowedRootSessionAndForgedOriginalRouteFailWithoutAnotherHead() throws Exception {
    try (var f = new Fixture(directory)) {
      var issued = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      var proof = issued.originalProof();
      var evilAlias =
          new VerifiedSourceAlias(
              proof.originalAlias().source(),
              proof.object(),
              new SourceVerificationEvidence(
                  proof.originalAlias().source().verificationSemantics(),
                  "https://evil.example/fake",
                  Instant.EPOCH));
      var evil =
          new MetadataContentProof(
              evilAlias,
              proof.actualEncodingKey(),
              proof.declaredContentKey(),
              proof.declaredDecodedBytes(),
              proof.decodedAttestation(),
              proof.structuralAttestation());
      var fakeClaim =
          new ContentClaim(
              f.rootClaim.slot(),
              proof.actualEncodingKey(),
              proof.encodedBytes(),
              UUID.randomUUID(),
              CanonicalReservation.Status.RECOVERY,
              Optional.of(evil));
      assertEquals(
          MetadataRepairException.Code.INTEGRITY,
          assertThrows(
                  MetadataRepairException.class,
                  () -> f.gateway.prepareRoot(f.selection, fakeClaim, CancellationToken.none()))
              .code());
      f.rootFixture.rejectNetwork = false;
      for (int i = 0; i < 16; i++)
        f.roots.prepareRoot(
            f.selection.plan().build(),
            Optional.of(f.selection.plan().configuration()),
            f.selection.plan().budgets(),
            CancellationToken.none());
      f.rootFixture.rejectNetwork = true;
      assertThrows(
          FileLookupException.class,
          () -> f.gateway.validateRoot(f.selection, issued, CancellationToken.none()));
      assertEquals(1, f.client.requests.size());
    }
  }

  static final class MutableClock extends Clock {
    Instant current = Fixture.NOW;

    public Instant instant() {
      return current;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  static final class Fixture implements AutoCloseable {
    static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    final FileObjectStore store;
    final RootLookupFixture rootFixture = new RootLookupFixture();
    final MutableClock clock = new MutableClock();
    final AtomicLong nanos = new AtomicLong();
    final HeadClient client = new HeadClient();
    final CdnMetadataTransport metadata =
        new CdnMetadataTransport(client, clock, Duration.ofSeconds(15));
    final OfficialBootstrapArtifactGateway installs;
    final OfficialFileLookupGateway roots;
    final OfficialMetadataRepairGateway gateway;
    final RootSelectionPlan selection;
    final ContentClaim rootClaim, installClaim;
    final InstallPlan install;
    final VerifiedSourceAlias installConfiguration;

    Fixture(Path path) throws Exception {
      store = new FileObjectStore(path);
      installs =
          new OfficialBootstrapArtifactGateway(
              store, new PinnedCdnTransport(client, clock, Duration.ofSeconds(15)), clock);
      roots = new OfficialFileLookupGateway(store, rootFixture, clock);
      var root =
          roots.prepareRoot(
              rootFixture.build("us.cdn.blizzard.com", "1.0"),
              Optional.empty(),
              LookupBudgets.defaults(),
              CancellationToken.none());
      selection = new RootSelectionPlan(root, root.candidates().getFirst());
      rootClaim =
          claim(
              roots.rootIdentity(selection),
              rootFixture.rootCKey,
              rootFixture.rootEKey,
              rootFixture.root.length,
              rootFixture.encodedRoot);
      client.length = rootFixture.encodedRoot.length;
      rootFixture.rejectNetwork = true;
      byte[] decoded = new byte[] {'I', 'N', 1, 16, 0, 0, 0, 0, 0, 0};
      var encoded = new ByteArrayOutputStream();
      var out = new DataOutputStream(encoded);
      out.writeBytes("BLTE");
      out.writeInt(0);
      out.writeByte('N');
      out.write(decoded);
      byte[] bytes = encoded.toByteArray();
      byte[] config =
          ("install = "
                  + hash("MD5", decoded)
                  + " "
                  + hash("MD5", bytes)
                  + "\ninstall-size = "
                  + decoded.length
                  + " "
                  + bytes.length
                  + "\n")
              .getBytes(StandardCharsets.UTF_8);
      var build = build(config, "us.cdn.blizzard.com");
      var source = installs.configurationIdentity(build);
      var object =
          store.publish(
              new ByteArrayInputStream(config),
              new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, build.buildConfig(), config.length),
              config.length,
              CancellationToken.none());
      var url =
          CdnRequest.configuration(CapturedBuildSource.resolve(build), build.buildConfig())
              .uri()
              .toString();
      installConfiguration =
          new VerifiedSourceAlias(
              source,
              object,
              new SourceVerificationEvidence(source.verificationSemantics(), url, NOW));
      install =
          installs.restoreConfiguration(build, installConfiguration, CancellationToken.none());
      installClaim =
          claim(
              installs.installIdentity(install),
              install.contentKey(),
              install.encodingKey(),
              install.decodedBytes(),
              bytes);
      gateway = new OfficialMetadataRepairGateway(installs, roots, metadata, clock, nanos::get);
    }

    public void close() {
      gateway.close();
      roots.close();
      store.close();
    }
  }

  static ContentClaim claim(
      ResolvedSourceIdentity source, String ckey, String ekey, long decoded, byte[] encoded)
      throws Exception {
    var object = new StoredObject(new ObjectDigest(hash("SHA-256", encoded)), encoded.length);
    var alias =
        new VerifiedSourceAlias(
            source,
            object,
            new SourceVerificationEvidence(
                source.verificationSemantics(),
                "https://us.cdn.blizzard.com/tpr/wow/data/"
                    + ekey.substring(0, 2)
                    + "/"
                    + ekey.substring(2, 4)
                    + "/"
                    + ekey,
                Instant.EPOCH));
    var proof =
        new MetadataContentProof(
            alias, ekey, ckey, OptionalLong.of(decoded), Optional.empty(), Optional.empty());
    return new ContentClaim(
        new ContentSlotKey("wow", ckey),
        ekey,
        encoded.length,
        UUID.randomUUID(),
        CanonicalReservation.Status.RECOVERY,
        Optional.of(proof));
  }

  static BuildSnapshot build(byte[] config, String host) throws Exception {
    var key = hash("MD5", config);
    String cdn = "d".repeat(32);
    String versions =
        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|"
            + key
            + "|"
            + cdn
            + "|1.0\n";
    String cdns =
        "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n"
            + "us|tpr/wow|"
            + host
            + "|https://"
            + host
            + "/|tpr/configs/data\n";
    return new BuildSnapshot(
        "wow",
        "Synthetic",
        "Published",
        "us",
        "1.0",
        key,
        cdn,
        Fixture.NOW,
        false,
        new BuildEvidence(
            "",
            "",
            List.of(
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/versions",
                    Fixture.NOW,
                    versions.getBytes(StandardCharsets.UTF_8)),
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/cdns",
                    Fixture.NOW,
                    cdns.getBytes(StandardCharsets.UTF_8)))));
  }

  static String hash(String algorithm, byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
  }

  static final class HeadClient extends HttpClient {
    int status = 200;
    long length;
    Map<String, List<String>> extra = Map.of();
    final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());
    volatile boolean stalled, alteredUri;
    byte[] body = new byte[0];
    final CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      assertEquals("HEAD", request.method(), "planning cannot fetch payload or config");
      requests.add(request);
      var fields = new HashMap<>(extra);
      fields.putIfAbsent("Content-Length", List.of(Long.toString(length)));
      var headers = HttpHeaders.of(fields, (a, b) -> true);
      var subscriber =
          handler.apply(
              new HttpResponse.ResponseInfo() {
                public int statusCode() {
                  return status;
                }

                public HttpHeaders headers() {
                  return headers;
                }

                public Version version() {
                  return Version.HTTP_1_1;
                }
              });
      subscriber.onSubscribe(
          new Flow.Subscription() {
            public void request(long n) {}

            public void cancel() {
              stopped.countDown();
            }
          });
      started.countDown();
      if (!stalled) {
        if (body.length != 0) subscriber.onNext(List.of(ByteBuffer.wrap(body)));
        subscriber.onComplete();
      }
      return subscriber
          .getBody()
          .toCompletableFuture()
          .thenApply(
              body ->
                  new HttpResponse<T>() {
                    public int statusCode() {
                      return status;
                    }

                    public HttpRequest request() {
                      return request;
                    }

                    public Optional<HttpResponse<T>> previousResponse() {
                      return Optional.empty();
                    }

                    public HttpHeaders headers() {
                      return headers;
                    }

                    public T body() {
                      return body;
                    }

                    public Optional<SSLSession> sslSession() {
                      return Optional.empty();
                    }

                    public URI uri() {
                      return alteredUri
                          ? URI.create("https://evil.example/changed")
                          : request.uri();
                    }

                    public Version version() {
                      return Version.HTTP_1_1;
                    }
                  });
    }

    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    public SSLContext sslContext() {
      try {
        return SSLContext.getDefault();
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    public Version version() {
      return Version.HTTP_1_1;
    }

    public Optional<Executor> executor() {
      return Optional.empty();
    }

    public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) {
      throw new AssertionError();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
      throw new AssertionError();
    }
  }
}
