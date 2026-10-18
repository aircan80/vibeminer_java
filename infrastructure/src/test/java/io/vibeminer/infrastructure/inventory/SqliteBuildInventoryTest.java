package io.vibeminer.infrastructure.inventory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.format.wow.root.FullRootInventory;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.buildopening.*;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.discoveryhistory.*;
import io.vibeminer.infrastructure.encoding.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.protocol.tact.blte.StreamingDecodedBlteInputStream;
import io.vibeminer.protocol.tact.config.TactConfigParser;
import io.vibeminer.protocol.tact.download.DownloadInventoryReader;
import io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryReader;
import io.vibeminer.protocol.tact.encoding.FullEncodingInventory;
import io.vibeminer.protocol.tact.install.InstallManifestParser;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SqliteBuildInventoryTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  static final SqliteEncodingLookupIndex.Limits EN_LIMITS =
      new SqliteEncodingLookupIndex.Limits(10000, 10000, 10000000, 10, 10000, 10, 100);
  static final SqliteBuildInventory.Limits LIMITS =
      new SqliteBuildInventory.Limits(
          10000,
          10000000,
          10,
          10000,
          new FullRootInventory.Limits(1000000, 10000, 100, 10000, 1000000),
          new InstallManifestParser.Limits(1000000, 10000, 100, 4096));

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
        new FullEncodingInventory.Limits(
            1000000, 10000, 10000, 100, 100000, 1024, 1000, 255, 100, 10000, 1000),
        new DownloadInventoryReader.Limits(1000000, 10000, 100, 4096, 4096, 100000, 255),
        new DownloadSizeInventoryReader.Limits(1000000, 10000, 100, 4096, 4096, 100000));
  }

  class Fixture implements AutoCloseable {
    final SqliteCatalog sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    final FileObjectStore store;
    final SqliteBuildObjectCatalog objects;
    final SqliteCanonicalContentRepository canonical;
    final BuildMetadataAdmissionRepository admissions;
    final BuildMetadataAdmissionFacts facts;
    final SqliteBuildInventory inventory;
    final String payloadC, payloadE;

    Fixture(boolean malformedRoot, boolean missingMapping, boolean prefixCollision)
        throws Exception {
      this(malformedRoot, missingMapping, prefixCollision, false);
    }

    Fixture(
        boolean malformedRoot, boolean missingMapping, boolean prefixCollision, boolean zeroEncoded)
        throws Exception {
      this(malformedRoot, missingMapping, prefixCollision, zeroEncoded, false);
    }

    Fixture(
        boolean malformedRoot,
        boolean missingMapping,
        boolean prefixCollision,
        boolean zeroEncoded,
        boolean officialSourceRow)
        throws Exception {
      this(malformedRoot, missingMapping, prefixCollision, zeroEncoded, officialSourceRow, false);
    }

    Fixture(
        boolean malformedRoot,
        boolean missingMapping,
        boolean prefixCollision,
        boolean zeroEncoded,
        boolean officialSourceRow,
        boolean metadataMember)
        throws Exception {
      this(
          malformedRoot,
          missingMapping,
          prefixCollision,
          zeroEncoded,
          officialSourceRow,
          metadataMember,
          FileObjectStore::new);
    }

    Fixture(
        boolean malformedRoot,
        boolean missingMapping,
        boolean prefixCollision,
        boolean zeroEncoded,
        boolean officialSourceRow,
        boolean metadataMember,
        java.util.function.Function<Path, FileObjectStore> storeFactory)
        throws Exception {
      store = storeFactory.apply(directory.resolve("objects"));
      objects = new SqliteBuildObjectCatalog(sql, store);
      canonical = new SqliteCanonicalContentRepository(sql, store);
      admissions = new BuildMetadataAdmissionRepository(sql, store);
      try (var c = sql.connect()) {
        BuildMetadataAdmissionSql.migrate(c);
        EncodingLookupSchema.migrate(c);
        InventorySchema.migrate(c);
      }
      byte[]
          payload = metadataMember ? new byte[] {'I', 'N', 1, 16, 0, 0, 0, 0, 0, 0} : new byte[100],
          encodedPayload = blte(payload);
      payloadC = md5(payload);
      payloadE = ekey(encodedPayload);
      var decoded =
          new EnumMap<BuildMetadataAdmission.Role, byte[]>(BuildMetadataAdmission.Role.class);
      decoded.put(BuildMetadataAdmission.Role.ROOT, root(payloadC, malformedRoot));
      decoded.put(
          BuildMetadataAdmission.Role.INSTALL, metadataMember ? payload : install(payloadC));
      decoded.put(
          BuildMetadataAdmission.Role.DOWNLOAD,
          download(payloadE, zeroEncoded ? 0 : encodedPayload.length));
      decoded.put(
          BuildMetadataAdmission.Role.SIZE,
          size(payloadE, zeroEncoded ? 0 : encodedPayload.length));
      decoded.put(
          BuildMetadataAdmission.Role.ENCODING,
          en(
              payloadC,
              payloadE,
              zeroEncoded ? 0 : encodedPayload.length,
              missingMapping,
              prefixCollision,
              payload.length));
      var encoded =
          new EnumMap<BuildMetadataAdmission.Role, byte[]>(BuildMetadataAdmission.Role.class);
      for (var entry : decoded.entrySet()) encoded.put(entry.getKey(), blte(entry.getValue()));
      StringBuilder config =
          new StringBuilder("root = " + md5(decoded.get(BuildMetadataAdmission.Role.ROOT)) + "\n");
      for (var entry :
          Map.of(
                  "encoding",
                  BuildMetadataAdmission.Role.ENCODING,
                  "install",
                  BuildMetadataAdmission.Role.INSTALL,
                  "download",
                  BuildMetadataAdmission.Role.DOWNLOAD,
                  "size",
                  BuildMetadataAdmission.Role.SIZE)
              .entrySet()) {
        var raw = decoded.get(entry.getValue());
        var bytes = encoded.get(entry.getValue());
        config
            .append(entry.getKey())
            .append(" = ")
            .append(md5(raw))
            .append(' ')
            .append(ekey(bytes))
            .append('\n')
            .append(entry.getKey())
            .append("-size = ")
            .append(raw.length)
            .append(' ')
            .append(bytes.length)
            .append('\n');
      }
      byte[] bc = utf(config.toString()), cc = utf("archives = " + "e".repeat(32) + "\n");
      String b = md5(bc), c = md5(cc), base = "https://us.version.battle.net/v2/products/wow/";
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
                              + b
                              + "|"
                              + c
                              + "|1|1.0\n")),
                  new SourceDocument(
                      base + "cdns",
                      NOW,
                      utf(
                          officialSourceRow
                              ? "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n"
                                    + "us|tpr/wow|us.cdn.blizzard.com|https://us.cdn.blizzard.com/|tpr/configs/data\n"
                              : "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\n"
                                  + "us|tpr/wow|cdn.example.invalid\n")));
      new SqliteDiscoveryHistoryRepository(sql)
          .append(event, DiscoveryHistoryLimits.defaults(), NONE);
      var reg =
          new SqliteBuildLibraryRepository(sql)
              .recordObservation(
                  event.eventId(),
                  BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
                  Optional.empty(),
                  NOW,
                  NONE);
      var build = event.builds().getFirst().build();
      var bca = config(build, bc, b, "CONFIGURATION");
      var cca = config(build, cc, c, "CDN_CONFIGURATION");
      var declaration =
          new BuildMetadataDeclarationClassifier()
              .classify(build, bca, bc, BuildMetadataDeclarationClassifier.Limits.defaults(), NONE);
      var roles = new ArrayList<BuildMetadataAdmissionFacts.MetadataFact>();
      for (var role : decoded.keySet()) {
        byte[] raw = decoded.get(role), bytes = encoded.get(role);
        String key = md5(raw), ek = ekey(bytes);
        var object =
            store.publishVerified(
                new ByteArrayInputStream(bytes), bytes.length, bytes.length, t -> {}, NONE);
        var source =
            new ResolvedSourceIdentity(
                switch (role) {
                  case ROOT -> "wow.root.tsfm.v1v2";
                  case INSTALL -> "tact.install.v1";
                  case ENCODING -> "tact.encoding.v1";
                  case DOWNLOAD -> "tact.download.v1v2v3";
                  case SIZE -> "tact.download-size.v1";
                  default -> throw new AssertionError();
                },
                key + ";" + ek + ";" + raw.length + ";" + bytes.length,
                "fixture-v1");
        var alias =
            new VerifiedSourceAlias(
                source,
                object,
                new SourceVerificationEvidence(
                    "fixture-v1", "https://cdn.example.invalid/" + ek, NOW));
        var kind =
            switch (role) {
              case ROOT -> MetadataStructureAttestation.Kind.ROOT;
              case INSTALL -> MetadataStructureAttestation.Kind.INSTALL;
              case ENCODING -> MetadataStructureAttestation.Kind.ENCODING;
              case DOWNLOAD -> MetadataStructureAttestation.Kind.DOWNLOAD;
              case SIZE -> MetadataStructureAttestation.Kind.DOWNLOAD_SIZE;
              default -> throw new AssertionError();
            };
        var proof =
            new MetadataContentProof(
                alias,
                ek,
                key,
                OptionalLong.of(raw.length),
                Optional.of(new DecodedContentAttestation(key, raw.length, "fixture-v1")),
                Optional.of(new MetadataStructureAttestation(kind, "fixture-v1")));
        var slot = new ContentSlotKey("wow", key);
        var claim = canonical.reserve(slot, ek, bytes.length, NONE);
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
      admissions.commit(facts, NONE);
      inventory =
          new SqliteBuildInventory(
              sql,
              store,
              admissions,
              new SqliteEncodingLookupIndex(sql, store, admissions, UUID.randomUUID()),
              canonical);
    }

    VerifiedSourceAlias config(BuildSnapshot build, byte[] bytes, String key, String role) {
      var object =
          store.publishVerified(
              new ByteArrayInputStream(bytes), bytes.length, bytes.length, t -> {}, NONE);
      objects.registerSnapshot(build);
      var source = new ResolvedSourceIdentity("tact.config.raw-md5", key, "tact-config-raw-md5-v1");
      var evidence =
          new SourceVerificationEvidence(
              source.verificationSemantics(), "https://cdn.example.invalid/config/" + key, NOW);
      objects.attachVerifiedObject(build.snapshotId(), role, source, object, evidence, NONE);
      return new VerifiedSourceAlias(source, object, evidence);
    }

    SqliteBuildInventory.Prepared prepare() throws IOException {
      return inventory.prepare(
          facts.reference(),
          facts.locale(),
          facts.scopeRevision(),
          budgets(),
          EN_LIMITS,
          LIMITS,
          NONE);
    }

    long count(String table, String condition) throws Exception {
      try (var c = sql.connect();
          var s = c.createStatement();
          var r = s.executeQuery("SELECT count(*) FROM " + table + " WHERE " + condition)) {
        r.next();
        return r.getLong(1);
      }
    }

    public void close() {
      store.close();
    }
  }

  @Test
  void actualFiveSourceProofsAllVariantsAndManifestRowsSealOneTarget() throws Exception {
    try (var f = new Fixture(false, false, false)) {
      var result = f.prepare();
      assertEquals(1, result.uniqueContentTargets());
      assertEquals(5, f.count("build_inventory_references", "1=1"));
      assertEquals(4, f.count("build_inventory_sources", "complete=1"));
      assertEquals(1, f.count("build_inventory_generations", "status='READY'"));
    }
  }

  @Test
  void malformedLateRootLeavesOnlyProvisionalGeneration() throws Exception {
    try (var f = new Fixture(true, false, false)) {
      assertThrows(IOException.class, f::prepare);
      assertEquals(0, f.count("build_inventory_generations", "status='READY'"));
      assertEquals(0, f.count("build_inventory_sources", "role='ROOT' AND complete=1"));
    }
  }

  @Test
  void missingEncodingMappingCannotTurnSupportedSubsetIntoFull() throws Exception {
    try (var f = new Fixture(false, true, false)) {
      assertThrows(IllegalStateException.class, f::prepare);
      assertEquals(0, f.count("build_inventory_generations", "status='READY'"));
      assertEquals(0, f.count("build_inventory_generations", "1=1"));
    }
  }

  @Test
  void dsPrefixCollisionCannotBeResolvedByAdvertisedSize() throws Exception {
    try (var f = new Fixture(false, false, true)) {
      assertThrows(IllegalStateException.class, f::prepare);
      assertEquals(0, f.count("build_inventory_generations", "status='READY'"));
      assertEquals(0, f.count("build_inventory_generations", "1=1"));
    }
  }

  @Test
  void canonicalAlternateDoesNotSatisfyExactDownloadExpectation() throws Exception {
    try (var f = new Fixture(false, false, false)) {
      f.canonical.reserve(new ContentSlotKey("wow", f.payloadC), "b".repeat(32), 99, NONE);
      assertThrows(IllegalStateException.class, f::prepare);
      assertEquals(0, f.count("build_inventory_generations", "status='READY'"));
      assertEquals(0, f.count("build_inventory_generations", "1=1"));
    }
  }

  @Test
  void reopeningReusesExactReadyGenerationAndLaterCanonicalConflictFailsClosed() throws Exception {
    try (var f = new Fixture(false, false, false)) {
      var first = f.prepare();
      var second = f.prepare();
      assertEquals(first, second);
      assertEquals(1, f.count("build_inventory_generations", "1=1"));
      f.canonical.reserve(new ContentSlotKey("wow", f.payloadC), "b".repeat(32), 99, NONE);
      assertThrows(IllegalStateException.class, f::prepare);
      assertEquals(1, f.count("build_inventory_generations", "status='READY'"));
    }
  }

  @Test
  void canceledPreparationReleasesAllStoreLeasesWithoutReadyPublication() throws Exception {
    try (var f = new Fixture(false, false, false)) {
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () ->
              f.inventory.prepare(
                  f.facts.reference(),
                  f.facts.locale(),
                  f.facts.scopeRevision(),
                  budgets(),
                  EN_LIMITS,
                  LIMITS,
                  () -> true));
      assertEquals(0, f.count("build_inventory_generations", "1=1"));
    }
  }

  @Test
  void knownZeroEncodedLengthIsTypedUnsupportedAndNeverReady() throws Exception {
    try (var f = new Fixture(false, false, false, true)) {
      var failure = assertThrows(InventoryException.class, f::prepare);
      assertEquals(InventoryException.Code.UNSUPPORTED_ZERO_LENGTH_ENCODING, failure.code());
      assertEquals(0, f.count("build_inventory_generations", "1=1"));
    }
  }

  @Test
  void readyReuseHoldsAllVerifiedMetadataLeasesThroughAuthorityReplay() throws Exception {
    try (var f = new Fixture(false, false, false)) {
      var ready = f.prepare();
      var active = new java.util.concurrent.atomic.AtomicInteger();
      var peak = new java.util.concurrent.atomic.AtomicInteger();
      var opens = new java.util.concurrent.atomic.AtomicInteger();
      ObjectStore observed =
          new ObjectStore() {
            public StoredObject publish(
                InputStream input,
                ExpectedObjectChecksum checksum,
                long maximum,
                CancellationToken token) {
              throw new AssertionError("No publication on reuse");
            }

            public StoredObject publishVerified(
                InputStream input,
                long expected,
                long maximum,
                CompletionVerification verify,
                CancellationToken token) {
              throw new AssertionError("No publication on reuse");
            }

            public Optional<StoredObject> verify(ObjectDigest digest, CancellationToken token) {
              return f.store.verify(digest, token);
            }

            public ObjectReadLease openRead(ObjectDigest digest, CancellationToken token) {
              var held = f.store.openRead(digest, token);
              opens.incrementAndGet();
              peak.accumulateAndGet(active.incrementAndGet(), Math::max);
              return new ObjectReadLease() {
                boolean closed;

                public StoredObject object() {
                  return held.object();
                }

                public InputStream stream() {
                  return held.stream();
                }

                public void close() {
                  if (!closed) {
                    closed = true;
                    active.decrementAndGet();
                    held.close();
                  }
                }
              };
            }

            public void close() {
              throw new AssertionError("Borrowed store");
            }
          };
      var index = new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID());
      var reusable = new SqliteBuildInventory(f.sql, observed, f.admissions, index, f.canonical);
      assertEquals(
          ready,
          reusable.prepare(
              f.facts.reference(),
              f.facts.locale(),
              f.facts.scopeRevision(),
              budgets(),
              EN_LIMITS,
              LIMITS,
              NONE));
      assertEquals(4, opens.get());
      assertEquals(4, peak.get());
      assertEquals(0, active.get());
      assertEquals(1, f.count("build_inventory_generations", "1=1"));
    }
  }

  @Test
  void closeFailureRetiresEveryReadyReuseLeaseAndPreservesOtherFailures() throws Exception {
    try (var f = new Fixture(false, false, false)) {
      f.prepare();
      var active = new java.util.concurrent.atomic.AtomicInteger();
      var custom =
          new SqliteBuildInventory(
              f.sql,
              closeFailingStore(f, active),
              f.admissions,
              new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID()),
              f.canonical);
      var failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  custom.prepare(
                      f.facts.reference(),
                      f.facts.locale(),
                      f.facts.scopeRevision(),
                      budgets(),
                      EN_LIMITS,
                      LIMITS,
                      NONE));
      assertEquals("lease close fault", failure.getMessage());
      assertEquals(3, failure.getSuppressed().length);
      assertEquals(0, active.get());
    }
  }

  @Test
  void parseFailureRemainsPrimaryWhileEveryLeaseCloseFailureIsSuppressed() throws Exception {
    try (var f = new Fixture(true, false, false)) {
      var active = new java.util.concurrent.atomic.AtomicInteger();
      var custom =
          new SqliteBuildInventory(
              f.sql,
              closeFailingStore(f, active),
              f.admissions,
              new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID()),
              f.canonical);
      var failure =
          assertThrows(
              IOException.class,
              () ->
                  custom.prepare(
                      f.facts.reference(),
                      f.facts.locale(),
                      f.facts.scopeRevision(),
                      budgets(),
                      EN_LIMITS,
                      LIMITS,
                      NONE));
      assertTrue(failure.getMessage().contains("inventory source"));
      assertEquals(4, failure.getSuppressed().length);
      assertEquals(0, active.get());
      assertEquals(0, f.count("build_inventory_generations", "1=1"));
    }
  }

  static ObjectStore closeFailingStore(
      Fixture f, java.util.concurrent.atomic.AtomicInteger active) {
    return new ObjectStore() {
      public StoredObject publish(
          InputStream i, ExpectedObjectChecksum e, long m, CancellationToken t) {
        throw new AssertionError();
      }

      public StoredObject publishVerified(
          InputStream i, long e, long m, CompletionVerification v, CancellationToken t) {
        throw new AssertionError();
      }

      public Optional<StoredObject> verify(ObjectDigest d, CancellationToken t) {
        return f.store.verify(d, t);
      }

      public ObjectReadLease openRead(ObjectDigest d, CancellationToken t) {
        var held = f.store.openRead(d, t);
        active.incrementAndGet();
        return new ObjectReadLease() {
          boolean closed;

          public StoredObject object() {
            return held.object();
          }

          public InputStream stream() {
            return held.stream();
          }

          public void close() {
            if (!closed) {
              closed = true;
              active.decrementAndGet();
              held.close();
              throw new IllegalStateException("lease close fault");
            }
          }
        };
      }

      public void close() {
        throw new AssertionError();
      }
    };
  }

  static byte[] root(String key, boolean malformed) {
    var b = ByteBuffer.allocate(20 + 2 * (12 + 4 + 16)).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {'T', 'S', 'F', 'M'}).putInt(20).putInt(1).putInt(2).putInt(0);
    for (int locale : List.of(2, 4))
      b.putInt(1).putInt(0x10000000).putInt(locale).putInt(7).put(HexFormat.of().parseHex(key));
    return malformed ? Arrays.copyOf(b.array(), b.capacity() - 1) : b.array();
  }

  static byte[] install(String key) throws Exception {
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.writeBytes("IN");
    o.writeByte(1);
    o.writeByte(16);
    o.writeShort(0);
    o.writeInt(1);
    o.writeBytes("file");
    o.writeByte(0);
    o.write(HexFormat.of().parseHex(key));
    o.writeInt(100);
    return b.toByteArray();
  }

  static byte[] download(String key, long bytes) throws Exception {
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.writeBytes("DL");
    o.writeByte(1);
    o.writeByte(16);
    o.writeByte(0);
    o.writeInt(1);
    o.writeShort(0);
    o.write(HexFormat.of().parseHex(key));
    u40(o, bytes);
    o.writeByte(0);
    return b.toByteArray();
  }

  static byte[] size(String key, long bytes) throws Exception {
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.writeBytes("DS");
    o.writeByte(1);
    o.writeByte(9);
    o.writeInt(1);
    o.writeShort(0);
    u40(o, bytes);
    o.write(HexFormat.of().parseHex(key.substring(0, 18)));
    o.writeInt((int) bytes);
    return b.toByteArray();
  }

  static byte[] en(String ck, String ek, long bytes, boolean missing, boolean collision)
      throws Exception {
    return en(ck, ek, bytes, missing, collision, 100);
  }

  static byte[] en(
      String ck, String ek, long bytes, boolean missing, boolean collision, long decodedBytes)
      throws Exception {
    var cp = new ByteArrayOutputStream();
    var co = new DataOutputStream(cp);
    co.writeByte(1);
    u40(co, decodedBytes);
    co.write(HexFormat.of().parseHex(missing ? "1".repeat(32) : ck));
    co.write(HexFormat.of().parseHex(ek));
    byte[] content = Arrays.copyOf(cp.toByteArray(), 1024);
    var eps = new TreeMap<String, Long>();
    eps.put(ek, bytes);
    if (collision)
      eps.put(
          ek.substring(0, 18) + (ek.endsWith("f".repeat(14)) ? "e" : "f").repeat(14), bytes + 1);
    var ep = new ByteArrayOutputStream();
    var eo = new DataOutputStream(ep);
    for (var entry : eps.entrySet()) {
      eo.write(HexFormat.of().parseHex(entry.getKey()));
      eo.writeInt(0);
      u40(eo, entry.getValue());
    }
    byte[] encoded = Arrays.copyOf(ep.toByteArray(), 1024);
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.writeBytes("EN");
    o.writeByte(1);
    o.writeByte(16);
    o.writeByte(16);
    o.writeShort(1);
    o.writeShort(1);
    o.writeInt(1);
    o.writeInt(1);
    o.writeByte(0);
    o.writeInt(2);
    o.writeByte('n');
    o.writeByte(0);
    o.write(content, 6, 16);
    o.write(MessageDigest.getInstance("MD5").digest(content));
    o.write(content);
    o.write(encoded, 0, 16);
    o.write(MessageDigest.getInstance("MD5").digest(encoded));
    o.write(encoded);
    o.writeByte('z');
    return b.toByteArray();
  }

  static void u40(DataOutputStream out, long n) throws IOException {
    for (int shift = 32; shift >= 0; shift -= 8) out.writeByte((int) (n >>> shift));
  }

  static byte[] blte(byte[] raw) throws Exception {
    var payload = new ByteArrayOutputStream();
    payload.write('N');
    payload.write(raw);
    var b = new ByteArrayOutputStream();
    var o = new DataOutputStream(b);
    o.writeBytes("BLTE");
    o.writeInt(36);
    o.writeByte(15);
    o.writeByte(0);
    o.writeShort(1);
    o.writeInt(payload.size());
    o.writeInt(raw.length);
    o.write(MessageDigest.getInstance("MD5").digest(payload.toByteArray()));
    o.write(payload.toByteArray());
    return b.toByteArray();
  }

  static byte[] utf(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static String md5(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
  }

  static String ekey(byte[] bytes) throws Exception {
    return md5(Arrays.copyOf(bytes, 36));
  }
}
