package io.vibeminer.infrastructure.item;

import static io.vibeminer.application.itemnames.ItemNamesException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.itemnames.ItemNamesException;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.itemnames.SparseNameProjectionSourceReader;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SelectedFileRecords;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.ByteBuffer;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Short transactions and generation checks for a single shared derived artifact. */
final class SqliteSparseNameRepository {
  record Header(
      SparseNameArtifactDefinition definition,
      UUID generation,
      UUID owner,
      boolean ready,
      PreparedSparseNames.Coverage coverage) {}

  record Stage(String artifactId, UUID generation) {}

  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  private final SqliteCatalog catalog;
  private final FileObjectStore.StoreSession session;

  SqliteSparseNameRepository(SqliteCatalog catalog, FileObjectStore.StoreSession session) {
    this.catalog = Objects.requireNonNull(catalog);
    this.session = Objects.requireNonNull(session);
  }

  Header claim(SparseNameArtifactDefinition definition, CancellationToken token) {
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
            if (!old.ready() && old.owner().equals(session.epoch()))
              throw failure(BUSY, "This artifact is already being prepared");
            // Only explicit prepare with a fresh verified source lease replaces old derived
            // copy facts or reclaims interrupted staging. Never hash old SQL facts into proof.
            try (var contexts =
                c.prepareStatement("DELETE FROM sparse_name_contexts WHERE artifact_id=?")) {
              contexts.setString(1, definition.artifactId());
              contexts.executeUpdate();
            }
            try (var query =
                c.prepareStatement(
                    "DELETE FROM sparse_name_artifacts WHERE artifact_id=? AND generation=?")) {
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
                      + " sparse_name_artifacts(artifact_id,definition,definition_sha256,generation,owner_epoch,status,section_ordinal,copy_proof_version)"
                      + " VALUES(?,?,?,?,?,'STAGING',?,1)")) {
            query.setString(1, definition.artifactId());
            var encodedDefinition = SparseNameRecords.definition(definition);
            query.setBytes(2, encodedDefinition);
            query.setString(3, sha256(encodedDefinition));
            query.setString(4, generation.toString());
            query.setString(5, session.epoch().toString());
            query.setInt(6, definition.interpretation().sectionOrdinal());
            query.executeUpdate();
          }
          return new Header(definition, generation, session.epoch(), false, null);
        });
  }

  void rows(Stage stage, List<DisplayCandidate> rows, CancellationToken token) {
    if (rows.size() > 128) throw failure(LIMIT_EXCEEDED, "Name row batch exceeds128");
    tx(
        token,
        c -> {
          var header = requireStage(c, stage);
          try (var query =
              c.prepareStatement("INSERT INTO sparse_name_rows VALUES(?,?,?,?,?,?,?)")) {
            for (var row : rows) {
              requireRowDefinition(row, header.definition());
              var bytes = SparseNameRecords.candidate(row);
              query.setString(1, stage.artifactId());
              query.setString(2, stage.generation().toString());
              query.setLong(3, row.physicalRecordId());
              query.setInt(4, row.physicalOrdinal());
              query.setInt(5, row.utf8Bytes());
              query.setBytes(6, bytes);
              query.setString(7, sha256(bytes));
              query.addBatch();
            }
            query.executeBatch();
          }
          return null;
        });
  }

  void copies(
      Stage stage,
      List<SparseNameProjectionSourceReader.CopyDeclaration> copies,
      CancellationToken token) {
    if (copies.size() > 128) throw failure(LIMIT_EXCEEDED, "Name copy batch exceeds128");
    tx(
        token,
        c -> {
          requireStage(c, stage);
          try (var query =
              c.prepareStatement(
                  "INSERT INTO"
                      + " sparse_name_copies(artifact_id,generation,target_id,source_id,record,record_sha256)"
                      + " VALUES(?,?,?,?,?,?)")) {
            for (var copy : copies) {
              query.setString(1, stage.artifactId());
              query.setString(2, stage.generation().toString());
              query.setLong(3, copy.targetId());
              query.setLong(4, copy.sourceId());
              var record = copyRecord(copy.targetId(), copy.sourceId());
              query.setBytes(5, record);
              query.setString(6, sha256(record));
              query.addBatch();
            }
            query.executeBatch();
          }
          return null;
        });
  }

  PreparedSparseNames publish(
      Stage stage,
      SparseNameArtifactDefinition definition,
      ItemProjectionEvidence evidence,
      PreparedSparseNames.Coverage coverage,
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
                  "SELECT 1 FROM sparse_name_copies c JOIN sparse_name_rows r ON"
                      + " r.artifact_id=c.artifact_id AND r.generation=c.generation AND"
                      + " r.record_id=c.target_id WHERE c.artifact_id=? AND c.generation=?"
                      + " LIMIT 1")) {
            bind(query, stage);
            try (var rows = query.executeQuery()) {
              if (rows.next())
                throw failure(CORRUPT, "Copy target overlaps an indexed physical row");
            }
          }
          var handle = new PreparedSparseNames(definition, evidence, coverage);
          var canonical = attachContext(c, handle);
          try (var query =
              c.prepareStatement(
                  "UPDATE sparse_name_artifacts SET"
                      + " status='READY',physical_rows=?,copy_count=?,excluded_sections=?,projected_utf8_bytes=?"
                      + " WHERE artifact_id=? AND generation=? AND owner_epoch=? AND"
                      + " status='STAGING'")) {
            query.setLong(1, coverage.physicalRows());
            query.setLong(2, coverage.unindexedCopyDeclarations());
            query.setInt(3, coverage.excludedSections());
            query.setLong(4, coverage.projectedUtf8Bytes());
            query.setString(5, stage.artifactId());
            query.setString(6, stage.generation().toString());
            query.setString(7, session.epoch().toString());
            if (query.executeUpdate() != 1)
              throw failure(BUSY, "Projection generation retired before publication");
          }
          return canonical;
        });
  }

  PreparedSparseNames reuse(
      Header header,
      ItemProjectionEvidence evidence,
      List<SparseNameProjectionSourceReader.CopyDeclaration> authenticatedCopies,
      CancellationToken token) {
    return tx(
        token,
        c -> {
          var current =
              readHeader(c, header.definition().artifactId())
                  .orElseThrow(() -> failure(NOT_READY, "Projection disappeared"));
          if (!current.ready() || !current.generation().equals(header.generation()))
            throw failure(NOT_READY, "Projection is not ready");
          verifyCounts(c, header.definition().artifactId(), current);
          var handle = new PreparedSparseNames(current.definition(), evidence, current.coverage());
          validateSourceCopies(c, handle, authenticatedCopies);
          return attachContext(
              c, new PreparedSparseNames(current.definition(), evidence, current.coverage()));
        });
  }

  private PreparedSparseNames attachContext(Connection c, PreparedSparseNames handle)
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
            "SELECT length(evidence),evidence,evidence_sha256 FROM sparse_name_contexts WHERE"
                + " artifact_id=? AND snapshot_id=? AND source_role=? AND locale=?")) {
      context(query, handle);
      try (var rows = query.executeQuery()) {
        if (rows.next()) {
          if (rows.getLong(1) > SparseNameRecords.MAX_EVIDENCE_BYTES)
            throw failure(CORRUPT, "Oversized projection context");
          if (!sha256(rows.getBytes(2)).equals(rows.getString(3)))
            throw failure(CORRUPT, "Name context checksum changed");
          var original = SparseNameRecords.evidence(rows.getBytes(2), build);
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
          return new PreparedSparseNames(handle.definition(), original, handle.coverage());
        }
      }
    }
    try (var query = c.prepareStatement("INSERT INTO sparse_name_contexts VALUES(?,?,?,?,?,?)")) {
      context(query, handle);
      var proof = SparseNameRecords.evidence(handle.evidence());
      query.setBytes(5, proof);
      query.setString(6, sha256(proof));
      query.executeUpdate();
    }
    return handle;
  }

  <T> T query(PreparedSparseNames handle, CancellationToken token, Work<T> work) {
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
                  "SELECT length(evidence),evidence,evidence_sha256 FROM sparse_name_contexts WHERE"
                      + " artifact_id=? AND snapshot_id=? AND source_role=? AND locale=?")) {
            context(statement, handle);
            try (var rows = statement.executeQuery()) {
              if (!rows.next())
                throw failure(NOT_READY, "Projection is not attached to this exact source context");
              if (rows.getLong(1) > SparseNameRecords.MAX_EVIDENCE_BYTES
                  || !sha256(rows.getBytes(2)).equals(rows.getString(3))
                  || !SparseNameRecords.evidence(rows.getBytes(2), build).equals(handle.evidence()))
                throw failure(CORRUPT, "Prepared handle changed original evidence");
            }
          }
          return work.run(c);
        });
  }

  PreparedSparseNames loadTrusted(PreparedSparseNames handle, CancellationToken token) {
    return query(
        handle,
        token,
        c -> {
          var build =
              catalog
                  .readSnapshot(c, handle.snapshotId())
                  .orElseThrow(() -> failure(MISSING_LOCAL, "Exact name snapshot is missing"));
          try (var q =
              c.prepareStatement(
                  "SELECT evidence FROM sparse_name_contexts WHERE artifact_id=? AND snapshot_id=?"
                      + " AND source_role=? AND locale=?")) {
            context(q, handle);
            try (var rows = q.executeQuery()) {
              if (!rows.next()) throw failure(NOT_READY, "Exact name context is missing");
              return new PreparedSparseNames(
                  handle.definition(),
                  SparseNameRecords.evidence(rows.getBytes(1), build),
                  handle.coverage());
            }
          }
        });
  }

  List<DisplayCandidate> page(Connection c, PreparedSparseNames handle, long after, int limit)
      throws SQLException {
    var result = new ArrayList<DisplayCandidate>();
    try (var query =
        c.prepareStatement(
            "SELECT record_id,physical_ordinal,length(record),record,record_sha256 FROM"
                + " sparse_name_rows WHERE artifact_id=? AND generation=(SELECT generation FROM"
                + " sparse_name_artifacts WHERE artifact_id=?) AND record_id>? ORDER BY record_id"
                + " ASC LIMIT ?")) {
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

  Optional<DisplayCandidate> exact(Connection c, PreparedSparseNames handle, long id)
      throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT record_id,physical_ordinal,length(record),record,record_sha256 FROM"
                + " sparse_name_rows WHERE artifact_id=? AND generation=(SELECT generation FROM"
                + " sparse_name_artifacts WHERE artifact_id=?) AND record_id=?")) {
      query.setString(1, handle.artifactId());
      query.setString(2, handle.artifactId());
      query.setLong(3, id);
      try (var rows = query.executeQuery()) {
        return rows.next() ? Optional.of(readRow(rows, handle)) : Optional.empty();
      }
    }
  }

  OptionalLong copy(Connection c, PreparedSparseNames handle, long id) throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT target_id,source_id,length(record),record,record_sha256 FROM"
                + " sparse_name_copies WHERE artifact_id=? AND generation=(SELECT generation"
                + " FROM sparse_name_artifacts WHERE artifact_id=?) AND target_id=?")) {
      query.setString(1, handle.artifactId());
      query.setString(2, handle.artifactId());
      query.setLong(3, id);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return OptionalLong.empty();
        return OptionalLong.of(readCopy(rows, id));
      }
    }
  }

  /** Full copy comparison is explicit-prepare work, never a page/exact-query scan. */
  private void validateSourceCopies(
      Connection c,
      PreparedSparseNames handle,
      List<SparseNameProjectionSourceReader.CopyDeclaration> source)
      throws SQLException {
    var expected = new ArrayList<>(source);
    expected.sort(
        Comparator.comparingLong(SparseNameProjectionSourceReader.CopyDeclaration::targetId));
    try (var query =
        c.prepareStatement(
            "SELECT target_id,source_id,length(record),record,record_sha256 FROM"
                + " sparse_name_copies WHERE artifact_id=? AND generation=(SELECT generation"
                + " FROM sparse_name_artifacts WHERE artifact_id=?) ORDER BY target_id")) {
      query.setString(1, handle.artifactId());
      query.setString(2, handle.artifactId());
      try (var rows = query.executeQuery()) {
        for (var declaration : expected)
          if (!rows.next() || readCopy(rows, declaration.targetId()) != declaration.sourceId())
            throw failure(
                CORRUPT, "Persisted copies differ from authenticated source declarations");
        if (rows.next()) throw failure(CORRUPT, "Projection contains extra copy declarations");
      }
    }
  }

  private static long readCopy(ResultSet rows, long expectedId) throws SQLException {
    if (rows.getObject(3) == null || rows.getLong(3) != 20)
      throw failure(CORRUPT, "Missing or invalid copy proof record");
    var record = rows.getBytes(4);
    if (!sha256(record).equals(rows.getString(5)))
      throw failure(CORRUPT, "Copy declaration checksum changed");
    var decoded = readCopyRecord(record);
    if (decoded[0] != expectedId || decoded[0] != rows.getLong(1) || decoded[1] != rows.getLong(2))
      throw failure(CORRUPT, "Indexed copy declaration differs from original record");
    return decoded[1];
  }

  private void requireDependencies(Connection c, PreparedSparseNames handle) throws SQLException {
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
          var canonicalEvidence =
              new SourceVerificationEvidence(
                  source.verificationSemantics(),
                  rows.getString(8),
                  Instant.parse(rows.getString(9)));
          var expected = entry.getValue();
          if (!source.equals(expected.source())
              || !object.equals(expected.object())
              || !evidence.equals(expected.evidence())
              || !canonicalEvidence.equals(expected.evidence()))
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

  private DisplayCandidate readRow(ResultSet rows, PreparedSparseNames handle) throws SQLException {
    if (rows.getLong(3) > SparseNameRecords.MAX_CANDIDATE_BYTES)
      throw failure(CORRUPT, "Oversized projection row");
    var bytes = rows.getBytes(4);
    if (!sha256(bytes).equals(rows.getString(5)))
      throw failure(CORRUPT, "Projection row checksum changed");
    var row = SparseNameRecords.candidate(bytes);
    if (row.physicalRecordId() != rows.getLong(1)
        || row.physicalOrdinal() != rows.getInt(2)
        || row.sectionOrdinal() != handle.coverage().sectionOrdinal()
        || row.physicalOrdinal() >= handle.coverage().physicalRows())
      throw failure(CORRUPT, "Projected physical identity changed");
    handle.requireMatch(
        new ItemNameMatch(
            row.physicalRecordId(),
            ItemNameMatch.Outcome.PHYSICAL_NAME,
            Optional.of(row),
            OptionalLong.empty()));
    return row;
  }

  private static void requireRowDefinition(
      DisplayCandidate row, SparseNameArtifactDefinition definition) {
    var i = definition.interpretation();
    if (row.sectionOrdinal() != i.sectionOrdinal()
        || row.decodedOffset() < i.recordAreaOffset()
        || row.decodedOffset() + row.recordBytes() > i.recordAreaOffset() + i.recordAreaBytes())
      throw failure(CORRUPT, "Name row is outside the admitted definition geometry");
  }

  void discard(Stage stage) {
    tx(
        CancellationToken.none(),
        c -> {
          try (var query =
              c.prepareStatement(
                  "DELETE FROM sparse_name_artifacts WHERE artifact_id=? AND generation=? AND"
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
                + " length(definition),definition,generation,owner_epoch,status,section_ordinal,physical_rows,copy_count,excluded_sections,copy_proof_version,projected_utf8_bytes,definition_sha256"
                + " FROM sparse_name_artifacts WHERE artifact_id=?")) {
      query.setString(1, id);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        if (rows.getLong(1) > 262144) throw failure(CORRUPT, "Oversized artifact definition");
        var definitionBytes = rows.getBytes(2);
        if (!sha256(definitionBytes).equals(rows.getString(12)))
          throw failure(CORRUPT, "Name definition checksum changed");
        var definition = SparseNameRecords.definition(definitionBytes);
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
                ? new PreparedSparseNames.Coverage(
                    rows.getInt(6),
                    integer(rows, 7),
                    integer(rows, 8),
                    Math.toIntExact(integer(rows, 9)),
                    integer(rows, 11))
                : null;
        copyProofVersion(rows, 10);
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
            "SELECT"
                + " count(*),min(physical_ordinal),max(physical_ordinal),coalesce(sum(utf8_bytes),0)"
                + " FROM sparse_name_rows WHERE artifact_id=? AND generation=?")) {
      query.setString(1, id);
      query.setString(2, header.generation().toString());
      try (var rows = query.executeQuery()) {
        long count = rows.getLong(1);
        if (count != coverage.physicalRows()
            || rows.getLong(4) != coverage.projectedUtf8Bytes()
            || count > 0 && (rows.getLong(2) != 0 || rows.getLong(3) != count - 1))
          throw failure(CORRUPT, "Projection physical coverage is incomplete");
      }
    }
    try (var query =
        c.prepareStatement(
            "SELECT count(*) FROM sparse_name_copies" + " WHERE artifact_id=? AND generation=?")) {
      query.setString(1, id);
      query.setString(2, header.generation().toString());
      try (var rows = query.executeQuery()) {
        if (rows.getLong(1) != coverage.unindexedCopyDeclarations())
          throw failure(CORRUPT, "Projection copy coverage changed");
      }
    }
  }

  private static boolean copyProofVersion(ResultSet rows, int column) throws SQLException {
    var value = rows.getObject(column);
    if (!(value instanceof Integer || value instanceof Long) || rows.getLong(column) != 1)
      throw failure(CORRUPT, "Unknown copy proof version");
    return true;
  }

  private static byte[] copyRecord(long target, long source) {
    return ByteBuffer.allocate(20)
        .putInt(SparseNameRecords.COPY_VERSION)
        .putLong(target)
        .putLong(source)
        .array();
  }

  private static long[] readCopyRecord(byte[] record) {
    var input = ByteBuffer.wrap(record);
    if (input.getInt() != SparseNameRecords.COPY_VERSION)
      throw failure(CORRUPT, "Unknown copy record version");
    long target = input.getLong(), source = input.getLong();
    if (target < 1 || target > 0xffffffffL || source < 1 || source > 0xffffffffL)
      throw failure(CORRUPT, "Invalid original copy identifiers");
    return new long[] {target, source};
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

  private static void context(PreparedStatement query, PreparedSparseNames handle)
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
      throw new ItemNamesException(
          failure.getErrorCode() == 19
              ? CORRUPT
              : failure.getErrorCode() == 5 ? BUSY : STORAGE_UNAVAILABLE,
          "Sparse name storage failed",
          failure);
    } catch (io.vibeminer.application.selectedfile.SelectedFileException failure) {
      throw new ItemNamesException(CORRUPT, "Stored projection association is invalid", failure);
    } catch (IllegalArgumentException | ArithmeticException failure) {
      throw new ItemNamesException(CORRUPT, "Invalid stored name metadata", failure);
    }
  }

  private static ItemNamesException failure(ItemNamesException.Code code, String message) {
    return new ItemNamesException(code, message);
  }
}
