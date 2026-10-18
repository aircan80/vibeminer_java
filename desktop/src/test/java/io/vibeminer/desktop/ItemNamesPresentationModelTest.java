package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.itemnames.*;
import io.vibeminer.domain.item.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ItemNamesPresentationModelTest {
  static final PreparedItemNames PAIR =
      new PreparedItemNames(
          ItemNamesPresentationFixture.item(Instant.EPOCH),
          ItemNamesPresentationFixture.names(Instant.EPOCH));

  static class Fake implements ItemNamesService {
    int prepares, pages, lookups;
    ItemNamesRequest request;
    Optional<ItemNamesCursor> cursor;
    CompletableFuture<PreparedItemNames> preparing = new CompletableFuture<>();
    CompletableFuture<ItemNamesPage> paging = new CompletableFuture<>();
    CompletableFuture<ItemNamesLookup> looking = new CompletableFuture<>();

    public CompletionStage<PreparedItemNames> prepare(ItemNamesRequest r) {
      prepares++;
      request = r;
      return preparing;
    }

    public CompletionStage<ItemNamesPage> page(
        PreparedItemNames p, Optional<ItemNamesCursor> c, int n) {
      pages++;
      cursor = c;
      return paging;
    }

    public CompletionStage<ItemNamesLookup> lookup(PreparedItemNames p, long id) {
      lookups++;
      return looking;
    }

    public void close() {
      throw new AssertionError("Borrowed service");
    }
  }

  static ItemNamesPresentationModel model(Fake f) {
    var m = new ItemNamesPresentationModel(f, Runnable::run, s -> {});
    m.selectContext(
        PAIR.item().evidence().association().descriptor().association().build(),
        "enUS",
        PAIR.item(),
        PAIR.names().evidence().association().descriptor());
    return m;
  }

  static void ready(ItemNamesPresentationModel m, Fake f) {
    m.prepare();
    f.preparing.complete(PAIR);
  }

  static NamedItemRow row(long id, int ordinal, ItemNameMatch.Outcome outcome, String text) {
    var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
    for (var binding : PAIR.item().definition().interpretation().schema().fields())
      values.add(
          new ItemPhysicalRow.IntegerCandidate(
              binding.field(),
              1,
              binding.candidateBits(),
              ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
              1,
              binding.candidateBits(),
              binding.sign()));
    var item = new ItemPhysicalRow(id, ordinal, 0, values);
    var name =
        new ItemNameMatch(
            id,
            outcome,
            outcome == ItemNameMatch.Outcome.PHYSICAL_NAME
                ? Optional.of(
                    new DisplayCandidate(text, id, ordinal, 0, 5252 + ordinal * 20, 20, 20))
                : Optional.empty(),
            outcome == ItemNameMatch.Outcome.COPY_UNINDEXED
                ? OptionalLong.of(99)
                : OptionalLong.empty());
    return new NamedItemRow(item, name);
  }

  @Test
  void contextNeverPreparesOrQueriesAndMissingPrerequisiteStaysUnready() {
    var f = new Fake();
    var m = model(f);
    assertNull(m.state().pair());
    assertEquals(0, f.prepares + f.pages + f.lookups);
    m.selectContext(m.state().build(), "enUS", PAIR.item(), null);
    m.prepare();
    assertEquals(0, f.prepares);
    assertEquals(ItemNamesPresentationModel.Stage.NO_SOURCE, m.state().stage());
  }

  @Test
  void explicitPreparePreservesItemAnchorAndDoesNotAutoBrowse() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    assertSame(PAIR.item(), f.request.item());
    assertEquals(PAIR, m.state().pair());
    assertEquals(0, f.pages + f.lookups);
    m.firstPage();
    f.paging.complete(
        new ItemNamesPage(
            PAIR,
            List.of(
                row(101, 0, ItemNameMatch.Outcome.PHYSICAL_NAME, ""),
                row(102, 1, ItemNameMatch.Outcome.COPY_UNINDEXED, "")),
            Optional.empty()));
    assertEquals("", m.state().page().rows().getFirst().name().candidate().orElseThrow().value());
    assertEquals(2, m.state().page().rows().size());
    assertEquals(
        ItemNameMatch.Outcome.COPY_UNINDEXED, m.state().page().rows().getLast().name().outcome());
  }

  @Test
  void positiveIdValidationMakesNoQueryAndMaxIdRemainsExact() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    for (var bad : List.of("0", "-1", "4294967296", "name", "1 2", "")) {
      m.setInput(bad);
      m.lookup();
      assertEquals(0, f.lookups);
      assertEquals(ItemNamesException.Code.INVALID_REQUEST, m.state().code());
    }
    m.setInput("4294967295");
    m.lookup();
    assertEquals(1, f.lookups);
    f.looking.complete(
        new ItemNamesLookup(
            PAIR,
            new JoinedItemLookup(
                new ItemLookupResult(
                    4294967295L,
                    ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
                    Optional.empty(),
                    OptionalLong.empty(),
                    PAIR.item().coverage()),
                Optional.empty())));
    assertTrue(m.state().message().contains("scope"));
    assertNull(m.state().selected());
  }

  @Test
  void lookupKeepsItemRowWhenSparseHasNoPhysicalMatch() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.setInput("101");
    m.lookup();
    var r = row(101, 0, ItemNameMatch.Outcome.NO_PHYSICAL_MATCH, "");
    f.looking.complete(
        new ItemNamesLookup(
            PAIR,
            new JoinedItemLookup(
                new ItemLookupResult(
                    101,
                    ItemLookupResult.Outcome.PHYSICAL_MATCH,
                    Optional.of(r.item()),
                    OptionalLong.empty(),
                    PAIR.item().coverage()),
                Optional.of(r.name()))));
    assertEquals(r, m.state().selected());
    assertEquals(4, m.state().selected().item().values().size());
  }

  @Test
  void typedPairFailureRevokesJoinedReadyButRetainsHistoricalSources() {
    for (var code :
        List.of(ItemNamesException.Code.MISSING_LOCAL, ItemNamesException.Code.CORRUPT)) {
      var f = new Fake();
      var m = model(f);
      ready(m, f);
      m.firstPage();
      f.paging.completeExceptionally(
          new CompletionException(new ItemNamesException(code, "secret/path")));
      assertEquals(code, m.state().code());
      assertNull(m.state().pair());
      assertSame(PAIR, m.state().historicalPair());
      assertSame(PAIR.item(), m.state().item());
      assertFalse(m.state().message().contains("secret/path"));
      m.setInput("102");
      assertEquals(code, m.state().code());
      assertTrue(m.state().message().contains("evidence"));
      m.firstPage();
      assertEquals(1, f.pages);
    }
  }

  @Test
  void cancellationRetainsIndependentItemAndIgnoresLatePair() {
    var f = new Fake();
    f.preparing =
        new CompletableFuture<>() {
          public boolean cancel(boolean b) {
            return false;
          }
        };
    var m = model(f);
    m.prepare();
    m.cancel();
    f.preparing.complete(PAIR);
    assertNull(m.state().pair());
    assertEquals(ItemNamesPresentationModel.Stage.CANCELLED, m.state().stage());
    assertSame(PAIR.item(), m.state().item());
    m.close();
  }

  @Test
  void localeChangeClearsReadyHistoryAndLateResult() {
    var f = new Fake();
    f.paging =
        new CompletableFuture<>() {
          public boolean cancel(boolean b) {
            return false;
          }
        };
    var m = model(f);
    ready(m, f);
    m.firstPage();
    m.selectContext(
        m.state().build(), "deDE", PAIR.item(), PAIR.names().evidence().association().descriptor());
    f.paging.complete(
        new ItemNamesPage(
            PAIR,
            List.of(row(101, 0, ItemNameMatch.Outcome.PHYSICAL_NAME, "Moon")),
            Optional.empty()));
    assertNull(m.state().pair());
    assertNull(m.state().page());
    assertNull(m.state().historicalPair());
    assertEquals(ItemNamesPresentationModel.Stage.UNSUPPORTED, m.state().stage());
  }

  @Test
  void draftChangePreservesOldPageButClearsSelectionWithoutQuery() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.firstPage();
    var r = row(101, 0, ItemNameMatch.Outcome.PHYSICAL_NAME, "雪");
    f.paging.complete(new ItemNamesPage(PAIR, List.of(r), Optional.empty()));
    m.selectRow(r);
    m.setInput("102");
    assertNull(m.state().selected());
    assertNotNull(m.state().page());
    assertTrue(m.state().mode().contains("Previous"));
    assertEquals(0, f.lookups);
  }

  @Test
  void duplicatePhysicalRowsAreRejectedAsCorrupt() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.firstPage();
    f.paging.complete(
        new ItemNamesPage(
            PAIR,
            List.of(
                row(101, 0, ItemNameMatch.Outcome.PHYSICAL_NAME, "A"),
                row(102, 0, ItemNameMatch.Outcome.PHYSICAL_NAME, "B")),
            Optional.empty()));
    assertEquals(ItemNamesException.Code.CORRUPT, m.state().code());
    assertNull(m.state().page());
  }

  @Test
  void nonAnchoredCursorAndEmptyFirstPageAreRejected() {
    for (boolean empty : List.of(true, false)) {
      var f = new Fake();
      var m = model(f);
      ready(m, f);
      m.firstPage();
      var c =
          new ItemNamesCursor(
              PAIR.item().artifactId(),
              PAIR.names().artifactId(),
              PAIR.snapshotId(),
              PAIR.item().sourceRole(),
              PAIR.names().sourceRole(),
              "enUS",
              ItemNamesCursor.QUERY_REVISION,
              ItemNamesCursor.OPTIONS_REVISION,
              99,
              m.pageSize());
      f.paging.complete(
          new ItemNamesPage(
              PAIR,
              empty ? List.of() : List.of(row(101, 0, ItemNameMatch.Outcome.PHYSICAL_NAME, "A")),
              Optional.of(c)));
      assertEquals(ItemNamesException.Code.CORRUPT, m.state().code());
    }
  }

  @Test
  void unsupportedProductAndWrongPreparedItemNeverReachService() {
    var f = new Fake();
    var m = model(f);
    var b = m.state().build();
    var other =
        new io.vibeminer.domain.BuildSnapshot(
            "wowt",
            b.editionLabel(),
            b.channel(),
            b.region(),
            b.versionLabel(),
            b.buildConfig(),
            b.cdnConfig(),
            b.observedAt(),
            true);
    m.selectContext(other, "enUS", PAIR.item(), PAIR.names().evidence().association().descriptor());
    m.prepare();
    assertEquals(0, f.prepares);
  }

  @Test
  void preparationPreservesOriginalProofButAllowsOnlyFreshPhysicalTimestamp() {
    for (boolean changedProof : List.of(true, false)) {
      var f = new Fake();
      var m = model(f);
      m.prepare();
      var old = PAIR.item();
      var e = old.evidence();
      var updated =
          new ItemProjectionEvidence(
              e.association(),
              new ItemProjectionEvidence.LocalVerification(
                  e.localVerification().object(),
                  Instant.EPOCH.plusSeconds(10),
                  e.localVerification().verifierRevision()),
              e.chunks(),
              changedProof
                  ? List.of(new ItemProjectionEvidence.DecodedWindow(7151, 2129288))
                  : e.windows(),
              e.fullDecoded());
      var returned =
          new PreparedItemNames(
              new PreparedItemProjection(old.definition(), updated, old.coverage()), PAIR.names());
      f.preparing.complete(returned);
      if (changedProof) {
        assertEquals(ItemNamesException.Code.CORRUPT, m.state().code());
        assertNull(m.state().pair());
      } else {
        assertEquals(returned, m.state().pair());
        assertSame(e.association(), m.state().pair().item().evidence().association());
      }
    }
  }

  @Test
  void nextPageBindsBothArtifactsAndRejectsRepeatedFirstPage() {
    var old = PAIR.item();
    var wide =
        new PreparedItemNames(
            new PreparedItemProjection(
                old.definition(),
                old.evidence(),
                new PreparedItemProjection.Coverage(0, 60, 1, 49)),
            PAIR.names());
    var f = new Fake();
    var m = new ItemNamesPresentationModel(f, Runnable::run, state -> {});
    m.selectContext(
        old.evidence().association().descriptor().association().build(),
        "enUS",
        wide.item(),
        wide.names().evidence().association().descriptor());
    m.prepare();
    f.preparing.complete(wide);
    m.firstPage();
    var rows = new ArrayList<NamedItemRow>();
    for (int n = 0; n < 50; n++)
      rows.add(row(101 + n, n, ItemNameMatch.Outcome.NO_PHYSICAL_MATCH, ""));
    var next =
        new ItemNamesCursor(
            wide.item().artifactId(),
            wide.names().artifactId(),
            wide.snapshotId(),
            wide.item().sourceRole(),
            wide.names().sourceRole(),
            "enUS",
            ItemNamesCursor.QUERY_REVISION,
            ItemNamesCursor.OPTIONS_REVISION,
            150,
            m.pageSize());
    f.paging.complete(new ItemNamesPage(wide, rows, Optional.of(next)));
    assertNotNull(m.state().page());
    f.paging = new CompletableFuture<>();
    m.nextPage();
    assertEquals(Optional.of(next), f.cursor);
    f.paging.complete(new ItemNamesPage(wide, List.of(rows.getFirst()), Optional.empty()));
    assertEquals(ItemNamesException.Code.CORRUPT, m.state().code());
    assertNull(m.state().page());
  }

  @Test
  void queryCannotSubstituteAnotherSourcePairOrRequestedId() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.setInput("101");
    m.lookup();
    f.looking.complete(
        new ItemNamesLookup(
            PAIR,
            new JoinedItemLookup(
                new ItemLookupResult(
                    102,
                    ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
                    Optional.empty(),
                    OptionalLong.empty(),
                    PAIR.item().coverage()),
                Optional.empty())));
    assertEquals(ItemNamesException.Code.CORRUPT, m.state().code());
    assertNull(m.state().lookup());
  }

  @Test
  void dependencyInvalidationIsExactBuildScopedAndDoesNotTouchIndependentItem() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.invalidateDependency("other-snapshot");
    assertEquals(PAIR, m.state().pair());
    m.invalidateDependency(PAIR.snapshotId());
    assertNull(m.state().pair());
    assertSame(PAIR, m.state().historicalPair());
    assertSame(PAIR.item(), m.state().item());
    assertEquals(0, f.pages + f.lookups);
  }

  @Test
  void closedQueuedCompletionCannotPublishAndBorrowedServiceRemainsOpen() {
    var f = new Fake();
    var queue = new ArrayDeque<Runnable>();
    var seen = new ArrayList<ItemNamesPresentationModel.State>();
    var m = new ItemNamesPresentationModel(f, queue::add, seen::add);
    m.selectContext(
        PAIR.item().evidence().association().descriptor().association().build(),
        "enUS",
        PAIR.item(),
        PAIR.names().evidence().association().descriptor());
    m.prepare();
    f.preparing.complete(PAIR);
    int count = seen.size();
    m.close();
    queue.remove().run();
    assertNull(m.state().pair());
    assertEquals(count, seen.size());
  }

  @Test
  void oversizedConsumedChunkCannotBecomePreparedThroughPortResult() {
    var f = new Fake();
    var m = model(f);
    m.prepare();
    var n = PAIR.names();
    var e = n.evidence();
    var a = e.association();
    var old = a.proof();
    var proof =
        new io.vibeminer.domain.selectedfile.FileAssociationProof(
            old.rootSource(),
            old.encodingBootstrapSource(),
            old.verifierRevision(),
            old.proofDigest(),
            OptionalLong.of(5L << 20),
            old.authenticatedCandidates());
    var association =
        new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
            a.descriptor(), a.configuration(), a.root(), proof, a.representation());
    var evidence =
        new ItemProjectionEvidence(
            association,
            e.localVerification(),
            List.of(
                new ItemProjectionEvidence.ChunkAttestation(
                    0, 8, 738336, "d".repeat(32), 0, 5L << 20, "chunk-v1")),
            e.windows(),
            e.fullDecoded());
    f.preparing.complete(
        new PreparedItemNames(
            PAIR.item(), new PreparedSparseNames(n.definition(), evidence, n.coverage())));
    assertEquals(ItemNamesException.Code.CORRUPT, m.state().code());
    assertNull(m.state().pair());
  }
}
