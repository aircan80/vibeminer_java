package io.vibeminer.bootstrap;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

/**
 * Authored CDN vectors for tools-only full Atlas Open review; no JUnit, live bytes or runtime
 * inputs.
 */
public final class BuildOpeningReviewData {
  public static final String PRODUCT = "wow", REGION = "us", VERSION = "authored-open";
  public static final long GAME_FILE_DATA_ID = 25;
  private final Map<String, byte[]> objects = new HashMap<>();
  private final Map<String, String> keys = new HashMap<>();
  private final AtomicInteger requests = new AtomicInteger(), gameRequests = new AtomicInteger();
  private final byte[] versions, cdns;
  private final String gameEKey;
  private volatile boolean offline, holdEncoding;
  private final CountDownLatch encodingEntered = new CountDownLatch(1),
      encodingRelease = new CountDownLatch(1);

  public BuildOpeningReviewData() throws Exception {
    var decoded = new LinkedHashMap<String, byte[]>();
    var encoded = new LinkedHashMap<String, byte[]>();
    byte[] game = "VibeMiner authored tiny member\n".getBytes(StandardCharsets.UTF_8);
    byte[] gameEncoded = blte(game);
    gameEKey = ekey(gameEncoded);
    String gameCKey = hash("MD5", game);
    byte[] root = root(gameCKey);
    decoded.put("root", root);
    encoded.put(
        "root", blte(Arrays.copyOfRange(root, 0, 40), Arrays.copyOfRange(root, 40, root.length)));
    decoded.put("install", install());
    decoded.put("download", download());
    decoded.put("size", size());
    for (String role : List.of("install", "download", "size"))
      encoded.put(role, blte(decoded.get(role)));
    decoded.put("game", game);
    encoded.put("game", gameEncoded);
    byte[] en = encoding(decoded, encoded);
    decoded.put("encoding", en);
    encoded.put("encoding", blte(en));
    var raw = new StringBuilder("root = " + hash("MD5", root) + "\n");
    for (String role : List.of("install", "encoding", "download", "size")) {
      raw.append(role)
          .append(" = ")
          .append(hash("MD5", decoded.get(role)))
          .append(' ')
          .append(ekey(encoded.get(role)))
          .append('\n');
      raw.append(role)
          .append("-size = ")
          .append(decoded.get(role).length)
          .append(' ')
          .append(encoded.get(role).length)
          .append('\n');
    }
    byte[] cfg = raw.toString().getBytes(StandardCharsets.UTF_8);
    byte[] cdn =
        ("archives = " + "a".repeat(32) + "\narchives-index-size = 173068\n")
            .getBytes(StandardCharsets.UTF_8);
    String configKey = hash("MD5", cfg), cdnKey = hash("MD5", cdn);
    objects.put(configKey, cfg);
    objects.put(cdnKey, cdn);
    for (var entry : encoded.entrySet()) {
      keys.put(entry.getKey(), ekey(entry.getValue()));
      objects.put(ekey(entry.getValue()), entry.getValue());
    }
    versions =
        ("Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|"
                + configKey
                + "|"
                + cdnKey
                + "|"
                + VERSION
                + "\n")
            .getBytes(StandardCharsets.UTF_8);
    cdns =
        "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\nus|tpr/wow|us.cdn.blizzard.com|https://us.cdn.blizzard.com/|tpr/wow\n"
            .getBytes(StandardCharsets.UTF_8);
  }

  public HttpClient newClient() {
    return new Client();
  }

  public void holdEncoding() {
    holdEncoding = true;
  }

