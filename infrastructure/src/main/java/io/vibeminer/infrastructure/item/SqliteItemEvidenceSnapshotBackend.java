package io.vibeminer.infrastructure.item;

import static io.vibeminer.application.itemexport.ItemEvidenceException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.CatalogException;
import io.vibeminer.application.item.ItemProjectionException;
import io.vibeminer.application.itemexport.*;
import io.vibeminer.application.itemnames.ItemNamesException;
import io.vibeminer.application.selectedfile.SelectedFileException;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.itemnames.SparseNameProjectionSourceReader;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Copies one retained physical row and original evidence; owns no stores, readers or leases. */
public final class SqliteItemEvidenceSnapshotBackend implements ItemEvidenceSnapshotBackend {
  private final SqliteItemProjectionRepository items;
  private final SqliteSparseNameRepository names;
  private final SqliteItemNamesBackend joined;
  private final FileObjectStore.StoreSession session;
  private final ItemProjectionSourceReader itemReader;
  private final SparseNameProjectionSourceReader nameReader;
  private final Clock clock;

  public SqliteItemEvidenceSnapshotBackend(
      SqliteCatalog catalog,
      FileObjectStore.StoreSession session,
      ItemProjectionSourceReader itemReader,
      SparseNameProjectionSourceReader nameReader,
      Clock clock) {
    this.session = Objects.requireNonNull(session);
    this.itemReader = Objects.requireNonNull(itemReader);
    this.nameReader = Objects.requireNonNull(nameReader);
    this.clock = Objects.requireNonNull(clock);
    items = new SqliteItemProjectionRepository(catalog, session);
    names = new SqliteSparseNameRepository(catalog, session);
    joined = new SqliteItemNamesBackend(catalog, session, itemReader, nameReader);
  }

  @Override
  public ItemEvidenceSnapshot capture(ItemEvidenceSelection selection, CancellationToken token) {
    if (selection == null || token == null)
      throw failure(INVALID_REQUEST, "Explicit retained selection and cancellation required");
    token.throwIfCancellationRequested();
    try {
      session.beforeProducerOperation();
      Instant started = clock.instant();
      if (selection.names().isEmpty()) {
        try (var lease = itemReader.verify(selection.item(), token)) {
          return items.queryCancellable(
              selection.item(), token, c -> captureItem(c, selection, started, token));
        }
      }
      var pair = selection.names().orElseThrow();
      var trusted =
          items.cancellableTransaction(
              token, c -> names.query(c, pair.names(), ignored -> pair.names()));
      try (var itemLease = itemReader.verify(selection.item(), token);
          var nameLease = nameReader.verify(trusted, token)) {
        return items.queryCancellable(
            selection.item(),
            token,
            c ->
                names.query(
                    c, trusted, ignored -> capturePair(c, selection, pair, started, token)));
      }
    } catch (RuntimeException error) {
      throw classify(error);
    }
  }

  private ItemEvidenceSnapshot captureItem(
      Connection c, ItemEvidenceSelection selection, Instant started, CancellationToken token)
      throws SQLException {
    var row =
        items
            .exact(c, selection.item(), selection.recordId())
            .orElseThrow(
                () -> failure(STALE_SELECTION, "Selected Item is not a current physical row"));
    return snapshot(c, selection, row, Optional.empty(), started, token);
  }

  private ItemEvidenceSnapshot capturePair(
      Connection c,
      ItemEvidenceSelection selection,
      PreparedItemNames pair,
      Instant started,
      CancellationToken token)
      throws SQLException {
    var row =
        joined
            .exactJoined(c, pair, selection.recordId())
            .orElseThrow(
                () -> failure(STALE_SELECTION, "Selected Item is not a current physical row"));
    return snapshot(c, selection, row.item(), Optional.of(row.name()), started, token);
  }

  private ItemEvidenceSnapshot snapshot(
      Connection c,
      ItemEvidenceSelection selection,
      ItemPhysicalRow row,
      Optional<ItemNameMatch> name,
      Instant started,
      CancellationToken token)
      throws SQLException {
    var generations = new ArrayList<String>();
    generations.add("item-evidence-snapshot-generations-v1");
    generation(c, "item_projection_artifacts", selection.item().artifactId(), generations);
    if (selection.names().isPresent())
      generation(
          c,
          "sparse_name_artifacts",
          selection.names().orElseThrow().names().artifactId(),
          generations);
    String fingerprint = fingerprint(generations);
    token.throwIfCancellationRequested();
    Instant completed = clock.instant();
    if (completed.isBefore(started))
      throw failure(STORAGE_UNAVAILABLE, "Verification clock moved backwards; capture again");
    return new ItemEvidenceSnapshot(selection, row, name, started, completed, fingerprint);
  }

