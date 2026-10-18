package io.vibeminer.infrastructure.item;

import io.vibeminer.application.itemnames.*;
import io.vibeminer.domain.item.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Non-FX authored-fixture smoke. Does not validate official root/EN semantics or live content. */
public final class ItemNamesFixtureSmoke {
  private static <T> T await(CompletionStage<T> result) throws Exception {
    return result.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private static void require(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }

  private static void failure(CompletionStage<?> result, ItemNamesException.Code code)
      throws Exception {
    try {
      await(result);
      throw new AssertionError("Expected " + code);
    } catch (ExecutionException e) {
      require(
          e.getCause() instanceof ItemNamesException n && n.code() == code,
          "Wrong typed names failure: " + e.getCause());
    }
  }

  public static void main(String[] args) throws Exception {
    Path root =
        args.length == 0
            ? Files.createTempDirectory("vibeminer-authored-names-")
            : Path.of(args[0]);
    String artifact;
    long rows;
    try (var fixture = new ItemNamesFixtureBackend(root, true)) {
      var item = await(fixture.itemService().prepare(fixture.itemRequest(0)));
      var pair = await(fixture.namesService().prepare(fixture.namesRequest(item, 0)));
      require(pair.item().equals(item), "Original Item proof changed");
      require(
          pair.item().evidence().fullDecoded().isEmpty()
              && pair.names().evidence().fullDecoded().isEmpty(),
          "Invented full decoded CKey");
      int projected = fixture.projects.get();
      var first = await(fixture.namesService().page(pair, Optional.empty(), 50));
      require(
          first.rows().size() == 50 && first.nextCursor().isPresent(),
          "Expected first50Item anchors");
      var next = await(fixture.namesService().page(pair, first.nextCursor(), 50));
      require(
          next.rows().size() == 11 && next.nextCursor().isEmpty(),
          "Expected remaining11Item anchors");
      require(
          await(fixture.namesService().lookup(pair, ItemNamesFixtureBackend.FIRST_ID))
                  .result()
                  .name()
                  .orElseThrow()
                  .outcome()
              == ItemNameMatch.Outcome.PHYSICAL_NAME,
          "Physical name unavailable");
      require(
          await(fixture.namesService().lookup(pair, ItemNamesFixtureBackend.EMPTY_ID))
              .result()
              .name()
              .orElseThrow()
              .candidate()
              .orElseThrow()
              .value()
              .isEmpty(),
          "Empty candidate became missing");
      require(
          await(fixture.namesService().lookup(pair, ItemNamesFixtureBackend.COPY_ID))
                  .result()
                  .name()
                  .orElseThrow()
                  .outcome()
              == ItemNameMatch.Outcome.COPY_UNINDEXED,
          "Sparse copy expanded");
      require(
          await(fixture.namesService().lookup(pair, ItemNamesFixtureBackend.MISSING_ID))
                  .result()
                  .name()
                  .orElseThrow()
                  .outcome()
              == ItemNameMatch.Outcome.NO_PHYSICAL_MATCH,
          "Scoped name nonmatch lost Item anchor");
      require(
          await(fixture.namesService().lookup(pair, ItemNamesFixtureBackend.SPARSE_ONLY_ID))
                  .result()
                  .item()
                  .outcome()
              == ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
          "Sparse-only row invented Item");
      require(fixture.projects.get() == projected, "Queries reprojected Sparse records");
      var otherItem = await(fixture.itemService().prepare(fixture.itemRequest(1)));
      var other = await(fixture.namesService().prepare(fixture.namesRequest(otherItem, 1)));
      require(
          other.names().artifactId().equals(pair.names().artifactId()),
          "Identical names duplicated across builds");
      require(
          fixture.nameArtifacts() == 1 && fixture.storedItemRows() == 61,
          "Unexpected duplicate artifact or Item rows");
      artifact = pair.names().artifactId();
      rows = fixture.storedNameRows();
      require(rows == ItemNamesFixtureData.physicalIds().size(), "Unexpected physical names count");
      fixture.removeSparseSource();
      failure(fixture.namesService().lookup(pair, 1001), ItemNamesException.Code.MISSING_LOCAL);
      require(
          await(fixture.itemService().page(item, Optional.empty(), 50)).rows().size() == 50,
          "Missing Sparse revoked independent Item");
      fixture.restoreSparseSource();
      pair = await(fixture.namesService().prepare(fixture.namesRequest(item, 0)));
      fixture.corruptItemSource();
      failure(fixture.namesService().lookup(pair, 1001), ItemNamesException.Code.CORRUPT);
      failure(fixture.namesService().lookup(pair, 1001), ItemNamesException.Code.NOT_READY);
      fixture.restoreItemSource();
      require(
          fixture.activeReaders.get() == 0 && fixture.outboundCalls() == 0,
          "Readers or transport leaked");
    }
    try (var fixture = new ItemNamesFixtureBackend(root, false)) {
      var item = await(fixture.itemService().prepare(fixture.itemRequest(0)));
      var pair = await(fixture.namesService().prepare(fixture.namesRequest(item, 0)));
      require(
          pair.names().artifactId().equals(artifact)
              && fixture.nameArtifacts() == 1
              && fixture.storedNameRows() == rows
              && fixture.storedItemRows() == 61,
          "Restart duplicated shared rows");
      int projected = fixture.projects.get();
      require(
          await(fixture.namesService().page(pair, Optional.empty(), 50)).rows().size() == 50,
          "Restart page unavailable");
      require(fixture.projects.get() == projected, "Restart query reprojected names");
      require(
          fixture.outboundCalls() == 0 && fixture.activeReaders.get() == 0,
          "Restart performed transport or leaked lease");
    }
    System.out.println(
        "PASS authored names: 61 Item anchors, 50 + 11 pages, distinct name outcomes, two-context"
            + " sharing, source failures, restart with zero HTTP; root/EN proof port only");
  }
}
