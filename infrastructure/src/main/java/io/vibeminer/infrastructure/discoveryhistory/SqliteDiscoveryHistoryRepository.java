package io.vibeminer.infrastructure.discoveryhistory;

import static io.vibeminer.application.discoveryhistory.DiscoveryHistoryException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.catalog.CatalogException;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** One additive metadata ledger. Owns no transport, content store or canonical proof authority. */
public final class SqliteDiscoveryHistoryRepository implements DiscoveryHistoryRepository {
  private final SqliteCatalog catalog;
  private final DiscoveryObservationMapper mapper;

  public SqliteDiscoveryHistoryRepository(SqliteCatalog catalog) {
    this(catalog, new DiscoveryObservationMapper());
  }

  public SqliteDiscoveryHistoryRepository(
      SqliteCatalog catalog, DiscoveryObservationMapper mapper) {
    this.catalog = Objects.requireNonNull(catalog);
    this.mapper = Objects.requireNonNull(mapper);
  }

  @Override
  public DiscoveryEvent append(
      DiscoveryEvent event, DiscoveryHistoryLimits limits, CancellationToken token) {
    if (event == null || limits == null || token == null)
      throw fail(INVALID_REQUEST, "Event, limits and cancellation required");
    token.throwIfCancellationRequested();
    var checked = mapper.replay(event);
    return tx(
        token,
        c -> {
          var existing = readEvent(c, checked.eventId(), token);
          if (existing.isPresent()) {
            if (!existing.get().equals(checked))
              throw fail(
                  IMMUTABLE_EVENT_CONFLICT,
                  "Observation UUID already names another immutable capture");
            return existing.get();
          }
          if (checked.builds().size() > limits.maximumBuildsPerEvent())
            throw fail(LIMIT_EXCEEDED, "Observation has too many matched build rows");
          byte[] record = DiscoveryHistoryRecords.event(checked);
          var bodies = new LinkedHashMap<String, byte[]>();
          for (var source : List.of(checked.versions().source(), checked.cdns().source())) {
            byte[] bytes = source.bytes();
            var previous = bodies.putIfAbsent(source.sha256(), bytes);
            if (previous != null && !Arrays.equals(previous, bytes))
              throw fail(CORRUPT_OBSERVATION, "One digest names different source bodies");
          }
          long addedBytes = 0;
          var newBodies = new LinkedHashMap<String, byte[]>();
          for (var body : bodies.entrySet()) {
            var stored = body(c, body.getKey());
            if (stored.isPresent()) {
              if (!Arrays.equals(stored.get(), body.getValue()))
                throw fail(CORRUPT_OBSERVATION, "Shared source body differs from its digest");
            } else {
              newBodies.put(body.getKey(), body.getValue());
              addedBytes = Math.addExact(addedBytes, body.getValue().length);
            }
          }
          if (Math.addExact(count(c, "discovery_history_events"), 1) > limits.maximumEvents()
              || Math.addExact(count(c, "discovery_history_builds"), checked.builds().size())
                  > limits.maximumBuildReferences())
            throw fail(QUOTA_EXCEEDED, "Observation event or build-reference quota reached");
          long existingBytes = scalar(c, "SELECT COALESCE(sum(length(bytes)),0) FROM source_blobs");
          if (addedBytes > 0
              && Math.addExact(existingBytes, addedBytes) > limits.maximumUniqueDocumentBytes())
            throw fail(QUOTA_EXCEEDED, "Shared captured-metadata byte quota reached");
          for (var entry : newBodies.entrySet())
            try (var insert = c.prepareStatement("INSERT INTO source_blobs VALUES(?,?)")) {
              insert.setString(1, entry.getKey());
              insert.setBytes(2, entry.getValue());
              insert.executeUpdate();
            }
          for (var row : checked.builds()) {
            token.throwIfCancellationRequested();
            catalog.ensureSnapshotIdentity(c, row.build());
          }
          try (var insert =
              c.prepareStatement(
                  "INSERT INTO"
                      + " discovery_history_events(event_id,product_id,region,completed_at,parser_revision,build_count,record,record_sha256)"
                      + " VALUES(?,?,?,?,?,?,?,?)")) {
            insert.setString(1, checked.eventId().toString());
            insert.setString(2, checked.productId());
            insert.setString(3, checked.region());
            insert.setString(4, checked.completedAt().toString());
            insert.setString(5, checked.parserRevision());
            insert.setInt(6, checked.builds().size());
            insert.setBytes(7, record);
            insert.setString(8, digest(record));
            insert.executeUpdate();
          }
          document(c, checked.eventId(), "versions", checked.versions().source());
          document(c, checked.eventId(), "cdns", checked.cdns().source());
          try (var insert =
              c.prepareStatement("INSERT INTO discovery_history_builds VALUES(?,?,?,?,?)")) {
            int position = 0;
            for (var row : checked.builds()) {
              insert.setString(1, checked.eventId().toString());
              insert.setInt(2, position++);
              insert.setString(3, row.build().snapshotId());
              insert.setInt(4, row.versionsRowOrdinal());
              insert.setString(5, row.build().channel());
              insert.addBatch();
            }
            insert.executeBatch();
          }
          return checked;
        });
  }

