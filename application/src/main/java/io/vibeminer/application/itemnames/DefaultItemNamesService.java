package io.vibeminer.application.itemnames;

import static io.vibeminer.application.itemnames.ItemNamesException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.item.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/** Caller-owned executor; bounded contextual authorization never replaces backend proof replay. */
public final class DefaultItemNamesService implements ItemNamesService {
  private record Authorization(PreparedItemNames pair, SparseNameLimits limits) {}

  private final ItemNamesBackend backend;
  private final Executor executor;
  private final Semaphore capacity = new Semaphore(4);
  private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
  private final Map<String, Authorization> ready = new LinkedHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public DefaultItemNamesService(ItemNamesBackend backend, Executor executor) {
    this.backend = Objects.requireNonNull(backend);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public CompletionStage<PreparedItemNames> prepare(ItemNamesRequest request) {
    return submit(
        token -> {
          validateRequest(request);
          try {
            var result = backend.prepare(request, token);
            token.throwIfCancellationRequested();
            if (result == null
                || !sameSelectedItem(request.item(), result.item())
                || !result.item().sourceRole().equals(request.item().sourceRole())
                || !result.snapshotId().equals(request.item().snapshotId())
                || !result.locale().equals(request.locale())
                || !result.names().sourceRole().equals(request.sparseSource().role()))
              throw error(CORRUPT, "Preparation changed the selected exact source pair");
            validateBounds(result, request.limits());
            synchronized (ready) {
              token.throwIfCancellationRequested();
              ready.put(key(result), new Authorization(result, request.limits()));
              while (ready.size() > 16) ready.remove(ready.keySet().iterator().next());
            }
            return result;
          } catch (ItemNamesException failure) {
            if (badEvidence(failure)) revokeRequest(request);
            throw failure;
          }
        });
  }

  @Override
  public CompletionStage<ItemNamesPage> page(
      PreparedItemNames pair, Optional<ItemNamesCursor> cursor, int size) {
    return submit(
        token -> {
          if (pair == null || cursor == null || size < 1 || size > 500)
            throw error(INVALID_REQUEST, "Invalid bounded paired page request");
          if (cursor.isPresent())
            try {
              cursor.get().requireContext(pair, size);
            } catch (IllegalArgumentException stale) {
              throw error(STALE_CURSOR, "Cursor belongs to another exact pair/query");
            }
          var authorization = requireReady(pair);
          try {
            var result = backend.page(pair, cursor, size, token);
            token.throwIfCancellationRequested();
            requireReady(pair);
            if (result == null
                || !result.pair().equals(pair)
                || result.rows().size() > size
                || result.rows().size() > pair.item().coverage().physicalRows())
              throw error(CORRUPT, "Page changed pair or bounds");
            long previous = cursor.map(ItemNamesCursor::afterRecordId).orElse(0L);
            var itemOrdinals = new HashSet<Integer>();
            var nameOrdinals = new HashSet<Integer>();
            for (var row : result.rows()) {
              validateRow(pair, row, authorization.limits());
              if (row.item().recordId() <= previous
                  || !itemOrdinals.add(row.item().physicalOrdinal()))
                throw error(CORRUPT, "Page changed Item numeric/physical ordering");
              if (row.name().candidate().isPresent()
                  && !nameOrdinals.add(row.name().candidate().get().physicalOrdinal()))
                throw error(CORRUPT, "Page duplicated a physical Sparse record");
              previous = row.item().recordId();
            }
            if (result.nextCursor().isPresent()) {
              var next = result.nextCursor().get();
              try {
                next.requireContext(pair, size);
              } catch (IllegalArgumentException changed) {
                throw error(CORRUPT, "Backend cursor changed pair/query");
              }
              if (result.rows().size() != size || next.afterRecordId() != previous)
                throw error(CORRUPT, "Backend cursor does not follow the admitted Item page");
            }
            return result;
          } catch (ItemNamesException failure) {
            revoke(pair, failure);
            throw failure;
          }
        });
  }

  @Override
  public CompletionStage<ItemNamesSearchPage> search(
      PreparedItemNames pair,
      ItemNameLiteralQuery query,
      Optional<ItemNamesSearchCursor> cursor,
      int size) {
    return submit(
        token -> {
          if (pair == null || query == null || cursor == null || size < 1 || size > 500)
            throw error(
                INVALID_REQUEST, "Explicit pair, literal query and page size 1 to 500 required");
          if (cursor.isPresent()) {
            try {
              cursor.get().requireContext(pair, query, size);
            } catch (IllegalArgumentException stale) {
              throw error(
                  STALE_CURSOR, "Search cursor belongs to another pair, query or page size");
            }
          }
          var authorization = requireReady(pair);
          if (pair.item().coverage().physicalRows() > ItemNamesBackend.MAX_SEARCH_ITEM_ROWS
              || pair.names().coverage().physicalRows() > ItemNamesBackend.MAX_SEARCH_NAME_ROWS
              || pair.names().coverage().projectedUtf8Bytes()
                  > ItemNamesBackend.MAX_SEARCH_NAME_UTF8_BYTES)
            throw error(LIMIT_EXCEEDED, "Prepared pair exceeds the admitted literal-search scope");
          try {
            var result = backend.search(pair, query, cursor, size, token);
            token.throwIfCancellationRequested();
            requireReady(pair);
            if (result == null
                || !result.pair().equals(pair)
                || !result.query().equals(query)
                || result.rows().size() > size
                || result.rows().size() > pair.item().coverage().physicalRows()
                || result.rows().size() > pair.names().coverage().physicalRows())
              throw error(CORRUPT, "Search response changed exact pair, query or result bounds");
            if (cursor.isPresent()
                && !cursor.get().generationFingerprint().equals(result.generationFingerprint()))
              throw error(
                  STALE_CURSOR, "Prepared artifact generations changed during search continuation");
            long previous = cursor.map(ItemNamesSearchCursor::afterRecordId).orElse(0L);
            long utf8 = 0;
            var itemOrdinals = new HashSet<Integer>();
            var nameOrdinals = new HashSet<Integer>();
            for (var row : result.rows()) {
              validateRow(pair, row, authorization.limits());
              if (row.name().outcome() != ItemNameMatch.Outcome.PHYSICAL_NAME)
                throw error(CORRUPT, "Search response contains an unavailable or unindexed name");
              var candidate = row.name().candidate().orElseThrow();
              if (!query.matches(candidate)
                  || row.item().recordId() <= previous
                  || !itemOrdinals.add(row.item().physicalOrdinal())
                  || !nameOrdinals.add(candidate.physicalOrdinal()))
                throw error(
                    CORRUPT, "Search response changed literal predicate or physical ordering");
              if (candidate.utf8Bytes() > pair.names().coverage().projectedUtf8Bytes() - utf8)
                throw error(CORRUPT, "Search response exceeds admitted projected-name bytes");
              utf8 += candidate.utf8Bytes();
              previous = row.item().recordId();
            }
            if (result.nextCursor().isPresent()) {
              var next = result.nextCursor().get();
              try {
                next.requireContext(pair, query, size);
              } catch (IllegalArgumentException changed) {
                throw error(CORRUPT, "Backend search cursor changed context");
              }
              if (result.rows().size() != size
                  || next.afterRecordId() != previous
                  || !next.generationFingerprint().equals(result.generationFingerprint()))
                throw error(
                    CORRUPT, "Search cursor does not follow the last visible match and generation");
            }
            return result;
          } catch (ItemNamesException failure) {
            revoke(pair, failure);
            throw failure;
          }
        });
  }

  @Override
  public CompletionStage<ItemNamesLookup> lookup(PreparedItemNames pair, long recordId) {
    return submit(
        token -> {
          if (pair == null || recordId < 1 || recordId > 0xffffffffL)
            throw error(INVALID_REQUEST, "Positive unsigned Item record ID required");
          var authorization = requireReady(pair);
          try {
            var result = backend.lookup(pair, recordId, token);
            token.throwIfCancellationRequested();
            requireReady(pair);
            if (result == null
                || !result.pair().equals(pair)
                || result.result().item().requestedId() != recordId)
              throw error(CORRUPT, "Lookup changed exact pair/requested Item ID");
            try {
              pair.requireLookup(result.result());
            } catch (IllegalArgumentException changed) {
              throw error(CORRUPT, "Lookup changed admitted Item/name scope");
            }
            if (result.result().name().isPresent())
              validateMatch(result.result().name().get(), authorization.limits());
            return result;
          } catch (ItemNamesException failure) {
            revoke(pair, failure);
            throw failure;
          }
        });
  }

  private static void validateRequest(ItemNamesRequest r) {
    if (r == null) throw error(INVALID_REQUEST, "Explicit retained source pair request required");
    var item = r.item();
    var iv = item.evidence().association().descriptor().association();
    var sparse = r.sparseSource().association();
    var i = item.definition().interpretation();
    var schema = i.schema();
    if (!"enUS".equals(r.locale())
        || !r.locale().equals(item.locale())
        || !item.snapshotId().equals(sparse.build().snapshotId())
        || !iv.build().productId().equals("wow")
        || !iv.build().region().equals("us")
        || !iv.build().versionLabel().equals("12.1.5.70448")
        || iv.fileDataId() != 841626
        || !iv.contentKey().equals("1df6b6dbae36878cea020a62b15b9bd6")
        || i.tableHash() != 0x50238ec2L
        || i.layoutHash() != 0x996192aaL
        || sparse.fileDataId() != 1572924
        || !sparse.contentKey().equals("bb0ecbdc2fd4988ddd3c1c5e95c34165")
        || (sparse.localeFlags() & 2) == 0
        || i.sectionOrdinal() != 0
        || item.definition().sources().size() != 1
        || !schema.provider().equals("WoWDBDefs")
        || !schema.revision().equals("b9a63096cdd99b26967f17f9515377ae62cadfd7")
        || !schema.definitionPath().equals("definitions/Item.dbd")
        || !schema
            .definitionSha256()
            .equals("076ea4c7e80c891b399cc77f285c807071080c0fed95701e447f7c98024ae120")
        || !schema
            .fields()
            .equals(
                List.of(
                    new ItemArtifactDefinition.FieldBinding(
                        ItemArtifactDefinition.Field.CLASS_ID,
                        0,
                        32,
                        ItemArtifactDefinition.Sign.SIGNED),
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
                        ItemArtifactDefinition.Sign.SIGNED))))
      throw error(
          INVALID_REQUEST,
          "Requires explicit supported Item/Sparse pair in one exact snapshot/enUS context");
  }

