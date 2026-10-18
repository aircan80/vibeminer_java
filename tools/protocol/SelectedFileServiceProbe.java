package io.vibeminer.infrastructure.lookup;

import io.vibeminer.application.lookup.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import javax.net.ssl.*;

/** Opt-in real-layer encoded-only acceptance; global supervision includes discovery. */
public final class SelectedFileServiceProbe {
  static final long MIB = 1L << 20;
  static final String CKEY = "1df6b6dbae36878cea020a62b15b9bd6";
  static final String EKEY = "a928b6c9c7ce92d85b638400f81b2fc2";
  static final SelectedFileBudgets FILE_LIMITS =
      new SelectedFileBudgets(
          RootLookupServiceProbe.LIMITS, MIB, 1 << 20, 16, 2 * MIB, Duration.ofSeconds(120));
  static final ArchiveSearchBudgets ARCHIVE_LIMITS =
      new ArchiveSearchBudgets(512, 16 * MIB, 1600, Duration.ofSeconds(120), 8L << 30);

  static final class GlobalBudget {
    final LongSupplier clock;
    final long started;
    long reserved, actual;
    int requests;

    GlobalBudget(LongSupplier clock) {
      this.clock = clock;
      started = clock.getAsLong();
    }

    synchronized long remainingNanos() {
      long left = Duration.ofSeconds(180).toNanos() - (clock.getAsLong() - started);
      if (left <= 0 || Thread.currentThread().isInterrupted())
        throw new IllegalStateException("Global deadline/cancellation");
      return left;
    }

    synchronized void reserve(long count) {
      remainingNanos();
      if (count < 0 || count > 80 * MIB - reserved || requests >= 2048)
        throw new IllegalStateException("Global request/body reservation exhausted");
      requests++;
      reserved += count;
    }

    synchronized void received(long count) {
      actual = Math.addExact(actual, count);
    }

    synchronized void settle(long reservedBytes, long actualBytes) {
      remainingNanos();
      if (actualBytes < 0 || actualBytes > reservedBytes)
        throw new IllegalStateException("Body bound");
      reserved -= reservedBytes - actualBytes;
    }

    <T> T await(CompletionStage<T> stage) throws Exception {
      var future = stage.toCompletableFuture();
      try {
        T value = future.get(remainingNanos(), TimeUnit.NANOSECONDS);
        remainingNanos();
        return value;
      } catch (Exception failure) {
        future.cancel(true);
        throw failure;
      }
    }
  }

  static long reservation(HttpRequest request) {
    if (request.method().equals("HEAD")) return 0;
    if (!request.method().equals("GET")) throw new IllegalArgumentException("GET/HEAD only");
    var ranges = request.headers().allValues("Range");
    if (ranges.size() > 1) throw new IllegalArgumentException("Duplicate range");
    if (!ranges.isEmpty()) {
      String value = ranges.getFirst();
      if (value.equals("bytes=-28")) return 28;
      if (!value.matches("bytes=[0-9]+-[0-9]+"))
        throw new IllegalArgumentException("Exact range only");
      var parts = value.substring(6).split("-");
      long start = Long.parseLong(parts[0]), end = Long.parseLong(parts[1]);
      long count = Math.addExact(Math.subtractExact(end, start), 1);
      if (count < 1 || count > 64 * MIB) throw new IllegalArgumentException("Range bound");
      return count;
    }
    String host = request.uri().getHost(), path = request.uri().getPath();
    if ("us.version.battle.net".equals(host)
        && (path.equals("/v2/products/wow/versions") || path.equals("/v2/products/wow/cdns")))
      return 64 << 10;
    if (host != null && host.endsWith(".cdn.blizzard.com") && path.contains("/config/")) return MIB;
    throw new IllegalArgumentException("Unbounded/unknown resource request");
  }

