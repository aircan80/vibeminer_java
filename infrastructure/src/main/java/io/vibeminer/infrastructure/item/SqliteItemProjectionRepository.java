package io.vibeminer.infrastructure.item;

import static io.vibeminer.application.item.ItemProjectionException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.item.ItemProjectionException;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SelectedFileRecords;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Short transactions and generation checks for a single shared derived artifact. */
final class SqliteItemProjectionRepository {
  record Header(
      ItemArtifactDefinition definition,
      UUID generation,
      UUID owner,
      boolean ready,
      PreparedItemProjection.Coverage coverage) {}

  record Stage(String artifactId, UUID generation) {}

  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  private final SqliteCatalog catalog;
  private final FileObjectStore.StoreSession session;

  SqliteItemProjectionRepository(SqliteCatalog catalog, FileObjectStore.StoreSession session) {
    this.catalog = Objects.requireNonNull(catalog);
    this.session = Objects.requireNonNull(session);
  }

  Header claim(ItemArtifactDefinition definition, CancellationToken token) {
    return tx(
        token,
        c -> {
          var previous = readHeader(c, definition.artifactId());
          if (previous.isPresent()) {
            var old = previous.get();
            if (!old.definition().equals(definition))
              throw failure(CORRUPT, "Shared artifact definition changed");
            if (old.ready()) {
              verifyCounts(c, definition.artifactId(), old);
              return old;
            }
            if (old.owner().equals(session.epoch()))
              throw failure(BUSY, "This artifact is already being prepared");
            // Only explicit prepare under a newly fenced exclusive store epoch reclaims old
            // staging.
            try (var query =
                c.prepareStatement(
                    "DELETE FROM item_projection_artifacts WHERE artifact_id=? AND generation=? AND"
                        + " status='STAGING'")) {
              query.setString(1, definition.artifactId());
              query.setString(2, old.generation().toString());
              if (query.executeUpdate() != 1)
                throw failure(BUSY, "Interrupted artifact generation changed");
            }
          }
          var generation = UUID.randomUUID();
          try (var query =
              c.prepareStatement(
                  "INSERT INTO"
                      + " item_projection_artifacts(artifact_id,definition,generation,owner_epoch,status,section_ordinal)"
                      + " VALUES(?,?,?,?,'STAGING',?)")) {
            query.setString(1, definition.artifactId());
            query.setBytes(2, ItemProjectionRecords.definition(definition));
            query.setString(3, generation.toString());
            query.setString(4, session.epoch().toString());
            query.setInt(5, definition.interpretation().sectionOrdinal());
            query.executeUpdate();
          }
          return new Header(definition, generation, session.epoch(), false, null);
        });
  }

  void rows(Stage stage, List<ItemPhysicalRow> rows, CancellationToken token) {
    tx(
        token,
        c -> {
          requireStage(c, stage);
          try (var query =
              c.prepareStatement("INSERT INTO item_projection_rows VALUES(?,?,?,?,?,?)")) {
            for (var row : rows) {
              var bytes = ItemProjectionRecords.row(row);
              query.setString(1, stage.artifactId());
              query.setString(2, stage.generation().toString());
              query.setLong(3, row.recordId());
              query.setInt(4, row.physicalOrdinal());
              query.setBytes(5, bytes);
              query.setString(6, sha256(bytes));
              query.addBatch();
            }
            query.executeBatch();
          }
          return null;
        });
  }

  void copies(
      Stage stage,
      List<ItemProjectionSourceReader.CopyDeclaration> copies,
      CancellationToken token) {
    tx(
        token,
        c -> {
          requireStage(c, stage);
          try (var query =
              c.prepareStatement("INSERT INTO item_projection_copies VALUES(?,?,?,?)")) {
            for (var copy : copies) {
              query.setString(1, stage.artifactId());
              query.setString(2, stage.generation().toString());
              query.setLong(3, copy.targetId());
              query.setLong(4, copy.sourceId());
              query.addBatch();
            }
            query.executeBatch();
          }
          return null;
        });
  }

