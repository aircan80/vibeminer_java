package io.vibeminer.infrastructure.item;

import static io.vibeminer.application.itemnames.ItemNamesException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.CatalogException;
import io.vibeminer.application.item.*;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.application.selectedfile.SelectedFileException;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.itemnames.SparseNameProjectionSourceReader;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Retained-only, explicit source-pair projection and fixed Item-ID joins. Owns no resources. */
public final class SqliteItemNamesBackend implements ItemNamesBackend {
  private final SqliteItemProjectionRepository items;
  private final SqliteSparseNameRepository names;
  private final ItemProjectionSourceReader itemReader;
  private final SparseNameProjectionSourceReader nameReader;
  private final FileObjectStore.StoreSession session;

  public SqliteItemNamesBackend(
      SqliteCatalog catalog,
      FileObjectStore.StoreSession session,
      ItemProjectionSourceReader itemReader,
      SparseNameProjectionSourceReader nameReader) {
    this.session = Objects.requireNonNull(session);
    items = new SqliteItemProjectionRepository(catalog, session);
    names = new SqliteSparseNameRepository(catalog, session);
    this.itemReader = Objects.requireNonNull(itemReader);
    this.nameReader = Objects.requireNonNull(nameReader);
  }

  @Override
  public PreparedItemNames prepare(ItemNamesRequest request, CancellationToken token) {
    validateRequest(request);
    Objects.requireNonNull(token);
    token.throwIfCancellationRequested();
    try {
      session.beforeProducerOperation();
      try (var itemLease = itemReader.verify(request.item(), token);
          var source = nameReader.open(request, token)) {
        itemQuery(request.item(), token, c -> null);
        var definition = source.definition();
        requireDefinition(request, definition);
        var copies = List.copyOf(source.copies());
        if (copies.size() > request.limits().maxCopyDeclarations())
          throw fail(LIMIT_EXCEEDED, "Sparse copy declarations exceed requested bound");
        var targets = new HashSet<Long>();
        for (var copy : copies)
          if (!targets.add(copy.targetId())) throw fail(CORRUPT, "Duplicate Sparse copy target");
        var header = names.claim(definition, token);
        var stage =
            header.ready()
                ? null
                : new SqliteSparseNameRepository.Stage(
                    definition.artifactId(), header.generation());
        boolean published = false;
        Throwable failed = null;
        try {
          int batch = Math.min(128, request.limits().maxStagingRows());
          if (stage != null)
            for (int start = 0; start < copies.size(); start += batch)
              names.copies(
                  stage, copies.subList(start, Math.min(copies.size(), start + batch)), token);
          var sink = new Sink(stage, definition, request.limits(), batch, targets, token);
          var completed = source.project(sink, token);
          token.throwIfCancellationRequested();
          sink.flush();
          requireCompleted(request, definition, completed, sink, copies.size());
          var retained =
              itemQuery(
                  request.item(),
                  token,
                  c -> {
                    if (stage == null) {
                      if (!header.coverage().equals(completed.coverage()))
                        throw fail(
                            CORRUPT, "Completed source coverage changed shared names artifact");
                      return names.reuse(c, header, completed.evidence(), copies);
                    }
                    return names.publish(
                        c,
                        stage,
                        completed.definition(),
                        completed.evidence(),
                        completed.coverage());
                  });
          published = true;
          // Preserve the complete originally selected Item proof; names preparation never restamps
          // it.
          return new PreparedItemNames(request.item(), retained);
        } catch (RuntimeException | Error failure) {
          failed = failure;
          throw failure;
        } finally {
          if (!published && stage != null)
            try {
              names.discard(stage);
            } catch (RuntimeException cleanup) {
              if (failed != null) failed.addSuppressed(cleanup);
              else throw cleanup;
            }
        }
      }
    } catch (RuntimeException failure) {
      throw classify(failure);
    }
  }

