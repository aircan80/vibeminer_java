package io.vibeminer.infrastructure.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalContentMigrationTest {
  @TempDir Path directory;
  static final String C = "c".repeat(32), A = "a".repeat(32), B = "b".repeat(32);

  private Connection fixture(String... keys) throws Exception {
    return fixtureProduct("wow", keys);
  }

  private Connection fixtureProduct(String product, String... keys) throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var connection = catalog.connect();
    connection.setAutoCommit(false);
    int index = 0;
    for (var ekey : keys) {
      index++;
      var build =
          new BuildSnapshot(
              product,
              "Retail",
              "live",
              "us",
              "fixture",
              String.format("%032x", index),
              "d".repeat(32),
              Instant.EPOCH,
              true);
      catalog.ensureSnapshot(connection, build);
      try (var query = connection.prepareStatement("INSERT INTO catalog_objects VALUES(?,80)")) {
        query.setString(1, String.format("%064x", index));
        query.executeUpdate();
      }
      try (var query =
          connection.prepareStatement("INSERT INTO source_aliases VALUES(?,?,?,?,?,?)")) {
        query.setString(1, "wow.root.tsfm.v1v2");
        query.setString(2, C + ";" + ekey + ";64;80");
        query.setString(3, "blte-nz-ekey-chunks-full-ckey+tsfm-v1v2-structural-eof-r1");
        query.setString(4, String.format("%064x", index));
        query.setString(5, "https://us.cdn.blizzard.com/fixture/" + index);
        query.setString(6, Instant.EPOCH.toString());
        query.executeUpdate();
      }
      try (var query =
          connection.prepareStatement(
              "INSERT INTO build_object_memberships VALUES(?,?,?,?,?,?,?)")) {
        query.setString(1, build.snapshotId());
        query.setString(2, "ROOT_MANIFEST");
        query.setString(3, "wow.root.tsfm.v1v2");
        query.setString(4, C + ";" + ekey + ";64;80");
        query.setString(5, "blte-nz-ekey-chunks-full-ckey+tsfm-v1v2-structural-eof-r1");
        query.setString(6, "https://us.cdn.blizzard.com/fixture/" + index);
        query.setString(7, Instant.EPOCH.toString());
        query.executeUpdate();
      }
    }
    connection.commit();
    connection.setAutoCommit(false);
    return connection;
  }

  @Test
  void reviewedClassicBetaLegacyMetadataReopensInSharedWowNamespace() throws Exception {
    try (var connection = fixtureProduct("wow_classic_beta", A)) {
      CanonicalContentSchema.migrate(connection);
      assertEquals(0, count(connection, "canonical_legacy_blocks"));
      assertEquals(1, count(connection, "canonical_content_slots"));
      connection.commit();
    }
    try (var connection = new SqliteCatalog(directory.resolve("catalog.sqlite")).connect()) {
      try (var rows =
          connection
              .createStatement()
              .executeQuery(
                  "SELECT namespace,content_key,encoding_key FROM canonical_content_slots")) {
        assertTrue(rows.next());
        assertEquals("wow", rows.getString(1));
        assertEquals(C, rows.getString(2));
        assertEquals(A, rows.getString(3));
        assertFalse(rows.next());
      }
      assertEquals(1, count(connection, "build_object_memberships"));
    }
  }

  @Test
  void reviewedClassicInstallRoleKeepsExactLegacyVerifierChecks() throws Exception {
    try (var connection = fixtureProduct("wow_classic_beta", A)) {
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA defer_foreign_keys=ON");
        statement.execute(
            "UPDATE source_aliases SET"
                + " namespace='tact.install.v1',verifier_semantics='blte-nz-ekey-header-or-whole-chunk-md5-ckey-md5+in-v1-r1'");
        statement.execute(
            "UPDATE build_object_memberships SET"
                + " role='INSTALL_MANIFEST',namespace='tact.install.v1',verifier_semantics='blte-nz-ekey-header-or-whole-chunk-md5-ckey-md5+in-v1-r1'");
      }
      CanonicalContentSchema.migrate(connection);
      assertEquals(0, count(connection, "canonical_legacy_blocks"));
      assertEquals(1, count(connection, "canonical_content_slots"));
      connection.commit();
    }
    try (var connection = new SqliteCatalog(directory.resolve("catalog.sqlite")).connect()) {
      assertEquals(1, count(connection, "canonical_content_slots"));
      assertEquals(1, count(connection, "build_object_memberships"));
    }
  }

  @Test
  void reviewedClassicProductCannotAdmitUnreviewedVerifier() throws Exception {
    try (var connection = fixtureProduct("wow_classic_beta", A)) {
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA defer_foreign_keys=ON");
        statement.execute("UPDATE source_aliases SET verifier_semantics='unreviewed'");
        statement.execute("UPDATE build_object_memberships SET verifier_semantics='unreviewed'");
      }
      CanonicalContentSchema.migrate(connection);
      assertEquals(1, count(connection, "canonical_legacy_blocks"));
      assertEquals(0, count(connection, "canonical_content_slots"));
    }
  }

  @Test
  void unknownProductStillBlocksLegacyMetadata() throws Exception {
    try (var connection = fixtureProduct("wow_unreviewed", A)) {
      CanonicalContentSchema.migrate(connection);
      assertEquals(1, count(connection, "canonical_legacy_blocks"));
      assertEquals(0, count(connection, "canonical_content_slots"));
    }
  }

  @Test
  void legacyMetadataOccupiesHeaderWithoutEarningCurrentCapabilities() throws Exception {
    try (var connection = fixture(A)) {
      CanonicalContentSchema.migrate(connection);
      try (var rows =
          connection
              .createStatement()
              .executeQuery(
                  "SELECT encoding_key,status,representation FROM canonical_content_slots")) {
        assertTrue(rows.next());
        assertEquals(A, rows.getString(1));
        assertEquals("RETAINED", rows.getString(2));
        var proof =
            (io.vibeminer.domain.content.MetadataContentProof)
                CanonicalContentRecords.decode(rows.getBytes(3));
        assertTrue(proof.decodedAttestation().isEmpty());
        assertTrue(proof.structuralAttestation().isEmpty());
        assertFalse(rows.next());
      }
      CanonicalContentSchema.migrate(connection);
      assertEquals(1, count(connection, "canonical_content_slots"));
      assertEquals(1, count(connection, "build_object_memberships"));
    }
  }

  @Test
  void alternateLegacyEncodingsBecomeBlockingInventoryWithoutChoosingWinner() throws Exception {
    try (var connection = fixture(A, B)) {
      CanonicalContentSchema.migrate(connection);
      assertEquals(2, count(connection, "canonical_legacy_conflicts"));
      assertEquals(0, count(connection, "canonical_content_slots"));
      assertEquals(2, count(connection, "build_object_memberships"));
      assertEquals(2, count(connection, "source_aliases"));
    }
  }

  @Test
  void unknownVerifierBlocksAcquisitionWithoutInventingAContentClaim() throws Exception {
    try (var connection = fixture(A)) {
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA defer_foreign_keys=ON");
        statement.execute("UPDATE source_aliases SET verifier_semantics='unreviewed'");
        statement.execute("UPDATE build_object_memberships SET verifier_semantics='unreviewed'");
      }
      CanonicalContentSchema.migrate(connection);
      assertEquals(1, count(connection, "canonical_legacy_blocks"));
      assertEquals(0, count(connection, "canonical_content_slots"));
      assertEquals(
          io.vibeminer.application.content.ContentClaimException.Code.LEGACY_CONFLICT,
          assertThrows(
                  io.vibeminer.application.content.ContentClaimException.class,
                  () ->
                      CanonicalContentGuards.check(
                          connection,
                          new io.vibeminer.domain.selectedfile.ContentSlotKey("wow", C)))
              .code());
      assertEquals(1, count(connection, "build_object_memberships"));
    }
  }

  @Test
  void conflictingLegacyInventoryBlocksBothFacadesAndPreservesAllReferences() throws Exception {
    try (var connection = fixture(A, B)) {
      CanonicalContentSchema.migrate(connection);
      connection.commit();
    }
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store =
        new io.vibeminer.infrastructure.storage.FileObjectStore(directory.resolve("store"))) {
      var repository = new SqliteCanonicalContentRepository(catalog, store);
      var slot = new io.vibeminer.domain.selectedfile.ContentSlotKey("wow", C);
      assertEquals(
          io.vibeminer.application.content.ContentClaimException.Code.LEGACY_CONFLICT,
          assertThrows(
                  io.vibeminer.application.content.ContentClaimException.class,
                  () -> repository.lookup(slot, CancellationToken.none()))
              .code());
      var selected =
          new io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog(catalog, store);
      assertEquals(
          io.vibeminer.application.selectedfile.SelectedFileException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  io.vibeminer.application.selectedfile.SelectedFileException.class,
                  () -> selected.reserve(slot, A, 80, CancellationToken.none()))
              .code());
    }
  }

  @Test
  void openingActualVersionFivePreservesMembershipAndCompatibilityPinRows() throws Exception {
    String snapshot;
    try (var connection = fixture(A)) {
      try (var rows =
          connection
              .createStatement()
              .executeQuery("SELECT snapshot_id FROM build_object_memberships")) {
        snapshot = rows.getString(1);
      }
      try (var query =
          connection.prepareStatement(
              "INSERT INTO retention_scopes VALUES(?,?, 'LEGACY_RETAINED',1,?)")) {
        query.setString(1, "f".repeat(64));
        query.setString(2, snapshot);
        query.setString(3, Instant.EPOCH.toString());
        query.executeUpdate();
      }
      try (var query =
          connection.prepareStatement(
              "INSERT INTO retention_scope_requirements SELECT"
                  + " ?,role,namespace,source_key,verifier_semantics,80,NULL FROM"
                  + " build_object_memberships")) {
        query.setString(1, "f".repeat(64));
        query.executeUpdate();
      }
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA user_version=5");
      }
      connection.commit();
    }
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var connection = catalog.connect()) {
      assertEquals(1, count(connection, "canonical_content_slots"));
      assertEquals(1, count(connection, "build_object_memberships"));
      assertEquals(1, count(connection, "source_aliases"));
      try (var rows =
          connection
              .createStatement()
              .executeQuery("SELECT snapshot_id,kind,pinned,created_at FROM retention_scopes")) {
        assertEquals(snapshot, rows.getString(1));
        assertEquals("LEGACY_RETAINED", rows.getString(2));
        assertEquals(1, rows.getInt(3));
        assertEquals(Instant.EPOCH.toString(), rows.getString(4));
      }
      try (var rows =
          connection
              .createStatement()
              .executeQuery("SELECT logical_decoded_bytes FROM retention_scope_requirements")) {
        assertNull(rows.getObject(1));
      }
    }
  }

  @Test
  void corruptLegacyFactsRollBackEntireVersionFiveUpgrade() throws Exception {
    try (var connection = fixture(A)) {
      try (var statement = connection.createStatement()) {
        statement.execute("UPDATE catalog_objects SET length=81");
        statement.execute("DROP TABLE canonical_legacy_conflicts");
        statement.execute("DROP TABLE canonical_legacy_blocks");
        statement.execute("PRAGMA user_version=5");
      }
      connection.commit();
    }
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    assertEquals(
        io.vibeminer.application.catalog.CatalogException.Code.CORRUPT_METADATA,
        assertThrows(io.vibeminer.application.catalog.CatalogException.class, catalog::connect)
            .code());
    try (var connection =
        java.sql.DriverManager.getConnection(
            "jdbc:sqlite:" + directory.resolve("catalog.sqlite"))) {
      try (var rows = connection.createStatement().executeQuery("PRAGMA user_version")) {
        assertEquals(5, rows.getInt(1));
      }
      assertEquals(1, count(connection, "build_object_memberships"));
      try (var rows =
          connection
              .createStatement()
              .executeQuery(
                  "SELECT count(*) FROM sqlite_master WHERE name='canonical_legacy_conflicts'")) {
        assertEquals(0, rows.getInt(1));
      }
    }
  }

  static int count(Connection connection, String table) throws SQLException {
    try (var rows = connection.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
      return rows.getInt(1);
    }
  }
}