  PreparedItemProjection publish(
      Stage stage,
      ItemArtifactDefinition definition,
      ItemProjectionEvidence evidence,
      PreparedItemProjection.Coverage coverage,
      CancellationToken token) {
    return tx(
        token,
        c -> {
          var header = requireStage(c, stage);
          if (!header.definition().equals(definition))
            throw failure(CORRUPT, "Publication definition changed");
          verifyCounts(
              c,
              stage.artifactId(),
              new Header(definition, stage.generation(), session.epoch(), false, coverage));
          try (var query =
              c.prepareStatement(
                  "SELECT 1 FROM item_projection_copies c JOIN item_projection_rows r ON"
                      + " r.artifact_id=c.artifact_id AND r.generation=c.generation AND"
                      + " r.record_id=c.target_id WHERE c.artifact_id=? AND c.generation=?"
                      + " LIMIT 1")) {
            bind(query, stage);
            try (var rows = query.executeQuery()) {
              if (rows.next())
                throw failure(CORRUPT, "Copy target overlaps an indexed physical row");
            }
          }
          var handle = new PreparedItemProjection(definition, evidence, coverage);
          var canonical = attachContext(c, handle);
          try (var query =
              c.prepareStatement(
                  "UPDATE item_projection_artifacts SET"
                      + " status='READY',physical_rows=?,copy_count=?,excluded_sections=? WHERE"
                      + " artifact_id=? AND generation=? AND owner_epoch=? AND status='STAGING'")) {
            query.setLong(1, coverage.physicalRows());
            query.setLong(2, coverage.unindexedCopyDeclarations());
            query.setInt(3, coverage.excludedSections());
            query.setString(4, stage.artifactId());
            query.setString(5, stage.generation().toString());
            query.setString(6, session.epoch().toString());
            if (query.executeUpdate() != 1)
              throw failure(BUSY, "Projection generation retired before publication");
          }
          return canonical;
        });
  }

  PreparedItemProjection reuse(
      Header header, ItemProjectionEvidence evidence, CancellationToken token) {
    return tx(
        token,
        c -> {
          var current =
              readHeader(c, header.definition().artifactId())
                  .orElseThrow(() -> failure(NOT_READY, "Projection disappeared"));
          if (!current.ready() || !current.generation().equals(header.generation()))
            throw failure(NOT_READY, "Projection is not ready");
          verifyCounts(c, header.definition().artifactId(), current);
          return attachContext(
              c, new PreparedItemProjection(current.definition(), evidence, current.coverage()));
        });
  }