  @Override
  public Optional<DiscoveryEvent> read(UUID eventId, CancellationToken token) {
    if (eventId == null || token == null)
      throw fail(INVALID_REQUEST, "Observation UUID and cancellation required");
    return tx(token, c -> readEvent(c, eventId, token));
  }

  @Override
  public DiscoveryHistoryPage page(
      DiscoveryHistoryFilter filter,
      Optional<DiscoveryHistoryCursor> cursor,
      int size,
      CancellationToken token) {
    if (filter == null || cursor == null || token == null || size < 1 || size > 100)
      throw fail(INVALID_REQUEST, "Exact history filter and page size1..100 required");
    try {
      cursor.ifPresent(value -> value.requireContext(filter, size));
    } catch (IllegalArgumentException invalid) {
      throw new DiscoveryHistoryException(
          INVALID_REQUEST, "Cursor belongs to another history query", invalid);
    }
    return tx(
        token,
        c -> {
          long upper =
              cursor.isPresent()
                  ? cursor.get().upperSequence()
                  : scalar(
                      c, "SELECT COALESCE(max(event_sequence),0) FROM discovery_history_events");
          if (upper == 0) return new DiscoveryHistoryPage(filter, List.of(), Optional.empty());
          String sql =
              "SELECT e.event_id FROM discovery_history_events e WHERE e.product_id=? AND"
                  + " e.event_sequence<=?"
                  + (cursor.isPresent() ? " AND e.event_sequence<?" : "")
                  + (filter.region().isPresent() ? " AND e.region=?" : "")
                  + (filter.channel().isPresent()
                      ? " AND EXISTS(SELECT 1 FROM discovery_history_builds b WHERE"
                            + " b.event_id=e.event_id AND b.channel=?)"
                      : "")
                  + " ORDER BY e.event_sequence DESC LIMIT ?";
          var events = new ArrayList<DiscoveryEventSummary>();
          try (var q = c.prepareStatement(sql)) {
            int i = 1;
            q.setString(i++, filter.productId());
            q.setLong(i++, upper);
            if (cursor.isPresent()) q.setLong(i++, cursor.get().beforeSequence());
            if (filter.region().isPresent()) q.setString(i++, filter.region().get());
            if (filter.channel().isPresent()) q.setString(i++, filter.channel().get());
            q.setInt(i, size + 1);
            try (var rows = q.executeQuery()) {
              while (rows.next()) {
                token.throwIfCancellationRequested();
                events.add(
                    header(c, UUID.fromString(rows.getString(1)))
                        .orElseThrow(
                            () -> fail(CORRUPT_OBSERVATION, "History page header disappeared"))
                        .summary());
              }
            }
          }
          boolean more = events.size() > size;
          var visible = List.copyOf(more ? events.subList(0, size) : events);
          Optional<DiscoveryHistoryCursor> next =
              more
                  ? Optional.of(
                      new DiscoveryHistoryCursor(
                          filter.identity(), upper, visible.getLast().sequence(), size))
                  : Optional.empty();
          return new DiscoveryHistoryPage(filter, visible, next);
        });
  }

