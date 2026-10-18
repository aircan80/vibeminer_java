package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RootMetadataReuseGatewayTest {
  @TempDir Path directory;

  static byte[] text(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  static BuildSnapshot build(byte[] config) throws Exception {
    String key = RootLookupFixture.hash("MD5", config), cdn = "e".repeat(32);
    var now = RootLookupFixture.NOW;
    return new BuildSnapshot(
        "wow",
        "Retail",
        "Published",
        "us",
        "second",
        key,
        cdn,
        now,
        false,
        new BuildEvidence(
            "",
            "",
            List.of(
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/versions",
                    now,
                    text(
                        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n"
                            + "us|"
                            + key
                            + "|"
                            + cdn
                            + "|second\n")),
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/cdns",
                    now,
                    text(
                        "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n"
                            + "us|tpr/wow|us.cdn.blizzard.com|https://us.cdn.blizzard.com/|tpr/configs/data\n")))));
  }

  record Setup(
      FileObjectStore store,
      RootLookupFixture fixture,
      MetadataContentProof actual,
      MetadataDeclaration declaration)
      implements AutoCloseable {
    public void close() {
      store.close();
    }
  }

  Setup setup(boolean badGrammar) throws Exception {
    var fixture = new RootLookupFixture(false, badGrammar);
    var store = new FileObjectStore(directory);
    var captured = new AtomicReference<MetadataContentProof>();
    try (var gateway =
        new OfficialFileLookupGateway(
            store, fixture, Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC))) {
      var plan =
          gateway.prepareRoot(
              fixture.build("us.cdn.blizzard.com", "first"),
              Optional.empty(),
              LookupBudgets.defaults(),
              CancellationToken.none());
      var observer =
          new MetadataPublicationObserver() {
            public void beforePromotion(MetadataContentProof p, CancellationToken t) {
              captured.set(p);
            }

            public void afterStructureValidation(MetadataContentProof p, CancellationToken t) {
              captured.set(p);
            }
          };
      if (badGrammar) {
        assertThrows(
            MetadataAcquisitionFailure.class,
            () ->
                gateway.acquireRoot(
                    new RootSelectionPlan(plan, plan.candidates().getFirst()),
                    observer,
                    CancellationToken.none()));
        var decodedOnly = captured.get();
        captured.set(
            decodedOnly.withAttestations(
                decodedOnly.decodedAttestation(),
                Optional.of(
                    new MetadataStructureAttestation(
                        MetadataStructureAttestation.Kind.ROOT, "wow-root-tsfm-v1v2-r1"))));
      } else
        gateway.acquireRoot(
            new RootSelectionPlan(plan, plan.candidates().getFirst()),
            observer,
            CancellationToken.none());
    }
    // A distinct valid encoding B exists, but this new root configuration declares only CKey.
    byte[] other = RootLookupFixture.blte(fixture.root);
    assertNotEquals(fixture.rootEKey, RootLookupFixture.ekey(other));
    byte[] config =
        text("root = " + fixture.rootCKey + "\n# second exact build, no EN acquisition required\n");
    var build = build(config);
    var object =
        store.publish(
            new ByteArrayInputStream(config),
            new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, build.buildConfig(), config.length),
            config.length,
            CancellationToken.none());
    var configAlias =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(
                "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1"),
            object,
            new SourceVerificationEvidence(
                "tact-config-raw-md5-v1",
                CdnRequest.configuration(CapturedBuildSource.resolve(build), build.buildConfig())
                    .uri()
                    .toString(),
                RootLookupFixture.NOW));
    var declaration =
        new MetadataDeclaration(
            build,
            configAlias,
            MetadataStructureAttestation.Kind.ROOT,
            new ContentSlotKey("wow", fixture.rootCKey),
            Optional.empty(),
            OptionalLong.empty(),
            OptionalLong.empty());
    fixture.rejectNetwork = true;
    return new Setup(store, fixture, captured.get(), declaration);
  }

  static MetadataReusePlan plan(Setup setup) {
    return new MetadataReusePlan(
        UUID.randomUUID(), UUID.randomUUID(), setup.declaration(), setup.actual());
  }

  @Test
  void newRootConfigurationReplaysAWithoutAnyCurrentEncodingMembershipOrNetwork() throws Exception {
    try (var setup = setup(false);
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      int requests = setup.fixture().requests.size();
      long count;
      try (var files = Files.walk(directory.resolve("objects"))) {
        count = files.filter(Files::isRegularFile).count();
      }
      assertEquals(
          setup.actual(),
          gateway.replay(plan(setup), LookupBudgets.defaults(), CancellationToken.none()));
      assertEquals(requests, setup.fixture().requests.size());
      try (var files = Files.walk(directory.resolve("objects"))) {
        assertEquals(count, files.filter(Files::isRegularFile).count());
      }
      assertTrue(setup.declaration().preferredEncodingKey().isEmpty());
      assertEquals(RootLookupFixture.NOW, setup.actual().originalAlias().evidence().verifiedAt());
    }
  }

  @Test
  void historicalGrammarClaimCannotReplaceActualBoundedParserPass() throws Exception {
    try (var setup = setup(true);
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      assertThrows(
          FileLookupException.class,
          () -> gateway.replay(plan(setup), LookupBudgets.defaults(), CancellationToken.none()));
    }
  }

  @Test
  void unsupportedHistoricalRevisionIsNotSilentlyUpgraded() throws Exception {
    try (var setup = setup(false);
        var gateway = new OfficialMetadataReuseGateway(setup.store())) {
      var actual = setup.actual();
      var unsupported =
          new MetadataContentProof(
              actual.originalAlias(),
              actual.actualEncodingKey(),
              actual.contentKey(),
              actual.declaredDecodedBytes(),
              actual.decodedAttestation(),
              Optional.of(
                  new MetadataStructureAttestation(
                      MetadataStructureAttestation.Kind.ROOT, "unknown-parser-v99")));
      var request =
          new MetadataReusePlan(
              UUID.randomUUID(), UUID.randomUUID(), setup.declaration(), unsupported);
      var failure =
          assertThrows(
              ContentClaimException.class,
              () -> gateway.replay(request, LookupBudgets.defaults(), CancellationToken.none()));
      assertEquals(ContentClaimException.Code.UNSUPPORTED_REUSE, failure.code());
    }
  }
}
