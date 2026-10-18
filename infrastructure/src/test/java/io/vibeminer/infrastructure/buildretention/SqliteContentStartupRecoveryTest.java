package io.vibeminer.infrastructure.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.buildretention.BuildRetention.Target;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.content.*;
import io.vibeminer.infrastructure.encoding.EncodingLookupSchema;
import io.vibeminer.infrastructure.inventory.InventorySchema;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SqliteContentStartupRecoveryTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  SqliteCatalog sql;
  byte[] proof;
  StoredObject object;

  @BeforeEach
  void seed() throws Exception {
    sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      byte[] bytes = {1, 2, 3, 4};
      object =
          store.publishVerified(
              new ByteArrayInputStream(bytes), bytes.length, bytes.length, t -> {}, NONE);
      var alias =
          new VerifiedSourceAlias(
              new ResolvedSourceIdentity(
                  "tact.install.v1",
                  "a".repeat(32) + ";" + "b".repeat(32) + ";4;4",
                  "authored-fixture-v1"),
              object,
              new SourceVerificationEvidence(
                  "authored-fixture-v1",
                  "https://fixture.invalid/authored",
                  Instant.parse("2026-10-18T12:00:00Z")));
      proof =
          CanonicalContentRecords.encode(
              new MetadataContentProof(
                  alias,
                  "b".repeat(32),
                  "a".repeat(32),
                  OptionalLong.of(4),
                  Optional.of(
                      new DecodedContentAttestation("a".repeat(32), 4, "authored-fixture-v1")),
                  Optional.of(
                      new MetadataStructureAttestation(
                          MetadataStructureAttestation.Kind.INSTALL, "authored-fixture-v1"))));
    }
    try (var c = sql.connect()) {
      EncodingLookupSchema.migrate(c);
      BuildRetentionSchema.install(c);
      InventorySchema.migrate(c);
      try (var q =
          c.prepareStatement(
              "INSERT INTO"
                  + " canonical_content_slots(namespace,content_key,encoding_key,encoded_bytes,token,status,owner_session,representation,worker_nonce)"
                  + " VALUES('wow',?,?,4,?,?,?,?,?)")) {
        for (int n = 0; n < 2; n++) {
          q.setString(1, n == 0 ? "a".repeat(32) : "c".repeat(32));
          q.setString(2, "b".repeat(32));
          q.setString(3, UUID.randomUUID().toString());
          q.setString(4, n == 0 ? "RETAINED" : "PUBLISHING");
          q.setString(5, UUID.randomUUID().toString());
          q.setBytes(6, proof);
          q.setString(7, UUID.randomUUID().toString());
          q.executeUpdate();
        }
      }
      try (var s = c.createStatement()) {
        s.execute(
            "INSERT INTO"
                + " en_lookup_artifacts(artifact,generation,owner,status,source_proof,content_count,encoding_count,candidate_count)"
                + " VALUES('ready','r','old','READY',X'0102',0,0,0),('partial','s','old','STAGING',X'03',NULL,NULL,NULL)");
        s.execute(
            "INSERT INTO en_lookup_content VALUES('partial','s','" + "f".repeat(32) + "',1,1)");
        s.execute(
            "INSERT INTO en_lookup_candidates VALUES('partial','s','"
                + "f".repeat(32)
                + "',0,'"
                + "e".repeat(32)
                + "')");
        s.execute(
            "INSERT INTO"
                + " build_inventory_generations(id,snapshot_id,admission_id,en_artifact,en_generation,scope,status,row_count,derived_bytes,batches,member_count)"
                + " VALUES('ready','snapshot','admission','ready','r','all','READY',0,0,0,0),('partial','snapshot','admission','ready','r','all','STAGING',0,0,0,NULL)");
        s.execute("INSERT INTO build_inventory_sources VALUES('partial','ROOT',X'04',0,NULL)");
        s.execute(
            "INSERT INTO"
                + " build_inventory_references(generation,role,ordinal,block_ordinal,key_ref,raw_fact)"
                + " VALUES('partial','ROOT',0,0,'"
                + "f".repeat(32)
                + "','authored')");
      }
      intent(c, "current", "DOWNLOAD_ALL", 1);
      intent(c, "paused", "ON_DEMAND", 2);
      intent(c, "obsolete", "DOWNLOAD_ALL", 2);
      intent(c, "exhausted", "DOWNLOAD_ALL", 1);
      job(c, "current", "RUNNING", 1, 1);
      job(c, "paused", "RUNNING", 1, 1);
      job(c, "paused", "QUEUED", 0, 0);
      job(c, "paused", "SUCCEEDED", 1, 0);
    }
  }

  static void intent(Connection c, String id, String mode, long generation) throws Exception {
    try (var q =
        c.prepareStatement(
            "INSERT INTO build_retention_intents"
                + " VALUES(?,?,?,?,?,1,'all','eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',?,?,?,10,0,100,10000,10000,10000,3,11,12,13)")) {
      q.setString(1, target(id).identity());
      q.setString(2, target(id).groupId());
      q.setString(3, "b".repeat(64));
      q.setString(4, "c".repeat(64));
      q.setString(5, "d".repeat(64));
      q.setString(6, mode);
      q.setLong(7, generation);
      q.setString(8, "2026-10-18T12:00:00Z");
      q.executeUpdate();
    }
  }

  static void job(Connection c, String target, String state, int attempts, long lease)
      throws Exception {
    try (var q =
        c.prepareStatement(
            "INSERT INTO"
                + " build_retention_jobs(target_id,member_id,ordinal,content_key,encoding_key,encoded_bytes,cost_wire,cost_additional,cost_requests,membership_commitment,state,attempts,worker_nonce,owner_epoch,lease_generation,fulfillment_proof)"
                + " VALUES(?,?,?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',4,4,4,1,'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',?,?,?,?,?,?)")) {
      q.setString(1, target(target).identity());
      q.setString(2, digest(state));
      q.setInt(3, state.equals("RUNNING") ? 0 : state.equals("QUEUED") ? 1 : 2);
      q.setString(4, state);
      q.setInt(5, attempts);
      q.setString(6, state.equals("RUNNING") ? UUID.randomUUID().toString() : null);
      q.setString(7, state.equals("RUNNING") ? UUID.randomUUID().toString() : null);
      if (state.equals("RUNNING")) q.setLong(8, lease);
      else q.setNull(8, Types.INTEGER);
      q.setString(9, state.equals("SUCCEEDED") ? digest("original-fulfillment") : null);
      q.executeUpdate();
    }
  }

  @Test
  void realReopenRecoversAllFamiliesOnceWithoutChangingPublishedEvidence() throws Exception {
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteContentStartupRecovery.recoverAtStartup(sql, store.contentSession());
      store.contentSession().beforeProducerOperation();
      try (var c = sql.connect();
          var q = c.createStatement()) {
        try (var r =
            q.executeQuery(
                "SELECT status,owner_session,worker_nonce,representation FROM"
                    + " canonical_content_slots ORDER BY content_key")) {
          assertTrue(r.next());
          assertEquals("RETAINED", r.getString(1));
          assertEquals(store.contentSession().epoch().toString(), r.getString(2));
          assertNull(r.getString(3));
          assertArrayEquals(proof, r.getBytes(4));
          assertTrue(r.next());
          assertEquals("RECOVERY", r.getString(1));
          assertArrayEquals(proof, r.getBytes(4));
          assertNull(r.getString(3));
        }
        assertEquals(1, count(c, "en_lookup_artifacts"));
        try (var r =
            q.executeQuery(
                "SELECT generation,source_proof FROM en_lookup_artifacts WHERE artifact='ready'")) {
          r.next();
          assertEquals("r", r.getString(1));
          assertArrayEquals(new byte[] {1, 2}, r.getBytes(2));
        }
        try (var r = q.executeQuery("SELECT id,status FROM build_inventory_generations")) {
          r.next();
          assertEquals("ready", r.getString(1));
          assertEquals("READY", r.getString(2));
        }

        assertEquals(0, count(c, "en_lookup_candidates"));
        assertEquals(1, count(c, "build_inventory_generations"));
        assertEquals(0, count(c, "build_inventory_sources"));
        assertJob(c, "current", "QUEUED", "INTERRUPTED", 1);
        assertJob(c, "paused", "FAILED", "CANCELLED", 1);
        try (var r =
            q.executeQuery(
                "SELECT mode,generation,reserved_wire,reserved_additional,reserved_requests FROM"
                    + " build_retention_intents WHERE target_id='"
                    + target("paused").identity()
                    + "'")) {
          r.next();
          assertEquals("ON_DEMAND", r.getString(1));
          assertEquals(2, r.getLong(2));
          assertEquals(11, r.getLong(3));
          assertEquals(12, r.getLong(4));
          assertEquals(13, r.getLong(5));
        }
        try (var r =
            q.executeQuery(
                "SELECT state,fulfillment_proof FROM build_retention_jobs WHERE target_id='"
                    + target("paused").identity()
                    + "' AND member_id='"
                    + digest("SUCCEEDED")
                    + "'")) {
          r.next();
          assertEquals("SUCCEEDED", r.getString(1));
          assertEquals(digest("original-fulfillment"), r.getString(2));
        }
        try (var lease = store.openRead(object.digest(), NONE)) {
          assertArrayEquals(new byte[] {1, 2, 3, 4}, lease.stream().readAllBytes());
        }
      }
      BuildRetentionInventory inventory =
          (target, token) ->
              new BuildRetentionInventory.Opened() {
                public Seal seal() {
                  return new Seal(target, 10, "authored-unit-state-v1");
                }

                public List<Member> members(long first, int max, CancellationToken token) {
                  throw new AssertionError("No transfer enumeration for paused mode");
                }

                public void close() {}
              };
      var jobs = new SqliteBuildRetentionRepository(sql, store, store.contentSession(), inventory);
      assertEquals(
          BuildRetentionException.Code.NOT_READY,
          assertThrows(
                  BuildRetentionException.class, () -> jobs.claimNext(target("paused"), 2, NONE))
              .code());
      assertThrows(
          IllegalStateException.class,
          () -> SqliteContentStartupRecovery.recoverAtStartup(sql, store.contentSession()));
    }
  }

  @Test
  void reauthorizedOnModeCanRequeueOldLeaseButExhaustedAttemptCannot() throws Exception {
    try (var c = sql.connect();
        var q = c.createStatement()) {
      q.execute("DELETE FROM build_retention_jobs WHERE state='RUNNING'");
      job(c, "obsolete", "RUNNING", 1, 1);
      job(c, "exhausted", "RUNNING", 3, 1);
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteContentStartupRecovery.recoverAtStartup(sql, store.contentSession());
      try (var c = sql.connect()) {
        assertJob(c, "obsolete", "QUEUED", "INTERRUPTED", 1);
        assertJob(c, "exhausted", "FAILED", "INTERRUPTED", 3);
      }
    }
  }

  @Test
  void lateSqlFailureRollsBackEveryFamilyAndPermanentlyFailsThatCapability() throws Exception {
    try (var c = sql.connect();
        var q = c.createStatement()) {
      q.execute(
          "CREATE TRIGGER fail_restart BEFORE UPDATE ON build_retention_jobs BEGIN SELECT"
              + " RAISE(ABORT,'authored late recovery failure'); END");
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      assertThrows(
          RuntimeException.class,
          () -> SqliteContentStartupRecovery.recoverAtStartup(sql, store.contentSession()));
      assertThrows(
          IllegalStateException.class, () -> store.contentSession().beforeProducerOperation());
      assertThrows(
          IllegalStateException.class,
          () -> SqliteContentStartupRecovery.recoverAtStartup(sql, store.contentSession()));
      try (var c = sql.connect();
          var q = c.createStatement()) {
        assertEquals(2, count(c, "en_lookup_artifacts"));
        assertEquals(1, count(c, "en_lookup_candidates"));
        assertEquals(2, count(c, "build_inventory_generations"));
        assertEquals(1, count(c, "build_inventory_sources"));
        try (var r =
            q.executeQuery(
                "SELECT status,worker_nonce FROM canonical_content_slots WHERE content_key='"
                    + "c".repeat(32)
                    + "'")) {
          r.next();
          assertEquals("PUBLISHING", r.getString(1));
          assertNotNull(r.getString(2));
        }
        assertJob(c, "current", "RUNNING", null, 1);
        q.execute("DROP TRIGGER fail_restart");
      }
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteContentStartupRecovery.recoverAtStartup(sql, store.contentSession());
    }
  }

  @Test
  void typedCorruptInterruptedJobRollsBackEarlierCanonicalAndStagingChanges() throws Exception {
    try (var c = sql.connect();
        var q = c.createStatement()) {
      // Authored on-disk corruption bypasses current DDL to exercise the strict recovery reader.
      q.execute("PRAGMA ignore_check_constraints=ON");
      q.execute("UPDATE build_retention_jobs SET attempts=1.5 WHERE state='RUNNING'");
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      assertEquals(
          BuildRetentionException.Code.CORRUPT_STATE,
          assertThrows(
                  BuildRetentionException.class,
                  () -> SqliteContentStartupRecovery.recoverAtStartup(sql, store.contentSession()))
              .code());
      assertThrows(
          IllegalStateException.class, () -> store.contentSession().beforeProducerOperation());
      try (var c = sql.connect();
          var q = c.createStatement()) {
        assertEquals(2, count(c, "en_lookup_artifacts"));
        assertEquals(2, count(c, "build_inventory_generations"));
        try (var r =
            q.executeQuery(
                "SELECT status,worker_nonce FROM canonical_content_slots WHERE content_key='"
                    + "c".repeat(32)
                    + "'")) {
          r.next();
          assertEquals("PUBLISHING", r.getString(1));
          assertNotNull(r.getString(2));
        }
      }
    }
  }

  @Test
  void alreadyUsedStoreAndSecondLiveOwnerCannotAcquireRecoveryAuthority() throws Exception {
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      try (var lease = store.openRead(object.digest(), NONE)) {
        assertEquals(object, lease.object());
      }
      assertThrows(
          IllegalStateException.class,
          () -> SqliteContentStartupRecovery.recoverAtStartup(sql, store.contentSession()));
      assertThrows(
          ObjectStoreException.class, () -> new FileObjectStore(directory.resolve("store")));
      try (var c = sql.connect()) {
        assertEquals(2, count(c, "en_lookup_artifacts"));
      }
    }
  }

  static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static Target target(String name) {
    return new Target(
        digest(name), "b".repeat(64), "c".repeat(64), "d".repeat(64), 1, "all", "e".repeat(64));
  }

  static long count(Connection c, String table) throws Exception {
    try (var q = c.createStatement();
        var r = q.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  static void assertJob(Connection c, String id, String state, String failure, int attempts)
      throws Exception {
    try (var q =
        c.prepareStatement(
            "SELECT state,failure_code,attempts,worker_nonce,owner_epoch,lease_generation FROM"
                + " build_retention_jobs WHERE target_id=? AND member_id='"
                + digest("RUNNING")
                + "'")) {
      q.setString(1, target(id).identity());
      try (var r = q.executeQuery()) {
        assertTrue(r.next());
        assertEquals(state, r.getString(1));
        assertEquals(failure, r.getString(2));
        assertEquals(attempts, r.getInt(3));
        if (!state.equals("RUNNING")) {
          assertNull(r.getString(4));
          assertNull(r.getString(5));
          assertNull(r.getString(6));
        }
      }
    }
  }
}
