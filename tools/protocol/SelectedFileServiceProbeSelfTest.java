package io.vibeminer.infrastructure.lookup;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.*;
import javax.net.ssl.SSLSession;

/** Generated HTTP body fixtures only; production pipeline fixtures live in issue #76 tests. */
public final class SelectedFileServiceProbeSelfTest {
  static int assertions;

  static void check(boolean value) {
    assertions++;
    if (!value) throw new AssertionError();
  }

  static void refuses(Runnable task) {
    assertions++;
    try {
      task.run();
      throw new AssertionError("Expected refusal");
    } catch (IllegalArgumentException | IllegalStateException expected) {
    }
  }

  static HttpRequest request(String method, String path, String range) {
    var builder = HttpRequest.newBuilder(URI.create("https://us.cdn.blizzard.com" + path));
    if (range != null) builder.header("Range", range);
    return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
  }

  static final class Fake extends SelectedFileServiceProbe.SupervisedClient {
    int status = 206, count = 8, invocations;
    boolean pending, cancelled;
    Runnable beforeBody = () -> {};
    Runnable releaseHeaders;
    HttpResponse.BodySubscriber<?> captured;
    CompletableFuture<?> future;

    Fake() {
      super(
          HttpClient.newHttpClient(), new SelectedFileServiceProbe.GlobalBudget(System::nanoTime));
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      invocations++;
      var result = new CompletableFuture<HttpResponse<T>>();
      future = result;
      Runnable delivery =
          () -> {
            var headers =
                HttpHeaders.of(
                    Map.of("Content-Length", List.of(Integer.toString(count))), (a, b) -> true);
            var subscriber =
                handler.apply(
                    new HttpResponse.ResponseInfo() {
                      public int statusCode() {
                        return status;
                      }

                      public HttpHeaders headers() {
                        return headers;
                      }

                      public HttpClient.Version version() {
                        return HttpClient.Version.HTTP_1_1;
                      }
                    });
            captured = subscriber;
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  boolean delivered;

                  public void request(long n) {
                    if (delivered || cancelled) return;
                    delivered = true;
                    beforeBody.run();
                    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[count])));
                    if (!cancelled) subscriber.onComplete();
                  }

