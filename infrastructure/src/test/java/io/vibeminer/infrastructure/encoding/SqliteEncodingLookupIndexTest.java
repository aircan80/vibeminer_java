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
      this(badDecodedKey, commit, EncodingLookupStagingTest.fixture());
    }

    Fixture(boolean badDecodedKey, boolean commit, byte[] en) throws Exception {
      try (var c = sql.connect()) {
        BuildMetadataAdmissionSql.migrate(c);
        EncodingLookupSchema.migrate(c);
      }
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

  @Test
  void reverseBatchPreservesOrderDuplicatesUnknownSizesAndOrphanPrefixCollision() throws Exception {
    try (var f = new Fixture(false, true, reverseFixture());
        var s = f.open()) {
      var exact = SqliteEncodingLookupIndex.ReverseQuery.exact(EncodingLookupStagingTest.key(10));
      var unknown = SqliteEncodingLookupIndex.ReverseQuery.exact(EncodingLookupStagingTest.key(11));
      var orphan = SqliteEncodingLookupIndex.ReverseQuery.exact(EncodingLookupStagingTest.key(12));
      var prefix =
          SqliteEncodingLookupIndex.ReverseQuery.prefix9(
              EncodingLookupStagingTest.key(10).substring(0, 18));
      var results = s.reverseBatch(List.of(exact, unknown, orphan, prefix, exact), NONE);
      assertEquals(
          List.of(exact, unknown, orphan, prefix, exact),
          results.stream().map(SqliteEncodingLookupIndex.ReverseResult::query).toList());
      assertEquals(3, results.get(0).matches().getFirst().origins().size());
      assertEquals(
          List.of(0, 1, 0),
          results.get(0).matches().getFirst().origins().stream()
              .map(SqliteEncodingLookupIndex.Origin::candidateOrdinal)
              .toList());
      assertTrue(results.get(1).matches().getFirst().encodedBytes().isEmpty());
      assertEquals(1, results.get(1).matches().getFirst().origins().size());
      assertEquals(200, results.get(2).matches().getFirst().encodedBytes().orElseThrow());
      assertTrue(results.get(2).matches().getFirst().origins().isEmpty());
      assertEquals(2, results.get(3).matches().size());
      assertTrue(results.get(3).matches().get(1).origins().isEmpty());
      assertEquals(results.get(0).matches(), results.get(4).matches());
      assertTrue(s.reverseExact(EncodingLookupStagingTest.key(99), NONE).isEmpty());
    }
  }

  @Test
  void reverseLimitsRefuseWholeBatchInsteadOfReturningPartialFacts() throws Exception {
    try (var f = new Fixture(false, true, reverseFixture())) {
      var bounds =
          new SqliteEncodingLookupIndex.Limits(
              10000,
              10000,
              10000000,
              2,
              10000,
              10,
              100,
              new SqliteEncodingLookupIndex.ReverseLimits(1, 2, 2, 10, 3, 100000));
      try (var s =
          f.index.open(
              f.facts.reference(), "enUS", f.facts.scopeRevision(), budgets(), bounds, NONE)) {
        assertEquals(
            EncodingLookupException.Code.LIMIT,
            assertThrows(
                    EncodingLookupException.class,
                    () ->
                        s.reversePrefix9(EncodingLookupStagingTest.key(10).substring(0, 18), NONE))
                .code());
        assertEquals(
            EncodingLookupException.Code.LIMIT,
            assertThrows(
                    EncodingLookupException.class,
                    () -> s.reverseExact(EncodingLookupStagingTest.key(10), NONE))
                .code());
        var q = SqliteEncodingLookupIndex.ReverseQuery.exact(EncodingLookupStagingTest.key(11));
        assertEquals(
            EncodingLookupException.Code.LIMIT,
            assertThrows(
                    EncodingLookupException.class, () -> s.reverseBatch(List.of(q, q, q, q), NONE))
                .code());
      }
    }
  }

  @Test
  void sessionSameTransactionValidationDoesNotCommitAndClosedSessionRejects() throws Exception {
    try (var f = new Fixture(false, true)) {
      var s = f.open();
      try (var c = f.sql.connect()) {
        c.setAutoCommit(false);
        s.validateInTransaction(c, NONE);
        try (var q = c.createStatement()) {
          q.execute("CREATE TABLE probe_rollback(value INTEGER)");
        }
        c.rollback();
        try (var q = c.createStatement()) {
          assertThrows(
              java.sql.SQLException.class, () -> q.executeQuery("SELECT * FROM probe_rollback"));
        }
      }
      s.close();
      try (var c = f.sql.connect()) {
        c.setAutoCommit(false);
        assertThrows(IllegalStateException.class, () -> s.validateInTransaction(c, NONE));
        c.rollback();
      }
      assertThrows(
          IllegalStateException.class,
          () -> s.reverseExact(EncodingLookupStagingTest.key(10), NONE));
    }
  }

  @Test
  void reverseSqlWorkBudgetAndCancellationFailWithoutPoisoningNextQuery() throws Exception {
    try (var f = new Fixture(false, true, reverseFixture())) {
      var bounds =
          new SqliteEncodingLookupIndex.Limits(
              10000,
              10000,
              10000000,
              2,
              10000,
              10,
              100,
              new SqliteEncodingLookupIndex.ReverseLimits(256, 65536, 256, 4096, 65536, 1000));
      try (var session =
          f.index.open(
              f.facts.reference(), "enUS", f.facts.scopeRevision(), budgets(), bounds, NONE)) {
        var q = SqliteEncodingLookupIndex.ReverseQuery.exact(EncodingLookupStagingTest.key(10));
        assertEquals(
            EncodingLookupException.Code.LIMIT,
            assertThrows(
                    EncodingLookupException.class,
                    () -> session.reverseBatch(java.util.Collections.nCopies(10, q), NONE))
                .code());
      }
      try (var session = f.open()) {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(
            java.util.concurrent.CancellationException.class,
            () ->
                session.reverseExact(
                    EncodingLookupStagingTest.key(10), () -> checks.incrementAndGet() > 8));
        assertEquals(
            3,
            session
                .reverseExact(EncodingLookupStagingTest.key(10), NONE)
                .getFirst()
                .origins()
                .size());
      }
    }
  }

  @Test
  void callerTransactionSeesItsOwnRevokedCanonicalAuthorityAndCanRollback() throws Exception {
    try (var f = new Fixture(false, true);
        var session = f.open();
        var c = f.sql.connect()) {
      c.setAutoCommit(false);
      session.validateInTransaction(c, NONE);
      var en =
          f.facts.metadata().stream()
              .filter(x -> x.role() == BuildMetadataAdmission.Role.ENCODING)
              .findFirst()
              .orElseThrow();
      try (var q =
          c.prepareStatement(
              "UPDATE canonical_content_slots SET status='RECOVERY' WHERE token=?")) {
        q.setString(1, en.canonicalReservationToken().toString());
        q.executeUpdate();
      }
      assertThrows(RuntimeException.class, () -> session.validateInTransaction(c, NONE));
      c.rollback();
      session.validateInTransaction(c, NONE);
      c.rollback();
    }
  }

  @Test
  void reverseDistinctContentAndReferenceCapsAreIndependent() throws Exception {
    try (var f = new Fixture(false, true, reverseFixture())) {
      var bounds =
          new SqliteEncodingLookupIndex.Limits(
              10000,
              10000,
              10000000,
              2,
              10000,
              2,
              100,
              new SqliteEncodingLookupIndex.ReverseLimits(10, 100, 1, 100, 100, 100000));
      try (var session =
          f.index.open(
              f.facts.reference(), "enUS", f.facts.scopeRevision(), budgets(), bounds, NONE)) {
        var q = SqliteEncodingLookupIndex.ReverseQuery.exact(EncodingLookupStagingTest.key(10));
        assertEquals(
            EncodingLookupException.Code.LIMIT,
            assertThrows(EncodingLookupException.class, () -> session.reverseExact(q.key(), NONE))
                .code());
        assertEquals(
            EncodingLookupException.Code.LIMIT,
            assertThrows(
                    EncodingLookupException.class,
                    () -> session.reverseBatch(List.of(q, q, q), NONE))
                .code());
        assertThrows(
            IllegalArgumentException.class,
            () -> SqliteEncodingLookupIndex.ReverseQuery.prefix9(q.key()));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SqliteEncodingLookupIndex.ReverseQuery.exact(
                    q.key().toUpperCase(java.util.Locale.ROOT).replace('A', 'G')));
      }
    }
  }

  @Test
  void ordinaryFanoutPageCompletesUnderExplicitQueryWorkBudget() throws Exception {
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.writeBytes("EN");
    o.writeByte(1);
    o.writeByte(16);
    o.writeByte(16);
    o.writeShort(1);
    o.writeShort(1);
    o.writeInt(64);
    o.writeInt(1);
    o.writeByte(0);
    o.writeInt(2);
    o.writeByte('n');
    o.writeByte(0);
    byte[][] records = new byte[64][];
    for (int n = 0; n < 64; n++) records[n] = EncodingLookupStagingTest.cp(n + 1, n, 10);
    EncodingLookupStagingTest.pages(o, records, false);
    EncodingLookupStagingTest.pages(o, new byte[][] {EncodingLookupStagingTest.ep(10, 100)}, true);
    try (var f = new Fixture(false, true, b.toByteArray())) {
      var bounds =
          new SqliteEncodingLookupIndex.Limits(
              10000,
              10000,
              10000000,
              2,
              10000,
              10,
              100,
              new SqliteEncodingLookupIndex.ReverseLimits(256, 65536, 256, 4096, 65536, 100000));
      try (var session =
          f.index.open(
              f.facts.reference(), "enUS", f.facts.scopeRevision(), budgets(), bounds, NONE)) {
        var query = SqliteEncodingLookupIndex.ReverseQuery.exact(EncodingLookupStagingTest.key(10));
        var page = session.reverseBatch(java.util.Collections.nCopies(10, query), NONE);
        assertEquals(10, page.size());
        assertTrue(
            page.stream()
                .allMatch(
                    x -> x.matches().size() == 1 && x.matches().getFirst().origins().size() == 64));
      }
    }
  }

  static byte[] reverseFixture() throws Exception {
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.writeBytes("EN");
    o.writeByte(1);
    o.writeByte(16);
    o.writeByte(16);
    o.writeShort(1);
    o.writeShort(1);
    o.writeInt(2);
    o.writeInt(3);
    o.writeByte(0);
    o.writeInt(2);
    o.writeByte('n');
    o.writeByte(0);
    EncodingLookupStagingTest.pages(
        o,
        new byte[][] {
          EncodingLookupStagingTest.cp(1, 5, 10, 10, 11), EncodingLookupStagingTest.cp(2, 6, 10)
        },
        false);
    var extra = EncodingLookupStagingTest.ep(10, 150);
    extra[15] = 1;
    EncodingLookupStagingTest.pages(
        o,
        new byte[][] {
          EncodingLookupStagingTest.ep(10, 100), extra, EncodingLookupStagingTest.ep(12, 200)
        },
        true);
    return b.toByteArray();
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
