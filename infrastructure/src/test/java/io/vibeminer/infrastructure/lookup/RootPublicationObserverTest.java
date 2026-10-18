package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RootPublicationObserverTest {
  @TempDir Path directory;

  static class Observations implements MetadataPublicationObserver {
    final List<MetadataContentProof> before = new ArrayList<>(), after = new ArrayList<>();

    public void beforePromotion(MetadataContentProof proof, CancellationToken token) {
      before.add(proof);
    }

    public void afterStructureValidation(MetadataContentProof proof, CancellationToken token) {
      after.add(proof);
    }
  }

  @Test
  void prospectiveDecodedGatePrecedesCasAndGrammarThenReplaysOffline() throws Exception {
    var fixture = new RootLookupFixture();
    var build = fixture.build("us.cdn.blizzard.com", "1.0");
    try (var store = new FileObjectStore(directory);
        var gateway = new OfficialFileLookupGateway(store, fixture, Clock.systemUTC())) {
      var plan =
          gateway.prepareRoot(
              build, Optional.empty(), LookupBudgets.defaults(), CancellationToken.none());
      var observer =
          new Observations() {
            public void beforePromotion(MetadataContentProof proof, CancellationToken token) {
              super.beforePromotion(proof, token);
              assertTrue(store.verify(proof.object().digest(), token).isEmpty());
              assertTrue(proof.decodedAttestation().isPresent());
              assertTrue(proof.structuralAttestation().isEmpty());
              assertEquals(
                  fixture.root.length,
                  proof.decodedAttestation().orElseThrow().actualDecodedBytes());
            }

            public void afterStructureValidation(
                MetadataContentProof proof, CancellationToken token) {
              super.afterStructureValidation(proof, token);
              assertTrue(store.verify(proof.object().digest(), token).isPresent());
            }
          };
      var alias =
          gateway.acquireRoot(
              new RootSelectionPlan(plan, plan.candidates().getFirst()),
              observer,
              CancellationToken.none());
      assertEquals(1, observer.before.size());
      assertEquals(1, observer.after.size());
      assertEquals(alias, observer.before.getFirst().originalAlias());
      assertEquals(alias, observer.after.getFirst().originalAlias());
      assertEquals(
          MetadataStructureAttestation.Kind.ROOT,
          observer.after.getFirst().structuralAttestation().orElseThrow().kind());
      ContentProofPolicy.requireMonotonicUpgrade(
          observer.before.getFirst(), observer.after.getFirst());
      fixture.rejectNetwork = true;
      var replay = new Observations();
      gateway.lookupRetained(
          build,
          plan.configuration(),
          alias,
          Set.of(42L),
          LookupBudgets.defaults(),
          replay,
          CancellationToken.none());
      assertTrue(replay.before.isEmpty());
      assertEquals(1, replay.after.size());
      assertEquals(alias, replay.after.getFirst().originalAlias());
    }
  }

  @Test
  void observerRefusalIsConclusiveAndCannotPromote() throws Exception {
    var fixture = new RootLookupFixture();
    try (var store = new FileObjectStore(directory);
        var gateway = new OfficialFileLookupGateway(store, fixture, Clock.systemUTC())) {
      var plan =
          gateway.prepareRoot(
              fixture.build("us.cdn.blizzard.com", "1.0"),
              Optional.empty(),
              LookupBudgets.defaults(),
              CancellationToken.none());
      var observer =
          new Observations() {
            public void beforePromotion(MetadataContentProof p, CancellationToken t) {
              super.beforePromotion(p, t);
              throw new IllegalStateException("refused");
            }
          };
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () ->
                  gateway.acquireRoot(
                      new RootSelectionPlan(plan, plan.candidates().getFirst()),
                      observer,
                      CancellationToken.none()));
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      assertTrue(
          store
              .verify(observer.before.getFirst().object().digest(), CancellationToken.none())
              .isEmpty());
      assertTrue(observer.after.isEmpty());
    }
  }

  @Test
  void malformedGrammarAfterPromotionStaysDecodedOnlyAndUncertain() throws Exception {
    var fixture = new RootLookupFixture(false, true);
    try (var store = new FileObjectStore(directory);
        var gateway = new OfficialFileLookupGateway(store, fixture, Clock.systemUTC())) {
      var plan =
          gateway.prepareRoot(
              fixture.build("us.cdn.blizzard.com", "1.0"),
              Optional.empty(),
              LookupBudgets.defaults(),
              CancellationToken.none());
      var observer = new Observations();
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () ->
                  gateway.acquireRoot(
                      new RootSelectionPlan(plan, plan.candidates().getFirst()),
                      observer,
                      CancellationToken.none()));
      assertEquals(MetadataAcquisitionFailure.Publication.UNCERTAIN, failure.publication());
      assertEquals(1, observer.before.size());
      assertTrue(observer.after.isEmpty());
      assertTrue(observer.before.getFirst().structuralAttestation().isEmpty());
      assertTrue(
          store
              .verify(observer.before.getFirst().object().digest(), CancellationToken.none())
              .isPresent());
    }
  }

  @Test
  void wrongFullContentKeyNeverEmitsProspectiveCapability() throws Exception {
    var fixture = new RootLookupFixture(true, false);
    try (var store = new FileObjectStore(directory);
        var gateway = new OfficialFileLookupGateway(store, fixture, Clock.systemUTC())) {
      var plan =
          gateway.prepareRoot(
              fixture.build("us.cdn.blizzard.com", "1.0"),
              Optional.empty(),
              LookupBudgets.defaults(),
              CancellationToken.none());
      var observer = new Observations();
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () ->
                  gateway.acquireRoot(
                      new RootSelectionPlan(plan, plan.candidates().getFirst()),
                      observer,
                      CancellationToken.none()));
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      assertTrue(observer.before.isEmpty());
      assertTrue(observer.after.isEmpty());
    }
  }

  @Test
  void postPromotionObserverFailureRemainsUncertainAndOriginalBytesExist() throws Exception {
    var fixture = new RootLookupFixture();
    try (var store = new FileObjectStore(directory);
        var gateway = new OfficialFileLookupGateway(store, fixture, Clock.systemUTC())) {
      var plan =
          gateway.prepareRoot(
              fixture.build("us.cdn.blizzard.com", "1.0"),
              Optional.empty(),
              LookupBudgets.defaults(),
              CancellationToken.none());
      var observer =
          new Observations() {
            public void afterStructureValidation(MetadataContentProof p, CancellationToken t) {
              super.afterStructureValidation(p, t);
              throw new IllegalStateException("durability failure");
            }
          };
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () ->
                  gateway.acquireRoot(
                      new RootSelectionPlan(plan, plan.candidates().getFirst()),
                      observer,
                      CancellationToken.none()));
      assertEquals(MetadataAcquisitionFailure.Publication.UNCERTAIN, failure.publication());
      assertEquals(1, observer.after.size());
      assertEquals(
          observer.before.getFirst().originalAlias(), observer.after.getFirst().originalAlias());
      assertTrue(
          store
              .verify(observer.before.getFirst().object().digest(), CancellationToken.none())
              .isPresent());
    }
  }
}