  private static void validateBounds(PreparedItemNames pair, SparseNameLimits l) {
    var n = pair.names();
    var i = n.definition().interpretation();
    if (n.evidence().localVerification().object().length() > l.maxEncodedBytes()
        || i.metadataPrefixBytes() > l.maxMetadataBytes()
        || i.suffixBytes() > l.maxSuffixBytes()
        || i.recordAreaBytes() > l.maxRecordAreaBytes()
        || n.coverage().physicalRows() > l.maxPhysicalRows()
        || n.coverage().unindexedCopyDeclarations() > l.maxCopyDeclarations()
        || n.coverage().projectedUtf8Bytes() > l.maxProjectedUtf8Bytes()
        || n.evidence().chunks().size() > l.maxChunks())
      throw error(LIMIT_EXCEEDED, "Prepared Sparse scope exceeded explicit extraction limits");
    long decoded = 0;
    for (var chunk : n.evidence().chunks()) {
      if (chunk.encodedBytes() > l.maxChunkEncodedBytes()
          || chunk.decodedBytes() > l.maxChunkDecodedBytes()
          || chunk.decodedBytes() > l.maxDecodedWorkBytes() - decoded)
        throw error(LIMIT_EXCEEDED, "Consumed Sparse chunks exceeded extraction bounds");
      decoded += chunk.decodedBytes();
    }
  }

