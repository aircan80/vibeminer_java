package io.vibeminer.infrastructure.buildopening;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.discoveryhistory.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class BuildMetadataAdmissionRepositoryTest {
  @TempDir Path dir;
  static final CancellationToken NONE = CancellationToken.none();
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

  static String hash(String algorithm, byte[] b) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(b));
  }

  static byte[] utf(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  static final String ROOT = "a".repeat(32),
      EN = "b".repeat(32),
      IN = "c".repeat(32),
      E = "d".repeat(32);

  class Fixture implements AutoCloseable {
    final SqliteCatalog catalog = new SqliteCatalog(dir.resolve("catalog.sqlite"));
    final FileObjectStore store = new FileObjectStore(dir.resolve("objects"));
    final SqliteBuildObjectCatalog objects = new SqliteBuildObjectCatalog(catalog, store);
    final SqliteBuildLibraryRepository library = new SqliteBuildLibraryRepository(catalog);
    final BuildMetadataAdmissionRepository repository =
        new BuildMetadataAdmissionRepository(catalog, store);
    final BuildMetadataAdmissionFacts facts;

    Fixture() throws Exception {
      this("archives = " + "e".repeat(32) + "\n");
    }

    Fixture(String cdnText) throws Exception {
      byte[] config =
          utf(
              "root = "
                  + ROOT
                  + "\nencoding = "
                  + EN
                  + " "
                  + E
                  + "\nencoding-size = 20 18\ninstall = "
                  + IN
                  + " "
                  + E
                  + "\ninstall-size = 20 18\n");
      byte[] cdn = utf(cdnText);
      String c = hash("MD5", config),
          d = hash("MD5", cdn),
          base = "https://us.version.battle.net/v2/products/wow/";
      var event =
          new DiscoveryObservationMapper()
              .map(
                  UUID.randomUUID(),
                  "wow",
                  "us",
                  NOW,
                  new SourceDocument(
                      base + "versions",
                      NOW,
                      utf(
                          "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|BuildId!DEC:4|VersionsName!STRING:0\n"
                              + "us|"
                              + c
                              + "|"
                              + d
                              + "|1|1.0\n")),
                  new SourceDocument(
                      base + "cdns",
                      NOW,
                      utf(
                          "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\n"
                              + "us|tpr/wow|cdn.example.invalid\n")));
      new SqliteDiscoveryHistoryRepository(catalog)
          .append(event, DiscoveryHistoryLimits.defaults(), NONE);
      var registration =
          library.recordObservation(
              event.eventId(),
              BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
              Optional.empty(),
              NOW,
              NONE);
      var ref = registration.references().getFirst();
      var build = event.builds().getFirst().build();
      var bca = config(build, config, c, "CONFIGURATION");
      var cca = config(build, cdn, d, "CDN_CONFIGURATION");
      var declaration =
          new BuildMetadataDeclarationClassifier()
              .classify(
                  build, bca, config, BuildMetadataDeclarationClassifier.Limits.defaults(), NONE);
      var roles = new ArrayList<BuildMetadataAdmissionFacts.MetadataFact>();
      for (var role :
          List.of(
              BuildMetadataAdmission.Role.ROOT,
              BuildMetadataAdmission.Role.ENCODING,
              BuildMetadataAdmission.Role.INSTALL)) {
        var kind = BuildMetadataAdmissionFacts.kind(role);
        String ck = declaration.source(kind).contentKey();
        byte[] bytes = new byte[18];
        bytes[0] = (byte) role.ordinal();
        var object = store.publishVerified(new ByteArrayInputStream(bytes), 18, 18, t -> {}, NONE);
        var source =
            new ResolvedSourceIdentity(
                switch (role) {
                  case ROOT -> "wow.root.tsfm.v1v2";
                  case INSTALL -> "tact.install.v1";
                  default -> "tact.encoding.v1";
                },
                ck + ";" + E + ";20;18",
                "fixture-v1");
        var alias =
            new VerifiedSourceAlias(
                source,
                object,
                new SourceVerificationEvidence(
                    "fixture-v1", "https://cdn.example.invalid/" + E, NOW));
        var proof =
            new MetadataContentProof(
                alias,
                E,
                ck,
                OptionalLong.of(20),
                Optional.of(new DecodedContentAttestation(ck, 20, "fixture-v1")),
                Optional.of(new MetadataStructureAttestation(kind, "fixture-v1")));
        var canonical = new SqliteCanonicalContentRepository(catalog, store);
        var slot = new ContentSlotKey("wow", ck);
        var claim = canonical.reserve(slot, E, 18, NONE);
        canonical.markPublishing(claim.token(), NONE);
        canonical.recordVerifiedCompletion(claim.token(), proof, NONE);
        canonical.completePublication(claim.token(), proof, NONE);
        roles.add(new BuildMetadataAdmissionFacts.MetadataFact(role, slot, claim.token(), proof));
      }
      facts =
          new BuildMetadataAdmissionFacts(
              ref,
              "enUS",
              "test-direct-v1",
              declaration,
              List.of(
                  new BuildMetadataAdmissionFacts.ConfigurationFact(
                      BuildMetadataAdmission.Role.BUILD_CONFIG, bca),
                  new BuildMetadataAdmissionFacts.ConfigurationFact(
                      BuildMetadataAdmission.Role.CDN_CONFIG, cca)),
              roles);
    }

    VerifiedSourceAlias config(BuildSnapshot b, byte[] bytes, String key, String role)
        throws Exception {
      var o =
          store.publishVerified(
              new ByteArrayInputStream(bytes), bytes.length, bytes.length, t -> {}, NONE);
      objects.registerSnapshot(b);
      var s = new ResolvedSourceIdentity("tact.config.raw-md5", key, "tact-config-raw-md5-v1");
      var e =
          new SourceVerificationEvidence(
              s.verificationSemantics(), "https://cdn.example.invalid/config/" + key, NOW);
      objects.attachVerifiedObject(b.snapshotId(), role, s, o, e, NONE);
      return new VerifiedSourceAlias(s, o, e);
    }

    public void close() {
      store.close();
    }
  }

  @Test
  void completeAdmissionReopensLongAuthenticatedCdnArchiveLine() throws Exception {
    String cdn =
        "archives = "
            + java.util.stream.IntStream.range(0, 1024)
                .mapToObj(i -> String.format("%032x", i))
                .collect(java.util.stream.Collectors.joining(" "))
            + "\n";
    assertTrue(utf(cdn).length > 16384);
    try (var f = new Fixture(cdn)) {
      var admitted = f.repository.commit(f.facts, NONE);
      assertEquals(
          admitted,
          new BuildMetadataAdmissionRepository(f.catalog, f.store)
              .find(f.facts.reference(), "enUS", f.facts.scopeRevision(), NONE)
              .orElseThrow()
              .admission());
    }
  }

  @Test
  void callerTransactionAuthorityDoesNotCommitAndRejectsForgedReference() throws Exception {
    try (var f = new Fixture()) {
      f.repository.commit(f.facts, NONE);
      try (var c = f.catalog.connect()) {
        c.setAutoCommit(false);
        try (var q = c.createStatement()) {
          q.execute("CREATE TABLE admission_transaction_probe(value INTEGER)");
          q.execute("INSERT INTO admission_transaction_probe VALUES(1)");
        }
        f.repository.validateInTransaction(c, f.facts, NONE);
        c.rollback();
        try (var q = c.createStatement();
            var rows =
                q.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE"
                        + " name='admission_transaction_probe'")) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1));
        }
        var r = f.facts.reference();
        var wrong =
            new BuildLibraryReference(
                r.key(),
                r.eventId(),
                r.observationSequence() + 1,
                r.snapshotId(),
                r.versionsRowOrdinal(),
                r.region(),
                r.observedAt(),
                r.versions(),
                r.cdns(),
                r.selectedCdn());
        var forged =
            new BuildMetadataAdmissionFacts(
                wrong,
                f.facts.locale(),
                f.facts.scopeRevision(),
                f.facts.declaration(),
                f.facts.configurations(),
                f.facts.metadata());
        assertThrows(
            BuildOpeningException.class, () -> f.repository.validateInTransaction(c, forged, NONE));
        assertEquals(
            BuildMetadataAdmissionRepository.closureId(f.facts),
            BuildMetadataAdmissionRepository.closureId(forged));
        c.rollback();
      }
    }
  }

  @Test
  void admissionAndAcknowledgementCommitTogetherAndReopenVerifiesAllRoles() throws Exception {
    try (var f = new Fixture()) {
      assertTrue(
          f.repository.find(f.facts.reference(), "enUS", f.facts.scopeRevision(), NONE).isEmpty());
      var result = f.repository.commit(f.facts, NONE);
      assertEquals(f.facts.admission(), result);
      assertTrue(
          f.library
              .findSeen(f.facts.reference().key().identity(), NONE)
              .orElseThrow()
              .acknowledgedAt()
              .isPresent());
      assertEquals(
          result,
          new BuildMetadataAdmissionRepository(f.catalog, f.store)
              .find(f.facts.reference(), "enUS", f.facts.scopeRevision(), NONE)
              .orElseThrow()
              .admission());
    }
  }

  @Test
  void forgedReferenceSequenceIsRejectedBeforeAnyAdmissionExists() throws Exception {
    try (var f = new Fixture()) {
      var r = f.facts.reference();
      var forged =
          new BuildLibraryReference(
              r.key(),
              r.eventId(),
              r.observationSequence() + 1,
              r.snapshotId(),
              r.versionsRowOrdinal(),
              r.region(),
              r.observedAt(),
              r.versions(),
              r.cdns(),
              r.selectedCdn());
      assertThrows(
          BuildOpeningException.class,
          () -> f.repository.find(forged, "enUS", f.facts.scopeRevision(), NONE));
      assertTrue(
          f.library.findSeen(r.key().identity(), NONE).orElseThrow().acknowledgedAt().isEmpty());
    }
  }

  @Test
  void forgedCanonicalTokenCannotAcknowledgeOrCommitPartialRoles() throws Exception {
    try (var f = new Fixture()) {
      var m = f.facts.metadata();
      var fake = new ArrayList<>(m);
      var p = m.getFirst();
      fake.set(
          0,
          new BuildMetadataAdmissionFacts.MetadataFact(
              p.role(), p.slot(), UUID.randomUUID(), p.proof()));
      var facts =
          new BuildMetadataAdmissionFacts(
              f.facts.reference(),
              "enUS",
              f.facts.scopeRevision(),
              f.facts.declaration(),
              f.facts.configurations(),
              fake);
      assertThrows(BuildOpeningException.class, () -> f.repository.commit(facts, NONE));
      assertTrue(
          f.library
              .findSeen(facts.reference().key().identity(), NONE)
              .orElseThrow()
              .acknowledgedAt()
              .isEmpty());
      assertTrue(
          f.repository.find(facts.reference(), "enUS", facts.scopeRevision(), NONE).isEmpty());
    }
  }

  @Test
  void sqlFailureAfterAdmissionInsertRollsBackBothAdmissionAndAck() throws Exception {
    try (var f = new Fixture()) {
      try (var c = f.catalog.connect();
          var s = c.createStatement()) {
        s.execute(
            "CREATE TRIGGER refuse_open BEFORE UPDATE OF acknowledged_at ON build_library_groups"
                + " BEGIN SELECT RAISE(ABORT,'injected');END");
      }
      assertThrows(BuildOpeningException.class, () -> f.repository.commit(f.facts, NONE));
      assertTrue(
          f.repository.find(f.facts.reference(), "enUS", f.facts.scopeRevision(), NONE).isEmpty());
      assertTrue(
          f.library
              .findSeen(f.facts.reference().key().identity(), NONE)
              .orElseThrow()
              .acknowledgedAt()
              .isEmpty());
    }
  }

  @Test
  void repeatedExactObservationKeepsOneClosureAndSeparateSuccessfulReceipts() throws Exception {
    try (var f = new Fixture()) {
      f.repository.commit(f.facts, NONE);
      var build = f.facts.declaration().build();
      var docs = build.evidence().documents();
      var event =
          new DiscoveryObservationMapper()
              .map(
                  UUID.randomUUID(),
                  "wow",
                  "us",
                  NOW.plusSeconds(40),
                  new SourceDocument(
                      docs.get(0).sourceUrl(), NOW.plusSeconds(38), docs.get(0).bytes()),
                  new SourceDocument(
                      docs.get(1).sourceUrl(), NOW.plusSeconds(39), docs.get(1).bytes()));
      new SqliteDiscoveryHistoryRepository(f.catalog)
          .append(event, DiscoveryHistoryLimits.defaults(), NONE);
      var registration =
          f.library.recordObservation(
              event.eventId(),
              BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
              Optional.empty(),
              NOW.plusSeconds(40),
              NONE);
      var next = registration.references().getFirst();
      var reused = f.repository.find(next, "enUS", f.facts.scopeRevision(), NONE).orElseThrow();
      assertEquals(f.facts.metadata(), reused.metadata());
      f.repository.commit(reused, NONE);
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        try (var r = q.executeQuery("SELECT count(*) FROM build_open_admissions")) {
          assertTrue(r.next());
          assertEquals(1, r.getInt(1));
        }
        try (var r = q.executeQuery("SELECT count(*) FROM build_open_receipts")) {
          assertTrue(r.next());
          assertEquals(2, r.getInt(1));
        }
      }
      assertEquals(
          build,
          new SqliteDiscoveryHistoryRepository(f.catalog)
              .read(f.facts.reference().eventId(), NONE)
              .orElseThrow()
              .builds()
              .getFirst()
              .build());
    }
  }

  @Test
  void changedCanonicalProofInvalidatesReopenWithoutErasingOriginalAdmission() throws Exception {
    try (var f = new Fixture()) {
      f.repository.commit(f.facts, NONE);
      try (var c = f.catalog.connect();
          var q =
              c.prepareStatement(
                  "UPDATE canonical_content_slots SET status='RECOVERY' WHERE token=?")) {
        q.setString(1, f.facts.metadata().getFirst().canonicalReservationToken().toString());
        q.executeUpdate();
      }
      assertEquals(
          BuildOpeningException.Code.NOT_READY,
          assertThrows(
                  BuildOpeningException.class,
                  () ->
                      f.repository.find(f.facts.reference(), "enUS", f.facts.scopeRevision(), NONE))
              .code());
      assertThrows(
          BuildOpeningException.class, () -> f.repository.validateCurrentAuthority(f.facts, NONE));
      assertTrue(
          f.library
              .findSeen(f.facts.reference().key().identity(), NONE)
              .orElseThrow()
              .acknowledgedAt()
              .isPresent());
    }
  }

  @Test
  void missingPhysicalPrerequisiteRevokesReadyEvenWithIntactAdmissionRows() throws Exception {
    try (var f = new Fixture()) {
      f.repository.commit(f.facts, NONE);
      var object = f.facts.metadata().getFirst().proof().object();
      try (var paths = Files.walk(dir.resolve("objects"))) {
        var physical =
            paths
                .filter(Files::isRegularFile)
                .filter(
                    p -> p.getFileName().toString().equals(object.digest().value().substring(2)))
                .findFirst()
                .orElseThrow();
        Files.delete(physical);
      }
      assertEquals(
          BuildOpeningException.Code.MISSING_METADATA,
          assertThrows(
                  BuildOpeningException.class,
                  () ->
                      f.repository.find(f.facts.reference(), "enUS", f.facts.scopeRevision(), NONE))
              .code());
    }
  }

  @Test
  void preCancelledCommitDoesNotAcknowledgeOrPublishContext() throws Exception {
    try (var f = new Fixture()) {
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> f.repository.commit(f.facts, () -> true));
      assertTrue(
          f.repository.find(f.facts.reference(), "enUS", f.facts.scopeRevision(), NONE).isEmpty());
      assertTrue(
          f.library
              .findSeen(f.facts.reference().key().identity(), NONE)
              .orElseThrow()
              .acknowledgedAt()
              .isEmpty());
    }
  }

  @Test
  void admissionAddsLocalPresenceWithoutClaimingCurrentPhysicalReadiness() throws Exception {
    try (var f = new Fixture()) {
      var reader = new SqliteBuildLibraryReader(f.catalog);
      var query = new BuildLibraryQuery("wow", BuildLibraryProjection.Filter.LOCAL);
      assertTrue(reader.page(query, Optional.empty(), 10, NONE).entries().isEmpty());
      f.repository.commit(f.facts, NONE);
      assertEquals(1, reader.page(query, Optional.empty(), 10, NONE).entries().size());
    }
  }

  @Test
  void versionFourteenUpgradeIsAdditiveAndPreservesHistory() throws Exception {
    try (var f = new Fixture()) {
      try (var c = f.catalog.connect();
          var s = c.createStatement()) {
        for (var t :
            List.of("build_open_receipts", "build_open_role_facts", "build_open_admissions"))
          s.execute("DROP TABLE " + t);
        s.execute("PRAGMA user_version=14");
      }
      try (var c = new SqliteCatalog(dir.resolve("catalog.sqlite")).connect();
          var s = c.createStatement();
          var r = s.executeQuery("PRAGMA user_version")) {
        assertTrue(r.next());
        assertEquals(SqliteCatalog.SCHEMA_VERSION, r.getInt(1));
      }
      assertTrue(
          f.repository.find(f.facts.reference(), "enUS", f.facts.scopeRevision(), NONE).isEmpty());
      assertEquals(5, f.repository.commit(f.facts, NONE).required().size());
    }
  }

  @Test
  void corruptPhysicalPrerequisiteCannotRetainReadyClaim() throws Exception {
    try (var f = new Fixture()) {
      f.repository.commit(f.facts, NONE);
      var object = f.facts.metadata().getFirst().proof().object();
      try (var paths = Files.walk(dir.resolve("objects"))) {
        var path =
            paths
                .filter(Files::isRegularFile)
                .filter(
                    p -> p.getFileName().toString().equals(object.digest().value().substring(2)))
                .findFirst()
                .orElseThrow();
        var bytes = Files.readAllBytes(path);
        bytes[0] ^= 1;
        Files.write(path, bytes);
      }
      assertEquals(
          BuildOpeningException.Code.CORRUPT_METADATA,
          assertThrows(
                  BuildOpeningException.class,
                  () ->
                      f.repository.find(f.facts.reference(), "enUS", f.facts.scopeRevision(), NONE))
              .code());
    }
  }

  @Test
  void missingMembershipBreaksAuthorityWithoutErasingImmutableAdmission() throws Exception {
    try (var f = new Fixture()) {
      f.repository.commit(f.facts, NONE);
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        q.executeUpdate("DELETE FROM build_object_memberships WHERE role LIKE 'BO1_%'");
      }
      assertThrows(
          BuildOpeningException.class, () -> f.repository.validateCurrentAuthority(f.facts, NONE));
      assertThrows(
          BuildOpeningException.class,
          () -> f.repository.find(f.facts.reference(), "enUS", f.facts.scopeRevision(), NONE));
    }
  }
}
