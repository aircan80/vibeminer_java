package io.vibeminer.infrastructure.buildopening;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildopening.BuildOpeningException;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuildMetadataDeclarationClassifierTest {
  static final String C = "a".repeat(32), E = "b".repeat(32);
  static final String BASE =
      "root = "
          + C
          + "\nencoding = "
          + C
          + " "
          + E
          + "\nencoding-size = 200 180\ninstall = "
          + C
          + " "
          + E
          + "\ninstall-size = 20 18\n";

  record Input(BuildSnapshot build, VerifiedSourceAlias configuration, byte[] bytes) {}

  static Input input(String text) throws Exception {
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    var md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var build =
        new BuildSnapshot(
            "wow", "Synthetic", "Published", "us", "1", md5, "d".repeat(32), Instant.EPOCH, true);
    var source = new ResolvedSourceIdentity("tact.config.raw-md5", md5, "tact-config-raw-md5-v1");
    return new Input(
        build,
        new VerifiedSourceAlias(
            source,
            new StoredObject(new ObjectDigest(sha), bytes.length),
            new SourceVerificationEvidence(
                source.verificationSemantics(), "https://example.test/config", Instant.EPOCH)),
        bytes);
  }

  static BuildMetadataDeclarationPlan classify(String text) throws Exception {
    var in = input(text);
    return new BuildMetadataDeclarationClassifier()
        .classify(
            in.build(),
            in.configuration(),
            in.bytes(),
            BuildMetadataDeclarationClassifier.Limits.defaults(),
            CancellationToken.none());
  }

  @Test
  void directSourcesAndAuthenticatedAbsenceRemainClassificationNotReadiness() throws Exception {
    var plan = classify(BASE);
    assertEquals(3, plan.requiredSources().size());
    assertFalse(plan.downloadPublished());
    assertFalse(plan.sizePublished());
    assertEquals(Optional.empty(), plan.source(Kind.ROOT).encodingKey());
    assertEquals(OptionalLong.of(200), plan.source(Kind.ENCODING).decodedBytes());
    assertEquals(Optional.of(E), plan.source(Kind.INSTALL).encodingKey());
    assertThrows(UnsupportedOperationException.class, () -> plan.values().clear());
  }

  @Test
  void declaredDlDsAndCKeyOnlySourcesStayRequiredUntilEncodingResolution() throws Exception {
    var plan =
        classify(
            BASE.replace("install = " + C + " " + E, "install = " + C)
                + "download = "
                + C
                + "\nsize = "
                + C
                + " "
                + E
                + "\nsize-size = 40 30\n");
    assertEquals(5, plan.requiredSources().size());
    assertTrue(plan.downloadPublished());
    assertTrue(plan.sizePublished());
    assertTrue(plan.source(Kind.DOWNLOAD).encodedBytes().isEmpty());
    assertEquals(OptionalLong.of(18), plan.source(Kind.INSTALL).encodedBytes());
    assertEquals(OptionalLong.of(30), plan.source(Kind.DOWNLOAD_SIZE).encodedBytes());
  }

  @Test
  void vfsAlternativePatchAndHarmlessUnknownsArePreservedWithoutInventedReferences()
      throws Exception {
    var text =
        BASE
            + "vfs-root = "
            + C
            + " "
            + E
            + "\nvfs-1 = "
            + C
            + " "
            + E
            + "\npatch = "
            + E
            + "\npatch-size = 10\nfeature-widget = true\ncustom-text = hello world\n";
    var plan = classify(text);
    assertEquals(
        BuildMetadataDeclarationPlan.Classification.ALTERNATIVE_RESOLVER,
        plan.classifications().get("vfs-1"));
    assertEquals(
        BuildMetadataDeclarationPlan.Classification.DELTA_UPDATE,
        plan.classifications().get("patch"));
    assertEquals(List.of("hello", "world"), plan.values().get("custom-text"));
    assertEquals(3, plan.requiredSources().size());
    var bad =
        assertThrows(
            BuildOpeningException.class, () -> classify(text.replace("root = " + C + "\n", "")));
    assertEquals(BuildOpeningException.Code.UNSUPPORTED_METADATA, bad.code());
  }

  @Test
  void malformedOrphanUnknownReferenceAndZeroSizesCannotBecomeAbsence() {
    for (var text :
        List.of(
            BASE + "download-size = 10 9\n",
            BASE + "size =\n",
            BASE + "mystery-manifest = abc\n",
            BASE + "custom-ref = " + C + "\n",
            BASE + "future = hello\nfuture-size = 10\n",
            BASE.replace("200 180", "0 180"),
            BASE.replace("200 180", "200 9223372036854775808")))
      assertThrows(BuildOpeningException.class, () -> classify(text));
  }

  @Test
  void exactMd5PhysicalShaLengthAndBuildBindingAreReverified() throws Exception {
    var in = input(BASE);
    var classifier = new BuildMetadataDeclarationClassifier();
    var changed = in.bytes().clone();
    changed[0] ^= 1;
    assertEquals(
        BuildOpeningException.Code.CORRUPT_METADATA,
        assertThrows(
                BuildOpeningException.class,
                () ->
                    classifier.classify(
                        in.build(),
                        in.configuration(),
                        changed,
                        BuildMetadataDeclarationClassifier.Limits.defaults(),
                        CancellationToken.none()))
            .code());
    var wrong =
        new VerifiedSourceAlias(
            in.configuration().source(),
            new StoredObject(new ObjectDigest("f".repeat(64)), in.bytes().length),
            in.configuration().evidence());
    assertThrows(
        BuildOpeningException.class,
        () ->
            classifier.classify(
                in.build(),
                wrong,
                in.bytes(),
                BuildMetadataDeclarationClassifier.Limits.defaults(),
                CancellationToken.none()));
  }

  @Test
  void cancellationAndBoundsFailBeforeIssuingPlan() throws Exception {
    var in = input(BASE);
    var classifier = new BuildMetadataDeclarationClassifier();
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            classifier.classify(
                in.build(),
                in.configuration(),
                in.bytes(),
                BuildMetadataDeclarationClassifier.Limits.defaults(),
                () -> true));
    var limits = new BuildMetadataDeclarationClassifier.Limits(10, 100, 1000, 8, 100, 1000, 1000);
    assertEquals(
        BuildOpeningException.Code.LIMIT_EXCEEDED,
        assertThrows(
                BuildOpeningException.class,
                () ->
                    classifier.classify(
                        in.build(),
                        in.configuration(),
                        in.bytes(),
                        limits,
                        CancellationToken.none()))
            .code());
  }

  @Test
  void physicalAliasCannotOverrideRawConfigMd5AndExactSnapshotIdentity() throws Exception {
    var original = input(BASE);
    var changed = input(BASE + "custom-text = changed\n");
    var alias =
        new VerifiedSourceAlias(
            original.configuration().source(),
            changed.configuration().object(),
            original.configuration().evidence());
    var classifier = new BuildMetadataDeclarationClassifier();
    assertEquals(
        BuildOpeningException.Code.CORRUPT_METADATA,
        assertThrows(
                BuildOpeningException.class,
                () ->
                    classifier.classify(
                        original.build(),
                        alias,
                        changed.bytes(),
                        BuildMetadataDeclarationClassifier.Limits.defaults(),
                        CancellationToken.none()))
            .code());
    assertThrows(
        BuildOpeningException.class,
        () ->
            classifier.classify(
                changed.build(),
                original.configuration(),
                original.bytes(),
                BuildMetadataDeclarationClassifier.Limits.defaults(),
                CancellationToken.none()));
  }

  @Test
  void manyVfsAlternativesDoNotExpandRequiredNetworkClosureOrLoseRawCase() throws Exception {
    var text = new StringBuilder(BASE);
    for (int i = 1; i <= 868; i++)
      text.append("vfs-")
          .append(i)
          .append(" = ")
          .append(C.toUpperCase(Locale.ROOT))
          .append(' ')
          .append(E)
          .append('\n');
    var plan = classify(text.toString());
    assertEquals(3, plan.requiredSources().size());
    assertEquals(C.toUpperCase(Locale.ROOT), plan.values().get("vfs-868").getFirst());
    assertEquals(
        868,
        plan.classifications().values().stream()
            .filter(
                value -> value == BuildMetadataDeclarationPlan.Classification.ALTERNATIVE_RESOLVER)
            .count());
  }

  @Test
  void sourceAndPropertyLimitsAreTypedAndExactBoundaryIsAdmitted() throws Exception {
    var in = input(BASE);
    var classifier = new BuildMetadataDeclarationClassifier();
    var exact =
        new BuildMetadataDeclarationClassifier.Limits(in.bytes().length, 100, 1000, 8, 5, 180, 200);
    assertEquals(
        3,
        classifier
            .classify(in.build(), in.configuration(), in.bytes(), exact, CancellationToken.none())
            .requiredSources()
            .size());
    for (var limits :
        List.of(
            new BuildMetadataDeclarationClassifier.Limits(
                in.bytes().length, 100, 1000, 8, 4, 180, 200),
            new BuildMetadataDeclarationClassifier.Limits(
                in.bytes().length, 100, 1000, 8, 5, 179, 200),
            new BuildMetadataDeclarationClassifier.Limits(
                in.bytes().length, 100, 1000, 8, 5, 180, 199)))
      assertEquals(
          BuildOpeningException.Code.LIMIT_EXCEEDED,
          assertThrows(
                  BuildOpeningException.class,
                  () ->
                      classifier.classify(
                          in.build(),
                          in.configuration(),
                          in.bytes(),
                          limits,
                          CancellationToken.none()))
              .code());
  }
}