  private static void generation(Connection c, String table, String artifact, List<String> facts)
      throws SQLException {
    // Table names are fixed call-site constants; caller values are always bound parameters.
    try (var q =
        c.prepareStatement(
            "SELECT generation FROM " + table + " WHERE artifact_id=? AND status='READY'")) {
      q.setString(1, artifact);
      try (var rows = q.executeQuery()) {
        if (!rows.next())
          throw failure(STALE_SELECTION, "Prepared artifact generation is no longer ready");
        facts.add(artifact);
        facts.add(UUID.fromString(rows.getString(1)).toString());
      }
    }
  }

  private static String fingerprint(List<String> facts) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        for (var fact : facts) {
          var utf8 = fact.getBytes(StandardCharsets.UTF_8);
          out.writeInt(utf8.length);
          out.write(utf8);
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static RuntimeException classify(RuntimeException error) {
    if (error instanceof ItemEvidenceException || error instanceof CancellationException)
      return error;
    if (error instanceof ItemProjectionException item)
      return failure(queryCode(item.code()), item.getMessage(), item);
    if (error instanceof ItemNamesException names)
      return failure(queryCode(names.code()), names.getMessage(), names);
    if (error instanceof ObjectStoreException store)
      return failure(
          switch (store.code()) {
            case MISSING_OBJECT -> MISSING_LOCAL;
            case INTEGRITY_FAILURE -> CORRUPT;
            case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
            case BUSY, STORE_IN_USE -> BUSY;
            case CLOSED -> CLOSED;
            case IO_FAILURE -> STORAGE_UNAVAILABLE;
          },
          "Retained source verification failed",
          store);
    if (error instanceof CatalogException catalog)
      return failure(
          switch (catalog.code()) {
            case CORRUPT_METADATA, INTEGRITY_FAILURE, INVALID_EVIDENCE, IMMUTABLE_CONFLICT ->
                CORRUPT;
            case MISSING_OBJECT, UNKNOWN_SNAPSHOT -> MISSING_LOCAL;
            case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
          },
          catalog.getMessage(),
          catalog);
    if (error instanceof SelectedFileException selected)
      return failure(
          switch (selected.code()) {
            case MISSING_LOCAL, NOT_FOUND -> MISSING_LOCAL;
            case INTEGRITY_FAILURE, INVALID_PLAN -> CORRUPT;
            case INVALID_REQUEST -> INVALID_REQUEST;
            case CLOSED -> CLOSED;
            case REPLAN_REQUIRED, RECOVERY_REQUIRED, INCOMPLETE_SEARCH -> STALE_SELECTION;
            case UNSUPPORTED -> UNSUPPORTED;
            case BUDGET_EXCEEDED -> LIMIT_EXCEEDED;
            case BUSY, CAPACITY_EXCEEDED -> BUSY;
            case CANCELLED ->
                throw new CancellationException("Retained source verification cancelled");
            default -> STORAGE_UNAVAILABLE;
          },
          selected.getMessage(),
          selected);
    if (error instanceof IllegalArgumentException || error instanceof ArithmeticException)
      return failure(CORRUPT, "Retained evidence is malformed", error);
    return failure(STORAGE_UNAVAILABLE, "Evidence snapshot could not be captured", error);
  }

  private static ItemEvidenceException.Code queryCode(ItemProjectionException.Code code) {
    return switch (code) {
      case INVALID_REQUEST -> INVALID_REQUEST;
      case NOT_READY, STALE_CURSOR -> STALE_SELECTION;
      case MISSING_LOCAL -> MISSING_LOCAL;
      case CORRUPT -> CORRUPT;
      case INCOMPATIBLE, UNSUPPORTED -> UNSUPPORTED;
      case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
      case BUSY -> BUSY;
      case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
      case CLOSED -> CLOSED;
    };
  }

  private static ItemEvidenceException.Code queryCode(ItemNamesException.Code code) {
    return switch (code) {
      case INVALID_REQUEST -> INVALID_REQUEST;
      case NOT_READY, STALE_CURSOR -> STALE_SELECTION;
      case MISSING_LOCAL -> MISSING_LOCAL;
      case CORRUPT -> CORRUPT;
      case INCOMPATIBLE, UNSUPPORTED -> UNSUPPORTED;
      case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
      case BUSY -> BUSY;
      case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
      case CLOSED -> CLOSED;
    };
  }

  private static ItemEvidenceException failure(ItemEvidenceException.Code code, String message) {
    return new ItemEvidenceException(code, message);
  }

  private static ItemEvidenceException failure(
      ItemEvidenceException.Code code, String message, Throwable cause) {
    return new ItemEvidenceException(code, message, cause);
  }
}
