package io.vibeminer.desktop;

import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Authored scalar repair facts and controllable service promises; no files or network. */
final class MetadataRepairFixture {
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  static final String A = "a".repeat(32), C = "c".repeat(32);
  static final LookupBudgets BUDGETS = LookupBudgets.defaults();

  static BuildSnapshot build(String version) {
    return new BuildSnapshot(
        "wow",
        "Synthetic",
        "current",
        "us",
        version,
        "d".repeat(32),
        "e".repeat(32),
        NOW.minusSeconds(120),
        true);
  }

  static VerifiedSourceAlias config(BuildSnapshot b) {
    var source =
        new ResolvedSourceIdentity(
            "tact.config.raw-md5", b.buildConfig(), "tact-config-raw-md5-v1");
    var raw =
        new SourceDocument(
            "https://config.example/config/" + b.buildConfig(), NOW.minusSeconds(60), new byte[40]);
    return new VerifiedSourceAlias(
        source,
        new StoredObject(new ObjectDigest(raw.sha256()), 40),
        new SourceVerificationEvidence(
            source.verificationSemantics(),
            "https://config.example/config/" + b.buildConfig(),
            NOW.minusSeconds(60)));
  }

  static InstallPlan install(BuildSnapshot b) {
    return new InstallPlan(
        b,
        new SourceDocument(
            config(b).evidence().sourceUrl(), config(b).evidence().verifiedAt(), new byte[40]),
        C,
        A,
        120,
        90);
  }

  static RootSelectionPlan root(BuildSnapshot b) {
    var candidate = new RootCandidate(A, 90, 1);
    return new RootSelectionPlan(
        new RootPlan("synthetic-root-session", b, config(b), C, 120, List.of(candidate), BUDGETS),
        candidate);
  }

  static MetadataRepairPlan plan(BuildSnapshot b, MetadataStructureAttestation.Kind kind) {
    var source =
        new ResolvedSourceIdentity(
            kind == MetadataStructureAttestation.Kind.ROOT
                ? "wow.root.tsfm.v1v2"
                : "tact.install.v1",
            C + ";" + A + ";120;90",
            "fixture-full-v1");
    var original =
        new VerifiedSourceAlias(
            source,
            new StoredObject(new ObjectDigest("f".repeat(64)), 90),
            new SourceVerificationEvidence(
                source.verificationSemantics(),
                "https://original.example/data/aa/aa/" + A,
                NOW.minusSeconds(3600)));
    var proof =
        new MetadataContentProof(
            original,
            A,
            C,
            OptionalLong.of(120),
            Optional.of(new DecodedContentAttestation(C, 120, "original-decoded-v1")),
            Optional.of(new MetadataStructureAttestation(kind, "original-parser-v1")));
    var request =
        new MetadataContentRequest(
            b, config(b), kind, new ContentSlotKey("wow", C), A, 90, OptionalLong.of(120));
    return new MetadataRepairPlan(
        new UUID(0, kind.ordinal() + 1),
        new UUID(0, 10),
        request,
        proof,
        new MetadataRepairSourceObservation(
            "https://fresh.example/data/aa/aa/" + A, A, 90, NOW.minusSeconds(1), "2".repeat(64)),
        new MetadataRepairCost(
            90,
            90,
            kind == MetadataStructureAttestation.Kind.ROOT ? 3 : 1,
            240,
            Duration.ofSeconds(60)),
        NOW,
        NOW.plusSeconds(300));
  }

  static MetadataBuildAssociation association(MetadataRepairPlan p) {
    var request = p.request();
    var declaration =
        new MetadataDeclaration(
            request.build(),
            request.configuration(),
            request.kind(),
            request.slot(),
            request.kind() == MetadataStructureAttestation.Kind.INSTALL
                ? Optional.of(A)
                : Optional.empty(),
            request.kind() == MetadataStructureAttestation.Kind.INSTALL
                ? OptionalLong.of(90)
                : OptionalLong.empty(),
            OptionalLong.of(120));
    return new MetadataBuildAssociation(
        declaration, p.reservationToken(), p.originalProof(), NOW.minusSeconds(30));
  }

  static MetadataRepairResult result(MetadataRepairPlan p, boolean transferred) {
    if (!transferred) return new MetadataRepairResult(association(p), Optional.empty());
    UUID attempt = new UUID(0, 20);
    var original = p.originalProof();
    var transfer =
        new VerifiedSourceAlias(
            original.originalAlias().source(),
            original.object(),
            new SourceVerificationEvidence(
                original.originalAlias().source().verificationSemantics(),
                p.sourceObservation().resourceUrl(),
                NOW.plusSeconds(2)));
    boolean root = p.request().kind() == MetadataStructureAttestation.Kind.ROOT;
    var structure = new MetadataStructureAttestation(p.request().kind(), "fresh-parser-v1");
    var prospective =
        new MetadataRepairObservation(
            attempt,
            original,
            transfer,
            new DecodedContentAttestation(C, 120, "fresh-decoded-v1"),
            root ? Optional.empty() : Optional.of(structure),
            1,
            90,
            "3".repeat(64));
    var events = new ArrayList<MetadataRepairAuditEvent>();
    events.add(
        new MetadataRepairAuditEvent(
            1,
            MetadataRepairAuditEvent.Kind.PROSPECTIVE,
            NOW.plusSeconds(2),
            Optional.of(prospective)));
    var complete = prospective;
    if (root) {
      complete = prospective.withStructuralAttestation(structure);
      events.add(
          new MetadataRepairAuditEvent(
              2,
              MetadataRepairAuditEvent.Kind.STRUCTURE,
              NOW.plusSeconds(3),
              Optional.of(complete)));
    }
    events.add(
        new MetadataRepairAuditEvent(
            events.size() + 1,
            MetadataRepairAuditEvent.Kind.COMPLETED,
            NOW.plusSeconds(4),
            Optional.of(complete)));
    var audit = new MetadataRepairAudit(attempt, p, NOW.plusSeconds(1), events);
    return new MetadataRepairResult(association(p), Optional.of(audit));
  }

  static final class MutableClock extends Clock {
    Instant now = NOW;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  static class Fake implements MetadataRepairService {
    int rootChecks, installChecks, executions, closes;
    RootSelectionPlan root;
    InstallPlan install;
    LookupBudgets budgets;
    MetadataRepairPlan chosen;
    CompletableFuture<MetadataRepairPlan> checking = new CompletableFuture<>();
    CompletableFuture<MetadataRepairResult> executing = new CompletableFuture<>();

    public CompletionStage<MetadataRepairPlan> prepareInstall(InstallPlan p, LookupBudgets limits) {
      installChecks++;
      install = p;
      budgets = limits;
      return checking;
    }

    public CompletionStage<MetadataRepairPlan> prepareRoot(RootSelectionPlan p) {
      rootChecks++;
      root = p;
      return checking;
    }

    public CompletionStage<MetadataRepairResult> execute(MetadataRepairPlan p) {
      executions++;
      chosen = p;
      return executing;
    }

    public void close() {
      closes++;
    }
  }
}