  public boolean awaitEncoding(Duration timeout) throws InterruptedException {
    return encodingEntered.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  public void releaseEncoding() {
    holdEncoding = false;
    encodingRelease.countDown();
  }

  public void offline() {
    offline = true;
  }

  public int requests() {
    return requests.get();
  }

  public int gameObjectRequests() {
    return gameRequests.get();
  }

  public String gameContentKey() {
    try {
      return hash("MD5", "VibeMiner authored tiny member\n".getBytes(StandardCharsets.UTF_8));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  public String gameEncodingKey() {
    return gameEKey;
  }

  static byte[] root(String ckey) {
    var out = ByteBuffer.allocate(69).order(ByteOrder.LITTLE_ENDIAN);
    out.put(new byte[] {'T', 'S', 'F', 'M'}).putInt(24).putInt(2).putInt(1).putInt(1).putInt(0);
    out.putInt(1).putInt(2).putInt(0x20000000).putInt(4).put((byte) 0);
    out.putInt(25).put(HexFormat.of().parseHex(ckey)).putLong(0x123L);
    return out.array();
  }

  int objectsGameLength() throws Exception {
    return blte("VibeMiner authored tiny member\n".getBytes(StandardCharsets.UTF_8)).length;
  }

  byte[] install() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("IN");
    out.writeByte(1);
    out.writeByte(16);
    out.writeShort(0);
    out.writeInt(1);
    out.writeBytes("authored-file");
    out.writeByte(0);
    out.write(HexFormat.of().parseHex(gameContentKey()));
    out.writeInt("VibeMiner authored tiny member\n".getBytes(StandardCharsets.UTF_8).length);
    return bytes.toByteArray();
  }

  byte[] download() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("DL");
    out.writeByte(1);
    out.writeByte(16);
    out.writeByte(0);
    out.writeInt(1);
    out.writeShort(0);
    out.write(HexFormat.of().parseHex(gameEKey));
    u40(out, objectsGameLength());
    out.writeByte(255);
    return bytes.toByteArray();
  }

  byte[] size() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("DS");
    out.writeByte(1);
    out.writeByte(9);
    out.writeInt(1);
    out.writeShort(0);
    u40(out, objectsGameLength());
    out.write(Arrays.copyOf(HexFormat.of().parseHex(gameEKey), 9));
    out.writeInt(objectsGameLength());
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
      content.write(HexFormat.of().parseHex(ekey(encoded.get(role))));
    }
    byte[] cpage = Arrays.copyOf(cp.toByteArray(), 1024);
    var ep = new ByteArrayOutputStream();
    var entries = new DataOutputStream(ep);
    var keys = new TreeMap<String, byte[]>();
    for (byte[] bytes : encoded.values()) keys.put(ekey(bytes), bytes);
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
    out.write(md5(cpage));
    out.write(cpage);
    out.write(HexFormat.of().parseHex(keys.firstKey()));
    out.write(md5(epage));
    out.write(epage);
    return bytes.toByteArray();
  }

  static void u40(DataOutputStream out, long value) throws IOException {
    for (int shift = 32; shift >= 0; shift -= 8) out.writeByte((int) (value >>> shift));
  }

  static String hash(String algorithm, byte[] bytes) throws Exception {
    return HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance(algorithm).digest(bytes));
  }

  static byte[] md5(byte[] value) throws Exception {
    return MessageDigest.getInstance("MD5").digest(value);
  }

  static String ekey(byte[] encoded) throws Exception {
    return hash("MD5", Arrays.copyOf(encoded, ByteBuffer.wrap(encoded).getInt(4)));
  }

  static byte[] concat(byte[]... parts) throws IOException {
    var out = new ByteArrayOutputStream();
    for (byte[] part : parts) out.write(part);
    return out.toByteArray();
  }

  static void u40(ByteBuffer out, long value) {
    out.put((byte) (value >>> 32)).putInt((int) value);
  }

  static byte[] blte(byte[]... decoded) throws Exception {
    var header = new ByteArrayOutputStream();
    var out = new DataOutputStream(header);
    out.writeBytes("BLTE");
    out.writeInt(12 + 24 * decoded.length);
    out.writeInt(0x0f000000 | decoded.length);
    var body = new ByteArrayOutputStream();
    for (int i = 0; i < decoded.length; i++) {
      byte[] encoded;
      if (i % 2 == 0) encoded = concat(new byte[] {'N'}, decoded[i]);
      else {
        var zipped = new ByteArrayOutputStream();
        zipped.write('Z');
        try (var zip = new java.util.zip.DeflaterOutputStream(zipped)) {
          zip.write(decoded[i]);
        }
        encoded = zipped.toByteArray();
      }
      out.writeInt(encoded.length);
      out.writeInt(decoded[i].length);
      out.write(md5(encoded));
      body.write(encoded);
    }
    return concat(header.toByteArray(), body.toByteArray());
  }

  private final class Client extends HttpClient {
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      if (offline) throw new AssertionError("Authored offline reopen attempted HTTP");
      if (!request.uri().getScheme().equals("https")
          || request.uri().getRawQuery() != null
          || request.uri().getPort() != -1
          || !Set.of("GET", "HEAD").contains(request.method()))
        throw new AssertionError("Unexpected authored HTTP request");
      requests.incrementAndGet();
      String path = request.uri().getPath();
      String key = path.substring(path.lastIndexOf('/') + 1);
      byte[] whole;
      if (request.uri().getHost().equals("us.version.battle.net")
          && path.equals("/v2/products/wow/versions")) whole = versions;
      else if (request.uri().getHost().equals("us.version.battle.net")
          && path.equals("/v2/products/wow/cdns")) whole = cdns;
      else {
        if (!request.uri().getHost().equals("us.cdn.blizzard.com")
            || !path.matches("/tpr/wow/(config|data)/[a-f0-9]{2}/[a-f0-9]{2}/[a-f0-9]{32}"))
          throw new AssertionError("Unexpected authored route: " + request.uri());
        whole = objects.get(key);
      }
      if (key.equals(gameEKey)) gameRequests.incrementAndGet();
      if (key.equals(keys.get("encoding")) && holdEncoding && !request.method().equals("HEAD")) {
        encodingEntered.countDown();
        try {
          if (!encodingRelease.await(30, TimeUnit.SECONDS))
            throw new AssertionError("Authored EN gate was not released");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new CancellationException("Authored EN gate interrupted");
        }
      }
      var range = request.headers().firstValue("Range");
      int status = whole == null ? 404 : range.isPresent() ? 206 : 200;
      byte[] bytes = whole;
      Map<String, List<String>> fields;
      if (status == 404) {
        bytes = new byte[0];
        fields = Map.of("Content-Length", List.of("0"));
      } else if (range.isPresent()) {
        String[] offsets = range.get().substring(6).split("-");
        int start = Integer.parseInt(offsets[0]), end = Integer.parseInt(offsets[1]);
        if (start < 0 || end < start || end >= whole.length)
          throw new AssertionError("Authored range exceeds its exact source");
        bytes = Arrays.copyOfRange(whole, start, end + 1);
        fields =
            Map.of(
                "Content-Range",
                List.of("bytes " + start + "-" + end + "/" + whole.length),
                "Content-Length",
                List.of(Integer.toString(bytes.length)));
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
      if (!request.method().equals("HEAD")) body.onNext(List.of(ByteBuffer.wrap(bytes)));
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
