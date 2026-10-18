package io.vibeminer.infrastructure.content;

import io.vibeminer.application.content.ContentClaimException;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.sql.*;

/** Same-transaction exclusions shared by every facade over the canonical header. */
public final class CanonicalContentGuards {
  private CanonicalContentGuards() {}

  public static void check(Connection connection, ContentSlotKey slot) throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT 1 FROM canonical_legacy_conflicts WHERE namespace=? AND content_key=? LIMIT"
                + " 1")) {
      query.setString(1, slot.namespace());
      query.setString(2, slot.contentKey());
      try (var rows = query.executeQuery()) {
        if (rows.next())
          throw new ContentClaimException(
              ContentClaimException.Code.LEGACY_CONFLICT,
              "Legacy alternate representations require explicit reconciliation");
      }
    }
    try (var query =
        connection.prepareStatement(
            "SELECT 1 FROM canonical_legacy_blocks WHERE product_id=? LIMIT 1")) {
      query.setString(1, slot.namespace());
      try (var rows = query.executeQuery()) {
        if (rows.next())
          throw new ContentClaimException(
              ContentClaimException.Code.LEGACY_CONFLICT,
              "Unreviewed historical metadata prevents safe canonical selection");
      }
    }
  }

  public static boolean hasActiveWorker(Connection connection, java.util.UUID id)
      throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT worker_nonce FROM canonical_content_slots WHERE token=?")) {
      query.setString(1, id.toString());
      try (var rows = query.executeQuery()) {
        return rows.next() && rows.getString(1) != null;
      }
    }
  }

  public static void requireWorker(
      Connection connection,
      java.util.UUID id,
      java.util.UUID session,
      io.vibeminer.domain.content.ContentWorkerPermit permit)
      throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT owner_session,worker_nonce FROM canonical_content_slots WHERE token=?")) {
      query.setString(1, id.toString());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) {
          if (permit == null) return; // Preserve the legacy facade's existing absent-token outcome.
          throw new ContentClaimException(
              ContentClaimException.Code.INVALID_STATE, "Worker claim no longer exists");
        }
        String nonce = rows.getString(2);
        if (permit == null) {
          if (nonce != null)
            throw new ContentClaimException(
                ContentClaimException.Code.BUSY, "Actual source worker owns this claim");
          return;
        }
        if (!id.equals(permit.reservationToken())
            || nonce == null
            || !nonce.equals(permit.workerNonce().toString()))
          throw new ContentClaimException(
              ContentClaimException.Code.INVALID_STATE,
              "Worker permit is stale or does not own this claim");
        if (!rows.getString(1).equals(session.toString()))
          throw new ContentClaimException(
              ContentClaimException.Code.RECOVERY_REQUIRED,
              "Worker belongs to another store session");
      }
    }
  }
}