  @Override
  public ItemNamesPage page(
      PreparedItemNames pair, Optional<ItemNamesCursor> cursor, int size, CancellationToken token) {
    if (pair == null || cursor == null || size < 1 || size > 500)
      throw fail(INVALID_REQUEST, "Explicit pair and page size1..500 required");
    try {
      cursor.ifPresent(value -> value.requireContext(pair, size));
    } catch (IllegalArgumentException stale) {
      throw new ItemNamesException(STALE_CURSOR, "Cursor belongs to another source pair", stale);
    }
    Objects.requireNonNull(token);
    token.throwIfCancellationRequested();
    try {
      session.beforeProducerOperation();
      var trusted = names.loadTrusted(pair.names(), token);
      try (var itemLease = itemReader.verify(pair.item(), token);
          var nameLease = nameReader.verify(trusted, token)) {
        var rows =
            itemQuery(
                pair.item(),
                token,
                c ->
                    names.query(
                        c,
                        trusted,
                        connection ->
                            joined(
                                connection,
                                pair,
                                cursor.map(ItemNamesCursor::afterRecordId).orElse(0L),
                                size + 1,
                                false)));
        boolean more = rows.size() > size;
        var visible = List.copyOf(more ? rows.subList(0, size) : rows);
        Optional<ItemNamesCursor> next =
            more
                ? Optional.of(
                    new ItemNamesCursor(
                        pair.item().artifactId(),
                        pair.names().artifactId(),
                        pair.snapshotId(),
                        pair.item().sourceRole(),
                        pair.names().sourceRole(),
                        pair.locale(),
                        ItemNamesCursor.QUERY_REVISION,
                        ItemNamesCursor.OPTIONS_REVISION,
                        visible.getLast().item().recordId(),
                        size))
                : Optional.empty();
        return new ItemNamesPage(pair, visible, next);
      }
    } catch (RuntimeException failure) {
      throw classify(failure);
    }
  }

  @Override
  public ItemNamesLookup lookup(PreparedItemNames pair, long recordId, CancellationToken token) {
    if (pair == null || recordId < 1 || recordId > 0xffffffffL)
      throw fail(INVALID_REQUEST, "Positive unsigned Item record ID required");
    Objects.requireNonNull(token);
    token.throwIfCancellationRequested();
    try {
      session.beforeProducerOperation();
      var trusted = names.loadTrusted(pair.names(), token);
      try (var itemLease = itemReader.verify(pair.item(), token);
          var nameLease = nameReader.verify(trusted, token)) {
        return itemQuery(
            pair.item(),
            token,
            c ->
                names.query(
                    c,
                    trusted,
                    connection -> {
                      var rows = joined(connection, pair, recordId, 1, true);
                      if (!rows.isEmpty()) {
                        var row = rows.getFirst();
                        var item =
                            new ItemLookupResult(
                                recordId,
                                ItemLookupResult.Outcome.PHYSICAL_MATCH,
                                Optional.of(row.item()),
                                OptionalLong.empty(),
                                pair.item().coverage());
                        return new ItemNamesLookup(
                            pair, new JoinedItemLookup(item, Optional.of(row.name())));
                      }
                      var copy = items.copy(connection, pair.item(), recordId);
                      var item =
                          new ItemLookupResult(
                              recordId,
                              copy.isPresent()
                                  ? ItemLookupResult.Outcome.COPY_UNINDEXED
                                  : ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
                              Optional.empty(),
                              copy,
                              pair.item().coverage());
                      return new ItemNamesLookup(
                          pair, new JoinedItemLookup(item, Optional.empty()));
                    }));
      }
    } catch (RuntimeException failure) {
      throw classify(failure);
    }
  }

  @Override
  public ItemNamesSearchPage search(
      PreparedItemNames pair,
      ItemNameLiteralQuery query,
      Optional<ItemNamesSearchCursor> cursor,
      int size,
      CancellationToken token) {
    if (pair == null || query == null || cursor == null || size < 1 || size > 500)
      throw fail(INVALID_REQUEST, "Explicit pair, literal query and size1..500 required");
    try {
      cursor.ifPresent(value -> value.requireContext(pair, query, size));
    } catch (IllegalArgumentException stale) {
      throw new ItemNamesException(
          STALE_CURSOR, "Search cursor belongs to another query or pair", stale);
    }
    Objects.requireNonNull(token);
    token.throwIfCancellationRequested();
    requireSearchBounds(pair);
    try {
      session.beforeProducerOperation();
      // Authenticate the complete stored manifest before the historical source verifier borrows it.
      var trusted =
          items.cancellableTransaction(
              token, c -> names.query(c, pair.names(), connection -> pair.names()));
      try (var itemLease = itemReader.verify(pair.item(), token);
          var nameLease = nameReader.verify(trusted, token)) {
        return items.queryCancellable(
            pair.item(),
            token,
            c ->
                names.query(
                    c,
                    trusted,
                    connection -> {
                      if (items.exact(connection, pair.item(), 0).isPresent())
                        throw fail(INCOMPATIBLE, "Admitted name join requires positive Item IDs");
                      requireSearchBounds(pair);
                      String fingerprint = generations(connection, pair);
                      if (cursor.isPresent()
                          && !cursor.get().generationFingerprint().equals(fingerprint))
                        throw fail(STALE_CURSOR, "Prepared SQL artifact generations changed");
                      return scan(
                          connection,
                          pair,
                          query,
                          cursor.map(ItemNamesSearchCursor::afterRecordId).orElse(0L),
                          size,
                          fingerprint,
                          token);
                    }));
      }
    } catch (RuntimeException failure) {
      throw classify(failure);
    }
  }

