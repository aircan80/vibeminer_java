package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.bootstrap.OfficialBootstrapArtifactGateway;
import io.vibeminer.infrastructure.buildopening.*;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.discoveryhistory.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialBuildOpeningGatewayIntegrationTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final Clock CLOCK = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);

  @Test
  void actualCompleteSevenRoleOpenAndStrictOfflineReopenUseOneCasAndAtomicAdmission()
      throws Exception {
    try (var f = new Fixture(directory)) {
      assertTrue(
          f.library
              .findSeen(f.reference.key().identity(), NONE)
              .orElseThrow()
              .acknowledgedAt()
              .isEmpty());
      var operation = f.service.open(f.reference, "enUS");
      var opened = operation.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertEquals(7, opened.required().size());
      assertEquals(BuildOpenProgress.Stage.COMPLETE, operation.progress().stage());
      assertTrue(operation.progress().transferredWireBytes().isEmpty());
      assertTrue(
          f.library
              .findSeen(f.reference.key().identity(), NONE)
              .orElseThrow()
              .acknowledgedAt()
              .isPresent());
      int requests = f.client.requests.get();
      assertTrue(requests > 7);
      var original =
          f.admissions
              .find(f.reference, "enUS", BuildOpeningPolicy.SCOPE_REVISION, NONE)
              .orElseThrow();
      f.client.reject = true;
      var reopen = f.service.open(f.reference, "enUS");
      assertEquals(opened, reopen.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
      assertEquals(OptionalLong.of(0), reopen.progress().transferredWireBytes());
      assertEquals(requests, f.client.requests.get());
      var repeated =
          f.admissions
              .find(f.reference, "enUS", BuildOpeningPolicy.SCOPE_REVISION, NONE)
              .orElseThrow();
      assertEquals(original.admission(), repeated.admission());
      assertEquals(original.metadata(), repeated.metadata());
    }
  }

  @Test
  void authenticatedAbsentOptionalRolesProduceCompleteFiveRoleAdmission() throws Exception {
    try (var f =
        new Fixture(directory, BuildOpeningPolicy.initialCohort(), () -> Long.MAX_VALUE, false)) {
      var opened =
          f.service
              .open(f.reference, "enUS")
              .completion()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      assertEquals(5, opened.required().size());
      assertEquals(
          BuildMetadataAdmission.DownloadApplicability.AUTHENTICATED_NOT_PUBLISHED,
          opened.download().applicability());
      assertEquals(
          BuildMetadataAdmission.SizeApplicability.AUTHENTICATED_NOT_PUBLISHED,
          opened.size().applicability());
    }
  }

  @Test
  void lateRequired404DoesNotAdmitAndRetryReusesEveryCompletedRole() throws Exception {
    try (var f = new Fixture(directory)) {
      String missing = f.client.keys.get("size");
      f.client.missing.add(missing);
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  f.service
                      .open(f.reference, "enUS")
                      .completion()
                      .toCompletableFuture()
                      .get(10, TimeUnit.SECONDS));
      assertEquals(
          BuildOpeningException.Code.UNAVAILABLE,
          assertInstanceOf(BuildOpeningException.class, failure.getCause()).code());
      assertTrue(
          f.admissions
              .find(f.reference, "enUS", BuildOpeningPolicy.SCOPE_REVISION, NONE)
              .isEmpty());
      assertTrue(
          f.library
              .findSeen(f.reference.key().identity(), NONE)
              .orElseThrow()
              .acknowledgedAt()
              .isEmpty());
      var before = new HashMap<String, Integer>();
      f.client.byKey.forEach((key, count) -> before.put(key, count.get()));
      awaitIdle(f.sourcePool);
      f.client.missing.remove(missing);
      var opened =
          f.service
              .open(f.reference, "enUS")
              .completion()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      assertEquals(7, opened.required().size());
      for (var entry : before.entrySet())
        if (!entry.getKey().equals(missing))
          assertEquals(entry.getValue(), f.client.byKey.get(entry.getKey()).get(), entry.getKey());
      assertEquals(before.get(missing) + 1, f.client.byKey.get(missing).get());
    }
  }

  @Test
  void missingAdmittedObjectRefusesOfflineWithoutImplicitRepairOrNewReceipt() throws Exception {
    try (var f = new Fixture(directory)) {
      f.service
          .open(f.reference, "enUS")
          .completion()
          .toCompletableFuture()
          .get(10, TimeUnit.SECONDS);
      var facts =
          f.admissions
              .find(f.reference, "enUS", BuildOpeningPolicy.SCOPE_REVISION, NONE)
              .orElseThrow();
      var proof =
          facts.metadata().stream()
              .filter(value -> value.role() == BuildMetadataAdmission.Role.SIZE)
              .findFirst()
              .orElseThrow()
              .proof();
      String digest = proof.object().digest().value();
      Files.delete(
          directory
              .resolve("store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2)));
      int requests = f.client.requests.get();
      f.client.reject = true;
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  f.service
                      .open(f.reference, "enUS")
                      .completion()
                      .toCompletableFuture()
                      .get(10, TimeUnit.SECONDS));
      assertEquals(
          BuildOpeningException.Code.MISSING_METADATA,
          assertInstanceOf(BuildOpeningException.class, failure.getCause()).code());
      assertEquals(requests, f.client.requests.get());
      assertTrue(
          f.library
              .findSeen(f.reference.key().identity(), NONE)
              .orElseThrow()
              .acknowledgedAt()
              .isPresent());
    }
  }

  @Test
  void unknownAndInsufficientDiskAreTypedAndHealthyReopenNeedsNoNewCapacity() throws Exception {
    for (long usable : new long[] {-1, 0}) {
      try (var f =
          new Fixture(
              directory.resolve("refuse" + usable),
              BuildOpeningPolicy.initialCohort(),
              () -> usable,
              true)) {
        var failure =
            assertThrows(
                ExecutionException.class,
                () ->
                    f.service
                        .open(f.reference, "enUS")
                        .completion()
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS));
        assertEquals(
            usable < 0
                ? BuildOpeningException.Code.STORAGE_UNAVAILABLE
                : BuildOpeningException.Code.LIMIT_EXCEEDED,
            assertInstanceOf(BuildOpeningException.class, failure.getCause()).code());
        assertEquals(0, f.client.requests.get());
      }
    }
    var usable = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
    try (var f =
        new Fixture(
            directory.resolve("reopen"), BuildOpeningPolicy.initialCohort(), usable::get, true)) {
      var original =
          f.service
              .open(f.reference, "enUS")
              .completion()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      usable.set(0);
      f.client.reject = true;
      assertEquals(
          original,
          f.service
              .open(f.reference, "enUS")
              .completion()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void wholeOperationAggregateWireReservationRefusesBeforeLargeSources() throws Exception {
    var p = BuildOpeningPolicy.initialCohort();
    var small =
        new BuildOpeningPolicy(
            2L << 20,
            p.maximumAdditionalPhysicalBytes(),
            p.maximumRequests(),
            p.maximumDecodedWorkBytes(),
            p.maximumGrammarWorkBytes(),
            p.freeSpaceHeadroomBytes(),
            p.timeout(),
            p.metadata(),
            p.root(),
            p.declarations());
    try (var f = new Fixture(directory, small, () -> Long.MAX_VALUE, true)) {
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  f.service
                      .open(f.reference, "enUS")
                      .completion()
                      .toCompletableFuture()
                      .get(10, TimeUnit.SECONDS));
      assertEquals(
          BuildOpeningException.Code.LIMIT_EXCEEDED,
          assertInstanceOf(BuildOpeningException.class, failure.getCause()).code());
      assertEquals(2, f.client.requests.get());
      assertTrue(
          f.admissions
              .find(f.reference, "enUS", BuildOpeningPolicy.SCOPE_REVISION, NONE)
              .isEmpty());
    }
  }

  @Test
  void sharedOpeningSourceSurvivesFirstConsumerCancellationWithoutExtraEncodedCopy()
      throws Exception {
    try (var f = new Fixture(directory)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      String encoding = f.client.keys.get("encoding");
      f.client.beforeRequest =
          request -> {
            if (request.uri().getPath().endsWith("/" + encoding)) {
              entered.countDown();
              awaitUninterruptibly(release);
            }
          };
      try {
        var first = f.service.open(f.reference, "enUS");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var survivor = f.service.open(f.reference, "enUS");
        awaitContextConsumers(f.gateway, "tact.encoding.v1", 2);
        first.requestCancellation();
        var stopped =
            assertThrows(
                ExecutionException.class,
                () -> first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, stopped.getCause());
        assertFalse(survivor.completion().toCompletableFuture().isDone());
        release.countDown();
        assertEquals(
            7,
            survivor
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
                .required()
                .size());
        assertEquals(1, f.client.byKey.get(encoding).get());
        awaitIdle(f.sourcePool);
        assertContextsEmpty(f.gateway);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void openingCannotBypassFourExistingSharedActualSourceWorkers() throws Exception {
    try (var f = new Fixture(directory)) {
      var entered = new CountDownLatch(4);
      var release = new CountDownLatch(1);
      var jobs = new ArrayList<PrioritySourceScheduler.ScheduledSource>();
      try {
        for (int i = 0; i < 4; i++)
          jobs.add(
              f.scheduler.submit(
                  new ResolvedSourceIdentity("authored.existing", "source" + i, "v1"),
                  token -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                  },
                  PrioritySourceScheduler.Demand.foreground()));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var opening = f.service.open(f.reference, "enUS");
        awaitContextConsumers(f.gateway, "tact.config.raw-md5", 1);
        assertEquals(0, f.client.requests.get());
        assertFalse(opening.completion().toCompletableFuture().isDone());
        release.countDown();
        assertEquals(
            7,
            opening.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).required().size());
      } finally {
        release.countDown();
        for (var job : jobs) {
          job.retirement().toCompletableFuture().get(5, TimeUnit.SECONDS);
          job.initialDemand().close();
        }
      }
    }
  }

  @Test
  void allConsumersCancelWhileActualMetadataIoStillOwnsItsCanonicalNonce() throws Exception {
    try (var f = new Fixture(directory)) {
      String encoding = f.client.keys.get("encoding");
      String raw =
          new String(f.client.objects.get(f.reference.key().buildConfig()), StandardCharsets.UTF_8);
      String ck =
          raw.lines()
              .filter(line -> line.startsWith("encoding = "))
              .findFirst()
              .orElseThrow()
              .split(" = ")[1]
              .split(" ")[0];
      var slot = new io.vibeminer.domain.selectedfile.ContentSlotKey("wow", ck);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.client.beforeRequest =
          request -> {
            if (request.uri().getPath().endsWith("/" + encoding)) {
              entered.countDown();
              awaitUninterruptibly(release);
            }
          };
      try {
        var operation = f.service.open(f.reference, "enUS");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        operation.requestCancellation();
        var cancelled =
            assertThrows(
                ExecutionException.class,
                () -> operation.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, cancelled.getCause());
        var held = f.canonical.lookup(slot, NONE).orElseThrow();
        assertEquals(
            io.vibeminer.application.content.ContentClaimException.Code.BUSY,
            assertThrows(
                    io.vibeminer.application.content.ContentClaimException.class,
                    () -> f.canonical.claimWorker(held.token(), NONE))
                .code());
        assertTrue(
            f.admissions
                .find(f.reference, "enUS", BuildOpeningPolicy.SCOPE_REVISION, NONE)
                .isEmpty());
        assertTrue(
            f.library
                .findSeen(f.reference.key().identity(), NONE)
                .orElseThrow()
                .acknowledgedAt()
                .isEmpty());
        release.countDown();
        awaitIdle(f.sourcePool);
        assertTrue(f.canonical.lookup(slot, NONE).isEmpty());
        assertTrue(
            f.store
                .verify(new ObjectDigest(hash("SHA-256", f.client.objects.get(encoding))), NONE)
                .isEmpty());
        assertContextsEmpty(f.gateway);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void diskIsRecheckedBetweenObjectsAndUnavailableCapacityIsNotInvented() throws Exception {
    var checks = new AtomicInteger();
    try (var f =
        new Fixture(
            directory.resolve("between"),
            BuildOpeningPolicy.initialCohort(),
            () -> checks.incrementAndGet() <= 4 ? Long.MAX_VALUE : 0,
            true)) {
      var failed =
          assertThrows(
              ExecutionException.class,
              () ->
                  f.service
                      .open(f.reference, "enUS")
                      .completion()
                      .toCompletableFuture()
                      .get(10, TimeUnit.SECONDS));
      assertEquals(
          BuildOpeningException.Code.LIMIT_EXCEEDED,
          assertInstanceOf(BuildOpeningException.class, failed.getCause()).code());
      assertEquals(2, f.client.requests.get());
      assertTrue(
          f.admissions
              .find(f.reference, "enUS", BuildOpeningPolicy.SCOPE_REVISION, NONE)
              .isEmpty());
    }
    try (var f =
        new Fixture(
            directory.resolve("unavailable"),
            BuildOpeningPolicy.initialCohort(),
            () -> {
              throw new IllegalStateException("authored unavailable volume");
            },
            true)) {
      var failed =
          assertThrows(
              ExecutionException.class,
              () ->
                  f.service
                      .open(f.reference, "enUS")
                      .completion()
                      .toCompletableFuture()
                      .get(10, TimeUnit.SECONDS));
      assertEquals(
          BuildOpeningException.Code.STORAGE_UNAVAILABLE,
          assertInstanceOf(BuildOpeningException.class, failed.getCause()).code());
      assertEquals(0, f.client.requests.get());
    }
  }

  @Test
  void forgedRegisteredSequenceAndUnsupportedCkeyOnlyInstallStopBeforeMetadata() throws Exception {
    try (var f = new Fixture(directory.resolve("forged"))) {
      var r = f.reference;
      var forged =
          new BuildLibraryReference(
              r.key(),
              r.eventId(),
              r.observationSequence() + 1,
              r.snapshotId(),
              r.versionsRowOrdinal(),
              r.region(),
              r.observedAt(),
              r.versions(),
              r.cdns(),
              r.selectedCdn());
      var failed =
          assertThrows(
              ExecutionException.class,
              () ->
                  f.service
                      .open(forged, "enUS")
                      .completion()
                      .toCompletableFuture()
                      .get(10, TimeUnit.SECONDS));
      assertEquals(
          BuildOpeningException.Code.INVALID_REQUEST,
          assertInstanceOf(BuildOpeningException.class, failed.getCause()).code());
      assertEquals(0, f.client.requests.get());
    }
    try (var f =
        new Fixture(
            directory.resolve("ckonly"),
            BuildOpeningPolicy.initialCohort(),
            () -> Long.MAX_VALUE,
            true,
            text -> text.replaceAll("(?m)^(install = [0-9a-f]{32}) [0-9a-f]{32}$", "$1"))) {
      var failed =
          assertThrows(
              ExecutionException.class,
              () ->
                  f.service
                      .open(f.reference, "enUS")
                      .completion()
                      .toCompletableFuture()
                      .get(10, TimeUnit.SECONDS));
      assertEquals(
          BuildOpeningException.Code.UNSUPPORTED_METADATA,
          assertInstanceOf(BuildOpeningException.class, failed.getCause()).code());
      assertEquals(1, f.client.requests.get());
      assertTrue(
          f.admissions
              .find(f.reference, "enUS", BuildOpeningPolicy.SCOPE_REVISION, NONE)
              .isEmpty());
    }
  }

  static void awaitUninterruptibly(CountDownLatch latch) {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (true) {
      try {
        if (!latch.await(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS))
          throw new AssertionError("Authored IO gate timed out");
        return;
      } catch (InterruptedException ignored) {
        /* Deliberately uncooperative authored dependency. */
      }
    }
  }

  static void awaitContextConsumers(
      OfficialBuildOpeningGateway gateway, String namespace, int minimum) throws Exception {
    var field = OfficialBuildOpeningGateway.class.getDeclaredField("contexts");
    field.setAccessible(true);
    var contexts = (Map<?, ?>) field.get(gateway);
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      synchronized (contexts) {
        for (var entry : contexts.entrySet()) {
          if (!((ResolvedSourceIdentity) entry.getKey()).namespace().equals(namespace)) continue;
          var consumers = entry.getValue().getClass().getDeclaredField("consumers");
          consumers.setAccessible(true);
          if (consumers.getInt(entry.getValue()) >= minimum) return;
        }
      }
      if (System.nanoTime() >= end)
        throw new AssertionError("Source consumers did not reach expected bound");
      Thread.sleep(2);
    }
  }

  static void assertContextsEmpty(OfficialBuildOpeningGateway gateway) throws Exception {
    var field = OfficialBuildOpeningGateway.class.getDeclaredField("contexts");
    field.setAccessible(true);
    var contexts = (Map<?, ?>) field.get(gateway);
    synchronized (contexts) {
      assertTrue(contexts.isEmpty());
    }
  }

  static void awaitIdle(ExecutorService executor) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    var pool = (ThreadPoolExecutor) executor;
    while (pool.getActiveCount() != 0 || !pool.getQueue().isEmpty()) {
      if (System.nanoTime() >= end)
        throw new AssertionError("Actual source workers did not retire");
      Thread.sleep(2);
    }
  }

  static final class Fixture implements AutoCloseable {
    final FileObjectStore store;
    final SqliteCatalog sql;
    final SqliteBuildLibraryRepository library;
    final SqliteCanonicalContentRepository canonical;
    final BuildMetadataAdmissionRepository admissions;
    final BuildLibraryReference reference;
    final Client client = new Client();
    final ExecutorService sourcePool = Executors.newFixedThreadPool(4);
    final ExecutorService orchestration = Executors.newFixedThreadPool(4);
    final PrioritySourceScheduler scheduler = new PrioritySourceScheduler(sourcePool);
    final OfficialFileLookupGateway roots;
    final OfficialBuildOpeningGateway gateway;
    final DefaultBuildOpeningService service;

    Fixture(Path path) throws Exception {
      this(path, BuildOpeningPolicy.initialCohort(), () -> Long.MAX_VALUE, true);
    }

    Fixture(
        Path path,
        BuildOpeningPolicy policy,
        java.util.function.LongSupplier usable,
        boolean optional)
        throws Exception {
      this(path, policy, usable, optional, java.util.function.UnaryOperator.identity());
    }

    Fixture(
        Path path,
        BuildOpeningPolicy policy,
        java.util.function.LongSupplier usable,
        boolean optional,
        java.util.function.UnaryOperator<String> editConfig)
        throws Exception {
      store = new FileObjectStore(path.resolve("store"));
      sql = new SqliteCatalog(path.resolve("catalog.sqlite"));
      var session = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(sql, session);
      canonical = new SqliteCanonicalContentRepository(sql, store, session);
      var builds = new SqliteBuildObjectCatalog(sql, store);
      library = new SqliteBuildLibraryRepository(sql);
      var history = new SqliteDiscoveryHistoryRepository(sql);
      var base = new RootLookupFixture();
      byte[] install = install(), download = download(), size = size();
      var decoded = new LinkedHashMap<String, byte[]>();
      var encoded = new LinkedHashMap<String, byte[]>();
      decoded.put("root", base.root);
      encoded.put("root", base.encodedRoot);
      var inputs = new LinkedHashMap<String, byte[]>();
      inputs.put("install", install);
      if (optional) {
        inputs.put("download", download);
        inputs.put("size", size);
      }
      for (var entry : inputs.entrySet()) {
        decoded.put(entry.getKey(), entry.getValue());
        encoded.put(entry.getKey(), RootLookupFixture.blte(entry.getValue()));
      }
      byte[] en = encoding(decoded, encoded);
      decoded.put("encoding", en);
      encoded.put("encoding", RootLookupFixture.blte(en));
      var raw = new StringBuilder("root = " + base.rootCKey + "\n");
      for (String role : List.of("install", "encoding", "download", "size")) {
        if (!encoded.containsKey(role)) continue;
        raw.append(role)
            .append(" = ")
            .append(hash("MD5", decoded.get(role)))
            .append(' ')
            .append(RootLookupFixture.ekey(encoded.get(role)))
            .append('\n');
        raw.append(role)
            .append("-size = ")
            .append(decoded.get(role).length)
            .append(' ')
            .append(encoded.get(role).length)
            .append('\n');
      }
      byte[] config = editConfig.apply(raw.toString()).getBytes(StandardCharsets.UTF_8);
      byte[] cdn =
          ("archives = " + "a".repeat(32) + "\narchives-index-size = 173068\n")
              .getBytes(StandardCharsets.UTF_8);
      String configKey = hash("MD5", config), cdnKey = hash("MD5", cdn);
      client.objects.put(configKey, config);
      client.objects.put(cdnKey, cdn);
      for (var entry : encoded.entrySet()) {
        client.keys.put(entry.getKey(), RootLookupFixture.ekey(entry.getValue()));
        client.objects.put(RootLookupFixture.ekey(entry.getValue()), entry.getValue());
      }
      byte[] versions =
          ("Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n"
                  + "us|"
                  + configKey
                  + "|"
                  + cdnKey
                  + "|authored-open\n")
              .getBytes(StandardCharsets.UTF_8);
      byte[] cdns =
          ("Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n"
                  + "us|tpr/wow|us.cdn.blizzard.com|https://us.cdn.blizzard.com/|tpr/wow\n")
              .getBytes(StandardCharsets.UTF_8);
      var event =
          new DiscoveryObservationMapper()
              .map(
                  UUID.randomUUID(),
                  "wow",
                  "us",
                  CLOCK.instant(),
                  new SourceDocument(
                      "https://us.version.battle.net/v2/products/wow/versions",
                      CLOCK.instant(),
                      versions),
                  new SourceDocument(
                      "https://us.version.battle.net/v2/products/wow/cdns", CLOCK.instant(), cdns));
      history.append(event, DiscoveryHistoryLimits.defaults(), NONE);
      reference =
          library
              .recordObservation(
                  event.eventId(),
                  BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
                  Optional.empty(),
                  CLOCK.instant(),
                  NONE)
              .references()
              .getFirst();
      var configs = new PinnedCdnTransport(client, CLOCK, Duration.ofSeconds(2));
      var ranges = new PinnedCdnRangeTransport(client, CLOCK, Duration.ofSeconds(2));
      roots = new OfficialFileLookupGateway(store, configs, ranges, CLOCK);
      var installs = new OfficialBootstrapArtifactGateway(store, configs, CLOCK);
      var metadata = new OfficialBuildMetadataSourceWorker(store, canonical, builds, ranges, CLOCK);
      admissions = new BuildMetadataAdmissionRepository(sql, store, CLOCK);
      gateway =
          new OfficialBuildOpeningGateway(
              history,
              admissions,
              store,
              builds,
              canonical,
              metadata,
              installs,
              roots,
              configs,
              sourcePool,
              scheduler,
              policy,
              usable);
      service = new DefaultBuildOpeningService(gateway, orchestration);
    }

    public void close() throws Exception {
      service.close();
      gateway.close();
      scheduler.close();
      orchestration.shutdown();
      sourcePool.shutdown();
      if (!orchestration.awaitTermination(5, TimeUnit.SECONDS)
          || !sourcePool.awaitTermination(5, TimeUnit.SECONDS))
        throw new AssertionError(
            "Actual opening/source workers did not retire; CAS deliberately remains open");
      roots.close();
      store.close();
    }
  }

  static byte[] install() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("IN");
    out.writeByte(1);
    out.writeByte(16);
    out.writeShort(0);
    out.writeInt(1);
    out.writeBytes("authored-file");
    out.writeByte(0);
    out.write(new byte[16]);
    out.writeInt(3);
    return bytes.toByteArray();
  }

  static byte[] download() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("DL");
    out.writeByte(1);
    out.writeByte(16);
    out.writeByte(0);
    out.writeInt(1);
    out.writeShort(0);
    out.write(new byte[16]);
    out.write(new byte[] {0, 0, 0, 0, 3});
    out.writeByte(255);
    return bytes.toByteArray();
  }

  static byte[] size() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("DS");
    out.writeByte(1);
    out.writeByte(9);
    out.writeInt(1);
    out.writeShort(0);
    out.write(new byte[] {0, 0, 0, 0, 3});
    out.write(new byte[9]);
    out.writeInt(3);
    return bytes.toByteArray();
  }

  static byte[] encoding(Map<String, byte[]> decoded, Map<String, byte[]> encoded)
      throws Exception {
    var cp = new ByteArrayOutputStream();
    var content = new DataOutputStream(cp);
    var roles = new ArrayList<>(decoded.keySet());
    roles.sort(
        Comparator.comparing(
            role -> {
              try {
                return hash("MD5", decoded.get(role));
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            }));
    for (String role : roles) {
      content.writeByte(1);
      u40(content, decoded.get(role).length);
      content.write(HexFormat.of().parseHex(hash("MD5", decoded.get(role))));
      content.write(HexFormat.of().parseHex(RootLookupFixture.ekey(encoded.get(role))));
    }
    byte[] cpage = Arrays.copyOf(cp.toByteArray(), 1024);
    var ep = new ByteArrayOutputStream();
    var entries = new DataOutputStream(ep);
    var keys = new TreeMap<String, byte[]>();
    for (byte[] bytes : encoded.values()) keys.put(RootLookupFixture.ekey(bytes), bytes);
    for (var entry : keys.entrySet()) {
      entries.write(HexFormat.of().parseHex(entry.getKey()));
      entries.writeInt(0);
      u40(entries, entry.getValue().length);
    }
    entries.write(new byte[16]);
    entries.writeInt(-1);
    u40(entries, 0);
    byte[] epage = Arrays.copyOf(ep.toByteArray(), 1024);
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("EN");
    out.writeByte(1);
    out.writeByte(16);
    out.writeByte(16);
    out.writeShort(1);
    out.writeShort(1);
    out.writeInt(1);
    out.writeInt(1);
    out.writeByte(0);
    out.writeInt(2);
    out.write(new byte[] {'z', 0});
    out.write(HexFormat.of().parseHex(hash("MD5", decoded.get(roles.getFirst()))));
    out.write(RootLookupFixture.md5(cpage));
    out.write(cpage);
    out.write(HexFormat.of().parseHex(keys.firstKey()));
    out.write(RootLookupFixture.md5(epage));
    out.write(epage);
    return bytes.toByteArray();
  }

  static void u40(DataOutputStream out, long value) throws IOException {
    for (int shift = 32; shift >= 0; shift -= 8) out.writeByte((int) (value >>> shift));
  }

  static String hash(String algorithm, byte[] bytes) throws Exception {
    return RootLookupFixture.hash(algorithm, bytes);
  }

  static class Client extends HttpClient {
    final Map<String, byte[]> objects = new HashMap<>();
    final AtomicInteger requests = new AtomicInteger();
    volatile boolean reject;
    final Map<String, String> keys = new HashMap<>();
    final Set<String> missing = ConcurrentHashMap.newKeySet();
    final Map<String, AtomicInteger> byKey = new ConcurrentHashMap<>();
    volatile java.util.function.Consumer<HttpRequest> beforeRequest = request -> {};

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      if (reject) throw new AssertionError("Offline reopen attempted HTTP");
      requests.incrementAndGet();
      beforeRequest.accept(request);
      String path = request.uri().getPath();
      byte[] whole = Objects.requireNonNull(objects.get(path.substring(path.lastIndexOf('/') + 1)));
      String key = path.substring(path.lastIndexOf('/') + 1);
      byKey.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
      var range = request.headers().firstValue("Range");
      int status = missing.contains(key) ? 404 : range.isPresent() ? 206 : 200;
      byte[] bytes = whole;
      Map<String, List<String>> fields;
      if (status == 404) {
        bytes = new byte[0];
        fields = Map.of("Content-Length", List.of("0"));
      } else if (range.isPresent()) {
        String[] offsets = range.get().substring(6).split("-");
        int start = Integer.parseInt(offsets[0]), end = Integer.parseInt(offsets[1]);
        bytes = Arrays.copyOfRange(whole, start, end + 1);
        fields =
            Map.of("Content-Range", List.of("bytes " + start + "-" + end + "/" + whole.length));
      } else fields = Map.of("Content-Length", List.of(Integer.toString(whole.length)));
      var headers = HttpHeaders.of(fields, (a, b) -> true);
      var body =
          handler.apply(
              new HttpResponse.ResponseInfo() {
                public int statusCode() {
                  return status;
                }

                public HttpHeaders headers() {
                  return headers;
                }

                public Version version() {
                  return Version.HTTP_1_1;
                }
              });
      body.onSubscribe(
          new Flow.Subscription() {
            public void request(long n) {}

            public void cancel() {}
          });
      body.onNext(List.of(ByteBuffer.wrap(bytes)));
      body.onComplete();
      return body.getBody()
          .toCompletableFuture()
          .thenApply(
              value ->
                  new HttpResponse<T>() {
                    public int statusCode() {
                      return status;
                    }

                    public HttpRequest request() {
                      return request;
                    }

                    public Optional<HttpResponse<T>> previousResponse() {
                      return Optional.empty();
                    }

                    public HttpHeaders headers() {
                      return headers;
                    }

                    public T body() {
                      return value;
                    }

                    public Optional<SSLSession> sslSession() {
                      return Optional.empty();
                    }

                    public URI uri() {
                      return request.uri();
                    }

                    public Version version() {
                      return Version.HTTP_1_1;
                    }
                  });
    }

    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    public SSLContext sslContext() {
      try {
        return SSLContext.getDefault();
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    public Version version() {
      return Version.HTTP_1_1;
    }

    public Optional<Executor> executor() {
      return Optional.empty();
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) {
      throw new UnsupportedOperationException();
    }
  }
}