  static class SupervisedClient extends HttpClient {
    final HttpClient delegate;
    final GlobalBudget budget;
    final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    final AtomicInteger calls = new AtomicInteger();
    final Set<Runnable> active = ConcurrentHashMap.newKeySet();
    final AtomicBoolean closed = new AtomicBoolean();

    SupervisedClient(HttpClient delegate, GlobalBudget budget) {
      this.delegate = delegate;
      this.budget = budget;
    }

    public synchronized <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      if (closed.get()) throw new IllegalStateException("HTTP supervisor closed");
      long cap = reservation(request);
      budget.reserve(cap);
      calls.incrementAndGet();
      var lifecycle = new Object();
      var stopped = new AtomicBoolean();
      var result =
          new CompletableFuture<HttpResponse<T>>() {
            public boolean cancel(boolean interrupt) {
              synchronized (lifecycle) {
                stopped.set(true);
                return super.cancel(interrupt);
              }
            }
          };
      var wire = new AtomicReference<CompletableFuture<HttpResponse<T>>>();
      var subscription = new AtomicReference<Flow.Subscription>();
      var terminal = new AtomicBoolean();
      var targetRef = new AtomicReference<HttpResponse.BodySubscriber<T>>();
      var bytes = new AtomicLong();
      var validBody = new AtomicBoolean();
      var validRoute = new AtomicBoolean();
      var settled = new AtomicBoolean();
      Runnable settle =
          () -> {
            synchronized (lifecycle) {
              if (!stopped.get()
                  && !result.isCompletedExceptionally()
                  && validBody.get()
                  && validRoute.get()
                  && settled.compareAndSet(false, true)) budget.settle(cap, bytes.get());
            }
          };
      MessageDigest digest;
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (Exception e) {
        throw new AssertionError(e);
      }
      Runnable stop =
          () -> {
            synchronized (lifecycle) {
              stopped.set(true);
            }
            var pending = wire.get();
            if (pending != null) pending.cancel(true);
            var body = subscription.get();
            if (body != null) body.cancel();
            var target = targetRef.get();
            if (target != null && terminal.compareAndSet(false, true))
              target.onError(new CancellationException("Global supervision stopped"));
          };
      Runnable abort =
          () -> {
            result.cancel(true);
            stop.run();
          };
      active.add(abort);
      if (closed.get()) abort.run();
      var timer =
          deadlines.schedule(
              () -> {
                stop.run();
                result.completeExceptionally(new TimeoutException("Global deadline"));
              },
              budget.remainingNanos(),
              TimeUnit.NANOSECONDS);
      HttpResponse.BodyHandler<T> bounded =
          info -> {
            var target = handler.apply(info);
            targetRef.set(target);
            return new HttpResponse.BodySubscriber<T>() {
              public CompletionStage<T> getBody() {
                return target.getBody();
              }

              public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                if (stopped.get()) {
                  value.cancel();
                  if (terminal.compareAndSet(false, true))
                    target.onError(new CancellationException());
                } else target.onSubscribe(value);
              }

              public void onNext(List<ByteBuffer> buffers) {
                synchronized (lifecycle) {
                  if (stopped.get() || terminal.get()) return;
                  try {
                    budget.remainingNanos();
                    long count = 0;
                    for (var buffer : buffers) count = Math.addExact(count, buffer.remaining());
                    budget.received(count);
                    if (bytes.addAndGet(count) > cap)
                      throw new IllegalStateException("Global body reservation exceeded");
                    for (var buffer : buffers) digest.update(buffer.asReadOnlyBuffer());
                    target.onNext(buffers);
                  } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                    stop.run();
                  }
                }
              }

              public void onError(Throwable failure) {
                stop.run();
                timer.cancel(false);
                active.remove(abort);
                if (terminal.compareAndSet(false, true)) target.onError(failure);
              }