  private static void requireSearchBounds(PreparedItemNames pair) {
    if (pair.item().coverage().physicalRows() > MAX_SEARCH_ITEM_ROWS
        || pair.names().coverage().physicalRows() > MAX_SEARCH_NAME_ROWS
        || pair.names().coverage().projectedUtf8Bytes() > MAX_SEARCH_NAME_UTF8_BYTES)
      throw fail(LIMIT_EXCEEDED, "Prepared physical coverage exceeds literal-search action limits");
  }

  private ItemNamesSearchPage scan(
      Connection c,
      PreparedItemNames pair,
      ItemNameLiteralQuery query,
      long after,
      int size,
      String fingerprint,
      CancellationToken token)
      throws SQLException {
    var matches = new ArrayList<NamedItemRow>();
    long itemCount = 0, nameCount = 0, utf8 = 0;
    boolean eof = false;
    while (!eof && matches.size() <= size) {
      token.throwIfCancellationRequested();
      int batchCount = 0;
      try (var statement = joinedStatement(c, pair, after, SEARCH_BATCH_ROWS, false);
          var rows = statement.executeQuery()) {
        while (rows.next()) {
          token.throwIfCancellationRequested();
          var row = readJoined(rows, pair);
          after = row.item().recordId();
          batchCount++;
          if (++itemCount > MAX_SEARCH_ITEM_ROWS)
            throw fail(LIMIT_EXCEEDED, "Examined Item anchors exceed search action limit");
          if (row.name().outcome() == ItemNameMatch.Outcome.PHYSICAL_NAME) {
            var candidate = row.name().candidate().orElseThrow();
            if (++nameCount > MAX_SEARCH_NAME_ROWS)
              throw fail(LIMIT_EXCEEDED, "Examined physical names exceed search action limit");
            utf8 = Math.addExact(utf8, candidate.utf8Bytes());
            if (utf8 > MAX_SEARCH_NAME_UTF8_BYTES)
              throw fail(LIMIT_EXCEEDED, "Decoded candidate bytes exceed search action limit");
            if (query.matches(candidate)) {
              matches.add(row);
              if (matches.size() > size) break;
            }
          }
        }
      }
      eof = batchCount < SEARCH_BATCH_ROWS;
    }
    token.throwIfCancellationRequested();
    boolean more = matches.size() > size;
    var visible = List.copyOf(more ? matches.subList(0, size) : matches);
    Optional<ItemNamesSearchCursor> next =
        more
            ? Optional.of(
                new ItemNamesSearchCursor(
                    pair.item().artifactId(),
                    pair.names().artifactId(),
                    pair.snapshotId(),
                    pair.item().sourceRole(),
                    pair.names().sourceRole(),
                    pair.locale(),
                    query,
                    ItemNamesSearchCursor.QUERY_REVISION,
                    fingerprint,
                    visible.getLast().item().recordId(),
                    size))
            : Optional.empty();
    return new ItemNamesSearchPage(pair, query, fingerprint, visible, next);
  }

