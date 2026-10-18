package io.vibeminer.desktop;

import static io.vibeminer.desktop.ItemNamesPresentationModelTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.itemnames.*;
import io.vibeminer.domain.item.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ItemNamesSearchPresentationModelTest {
  static class SearchFake extends Fake {
    int searches;
    ItemNameLiteralQuery query;
    Optional<ItemNamesSearchCursor> searchCursor;
    CompletableFuture<ItemNamesSearchPage> searching = new CompletableFuture<>();

    @Override
    public CompletionStage<ItemNamesSearchPage> search(
        PreparedItemNames p,
        ItemNameLiteralQuery q,
        Optional<ItemNamesSearchCursor> cursor,
        int size) {
      searches++;
      query = q;
      searchCursor = cursor;
      return searching;
    }
  }

  static ItemNamesPresentationModel prepared(SearchFake f) {
    var m = model(f);
    ready(m, f);
    return m;
  }

  static ItemNamesSearchPage result(ItemNameLiteralQuery q, List<NamedItemRow> rows) {
    return new ItemNamesSearchPage(PAIR, q, "a".repeat(64), rows, Optional.empty());
  }

  @Test
  void onlyExplicitSearchRunsAndPreservesLiteralWhitespaceUnicodeAndPunctuation() {
    var f = new SearchFake();
    var m = prepared(f);
    String text = " \t雪\n%_\\'😀 ";
    assertTrue(m.setSearchInput(text));
    assertEquals(0, f.searches);
    m.search();
    assertEquals(text, f.query.text());
    f.searching.complete(result(f.query, List.of()));
    assertEquals(text, m.state().executedQuery().text());
    assertEquals(0, m.state().searchPage().rows().size());
    assertEquals(ItemNamesPresentationModel.QueryMode.SEARCH, m.state().queryMode());
  }

  @Test
  void rejectedRawIngressCannotAuthorizeOldOrSilentlyFilteredText() {
    for (String raw : List.of("A\r\nB", "A\0B", "A\u000bB", "A\u000cB", "A\u007fB")) {
      var f = new SearchFake();
      var m = prepared(f);
      m.setSearchInput("old");
      assertFalse(m.setSearchInput(raw));
      assertEquals("old", m.state().searchInput());
      assertTrue(m.state().searchBlocked());
      m.search();
      assertEquals(0, f.searches);
      assertTrue(m.setSearchInput("old"));
      assertFalse(m.state().searchBlocked());
      m.search();
      assertEquals("old", f.query.text());
    }
  }

  @Test
  void domainLengthAndStrictScalarFailuresMakeNoQuery() {
    var f = new SearchFake();
    var m = prepared(f);
    for (String text : List.of("", "x".repeat(129), "\ud800", "\udc00")) {
      m.setSearchInput(text);
      m.search();
      assertEquals(0, f.searches);
      assertEquals(ItemNamesException.Code.INVALID_REQUEST, m.state().code());
    }
    m.setSearchInput(" ");
    m.search();
    assertEquals(" ", f.query.text());
  }

  @Test
  void clearReturnsBrowseWithNoAutomaticRequestAndLeavesExactIdIndependent() {
    var f = new SearchFake();
    var m = prepared(f);
    m.setInput("101");
    m.setSearchInput("name");
    m.search();
    f.searching.complete(
        result(f.query, List.of(row(101, 0, ItemNameMatch.Outcome.PHYSICAL_NAME, "name"))));
    m.clearSearch();
    assertEquals("", m.state().searchInput());
    assertNull(m.state().searchPage());
    assertNull(m.state().executedQuery());
    assertNull(m.state().selected());
    assertEquals("101", m.state().input());
    assertEquals(ItemNamesPresentationModel.QueryMode.BROWSE, m.state().queryMode());
    assertTrue(m.state().mode().contains("no query made"));
    assertEquals(1, f.searches);
    assertEquals(0, f.pages + f.lookups);
    m.lookup();
    assertEquals(1, f.lookups);
    assertEquals(ItemNamesPresentationModel.QueryMode.EXACT_ID, m.state().queryMode());
  }

  @Test
  void changedDraftKeepsPreviousResultsButClearsInspectorAndCannotContinue() {
    var f = new SearchFake();
    var m = prepared(f);
    m.setSearchInput("Moon");
    m.search();
    var r = row(101, 0, ItemNameMatch.Outcome.PHYSICAL_NAME, "Moon");
    f.searching.complete(result(f.query, List.of(r)));
    m.selectRow(r);
    m.setSearchInput("moon");
    assertNotNull(m.state().searchPage());
    assertNull(m.state().selected());
    assertTrue(m.state().mode().contains("Previous"));
    assertFalse(m.canContinueSearch());
    m.nextPage();
    assertEquals(1, f.searches);
  }

  @Test
  void falseMatchAndDifferentQueryOrGenerationCannotBePresented() {
    for (int bad = 0; bad < 3; bad++) {
      var f = new SearchFake();
      var m = prepared(f);
      m.setSearchInput("Moon");
      m.search();
      var q = bad == 1 ? new ItemNameLiteralQuery("Other") : f.query;
      var rows =
          List.of(row(101, 0, ItemNameMatch.Outcome.PHYSICAL_NAME, bad == 0 ? "moon" : "Moon"));
      var cursor =
          new ItemNamesSearchCursor(
              PAIR.item().artifactId(),
              PAIR.names().artifactId(),
              PAIR.snapshotId(),
              PAIR.item().sourceRole(),
              PAIR.names().sourceRole(),
              "enUS",
              q,
              ItemNamesSearchCursor.QUERY_REVISION,
              "b".repeat(64),
              101,
              m.pageSize());
      f.searching.complete(
          new ItemNamesSearchPage(
              PAIR, q, "a".repeat(64), rows, bad == 2 ? Optional.of(cursor) : Optional.empty()));
      assertEquals(ItemNamesException.Code.CORRUPT, m.state().code());
      assertNull(m.state().searchPage());
    }
  }

  @Test
  void staleSearchCursorPreservesPreparedPairAndRequiresExplicitFreshSearch() {
    var f = new SearchFake();
    var m = prepared(f);
    m.setSearchInput("Moon");
    m.search();
    f.searching.completeExceptionally(
        new ItemNamesException(ItemNamesException.Code.STALE_CURSOR, "private"));
    assertSame(PAIR, m.state().pair());
    assertNull(m.state().searchPage());
    assertNull(m.state().executedQuery());
    assertTrue(m.state().message().contains("beginning"));
    assertEquals(1, f.searches);
  }

  @Test
  void boundsOrCancellationNeverBecomeNoMatchAndNeverPublishPartialRows() {
    var f = new SearchFake();
    var m = prepared(f);
    m.setSearchInput("Moon");
    m.search();
    f.searching.completeExceptionally(
        new ItemNamesException(ItemNamesException.Code.LIMIT_EXCEEDED, "private"));
    assertNull(m.state().searchPage());
    assertSame(PAIR, m.state().pair());
    assertTrue(m.state().message().contains("incomplete"));
    assertFalse(m.state().message().contains("No matching"));
  }

  @Test
  void typingAndClearDetachLateSearchWithoutExecutingNewWork() {
    var f = new SearchFake();
    f.searching =
        new CompletableFuture<>() {
          public boolean cancel(boolean x) {
            return false;
          }
        };
    var m = prepared(f);
    m.setSearchInput("Moon");
    m.search();
    m.setSearchInput("Sun");
    f.searching.complete(
        result(f.query, List.of(row(101, 0, ItemNameMatch.Outcome.PHYSICAL_NAME, "Moon"))));
    assertNull(m.state().searchPage());
    assertEquals("Sun", m.state().searchInput());
    assertEquals(1, f.searches);
    m.clearSearch();
    assertEquals(1, f.searches);
    assertEquals(0, f.pages);
  }

  @Test
  void emptyVerifiedSearchIsScopedAndDoesNotClaimWholeBuildAbsence() {
    var f = new SearchFake();
    var m = prepared(f);
    m.setSearchInput("absent");
    m.search();
    f.searching.complete(result(f.query, List.of()));
    assertNotNull(m.state().searchPage());
    assertTrue(m.state().message().contains("admitted paired physical scope"));
    assertSame(PAIR, m.state().pair());
  }

  static PreparedItemNames widePair() {
    var old = PAIR.names();
    var d = old.definition();
    var i = d.interpretation();
    var definition =
        new SparseNameArtifactDefinition(
            d.sources(),
            new SparseNameArtifactDefinition.Interpretation(
                i.decoderRevision(),
                i.indexRevision(),
                i.tableHash(),
                i.layoutHash(),
                i.sectionOrdinal(),
                i.metadataPrefixBytes(),
                i.recordAreaOffset(),
                500,
                i.recordAreaOffset() + 500,
                i.suffixBytes(),
                i.schema(),
                i.locale(),
                i.optionsRevision(),
                i.proofSemantics()));
    var e = old.evidence();
    var evidence =
        new ItemProjectionEvidence(
            e.association(),
            e.localVerification(),
            e.chunks(),
            List.of(
                new ItemProjectionEvidence.DecodedWindow(0, 4040),
                new ItemProjectionEvidence.DecodedWindow(5252, 500),
                new ItemProjectionEvidence.DecodedWindow(5752, 36)),
            e.fullDecoded());
    var names =
        new PreparedSparseNames(
            definition, evidence, new PreparedSparseNames.Coverage(0, 60, 1, 48, 100));
    var item = PAIR.item();
    return new PreparedItemNames(
        new PreparedItemProjection(
            item.definition(), item.evidence(), new PreparedItemProjection.Coverage(0, 60, 1, 49)),
        names);
  }

  static NamedItemRow wideRow(long id, int ordinal) {
    var item = row(id, ordinal, ItemNameMatch.Outcome.NO_PHYSICAL_MATCH, "").item();
    return new NamedItemRow(
        item,
        new ItemNameMatch(
            id,
            ItemNameMatch.Outcome.PHYSICAL_NAME,
            Optional.of(new DisplayCandidate("A", id, ordinal, 0, 5252 + ordinal * 6, 6, 6)),
            OptionalLong.empty()));
  }

  @Test
  void continuationBindsExecutedQueryAndBothArtifactGenerations() {
    for (boolean changedGeneration : List.of(false, true)) {
      var wide = widePair();
      var f = new SearchFake();
      var m = model(f);
      m.selectContext(
          m.state().build(),
          "enUS",
          wide.item(),
          wide.names().evidence().association().descriptor());
      m.prepare();
      f.preparing.complete(wide);
      m.setSearchInput("A");
      m.search();
      var rows = new ArrayList<NamedItemRow>();
      for (int n = 0; n < 50; n++) rows.add(wideRow(101 + n, n));
      var next =
          new ItemNamesSearchCursor(
              wide.item().artifactId(),
              wide.names().artifactId(),
              wide.snapshotId(),
              wide.item().sourceRole(),
              wide.names().sourceRole(),
              "enUS",
              f.query,
              ItemNamesSearchCursor.QUERY_REVISION,
              "a".repeat(64),
              150,
              50);
      f.searching.complete(
          new ItemNamesSearchPage(wide, f.query, "a".repeat(64), rows, Optional.of(next)));
      assertTrue(m.canContinueSearch());
      m.setSearchInput("B");
      assertFalse(m.canContinueSearch());
      m.nextPage();
      assertEquals(1, f.searches);
      m.setSearchInput("A");
      assertTrue(m.canContinueSearch());
      f.searching = new CompletableFuture<>();
      m.nextPage();
      assertEquals(Optional.of(next), f.searchCursor);
      f.searching.complete(
          new ItemNamesSearchPage(
              wide,
              f.query,
              (changedGeneration ? "b" : "a").repeat(64),
              List.of(wideRow(151, 50)),
              Optional.empty()));
      if (changedGeneration) {
        assertEquals(ItemNamesException.Code.CORRUPT, m.state().code());
        assertNull(m.state().searchPage());
      } else {
        assertEquals(151, m.state().searchPage().rows().getFirst().item().recordId());
        assertFalse(m.canContinueSearch());
      }
    }
  }

  @Test
  void reentrantServiceActionCannotBindLateConsumerAfterContextClearOrCancel() {
    for (String action : List.of("prepare", "search", "lookup", "close")) {
      var reference = new java.util.concurrent.atomic.AtomicReference<ItemNamesPresentationModel>();
      var f =
          new SearchFake() {
            @Override
            public CompletionStage<PreparedItemNames> prepare(ItemNamesRequest request) {
              var result = super.prepare(request);
              if (action.equals("prepare"))
                reference
                    .get()
                    .selectContext(
                        reference.get().state().build(),
                        "deDE",
                        PAIR.item(),
                        PAIR.names().evidence().association().descriptor());
              return result;
            }

            @Override
            public CompletionStage<ItemNamesSearchPage> search(
                PreparedItemNames pair,
                ItemNameLiteralQuery query,
                Optional<ItemNamesSearchCursor> cursor,
                int size) {
              var result = super.search(pair, query, cursor, size);
              if (action.equals("close")) reference.get().close();
              else reference.get().clearSearch();
              return result;
            }

            @Override
            public CompletionStage<ItemNamesLookup> lookup(PreparedItemNames pair, long id) {
              var result = super.lookup(pair, id);
              reference.get().cancel();
              return result;
            }
          };
      var m = model(f);
      reference.set(m);
      if (action.equals("prepare")) {
        m.prepare();
        assertTrue(f.preparing.isCancelled());
        assertEquals("deDE", m.state().locale());
      } else {
        ready(m, f);
        if (action.equals("search") || action.equals("close")) {
          m.setSearchInput("Moon");
          m.search();
          assertTrue(f.searching.isCancelled());
          if (action.equals("search"))
            assertEquals(ItemNamesPresentationModel.QueryMode.BROWSE, m.state().queryMode());
          else assertNull(m.state().pair());
        } else {
          m.setInput("101");
          m.lookup();
          assertTrue(f.looking.isCancelled());
        }
      }
      assertNull(m.state().searchPage());
      assertNull(m.state().lookup());
    }
  }
}
