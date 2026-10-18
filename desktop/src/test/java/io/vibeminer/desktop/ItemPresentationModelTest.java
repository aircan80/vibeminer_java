package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.item.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ItemPresentationModelTest {
  static class Fake implements ItemProjectionService {
    int prepares, pages, lookups;
    ItemProjectionRequest request;
    Optional<ItemPageCursor> cursor;
    long id;
    CompletableFuture<PreparedItemProjection> preparing = new CompletableFuture<>();
    CompletableFuture<ItemPage> paging = new CompletableFuture<>();
    CompletableFuture<ItemLookupResult> looking = new CompletableFuture<>();

    public CompletionStage<PreparedItemProjection> prepare(ItemProjectionRequest r) {
      prepares++;
      request = r;
      return preparing;
    }

    public CompletionStage<ItemPage> page(
        PreparedItemProjection h, Optional<ItemPageCursor> c, int s) {
      pages++;
      cursor = c;
      return paging;
    }

    public CompletionStage<ItemLookupResult> lookup(PreparedItemProjection h, long id) {
      lookups++;
      this.id = id;
      return looking;
    }

    public void close() {
      throw new AssertionError("Borrowed service lifecycle belongs to bootstrap");
    }
  }

  static ItemPresentationModel model(Fake f) {
    var m = new ItemPresentationModel(f, Runnable::run, s -> {});
    var a = ItemPresentationFixture.handle().evidence().association();
    m.selectContext(a.descriptor().association().build(), "enUS", a);
    return m;
  }

  static ItemPhysicalRow row(long id) {
    var fields = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
    for (var field : ItemArtifactDefinition.Field.values())
      fields.add(
          new ItemPhysicalRow.IntegerCandidate(
              field,
              field.ordinal() + 1,
              32,
              ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
              field.ordinal() + 1,
              32,
              ItemArtifactDefinition.Sign.UNSIGNED));
    return new ItemPhysicalRow(id, 0, 0, fields);
  }

  static void ready(ItemPresentationModel m, Fake f) {
    m.prepare();
    f.preparing.complete(ItemPresentationFixture.handle());
  }

  @Test
  void selectingAndNavigationNeverPrepareAutomatically() {
    var f = new Fake();
    var m = model(f);
    assertEquals(0, f.prepares + f.pages + f.lookups);
    assertEquals(ItemPresentationModel.Stage.UNPREPARED, m.state().stage());
    m.prepare();
    assertEquals(1, f.prepares);
    assertEquals(0, f.pages);
    assertEquals(841626, f.request.source().association().fileDataId());
    f.preparing.complete(ItemPresentationFixture.handle());
    assertNotNull(m.state().handle());
    assertEquals(0, f.pages);
  }

  @Test
  void unsupportedLocaleOrMissingVerifiedReceiptCannotPrepare() {
    var f = new Fake();
    var m = model(f);
    var a = ItemPresentationFixture.handle().evidence().association();
    m.selectContext(a.descriptor().association().build(), "deDE", a);
    m.prepare();
    assertEquals(0, f.prepares);
    assertEquals(ItemPresentationModel.Stage.UNSUPPORTED, m.state().stage());
    m.selectContext(a.descriptor().association().build(), "enUS", null);
    m.prepare();
    assertEquals(0, f.prepares);
    assertNull(m.state().handle());
  }

  @Test
  void pageAndExactLookupStayScopedAndCopiesRemainUnindexed() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.firstPage();
    assertEquals(1, f.pages);
    assertTrue(f.cursor.isEmpty());
    var h = m.state().handle();
    var cursor =
        new ItemPageCursor(
            h.artifactId(),
            h.snapshotId(),
            h.sourceRole(),
            h.locale(),
            ItemPageCursor.QUERY_REVISION,
            25,
            m.pageSize());
    f.paging.complete(new ItemPage(h, List.of(row(25)), Optional.of(cursor)));
    m.selectRow(row(25));
    assertNotNull(m.state().selected());
    m.setInput("99");
    m.lookup();
    assertEquals(99, f.id);
    f.looking.complete(
        new ItemLookupResult(
            99,
            ItemLookupResult.Outcome.COPY_UNINDEXED,
            Optional.empty(),
            OptionalLong.of(25),
            h.coverage()));
    assertNull(m.state().selected());
    assertEquals(ItemLookupResult.Outcome.COPY_UNINDEXED, m.state().lookup().outcome());
    assertTrue(m.state().message().contains("not indexed"));
  }

  @Test
  void invalidUnsignedIdMakesNoQuery() {
    for (String id : List.of("", "0", "-1", "1.2", "1 2", "4294967296")) {
      var f = new Fake();
      var m = model(f);
      ready(m, f);
      m.setInput(id);
      m.lookup();
      assertEquals(0, f.lookups);
      assertNotNull(m.state().handle());
      assertEquals(ItemProjectionException.Code.INVALID_REQUEST, m.state().code());
    }
  }

  @Test
  void cancelAndLatePreparationCannotPublishReadyAfterSourceChange() {
    var f = new Fake();
    f.preparing =
        new CompletableFuture<>() {
          public boolean cancel(boolean x) {
            return false;
          }
        };
    var m = model(f);
    m.prepare();
    m.selectContext(null, "enUS", null);
    f.preparing.complete(ItemPresentationFixture.handle());
    assertNull(m.state().handle());
    assertEquals(ItemPresentationModel.Stage.NO_SOURCE, m.state().stage());
    var g = new Fake();
    var n = model(g);
    n.prepare();
    n.cancel();
    assertTrue(g.preparing.isCancelled());
    assertNull(n.state().handle());
    assertEquals(ItemPresentationModel.Stage.CANCELLED, n.state().stage());
  }

  @Test
  void corruptQueryRevokesCurrentReadinessAndRows() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.firstPage();
    f.paging.completeExceptionally(
        new ItemProjectionException(ItemProjectionException.Code.CORRUPT, "internal"));
    assertNull(m.state().handle());
    assertNull(m.state().page());
    assertNull(m.state().selected());
    assertFalse(m.state().message().contains("internal"));
  }

  @Test
  void staleCursorDoesNotRetainWrongPageAndAllowsExplicitFirstPage() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.firstPage();
    f.paging.completeExceptionally(
        new ItemProjectionException(ItemProjectionException.Code.STALE_CURSOR, "old"));
    assertNotNull(m.state().handle());
    assertNull(m.state().page());
    assertEquals(ItemProjectionException.Code.STALE_CURSOR, m.state().code());
  }

  @Test
  void queuedCompletionUsesUiExecutorAndCloseDoesNotCloseService() {
    var f = new Fake();
    Queue<Runnable> ui = new ArrayDeque<>();
    var m = new ItemPresentationModel(f, ui::add, s -> {});
    var a = ItemPresentationFixture.handle().evidence().association();
    m.selectContext(a.descriptor().association().build(), "enUS", a);
    m.prepare();
    f.preparing.complete(ItemPresentationFixture.handle());
    assertNull(m.state().handle());
    m.close();
    ui.remove().run();
    assertNull(m.state().handle());
  }

  @Test
  void unsupportedSourceFileAndSnapshotMismatchNeverPrepare() {
    var f = new Fake();
    var m = model(f);
    var a = ItemPresentationFixture.handle().evidence().association();
    var other =
        new io.vibeminer.domain.BuildSnapshot(
            "wow",
            "Fixture",
            "current",
            "us",
            "other",
            "c".repeat(32),
            "d".repeat(32),
            java.time.Instant.EPOCH,
            true);
    m.selectContext(other, "enUS", a);
    m.prepare();
    assertEquals(0, f.prepares);
    assertNull(m.state().source());
  }

  @Test
  void unsignedBoundaryIdAndScopedNoMatchStayDistinctFromFileId() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.setInput("4294967295");
    m.lookup();
    assertEquals(4294967295L, f.id);
    f.looking.complete(
        new ItemLookupResult(
            f.id,
            ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
            Optional.empty(),
            OptionalLong.empty(),
            m.state().handle().coverage()));
    assertTrue(m.state().message().startsWith("No match in indexed physical rows"));
    assertNull(m.state().selected());
  }

  @Test
  void nextPageUsesExactReturnedCursorAndOutOfOrderRowsRevokeReady() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    var h = m.state().handle();
    var c =
        new ItemPageCursor(
            h.artifactId(),
            h.snapshotId(),
            h.sourceRole(),
            h.locale(),
            ItemPageCursor.QUERY_REVISION,
            25,
            m.pageSize());
    m.firstPage();
    f.paging.complete(new ItemPage(h, List.of(row(25)), Optional.of(c)));
    f.paging = new CompletableFuture<>();
    m.nextPage();
    assertEquals(c, f.cursor.orElseThrow());
    f.paging.complete(new ItemPage(h, List.of(row(30), row(29)), Optional.empty()));
    assertEquals(ItemProjectionException.Code.CORRUPT, m.state().code());
    assertNull(m.state().handle());
  }

  @Test
  void laterQueryCompletionCannotReplaceNewDraftInput() {
    var f = new Fake();
    f.looking =
        new CompletableFuture<>() {
          public boolean cancel(boolean x) {
            return false;
          }
        };
    var m = model(f);
    ready(m, f);
    m.setInput("25");
    m.lookup();
    m.setInput("26");
    f.looking.complete(
        new ItemLookupResult(
            25,
            ItemLookupResult.Outcome.PHYSICAL_MATCH,
            Optional.of(row(25)),
            OptionalLong.empty(),
            m.state().handle().coverage()));
    assertEquals("26", m.state().input());
    assertNull(m.state().lookup());
  }

  @Test
  void returnedPreparationCannotExceedDisclosedPhysicalWorkBound() {
    var f = new Fake();
    var m = model(f);
    m.prepare();
    var h = ItemPresentationFixture.handle();
    f.preparing.complete(
        new PreparedItemProjection(
            h.definition(),
            h.evidence(),
            new PreparedItemProjection.Coverage(0, m.limits().maxPhysicalRows() + 1L, 1, 1)));
    assertNull(m.state().handle());
    assertEquals(ItemProjectionException.Code.LIMIT_EXCEEDED, m.state().code());
  }

  @Test
  void newDraftClearsInspectorSelectionButLabelsPreviousPageWithoutQuery() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.firstPage();
    f.paging.complete(new ItemPage(m.state().handle(), List.of(row(25)), Optional.empty()));
    m.selectRow(row(25));
    m.setInput("26");
    assertNull(m.state().selected());
    assertEquals(1, m.state().page().rows().size());
    assertTrue(m.state().mode().startsWith("Previous browse page"));
    assertEquals(0, f.lookups);
  }

  static ItemPageCursor adversarialCursor(PreparedItemProjection h, long after) {
    return new ItemPageCursor(
        h.artifactId(),
        h.snapshotId(),
        h.sourceRole(),
        h.locale(),
        ItemPageCursor.QUERY_REVISION,
        after,
        50);
  }

  @Test
  void rejectsCursorThatDoesNotAnchorLastVisibleRow() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    var h = m.state().handle();
    m.firstPage();
    f.paging.complete(new ItemPage(h, List.of(row(25)), Optional.of(adversarialCursor(h, 0))));
    assertEquals(ItemProjectionException.Code.CORRUPT, m.state().code());
  }

  @Test
  void rejectsNextPageRepeatingIdsBeforeRequestedCursor() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    var h = m.state().handle();
    m.firstPage();
    f.paging.complete(new ItemPage(h, List.of(row(25)), Optional.of(adversarialCursor(h, 25))));
    f.paging = new CompletableFuture<>();
    m.nextPage();
    f.paging.complete(new ItemPage(h, List.of(row(20)), Optional.empty()));
    assertEquals(ItemProjectionException.Code.CORRUPT, m.state().code());
  }

  @Test
  void rejectsEmptyPageWithNextCursor() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    var h = m.state().handle();
    m.firstPage();
    f.paging.complete(new ItemPage(h, List.of(), Optional.of(adversarialCursor(h, 25))));
    assertEquals(ItemProjectionException.Code.CORRUPT, m.state().code());
  }

  @Test
  void rejectsChangedCurrentSourceDependencyEvidence() {
    var f = new Fake();
    var m = model(f);
    var h = ItemPresentationFixture.handle();
    var a = h.evidence().association();
    var old = a.configuration();
    var cfg =
        new VerifiedSourceAlias(
            old.source(),
            new StoredObject(new ObjectDigest("7".repeat(64)), old.object().length()),
            old.evidence());
    var changed =
        new SelectedFileAssociation(a.descriptor(), cfg, a.root(), a.proof(), a.representation());
    var e = h.evidence();
    var proof =
        new ItemProjectionEvidence(
            changed, e.localVerification(), e.chunks(), e.windows(), e.fullDecoded());
    m.prepare();
    f.preparing.complete(new PreparedItemProjection(h.definition(), proof, h.coverage()));
    assertEquals(ItemProjectionException.Code.CORRUPT, m.state().code());
  }

  @Test
  void rejectsDuplicatePhysicalOrdinalAndMoreRowsThanDeclaredCoverage() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    var h = m.state().handle();
    m.firstPage();
    f.paging.complete(new ItemPage(h, List.of(row(10), row(20), row(30)), Optional.empty()));
    assertEquals(ItemProjectionException.Code.CORRUPT, m.state().code());
  }

  @Test
  void duplicatePhysicalCoordinatesAreRejectedEvenWithinCount() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    var h = m.state().handle();
    m.firstPage();
    f.paging.complete(new ItemPage(h, List.of(row(10), row(20)), Optional.empty()));
    assertEquals(ItemProjectionException.Code.CORRUPT, m.state().code());
    assertNull(m.state().page());
  }

  @Test
  void firstPageCannotBeEmptyForKnownNonemptyScope() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.firstPage();
    f.paging.complete(new ItemPage(m.state().handle(), List.of(), Optional.empty()));
    assertEquals(ItemProjectionException.Code.CORRUPT, m.state().code());
  }

  @Test
  void copyOutcomeCannotContradictZeroAdmittedCopyDeclarations() {
    var f = new Fake();
    var m = model(f);
    m.prepare();
    var original = ItemPresentationFixture.handle();
    var h =
        new PreparedItemProjection(
            original.definition(),
            original.evidence(),
            new PreparedItemProjection.Coverage(0, 2, 0, 1));
    f.preparing.complete(h);
    m.setInput("99");
    m.lookup();
    f.looking.complete(
        new ItemLookupResult(
            99,
            ItemLookupResult.Outcome.COPY_UNINDEXED,
            Optional.empty(),
            OptionalLong.of(25),
            h.coverage()));
    assertEquals(ItemProjectionException.Code.CORRUPT, m.state().code());
  }

  @Test
  void zeroPhysicalRecordCannotBypassPositiveItemIdQueryPolicy() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    m.firstPage();
    f.paging.complete(new ItemPage(m.state().handle(), List.of(row(0)), Optional.empty()));
    assertEquals(ItemProjectionException.Code.CORRUPT, m.state().code());
    assertNull(m.state().page());
  }

  @Test
  void confirmedRootDependencyFailureRevokesOnlyMatchingItemSnapshot() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    var original = m.state().handle();
    m.invalidateMetadataDependency(
        "0".repeat(64), io.vibeminer.application.content.ContentClaimException.Code.MISSING_LOCAL);
    assertSame(original, m.state().handle());
    m.invalidateMetadataDependency(
        original.snapshotId(),
        io.vibeminer.application.content.ContentClaimException.Code.MISSING_LOCAL);
    assertNull(m.state().handle());
    assertNull(m.state().page());
    assertNotNull(m.state().source());
    assertTrue(m.state().message().contains("root/configuration evidence needs re-verification"));
    assertEquals(0, f.pages + f.lookups);
  }

  @Test
  void repairRequiredRevokesOnlyCurrentReadinessWithoutClaimingMissingOrCorrupt() {
    var f = new Fake();
    var m = model(f);
    ready(m, f);
    var original = m.state().handle();
    m.invalidateForRepair("other-snapshot");
    assertSame(original, m.state().handle());
    m.invalidateForRepair(original.snapshotId());
    assertNull(m.state().handle());
    assertNotNull(m.state().source());
    assertEquals(ItemProjectionException.Code.NOT_READY, m.state().code());
    assertTrue(m.state().message().contains("component is not identified"));
    assertEquals(0, f.pages + f.lookups);
  }
}
