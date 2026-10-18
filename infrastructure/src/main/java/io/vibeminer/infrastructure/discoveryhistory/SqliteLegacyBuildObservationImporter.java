package io.vibeminer.infrastructure.discoveryhistory;

import static io.vibeminer.application.buildlibrary.LegacyBuildImportException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.catalog.CatalogException;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.SourceDocument;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Explicit, bounded migration of original app-owned publication receipts; no network or schema. */
public final class SqliteLegacyBuildObservationImporter implements LegacyBuildObservationImporter {
  private final SqliteCatalog catalog;
  private final SqliteDiscoveryHistoryRepository history;
  private final SqliteBuildLibraryRepository library;
  private final DiscoveryObservationMapper mapper = new DiscoveryObservationMapper();

  public SqliteLegacyBuildObservationImporter(SqliteCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog);
    history = new SqliteDiscoveryHistoryRepository(catalog);
    library = new SqliteBuildLibraryRepository(catalog);
  }

  @Override
  public Batch importNext(
      String productId,
      Optional<Cursor> cursor,
      Limits limits,
      Instant recordedAt,
      CancellationToken token) {
    Objects.requireNonNull(cursor);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(recordedAt);
    Objects.requireNonNull(token);
    new BuildLibraryQuery(productId, BuildLibraryProjection.Filter.ALL);
    var progress = new Progress(productId, cursor.filter(c -> c.productId().equals(productId)));
    var handled = new HashSet<UUID>();
    long documentBytes = 0;
    try {
      if (cursor.isPresent() && !cursor.get().productId().equals(productId))
        throw new LegacyFailure(INVALID_REQUEST, "Legacy cursor belongs to another product");
      token.throwIfCancellationRequested();
      var ids = snapshotIds(productId, cursor, limits.maximumSnapshotHeaders());
      for (int index = 0; index < ids.size(); index++) {
        token.throwIfCancellationRequested();
        String id = ids.get(index);
        if (index == limits.maximumSnapshotHeaders()) return progress.batch(false);
        Header header;
        try (var c = catalog.connect()) {
          header = header(c, id);
        }
        if (!header.fixture()) {
          UUID eventId = eventId(productId, header);
          if (!handled.contains(eventId)) {
            if (handled.size() == limits.maximumPairs()) return progress.batch(false);
            Optional<BuildLibraryRegistration> prior;
            try (var c = catalog.connect()) {
              prior = library.registrationMetadata(c, eventId);
              if (prior.isPresent()) validateCompleted(c, header, eventId, prior.get());
            }
            if (prior.isPresent()) {
              progress.skipped++;
            } else {
              long pairBytes = Math.addExact(header.versions().length(), header.cdns().length());
              if (pairBytes > limits.maximumDocumentBytes() - documentBytes)
                return progress.batch(false);
              documentBytes += pairBytes;
              DiscoveryEvent event;
              try (var c = catalog.connect()) {
                var versions = source(c, header.versions());
                var cdns = source(c, header.cdns());
                event =
                    mapper.map(
                        eventId, productId, header.region(), header.observedAt(), versions, cdns);
                validateContributors(c, header, event, token);
              }
              token.throwIfCancellationRequested();
              history.append(event, DiscoveryHistoryLimits.defaults(), token);
              library.recordObservation(
                  eventId,
                  BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
                  Optional.empty(),
                  recordedAt,
                  token);
              // Registration is committed. A late cancellation must report its completed progress.
              progress.imported++;
            }
            handled.add(eventId);
          } else {
            try (var c = catalog.connect()) {
              validateCompleted(
                  c,
                  header,
                  eventId,
                  library
                      .registrationMetadata(c, eventId)
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "Completed import registration disappeared")));
            }
          }
        }
        progress.scanned++;
        progress.cursor = Optional.of(new Cursor(productId, id));
      }
      token.throwIfCancellationRequested();
      return progress.batch(true);
    } catch (CancellationException cancelled) {
      throw new LegacyBuildImportCancelled(progress.batch(false));
    } catch (LegacyFailure incompatible) {
      throw new LegacyBuildImportException(
          incompatible.code, incompatible.getMessage(), progress.batch(false), incompatible);
    } catch (DiscoveryHistoryException failure) {
      var code =
          switch (failure.code()) {
            case LIMIT_EXCEEDED, QUOTA_EXCEEDED -> LIMIT_EXCEEDED;
            case UNSUPPORTED_INTERPRETATION -> INCOMPATIBLE_LEGACY;
            case STORAGE_UNAVAILABLE, BUSY, CLOSED, OBSERVATION_NOT_SAVED -> STORAGE_UNAVAILABLE;
            default -> CORRUPT_LEGACY;
          };
      throw new LegacyBuildImportException(
          code, "Legacy publication import stopped", progress.batch(false), failure);
    } catch (BuildLibraryException failure) {
      var code =
          switch (failure.code()) {
            case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
            case STORAGE_UNAVAILABLE, BUSY, CLOSED -> STORAGE_UNAVAILABLE;
            default -> CORRUPT_LEGACY;
          };
      throw new LegacyBuildImportException(
          code, "Legacy library registration stopped", progress.batch(false), failure);
    } catch (CatalogException failure) {
      throw new LegacyBuildImportException(
          failure.code() == CatalogException.Code.STORAGE_UNAVAILABLE
              ? STORAGE_UNAVAILABLE
              : CORRUPT_LEGACY,
          "Legacy catalog could not be read",
          progress.batch(false),
          failure);
    } catch (SQLException failure) {
      throw new LegacyBuildImportException(
          STORAGE_UNAVAILABLE, "Legacy catalog operation failed", progress.batch(false), failure);
    } catch (IllegalArgumentException
        | ArithmeticException
        | java.time.DateTimeException
        | NoSuchElementException failure) {
      throw new LegacyBuildImportException(
          CORRUPT_LEGACY,
          "Legacy publication facts are inconsistent",
          progress.batch(false),
          failure);
    }
  }

  private List<String> snapshotIds(String product, Optional<Cursor> cursor, int maximum)
      throws SQLException {
    try (var c = catalog.connect();
        var q =
            c.prepareStatement(
                "SELECT id FROM snapshots WHERE product_id=? AND id>? ORDER BY id LIMIT ?")) {
      q.setString(1, product);
      q.setString(2, cursor.map(Cursor::afterSnapshotId).orElse(""));
      q.setInt(3, maximum + 1);
      try (var r = q.executeQuery()) {
        var ids = new ArrayList<String>();
        while (r.next()) ids.add(r.getString(1));
        return ids;
      }
    }
  }

  private Header header(Connection c, String id) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT product_id,region,observed_at,fixture,source_count FROM snapshots WHERE"
                + " id=?")) {
      q.setString(1, id);
      try (var r = q.executeQuery()) {
        if (!r.next()) throw new IllegalArgumentException("Legacy snapshot disappeared");
        if (r.getInt(4) != 0)
          return new Header(
              id, r.getString(1), r.getString(2), Instant.parse(r.getString(3)), true, null, null);
        if (r.getInt(5) != 2)
          throw incompatible("Legacy snapshot lacks exactly two publication receipts: " + id);
        var docs = new ArrayList<Receipt>();
        try (var sources =
            c.prepareStatement(
                "SELECT s.position,s.url,s.captured_at,s.digest,length(b.bytes) FROM"
                    + " snapshot_sources s LEFT JOIN source_blobs b ON b.digest=s.digest WHERE"
                    + " s.snapshot_id=? ORDER BY s.position")) {
          sources.setString(1, id);
          try (var d = sources.executeQuery()) {
            while (d.next()) {
              if (d.getInt(1) != docs.size()
                  || d.getObject(5) == null
                  || d.getLong(5) < 1
                  || d.getLong(5) > 1_048_576)
                throw new IllegalArgumentException(
                    "Legacy source membership is missing or unbounded");
              docs.add(
                  new Receipt(
                      d.getString(2), Instant.parse(d.getString(3)), d.getString(4), d.getLong(5)));
            }
          }
        }
        if (docs.size() != 2)
          throw new IllegalArgumentException("Incomplete legacy source membership");
        String base = "https://us.version.battle.net/v2/products/" + r.getString(1) + "/";
        if (!docs.get(0).url().equals(base + "versions")
            || !docs.get(1).url().equals(base + "cdns"))
          throw incompatible("Legacy receipt roles/routes are unsupported: " + id);
        return new Header(
            id,
            r.getString(1),
            r.getString(2),
            Instant.parse(r.getString(3)),
            false,
            docs.get(0),
            docs.get(1));
      }
    }
  }

  private void validateContributors(
      Connection c, Header header, DiscoveryEvent event, CancellationToken token)
      throws SQLException {
    // At most 64 distinct canonical snapshots can be justified by this bounded event.
    try (var q =
        c.prepareStatement(
            "SELECT s.id FROM snapshots s JOIN snapshot_sources v ON v.snapshot_id=s.id AND"
                + " v.position=0 JOIN snapshot_sources d ON d.snapshot_id=s.id AND d.position=1"
                + " WHERE s.product_id=? AND s.region=? AND v.digest=? AND v.url=? AND"
                + " v.captured_at=? AND d.digest=? AND d.url=? AND d.captured_at=? ORDER BY s.id"
                + " LIMIT 65")) {
      int n = 1;
      q.setString(n++, header.product());
      q.setString(n++, header.region());
      for (var receipt : List.of(header.versions(), header.cdns())) {
        q.setString(n++, receipt.sha());
        q.setString(n++, receipt.url());
        q.setString(n++, receipt.capturedAt().toString());
      }
      try (var rows = q.executeQuery()) {
        int count = 0;
        while (rows.next()) {
          token.throwIfCancellationRequested();
          if (++count > 64)
            throw new LegacyFailure(
                LIMIT_EXCEEDED, "Legacy pair has more than 64 canonical contributors");
          var contributor = header(c, rows.getString(1));
          requireSnapshot(event, contributor);
          var interpreted =
              event.builds().stream()
                  .filter(b -> b.build().snapshotId().equals(contributor.id()))
                  .findFirst()
                  .orElseThrow();
          catalog.requireSnapshotIdentity(c, interpreted.build());
        }
        if (count == 0)
          throw new IllegalArgumentException("Legacy pair has no canonical contributor");
      }
    }
  }

  private void validateCompleted(
      Connection c, Header header, UUID id, BuildLibraryRegistration registration)
      throws SQLException {
    if (registration.kind() != BuildLibraryRegistration.Kind.HISTORICAL_IMPORT)
      throw new IllegalArgumentException("Stable import ID is registered under another kind");
    var summary = history.header(c, id).orElseThrow().summary();
    if (!summary.productId().equals(header.product())
        || !summary.region().equals(header.region())
        || !summary.completedAt().equals(header.observedAt())
        || !receiptMatches(header.versions(), summary.versions())
        || !receiptMatches(header.cdns(), summary.cdns())
        || summary.builds().stream().noneMatch(b -> b.snapshotId().equals(header.id())))
      throw incompatible(
          "Completed import does not reproduce original legacy snapshot: " + header.id());
  }

  private static void requireSnapshot(DiscoveryEvent event, Header header) {
    if (!event.completedAt().equals(header.observedAt())
        || event.builds().stream().noneMatch(b -> b.build().snapshotId().equals(header.id())))
      throw incompatible(
          "Legacy publication pair has inconsistent snapshots or observation times: "
              + header.id());
  }

  private static boolean receiptMatches(Receipt r, DiscoveryEventSummary.DocumentReceipt d) {
    return r.url().equals(d.sourceUrl())
        && r.capturedAt().equals(d.capturedAt())
        && r.sha().equals(d.sha256())
        && r.length() == d.length();
  }

  private static SourceDocument source(Connection c, Receipt receipt) throws SQLException {
    try (var q =
        c.prepareStatement("SELECT length(bytes),bytes FROM source_blobs WHERE digest=?")) {
      q.setString(1, receipt.sha());
      try (var r = q.executeQuery()) {
        if (!r.next() || r.getLong(1) != receipt.length())
          throw new IllegalArgumentException("Legacy body length differs");
        return new SourceDocument(
            receipt.url(), receipt.capturedAt(), receipt.sha(), r.getBytes(2));
      }
    }
  }

  private static UUID eventId(String product, Header header) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      for (var value : List.of("vibeminer-legacy-publication-import-v1", product, header.region()))
        text(out, value);
      for (var r : List.of(header.versions(), header.cdns())) {
        text(out, r.url());
        text(out, r.capturedAt().toString());
        text(out, r.sha());
        out.writeLong(r.length());
      }
      var digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
      var b = java.nio.ByteBuffer.wrap(digest);
      return new UUID(b.getLong(), b.getLong());
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    var b = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(b.length);
    out.write(b);
  }

  private record Receipt(String url, Instant capturedAt, String sha, long length) {}

  private record Header(
      String id,
      String product,
      String region,
      Instant observedAt,
      boolean fixture,
      Receipt versions,
      Receipt cdns) {}

  private static LegacyFailure incompatible(String message) {
    return new LegacyFailure(INCOMPATIBLE_LEGACY, message);
  }

  private static final class LegacyFailure extends RuntimeException {
    final LegacyBuildImportException.Code code;

    LegacyFailure(LegacyBuildImportException.Code code, String message) {
      super(message);
      this.code = code;
    }
  }

  private static final class Progress {
    final String product;
    Optional<Cursor> cursor;
    int scanned, imported, skipped;

    Progress(String product, Optional<Cursor> cursor) {
      this.product = product;
      this.cursor = cursor;
    }

    Batch batch(boolean complete) {
      return new Batch(
          product, scanned, imported, skipped, complete ? Optional.empty() : cursor, complete);
    }
  }
}
