package io.vibeminer.infrastructure.selectedfile;

import static io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalogTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.selectedfile.SelectedFileException;
import io.vibeminer.domain.selectedfile.SelectedFileAssociation;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginalSelectedAssociationTest {
  @TempDir Path directory;

  @Test
  void originalAssociationRemainsReadableUnderConflictWithoutSelectingWinnerOrChangingPins()
      throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    SelectedFileAssociation saved;
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var objects = new SqliteBuildObjectCatalog(factory, store);
      var build = build("one");
      objects.registerSnapshot(build);
      var config = dependency(objects, store, build, "BUILD_CONFIG", "cfg");
      var root =
          metadataDependency(
              objects, store, build, "ROOT_MANIFEST", "d".repeat(32), "e".repeat(32));
      var files = new SqliteSelectedFileCatalog(factory, store);
      byte[] bytes = "fixture".getBytes();
      var claim = files.reserve(slot(), A, bytes.length, NONE);
      files.markPublishing(claim.token(), NONE);
      var proof = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      files.recordVerifiedCompletion(claim.token(), proof, NONE);
      publish(store, bytes);
      files.completePublication(claim.token(), proof, NONE);
      saved =
          files.attachAssociation(
              association(build, config, root, proof),
              scope(association(build, config, root, proof)),
              NONE);
      var other = build("legacy-install");
      objects.registerSnapshot(other);
      metadataDependency(objects, store, other, "INSTALL_MANIFEST", C, B);
      try (var connection = factory.connect()) {
        connection.setAutoCommit(false);
        io.vibeminer.infrastructure.content.CanonicalContentSchema.migrate(connection);
        connection.commit();
        try (var rows =
            connection
                .createStatement()
                .executeQuery("SELECT count(*) FROM canonical_legacy_conflicts")) {
          assertEquals(2, rows.getInt(1));
        }
      }
      assertThrows(SelectedFileException.class, () -> files.lookup(slot(), NONE));
      assertThrows(SelectedFileException.class, () -> files.reserve(slot(), B, bytes.length, NONE));
      assertThrows(
          SelectedFileException.class,
          () -> files.findAssociation(saved.snapshotId(), saved.role(), NONE));
      assertEquals(saved, files.findOriginalAssociation(saved.descriptor(), NONE).orElseThrow());
      assertTrue(
          files
              .findOriginalAssociation(
                  new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
                      saved.descriptor().association(),
                      B,
                      saved.descriptor().encodedVerifierSemantics()),
                  NONE)
              .isEmpty());
      assertThrows(
          SelectedFileException.class, () -> files.attachAssociation(saved, scope(saved), NONE));
      try (var connection = factory.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery("SELECT count(*) FROM retention_scopes WHERE pinned=1")) {
        assertEquals(1, rows.getInt(1));
      }
      try (var connection = factory.connect();
          var rows =
              connection.createStatement().executeQuery("SELECT count(*) FROM source_aliases")) {
        assertEquals(4, rows.getInt(1));
      }
      try (var connection = factory.connect();
          var query =
              connection.prepareStatement(
                  "SELECT representation FROM canonical_content_slots WHERE namespace=? AND"
                      + " content_key=?")) {
        query.setString(1, slot().namespace());
        query.setString(2, slot().contentKey());
        try (var rows = query.executeQuery()) {
          assertArrayEquals(SelectedFileRecords.representation(proof), rows.getBytes(1));
        }
      }
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var files = new SqliteSelectedFileCatalog(factory, store);
      assertEquals(saved, files.findOriginalAssociation(saved.descriptor(), NONE).orElseThrow());
      try (var connection = factory.connect();
          var query =
              connection.prepareStatement(
                  "DELETE FROM build_object_memberships WHERE snapshot_id=? AND role=?")) {
        query.setString(1, saved.snapshotId());
        query.setString(2, "BUILD_CONFIG");
        query.executeUpdate();
      }
      assertThrows(
          SelectedFileException.class,
          () -> files.findOriginalAssociation(saved.descriptor(), NONE));
    }
  }

  private static io.vibeminer.domain.VerifiedSourceAlias metadataDependency(
      SqliteBuildObjectCatalog objects,
      io.vibeminer.application.ObjectStore store,
      io.vibeminer.domain.BuildSnapshot build,
      String role,
      String ckey,
      String ekey)
      throws Exception {
    byte[] bytes = ("legacy-" + role).getBytes();
    publish(store, bytes);
    boolean root = role.equals("ROOT_MANIFEST");
    var source =
        new io.vibeminer.domain.ResolvedSourceIdentity(
            root ? "wow.root.tsfm.v1v2" : "tact.install.v1",
            ckey + ";" + ekey + ";64;" + bytes.length,
            root
                ? "blte-nz-ekey-chunks-full-ckey+tsfm-v1v2-structural-eof-r1"
                : "blte-nz-ekey-header-or-whole-chunk-md5-ckey-md5+in-v1-r1");
    var alias =
        new io.vibeminer.domain.VerifiedSourceAlias(
            source,
            new io.vibeminer.domain.StoredObject(
                new io.vibeminer.domain.ObjectDigest(
                    java.util.HexFormat.of()
                        .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(bytes))),
                bytes.length),
            new io.vibeminer.domain.SourceVerificationEvidence(
                source.verificationSemantics(),
                "https://us.cdn.blizzard.com/fixture/" + ekey,
                java.time.Instant.EPOCH));
    objects.attachVerifiedObject(
        build.snapshotId(), role, source, alias.object(), alias.evidence(), NONE);
    return alias;
  }
}
