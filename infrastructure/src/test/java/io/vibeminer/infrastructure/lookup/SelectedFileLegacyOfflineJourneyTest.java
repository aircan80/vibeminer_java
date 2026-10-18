package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.CanonicalContentSchema;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectedFileLegacyOfflineJourneyTest {
  @TempDir Path directory;

  @Test
  void migratedConflictPreservesExactOriginalOfflineEvidenceWithoutSelectingOrMutating()
      throws Exception {
    var data = directory.resolve("objects");
    var sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    SelectedFileAssociation original;
    SelectedFileHttpFixture fixture;
    try (var setup = new OfficialSelectedFileGatewayTest.Setup(data, false)) {
      fixture = setup.fixture;
      var objects = new SqliteBuildObjectCatalog(sql, setup.store);
      objects.registerSnapshot(setup.build);
      attach(objects, setup.build, "BUILD_CONFIG", setup.config);
      attach(objects, setup.build, "ROOT_MANIFEST", setup.root);
      try (var service = service(setup.gateway, sql, setup.store)) {
        var candidates =
            service
                .planCandidates(setup.build, setup.variant, SelectedFileBudgets.defaults())
                .toCompletableFuture()
                .join();
        var choice = OfficialSelectedFileGatewayTest.choice(candidates, fixture.selectedEKey);
        original =
            service
                .retain(
                    service.probeLoose(choice).toCompletableFuture().join().plan().orElseThrow())
                .toCompletableFuture()
                .join();
      }
      // Historical inventory is deliberately a declaration, not a current IN attestation.
      byte[] legacy =
          "synthetic historical install declaration"
              .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(legacy));
      var object =
          setup.store.publish(
              new java.io.ByteArrayInputStream(legacy),
              new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, sha, legacy.length),
              legacy.length,
              CancellationToken.none());
      var other = fixture.build("legacy-conflict");
      objects.registerSnapshot(other);
      String alternate = "f".repeat(32);
      assertNotEquals(original.descriptor().actualEncodingKey(), alternate);
      String semantics = "blte-nz-ekey-header-or-whole-chunk-md5-ckey-md5+in-v1-r1";
      var source =
          new ResolvedSourceIdentity(
              "tact.install.v1",
              original.descriptor().association().contentKey()
                  + ";"
                  + alternate
                  + ";64;"
                  + legacy.length,
              semantics);
      attach(
          objects,
          other,
          "INSTALL_MANIFEST",
          new VerifiedSourceAlias(
              source,
              object,
              new SourceVerificationEvidence(
                  semantics, "https://us.cdn.blizzard.com/fixture/" + alternate, Instant.EPOCH)));
      try (var connection = sql.connect()) {
        connection.setAutoCommit(false);
        CanonicalContentSchema.migrate(connection);
        connection.commit();
        try (var rows =
            connection
                .createStatement()
                .executeQuery("SELECT count(*) FROM canonical_legacy_conflicts")) {
          assertEquals(2, rows.getInt(1));
        }
      }
    }
    fixture.rejectNetwork = true;
    int requests = fixture.requests.size();
    try (var store = new FileObjectStore(data)) {
      var clock = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
      var client = fixture.client();
      try (var gateway =
              new OfficialSelectedFileGateway(
                  store,
                  new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
                  new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1)),
                  new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
                  clock);
          var service = service(gateway, sql, store)) {
        var before = state(sql);
        assertEquals(
            original,
            service
                .inspectOffline(original.descriptor(), SelectedFileBudgets.defaults())
                .toCompletableFuture()
                .join());
        assertFailure(
            service.inspectOffline(
                original.descriptor().association(), SelectedFileBudgets.defaults()),
            SelectedFileException.Code.INTEGRITY_FAILURE);
        var files = new SqliteSelectedFileCatalog(sql, store);
        assertThrows(
            SelectedFileException.class,
            () ->
                files.reserve(
                    original.representation().slot(),
                    original.descriptor().actualEncodingKey(),
                    original.representation().encoded().object().length(),
                    CancellationToken.none()));
        assertEquals(before, state(sql));
        assertEquals(requests, fixture.requests.size());

        String digest = original.representation().encoded().object().digest().value();
        var path =
            data.resolve("objects").resolve(digest.substring(0, 2)).resolve(digest.substring(2));
        byte[] bytes = Files.readAllBytes(path), corrupt = bytes.clone();
        corrupt[0] ^= 1;
        Files.write(path, corrupt);
        assertFailure(
            service.inspectOffline(original.descriptor(), SelectedFileBudgets.defaults()),
            SelectedFileException.Code.INTEGRITY_FAILURE);
        Files.delete(path);
        assertFailure(
            service.inspectOffline(original.descriptor(), SelectedFileBudgets.defaults()),
            SelectedFileException.Code.MISSING_LOCAL);
        Files.write(path, bytes);
        assertEquals(before, state(sql));
        assertEquals(requests, fixture.requests.size());

        byte[] record;
        try (var connection = sql.connect();
            var query =
                connection.prepareStatement(
                    "SELECT record FROM selected_file_associations WHERE snapshot_id=? AND"
                        + " role=?")) {
          query.setString(1, original.snapshotId());
          query.setString(2, original.role());
          try (var rows = query.executeQuery()) {
            assertTrue(rows.next());
            record = rows.getBytes(1);
          }
        }
        byte[] altered = record.clone();
        byte[] proofDigest =
            original.proof().proofDigest().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int position = -1;
        for (int offset = 0; offset <= altered.length - proofDigest.length; offset++) {
          if (Arrays.equals(
              altered, offset, offset + proofDigest.length, proofDigest, 0, proofDigest.length)) {
            position = offset;
            break;
          }
        }
        assertTrue(position >= 0, "Persisted original proof digest must be present");
        altered[position] = (byte) (altered[position] == '0' ? '1' : '0');
        replaceRecord(sql, original, altered);
        var changedProof = state(sql);
        assertFailure(
            service.inspectOffline(original.descriptor(), SelectedFileBudgets.defaults()),
            SelectedFileException.Code.INTEGRITY_FAILURE);
        assertEquals(changedProof, state(sql));
        assertEquals(requests, fixture.requests.size());
        replaceRecord(sql, original, record);
        assertEquals(before, state(sql));

        try (var connection = sql.connect();
            var delete =
                connection.prepareStatement(
                    "DELETE FROM build_object_memberships WHERE snapshot_id=? AND"
                        + " role='ROOT_MANIFEST'")) {
          delete.setString(1, original.snapshotId());
          delete.executeUpdate();
        }
        var tampered = state(sql);
        assertFailure(
            service.inspectOffline(original.descriptor(), SelectedFileBudgets.defaults()),
            SelectedFileException.Code.INTEGRITY_FAILURE);
        assertEquals(tampered, state(sql));
        assertEquals(requests, fixture.requests.size());
      }
    }
  }

  private static DefaultSelectedFileService service(
      OfficialSelectedFileGateway gateway, SqliteCatalog sql, FileObjectStore store) {
    return new DefaultSelectedFileService(
        gateway,
        new SqliteBuildObjectCatalog(sql, store),
        new SqliteSelectedFileCatalog(sql, store),
        new SqliteRetentionRepository(sql),
        Runnable::run,
        Runnable::run);
  }

  private static void attach(
      SqliteBuildObjectCatalog objects,
      BuildSnapshot build,
      String role,
      VerifiedSourceAlias alias) {
    objects.attachVerifiedObject(
        build.snapshotId(),
        role,
        alias.source(),
        alias.object(),
        alias.evidence(),
        CancellationToken.none());
  }

  private static void assertFailure(
      CompletionStage<?> operation, SelectedFileException.Code expected) {
    var failure =
        assertThrows(CompletionException.class, () -> operation.toCompletableFuture().join());
    assertEquals(
        expected, assertInstanceOf(SelectedFileException.class, failure.getCause()).code());
  }

  private static void replaceRecord(
      SqliteCatalog sql, SelectedFileAssociation original, byte[] record) throws Exception {
    try (var connection = sql.connect();
        var update =
            connection.prepareStatement(
                "UPDATE selected_file_associations SET record=? WHERE snapshot_id=? AND role=?")) {
      update.setBytes(1, record);
      update.setString(2, original.snapshotId());
      update.setString(3, original.role());
      assertEquals(1, update.executeUpdate());
    }
  }

  private static List<String> state(SqliteCatalog sql) throws Exception {
    var result = new ArrayList<String>();
    try (var connection = sql.connect()) {
      for (String table :
          List.of(
              "canonical_content_slots",
              "canonical_legacy_conflicts",
              "canonical_legacy_blocks",
              "selected_file_associations",
              "encoded_file_receipts",
              "retention_scopes",
              "retention_scope_requirements",
              "build_object_memberships")) {
        try (var rows = connection.createStatement().executeQuery("SELECT * FROM " + table)) {
          while (rows.next()) {
            var values = new ArrayList<String>();
            for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
              var value = rows.getObject(column);
              values.add(
                  value instanceof byte[] bytes
                      ? HexFormat.of().formatHex(bytes)
                      : String.valueOf(value));
            }
            result.add(table + values);
          }
        }
      }
    }
    result.sort(String::compareTo);
    return result;
  }
}
