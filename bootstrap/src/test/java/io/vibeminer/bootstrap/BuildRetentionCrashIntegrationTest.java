package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildoverview.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.buildopening.*;
import io.vibeminer.infrastructure.content.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Process-death evidence, not a power-loss/fsync or arbitrary-boundary claim. */
class BuildRetentionCrashIntegrationTest {
  private static final CancellationToken NONE = CancellationToken.none();
  private static final int HALTED = 83;
  @TempDir Path directory;

  enum Cut {
    QUEUED,
    VERIFIED_BEFORE_PROMOTION,
    PROMOTED_BEFORE_CANONICAL,
    CANONICAL_BEFORE_ASSOCIATION,
    ASSOCIATED_BEFORE_JOB_FINISH,
    PROMOTED_THEN_CORRUPTED,
    PAUSED_AFTER_COMMIT;

    Cut physicalCut() {
      return this == PROMOTED_THEN_CORRUPTED ? PROMOTED_BEFORE_CANONICAL : this;
    }
  }

  @TestFactory
  Stream<DynamicTest> durableBoundaries() {
    return Arrays.stream(Cut.values())
        .map(cut -> DynamicTest.dynamicTest(cut.name(), () -> crashAndRestart(cut)));
  }

  private void crashAndRestart(Cut cut) throws Exception {
    Path home = directory.resolve(cut.name()), log = directory.resolve(cut.name() + ".log");
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                childClasspath(),
                CrashChild.class.getName(),
                home.toString(),
                cut.physicalCut().name())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(child.waitFor(40, TimeUnit.SECONDS), () -> "Child timed out: " + readLog(log));
      assertEquals(HALTED, child.exitValue(), () -> readLog(log));
      assertTrue(readLog(log).contains("HALT_AT=" + cut.physicalCut()), () -> readLog(log));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Child did not terminate");
      }
    }
    var data = new BuildOpeningReviewData();
    var tracking = new Tracking(data);
    var sql = new SqliteCatalog(home.resolve("catalog.sqlite"));
    var before = state(sql, data);
    boolean paused = cut == Cut.PAUSED_AFTER_COMMIT;
    assertEquals(
        paused ? BuildRetention.Mode.ON_DEMAND : BuildRetention.Mode.DOWNLOAD_ALL, before.mode());
    assertEquals(paused ? 2 : 1, before.generation());
    if (paused) assertTrue(Set.of("RUNNING", "FAILED").contains(before.jobState()));
    else assertEquals(cut.physicalCut() == Cut.QUEUED ? "QUEUED" : "RUNNING", before.jobState());
    assertEquals(cut.physicalCut() == Cut.QUEUED ? 0 : 1, before.attempts());
    if (!paused) assertEquals(cut.physicalCut() != Cut.QUEUED, before.jobNonce() != null);
    if (!paused)
      assertEquals(
          cut.physicalCut() != Cut.QUEUED && cut.physicalCut() != Cut.ASSOCIATED_BEFORE_JOB_FINISH,
          before.canonicalNonce() != null);
    assertEquals(
        cut.physicalCut() == Cut.ASSOCIATED_BEFORE_JOB_FINISH ? 1 : 0, before.associations());
    boolean canonicalCommitted =
        EnumSet.of(Cut.CANONICAL_BEFORE_ASSOCIATION, Cut.ASSOCIATED_BEFORE_JOB_FINISH)
            .contains(cut.physicalCut());
    assertEquals(canonicalCommitted ? 1 : 0, before.encodedOrigins());
    if (cut.physicalCut() == Cut.QUEUED) {
      assertNull(before.canonicalState());
      assertNull(before.proof());
    } else if (!paused) {
      assertEquals(canonicalCommitted ? "RETAINED" : "PUBLISHING", before.canonicalState());
      var proof = assertInstanceOf(EncodedContentProof.class, before.proof());
      assertEquals(data.gameObjectDigest(), proof.object().digest().value());
      assertEquals(data.gameEncodingKey(), proof.actualEncodingKey());
      assertEquals(data.gameContentKey(), proof.contentKey());
    }

    boolean promoted =
        EnumSet.of(
                Cut.PROMOTED_BEFORE_CANONICAL,
                Cut.CANONICAL_BEFORE_ASSOCIATION,
                Cut.ASSOCIATED_BEFORE_JOB_FINISH)
            .contains(cut.physicalCut());
    assertEquals(promoted, Files.exists(gamePath(home, data)));
    assertEquals(cut.physicalCut() == Cut.VERIFIED_BEFORE_PROMOTION ? 1 : 0, stages(home));
    assertEquals(promoted ? 8 : 7, physicalObjects(home));
    byte[] originalBytes = promoted ? Files.readAllBytes(gamePath(home, data)) : null;
    if (cut == Cut.PROMOTED_THEN_CORRUPTED) {
      var corrupt = originalBytes.clone();
      corrupt[corrupt.length - 1] ^= 1;
      Files.write(gamePath(home, data), corrupt);
    }

    BuildLibraryReference reference;
    try (var metadata = MetadataRuntime.open(home, tracking::client, gateway -> gateway)) {
      reference =
          metadata
              .library()
              .page(
                  new BuildLibraryQuery("wow", BuildLibraryProjection.Filter.ALL),
                  Optional.empty(),
                  10)
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS)
              .entries()
              .getFirst()
              .row()
              .group()
              .preferredReference();
      // Production factory without resumeAuthorized lets us measure recovered reality before any
      // automatic worker can change it. This is an actual backend, never a fabricated snapshot.
      var assessed = assessRecovered(home, tracking, reference, before.target());
      assertEquals(
          cut.physicalCut() == Cut.ASSOCIATED_BEFORE_JOB_FINISH
              ? BuildOverview.Readiness.FULL
              : BuildOverview.Readiness.PARTIAL,
          assessed.readiness());
      assertEquals(0, tracking.requests.size());
      assertEquals(0, stages(home));
      var recovered = state(sql, data);
      assertNull(recovered.jobNonce());
      assertNull(recovered.canonicalNonce());
      assertEquals(paused ? "FAILED" : "QUEUED", recovered.jobState());
      assertEquals(before.policy(), recovered.policy());
      assertEquals(before.generation(), recovered.generation());
      assertEquals(before.authorizedAt(), recovered.authorizedAt());
      assertEquals(before.proof(), recovered.proof());
      try (var runtime = ContentRuntime.open(home, tracking::client)) {
        // Only the stored authorization starts this work; do not call resume or setMode.
        var terminal =
            awaitJob(
                sql, data, cut == Cut.PROMOTED_THEN_CORRUPTED || paused ? "FAILED" : "SUCCEEDED");
        var actual =
            runtime
                .buildOpening()
                .open(reference, "enUS")
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        var overview = read(runtime, actual, before.target());
        if (paused) {
          assertEquals(BuildOverview.Readiness.PARTIAL, overview.readiness());
          assertEquals(0, tracking.requests.size());
          assertEquals(0, terminal.associations());
          assertEquals(0, terminal.encodedOrigins());
          assertEquals(7, physicalObjects(home));
          assertNull(terminal.proof());
          assertNull(terminal.canonicalNonce());
        } else if (cut == Cut.PROMOTED_THEN_CORRUPTED) {
          assertNotEquals(BuildOverview.Readiness.FULL, overview.readiness());
          assertEquals(0, terminal.associations());
          assertEquals(0, terminal.encodedOrigins());
          assertEquals(before.proof(), terminal.proof());
          assertEquals("RECOVERY", terminal.canonicalState());
          assertEquals(0, tracking.gameGets());
          assertFalse(Arrays.equals(originalBytes, Files.readAllBytes(gamePath(home, data))));
        } else {
          assertEquals(BuildOverview.Readiness.FULL, overview.readiness());
          assertEquals(1, terminal.associations());
          assertEquals(1, terminal.encodedOrigins());
          assertEquals(8, physicalObjects(home));
          assertEquals(0, stages(home));
          assertEquals(
              cut.physicalCut() == Cut.QUEUED || cut.physicalCut() == Cut.VERIFIED_BEFORE_PROMOTION
                  ? 1
                  : 0,
              tracking.gameGets());
          if (before.proof() != null)
            assertEquals(
                before.proof(),
                terminal.proof(),
                "Original prospective receipt may not be restamped");
          if (originalBytes != null)
            assertArrayEquals(originalBytes, Files.readAllBytes(gamePath(home, data)));
        }
        assertEquals(before.policy(), terminal.policy());
        assertEquals(before.generation(), terminal.generation());
        assertEquals(before.authorizedAt(), terminal.authorizedAt());
        assertEquals(before.target(), terminal.target());
        assertEquals(
            tracking.gameRequests(),
            tracking.requests.size(),
            "Retained metadata must not be downloaded again");
      }
    }
  }

  private static BuildOverview.Snapshot assessRecovered(
      Path home, Tracking tracking, BuildLibraryReference reference, BuildRetention.Target target)
      throws Exception {
    var sql = new SqliteCatalog(home.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(home.resolve("content"))) {
      SqliteContentStartupRecovery.recoverAtStartup(sql, store.contentSession());
      var canonical = new SqliteCanonicalContentRepository(sql, store, store.contentSession());
      var files = new SqliteSelectedFileCatalog(sql, store, store.contentSession());
      var admissions = new BuildMetadataAdmissionRepository(sql, store);
      var clock = Clock.systemUTC();
      var client = tracking.client();
      var ranges = new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1));
      try (var scheduler = new PrioritySourceScheduler(Runnable::run);
          var sources = new EncodedSourceAcquisition(files, Runnable::run, scheduler);
          var mining =
              BuildMiningRuntime.open(
                  new BuildMiningRuntime.Dependencies(
                      home.resolve("catalog.sqlite"),
                      sql,
                      store,
                      canonical,
                      admissions,
                      files,
                      sources,
                      scheduler,
                      new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
                      ranges,
                      new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
                      Runnable::run,
                      Runnable::run,
                      Runnable::run,
                      clock))) {
        var admission =
            admissions
                .find(reference, "enUS", BuildOpeningPolicy.SCOPE_REVISION, NONE)
                .orElseThrow()
                .admission();
        return mining
            .overview()
            .read(request(admission, target))
            .completion()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
      } finally {
        client.shutdownNow();
      }
    }
  }

  private static BuildOverview.Request request(
      BuildMetadataAdmission admission, BuildRetention.Target target) {
    return new BuildOverview.Request(
        admission.reference(),
        admission.locale(),
        admission.scopeRevision(),
        BuildMiningRuntime.overviewLimits(),
        Optional.of(target));
  }

  private static BuildOverview.Snapshot read(
      ContentRuntime runtime, BuildMetadataAdmission admission, BuildRetention.Target target)
      throws Exception {
    return runtime
        .buildOverview()
        .read(request(admission, target))
        .completion()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  private static State awaitJob(SqliteCatalog sql, BuildOpeningReviewData data, String expected)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    for (; ; ) {
      var state = state(sql, data);
      if (state.jobState().equals(expected) && state.jobNonce() == null) return state;
      assertTrue(System.nanoTime() < deadline, () -> "Expected " + expected + ", actual " + state);
      Thread.yield();
    }
  }

  record State(
      BuildRetention.Target target,
      BuildRetention.Policy policy,
      Instant authorizedAt,
      BuildRetention.Mode mode,
      long generation,
      String jobState,
      int attempts,
      String jobNonce,
      String canonicalState,
      String canonicalNonce,
      CanonicalContentProof proof,
      long associations,
      long encodedOrigins) {}

  private static State state(SqliteCatalog sql, BuildOpeningReviewData data) throws Exception {
    try (var c = sql.connect();
        var s = c.createStatement();
        var rows = s.executeQuery("SELECT * FROM build_retention_intents")) {
      assertTrue(rows.next());
      var target =
          new BuildRetention.Target(
              rows.getString("group_id"),
              rows.getString("snapshot_id"),
              rows.getString("admission_id"),
              rows.getString("inventory_id"),
              rows.getLong("inventory_generation"),
              rows.getString("scope_revision"),
              rows.getString("scope_commitment"));
      var policy =
          new BuildRetention.Policy(
              rows.getLong("maximum_object"),
              rows.getLong("maximum_wire"),
              rows.getLong("maximum_additional"),
              rows.getLong("maximum_requests"),
              rows.getInt("maximum_attempts"));
      var at = Instant.parse(rows.getString("authorized_at"));
      var mode = BuildRetention.Mode.valueOf(rows.getString("mode"));
      long generation = rows.getLong("generation");
      assertFalse(rows.next());
      String job, jobNonce;
      int attempts;
      try (var q = c.createStatement();
          var r = q.executeQuery("SELECT state,attempts,worker_nonce FROM build_retention_jobs")) {
        assertTrue(r.next());
        job = r.getString(1);
        attempts = r.getInt(2);
        jobNonce = r.getString(3);
        assertFalse(r.next());
      }
      String status = null, nonce = null;
      CanonicalContentProof proof = null;
      try (var q =
          c.prepareStatement(
              "SELECT status,worker_nonce,representation FROM canonical_content_slots WHERE"
                  + " namespace='wow' AND content_key=?")) {
        q.setString(1, data.gameContentKey());
        try (var r = q.executeQuery()) {
          if (r.next()) {
            status = r.getString(1);
            nonce = r.getString(2);
            var bytes = r.getBytes(3);
            if (bytes != null) proof = CanonicalContentRecords.decode(bytes);
            assertFalse(r.next());
          }
        }
      }
      long associations;
      try (var q = c.createStatement();
          var r = q.executeQuery("SELECT count(*) FROM build_retention_sources")) {
        assertTrue(r.next());
        associations = r.getLong(1);
      }
      long origins;
      try (var q = c.createStatement();
          var r = q.executeQuery("SELECT count(*) FROM encoded_file_receipts")) {
        assertTrue(r.next());
        origins = r.getLong(1);
      }
      return new State(
          target,
          policy,
          at,
          mode,
          generation,
          job,
          attempts,
          jobNonce,
          status,
          nonce,
          proof,
          associations,
          origins);
    }
  }

  private static Path gamePath(Path home, BuildOpeningReviewData data) {
    String sha = data.gameObjectDigest();
    return home.resolve("content/objects").resolve(sha.substring(0, 2)).resolve(sha.substring(2));
  }

  private static long physicalObjects(Path home) throws IOException {
    try (var paths = Files.walk(home.resolve("content/objects"))) {
      return paths.filter(Files::isRegularFile).count();
    }
  }

  private static long stages(Path home) throws IOException {
    try (var paths = Files.list(home.resolve("content/work"))) {
      return paths.filter(Files::isRegularFile).count();
    }
  }

  private static String readLog(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      return failure.toString();
    }
  }

  private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
    var f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return type.cast(f.get(owner));
  }

  private static void replace(Object owner, String name, Object value) throws Exception {
    var f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(owner, value);
  }

  private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(delegate, args);
    } catch (InvocationTargetException failed) {
      throw failed.getCause();
    }
  }

  public static final class CrashChild {
    public static void main(String[] args) throws Exception {
      Path home = Path.of(args[0]);
      Cut cut = Cut.valueOf(args[1]);
      var data = new BuildOpeningReviewData();
      if (cut == Cut.PAUSED_AFTER_COMMIT) data.holdGame();
      try (var metadata = MetadataRuntime.open(home, data::newClient, gateway -> gateway)) {
        var found =
            metadata
                .library()
                .refreshProduct("wow")
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        var reference = found.registrations().getFirst().references().getFirst();
        try (var runtime = ContentRuntime.open(home, data::newClient)) {
          var admission =
              runtime
                  .buildOpening()
                  .open(reference, "enUS")
                  .completion()
                  .toCompletableFuture()
                  .get(10, TimeUnit.SECONDS);
          var prepared =
              runtime
                  .inventoryPreparation()
                  .prepare(admission)
                  .completion()
                  .toCompletableFuture()
                  .get(10, TimeUnit.SECONDS);
          var core = field(runtime, "encodedSources", EncodedSourceAcquisition.class);
          var actualFiles = field(core, "files", EncodedSourceCatalog.class);
          if (!(actualFiles instanceof SqliteSelectedFileCatalog))
            throw new AssertionError("Actual SQLite source catalog required");
          var proxy =
              Proxy.newProxyInstance(
                  EncodedSourceCatalog.class.getClassLoader(),
                  new Class<?>[] {EncodedSourceCatalog.class},
                  (ignored, method, arguments) -> {
                    if (method.getName().equals("completePublication")
                        && cut == Cut.PROMOTED_BEFORE_CANONICAL) halt(cut);
                    var result = invoke(actualFiles, method, arguments);
                    if (method.getName().equals("recordVerifiedCompletion")
                        && cut == Cut.VERIFIED_BEFORE_PROMOTION) halt(cut);
                    if (method.getName().equals("completePublication")
                        && cut == Cut.CANONICAL_BEFORE_ASSOCIATION) halt(cut);
                    return result;
                  });
          replace(core, "files", proxy);
          var mining = field(runtime, "buildMining", BuildMiningRuntime.class);
          var pump = field(mining, "pump", BuildRetentionPump.class);
          var actualRepository = field(pump, "repository", BuildRetentionRepository.class);
          if (!actualRepository
              .getClass()
              .getName()
              .equals("io.vibeminer.infrastructure.buildretention.SqliteBuildRetentionRepository"))
            throw new AssertionError("Actual SQLite job repository required");
          replace(
              pump,
              "repository",
              Proxy.newProxyInstance(
                  BuildRetentionRepository.class.getClassLoader(),
                  new Class<?>[] {BuildRetentionRepository.class},
                  (ignored, method, arguments) -> {
                    if (method.getName().equals("finish")
                        && cut == Cut.ASSOCIATED_BEFORE_JOB_FINISH
                        && ((BuildRetention.Finish) arguments[1]).state()
                            == BuildRetention.State.SUCCEEDED) halt(cut);
                    var result = invoke(actualRepository, method, arguments);
                    if (method.getName().equals("fillWindow") && cut == Cut.QUEUED) halt(cut);
                    return result;
                  }));
          runtime
              .retentionMode()
              .setMode(
                  prepared.target(),
                  0,
                  BuildRetention.Mode.DOWNLOAD_ALL,
                  prepared.downloadPolicy().orElseThrow())
              .completion()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
          if (cut == Cut.PAUSED_AFTER_COMMIT) {
            if (!data.awaitGame(Duration.ofSeconds(10)))
              throw new AssertionError("No actual game request to pause");
            var mode = runtime.retentionMode();
            var repository = field(mode, "repository", BuildRetentionRepository.class);
            replace(
                mode,
                "repository",
                Proxy.newProxyInstance(
                    BuildRetentionRepository.class.getClassLoader(),
                    new Class<?>[] {BuildRetentionRepository.class},
                    (ignored, method, arguments) -> {
                      var result = invoke(repository, method, arguments);
                      if (method.getName().equals("setMode")
                          && ((BuildRetention.Intent) result).mode()
                              == BuildRetention.Mode.ON_DEMAND) halt(cut);
                      return result;
                    }));
            mode.setMode(
                    prepared.target(),
                    1,
                    BuildRetention.Mode.ON_DEMAND,
                    prepared.downloadPolicy().orElseThrow())
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
          }
          new CountDownLatch(1).await(20, TimeUnit.SECONDS);
          throw new AssertionError("Crash boundary was not reached: " + cut);
        }
      }
    }

    private static void halt(Cut cut) {
      System.out.println("HALT_AT=" + cut);
      System.out.flush();
      Runtime.getRuntime().halt(HALTED);
    }
  }

  private static String childClasspath() throws Exception {
    var paths = new LinkedHashSet<String>();
    for (Class<?> type :
        List.of(
            CrashChild.class,
            ContentRuntime.class,
            io.vibeminer.application.selectedfile.DefaultSelectedFileService.class,
            io.vibeminer.domain.StoredObject.class,
            FileObjectStore.class,
            io.vibeminer.protocol.tact.blte.BlteDecoder.class,
            io.vibeminer.format.wow.root.SelectedRootLookup.class,
            io.vibeminer.protocol.db2.CStringPrefixReader.class,
            Class.forName("org.sqlite.JDBC"),
            org.junit.jupiter.api.Assertions.class,
            org.opentest4j.AssertionFailedError.class,
            org.junit.platform.commons.util.Preconditions.class))
      paths.add(
          Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    return String.join(java.io.File.pathSeparator, paths);
  }

  private static final class Tracking {
    final BuildOpeningReviewData data;
    final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

    Tracking(BuildOpeningReviewData data) {
      this.data = data;
    }

    HttpClient client() {
      return new CountingClient(data.newClient(), requests);
    }

    long gameGets() {
      return requests.stream()
          .filter(
              r -> r.method().equals("GET") && r.uri().getPath().endsWith(data.gameEncodingKey()))
          .count();
    }

    long gameRequests() {
      return requests.stream()
          .filter(r -> r.uri().getPath().endsWith(data.gameEncodingKey()))
          .count();
    }
  }

  private static final class CountingClient extends HttpClient {
    final HttpClient actual;
    final List<HttpRequest> requests;

    CountingClient(HttpClient actual, List<HttpRequest> requests) {
      this.actual = actual;
      this.requests = requests;
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      requests.add(request);
      return actual.sendAsync(request, handler);
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) {
      requests.add(request);
      return actual.sendAsync(request, handler, push);
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
      requests.add(request);
      return actual.send(request, handler);
    }

    public Optional<CookieHandler> cookieHandler() {
      return actual.cookieHandler();
    }

    public Optional<Duration> connectTimeout() {
      return actual.connectTimeout();
    }

    public Redirect followRedirects() {
      return actual.followRedirects();
    }

    public Optional<ProxySelector> proxy() {
      return actual.proxy();
    }

    public SSLContext sslContext() {
      return actual.sslContext();
    }

    public SSLParameters sslParameters() {
      return actual.sslParameters();
    }

    public Optional<Authenticator> authenticator() {
      return actual.authenticator();
    }

    public Version version() {
      return actual.version();
    }

    public Optional<Executor> executor() {
      return actual.executor();
    }

    public void shutdown() {
      actual.shutdown();
    }

    public void shutdownNow() {
      actual.shutdownNow();
    }

    public boolean awaitTermination(Duration duration) throws InterruptedException {
      return actual.awaitTermination(duration);
    }

    public boolean isTerminated() {
      return actual.isTerminated();
    }
  }
}