  private static String generations(Connection c, PreparedItemNames pair) throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT i.generation,n.generation FROM item_projection_artifacts i JOIN"
                + " sparse_name_artifacts n ON n.artifact_id=? WHERE i.artifact_id=? AND"
                + " i.status='READY' AND n.status='READY'")) {
      query.setString(1, pair.names().artifactId());
      query.setString(2, pair.item().artifactId());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) throw fail(NOT_READY, "Prepared search generations are absent");
        try {
          var bytes = new ByteArrayOutputStream();
          try (var out = new DataOutputStream(bytes)) {
            for (var value :
                List.of(
                    "item-name-search-generations-v1",
                    pair.item().artifactId(),
                    UUID.fromString(rows.getString(1)).toString(),
                    pair.names().artifactId(),
                    UUID.fromString(rows.getString(2)).toString())) {
              var text = value.getBytes(StandardCharsets.UTF_8);
              out.writeInt(text.length);
              out.write(text);
            }
          }
          return HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
          throw new AssertionError(impossible);
        }
      }
    }
  }

  private <T> T itemQuery(
      PreparedItemProjection handle,
      CancellationToken token,
      SqliteItemProjectionRepository.Work<T> work) {
    return items.query(
        handle,
        token,
        connection -> {
          if (items.exact(connection, handle, 0).isPresent())
            throw fail(INCOMPATIBLE, "Admitted name join requires positive physical Item IDs");
          return work.run(connection);
        });
  }

  private PreparedStatement joinedStatement(
      Connection c, PreparedItemNames pair, long id, int limit, boolean exact) throws SQLException {
    // The Item index anchors every row; both independent artifact generations are explicit.
    String sql =
        "SELECT i.record_id,i.physical_ordinal,length(i.row),i.row,i.row_sha256,"
            + "n.record_id,n.physical_ordinal,length(n.record),n.record,n.record_sha256,p.target_id,p.source_id,length(p.record),p.record,p.record_sha256"
            + " FROM item_projection_rows i LEFT JOIN sparse_name_rows n ON n.artifact_id=? AND"
            + " n.generation=(SELECT generation FROM sparse_name_artifacts WHERE artifact_id=?) AND"
            + " n.record_id=i.record_id LEFT JOIN sparse_name_copies p ON p.artifact_id=? AND"
            + " p.generation=(SELECT generation FROM sparse_name_artifacts WHERE artifact_id=?) AND"
            + " p.target_id=i.record_id WHERE i.artifact_id=? AND i.generation=(SELECT generation"
            + " FROM item_projection_artifacts WHERE artifact_id=?) AND i.record_id"
            + (exact ? "=?" : " >?")
            + " ORDER BY i.record_id ASC LIMIT ?";
    var q = c.prepareStatement(sql);
    try {
      for (int n = 1; n <= 4; n++) q.setString(n, pair.names().artifactId());
      q.setString(5, pair.item().artifactId());
      q.setString(6, pair.item().artifactId());
      q.setLong(7, id);
      q.setInt(8, limit);
      return q;
    } catch (SQLException | RuntimeException | Error failure) {
      try {
        q.close();
      } catch (SQLException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private NamedItemRow readJoined(ResultSet rows, PreparedItemNames pair) throws SQLException {
    var item = items.readRow(rows, pair.item());
    boolean physical = rows.getObject(6) != null, copy = rows.getObject(11) != null;
    if (physical && copy) throw fail(CORRUPT, "Sparse physical row overlaps a copy declaration");
    ItemNameMatch match;
    if (physical)
      match =
          new ItemNameMatch(
              item.recordId(),
              ItemNameMatch.Outcome.PHYSICAL_NAME,
              Optional.of(names.readRow(rows, pair.names(), 6)),
              OptionalLong.empty());
    else if (copy)
      match =
          new ItemNameMatch(
              item.recordId(),
              ItemNameMatch.Outcome.COPY_UNINDEXED,
              Optional.empty(),
              OptionalLong.of(SqliteSparseNameRepository.readCopy(rows, item.recordId(), 11)));
    else
      match =
          new ItemNameMatch(
              item.recordId(),
              ItemNameMatch.Outcome.NO_PHYSICAL_MATCH,
              Optional.empty(),
              OptionalLong.empty());
    var joined = new NamedItemRow(item, match);
    pair.requireRow(joined);
    return joined;
  }

  /** Caller must already hold source leases and the exact verified context transaction. */
  Optional<NamedItemRow> exactJoined(Connection connection, PreparedItemNames pair, long recordId)
      throws SQLException {
    return joined(connection, pair, recordId, 1, true).stream().findFirst();
  }

  private List<NamedItemRow> joined(
      Connection c, PreparedItemNames pair, long id, int limit, boolean exact) throws SQLException {
    var result = new ArrayList<NamedItemRow>();
    try (var query = joinedStatement(c, pair, id, limit, exact);
        var rows = query.executeQuery()) {
      while (rows.next()) result.add(readJoined(rows, pair));
    }
    return result;
  }

  private final class Sink implements SparseNameProjectionSourceReader.RowSink {
    final SqliteSparseNameRepository.Stage stage;
    final SparseNameArtifactDefinition definition;
    final SparseNameLimits limits;
    final int batch;
    final Set<Long> copies;
    final CancellationToken token;
    final List<DisplayCandidate> pending = new ArrayList<>();
    final Set<Long> physicalIds = new HashSet<>();
    long count, utf8;

    Sink(
        SqliteSparseNameRepository.Stage stage,
        SparseNameArtifactDefinition definition,
        SparseNameLimits limits,
        int batch,
        Set<Long> copies,
        CancellationToken token) {
      this.stage = stage;
      this.definition = definition;
      this.limits = limits;
      this.batch = batch;
      this.copies = copies;
      this.token = token;
    }

    public void accept(DisplayCandidate row) {
      token.throwIfCancellationRequested();
      if (count >= limits.maxPhysicalRows()
          || row.recordBytes() > limits.maxRecordBytes()
          || row.prefixBytes() > limits.maxPrefixBytes()
          || row.utf8Bytes() > limits.maxStringBytes())
        throw fail(LIMIT_EXCEEDED, "Sparse row exceeds requested extraction limits");
      var i = definition.interpretation();
      if (row.sectionOrdinal() != i.sectionOrdinal()
          || row.physicalOrdinal() != count
          || row.decodedOffset() < i.recordAreaOffset()
          || row.decodedOffset() + row.recordBytes() > i.recordAreaOffset() + i.recordAreaBytes()
          || copies.contains(row.physicalRecordId()))
        throw fail(CORRUPT, "Sparse callback changed physical scope or overlaps copy metadata");
      if (!physicalIds.add(row.physicalRecordId()))
        throw fail(CORRUPT, "Duplicate physical Sparse record ID");
      utf8 = Math.addExact(utf8, row.utf8Bytes());
      if (utf8 > limits.maxProjectedUtf8Bytes())
        throw fail(LIMIT_EXCEEDED, "Projected name bytes exceed requested bound");
      count++;
      if (stage != null) {
        pending.add(row);
        if (pending.size() == batch) flush();
      }
    }

    void flush() {
      if (!pending.isEmpty()) {
        names.rows(stage, pending, token);
        pending.clear();
      }
    }
  }

  private static void validateRequest(ItemNamesRequest request) {
    if (request == null
        || !"enUS".equals(request.locale())
        || !request.item().locale().equals(request.locale())
        || !request
            .item()
            .snapshotId()
            .equals(request.sparseSource().association().build().snapshotId()))
      throw fail(INVALID_REQUEST, "Explicit same-snapshot enUS Item/Sparse pair required");
    var item = request.item();
    var iv = item.evidence().association().descriptor().association();
    var i = item.definition().interpretation();
    var schema = i.schema();
    var expectedFields =
        List.of(
            new ItemArtifactDefinition.FieldBinding(
                ItemArtifactDefinition.Field.CLASS_ID, 0, 32, ItemArtifactDefinition.Sign.SIGNED),
            new ItemArtifactDefinition.FieldBinding(
                ItemArtifactDefinition.Field.SUBCLASS_ID,
                1,
                8,
                ItemArtifactDefinition.Sign.UNSIGNED),
            new ItemArtifactDefinition.FieldBinding(
                ItemArtifactDefinition.Field.INVENTORY_TYPE,
                3,
                8,
                ItemArtifactDefinition.Sign.SIGNED),
            new ItemArtifactDefinition.FieldBinding(
                ItemArtifactDefinition.Field.ICON_FILE_DATA_ID,
                6,
                32,
                ItemArtifactDefinition.Sign.SIGNED));
    if (!iv.build().productId().equals("wow")
        || !iv.build().region().equals("us")
        || !iv.build().versionLabel().equals("12.1.5.70448")
        || iv.fileDataId() != 841626
        || !iv.contentKey().equals("1df6b6dbae36878cea020a62b15b9bd6")
        || i.tableHash() != 0x50238ec2L
        || i.layoutHash() != 0x996192aaL
        || i.sectionOrdinal() != 0
        || item.definition().sources().size() != 1
        || !schema.provider().equals("WoWDBDefs")
        || !schema.revision().equals("b9a63096cdd99b26967f17f9515377ae62cadfd7")
        || !schema.definitionPath().equals("definitions/Item.dbd")
        || !schema
            .definitionSha256()
            .equals("076ea4c7e80c891b399cc77f285c807071080c0fed95701e447f7c98024ae120")
        || !schema.fields().equals(expectedFields))
      throw fail(UNSUPPORTED, "Item source is outside admitted paired cohort");
    var v = request.sparseSource().association();
    if (v.fileDataId() != 1572924
        || !v.contentKey().equals("bb0ecbdc2fd4988ddd3c1c5e95c34165")
        || (v.localeFlags() & 2) == 0)
      throw fail(UNSUPPORTED, "Sparse source is outside admitted cohort");
  }

  private static void requireDefinition(
      ItemNamesRequest request, SparseNameArtifactDefinition definition) {
    var i = definition.interpretation();
    var l = request.limits();
    if (!request.locale().equals(i.locale()))
      throw fail(CORRUPT, "Sparse definition changed requested locale");
    if (definition.sources().getFirst().length() > l.maxEncodedBytes()
        || i.metadataPrefixBytes() > l.maxMetadataBytes()
        || i.recordAreaBytes() > l.maxRecordAreaBytes()
        || i.suffixBytes() > l.maxSuffixBytes())
      throw fail(LIMIT_EXCEEDED, "Sparse definition exceeds requested local extraction bounds");
  }

  private static void requireCompleted(
      ItemNamesRequest request,
      SparseNameArtifactDefinition definition,
      PreparedSparseNames completed,
      Sink sink,
      int copies) {
    if (completed == null
        || !completed.definition().equals(definition)
        || !completed.snapshotId().equals(request.item().snapshotId())
        || !completed.sourceRole().equals(request.sparseSource().role())
        || completed.coverage().physicalRows() != sink.count
        || completed.coverage().projectedUtf8Bytes() != sink.utf8
        || completed.coverage().unindexedCopyDeclarations() != copies)
      throw fail(CORRUPT, "Completing Sparse source changed explicit pair or provisional coverage");
  }

  private static RuntimeException classify(RuntimeException error) {
    if (error instanceof ItemNamesException || error instanceof CancellationException) return error;
    if (error instanceof ItemProjectionException item)
      return new ItemNamesException(
          ItemNamesException.Code.valueOf(item.code().name()), item.getMessage(), item);
    if (error instanceof ObjectStoreException store)
      return new ItemNamesException(
          switch (store.code()) {
            case MISSING_OBJECT -> MISSING_LOCAL;
            case INTEGRITY_FAILURE -> CORRUPT;
            case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
            case BUSY, STORE_IN_USE -> BUSY;
            case CLOSED -> CLOSED;
            case IO_FAILURE -> STORAGE_UNAVAILABLE;
          },
          "Retained names source is unavailable",
          store);
    if (error instanceof CatalogException catalog)
      return new ItemNamesException(
          switch (catalog.code()) {
            case CORRUPT_METADATA, INTEGRITY_FAILURE, INVALID_EVIDENCE, IMMUTABLE_CONFLICT ->
                CORRUPT;
            case MISSING_OBJECT, UNKNOWN_SNAPSHOT -> MISSING_LOCAL;
            case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
          },
          catalog.getMessage(),
          catalog);
    if (error instanceof SelectedFileException selected)
      return new ItemNamesException(
          switch (selected.code()) {
            case MISSING_LOCAL, NOT_FOUND -> MISSING_LOCAL;
            case INTEGRITY_FAILURE, INVALID_PLAN -> CORRUPT;
            case INVALID_REQUEST -> INVALID_REQUEST;
            case CLOSED -> CLOSED;
            case REPLAN_REQUIRED, RECOVERY_REQUIRED, INCOMPLETE_SEARCH -> NOT_READY;
            case UNSUPPORTED -> UNSUPPORTED;
            case BUDGET_EXCEEDED -> LIMIT_EXCEEDED;
            case BUSY, CAPACITY_EXCEEDED -> BUSY;
            case CANCELLED -> throw new CancellationException("Retained source replay cancelled");
            default -> STORAGE_UNAVAILABLE;
          },
          selected.getMessage(),
          selected);
    if (error instanceof IllegalArgumentException || error instanceof ArithmeticException)
      return new ItemNamesException(CORRUPT, "Invalid completed names metadata", error);
    return new ItemNamesException(STORAGE_UNAVAILABLE, "Item names operation failed", error);
  }

  private static ItemNamesException fail(ItemNamesException.Code code, String message) {
    return new ItemNamesException(code, message);
  }
}
