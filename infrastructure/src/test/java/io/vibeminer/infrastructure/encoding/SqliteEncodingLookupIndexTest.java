package io.vibeminer.infrastructure.encoding;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.buildopening.*;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.discoveryhistory.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.protocol.tact.blte.StreamingDecodedBlteInputStream;
import io.vibeminer.protocol.tact.config.TactConfigParser;
import io.vibeminer.protocol.tact.download.DownloadInventoryReader;
import io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SqliteEncodingLookupIndexTest {
  @TempDir Path dir;
  static final CancellationToken NONE = CancellationToken.none();
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  static final SqliteEncodingLookupIndex.Limits LIMITS =
      new SqliteEncodingLookupIndex.Limits(10000, 10000, 10000000, 2, 10000, 10, 100);

  static MetadataStreamBudgets budgets() {
    return new MetadataStreamBudgets(
        1024,
        1000000,
        10000,
        Duration.ofSeconds(30),
        1000000,
        2000000,
        new StreamingDecodedBlteInputStream.Limits(
            4096, 100, 1000000, 1000000, 1000000, 1000000, 1000000, 1024),
        new TactConfigParser.Limits(65536, 100, 4096, 100, 1000000, 1000000),
        EncodingLookupStagingTest.PARSER,
        new DownloadInventoryReader.Limits(1000000, 10000, 100, 4096, 4096, 100000, 255),
        new DownloadSizeInventoryReader.Limits(1000000, 10000, 100, 4096, 4096, 100000));
  }

  class Fixture implements AutoCloseable {
    final SqliteCatalog sql = new SqliteCatalog(dir.resolve("catalog.sqlite"));
    final FileObjectStore store = new FileObjectStore(dir.resolve("objects"));
    final SqliteBuildObjectCatalog objects = new SqliteBuildObjectCatalog(sql, store);
    final SqliteBuildLibraryRepository library = new SqliteBuildLibraryRepository(sql);
    final BuildMetadataAdmissionRepository admissions =
        new BuildMetadataAdmissionRepository(sql, store);
    final BuildMetadataAdmissionFacts facts;
    final SqliteEncodingLookupIndex index;

    Fixture(boolean badDecodedKey, boolean commit) throws Exception {
      try (var c = sql.connect()) {
        BuildMetadataAdmissionSql.migrate(c);
        EncodingLookupSchema.migrate(c);
      }
      var en = EncodingLookupStagingTest.fixture();
      var encoded = blte(en);
      String ek = hash("MD5", Arrays.copyOf(encoded, 36)),
          ck = badDecodedKey ? "b".repeat(32) : hash("MD5", en);
      byte[]
          config =
              utf(
                  "root = "
                      + "a".repeat(32)
                      + "\nencoding = "
                      + ck
                      + " "
                      + ek
                      + "\nencoding-size = "
                      + en.length
                      + " "
                      + encoded.length
                      + "\ninstall = "
                      + "c".repeat(32)
                      + " "
                      + "d".repeat(32)
                      + "\ninstall-size = 20 18\n"),
          cdn = utf("archives = " + "e".repeat(32) + "\n");
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
      new SqliteDiscoveryHistoryRepository(sql)
          .append(event, DiscoveryHistoryLimits.defaults(), NONE);
      var reg =
          library.recordObservation(
              event.eventId(),
              BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
              Optional.empty(),
              NOW,
              NONE);
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
        boolean encoding = role == BuildMetadataAdmission.Role.ENCODING;
        var kind =
            role == BuildMetadataAdmission.Role.ROOT
                ? MetadataStructureAttestation.Kind.ROOT
                : encoding
                    ? MetadataStructureAttestation.Kind.ENCODING
                    : MetadataStructureAttestation.Kind.INSTALL;
        String key = declaration.source(kind).contentKey(), ekey = encoding ? ek : "d".repeat(32);
        byte[] bytes = encoding ? encoded : new byte[18];
        if (!encoding) bytes[0] = (byte) role.ordinal();
        long decoded = encoding ? en.length : 20;
        var object =
            store.publishVerified(
                new ByteArrayInputStream(bytes), bytes.length, bytes.length, t -> {}, NONE);
        var source =
            new ResolvedSourceIdentity(
                encoding
                    ? "tact.encoding.v1"
                    : role == BuildMetadataAdmission.Role.ROOT
                        ? "wow.root.tsfm.v1v2"
                        : "tact.install.v1",
                key + ";" + ekey + ";" + decoded + ";" + bytes.length,
                "fixture-v1");
        var alias =
            new VerifiedSourceAlias(
                source,
                object,
                new SourceVerificationEvidence(
                    "fixture-v1", "https://cdn.example.invalid/" + ekey, NOW));
        var proof =
            new MetadataContentProof(
                alias,
                ekey,
                key,
                OptionalLong.of(decoded),
                Optional.of(new DecodedContentAttestation(key, decoded, "fixture-v1")),
                Optional.of(new MetadataStructureAttestation(kind, "fixture-v1")));
        var canonical = new SqliteCanonicalContentRepository(sql, store);
        var slot = new ContentSlotKey("wow", key);
        var claim = canonical.reserve(slot, ekey, bytes.length, NONE);
        canonical.markPublishing(claim.token(), NONE);
        canonical.recordVerifiedCompletion(claim.token(), proof, NONE);
        canonical.completePublication(claim.token(), proof, NONE);
        roles.add(new BuildMetadataAdmissionFacts.MetadataFact(role, slot, claim.token(), proof));
      }
      facts =
          new BuildMetadataAdmissionFacts(
              reg.references().getFirst(),
              "enUS",
              "test-direct-v1",
              declaration,
              List.of(
                  new BuildMetadataAdmissionFacts.ConfigurationFact(
                      BuildMetadataAdmission.Role.BUILD_CONFIG, bca),
                  new BuildMetadataAdmissionFacts.ConfigurationFact(
                      BuildMetadataAdmission.Role.CDN_CONFIG, cca)),
              roles);
      if (commit) admissions.commit(facts, NONE);
      index = new SqliteEncodingLookupIndex(sql, store, admissions, UUID.randomUUID());
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

    SqliteEncodingLookupIndex.Session open() throws Exception {
      return index.open(facts.reference(), "enUS", facts.scopeRevision(), budgets(), LIMITS, NONE);
    }

    public void close() {
      store.close();
    }
  }

  @Test
  void realRetainedEnToSqlSessionRepeatedLookupAndClosedLease() throws Exception {
    try (var f = new Fixture(false, true)) {
      var s = f.open();
      var rows = s.lookup(List.of(EncodingLookupStagingTest.key(1)), NONE);
      assertEquals(3, rows.getFirst().candidates().size());
      for (int i = 0; i < 10; i++)
        assertEquals(rows, s.lookup(List.of(EncodingLookupStagingTest.key(1)), NONE));
      assertThrows(ObjectStoreException.class, f.store::close);
      s.close();
      assertThrows(
          IllegalStateException.class,
          () -> s.lookup(List.of(EncodingLookupStagingTest.key(1)), NONE));
      try (var second = f.open()) {
        assertEquals(s.artifactId(), second.artifactId());
        assertEquals(s.generation(), second.generation());
      }
    }
  }

  @Test
  void callerFactsWithoutAdmissionCannotAuthorizeIndex() throws Exception {
    try (var f = new Fixture(false, false)) {
      assertThrows(RuntimeException.class, f::open);
    }
  }

  @Test
  void falseLedgerDecodedCKeyFailsActualFullLocalEnAndPublishesNoReady() throws Exception {
    try (var f = new Fixture(true, true)) {
      assertThrows(IOException.class, f::open);
      try (var c = f.sql.connect();
          var q = c.createStatement();
          var r = q.executeQuery("SELECT count(*) FROM en_lookup_artifacts WHERE status='READY'")) {
        r.next();
        assertEquals(0, r.getInt(1));
      }
    }
  }

  @Test
  void revokedCanonicalSourceRejectsExistingSessionBeforeSqlRows() throws Exception {
    try (var f = new Fixture(false, true);
        var s = f.open()) {
      var en =
          f.facts.metadata().stream()
              .filter(x -> x.role() == BuildMetadataAdmission.Role.ENCODING)
              .findFirst()
              .orElseThrow();
      try (var c = f.sql.connect();
          var q =
              c.prepareStatement(
                  "UPDATE canonical_content_slots SET status='RECOVERY' WHERE token=?")) {
        q.setString(1, en.canonicalReservationToken().toString());
        q.executeUpdate();
      }
      assertThrows(
          RuntimeException.class, () -> s.lookup(List.of(EncodingLookupStagingTest.key(1)), NONE));
    }
  }

  @Test
  void repeatedQueriesNeverReopenOrDecodeTheEnSource() throws Exception {
    try (var f = new Fixture(false, true)) {
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      ObjectStore observed =
          new ObjectStore() {
            public StoredObject publish(
                InputStream i, ExpectedObjectChecksum e, long m, CancellationToken t) {
              throw new AssertionError("No source publication");
            }

            public StoredObject publishVerified(
                InputStream i, long n, long m, CompletionVerification v, CancellationToken t) {
              throw new AssertionError("No source publication");
            }

            public Optional<StoredObject> verify(ObjectDigest d, CancellationToken t) {
              return f.store.verify(d, t);
            }

            public ObjectReadLease openRead(ObjectDigest d, CancellationToken t) {
              reads.incrementAndGet();
              return f.store.openRead(d, t);
            }

            public void close() {
              throw new AssertionError("Borrowed store");
            }
          };
      var index = new SqliteEncodingLookupIndex(f.sql, observed, f.admissions, UUID.randomUUID());
      try (var session =
          index.open(
              f.facts.reference(), "enUS", f.facts.scopeRevision(), budgets(), LIMITS, NONE)) {
        assertEquals(1, reads.get());
        for (int n = 0; n < 20; n++)
          session.lookup(List.of(EncodingLookupStagingTest.key(1)), NONE);
        assertEquals(1, reads.get());
      }
    }
  }

  static byte[] utf(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  static String hash(String algorithm, byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
  }

  static byte[] blte(byte[] bytes) throws Exception {
    var payload = new ByteArrayOutputStream();
    payload.write('N');
    payload.write(bytes);
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.writeBytes("BLTE");
    o.writeInt(36);
    o.writeByte(15);
    o.writeByte(0);
    o.writeShort(1);
    o.writeInt(payload.size());
    o.writeInt(bytes.length);
    o.write(MessageDigest.getInstance("MD5").digest(payload.toByteArray()));
    o.write(payload.toByteArray());
    return b.toByteArray();
  }
}
