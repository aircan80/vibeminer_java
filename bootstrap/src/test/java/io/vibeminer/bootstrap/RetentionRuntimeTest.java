package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.application.retention.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.*;
import io.vibeminer.infrastructure.bootstrap.*;
import io.vibeminer.infrastructure.persistence.*;
import io.vibeminer.infrastructure.retention.*;
import io.vibeminer.infrastructure.storage.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionRuntimeTest {
  static final class ForbiddenHttp extends HttpClient {
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    public Optional<Duration> connectTimeout() {
      return Optional.of(Duration.ofSeconds(1));
    }

    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    public SSLContext sslContext() {
      throw new AssertionError("No TLS setup");
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
      throw new AssertionError("Forbidden HTTP " + request.uri());
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new AssertionError("Forbidden HTTP " + request.uri());
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) {
      throw new AssertionError("Forbidden HTTP " + request.uri());
    }
  }

  @TempDir Path directory;

  static String md5(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
  }

  static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void productionRuntimePinsReusedInstallScopeInSharedDurableCatalog() throws Exception {
    // All bytes are generated locally. Seeding verified aliases makes both runtime actions offline.
    byte[] decoded = new byte[] {'I', 'N', 1, 16, 0, 0, 0, 0, 0, 0};
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeBytes("BLTE");
      out.writeInt(0);
      out.writeByte('N');
      out.write(decoded);
    }
    byte[] encoded = bytes.toByteArray();
    byte[] config =
        text(
            "install = "
                + md5(decoded)
                + " "
                + md5(encoded)
                + "\ninstall-size = "
                + decoded.length
                + " "
                + encoded.length
                + "\n");
    String configKey = md5(config), cdnKey = "a".repeat(32);
    var now = Instant.EPOCH;
    var versions =
        new SourceDocument(
            "https://us.version.battle.net/v2/products/wow/versions",
            now,
            text(
                "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|"
                    + configKey
                    + "|"
                    + cdnKey
                    + "|synthetic\n"));
    var cdns =
        new SourceDocument(
            "https://us.version.battle.net/v2/products/wow/cdns",
            now,
            text(
                "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n"
                    + "us|tpr/wow|us.cdn.blizzard.com|https://us.cdn.blizzard.com/|tpr/configs/data\n"));
    var build =
        new BuildSnapshot(
            "wow",
            "Synthetic",
            "Published",
            "us",
            "synthetic",
            configKey,
            cdnKey,
            now,
            true,
            new BuildEvidence("", "", List.of(versions, cdns)));
    RetentionScope scope;
    var sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("content"))) {
      var catalog = new SqliteBuildObjectCatalog(sql, store);
      var gateway =
          new OfficialBootstrapArtifactGateway(
              store,
              new PinnedCdnTransport(new ForbiddenHttp(), Clock.systemUTC(), Duration.ofSeconds(1)),
              Clock.systemUTC());
      var document =
          new SourceDocument(
              CdnRequest.configuration(CapturedBuildSource.resolve(build), configKey)
                  .uri()
                  .toString(),
              now,
              config);
      var plan =
          new InstallPlan(
              build, document, md5(decoded), md5(encoded), decoded.length, encoded.length);
      var configuration = gateway.retainConfiguration(plan, CancellationToken.none());
      var object =
          store.publish(
              new ByteArrayInputStream(encoded),
              new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, md5(encoded), encoded.length),
              encoded.length,
              CancellationToken.none());
      var identity = gateway.installIdentity(plan);
      var proof =
          new SourceVerificationEvidence(
              identity.verificationSemantics(),
              "https://us.cdn.blizzard.com/tpr/wow/data/"
                  + md5(encoded).substring(0, 2)
                  + "/"
                  + md5(encoded).substring(2, 4)
                  + "/"
                  + md5(encoded),
              now);
      gateway.inspectRetained(
          plan, new VerifiedSourceAlias(identity, object, proof), CancellationToken.none());
      catalog.registerSnapshot(build);
      catalog.attachVerifiedObject(
          build.snapshotId(),
          "BUILD_CONFIG",
          configuration.source(),
          configuration.object(),
          configuration.evidence(),
          CancellationToken.none());
      // A different build's reusable bytes need no membership in this build yet.
      var other =
          new BuildSnapshot(
              build.productId(),
              build.editionLabel(),
              build.channel(),
              build.region(),
              "other",
              build.buildConfig(),
              build.cdnConfig(),
              now,
              true);
      catalog.registerSnapshot(other);
      catalog.attachVerifiedObject(
          other.snapshotId(),
          "INSTALL_MANIFEST",
          identity,
          object,
          proof,
          CancellationToken.none());
      scope =
          new RetentionScope(
              build.snapshotId(),
              ScopeKind.INSTALL_METADATA,
              List.of(
                  new ScopeRequirement(
                      "BUILD_CONFIG",
                      Optional.of(configuration.source()),
                      OptionalLong.of(config.length),
                      OptionalLong.of(config.length)),
                  new ScopeRequirement(
                      "INSTALL_MANIFEST",
                      Optional.of(identity),
                      OptionalLong.of(encoded.length),
                      OptionalLong.of(decoded.length))));
    }
    try (var runtime = ContentRuntime.open(directory, ForbiddenHttp::new)) {
      var plan =
          runtime
              .service()
              .inspectConfiguration(build)
              .toCompletableFuture()
              .get(3, TimeUnit.SECONDS);
      try (var connection = sql.connect();
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery("SELECT count(*) FROM retention_scopes WHERE pinned=1")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1));
      }
      assertTrue(
          runtime
              .service()
              .download(plan)
              .toCompletableFuture()
              .get(3, TimeUnit.SECONDS)
              .entries()
              .isEmpty());
      var view =
          new SqliteRetentionRepository(sql)
              .readView(scope.scopeId(), RetentionLimits.defaults(), CancellationToken.none());
      assertTrue(view.pinned());
      assertEquals(scope, view.selected());
    }
    assertTrue(
        new SqliteRetentionRepository(new SqliteCatalog(directory.resolve("catalog.sqlite")))
            .readView(scope.scopeId(), RetentionLimits.defaults(), CancellationToken.none())
            .pinned());
  }
}
