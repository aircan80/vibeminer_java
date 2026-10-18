package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MetadataReusePresentationModelTest {
  static BuildSnapshot build(String v) {
    return new BuildSnapshot(
        "wow",
        "Synthetic",
        "current",
        "us",
        v,
        "d".repeat(32),
        "e".repeat(32),
        Instant.EPOCH,
        true);
  }

  static MetadataReusePlan plan(BuildSnapshot b, MetadataStructureAttestation.Kind kind) {
    String c = "c".repeat(32), a = "a".repeat(32);
    var original =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(
                kind == MetadataStructureAttestation.Kind.ROOT
                    ? "wow.root.tsfm.v1v2"
                    : "tact.install.v1",
                c + ";" + a + ";120;90",
                "fixture-full-v1"),
            new StoredObject(new ObjectDigest("f".repeat(64)), 90),
            new SourceVerificationEvidence(
                "fixture-full-v1", "https://original.example/" + kind, Instant.EPOCH));
    var actual =
        new MetadataContentProof(
            original,
            a,
            c,
            OptionalLong.of(120),
            Optional.of(new DecodedContentAttestation(c, 120, "fixture-decoded-v1")),
            Optional.of(new MetadataStructureAttestation(kind, "fixture-grammar-v1")));
    var config =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(
                "tact.config.raw-md5", b.buildConfig(), "tact-config-raw-md5-v1"),
            new StoredObject(new ObjectDigest("1".repeat(64)), 40),
            new SourceVerificationEvidence(
                "tact-config-raw-md5-v1", "https://current.example/config", Instant.EPOCH));
    var declaration =
        new MetadataDeclaration(
            b,
            config,
            kind,
            new ContentSlotKey(ContentSlotKey.WOW_NAMESPACE, c),
            kind == MetadataStructureAttestation.Kind.INSTALL
                ? Optional.of("b".repeat(32))
                : Optional.empty(),
            kind == MetadataStructureAttestation.Kind.INSTALL
                ? OptionalLong.of(91)
                : OptionalLong.empty(),
            kind == MetadataStructureAttestation.Kind.INSTALL
                ? OptionalLong.of(120)
                : OptionalLong.empty());
    return new MetadataReusePlan(new UUID(0, 1), new UUID(0, 2), declaration, actual);
  }

  static MetadataBuildAssociation applied(MetadataReusePlan p) {
    return new MetadataBuildAssociation(
        p.declaration(), p.canonicalReservationToken(), p.actualRepresentation(), Instant.EPOCH);
  }

  static class Fake implements MetadataReuseService {
    int checks, applies;
    BuildSnapshot build;
    MetadataStructureAttestation.Kind kind;
    LookupBudgets budgets;
    MetadataReusePlan chosen;
    CompletableFuture<MetadataReusePlan> checking = new CompletableFuture<>();
    CompletableFuture<MetadataBuildAssociation> applying = new CompletableFuture<>();

    public CompletionStage<MetadataReusePlan> planRetained(
        BuildSnapshot b, MetadataStructureAttestation.Kind k, LookupBudgets limits) {
      checks++;
      build = b;
      kind = k;
      budgets = limits;
      return checking;
    }

    public CompletionStage<MetadataBuildAssociation> apply(MetadataReusePlan p) {
      applies++;
      chosen = p;
      return applying;
    }

    public void close() {
      throw new AssertionError("Borrowed service must stay open");
    }
  }

  static MetadataReusePresentationModel model(Fake f, MetadataStructureAttestation.Kind k) {
    var m = new MetadataReusePresentationModel(f, k, Runnable::run, s -> {});
    m.selectContext(build("A"), "enUS");
    return m;
  }

  @Test
  void contextAndOrdinaryKindDoNotMakeRequests() {
    var f = new Fake();
    var m = model(f, MetadataStructureAttestation.Kind.ROOT);
    m.apply();
    assertEquals(0, f.checks + f.applies);
    m.checkRetained();
    assertEquals(1, f.checks);
    assertEquals(MetadataStructureAttestation.Kind.ROOT, f.kind);
    f.checking.complete(plan(build("A"), f.kind));
    assertEquals(0, f.applies);
    assertEquals(0, m.state().plan().additionalPayloadTransferBytes());
    assertTrue(m.state().plan().declaration().preferredEncodingKey().isEmpty());
  }

  @Test
  void declaredBAndActualAStaySeparateAndApplyIsExplicit() {
    var f = new Fake();
    var m = model(f, MetadataStructureAttestation.Kind.INSTALL);
    m.checkRetained();
    var p = plan(build("A"), f.kind);
    f.checking.complete(p);
    assertEquals(
        "b".repeat(32), m.state().plan().declaration().preferredEncodingKey().orElseThrow());
    assertEquals("a".repeat(32), m.state().plan().actualRepresentation().actualEncodingKey());
    m.apply();
    assertSame(p, f.chosen);
    f.applying.complete(applied(p));
    assertEquals(MetadataReusePresentationModel.Stage.ASSOCIATED, m.state().stage());
    assertNull(m.state().plan());
    assertSame(p.actualRepresentation(), m.state().association().actualRepresentation());
    assertEquals(1, f.checks);
  }

  @Test
  void wrongKindOrBuildResponseCannotAuthorizeReplay() {
    for (boolean wrongKind : List.of(true, false)) {
      var f = new Fake();
      var m = model(f, MetadataStructureAttestation.Kind.ROOT);
      m.checkRetained();
      f.checking.complete(
          plan(
              build(wrongKind ? "A" : "B"),
              wrongKind
                  ? MetadataStructureAttestation.Kind.INSTALL
                  : MetadataStructureAttestation.Kind.ROOT));
      assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, m.state().code());
      assertNull(m.state().plan());
      m.apply();
      assertEquals(0, f.applies);
    }
  }

  @Test
  void changedLocaleDetachesAndIgnoresLatePlan() {
    var f = new Fake();
    f.checking =
        new CompletableFuture<>() {
          public boolean cancel(boolean x) {
            return false;
          }
        };
    var m = model(f, MetadataStructureAttestation.Kind.ROOT);
    m.checkRetained();
    m.selectContext(build("A"), "deDE");
    f.checking.complete(plan(build("A"), f.kind));
    assertNull(m.state().plan());
    assertEquals("deDE", m.state().locale());
  }

  @Test
  void missingConfigurationNeverFetchesAndExpiredPlanCannotRetryApply() {
    var f = new Fake();
    var m = model(f, MetadataStructureAttestation.Kind.ROOT);
    m.checkRetained();
    f.checking.completeExceptionally(
        new ContentClaimException(ContentClaimException.Code.MISSING_LOCAL, "private"));
    assertEquals(ContentClaimException.Code.MISSING_LOCAL, m.state().code());
    assertEquals(1, f.checks);
    assertEquals(0, f.applies);
    f.checking = new CompletableFuture<>();
    m.checkRetained();
    f.checking.complete(plan(build("A"), f.kind));
    m.apply();
    f.applying.completeExceptionally(
        new CompletionException(
            new ContentClaimException(ContentClaimException.Code.REPLAN_REQUIRED, "expired")));
    assertNull(m.state().plan());
    m.apply();
    assertEquals(1, f.applies);
  }

  @Test
  void confirmedLocalApplyFailureRemainsTypedAndRevokesConsentWithoutFallback() {
    for (var kind :
        List.of(
            MetadataStructureAttestation.Kind.ROOT, MetadataStructureAttestation.Kind.INSTALL)) {
      for (var code :
          List.of(
              ContentClaimException.Code.MISSING_LOCAL,
              ContentClaimException.Code.INTEGRITY_FAILURE)) {
        var f = new Fake();
        var seen = new ArrayList<MetadataReusePresentationModel.State>();
        var m = new MetadataReusePresentationModel(f, kind, Runnable::run, seen::add);
        m.selectContext(build("A"), "enUS");
        m.checkRetained();
        var original = plan(build("A"), kind);
        f.checking.complete(original);
        m.apply();
        f.applying.completeExceptionally(
            new CompletionException(
                new ExecutionException(
                    new ContentClaimException(code, "private physical path must not be shown"))));
        assertEquals(MetadataReusePresentationModel.Stage.FAILED, m.state().stage());
        assertEquals(code, m.state().code());
        assertEquals(code, seen.getLast().code());
        assertNull(m.state().plan());
        assertNull(m.state().association());
        assertSame(original, m.state().historicalPlan());
        assertFalse(m.state().message().contains("private physical"));
        m.apply();
        assertEquals(1, f.checks);
        assertEquals(1, f.applies);
      }
    }
  }

  @Test
  void cancellationAndCloseDoNotClaimNoPublicationOrCloseService() {
    var f = new Fake();
    var m = model(f, MetadataStructureAttestation.Kind.INSTALL);
    m.checkRetained();
    m.cancel();
    assertTrue(f.checking.isCancelled());
    assertEquals(MetadataReusePresentationModel.Stage.CANCELLED, m.state().stage());
    assertNull(m.state().association());
    m.close();
  }

  @Test
  void applyMustPreserveOriginalRepresentationAndDeclaration() {
    var f = new Fake();
    var m = model(f, MetadataStructureAttestation.Kind.ROOT);
    m.checkRetained();
    var p = plan(build("A"), f.kind);
    f.checking.complete(p);
    m.apply();
    f.applying.complete(applied(plan(build("B"), f.kind)));
    assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, m.state().code());
    assertNull(m.state().association());
  }

  @Test
  void originalReceiptCannotBeSwappedUnderSameEncoding() {
    var f = new Fake();
    var m = model(f, MetadataStructureAttestation.Kind.ROOT);
    m.checkRetained();
    var p = plan(build("A"), f.kind);
    f.checking.complete(p);
    m.apply();
    var old = p.actualRepresentation();
    var alias = old.originalAlias();
    var changedAlias =
        new VerifiedSourceAlias(
            alias.source(),
            new StoredObject(new ObjectDigest("7".repeat(64)), alias.object().length()),
            alias.evidence());
    var changed =
        new MetadataContentProof(
            changedAlias,
            old.actualEncodingKey(),
            old.declaredContentKey(),
            old.declaredDecodedBytes(),
            old.decodedAttestation(),
            old.structuralAttestation());
    f.applying.complete(
        new MetadataBuildAssociation(
            p.declaration(), p.canonicalReservationToken(), changed, Instant.EPOCH));
    assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, m.state().code());
    assertNull(m.state().association());
    assertSame(p, m.state().historicalPlan());
  }

  @Test
  void unsupportedProductCannotCheckAndCallbacksStayOnUiExecutor() {
    var f = new Fake();
    Queue<Runnable> ui = new ArrayDeque<>();
    var m =
        new MetadataReusePresentationModel(
            f, MetadataStructureAttestation.Kind.ROOT, ui::add, s -> {});
    var b = build("A");
    var other =
        new BuildSnapshot(
            "wowt",
            b.editionLabel(),
            b.channel(),
            b.region(),
            b.versionLabel(),
            b.buildConfig(),
            b.cdnConfig(),
            Instant.EPOCH,
            true);
    m.selectContext(other, "enUS");
    m.checkRetained();
    assertEquals(0, f.checks);
    m.selectContext(b, "enUS");
    m.checkRetained();
    f.checking.complete(plan(b, f.kind));
    assertNull(m.state().plan());
    m.close();
    ui.remove().run();
    assertNull(m.state().plan());
  }
}
