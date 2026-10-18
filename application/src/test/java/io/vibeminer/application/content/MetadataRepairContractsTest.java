package io.vibeminer.application.content;

import static io.vibeminer.application.content.ContentContractsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetadataRepairContractsTest {
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  final MetadataContentProof original = historical();
  final MetadataContentRequest request =
      new MetadataContentRequest(
          build("wow"),
          configuration(),
          MetadataStructureAttestation.Kind.ROOT,
          new ContentSlotKey("wow", C),
          E,
          80,
          OptionalLong.of(100));
  final MetadataRepairCost cost = new MetadataRepairCost(80, 80, 10, 200, Duration.ofMinutes(5));
  final MetadataRepairSourceObservation head =
      new MetadataRepairSourceObservation(url(E), E, 80, NOW, "1".repeat(64));

  static String url(String key) {
    return "https://us.cdn.blizzard.com/tpr/wow/data/"
        + key.substring(0, 2)
        + "/"
        + key.substring(2, 4)
        + "/"
        + key;
  }

  MetadataRepairPlan plan() {
    return new MetadataRepairPlan(
        UUID.randomUUID(),
        UUID.randomUUID(),
        request,
        original,
        head,
        cost,
        NOW,
        NOW.plusSeconds(300));
  }

  @Test
  void freshIssuanceDistinguishesConsentAndPreservesHistoricalProof() {
    var first = plan();
    var second = plan();
    assertNotEquals(first.issuanceId(), second.issuanceId());
    assertNotEquals(first, second);
    assertSame(original, first.originalProof());
    assertTrue(original.decodedAttestation().isEmpty());
    assertTrue(original.structuralAttestation().isEmpty());
    assertTrue(first.isUsableAt(NOW));
    assertFalse(first.isUsableAt(NOW.minusNanos(1)));
    assertFalse(first.isUsableAt(first.expiresAt()));
  }

  @Test
  void zeroCostUnknownGeometryAndInvalidExpiryCannotBecomeRepairConsent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetadataRepairCost(0, 0, 1, 200, Duration.ofSeconds(10)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetadataRepairCost(80, 80, 0, 200, Duration.ofSeconds(10)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetadataRepairCost(80, 80, 1, 200, Duration.ZERO));
    var p = plan();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairPlan(
                p.issuanceId(), p.reservationToken(), request, original, head, cost, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairPlan(
                p.issuanceId(),
                p.reservationToken(),
                request,
                original,
                head,
                cost,
                NOW,
                NOW.plus(Duration.ofMinutes(11))));
    var shortCost = new MetadataRepairCost(79, 80, 10, 200, Duration.ofMinutes(5));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairPlan(
                p.issuanceId(),
                p.reservationToken(),
                request,
                original,
                head,
                shortCost,
                NOW,
                p.expiresAt()));
  }

  @Test
  void alternateEncodingLengthOrSourceCannotBeBoundToOriginalA() {
    var p = plan();
    var other = "f".repeat(32);
    var changed =
        new MetadataContentRequest(
            request.build(),
            request.configuration(),
            request.kind(),
            request.slot(),
            other,
            80,
            OptionalLong.of(100));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairPlan(
                p.issuanceId(),
                p.reservationToken(),
                changed,
                original,
                head,
                cost,
                NOW,
                p.expiresAt()));
    var differentHead =
        new MetadataRepairSourceObservation(url(other), other, 80, NOW, "2".repeat(64));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairPlan(
                p.issuanceId(),
                p.reservationToken(),
                request,
                original,
                differentHead,
                cost,
                NOW,
                p.expiresAt()));
    var wrongSize = new MetadataRepairSourceObservation(url(E), E, 81, NOW, "2".repeat(64));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairPlan(
                p.issuanceId(),
                p.reservationToken(),
                request,
                original,
                wrongSize,
                cost,
                NOW,
                p.expiresAt()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairSourceObservation(
                "https://us.cdn.blizzard.com/not-the-object", E, 80, NOW, "2".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairSourceObservation(
                "https://user@us.cdn.blizzard.com/" + E, E, 80, NOW, "2".repeat(64)));
  }

  @Test
  void staleOrFutureHeadObservationIsNotFreshRepairConsent() {
    var p = plan();
    var future =
        new MetadataRepairSourceObservation(url(E), E, 80, NOW.plusSeconds(1), "2".repeat(64));
    var old =
        new MetadataRepairSourceObservation(
            url(E), E, 80, NOW.minus(Duration.ofMinutes(11)), "2".repeat(64));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairPlan(
                p.issuanceId(),
                p.reservationToken(),
                request,
                original,
                future,
                cost,
                NOW,
                p.expiresAt()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairPlan(
                p.issuanceId(),
                p.reservationToken(),
                request,
                original,
                old,
                cost,
                NOW,
                p.expiresAt()));
  }

  @Test
  void rootPlanBindsExactRepairCandidateAndKeepsLegacyConstructorEmpty() {
    var candidates = List.of(new RootCandidate(E, 80, 0));
    var legacy =
        new RootPlan(
            "session",
            request.build(),
            configuration(),
            C,
            100,
            candidates,
            LookupBudgets.defaults());
    assertTrue(legacy.repair().isEmpty());
    var repaired =
        new RootPlan(
            "session",
            request.build(),
            configuration(),
            C,
            100,
            candidates,
            LookupBudgets.defaults(),
            Optional.of(plan()));
    assertTrue(repaired.repair().isPresent());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RootPlan(
                "session",
                request.build(),
                configuration(),
                C,
                100,
                List.of(new RootCandidate(E, -1, 0)),
                LookupBudgets.defaults(),
                Optional.of(plan())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RootPlan(
                "session",
                request.build(),
                configuration(),
                "f".repeat(32),
                100,
                candidates,
                LookupBudgets.defaults(),
                Optional.of(plan())));
  }

  @Test
  void installPlanLegacyConsentRemainsEmptyAndCannotCarryRootRepair() {
    var document =
        new SourceDocument(
            configuration().evidence().sourceUrl(),
            configuration().evidence().verifiedAt(),
            new byte[10]);
    var legacy = new InstallPlan(request.build(), document, C, E, 100, 80);
    assertTrue(legacy.repair().isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> new InstallPlan(request.build(), document, C, E, 100, 80, Optional.of(plan())));
  }

  @Test
  void actualRepairObservationNeverRestampsOriginalProofOrInventsGrammar() {
    var actual =
        new VerifiedSourceAlias(
            original.originalAlias().source(),
            original.object(),
            new SourceVerificationEvidence(
                original.originalAlias().source().verificationSemantics(), url(E), NOW));
    var decoded = new DecodedContentAttestation(C, 100, "current-decoder.v1");
    var prospective =
        new MetadataRepairObservation(
            UUID.randomUUID(), original, actual, decoded, Optional.empty(), 2, 80, "3".repeat(64));
    assertSame(original, prospective.originalProof());
    assertEquals(NOW, prospective.actualTransfer().evidence().verifiedAt());
    assertEquals(Instant.EPOCH, original.originalAlias().evidence().verifiedAt());
    assertTrue(original.decodedAttestation().isEmpty());
    assertTrue(prospective.structuralAttestation().isEmpty());
    var complete =
        prospective.withStructuralAttestation(
            new MetadataStructureAttestation(
                MetadataStructureAttestation.Kind.ROOT, "current-parser.v1"));
    assertSame(original, complete.originalProof());
    assertTrue(original.structuralAttestation().isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            complete.withStructuralAttestation(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.ROOT, "replacement-parser.v2")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            prospective.withStructuralAttestation(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.INSTALL, "wrong-parser.v1")));
  }

  @Test
  void repairMustMatchOriginalPhysicalShaAndFreshDecodedCkey() {
    var changed = alias(original.originalAlias().source(), 80);
    changed =
        new VerifiedSourceAlias(
            changed.source(),
            new StoredObject(new ObjectDigest("f".repeat(64)), 80),
            changed.evidence());
    var wrongPhysical = changed;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairObservation(
                UUID.randomUUID(),
                original,
                wrongPhysical,
                new DecodedContentAttestation(C, 100, "decoder.v1"),
                Optional.empty(),
                1,
                80,
                "3".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairObservation(
                UUID.randomUUID(),
                original,
                original.originalAlias(),
                new DecodedContentAttestation("f".repeat(32), 100, "decoder.v1"),
                Optional.empty(),
                1,
                80,
                "3".repeat(64)));
  }

  @Test
  void positiveInstallConsentBindsItsOwnSourceAndSingleWholeTransfer() {
    var source =
        new ResolvedSourceIdentity("tact.install.v1", C + ";" + E + ";100;80", "install.v1");
    var historicalInstall =
        new MetadataContentProof(
            alias(source, 80), E, C, OptionalLong.empty(), Optional.empty(), Optional.empty());
    var installRequest =
        new MetadataContentRequest(
            build("wow"),
            configuration(),
            MetadataStructureAttestation.Kind.INSTALL,
            new ContentSlotKey("wow", C),
            E,
            80,
            OptionalLong.of(100));
    var oneGet = new MetadataRepairCost(80, 80, 1, 200, Duration.ofMinutes(1));
    var repair =
        new MetadataRepairPlan(
            UUID.randomUUID(),
            UUID.randomUUID(),
            installRequest,
            historicalInstall,
            head,
            oneGet,
            NOW,
            NOW.plusSeconds(60));
    var document =
        new SourceDocument(
            configuration().evidence().sourceUrl(),
            configuration().evidence().verifiedAt(),
            new byte[10]);
    var install = new InstallPlan(build("wow"), document, C, E, 100, 80, Optional.of(repair));
    assertEquals(Optional.of(repair), install.repair());
    assertSame(historicalInstall, repair.originalProof());
    assertTrue(historicalInstall.decodedAttestation().isEmpty());
    var wrongDocument =
        new SourceDocument(
            "https://kr.cdn.blizzard.com/another-config", document.capturedAt(), document.bytes());
    assertThrows(
        IllegalArgumentException.class,
        () -> new InstallPlan(build("wow"), wrongDocument, C, E, 100, 80, Optional.of(repair)));
    var moreRequests =
        new MetadataRepairPlan(
            repair.issuanceId(),
            repair.reservationToken(),
            installRequest,
            historicalInstall,
            head,
            cost,
            NOW,
            NOW.plusSeconds(60));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InstallPlan(build("wow"), document, C, E, 100, 80, Optional.of(moreRequests)));
  }

  @Test
  void repairCannotWidenOriginalRootBudgetsOrBindAnotherBuild() {
    var budgets = LookupBudgets.defaults();
    var smaller =
        new LookupBudgets(
            budgets.maximumConfigurationBytes(),
            budgets.maximumMetadataWireBytes(),
            budgets.maximumMetadataRequests(),
            budgets.maximumMetadataDecodedBytes(),
            budgets.maximumIndexBytes(),
            budgets.maximumPageBytes(),
            budgets.maximumCandidates(),
            budgets.maximumRootEncodedBytes(),
            budgets.maximumRootDecodedBytes(),
            budgets.maximumHeaderBytes(),
            budgets.maximumChunks(),
            budgets.maximumEncodedChunkBytes(),
            budgets.maximumDecodedChunkBytes(),
            199,
            budgets.maximumSelectedIds(),
            budgets.maximumMatches(),
            budgets.maximumRootRecords());
    var candidates = List.of(new RootCandidate(E, 80, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RootPlan(
                "session",
                request.build(),
                configuration(),
                C,
                100,
                candidates,
                smaller,
                Optional.of(plan())));
    var old = build("wow");
    var different =
        new BuildSnapshot(
            old.productId(),
            old.editionLabel(),
            old.channel(),
            old.region(),
            "other",
            "f".repeat(32),
            old.cdnConfig(),
            NOW,
            true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RootPlan(
                "session",
                different,
                configuration(),
                C,
                100,
                candidates,
                budgets,
                Optional.of(plan())));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetadataRepairCost(80, 80, 1, 200, Duration.ofMinutes(11)));
  }

  @Test
  void sourceValueRejectsAmbiguousAuthorityPathAndPortWithoutNetworkTypes() {
    for (String invalid :
        List.of(
            "http://us.cdn.blizzard.com/" + E,
            "https://us.cdn.blizzard.com:65536/" + E,
            "https://us.cdn.blizzard.com:0/" + E,
            "https://us..cdn.blizzard.com/" + E,
            "https://us.cdn.blizzard.com/../" + E,
            "https://us.cdn.blizzard.com/a//" + E,
            "https://us.cdn.blizzard.com/" + E + "?query=1",
            "https://us.cdn.blizzard.com/" + E + "#fragment"))
      assertThrows(
          IllegalArgumentException.class,
          () -> new MetadataRepairSourceObservation(invalid, E, 80, NOW, "2".repeat(64)),
          invalid);
    assertDoesNotThrow(
        () ->
            new MetadataRepairSourceObservation(
                "https://us.cdn.blizzard.com:443/data/" + E, E, 80, NOW, "2".repeat(64)));
  }
}
