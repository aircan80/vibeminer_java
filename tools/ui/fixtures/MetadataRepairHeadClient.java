package io.vibeminer.infrastructure.lookup;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

/** Tool-only HEAD responses consumed by the actual pinned metadata transport; never real HTTP. */
public final class MetadataRepairHeadClient extends HttpClient {
  public final AtomicInteger calls = new AtomicInteger();
  public final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());
  public final Map<String, Long> lengths = new ConcurrentHashMap<>();
  public volatile int status = 200;
  public volatile boolean reject;

  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    if (reject) throw new AssertionError("Restart must not make HEAD requests");
    if (!request.method().equals("HEAD")
        || !request.uri().getScheme().equals("https")
        || !request.uri().getHost().equals("us.cdn.blizzard.com")
        || !request.headers().firstValue("Accept-Encoding").orElse("").equals("identity"))
      throw new AssertionError("Repair planning did not use exact pinned HEAD");
    calls.incrementAndGet();
    requests.add(request);
    String path = request.uri().getPath();
    String key = path.substring(path.lastIndexOf('/') + 1);
    Long length = lengths.get(key);
    if (length == null) throw new AssertionError("Unexpected HEAD identity: " + key);
    int code = status;
    var headers =
        HttpHeaders.of(Map.of("Content-Length", List.of(Long.toString(length))), (a, b) -> true);
    var subscriber =
        handler.apply(
            new HttpResponse.ResponseInfo() {
              public int statusCode() {
                return code;
              }

              public HttpHeaders headers() {
                return headers;
              }

              public Version version() {
                return Version.HTTP_1_1;
              }
            });
    subscriber.onSubscribe(
        new Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {}
        });
    subscriber.onComplete();
    return subscriber
        .getBody()
        .toCompletableFuture()
        .thenApply(
            body ->
                new HttpResponse<T>() {
                  public int statusCode() {
                    return code;
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

  public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) {
    throw new AssertionError("No synchronous HTTP");
  }

  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
    throw new AssertionError("No push requests");
  }
}
