package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.SourceDocument;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class GuidedDiscoveryLocalPreparationTest {
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  static final List<GuidedDiscoveryPane.ProductOption> PRODUCTS =
      List.of(
          new GuidedDiscoveryPane.ProductOption("wow", "Retail"),
          new GuidedDiscoveryPane.ProductOption("wowt", "PTR"));

  static final class Preparation implements BuildLibraryService.LocalPreparationOperation {
    final String product;
    final Optional<LegacyBuildObservationImporter.Cursor> cursor;
    final CompletableFuture<BuildLibraryLocalPreparationResult> future = new CompletableFuture<>();
    int cancelled;

    Preparation(String p, Optional<LegacyBuildObservationImporter.Cursor> c) {
      product = p;
      cursor = c;
    }

    public CompletionStage<BuildLibraryLocalPreparationResult> completion() {
      return future;
    }

    public void requestCancellation() {
      cancelled++;
    }
  }

  static final class Service implements BuildLibraryService {
    final GuidedDiscoveryPresentationModelTest.Service delegate =
        new GuidedDiscoveryPresentationModelTest.Service();
    final ArrayDeque<Preparation> preparations = new ArrayDeque<>();
    RuntimeException synchronousFailure;

    public LocalPreparationOperation prepareLocal(
        String p, Optional<LegacyBuildObservationImporter.Cursor> c) {
      if (synchronousFailure != null) throw synchronousFailure;
      var op = new Preparation(p, c);
      preparations.add(op);
      return op;
    }

    public RefreshOperation refreshProduct(String p) {
      return delegate.refreshProduct(p);
    }

    public CompletionStage<BuildLibraryPage> page(
        BuildLibraryQuery q, Optional<BuildLibraryCursor> c, int size) {
      return delegate.page(q, c, size);
    }

    public CompletionStage<BuildLibraryPage.Entry> select(String id) {
      return delegate.select(id);
    }

    public void close() {
      delegate.close();
    }
  }

  static final class Harness {
    final Service service = new Service();
    final GuidedDiscoveryPresentationModel model =
        new GuidedDiscoveryPresentationModel(
            service,
            PRODUCTS,
            Runnable::run,
            s -> {},
            e -> new GuidedDiscoveryPresentationModel.OpenAdmission(true, ""),
            e -> {},
            e -> {});

    BuildLibraryPage page() {
      var req = service.delegate.pages.remove();
      var page = new BuildLibraryPage(req.query(), 25, 1, List.of(), Optional.empty());
      req.future().complete(page);
      return page;
    }
  }

  static LegacyBuildObservationImporter.Batch batch(String p, boolean complete, String cursor) {
    return new LegacyBuildObservationImporter.Batch(
        p,
        1,
        1,
        0,
        complete
            ? Optional.empty()
            : Optional.of(new LegacyBuildObservationImporter.Cursor(p, cursor.repeat(64))),
        complete);
  }

  static void complete(Preparation op, boolean complete) {
    op.future.complete(
        new BuildLibraryLocalPreparationResult(batch(op.product, complete, "a"), 1, false));
  }

  static BuildLibraryRefreshResult refresh(String product) {
    var v =
        new DiscoveryEvent.ParsedSource(
            new SourceDocument(
                "https://us.version.battle.net/v2/products/" + product + "/versions",
                NOW,
                "Region\n".getBytes(StandardCharsets.UTF_8)),
            List.of("Region"),
            Optional.empty());
    var d =
        new DiscoveryEvent.ParsedSource(
            new SourceDocument(
                "https://us.version.battle.net/v2/products/" + product + "/cdns",
                NOW,
                "Name\n".getBytes(StandardCharsets.UTF_8)),
            List.of("Name"),
            Optional.empty());
    return new BuildLibraryRefreshResult(
        new BuildLibraryProductCheckTicket(UUID.randomUUID(), product, 1, NOW),
        new BuildLibraryProductObservation(
            product, NOW, "test-v1", v, d, List.of(), List.of(), Optional.empty()),
        List.of(),
        List.of(),
        false);
  }

  @Test
  void restorePreparesLocalBeforeFirstPageAndNeverRequestsNetwork() {
    var h = new Harness();
    h.model.restore("wow", GuidedDiscoveryPane.Filter.ALL);
    assertTrue(h.service.delegate.pages.isEmpty());
    assertTrue(h.service.delegate.refreshes.isEmpty());
    complete(h.service.preparations.remove(), true);
    assertTrue(h.service.delegate.refreshes.isEmpty());
    assertSame(h.page(), h.model.state().page());
    assertTrue(h.service.delegate.refreshes.isEmpty());
  }

  @Test
  void explicitProductSelectionPresentsLocalPageBeforeNetworkAndKeepsItOnFailure() {
    var h = new Harness();
    h.model.selectProduct("wow");
    complete(h.service.preparations.remove(), true);
    assertTrue(h.service.delegate.refreshes.isEmpty());
    var local = h.page();
    assertSame(local, h.model.state().page());
    assertEquals(List.of("wow"), h.service.delegate.refreshProducts);
    h.service
        .delegate
        .refreshes
        .remove()
        .future
        .completeExceptionally(new IllegalStateException("secret route"));
    assertSame(local, h.model.state().page());
    h.page();
    assertFalse(h.model.state().error().contains("secret"));
  }

  @Test
  void typedMigrationFailureStillShowsPartialLocalAndNeedsExplicitRetry() {
    var h = new Harness();
    h.model.selectProduct("wow");
    var op = h.service.preparations.remove();
    var failed =
        new LegacyBuildImportException(
            LegacyBuildImportException.Code.INCOMPATIBLE_LEGACY,
            "private provenance",
            batch("wow", false, "b"),
            null);
    op.future.completeExceptionally(failed);
    h.page();
    assertSame(failed, h.model.localPreparationFailure().orElseThrow());
    assertFalse(h.model.state().error().isBlank());
    assertTrue(h.service.delegate.refreshes.isEmpty());
    h.model.refresh();
    var retry = h.service.preparations.remove();
    assertEquals("b".repeat(64), retry.cursor.orElseThrow().afterSnapshotId());
    complete(retry, true);
    h.page();
    assertEquals(1, h.service.delegate.refreshes.size());
  }

  @Test
  void incompletePreparationRemainsVisibleAfterSuccessfulNetworkAndResumesItsCursor() {
    var h = new Harness();
    h.model.selectProduct("wow");
    complete(h.service.preparations.remove(), false);
    h.page();
    h.service.delegate.refreshes.remove().future.complete(refresh("wow"));
    h.page();
    assertTrue(h.model.state().error().contains("incomplete"));
    h.model.refresh();
    assertEquals(
        "a".repeat(64), h.service.preparations.remove().cursor.orElseThrow().afterSnapshotId());
  }

  @Test
  void stalePreparationCannotPageOrRefreshAndActualOperationIsCancelled() {
    var h = new Harness();
    h.model.selectProduct("wow");
    var a = h.service.preparations.remove();
    h.model.selectProduct("wowt");
    var b = h.service.preparations.remove();
    assertEquals(1, a.cancelled);
    complete(a, true);
    assertTrue(h.service.delegate.pages.isEmpty());
    assertTrue(h.service.delegate.refreshes.isEmpty());
    complete(b, true);
    h.page();
    assertEquals(List.of("wowt"), h.service.delegate.refreshProducts);
  }

  @Test
  void cancelledCommittedPreparationShowsLocalWithoutNetworkAndCloseRetiresOutstanding() {
    var h = new Harness();
    h.model.selectProduct("wow");
    var op = h.service.preparations.remove();
    op.future.complete(new BuildLibraryLocalPreparationResult(batch("wow", false, "c"), 1, true));
    h.page();
    assertTrue(h.service.delegate.refreshes.isEmpty());
    assertTrue(h.model.state().error().contains("stopped"));
    h.model.refresh();
    var next = h.service.preparations.remove();
    h.model.close();
    assertEquals(1, next.cancelled);
    complete(next, true);
    assertTrue(h.service.delegate.pages.isEmpty());
  }

  @Test
  void explicitRemoteRetryCannotBePermanentlyBlockedByBadLegacyEvidence() {
    var h = new Harness();
    h.model.selectProduct("wow");
    var failure =
        new LegacyBuildImportException(
            LegacyBuildImportException.Code.CORRUPT_LEGACY,
            "bad old receipt",
            batch("wow", false, "b"),
            null);
    h.service.preparations.remove().future.completeExceptionally(failure);
    h.page();
    assertTrue(h.service.delegate.refreshes.isEmpty());
    h.model.refresh();
    h.service.preparations.remove().future.completeExceptionally(failure);
    h.page();
    assertEquals(1, h.service.delegate.refreshes.size());
    h.service.delegate.refreshes.remove().future.complete(refresh("wow"));
    h.page();
    assertSame(failure, h.model.localPreparationFailure().orElseThrow());
    assertFalse(h.model.state().error().isBlank());
  }

  @Test
  void synchronousPreparationFailureAllowsExplicitRemoteRetryAfterLocalPage() {
    var h = new Harness();
    var failure =
        new LegacyBuildImportException(
            LegacyBuildImportException.Code.STORAGE_UNAVAILABLE,
            "private disk detail",
            batch("wow", false, "b"),
            null);
    h.service.synchronousFailure = failure;
    h.model.selectProduct("wow");
    h.page();
    assertTrue(h.service.delegate.refreshes.isEmpty());
    h.model.refresh();
    assertTrue(h.service.delegate.refreshes.isEmpty());
    h.page();
    assertEquals(1, h.service.delegate.refreshes.size());
    h.service.delegate.refreshes.remove().future.complete(refresh("wow"));
    h.page();
    assertSame(failure, h.model.localPreparationFailure().orElseThrow());
    assertFalse(h.model.state().error().contains("private"));
    assertFalse(h.model.state().error().isBlank());
  }
}
