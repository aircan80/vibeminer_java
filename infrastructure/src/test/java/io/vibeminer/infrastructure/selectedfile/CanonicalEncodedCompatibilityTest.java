package io.vibeminer.infrastructure.selectedfile;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.content.EncodedContentProof;
import io.vibeminer.infrastructure.content.CanonicalContentRecords;
import org.junit.jupiter.api.Test;

class CanonicalEncodedCompatibilityTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

  @Test
  void neutralCodecPreservesExistingEncodedRecordBytesAndProof() throws Exception {
    var receipt =
        SqliteSelectedFileCatalogTest.prospective(
            SqliteSelectedFileCatalogTest.slot(),
            SqliteSelectedFileCatalogTest.A,
            "fixture".getBytes(),
            "us.cdn.blizzard.com");
    byte[] existing = SelectedFileRecords.representation(receipt);
    var read = CanonicalContentRecords.decode(existing);
    assertEquals(new EncodedContentProof(receipt), read);
    assertArrayEquals(existing, CanonicalContentRecords.encode(read));
  }

  @Test
  void versionFiveUpgradePreservesEncodedHeaderTokenOwnerAndRawBlob() throws Exception {
    var path = directory.resolve("catalog.sqlite");
    var catalog = new io.vibeminer.infrastructure.persistence.SqliteCatalog(path);
    var session = java.util.UUID.randomUUID();
    byte[] payload = "fixture".getBytes();
    try (var store =
        new io.vibeminer.infrastructure.storage.FileObjectStore(directory.resolve("store"))) {
      var adapter = new SqliteSelectedFileCatalog(catalog, store, session);
      var claim =
          adapter.reserve(
              SqliteSelectedFileCatalogTest.slot(),
              SqliteSelectedFileCatalogTest.A,
              payload.length,
              io.vibeminer.application.CancellationToken.none());
      adapter.markPublishing(claim.token(), io.vibeminer.application.CancellationToken.none());
      var proof =
          SqliteSelectedFileCatalogTest.prospective(
              claim.slot(), claim.actualEncodingKey(), payload, "us.cdn.blizzard.com");
      adapter.recordVerifiedCompletion(
          claim.token(), proof, io.vibeminer.application.CancellationToken.none());
      SqliteSelectedFileCatalogTest.publish(store, payload);
      adapter.completePublication(
          claim.token(), proof, io.vibeminer.application.CancellationToken.none());
      byte[] encoded = SelectedFileRecords.representation(proof);
      try (var connection = catalog.connect();
          var statement = connection.createStatement()) {
        statement.execute("PRAGMA user_version=5");
      }
      var upgraded = new io.vibeminer.infrastructure.persistence.SqliteCatalog(path);
      try (var connection = upgraded.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery(
                      "SELECT token,status,owner_session,representation FROM"
                          + " canonical_content_slots")) {
        assertEquals(claim.token().toString(), rows.getString(1));
        assertEquals("RETAINED", rows.getString(2));
        assertEquals(session.toString(), rows.getString(3));
        assertArrayEquals(encoded, rows.getBytes(4));
      }
      assertEquals(
          proof,
          new SqliteSelectedFileCatalog(upgraded, store)
              .lookup(claim.slot(), io.vibeminer.application.CancellationToken.none())
              .orElseThrow()
              .representation()
              .orElseThrow());
    }
  }
}
