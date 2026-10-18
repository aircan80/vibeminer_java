package io.vibeminer.infrastructure.buildretention;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.catalog.CatalogException;
import java.nio.file.*;
import java.sql.*;
import java.util.Objects;
import org.sqlite.SQLiteConfig;

/**
 * Read-only startup hint from the exact app catalog. A positive result authorizes no source work:
 * the content owner must independently replay stored intents, scope and policy after store
 * recovery. This reader never creates, migrates or repairs a catalog.
 */
public final class SqliteDownloadAllPresence {
  private final Path database;

  public SqliteDownloadAllPresence(Path database) {
    this.database = Objects.requireNonNull(database).toAbsolutePath().normalize();
  }

  public boolean hasDownloadAll(CancellationToken cancellation) {
    Objects.requireNonNull(cancellation).throwIfCancellationRequested();
    try {
      if (!Files.readAttributes(database, java.nio.file.attribute.BasicFileAttributes.class)
          .isRegularFile())
        throw new CatalogException(
            CatalogException.Code.STORAGE_UNAVAILABLE, "App catalog is not a regular file");
    } catch (NoSuchFileException absent) {
      return false;
    } catch (java.io.IOException failure) {
      throw new CatalogException(
          CatalogException.Code.STORAGE_UNAVAILABLE, "Cannot inspect app catalog path", failure);
    }
    var config = new SQLiteConfig();
    config.setReadOnly(true);
    try (var c = config.createConnection("jdbc:sqlite:" + database);
        var q = c.createStatement()) {
      q.execute("PRAGMA query_only=ON");
      q.execute("PRAGMA busy_timeout=5000");
      cancellation.throwIfCancellationRequested();
      try (var tables =
          q.executeQuery(
              "SELECT 1 FROM sqlite_master WHERE type='table' AND"
                  + " name='build_retention_intents'")) {
        if (!tables.next()) return false;
      }
      cancellation.throwIfCancellationRequested();
      try (var result =
          q.executeQuery(
              "SELECT EXISTS(SELECT 1 FROM build_retention_intents INDEXED BY"
                  + " build_retention_active_group WHERE mode='DOWNLOAD_ALL' LIMIT 1)")) {
        if (!result.next())
          throw new CatalogException(
              CatalogException.Code.CORRUPT_METADATA, "Stored retention presence is unreadable");
        boolean present = result.getInt(1) != 0;
        cancellation.throwIfCancellationRequested();
        return present;
      }
    } catch (SQLException failure) {
      int code = failure.getErrorCode();
      throw new CatalogException(
          code == 1 || code == 11 || code == 26
              ? CatalogException.Code.CORRUPT_METADATA
              : CatalogException.Code.STORAGE_UNAVAILABLE,
          "Cannot inspect stored retention startup hint",
          failure);
    }
  }
}
