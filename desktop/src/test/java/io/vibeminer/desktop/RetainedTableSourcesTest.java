package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetainedTableSourcesTest {
  @Test
  void sparseSelectionPreservesIndependentItemSourceAndExactHistory() {
    var r = new RetainedTableSources();
    var item = ItemNamesPresentationFixture.association(false, Instant.EPOCH);
    var sparse = ItemNamesPresentationFixture.association(true, Instant.EPOCH);
    var b = item.descriptor().association().build();
    r.verified(item);
    r.selectVariant(sparse.descriptor().association());
    r.verified(sparse);
    assertSame(item, r.current(b, 841626).orElseThrow());
    assertSame(sparse, r.current(b, 1572924).orElseThrow());
    r.invalidate(b.snapshotId(), 1572924);
    assertTrue(r.current(b, 1572924).isEmpty());
    assertSame(item, r.current(b, 841626).orElseThrow());
    assertSame(sparse, r.history(b, 1572924).orElseThrow());
  }

  @Test
  void rootDependencyRevokesBothButCannotBeRestoredFromHistory() {
    var r = new RetainedTableSources();
    var i = ItemNamesPresentationFixture.association(false, Instant.EPOCH);
    var s = ItemNamesPresentationFixture.association(true, Instant.EPOCH);
    var b = i.descriptor().association().build();
    r.verified(i);
    r.verified(s);
    r.invalidateDependencies("other");
    assertTrue(r.current(b, 841626).isPresent());
    r.invalidateDependencies(b.snapshotId());
    assertTrue(r.current(b, 841626).isEmpty());
    assertTrue(r.current(b, 1572924).isEmpty());
    assertTrue(r.history(b, 841626).isPresent());
    r.verified(s);
    assertTrue(r.current(b, 841626).isEmpty());
  }

  @Test
  void choosingAnotherRawVariantRevokesOnlyThatTable() {
    var r = new RetainedTableSources();
    var i = ItemNamesPresentationFixture.association(false, Instant.EPOCH);
    var s = ItemNamesPresentationFixture.association(true, Instant.EPOCH);
    var v = i.descriptor().association();
    r.verified(i);
    r.verified(s);
    var other =
        new io.vibeminer.domain.selectedfile.FileVariantAssociation(
            v.build(),
            v.rootSource(),
            v.fileDataId(),
            v.contentKey(),
            v.localeFlags(),
            v.contentFlags() + 1,
            v.rawFlagsFirst(),
            v.rawFlagsSecond(),
            v.rawFlagsThird(),
            v.nameHash());
    r.selectVariant(other);
    assertTrue(r.current(v.build(), 841626).isEmpty());
    assertTrue(r.current(v.build(), 1572924).isPresent());
    assertSame(i, r.history(v.build(), 841626).orElseThrow());
  }

  @Test
  void laterObservationOfSameCanonicalVariantPreservesSource() {
    var registry = new RetainedTableSources();
    var original = ItemNamesPresentationFixture.association(false, Instant.EPOCH);
    registry.verified(original);
    registry.selectVariant(
        ItemNamesPresentationFixture.association(false, Instant.EPOCH.plusSeconds(100))
            .descriptor()
            .association());
    assertSame(
        original,
        registry.current(original.descriptor().association().build(), 841626).orElseThrow());
  }
}