  private Optional<DiscoveryEvent> readEvent(Connection c, UUID id, CancellationToken token)
      throws SQLException {
    var found = header(c, id);
    if (found.isEmpty()) return Optional.empty();
    var header = found.get();
    var blobs = new HashMap<String, byte[]>();
    var versions = source(c, header.summary().versions(), blobs);
    var cdns = source(c, header.summary().cdns(), blobs);
    var event = DiscoveryHistoryRecords.event(header.record(), versions, cdns);
    for (var row : event.builds()) {
      token.throwIfCancellationRequested();
      catalog.requireSnapshotIdentity(c, row.build());
    }
    return Optional.of(mapper.replay(event));
  }

  private Optional<Header> header(Connection c, UUID id) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT"
                + " event_sequence,product_id,region,completed_at,parser_revision,build_count,length(record),record,record_sha256"
                + " FROM discovery_history_events WHERE event_id=?")) {
      q.setString(1, id.toString());
      try (var row = q.executeQuery()) {
        if (!row.next()) return Optional.empty();
        if (row.getObject(7) == null
            || row.getLong(7) < 1
            || row.getLong(7) > DiscoveryHistoryRecords.MAX_RECORD_BYTES)
          throw fail(CORRUPT_OBSERVATION, "Observation record is missing or oversized");
        byte[] record = row.getBytes(8);
        if (!digest(record).equals(row.getString(9)))
          throw fail(CORRUPT_OBSERVATION, "Observation record checksum differs");
        var summary = DiscoveryHistoryRecords.summary(record, row.getLong(1));
        if (!summary.eventId().equals(id)
            || !summary.productId().equals(row.getString(2))
            || !summary.region().equals(row.getString(3))
            || !summary.completedAt().toString().equals(row.getString(4))
            || !summary.parserRevision().equals(row.getString(5))
            || summary.builds().size() != row.getInt(6))
          throw fail(CORRUPT_OBSERVATION, "Observation header differs from immutable record");
        checkDocuments(c, summary);
        checkReferences(c, summary);
        return Optional.of(new Header(summary, record));
      }
    }
  }

  private void checkDocuments(Connection c, DiscoveryEventSummary summary) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT d.role,d.digest,d.source_url,d.captured_at,d.byte_length,length(b.bytes) FROM"
                + " discovery_history_documents d LEFT JOIN source_blobs b ON b.digest=d.digest"
                + " WHERE d.event_id=?")) {
      q.setString(1, summary.eventId().toString());
      var roles = new HashSet<String>();
      try (var rows = q.executeQuery()) {
        while (rows.next()) {
          String role = rows.getString(1);
          if (!roles.add(role))
            throw fail(CORRUPT_OBSERVATION, "Duplicate observation source role");
          var expected =
              switch (role) {
                case "versions" -> summary.versions();
                case "cdns" -> summary.cdns();
                default -> throw fail(CORRUPT_OBSERVATION, "Unknown observation source role");
              };
          if (!expected.sha256().equals(rows.getString(2))
              || !expected.sourceUrl().equals(rows.getString(3))
              || !expected.capturedAt().toString().equals(rows.getString(4))
              || expected.length() != rows.getLong(5)
              || rows.getObject(6) == null
              || expected.length() != rows.getLong(6))
            throw fail(CORRUPT_OBSERVATION, "Observation source receipt/reference differs");
        }
      }
      if (!roles.equals(Set.of("versions", "cdns")))
        throw fail(CORRUPT_OBSERVATION, "Observation source references are incomplete");
    }
  }

  private void checkReferences(Connection c, DiscoveryEventSummary summary) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT b.position,b.snapshot_id,b.versions_row_ordinal,b.channel,s.id FROM"
                + " discovery_history_builds b LEFT JOIN snapshots s ON s.id=b.snapshot_id WHERE"
                + " b.event_id=? ORDER BY b.position")) {
      q.setString(1, summary.eventId().toString());
      int position = 0;
      try (var rows = q.executeQuery()) {
        while (rows.next()) {
          if (position >= summary.builds().size())
            throw fail(CORRUPT_OBSERVATION, "Excess observation build references");
          var expected = summary.builds().get(position);
          if (rows.getInt(1) != position++
              || !expected.snapshotId().equals(rows.getString(2))
              || expected.versionsRowOrdinal() != rows.getInt(3)
              || !expected.channel().equals(rows.getString(4))
              || rows.getString(5) == null)
            throw fail(CORRUPT_OBSERVATION, "Observation build reference differs");
        }
      }
      if (position != summary.builds().size())
        throw fail(CORRUPT_OBSERVATION, "Observation build references are incomplete");
    }
  }

  private SourceDocument source(
      Connection c, DiscoveryEventSummary.DocumentReceipt receipt, Map<String, byte[]> blobs)
      throws SQLException {
    var bytes = blobs.get(receipt.sha256());
    if (bytes == null) {
      bytes =
          body(c, receipt.sha256())
              .orElseThrow(() -> fail(CORRUPT_OBSERVATION, "Observation source body is missing"));
      blobs.put(receipt.sha256(), bytes);
    }
    return new SourceDocument(receipt.sourceUrl(), receipt.capturedAt(), receipt.sha256(), bytes);
  }

  private Optional<byte[]> body(Connection c, String sha) throws SQLException {
    try (var q =
        c.prepareStatement("SELECT length(bytes),bytes FROM source_blobs WHERE digest=?")) {
      q.setString(1, sha);
      try (var r = q.executeQuery()) {
        if (!r.next()) return Optional.empty();
        if (r.getObject(1) == null || r.getLong(1) < 1 || r.getLong(1) > 1048576)
          throw fail(CORRUPT_OBSERVATION, "Shared metadata body has invalid length");
        byte[] bytes = r.getBytes(2);
        if (!digest(bytes).equals(sha))
          throw fail(CORRUPT_OBSERVATION, "Shared metadata body checksum differs");
        return Optional.of(bytes);
      }
    }
  }

  private static void document(Connection c, UUID id, String role, SourceDocument source)
      throws SQLException {
    try (var insert =
        c.prepareStatement("INSERT INTO discovery_history_documents VALUES(?,?,?,?,?,?)")) {
      insert.setString(1, id.toString());
      insert.setString(2, role);
      insert.setString(3, source.sha256());
      insert.setString(4, source.sourceUrl());
      insert.setString(5, source.capturedAt().toString());
      insert.setLong(6, source.bytes().length);
      insert.executeUpdate();
    }
  }

  private static long count(Connection c, String table) throws SQLException {
    return scalar(c, "SELECT count(*) FROM " + table);
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (var q = c.createStatement();
        var r = q.executeQuery(sql)) {
      if (!r.next() || r.getObject(1) == null || r.getLong(1) < 0)
        throw fail(CORRUPT_OBSERVATION, "Invalid metadata accounting");
      return r.getLong(1);
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private record Header(DiscoveryEventSummary summary, byte[] record) {}

  @FunctionalInterface
  private interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  private <T> T tx(CancellationToken token, Work<T> work) {
    token.throwIfCancellationRequested();
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      try {
        T result = work.run(c);
        token.throwIfCancellationRequested();
        c.commit();
        return result;
      } catch (SQLException | RuntimeException | Error error) {
        try {
          c.rollback();
        } catch (SQLException cleanup) {
          error.addSuppressed(cleanup);
        }
        throw error;
      }
    } catch (DiscoveryHistoryException | CancellationException error) {
      throw error;
    } catch (SQLException error) {
      throw new DiscoveryHistoryException(
          error.getErrorCode() == 5 ? BUSY : STORAGE_UNAVAILABLE,
          "Observation catalog operation failed",
          error);
    } catch (CatalogException error) {
      throw new DiscoveryHistoryException(
          error.code() == CatalogException.Code.STORAGE_UNAVAILABLE
              ? STORAGE_UNAVAILABLE
              : CORRUPT_OBSERVATION,
          "Observation snapshot metadata failed validation",
          error);
    } catch (DiscoveryHistoryRecords.LimitException error) {
      throw new DiscoveryHistoryException(
          LIMIT_EXCEEDED, "Observation metadata exceeds encoding budget", error);
    } catch (IllegalArgumentException | ArithmeticException error) {
      throw new DiscoveryHistoryException(
          CORRUPT_OBSERVATION, "Observation record failed validation", error);
    }
  }

  private static DiscoveryHistoryException fail(
      DiscoveryHistoryException.Code code, String message) {
    return new DiscoveryHistoryException(code, message);
  }
}
