package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.infrastructure.lookup.RootLookupFixture.*;

import io.vibeminer.domain.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

/** Synthetic CDN-only HTTP fixture. Unknown routes fail rather than reaching any network. */
final class SelectedFileHttpFixture {
  static final String HOST = "us.cdn.blizzard.com";
  final byte[] config,
      cdnConfig,
      root,
      encodedRoot,
      selected,
      secondSelected,
      archiveIndex,
      archive;
  final String rootCKey, rootEKey, encodingEKey, selectedCKey = "2".repeat(32);
  final String selectedEKey, secondSelectedEKey, archiveKey;
  final int archiveOffset = 53;
  final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  final Map<String, byte[]> bodies = new ConcurrentHashMap<>();
  final Set<String> stalled = ConcurrentHashMap.newKeySet();
  final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
  final AtomicInteger demand = new AtomicInteger(), cancellations = new AtomicInteger();
  final Map<String, Map<String, List<String>>> headerOverrides = new ConcurrentHashMap<>();
  volatile boolean rejectNetwork;
  volatile java.util.function.Consumer<HttpRequest> requestObserver = request -> {};
  private final HttpClient client = new Routes();

  SelectedFileHttpFixture() throws Exception {
    this(false);
  }

  /** Missing selected metadata permits explicit HEAD discovery rather than invented sizes. */
  SelectedFileHttpFixture(boolean missingSelectedMetadata) throws Exception {
    this(missingSelectedMetadata, OptionalLong.empty());
  }

