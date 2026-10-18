package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataRuntimeLocalPreparationTest {
  @TempDir Path directory;

  @Test
  void alphaSixLocalBuildIsImportedOnEntryAndRestartRemainsOffline() throws Exception {
    var original = MetadataRuntimeTest.event();
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      catalog.ensureSnapshot(c, original.builds().getFirst().build());
      c.commit();
    }
    var snapshot = original.builds().getFirst().build();
    new SqliteRetentionRepository(catalog)
        .pin(
            new RetentionScope(
                snapshot.snapshotId(),
                ScopeKind.ROOT_METADATA,
                List.of(
                    new ScopeRequirement(
                        "BUILD_CONFIG",
                        Optional.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty()),
                    new ScopeRequirement(
                        "ROOT_MANIFEST",
                        Optional.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty()))),
            CancellationToken.none());
    for (int attempt = 0; attempt < 2; attempt++) {
      var client = new MetadataRuntimeTest.Client();
      try (var runtime =
          MetadataRuntime.open(
              directory,
              () -> client,
              real -> real,
              real ->
                  (p, t) -> {
                    throw new AssertionError("Entry cannot capture metadata");
                  })) {
        var result =
            runtime
                .library()
                .prepareLocal("wow", Optional.empty())
                .completion()
                .toCompletableFuture()
                .join();
        assertTrue(result.progress().complete());
        assertEquals(attempt == 0 ? 1 : 0, result.progress().importedPairs());
        var page =
            runtime
                .library()
                .page(
                    new BuildLibraryQuery("wow", BuildLibraryProjection.Filter.ALL),
                    Optional.empty(),
                    25)
                .toCompletableFuture()
                .join();
        assertEquals(1, page.entries().size());
        var row = page.entries().getFirst().row();
        assertTrue(row.local().isLocal());
        assertFalse(row.seen().eligibleAsNew());
        assertEquals(BuildLibraryProjection.Advertisement.UNKNOWN, row.advertisement());
        assertEquals(snapshot.observedAt(), row.group().references().getFirst().observedAt());
        assertEquals(0, client.requests.get());
      }
    }
    try (var c = catalog.connect()) {
      assertEquals(snapshot, catalog.readSnapshot(c, snapshot.snapshotId()).orElseThrow());
    }
  }
}
