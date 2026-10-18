package io.vibeminer.application.itemnames;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.item.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ItemNamesSearchServiceTest {
  final ItemNamesServiceTest fixture = new ItemNamesServiceTest();
  final PreparedItemNames pair = fixture.pair;
  final ItemNameLiteralQuery query = new ItemNameLiteralQuery("Name");
  static final String GENERATION = "a".repeat(64);

  interface Action {
    ItemNamesSearchPage run(
        PreparedItemNames pair,
        ItemNameLiteralQuery query,
        Optional<ItemNamesSearchCursor> cursor,
        int size,
        CancellationToken token);
  }

  class Fake extends ItemNamesServiceTest.Fake {
    int searches;
    Action action;

    public ItemNamesSearchPage search(
        PreparedItemNames p,
        ItemNameLiteralQuery q,
        Optional<ItemNamesSearchCursor> c,
        int size,
        CancellationToken token) {
      searches++;
      if (failure != null) throw failure;
      if (action != null) return action.run(p, q, c, size, token);
      return ItemNamesSearchServiceTest.page(
          p, q, GENERATION, List.of(row(101, 0, "Name")), Optional.empty());
    }
  }

  NamedItemRow row(long id, int ordinal, String text) {
    return fixture.row(
        id,
        ordinal,
        new ItemNameMatch(
            id,
            ItemNameMatch.Outcome.PHYSICAL_NAME,
            Optional.of(
                new DisplayCandidate(
                    text,
                    id,
                    ordinal,
                    0,
                    5252 + ordinal * 10,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 5,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 5)),
            OptionalLong.empty()));
  }

  static ItemNamesSearchPage page(
      PreparedItemNames p,
      ItemNameLiteralQuery q,
      String generation,
      List<NamedItemRow> rows,
      Optional<ItemNamesSearchCursor> next) {
    return new ItemNamesSearchPage(p, q, generation, rows, next);
  }

  static ItemNamesSearchCursor cursor(
      PreparedItemNames p, ItemNameLiteralQuery q, String generation, long id, int size) {
    return new ItemNamesSearchCursor(
        p.item().artifactId(),
        p.names().artifactId(),
        p.snapshotId(),
        p.item().sourceRole(),
        p.names().sourceRole(),
        p.locale(),
        q,
        ItemNamesSearchCursor.QUERY_REVISION,
        generation,
        id,
        size);
  }

  DefaultItemNamesService prepared(Fake fake) {
    var service = new DefaultItemNamesService(fake, Runnable::run);
    service.prepare(fixture.request).toCompletableFuture().join();
    return service;
  }

  ItemNamesException.Code code(CompletionStage<?> stage) {
    return ItemNamesServiceTest.code(stage);
  }

  @Test
  void explicitAuthorizedSearchPreservesPairQueryAndImmutableResults() {
    var fake = new Fake();
    try (var service = prepared(fake)) {
      var result = service.search(pair, query, Optional.empty(), 10).toCompletableFuture().join();
      assertEquals(pair, result.pair());
      assertEquals(query, result.query());
      assertEquals(GENERATION, result.generationFingerprint());
      assertEquals(1, fake.searches);
      assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
    }
  }

  @Test
  void badRequestUnpreparedPairAndWrongCursorNeverReachSearchPort() {
    var fake = new Fake();
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      assertEquals(
          ItemNamesException.Code.NOT_READY,
          code(service.search(pair, query, Optional.empty(), 1)));
      service.prepare(fixture.request).toCompletableFuture().join();
      assertEquals(
          ItemNamesException.Code.INVALID_REQUEST,
          code(service.search(pair, null, Optional.empty(), 1)));
      assertEquals(
          ItemNamesException.Code.INVALID_REQUEST,
          code(service.search(pair, query, Optional.empty(), 501)));
      assertEquals(
          ItemNamesException.Code.STALE_CURSOR,
          code(
              service.search(
                  pair,
                  query,
                  Optional.of(cursor(pair, new ItemNameLiteralQuery("name"), GENERATION, 101, 1)),
                  1)));
      assertEquals(0, fake.searches);
    }
  }

  @Test
  void resultMustContainOnlyMatchingPhysicalRowsInStrictItemOrder() {
    var wrongRows =
        List.of(
            List.of(row(101, 0, "Wrong")),
            List.of(row(202, 1, "Name"), row(101, 0, "Name")),
            List.of(row(101, 0, "Name"), row(202, 0, "Name")),
            List.of(
                fixture.row(
                    101,
                    0,
                    new ItemNameMatch(
                        101,
                        ItemNameMatch.Outcome.COPY_UNINDEXED,
                        Optional.empty(),
                        OptionalLong.of(202)))));
    for (var rows : wrongRows) {
      var fake = new Fake();
      fake.action = (p, q, c, n, t) -> page(p, q, GENERATION, rows, Optional.empty());
      try (var service = prepared(fake)) {
        assertEquals(
            ItemNamesException.Code.CORRUPT,
            code(service.search(pair, query, Optional.empty(), 2)));
        assertEquals(ItemNamesException.Code.NOT_READY, code(service.lookup(pair, 101)));
      }
    }
  }

  @Test
  void nextCursorMustFollowVisibleMatchAndBindResultGeneration() {
    var fake = new Fake();
    fake.action =
        (p, q, c, n, t) ->
            page(
                p,
                q,
                GENERATION,
                List.of(row(101, 0, "Name")),
                Optional.of(cursor(p, q, GENERATION, 101, n)));
    try (var service = prepared(fake)) {
      var first = service.search(pair, query, Optional.empty(), 1).toCompletableFuture().join();
      assertEquals(101, first.nextCursor().orElseThrow().afterRecordId());
      fake.action = (p, q, c, n, t) -> page(p, q, "b".repeat(64), List.of(), Optional.empty());
      assertEquals(
          ItemNamesException.Code.STALE_CURSOR,
          code(service.search(pair, query, first.nextCursor(), 1)));
      assertEquals(pair, service.lookup(pair, 101).toCompletableFuture().join().pair());
    }
    for (var after : List.of(99L, 202L)) {
      fake = new Fake();
      final long id = after;
      fake.action =
          (p, q, c, n, t) ->
              page(
                  p,
                  q,
                  GENERATION,
                  List.of(row(101, 0, "Name")),
                  Optional.of(cursor(p, q, GENERATION, id, n)));
      try (var service = prepared(fake)) {
        assertEquals(
            ItemNamesException.Code.CORRUPT,
            code(service.search(pair, query, Optional.empty(), 1)));
      }
    }
  }

  @Test
  void backendCannotReplaceActualQueryOrNextCursorGeneration() {
    for (int variant = 0; variant < 2; variant++) {
      var fake = new Fake();
      int choice = variant;
      fake.action =
          (p, q, c, n, t) ->
              choice == 0
                  ? page(
                      p,
                      new ItemNameLiteralQuery("name"),
                      GENERATION,
                      List.of(row(101, 0, "Name")),
                      Optional.empty())
                  : page(
                      p,
                      q,
                      GENERATION,
                      List.of(row(101, 0, "Name")),
                      Optional.of(cursor(p, q, "b".repeat(64), 101, n)));
      try (var service = prepared(fake)) {
        assertEquals(
            ItemNamesException.Code.CORRUPT,
            code(service.search(pair, query, Optional.empty(), 1)));
      }
    }
  }

  @Test
  void missingSourceRevokesOnlyItsReadyAuthorization() {
    var fake = new Fake();
    try (var service = prepared(fake)) {
      fake.failure =
          new ItemNamesException(ItemNamesException.Code.MISSING_LOCAL, "Missing source");
      assertEquals(
          ItemNamesException.Code.MISSING_LOCAL,
          code(service.search(pair, query, Optional.empty(), 1)));
      fake.failure = null;
      assertEquals(ItemNamesException.Code.NOT_READY, code(service.lookup(pair, 101)));
    }
  }

  @Test
  void queuedCancellationHoldsFourSlotsUntilActualRetirement() {
    var tasks = new ArrayDeque<Runnable>();
    var fake = new Fake();
    try (var service = new DefaultItemNamesService(fake, tasks::add)) {
      var preparation = service.prepare(fixture.request);
      tasks.remove().run();
      preparation.toCompletableFuture().join();
      for (int n = 0; n < 4; n++)
        service.search(pair, query, Optional.empty(), 1).toCompletableFuture().cancel(true);
      assertEquals(
          ItemNamesException.Code.BUSY, code(service.search(pair, query, Optional.empty(), 1)));
      assertEquals(4, tasks.size());
      while (!tasks.isEmpty()) tasks.remove().run();
      assertEquals(0, fake.searches);
      var pending = service.search(pair, query, Optional.empty(), 1);
      tasks.remove().run();
      assertEquals(1, pending.toCompletableFuture().join().rows().size());
    }
  }

  @Test
  void authorizationReplacementDuringBackendWorkRejectsLateResult() {
    var fake = new Fake();
    var reference = new AtomicReference<DefaultItemNamesService>();
    var e = pair.names().evidence();
    var newEvidence =
        new ItemProjectionEvidence(
            e.association(),
            new ItemProjectionEvidence.LocalVerification(
                e.localVerification().object(),
                Instant.ofEpochSecond(99),
                e.localVerification().verifierRevision()),
            e.chunks(),
            e.windows(),
            e.fullDecoded());
    var other =
        new PreparedItemNames(
            pair.item(),
            new PreparedSparseNames(
                pair.names().definition(), newEvidence, pair.names().coverage()));
    fake.action =
        (p, q, c, n, t) -> {
          fake.prepared = other;
          reference.get().prepare(fixture.request).toCompletableFuture().join();
          return ItemNamesSearchServiceTest.page(
              p, q, GENERATION, List.of(row(101, 0, "Name")), Optional.empty());
        };
    try (var service = prepared(fake)) {
      reference.set(service);
      assertEquals(
          ItemNamesException.Code.NOT_READY,
          code(service.search(pair, query, Optional.empty(), 1)));
      fake.action = null;
      assertEquals(
          other,
          service.search(other, query, Optional.empty(), 1).toCompletableFuture().join().pair());
    }
  }

  @Test
  void searchScopeLimitIsCheckedBeforeSearchPortWithoutImplicitReprepare() {
    var original = pair.item();
    var largeItem =
        new PreparedItemProjection(
            original.definition(),
            original.evidence(),
            new PreparedItemProjection.Coverage(
                original.coverage().sectionOrdinal(),
                200001,
                original.coverage().unindexedCopyDeclarations(),
                original.coverage().excludedSections()));
    var largePair = new PreparedItemNames(largeItem, pair.names());
    var fake = new Fake();
    fake.prepared = largePair;
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      var request =
          new ItemNamesRequest(
              largeItem, fixture.request.sparseSource(), fixture.request.locale(), fixture.limits);
      service.prepare(request).toCompletableFuture().join();
      assertEquals(
          ItemNamesException.Code.LIMIT_EXCEEDED,
          code(service.search(largePair, query, Optional.empty(), 1)));
      assertEquals(0, fake.searches);
      assertEquals(1, fake.calls);
    }
  }

  @Test
  void emptyTerminalSearchPreservesBothProofScopesAndGeneration() {
    var fake = new Fake();
    fake.action = (p, q, c, n, t) -> page(p, q, GENERATION, List.of(), Optional.empty());
    try (var service = prepared(fake)) {
      var result = service.search(pair, query, Optional.empty(), 10).toCompletableFuture().join();
      assertEquals(pair, result.pair());
      assertEquals(GENERATION, result.generationFingerprint());
      assertTrue(result.rows().isEmpty());
      assertTrue(result.nextCursor().isEmpty());
      assertEquals(pair, service.lookup(pair, 101).toCompletableFuture().join().pair());
    }
  }

  @Test
  void totalReturnedNameBytesCannotExceedPreparedSourceCoverage() {
    var fake = new Fake();
    fake.action =
        (p, q, c, n, t) ->
            page(
                p,
                q,
                GENERATION,
                List.of(row(101, 0, "NameName"), row(202, 1, "NameName")),
                Optional.empty());
    try (var service = prepared(fake)) {
      assertEquals(
          ItemNamesException.Code.CORRUPT, code(service.search(pair, query, Optional.empty(), 2)));
    }
  }
}
