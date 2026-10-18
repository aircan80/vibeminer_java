package io.vibeminer.infrastructure.content;

import io.vibeminer.application.content.ContentClaimException;
import io.vibeminer.infrastructure.buildretention.SqliteBuildRetentionRepository;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.sql.*;
import java.util.Objects;

/**
 * One unused exclusive-store capability and one transaction for concrete content families.
 * Migration must already install all families. No HTTP, payload decoding, readiness upgrade or
 * uncertain transfer-budget refund occurs here.
 */
public final class SqliteContentStartupRecovery {
  private SqliteContentStartupRecovery() {}

  public static void recoverAtStartup(SqliteCatalog catalog, FileObjectStore.StoreSession session) {
    Objects.requireNonNull(catalog);
    Objects.requireNonNull(session);
    session.recoverAtStartup(
        () -> {
          try (var c = catalog.connect()) {
            c.setAutoCommit(false);
            try {
              session.requireRecoveryInProgress();
              SqliteCanonicalContentRepository.recoverInStartupTransaction(c, session);
              try (var s = c.createStatement()) {
                s.executeUpdate("DELETE FROM en_lookup_artifacts WHERE status='STAGING'");
                s.executeUpdate("DELETE FROM build_inventory_generations WHERE status='STAGING'");
              }
              SqliteBuildRetentionRepository.recoverInStartupTransaction(c, session);
              c.commit();
            } catch (SQLException | RuntimeException | Error failed) {
              try {
                c.rollback();
              } catch (SQLException rollback) {
                failed.addSuppressed(rollback);
              }
              throw failed;
            }
          } catch (SQLException failed) {
            throw new ContentClaimException(
                ContentClaimException.Code.STORAGE_UNAVAILABLE,
                "Cannot atomically recover content startup",
                failed);
          }
        });
  }
}