  private static boolean sameSelectedItem(
      PreparedItemProjection requested, PreparedItemProjection returned) {
    var before = requested.evidence();
    var after = returned.evidence();
    return requested.definition().equals(returned.definition())
        && requested.coverage().equals(returned.coverage())
        && before.association().equals(after.association())
        && before.localVerification().object().equals(after.localVerification().object())
        && before
            .localVerification()
            .verifierRevision()
            .equals(after.localVerification().verifierRevision())
        && before.chunks().equals(after.chunks())
        && before.windows().equals(after.windows())
        && before.fullDecoded().equals(after.fullDecoded());
  }

  private static void validateRow(
      PreparedItemNames pair, NamedItemRow row, SparseNameLimits limits) {
    try {
      pair.requireRow(row);
    } catch (IllegalArgumentException changed) {
      throw error(CORRUPT, "Joined row changed admitted physical scope/binding");
    }
    validateMatch(row.name(), limits);
  }

  private static void validateMatch(ItemNameMatch match, SparseNameLimits limits) {
    if (match.candidate().isPresent()) {
      var c = match.candidate().get();
      if (c.recordBytes() > limits.maxRecordBytes()
          || c.prefixBytes() > limits.maxPrefixBytes()
          || c.utf8Bytes() > limits.maxStringBytes())
        throw error(CORRUPT, "Joined name exceeds admitted extraction bounds");
    }
  }

