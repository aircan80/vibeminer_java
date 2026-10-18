package io.vibeminer.infrastructure.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ItemNamesSqlCancellationTest {
  private static final String LARGE =
      "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<100000) SELECT sum(x)"
          + " FROM n";

  @Test
  void interruptsActualSqlAndClearsHandlerBeforeConnectionReuse() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
      var checks = new AtomicInteger();
      CancellationToken token = () -> checks.incrementAndGet() >= 3;
      var failure =
          assertThrows(
              CancellationException.class,
              () ->
                  SqliteItemProjectionRepository.withProgress(
                      connection,
                      token,
                      c -> {
                        try (var statement = c.createStatement();
                            var result = statement.executeQuery(LARGE)) {
                          return result.getLong(1);
                        }
                      }));
      assertInstanceOf(SQLException.class, failure.getCause());
      int before = checks.get();
      try (var statement = connection.createStatement();
          var result = statement.executeQuery(LARGE)) {
        assertEquals(5000050000L, result.getLong(1));
      }
      assertEquals(before, checks.get(), "handler must not survive cancellation");
    }
  }

  @Test
  void clearsHandlerAfterSuccessAndDoesNotRelabelUnrelatedSqlFailure() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
      var checks = new AtomicInteger();
      CancellationToken token =
          () -> {
            checks.incrementAndGet();
            return false;
          };
      int value = SqliteItemProjectionRepository.withProgress(connection, token, c -> 7);
      assertEquals(7, value);
      var error =
          assertThrows(
              SQLException.class,
              () ->
                  SqliteItemProjectionRepository.withProgress(
                      connection,
                      token,
                      c -> {
                        try (var statement = c.createStatement()) {
                          statement.executeQuery("SELECT missing_column");
                        }
                        return null;
                      }));
      assertNotEquals(9, error.getErrorCode());
      int before = checks.get();
      try (var statement = connection.createStatement();
          var result = statement.executeQuery(LARGE)) {
        assertEquals(5000050000L, result.getLong(1));
      }
      assertEquals(before, checks.get());
    }
  }
}
