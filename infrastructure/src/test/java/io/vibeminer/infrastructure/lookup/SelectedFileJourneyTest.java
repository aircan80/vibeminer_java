package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Full real-layer journey; only official HTTP response delivery is synthetic. (#76) */
class SelectedFileJourneyTest {
  @TempDir Path directory;

  @Test
  void knownEncodingRetainsOnceAcrossBuildsAndReopensWithoutHttp() throws Exception {
    journey(false);
  }

  @Test
  void unknownEncodingDeclarationStaysUnknownThroughKnownCostRetentionAndRestart()
      throws Exception {
    journey(true);
  }

  private void journey(boolean unknown) throws Exception {
    var data = directory.resolve("objects");
    var database = directory.resolve("catalog.sqlite");
    SelectedFileAssociation first;
    SelectedFileAssociation second;
    SelectedFileHttpFixture fixture;
    try (var setup = new OfficialSelectedFileGatewayTest.Setup(data, unknown)) {
      fixture = setup.fixture;
      var sql = new SqliteCatalog(database);
      var builds = new SqliteBuildObjectCatalog(sql, setup.store);
      register(builds, setup.build, setup.config, setup.root);
      long before = physicalObjects(data);
      try (var service = service(setup.gateway, sql, setup.store)) {
        var candidates =
            await(
                service.planCandidates(setup.build, setup.variant, SelectedFileBudgets.defaults()));
        var choice = OfficialSelectedFileGatewayTest.choice(candidates, fixture.selectedEKey);
        assertEquals(unknown, choice.candidate().encodedBytes().isEmpty());
        var consent = await(service.probeLoose(choice)).plan().orElseThrow();
        assertEquals(fixture.selected.length, consent.acquisition().orElseThrow().encodedBytes());
        assertEquals(fixture.selected.length, consent.cost().encodedTransferBytes());
        first = await(service.retain(consent));
        assertEquals(before + 1, physicalObjects(data));
        assertEquals(
            first,
            await(service.inspectOffline(first.descriptor(), SelectedFileBudgets.defaults())));
        long payloadRequests = payloadRequests(fixture);
        assertEquals(1, payloadRequests);

        var next = fixture.build("second-observed-build");
        register(builds, next, setup.config, setup.root);
        var v = setup.variant;
        var nextVariant =
            new FileVariantAssociation(
                next,
                v.rootSource(),
                v.fileDataId(),
                v.contentKey(),
                v.localeFlags(),
                v.contentFlags(),
                v.rawFlagsFirst(),
                v.rawFlagsSecond(),
                v.rawFlagsThird(),
                v.nameHash());
        var nextCandidates =
            await(service.planCandidates(next, nextVariant, SelectedFileBudgets.defaults()));
        var nextChoice =
            OfficialSelectedFileGatewayTest.choice(nextCandidates, fixture.selectedEKey);
        var reuse = await(service.probeLoose(nextChoice)).plan().orElseThrow();
        assertTrue(reuse.acquisition().isEmpty());
        assertEquals(new AcquisitionCost(0, 0), reuse.cost());
        second = await(service.retain(reuse));
        assertNotEquals(first.snapshotId(), second.snapshotId());
        assertEquals(first.representation(), second.representation());
        assertEquals(
            first.representation().sourceReceipt(), second.representation().sourceReceipt());
        assertNotEquals(first.proof().proofDigest(), second.proof().proofDigest());
        assertEquals(
            unknown,
            second.proof().authenticatedCandidates().stream()
                .filter(c -> c.encodingKey().equals(fixture.selectedEKey))
                .findFirst()
                .orElseThrow()
                .encodedBytes()
                .isEmpty());
        assertEquals(payloadRequests, payloadRequests(fixture));
        assertEquals(before + 1, physicalObjects(data));
        assertEquals(3, builds.listReferences(first.snapshotId()).size());
        assertEquals(3, builds.listReferences(second.snapshotId()).size());
        assertDependencyPin(sql, first);
        assertDependencyPin(sql, second);
        var alternative =
            nextCandidates.candidates().stream()
                .filter(c -> !c.encodingKey().equals(fixture.selectedEKey))
                .findFirst()
                .orElseThrow();
        var failure =
            assertThrows(
                CompletionException.class,
                () -> await(service.probeLoose(new CandidateChoice(nextCandidates, alternative))));
        assertEquals(
            SelectedFileException.Code.REPLAN_REQUIRED,
            assertInstanceOf(SelectedFileException.class, failure.getCause()).code());
      }
    }
    fixture.rejectNetwork = true;
    int requestCount = fixture.requests.size();
    try (var store = new FileObjectStore(data)) {
      var clock = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
      var client = fixture.client();
      try (var gateway =
              new OfficialSelectedFileGateway(
                  store,
                  new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
                  new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1)),
                  new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
                  clock);
          var service = service(gateway, new SqliteCatalog(database), store)) {
        assertEquals(
            first,
            await(service.inspectOffline(first.descriptor(), SelectedFileBudgets.defaults())));
        assertEquals(
            second,
            await(service.inspectOffline(second.descriptor(), SelectedFileBudgets.defaults())));
        assertEquals(
            first,
            await(
                service.inspectOffline(
                    first.descriptor().association(), SelectedFileBudgets.defaults())));
        assertEquals(
            second,
            await(
                service.inspectOffline(
                    second.descriptor().association(), SelectedFileBudgets.defaults())));
        assertEquals(requestCount, fixture.requests.size());
        var digest = first.representation().encoded().object().digest().value();
        var object =
            data.resolve("objects").resolve(digest.substring(0, 2)).resolve(digest.substring(2));
        byte[] original = Files.readAllBytes(object);
        byte[] corrupt = original.clone();
        corrupt[0] ^= 1;
        Files.write(object, corrupt);
        assertOfflineFailure(service, first, SelectedFileException.Code.INTEGRITY_FAILURE);
        Files.delete(object);
        assertOfflineFailure(service, second, SelectedFileException.Code.MISSING_LOCAL);
        assertEquals(requestCount, fixture.requests.size());
      }
    }
  }

  private static void assertOfflineFailure(
      SelectedFileService service,
      SelectedFileAssociation association,
      SelectedFileException.Code expected) {
    var failure =
        assertThrows(
            CompletionException.class,
            () ->
                await(
                    service.inspectOffline(
                        association.descriptor(), SelectedFileBudgets.defaults())));
    assertEquals(
        expected, assertInstanceOf(SelectedFileException.class, failure.getCause()).code());
    var fromVariant =
        assertThrows(
            CompletionException.class,
            () ->
                await(
                    service.inspectOffline(
                        association.descriptor().association(), SelectedFileBudgets.defaults())));
    assertEquals(
        expected, assertInstanceOf(SelectedFileException.class, fromVariant.getCause()).code());
  }

  private static void assertDependencyPin(SqliteCatalog sql, SelectedFileAssociation association)
      throws Exception {
    try (var connection = sql.connect();
        var query =
            connection.prepareStatement(
                "SELECT r.role FROM retention_scopes s JOIN retention_scope_requirements r ON"
                    + " r.scope_id=s.id WHERE s.snapshot_id=? AND s.kind='ENCODED_FILE' AND"
                    + " s.pinned=1")) {
      query.setString(1, association.snapshotId());
      var roles = new ArrayList<String>();
      try (var rows = query.executeQuery()) {
        while (rows.next()) roles.add(rows.getString(1));
      }
      assertEquals(3, roles.size());
      assertEquals(Set.of("BUILD_CONFIG", "ROOT_MANIFEST", association.role()), Set.copyOf(roles));
    }
  }

  private static DefaultSelectedFileService service(
      SelectedFileGateway gateway, SqliteCatalog sql, FileObjectStore store) {
    return new DefaultSelectedFileService(
        gateway,
        new SqliteBuildObjectCatalog(sql, store),
        new SqliteSelectedFileCatalog(sql, store),
        new SqliteRetentionRepository(sql),
        Runnable::run,
        Runnable::run);
  }

  private static void register(
      SqliteBuildObjectCatalog builds,
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root) {
    builds.registerSnapshot(build);
    attach(builds, build, "BUILD_CONFIG", configuration);
    attach(builds, build, "ROOT_MANIFEST", root);
  }

  private static void attach(
      SqliteBuildObjectCatalog builds,
      BuildSnapshot build,
      String role,
      VerifiedSourceAlias alias) {
    builds.attachVerifiedObject(
        build.snapshotId(),
        role,
        alias.source(),
        alias.object(),
        alias.evidence(),
        CancellationToken.none());
  }

  private static long physicalObjects(Path data) throws Exception {
    try (var paths = Files.walk(data.resolve("objects"))) {
      return paths.filter(Files::isRegularFile).count();
    }
  }

  private static long payloadRequests(SelectedFileHttpFixture fixture) {
    return fixture.requests.stream()
        .filter(r -> r.method().equals("GET") && r.uri().getPath().endsWith(fixture.selectedEKey))
        .count();
  }

  private static <T> T await(CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }
}