  private static String key(PreparedItemNames p) {
    return p.item().artifactId()
        + ":"
        + p.names().artifactId()
        + ":"
        + p.snapshotId()
        + ":"
        + p.item().sourceRole()
        + ":"
        + p.names().sourceRole()
        + ":"
        + p.locale();
  }

  private Authorization requireReady(PreparedItemNames p) {
    synchronized (ready) {
      var a = ready.get(key(p));
      if (a == null || !a.pair().equals(p))
        throw error(
            NOT_READY, "Explicit successful preparation required for this exact source pair");
      return a;
    }
  }

  private void revokeRequest(ItemNamesRequest r) {
    synchronized (ready) {
      ready
          .values()
          .removeIf(
              a ->
                  a.pair().snapshotId().equals(r.item().snapshotId())
                      && a.pair().locale().equals(r.locale())
                      && a.pair().item().sourceRole().equals(r.item().sourceRole())
                      && a.pair().names().sourceRole().equals(r.sparseSource().role()));
    }
  }

  private void revoke(PreparedItemNames p, ItemNamesException failure) {
    if (badEvidence(failure))
      synchronized (ready) {
        var a = ready.get(key(p));
        if (a != null && a.pair().equals(p)) ready.remove(key(p));
      }
  }

  private static boolean badEvidence(ItemNamesException f) {
    return f.code() == MISSING_LOCAL
        || f.code() == CORRUPT
        || f.code() == INCOMPATIBLE
        || f.code() == UNSUPPORTED;
  }

  private <T> CompletionStage<T> submit(Function<CancellationToken, T> operation) {
    if (closed.get()) return CompletableFuture.failedFuture(error(CLOSED, "Names service closed"));
    if (!capacity.tryAcquire())
      return CompletableFuture.failedFuture(error(BUSY, "Four names workers already admitted"));
    var result = new CompletableFuture<T>();
    var worker = new AtomicReference<Thread>();
    active.add(result);
    result.whenComplete(
        (value, failure) -> {
          if (result.isCancelled()) {
            var t = worker.get();
            if (t != null) t.interrupt();
          }
        });
    try {
      executor.execute(
          () -> {
            worker.set(Thread.currentThread());
            CancellationToken token =
                () ->
                    result.isCancelled() || closed.get() || Thread.currentThread().isInterrupted();
            try {
              token.throwIfCancellationRequested();
              var value = operation.apply(token);
              token.throwIfCancellationRequested();
              result.complete(value);
            } catch (Throwable failure) {
              result.completeExceptionally(
                  failure instanceof ItemNamesException || failure instanceof CancellationException
                      ? failure
                      : new ItemNamesException(
                          STORAGE_UNAVAILABLE, "Local names operation failed", failure));
              if (failure instanceof Error fatal) throw fatal;
            } finally {
              worker.set(null);
              active.remove(result);
              capacity.release();
            }
          });
    } catch (RuntimeException rejected) {
      active.remove(result);
      capacity.release();
      result.completeExceptionally(
          new ItemNamesException(BUSY, "Names executor rejected work", rejected));
    }
    if (closed.get()) result.cancel(true);
    return result;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    active.forEach(f -> f.cancel(true));
    synchronized (ready) {
      ready.clear();
    }
  }

  private static ItemNamesException error(ItemNamesException.Code c, String m) {
    return new ItemNamesException(c, m);
  }
}