  SelectedFileHttpFixture(boolean missingSelectedMetadata, OptionalLong advertisedIndexSize)
      throws Exception {
    root = root();
    rootCKey = hash("MD5", root);
    encodedRoot = blte(Arrays.copyOfRange(root, 0, 40), Arrays.copyOfRange(root, 40, root.length));
    rootEKey = ekey(encodedRoot);
    selected = concat(new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'E'}, new byte[] {3, 1, 4, 1, 5});
    selectedEKey = hash("MD5", selected);
    secondSelected = blte(new byte[] {8, 6, 7}, new byte[] {5, 3, 0, 9});
    secondSelectedEKey = ekey(secondSelected);
    objects.put(rootEKey, encodedRoot);
    objects.put(selectedEKey, selected);
    objects.put(secondSelectedEKey, secondSelected);
    byte[] cp = new byte[1024], ep = new byte[1024];
    var content = ByteBuffer.wrap(cp).order(ByteOrder.BIG_ENDIAN);
    var ckeys = new TreeMap<String, List<String>>();
    ckeys.put(rootCKey, List.of(rootEKey));
    ckeys.put(selectedCKey, List.of(selectedEKey, secondSelectedEKey));
    for (var entry : ckeys.entrySet()) {
      content.put((byte) entry.getValue().size());
      u40(content, entry.getKey().equals(rootCKey) ? root.length : 7);
      content.put(HexFormat.of().parseHex(entry.getKey()));
      for (String key : entry.getValue()) content.put(HexFormat.of().parseHex(key));
    }
    var ekeys = new TreeSet<String>();
    ekeys.add(rootEKey);
    if (!missingSelectedMetadata) {
      ekeys.add(selectedEKey);
      ekeys.add(secondSelectedEKey);
    }
    var encoding = ByteBuffer.wrap(ep).order(ByteOrder.BIG_ENDIAN);
    int specification = 0;
    for (String key : ekeys) {
      encoding.put(HexFormat.of().parseHex(key)).putInt(specification++);
      u40(encoding, objects.get(key).length);
    }
    var header = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN);
    header
        .put(new byte[] {'E', 'N', 1, 16, 16})
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(1)
        .putInt(1)
        .put((byte) 0)
        .putInt(0);
    byte[] en =
        concat(
            header.array(),
            concat(HexFormat.of().parseHex(ckeys.firstKey()), md5(cp)),
            cp,
            concat(HexFormat.of().parseHex(ekeys.first()), md5(ep)),
            ep);
    byte[] encodedEn = blte(en);
    encodingEKey = ekey(encodedEn);
    objects.put(encodingEKey, encodedEn);
    config =
        ("root = "
                + rootCKey
                + "\nencoding = "
                + hash("MD5", en)
                + " "
                + encodingEKey
                + "\nencoding-size = "
                + en.length
                + " "
                + encodedEn.length
                + "\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] page = new byte[4096];
    ByteBuffer.wrap(page)
        .put(HexFormat.of().parseHex(selectedEKey))
        .putInt(selected.length)
        .putInt(archiveOffset);
    byte[] toc = concat(HexFormat.of().parseHex(selectedEKey), Arrays.copyOf(md5(page), 8));
    byte[] footer = new byte[28];
    System.arraycopy(md5(toc), 0, footer, 0, 8);
    footer[8] = 1;
    footer[11] = 4;
    footer[12] = 4;
    footer[13] = 4;
    footer[14] = 16;
    footer[15] = 8;
    ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1);
    System.arraycopy(md5(Arrays.copyOfRange(footer, 8, 28)), 0, footer, 20, 8);
    archiveKey = hash("MD5", footer);
    archiveIndex = concat(page, toc, footer);
    archive = concat(new byte[archiveOffset], selected, new byte[29]);
    objects.put(archiveKey, archive);
    objects.put(archiveKey + ".index", archiveIndex);
    cdnConfig =
        ("archives = "
                + archiveKey
                + "\n"
                + (advertisedIndexSize.isPresent()
                    ? "archives-index-size = " + advertisedIndexSize.getAsLong() + "\n"
                    : ""))
            .getBytes(StandardCharsets.UTF_8);
  }

  HttpClient client() {
    return client;
  }

  String route(String method, String key) {
    return method + " " + key;
  }

  BuildSnapshot build() throws Exception {
    return build("1.0");
  }

  BuildSnapshot build(String version) throws Exception {
    String buildKey = hash("MD5", config), cdnKey = hash("MD5", cdnConfig);
    byte[] versions =
        ("Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|"
                + buildKey
                + "|"
                + cdnKey
                + "|"
                + version
                + "\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] cdns =
        ("Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n"
                + "us|tpr/wow|"
                + HOST
                + "|https://"
                + HOST
                + "/|tpr/configs/data\n")
            .getBytes(StandardCharsets.UTF_8);
    return new BuildSnapshot(
        "wow",
        "Synthetic",
        "Published",
        "us",
        version,
        buildKey,
        cdnKey,
        NOW,
        false,
        new BuildEvidence(
            "",
            "",
            List.of(
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/versions", NOW, versions),
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/cdns", NOW, cdns))));
  }

  private final class Routes extends HttpClient {
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      if (rejectNetwork) throw new AssertionError("Unexpected offline HTTP");
      requests.add(request);
      requestObserver.accept(request);
      URI uri = request.uri();
      if (!"https".equals(uri.getScheme())
          || !HOST.equals(uri.getHost())
          || uri.getQuery() != null
          || uri.getFragment() != null) throw new AssertionError("Unexpected CDN URI: " + uri);
      String path = uri.getPath(),
          key = path.substring(path.lastIndexOf('/') + 1),
          method = request.method();
      byte[] source;
      try {
        if (path.equals(
            "/tpr/wow/config/" + key.substring(0, 2) + "/" + key.substring(2, 4) + "/" + key)) {
          if (key.equals(hash("MD5", config))) source = config;
          else if (key.equals(hash("MD5", cdnConfig))) source = cdnConfig;
          else throw new AssertionError("Unknown configuration: " + uri);
        } else if (path.equals(
            "/tpr/wow/data/" + key.substring(0, 2) + "/" + key.substring(2, 4) + "/" + key)) {
          source = Objects.requireNonNull(objects.get(key), "Unknown data route: " + uri);
        } else throw new AssertionError("Unexpected CDN path: " + uri);
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
      if (!method.equals("GET") && !method.equals("HEAD"))
        throw new AssertionError("Unexpected method");
      String route = route(method, key);
      int status =
          statuses.getOrDefault(
              route,
              method.equals("GET") && request.headers().firstValue("Range").isPresent()
                  ? 206
                  : 200);
      var headers = new HashMap<String, List<String>>();
      byte[] payload = source;
      if (method.equals("GET") && request.headers().firstValue("Range").isPresent()) {
        String value = request.headers().firstValue("Range").orElseThrow();
        if (!value.matches("bytes=(?:[0-9]+-[0-9]+|-[0-9]+)"))
          throw new AssertionError("Unexpected range: " + value);
        String[] pair = value.substring(6).split("-", -1);
        int start =
            pair[0].isEmpty()
                ? source.length - Integer.parseInt(pair[1])
                : Integer.parseInt(pair[0]);
        int end = pair[0].isEmpty() ? source.length - 1 : Integer.parseInt(pair[1]);
        if (start < 0 || end < start || end >= source.length)
          throw new AssertionError("Invalid range");
        payload = Arrays.copyOfRange(source, start, end + 1);
        headers.put("Content-Range", List.of("bytes " + start + "-" + end + "/" + source.length));
      }
      headers.put(
          "Content-Length",
          List.of(Integer.toString(method.equals("HEAD") ? source.length : payload.length)));
      payload = bodies.getOrDefault(route, payload);
      if (method.equals("HEAD")) payload = new byte[0];
      headers.putAll(headerOverrides.getOrDefault(route, Map.of()));
      HttpHeaders responseHeaders = HttpHeaders.of(headers, (a, b) -> true);
      final int responseStatus = status;
      final byte[] responseBytes = payload;
      var subscriber =
          handler.apply(
              new HttpResponse.ResponseInfo() {
                public int statusCode() {
                  return responseStatus;
                }

                public HttpHeaders headers() {
                  return responseHeaders;
                }

                public Version version() {
                  return Version.HTTP_1_1;
                }
              });
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      var sent = new java.util.concurrent.atomic.AtomicBoolean();
      subscriber.onSubscribe(
          new Flow.Subscription() {
            public void request(long n) {
              demand.incrementAndGet();
              if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("Nonpositive demand"));
                return;
              }
              if (!cancelled.get() && !stalled.contains(route) && sent.compareAndSet(false, true)) {
                if (responseBytes.length > 0)
                  subscriber.onNext(List.of(ByteBuffer.wrap(responseBytes)));
                if (!cancelled.get()) subscriber.onComplete();
              }
            }

            public void cancel() {
              if (cancelled.compareAndSet(false, true)) cancellations.incrementAndGet();
            }
          });
      CompletableFuture<HttpResponse<T>> result =
          subscriber
              .getBody()
              .toCompletableFuture()
              .thenApply(
                  body ->
                      new HttpResponse<T>() {
                        public int statusCode() {
                          return responseStatus;
                        }

                        public HttpRequest request() {
                          return request;
                        }

                        public Optional<HttpResponse<T>> previousResponse() {
                          return Optional.empty();
                        }

                        public HttpHeaders headers() {
                          return responseHeaders;
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
      result.whenComplete(
          (v, f) -> {
            if (result.isCancelled() && cancelled.compareAndSet(false, true))
              cancellations.incrementAndGet();
          });
      return result;
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
        throw new IllegalStateException(e);
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
        HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
      throw new UnsupportedOperationException();
    }
  }
}