              public void onComplete() {
                synchronized (lifecycle) {
                  if (stopped.get() || !terminal.compareAndSet(false, true)) return;
                  try {
                    budget.remainingNanos();
                    var lengths = info.headers().allValues("Content-Length");
                    var encodings = info.headers().allValues("Content-Encoding");
                    boolean valid =
                        (info.statusCode() == 200 || info.statusCode() == 206)
                            && lengths.size() <= 1
                            && encodings.size() <= 1
                            && (encodings.isEmpty()
                                || encodings.getFirst().equalsIgnoreCase("identity"))
                            && (lengths.isEmpty()
                                || Long.parseLong(lengths.getFirst()) == bytes.get()
                                || request.method().equals("HEAD"));
                    validBody.set(valid);
                    settle.run();
                    System.out.println(
                        "HTTP RECEIPT method="
                            + request.method()
                            + " requestedUrl="
                            + request.uri()
                            + " status="
                            + info.statusCode()
                            + " bodyBytes="
                            + bytes.get()
                            + " bodySHA256="
                            + HexFormat.of().formatHex(digest.digest())
                            + " conservativeReservation="
                            + cap
                            + " bodyRefundEligible="
                            + valid);
                    if (validRoute.get()) {
                      timer.cancel(false);
                      active.remove(abort);
                    }
                    target.onComplete();
                  } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                    stop.run();
                    target.onError(failure);
                  }
                }
              }
            };
          };
      try {
        var sent = delegate.sendAsync(request, bounded);
        wire.set(sent);
        if (stopped.get()) sent.cancel(true);
        sent.whenComplete(
            (response, failure) -> {
              if (failure != null) result.completeExceptionally(failure);
              else if (!response.uri().equals(request.uri()))
                result.completeExceptionally(
                    new IllegalStateException("Redirect/source substitution"));
              else {
                synchronized (lifecycle) {
                  if (stopped.get()) return;
                  validRoute.set(true);
                  try {
                    budget.remainingNanos();
                    result.complete(response);
                    settle.run();
                    if (terminal.get()) {
                      timer.cancel(false);
                      active.remove(abort);
                    }
                  } catch (Throwable invalid) {
                    result.completeExceptionally(invalid);
                  }
                }
              }
            });
      } catch (Throwable failure) {
        result.completeExceptionally(failure);
      }
      result.whenComplete(
          (response, failure) -> {
            // An InputStream result may be delivered before its complete body. The deadline remains
            // active until the stream subscriber reaches terminal state (see body completion
            // below).
            if (failure != null || result.isCancelled()) {
              stop.run();
              timer.cancel(false);
              active.remove(abort);
            }
          });
      // The timer can safely remain until the global deadline; it closes completed subscriptions.
      return result;
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> push) {
      throw new UnsupportedOperationException("No push");
    }

    public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h)
        throws IOException, InterruptedException {
      try {
        return sendAsync(r, h).get();
      } catch (ExecutionException e) {
        throw new IOException(e.getCause());
      }
    }

    public Optional<CookieHandler> cookieHandler() {
      return delegate.cookieHandler();
    }

    public Optional<Duration> connectTimeout() {
      return delegate.connectTimeout();
    }

    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    public Optional<ProxySelector> proxy() {
      return delegate.proxy();
    }

    public SSLContext sslContext() {
      return delegate.sslContext();
    }

    public SSLParameters sslParameters() {
      return delegate.sslParameters();
    }

    public Optional<Authenticator> authenticator() {
      return delegate.authenticator();
    }

    public Version version() {
      return delegate.version();
    }

    public Optional<Executor> executor() {
      return delegate.executor();
    }

    public void shutdownNow() {
      close();
    }

    public boolean awaitTermination(Duration duration) throws InterruptedException {
      return delegate.awaitTermination(duration);
    }

    public synchronized void close() {
      if (!closed.compareAndSet(false, true)) return;
      for (var abort : List.copyOf(active)) abort.run();
      deadlines.shutdownNow();
      delegate.shutdownNow();
      try {
        if (!delegate.awaitTermination(Duration.ofSeconds(10)))
          throw new IllegalStateException("HTTP delegate did not retire");
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(failure);
      }
    }
  }

  static final class Services implements AutoCloseable {
    final FileObjectStore store;
    final AtomicInteger openStores;
    final OfficialFileLookupGateway roots;
    final OfficialSelectedFileGateway selected;
    final DefaultFileLookupService rootService;
    final DefaultSelectedFileService selectedService;
    final ExecutorService jobs = Executors.newFixedThreadPool(4),
        sources = Executors.newFixedThreadPool(4);

    Services(Path directory, HttpClient client, AtomicInteger openStores) {
      this.openStores = openStores;
      openStores.incrementAndGet();
      store = new FileObjectStore(directory.resolve("store"));
      var sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      var catalog = new SqliteBuildObjectCatalog(sql, store);
      var config = new PinnedCdnTransport(client, Clock.systemUTC(), Duration.ofSeconds(15));
      var range = new PinnedCdnRangeTransport(client, Clock.systemUTC(), Duration.ofSeconds(15));
      roots = new OfficialFileLookupGateway(store, config, range, Clock.systemUTC());
      selected =
          new OfficialSelectedFileGateway(
              store,
              config,
              range,
              new CdnMetadataTransport(client, Clock.systemUTC(), Duration.ofSeconds(15)),
              Clock.systemUTC());
      rootService = new DefaultFileLookupService(roots, catalog, store, jobs, sources);
      selectedService =
          new DefaultSelectedFileService(
              selected,
              catalog,
              new SqliteSelectedFileCatalog(sql, store),
              new SqliteRetentionRepository(sql),
              jobs,
              sources);
    }

    public void close() {
      selectedService.close();
      rootService.close();
      if (!retire(jobs, sources, Duration.ofSeconds(10)))
        throw new IllegalStateException(
            "Workers not retired; CAS remains open and temporary data preserved");
      selected.close();
      roots.close();
      store.close();
      openStores.decrementAndGet();
    }
  }

  static boolean retire(ExecutorService jobs, ExecutorService sources, Duration timeout) {
    jobs.shutdownNow();
    sources.shutdownNow();
    try {
      boolean first = jobs.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
      boolean second = sources.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
      return first && second;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  static boolean cleanup(Path directory, AtomicInteger openStores) throws IOException {
    if (openStores.get() != 0) return false;
    try (var paths = Files.walk(directory)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
    return true;
  }

  static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  static void live(
      Path directory, SupervisedClient client, GlobalBudget budget, AtomicInteger openStores)
      throws Exception {
    // Metadata helper's older ledger is metadata-only; every request is also strictly supervised.
    BuildSnapshot build =
        RootLookupServiceProbe.official(client, new RootLookupServiceProbe.Budget());
    SelectedFileAssociation retained;
    try (var services = new Services(directory, client, openStores)) {
      var root = budget.await(services.rootService.planRoot(build, RootLookupServiceProbe.LIMITS));
      var encoding =
          root.candidates().stream()
              .filter(c -> c.encodingKey().equals(RootLookupServiceProbe.ROOT))
              .findFirst()
              .orElseThrow();
      require(
          encoding.encodedBytes() == 50651441 && root.decodedBytes() == 67292495,
          "Pinned root sizes changed");
      require(
          root.contentKey().equals("e84483ee756c69a7dc5fbff04af81316"), "Pinned root CKey changed");
      System.out.println("CONSENT root encodedBytes=50651441 completeRootVerification=true");
      budget.await(services.rootService.retainRoot(new RootSelectionPlan(root, encoding)));
      var lookup =
          budget.await(
              services.rootService.lookupOffline(
                  build, Set.of(841626L), RootLookupServiceProbe.LIMITS));
      require(lookup.variants().size() == 1, "Expected exact one Item variant");
      var v = lookup.variants().getFirst();
      require(v.contentKey().equals(CKEY), "Pinned Item CKey changed");
      var variant =
          new FileVariantAssociation(
              build,
              lookup.root().source(),
              v.fileDataId(),
              v.contentKey(),
              v.localeFlags(),
              v.contentFlags(),
              v.rawFlagsFirst(),
              v.rawFlagsSecond(),
              v.rawFlagsThird(),
              v.nameHash());
      var candidates =
          budget.await(services.selectedService.planCandidates(build, variant, FILE_LIMITS));
      var candidate =
          candidates.candidates().stream()
              .filter(c -> c.encodingKey().equals(EKEY))
              .findFirst()
              .orElseThrow();
      require(
          candidate.encodedBytes().isPresent() && candidate.encodedBytes().getAsLong() == 738344,
          "Pinned Item EKey size changed");
      System.out.println("CONSENT loose probe exactEKey=" + EKEY + " encodedBytes=738344");
      var probe =
          budget.await(
              services.selectedService.probeLoose(new CandidateChoice(candidates, candidate)));
      require(
          probe.missing().isPresent(), "Expected exact loose404; stop instead of alternative flow");
      System.out.println(
          "CONSENT separately approved archive metadata search budgets=" + ARCHIVE_LIMITS);
      var search =
          budget.await(
              services.selectedService.searchArchives(
                  probe.missing().orElseThrow(), ARCHIVE_LIMITS));
      var plan = search.plan().orElseThrow();
      require(plan.cost().encodedTransferBytes() == 738344, "Unexpected payload cost");
      System.out.println(
          "CONSENT complete encoded payload cost="
              + plan.cost()
              + " archiveCoverage="
              + search.checkedArchives()
              + "/"
              + search.advertisedArchives());
      retained = budget.await(services.selectedService.retain(plan));
      System.out.println(
          "RETAINED encoded="
              + retained.representation()
              + " fullDecodedCKeyVerified=false rowsDecoded=false");
    }
    int before = client.calls.get();
    try (var forbidden =
            new SupervisedClient(HttpClient.newHttpClient(), budget) {
              public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                  HttpRequest r, HttpResponse.BodyHandler<T> h) {
                throw new AssertionError("Offline HTTP forbidden");
              }
            };
        var reopened = new Services(directory, forbidden, openStores)) {
      var actual =
          budget.await(reopened.selectedService.inspectOffline(retained.descriptor(), FILE_LIMITS));
      require(actual.equals(retained), "Fresh offline receipt/association changed");
      require(client.calls.get() == before && forbidden.calls.get() == 0, "Offline HTTP occurred");
      System.out.println(
          "PASS fresh store/catalog/gateways/services offlineHTTP=0 sameActualReceipt=true"
              + " completeEncodedOnly=true fullDecodedCKeyVerified=false rowsDecoded=false"
              + " wholeArchiveVerified=false");
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      SelectedFileServiceProbeSelfTest.main(new String[0]);
      return;
    }
    if (args.length != 1 || !args[0].equals("--live"))
      throw new IllegalArgumentException(
          "Use --self-test or separately authorized --live; no overrides");
    var budget = new GlobalBudget(System::nanoTime);
    var directory = Files.createTempDirectory("vibeminer-selected-service-");
    var openStores = new AtomicInteger();
    try (var client =
        new SupervisedClient(
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build(),
            budget)) {
      System.out.println(
          "GLOBAL ceilings conservativeBodyBytes=83886080 requests=2048 durationSeconds=180"
              + " noOverrides=true");
      live(directory, client, budget, openStores);
    } finally {
      System.out.println(
          "GLOBAL COST conservativeBodyBytes="
              + budget.reserved
              + " observedBodyBytes="
              + budget.actual
              + " requests="
              + budget.requests);
      boolean removed = cleanup(directory, openStores);
      System.out.println(
          "Temporary payloads/catalog removed=" + removed + " preservedIfWorkersActive=true");
    }
  }
}
