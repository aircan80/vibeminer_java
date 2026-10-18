package io.vibeminer.infrastructure.item;

import static io.vibeminer.application.item.ItemProjectionException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.item.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.util.*;

/** Retained-only projection preparation and fixed numeric queries; owns no transport or stores. */
public final class SqliteItemProjectionBackend implements ItemProjectionBackend {
  private static final int BATCH_SIZE = 128;
  private final SqliteItemProjectionRepository repository;
  private final ItemProjectionSourceReader reader;
  private final FileObjectStore.StoreSession session;

  public SqliteItemProjectionBackend(
      SqliteCatalog catalog,
      FileObjectStore.StoreSession session,
      ItemProjectionSourceReader reader) {
    this.session = Objects.requireNonNull(session);
    repository = new SqliteItemProjectionRepository(catalog, session);
    this.reader = Objects.requireNonNull(reader);
  }

  @Override
  public PreparedItemProjection prepare(ItemProjectionRequest request, CancellationToken token) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(token);
    token.throwIfCancellationRequested();
    beforeSourceOperation();
    try (var opened = reader.open(request, token)) {
      var definition = opened.definition();
      var evidence = opened.evidence();
      validateSource(request, definition, evidence);
      var copies = List.copyOf(opened.copies());
      if (copies.size() > request.limits().maxCopyDeclarations())
        throw failure(LIMIT_EXCEEDED, "Copy declarations exceed requested projection bound");
      var copyTargets = new HashSet<Long>();
      for (var copy : copies)
        if (!copyTargets.add(copy.targetId()))
          throw failure(CORRUPT, "Duplicate copy declaration target");
      var header = repository.claim(definition, token);
      if (header.ready()) {
        requireCoverage(header.coverage(), request.limits(), copies.size());
        return repository.reuse(header, evidence, token);
      }
      var stage =
          new SqliteItemProjectionRepository.Stage(definition.artifactId(), header.generation());
      boolean published = false;
      Throwable failed = null;
      try {
        for (int start = 0; start < copies.size(); start += BATCH_SIZE)
          repository.copies(
              stage, copies.subList(start, Math.min(copies.size(), start + BATCH_SIZE)), token);
        var sink =
            new BatchSink(
                stage,
                definition.interpretation().sectionOrdinal(),
                request.limits().maxPhysicalRows(),
                token);
        var coverage = opened.project(sink, token);
        token.throwIfCancellationRequested();
        sink.flush();
        requireCoverage(coverage, request.limits(), copies.size());
        if (coverage.sectionOrdinal() != definition.interpretation().sectionOrdinal()
            || coverage.physicalRows() != sink.count)
          throw failure(CORRUPT, "Completing projection scope differs from staged physical rows");
        var result = repository.publish(stage, definition, evidence, coverage, token);
        published = true;
        return result;
      } catch (RuntimeException | Error failure) {
        failed = failure;
        throw failure;
      } finally {
        if (!published)
          try {
            repository.discard(stage);
          } catch (RuntimeException cleanup) {
            if (failed != null) failed.addSuppressed(cleanup);
            else throw cleanup;
          }
      }
    } catch (RuntimeException failure) {
      throw classify(failure);
    }
  }

  @Override
  public ItemPage page(
      PreparedItemProjection handle,
      Optional<ItemPageCursor> cursor,
      int size,
      CancellationToken token) {
    Objects.requireNonNull(handle);
    Objects.requireNonNull(cursor);
    Objects.requireNonNull(token);
    if (size < 1 || size > 500)
      throw failure(INVALID_REQUEST, "Page size must be between 1 and 500");
    try {
      cursor.ifPresent(value -> value.requireContext(handle, size));
    } catch (IllegalArgumentException stale) {
      throw new ItemProjectionException(
          STALE_CURSOR, "Cursor belongs to another projection query", stale);
    }
    token.throwIfCancellationRequested();
    beforeSourceOperation();
    try (var verified = reader.verify(handle, token)) {
      var rows =
          repository.query(
              handle,
              token,
              c ->
                  repository.page(
                      c, handle, cursor.map(ItemPageCursor::afterRecordId).orElse(-1L), size + 1));
      boolean more = rows.size() > size;
      var visible = List.copyOf(more ? rows.subList(0, size) : rows);
      Optional<ItemPageCursor> next =
          more
              ? Optional.of(
                  new ItemPageCursor(
                      handle.artifactId(),
                      handle.snapshotId(),
                      handle.sourceRole(),
                      handle.locale(),
                      ItemPageCursor.QUERY_REVISION,
                      visible.getLast().recordId(),
                      size))
              : Optional.empty();
      return new ItemPage(handle, visible, next);
    } catch (RuntimeException failure) {
      throw classify(failure);
    }
  }

  @Override
  public ItemLookupResult lookup(
      PreparedItemProjection handle, long recordId, CancellationToken token) {
    Objects.requireNonNull(handle);
    Objects.requireNonNull(token);
    if (recordId < 0 || recordId > 0xffffffffL)
      throw failure(INVALID_REQUEST, "Unsigned Item record ID required");
    token.throwIfCancellationRequested();
    beforeSourceOperation();
    try (var verified = reader.verify(handle, token)) {
      return repository.query(
          handle,
          token,
          c -> {
            var row = repository.exact(c, handle, recordId);
            if (row.isPresent())
              return new ItemLookupResult(
                  recordId,
                  ItemLookupResult.Outcome.PHYSICAL_MATCH,
                  row,
                  OptionalLong.empty(),
                  handle.coverage());
            var copy = repository.copy(c, handle, recordId);
            return new ItemLookupResult(
                recordId,
                copy.isPresent()
                    ? ItemLookupResult.Outcome.COPY_UNINDEXED
                    : ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
                Optional.empty(),
                copy,
                handle.coverage());
          });
    } catch (RuntimeException failure) {
      throw classify(failure);
    }
  }

  private final class BatchSink implements ItemProjectionSourceReader.RowSink {
    final SqliteItemProjectionRepository.Stage stage;
    final int section, maximum;
    final CancellationToken token;
    final ArrayList<ItemPhysicalRow> pending = new ArrayList<>(BATCH_SIZE);
    long count;

    BatchSink(
        SqliteItemProjectionRepository.Stage stage,
        int section,
        int maximum,
        CancellationToken token) {
      this.stage = stage;
      this.section = section;
      this.maximum = maximum;
      this.token = token;
    }

    public void accept(ItemPhysicalRow row) {
      token.throwIfCancellationRequested();
      if (count >= maximum)
        throw failure(LIMIT_EXCEEDED, "Physical rows exceed requested projection bound");
      if (row.sectionOrdinal() != section || row.physicalOrdinal() != count)
        throw failure(CORRUPT, "Projection rows must preserve admitted physical order");
      pending.add(row);
      count++;
      if (pending.size() == BATCH_SIZE) flush();
    }

    void flush() {
      if (!pending.isEmpty()) {
        repository.rows(stage, pending, token);
        pending.clear();
      }
    }
  }

  private static void validateSource(
      ItemProjectionRequest request,
      ItemArtifactDefinition definition,
      ItemProjectionEvidence evidence) {
    if (!request.source().role().equals(evidence.association().role())
        || !request
            .source()
            .association()
            .build()
            .snapshotId()
            .equals(evidence.association().snapshotId())
        || !request.locale().equals(definition.interpretation().locale())
        || !definition.sources().contains(evidence.localVerification().object()))
      throw failure(CORRUPT, "Opened projection differs from exact retained request");
    if (evidence.localVerification().object().length() > request.limits().maxEncodedBytes()
        || definition.interpretation().sectionDecodedBytes() > request.limits().maxSectionBytes())
      throw failure(LIMIT_EXCEEDED, "Opened source exceeds requested local work bounds");
  }

  private static void requireCoverage(
      PreparedItemProjection.Coverage coverage, ItemProjectionLimits limits, int copies) {
    if (coverage.physicalRows() > limits.maxPhysicalRows()
        || coverage.unindexedCopyDeclarations() > limits.maxCopyDeclarations())
      throw failure(LIMIT_EXCEEDED, "Projection coverage exceeds requested bounds");
    if (coverage.unindexedCopyDeclarations() != copies)
      throw failure(CORRUPT, "Completing copy coverage changed");
  }

  private void beforeSourceOperation() {
    try {
      session.beforeProducerOperation();
    } catch (RuntimeException failure) {
      throw classify(failure);
    }
  }

  private static RuntimeException classify(RuntimeException failure) {
    if (failure instanceof ItemProjectionException
        || failure instanceof java.util.concurrent.CancellationException) return failure;
    if (failure instanceof ObjectStoreException storage)
      return new ItemProjectionException(
          switch (storage.code()) {
            case MISSING_OBJECT -> MISSING_LOCAL;
            case INTEGRITY_FAILURE -> CORRUPT;
            default -> STORAGE_UNAVAILABLE;
          },
          "Retained projection source is unavailable",
          failure);
    return new ItemProjectionException(
        STORAGE_UNAVAILABLE, "Item projection operation failed", failure);
  }

  private static ItemProjectionException failure(
      ItemProjectionException.Code code, String message) {
    return new ItemProjectionException(code, message);
  }
}