                  public void cancel() {
                    cancelled = true;
                  }
                });
            subscriber
                .getBody()
                .whenComplete(
                    (body, failure) -> {
                      if (failure != null) result.completeExceptionally(failure);
                      else
                        result.complete(
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
                                return body;
                              }

                              public Optional<SSLSession> sslSession() {
                                return Optional.empty();
                              }

                              public URI uri() {
                                return request.uri();
                              }

                              public HttpClient.Version version() {
                                return HttpClient.Version.HTTP_1_1;
                              }
                            });
                    });
          };
      releaseHeaders = delivery;
      if (!pending) delivery.run();
      return result;
    }
  }

  static final class Late extends SelectedFileServiceProbe.SupervisedClient {
    final Fake fake;
    CompletableFuture<HttpResponse<Object>> delayed;
    HttpResponse<Object> actual;

    Late(Fake fake) {
      super(fake, new SelectedFileServiceProbe.GlobalBudget(System::nanoTime));
      this.fake = fake;
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      actual = (HttpResponse<Object>) (HttpResponse<?>) fake.sendAsync(request, handler).join();
      delayed =
          new CompletableFuture<>() {
            public boolean cancel(boolean interrupt) {
              return false;
            }
          };
      return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) delayed;
    }
  }

  public static void main(String[] args) throws Exception {
    var clock = new AtomicLong();
    var budget = new SelectedFileServiceProbe.GlobalBudget(clock::get);
    budget.reserve(80L << 20);
    check(budget.reserved == 80L << 20);
    refuses(() -> budget.reserve(1));
    check(budget.requests == 1);
    budget.settle(80L << 20, 4);
    check(budget.reserved == 4);
    refuses(() -> budget.settle(1, 2));
    var requests = new SelectedFileServiceProbe.GlobalBudget(clock::get);
    for (int i = 0; i < 2048; i++) requests.reserve(0);
    refuses(() -> requests.reserve(0));
    check(requests.requests == 2048);
    clock.set(Duration.ofSeconds(180).toNanos());
    refuses(() -> budget.reserve(0));
    Thread.currentThread().interrupt();
    refuses(() -> new SelectedFileServiceProbe.GlobalBudget(System::nanoTime).reserve(0));
    Thread.interrupted();
    check(SelectedFileServiceProbe.reservation(request("HEAD", "/data/x", null)) == 0);
    check(SelectedFileServiceProbe.reservation(request("GET", "/data/x", "bytes=-28")) == 28);
    check(SelectedFileServiceProbe.reservation(request("GET", "/data/x", "bytes=10-17")) == 8);
    check(
        SelectedFileServiceProbe.reservation(request("GET", "/tpr/wow/config/aa/bb/x", null))
            == 1L << 20);
    check(
        SelectedFileServiceProbe.reservation(
                HttpRequest.newBuilder(
                        URI.create("https://us.version.battle.net/v2/products/wow/versions"))
                    .build())
            == 65536);
    refuses(() -> SelectedFileServiceProbe.reservation(request("GET", "/data/x", null)));
    refuses(() -> SelectedFileServiceProbe.reservation(request("GET", "/data/x", "bytes=-29")));
    refuses(() -> SelectedFileServiceProbe.reservation(request("GET", "/data/x", "bytes=17-10")));
    refuses(
        () -> SelectedFileServiceProbe.reservation(request("GET", "/data/x", "bytes=0-67108864")));
    try (var fake = new Fake();
        var supervised =
            new SelectedFileServiceProbe.SupervisedClient(
                fake, new SelectedFileServiceProbe.GlobalBudget(System::nanoTime))) {
      var response =
          supervised
              .sendAsync(
                  request("GET", "/data/x", "bytes=0-7"), HttpResponse.BodyHandlers.ofByteArray())
              .join();
      check(response.body().length == 8);
      check(supervised.budget.actual == 8);
      check(supervised.budget.reserved == 8);
    }
    try (var fake = new Fake();
        var supervised =
            new SelectedFileServiceProbe.SupervisedClient(
                fake, new SelectedFileServiceProbe.GlobalBudget(System::nanoTime))) {
      fake.status = 404;
      fake.count = 2;
      supervised
          .sendAsync(
              request("GET", "/data/x", "bytes=0-7"), HttpResponse.BodyHandlers.ofByteArray())
          .join();
      check(supervised.budget.actual == 2);
      check(supervised.budget.reserved == 8);
    }
    try (var fake = new Fake();
        var supervised =
            new SelectedFileServiceProbe.SupervisedClient(
                fake, new SelectedFileServiceProbe.GlobalBudget(System::nanoTime))) {
      fake.count = 9;
      try {
        supervised
            .sendAsync(
                request("GET", "/data/x", "bytes=0-7"), HttpResponse.BodyHandlers.ofByteArray())
            .join();
        throw new AssertionError();
      } catch (CompletionException expected) {
        check(true);
      }
      check(fake.cancelled);
      check(supervised.budget.reserved == 8);
      check(supervised.budget.actual == 9);
    }
    try (var fake = new Fake();
        var supervised =
            new SelectedFileServiceProbe.SupervisedClient(
                fake, new SelectedFileServiceProbe.GlobalBudget(System::nanoTime))) {
      fake.pending = true;
      var result =
          supervised.sendAsync(
              request("GET", "/data/x", "bytes=0-7"), HttpResponse.BodyHandlers.ofByteArray());
      result.cancel(true);
      check(fake.future.isCancelled());
      fake.releaseHeaders.run();
      check(fake.cancelled);
      check(supervised.budget.actual == 0);
      check(supervised.budget.reserved == 8);
    }
    try (var fake = new Fake();
        var supervised =
            new SelectedFileServiceProbe.SupervisedClient(
                fake, new SelectedFileServiceProbe.GlobalBudget(System::nanoTime))) {
      supervised.budget.reserve(80L << 20);
      refuses(
          () ->
              supervised.sendAsync(
                  request("GET", "/data/x", "bytes=0-7"), HttpResponse.BodyHandlers.ofByteArray()));
      check(fake.invocations == 0);
    }
    var expiredClock = new AtomicLong();
    try (var fake = new Fake();
        var supervised =
            new SelectedFileServiceProbe.SupervisedClient(
                fake, new SelectedFileServiceProbe.GlobalBudget(expiredClock::get))) {
      fake.beforeBody = () -> expiredClock.set(Duration.ofSeconds(180).toNanos());
      try {
        supervised
            .sendAsync(
                request("GET", "/data/x", "bytes=0-7"), HttpResponse.BodyHandlers.ofByteArray())
            .join();
        throw new AssertionError("Expired body accepted");
      } catch (CompletionException expected) {
        check(true);
      }
      check(fake.cancelled);
      check(supervised.budget.reserved == 8);
    }
    var awaitClock = new AtomicLong();
    var awaitedBudget = new SelectedFileServiceProbe.GlobalBudget(awaitClock::get);
    var pendingStage = new CompletableFuture<Void>();
    awaitClock.set(Duration.ofSeconds(180).toNanos());
    try {
      awaitedBudget.await(pendingStage);
      throw new AssertionError();
    } catch (IllegalStateException expected) {
      check(pendingStage.isCancelled());
    }
    var postClock = new AtomicLong();
    var postBudget = new SelectedFileServiceProbe.GlobalBudget(postClock::get);
    var postCancelled = new AtomicBoolean();
    var completed =
        new CompletableFuture<String>() {
          public String get(long timeout, TimeUnit unit) {
            postClock.set(Duration.ofSeconds(180).toNanos());
            return "too-late";
          }

          public boolean cancel(boolean interrupt) {
            postCancelled.set(true);
            return super.cancel(interrupt);
          }
        };
    completed.complete("ready");
    try {
      postBudget.await(completed);
      throw new AssertionError("Post-get deadline accepted");
    } catch (IllegalStateException expected) {
      check(postCancelled.get());
    }
    try (var fake = new Fake();
        var late = new Late(fake);
        var wrapper =
            new SelectedFileServiceProbe.SupervisedClient(
                late, new SelectedFileServiceProbe.GlobalBudget(System::nanoTime))) {
      fake.count = 2;
      var result =
          wrapper.sendAsync(
              request("GET", "/data/x", "bytes=0-7"), HttpResponse.BodyHandlers.ofByteArray());
      check(!result.isDone());
      fake.captured.onNext(List.of(ByteBuffer.wrap(new byte[4])));
      check(wrapper.budget.actual == 2);
      result.cancel(true);
      fake.captured.onNext(List.of(ByteBuffer.wrap(new byte[4])));
      check(wrapper.budget.actual == 2);
      late.delayed.complete(late.actual);
      check(result.isCancelled());
      check(wrapper.budget.reserved == 8);
    }
    try (var fake = new Fake()) {
      fake.pending = true;
      var wrapper =
          new SelectedFileServiceProbe.SupervisedClient(
              fake, new SelectedFileServiceProbe.GlobalBudget(System::nanoTime));
      var result =
          wrapper.sendAsync(
              request("GET", "/data/x", "bytes=0-7"), HttpResponse.BodyHandlers.ofByteArray());
      wrapper.close();
      check(result.isDone());
      check(fake.future.isCancelled());
    }
    var jobs = Executors.newSingleThreadExecutor();
    var sources = Executors.newSingleThreadExecutor();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    jobs.submit(
        () -> {
          entered.countDown();
          boolean released = false;
          while (!released) {
            try {
              release.await();
              released = true;
            } catch (InterruptedException ignored) {
            }
          }
        });
    check(entered.await(2, TimeUnit.SECONDS));
    var cleanupDirectory =
        java.nio.file.Files.createTempDirectory("selected-probe-cleanup-selftest-");
    var marker = cleanupDirectory.resolve("synthetic-marker");
    java.nio.file.Files.writeString(marker, "fixture metadata");
    var openStores = new AtomicInteger(1);
    try {
      check(!SelectedFileServiceProbe.retire(jobs, sources, Duration.ofMillis(5)));
      check(!SelectedFileServiceProbe.cleanup(cleanupDirectory, openStores));
      check(java.nio.file.Files.exists(marker));
    } finally {
      release.countDown();
      jobs.shutdownNow();
      sources.shutdownNow();
    }
    check(SelectedFileServiceProbe.retire(jobs, sources, Duration.ofSeconds(2)));
    openStores.decrementAndGet();
    check(SelectedFileServiceProbe.cleanup(cleanupDirectory, openStores));
    check(!java.nio.file.Files.exists(cleanupDirectory));
    System.out.println(
        "Selected-file production acceptance offline supervisor PASS assertions="
            + assertions
            + " networkRequests=0 payloadFixtures=generated-http-bodies-only");
  }
}