  private PreparedItemProjection attachContext(Connection c, PreparedItemProjection handle)
      throws SQLException {
    var association = handle.evidence().association();
    requireDependencies(c, handle);
    var build =
        catalog
            .readSnapshot(c, handle.snapshotId())
            .orElseThrow(() -> failure(MISSING_LOCAL, "Projection exact snapshot is missing"));
    try (var query =
        c.prepareStatement(
            "SELECT namespace,source_key,verifier_semantics FROM build_object_memberships WHERE"
                + " snapshot_id=? AND role=?")) {
      query.setString(1, handle.snapshotId());
      query.setString(2, handle.sourceRole());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) throw failure(MISSING_LOCAL, "Projection source membership is missing");
        var source = association.representation().encoded().source();
        if (!source.namespace().equals(rows.getString(1))
            || !source.immutableSourceKey().equals(rows.getString(2))
            || !source.verificationSemantics().equals(rows.getString(3)))
          throw failure(CORRUPT, "Projection source membership changed");
      }
    }
    try (var query =
        c.prepareStatement(
            "SELECT length(evidence),evidence FROM item_projection_contexts WHERE artifact_id=? AND"
                + " snapshot_id=? AND source_role=? AND locale=?")) {
      context(query, handle);
      try (var rows = query.executeQuery()) {
        if (rows.next()) {
          if (rows.getLong(1) > ItemProjectionRecords.MAX_RECORD_BYTES)
            throw failure(CORRUPT, "Oversized projection context");
          var original = ItemProjectionRecords.evidence(rows.getBytes(2), build);
          var incoming = handle.evidence();
          var normalized =
              new ItemProjectionEvidence(
                  original.association(),
                  new ItemProjectionEvidence.LocalVerification(
                      original.localVerification().object(),
                      incoming.localVerification().verifiedAt(),
                      original.localVerification().verifierRevision()),
                  original.chunks(),
                  original.windows(),
                  original.fullDecoded());
          if (!normalized.equals(incoming))
            throw failure(CORRUPT, "Immutable projection context changed");
          return new PreparedItemProjection(handle.definition(), original, handle.coverage());
        }
      }
    }
    try (var query = c.prepareStatement("INSERT INTO item_projection_contexts VALUES(?,?,?,?,?)")) {
      context(query, handle);
      query.setBytes(5, ItemProjectionRecords.evidence(handle.evidence()));
      query.executeUpdate();
    }
    return handle;
  }

  <T> T query(PreparedItemProjection handle, CancellationToken token, Work<T> work) {
    return tx(
        token,
        c -> {
          var header =
              readHeader(c, handle.artifactId())
                  .orElseThrow(() -> failure(NOT_READY, "Projection is not prepared"));
          if (!header.ready()) throw failure(NOT_READY, "Projection is still being prepared");
          if (!header.definition().equals(handle.definition())
              || !header.coverage().equals(handle.coverage()))
            throw failure(CORRUPT, "Prepared handle differs from persisted artifact");
          verifyCounts(c, handle.artifactId(), header);
          requireDependencies(c, handle);
          var build =
              catalog
                  .readSnapshot(c, handle.snapshotId())
                  .orElseThrow(
                      () -> failure(MISSING_LOCAL, "Exact projection snapshot is missing"));
          try (var statement =
              c.prepareStatement(
                  "SELECT length(evidence),evidence FROM item_projection_contexts WHERE"
                      + " artifact_id=? AND snapshot_id=? AND source_role=? AND locale=?")) {
            context(statement, handle);
            try (var rows = statement.executeQuery()) {
              if (!rows.next())
                throw failure(NOT_READY, "Projection is not attached to this exact source context");
              if (rows.getLong(1) > ItemProjectionRecords.MAX_RECORD_BYTES
                  || !ItemProjectionRecords.evidence(rows.getBytes(2), build)
                      .equals(handle.evidence()))
                throw failure(CORRUPT, "Prepared handle changed original evidence");
            }
          }
          return work.run(c);
        });
  }

  List<ItemPhysicalRow> page(Connection c, PreparedItemProjection handle, long after, int limit)
      throws SQLException {
    var result = new ArrayList<ItemPhysicalRow>();
    try (var query =
        c.prepareStatement(
            "SELECT record_id,physical_ordinal,length(row),row,row_sha256 FROM item_projection_rows"
                + " WHERE artifact_id=? AND generation=(SELECT generation FROM"
                + " item_projection_artifacts WHERE artifact_id=?) AND record_id>? ORDER BY"
                + " record_id ASC LIMIT ?")) {
      query.setString(1, handle.artifactId());
      query.setString(2, handle.artifactId());
      query.setLong(3, after);
      query.setInt(4, limit);
      try (var rows = query.executeQuery()) {
        while (rows.next()) result.add(readRow(rows, handle));
      }
    }
    return result;
  }

  Optional<ItemPhysicalRow> exact(Connection c, PreparedItemProjection handle, long id)
      throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT record_id,physical_ordinal,length(row),row,row_sha256 FROM item_projection_rows"
                + " WHERE artifact_id=? AND generation=(SELECT generation FROM"
                + " item_projection_artifacts WHERE artifact_id=?) AND record_id=?")) {
      query.setString(1, handle.artifactId());
      query.setString(2, handle.artifactId());
      query.setLong(3, id);
      try (var rows = query.executeQuery()) {
        return rows.next() ? Optional.of(readRow(rows, handle)) : Optional.empty();
      }
    }
  }

  OptionalLong copy(Connection c, PreparedItemProjection handle, long id) throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT source_id FROM item_projection_copies WHERE artifact_id=? AND"
                + " generation=(SELECT generation FROM item_projection_artifacts WHERE"
                + " artifact_id=?) AND target_id=?")) {
      query.setString(1, handle.artifactId());
      query.setString(2, handle.artifactId());
      query.setLong(3, id);
      try (var rows = query.executeQuery()) {
        return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
      }
    }
  }

  private void requireDependencies(Connection c, PreparedItemProjection handle)
      throws SQLException {
    var association = handle.evidence().association();
    for (var entry :
        Map.of(
                "BUILD_CONFIG",
                association.configuration(),
                "ROOT_MANIFEST",
                association.root(),
                handle.sourceRole(),
                association.representation().encoded())
            .entrySet()) {
      try (var query =
          c.prepareStatement(
              "SELECT"
                  + " m.namespace,m.source_key,m.verifier_semantics,m.source_url,m.verified_at,a.object_digest,o.length,a.source_url,a.verified_at"
                  + " FROM build_object_memberships m LEFT JOIN source_aliases a ON"
                  + " a.namespace=m.namespace AND a.source_key=m.source_key AND"
                  + " a.verifier_semantics=m.verifier_semantics LEFT JOIN catalog_objects o ON"
                  + " o.digest=a.object_digest WHERE m.snapshot_id=? AND m.role=?")) {
        query.setString(1, handle.snapshotId());
        query.setString(2, entry.getKey());
        try (var rows = query.executeQuery()) {
          if (!rows.next())
            throw failure(MISSING_LOCAL, "Projection dependency membership is missing");
          if (rows.getString(6) == null
              || !(rows.getObject(7) instanceof Integer || rows.getObject(7) instanceof Long))
            throw failure(CORRUPT, "Projection dependency metadata is incomplete");
          var source =
              new ResolvedSourceIdentity(rows.getString(1), rows.getString(2), rows.getString(3));
          var object = new StoredObject(new ObjectDigest(rows.getString(6)), rows.getLong(7));
          var evidence =
              new SourceVerificationEvidence(
                  source.verificationSemantics(),
                  rows.getString(4),
                  Instant.parse(rows.getString(5)));
          new SourceVerificationEvidence(
              source.verificationSemantics(), rows.getString(8), Instant.parse(rows.getString(9)));
          var expected = entry.getValue();
          if (!source.equals(expected.source())
              || !object.equals(expected.object())
              || !evidence.equals(expected.evidence()))
            throw failure(CORRUPT, "Projection dependency differs from immutable source evidence");
        }
      }
    }
    var build =
        catalog
            .readSnapshot(c, handle.snapshotId())
            .orElseThrow(() -> failure(MISSING_LOCAL, "Projection exact snapshot missing"));
    try (var query =
        c.prepareStatement(
            "SELECT length(record),record FROM selected_file_associations WHERE snapshot_id=? AND"
                + " role=?")) {
      query.setString(1, handle.snapshotId());
      query.setString(2, handle.sourceRole());
      try (var rows = query.executeQuery()) {
        if (!rows.next())
          throw failure(MISSING_LOCAL, "Projection original selected association missing");
        if (rows.getLong(1) > 65536) throw failure(CORRUPT, "Oversized original association");
        var saved =
            SelectedFileRecords.association(rows.getBytes(2), build, association.representation());
        if (!saved.equals(association))
          throw failure(CORRUPT, "Projection original association changed");
      }
    }
  }

  private ItemPhysicalRow readRow(ResultSet rows, PreparedItemProjection handle)
      throws SQLException {
    if (rows.getLong(3) > ItemProjectionRecords.MAX_ROW_BYTES)
      throw failure(CORRUPT, "Oversized projection row");
    var bytes = rows.getBytes(4);
    if (!sha256(bytes).equals(rows.getString(5)))
      throw failure(CORRUPT, "Projection row checksum changed");
    var row = ItemProjectionRecords.row(bytes);
    if (row.recordId() != rows.getLong(1)
        || row.physicalOrdinal() != rows.getInt(2)
        || row.sectionOrdinal() != handle.coverage().sectionOrdinal()
        || row.physicalOrdinal() >= handle.coverage().physicalRows())
      throw failure(CORRUPT, "Projected physical identity changed");
    return row;
  }

  void discard(Stage stage) {
    tx(
        CancellationToken.none(),
        c -> {
          try (var query =
              c.prepareStatement(
                  "DELETE FROM item_projection_artifacts WHERE artifact_id=? AND generation=? AND"
                      + " owner_epoch=? AND status='STAGING'")) {
            query.setString(1, stage.artifactId());
            query.setString(2, stage.generation().toString());
            query.setString(3, session.epoch().toString());
            query.executeUpdate();
          }
          return null;
        });
  }

  private Header requireStage(Connection c, Stage stage) throws SQLException {
    var header =
        readHeader(c, stage.artifactId())
            .orElseThrow(() -> failure(BUSY, "Projection generation has retired"));
    if (header.ready()
        || !header.generation().equals(stage.generation())
        || !header.owner().equals(session.epoch()))
      throw failure(BUSY, "Another projection generation owns publication");
    return header;
  }

  private Optional<Header> readHeader(Connection c, String id) throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT"
                + " length(definition),definition,generation,owner_epoch,status,section_ordinal,physical_rows,copy_count,excluded_sections"
                + " FROM item_projection_artifacts WHERE artifact_id=?")) {
      query.setString(1, id);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        if (rows.getLong(1) > 262144) throw failure(CORRUPT, "Oversized artifact definition");
        var definition = ItemProjectionRecords.definition(rows.getBytes(2));
        if (!definition.artifactId().equals(id)
            || rows.getInt(6) != definition.interpretation().sectionOrdinal())
          throw failure(CORRUPT, "Artifact identity changed");
        boolean ready =
            switch (rows.getString(5)) {
              case "READY" -> true;
              case "STAGING" -> false;
              default -> throw failure(CORRUPT, "Unknown artifact state");
            };
        var coverage =
            ready
                ? new PreparedItemProjection.Coverage(
                    rows.getInt(6),
                    integer(rows, 7),
                    integer(rows, 8),
                    Math.toIntExact(integer(rows, 9)))
                : null;
        return Optional.of(
            new Header(
                definition,
                UUID.fromString(rows.getString(3)),
                UUID.fromString(rows.getString(4)),
                ready,
                coverage));
      }
    }
  }

  private void verifyCounts(Connection c, String id, Header header) throws SQLException {
    var coverage = Objects.requireNonNull(header.coverage());
    try (var query =
        c.prepareStatement(
            "SELECT count(*),min(physical_ordinal),max(physical_ordinal) FROM item_projection_rows"
                + " WHERE artifact_id=? AND generation=?")) {
      query.setString(1, id);
      query.setString(2, header.generation().toString());
      try (var rows = query.executeQuery()) {
        long count = rows.getLong(1);
        if (count != coverage.physicalRows()
            || count > 0 && (rows.getLong(2) != 0 || rows.getLong(3) != count - 1))
          throw failure(CORRUPT, "Projection physical coverage is incomplete");
      }
    }
    try (var query =
        c.prepareStatement(
            "SELECT count(*) FROM item_projection_copies WHERE artifact_id=? AND generation=?")) {
      query.setString(1, id);
      query.setString(2, header.generation().toString());
      try (var rows = query.executeQuery()) {
        if (rows.getLong(1) != coverage.unindexedCopyDeclarations())
          throw failure(CORRUPT, "Projection copy coverage changed");
      }
    }
  }

  private static long integer(ResultSet rows, int column) throws SQLException {
    if (!(rows.getObject(column) instanceof Integer || rows.getObject(column) instanceof Long)
        || rows.getLong(column) < 0) throw failure(CORRUPT, "Invalid artifact coverage integer");
    return rows.getLong(column);
  }

  private static void bind(PreparedStatement query, Stage stage) throws SQLException {
    query.setString(1, stage.artifactId());
    query.setString(2, stage.generation().toString());
  }

  private static void context(PreparedStatement query, PreparedItemProjection handle)
      throws SQLException {
    query.setString(1, handle.artifactId());
    query.setString(2, handle.snapshotId());
    query.setString(3, handle.sourceRole());
    query.setString(4, handle.locale());
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private <T> T tx(CancellationToken token, Work<T> work) {
    token.throwIfCancellationRequested();
    session.beforeProducerOperation();
    try (var connection = catalog.connect()) {
      connection.setAutoCommit(false);
      try {
        var result = work.run(connection);
        token.throwIfCancellationRequested();
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException | Error failure) {
        try {
          connection.rollback();
        } catch (SQLException suppressed) {
          failure.addSuppressed(suppressed);
        }
        throw failure;
      }
    } catch (SQLException failure) {
      throw new ItemProjectionException(
          failure.getErrorCode() == 19
              ? CORRUPT
              : failure.getErrorCode() == 5 ? BUSY : STORAGE_UNAVAILABLE,
          "Item projection storage failed",
          failure);
    } catch (io.vibeminer.application.selectedfile.SelectedFileException failure) {
      throw new ItemProjectionException(
          CORRUPT, "Stored projection association is invalid", failure);
    } catch (IllegalArgumentException | ArithmeticException failure) {
      throw new ItemProjectionException(
          CORRUPT, "Invalid stored Item projection metadata", failure);
    }
  }

  private static ItemProjectionException failure(
      ItemProjectionException.Code code, String message) {
    return new ItemProjectionException(code, message);
  }
}
