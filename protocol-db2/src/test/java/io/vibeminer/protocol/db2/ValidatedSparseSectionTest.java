package io.vibeminer.protocol.db2;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ValidatedSparseSectionTest {
  private final ValidatedSparseSection.Limits limits =
      new ValidatedSparseSection.Limits(10000, 10000, 100000);

  private Wdc5Metadata.Section section() {
    return new Wdc5Metadata.Section("0000000000000000", 100, 3, 0, 111, 12, 0, 3, 1, 161);
  }

  private SparseSectionIndex raw() {
    return raw(
        List.of(10L, 20L, 30L),
        List.of(
            new SparseSectionIndex.MapEntry(100, 4),
            new SparseSectionIndex.MapEntry(104, 5),
            new SparseSectionIndex.MapEntry(109, 2)),
        List.of(new SparseSectionIndex.Copy(40, 20)));
  }

  private SparseSectionIndex raw(
      List<Long> ids,
      List<SparseSectionIndex.MapEntry> maps,
      List<SparseSectionIndex.Copy> copies) {
    return new SparseSectionIndex(
        111,
        123,
        131,
        149,
        161,
        ids,
        copies,
        maps,
        ids,
        Set.of(SparseSectionIndex.Capability.RAW_OFFSET_ORIGIN_UNVERIFIED));
  }

  private ValidatedSparseSection prepare(SparseSectionIndex index) throws Exception {
    return ValidatedSparseSection.prepare(index, section(), limits, () -> false);
  }

  @Test
  void locatesPhysicalAndCopyWithoutClaimingWholeTableAbsence() throws Exception {
    var locator = prepare(raw());
    var physical = locator.lookup(20).location().orElseThrow();
    assertEquals(new ValidatedSparseSection.Location(20, 20, 104, 5, false), physical);
    assertEquals(
        new ValidatedSparseSection.Location(40, 20, 104, 5, true),
        locator.lookup(40).location().orElseThrow());
    assertEquals(ValidatedSparseSection.Status.NOT_IN_THIS_SECTION, locator.lookup(99).status());
    assertTrue(locator.lookup(99).location().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> locator.lookup(-1));
  }

  @Test
  void requiresMatchingStrictlyIncreasingPositiveIds() {
    for (var ids : List.of(List.of(0L, 20L, 30L), List.of(10L, 10L, 30L), List.of(20L, 10L, 30L)))
      assertThrows(
          SparseRecordException.class, () -> prepare(raw(ids, raw().mapEntries(), raw().copies())));
    var raw = raw();
    var different =
        new SparseSectionIndex(
            111,
            123,
            131,
            149,
            161,
            raw.recordIds(),
            raw.copies(),
            raw.mapEntries(),
            List.of(10L, 21L, 30L),
            Set.of());
    assertThrows(SparseRecordException.class, () -> prepare(different));
  }

  @Test
  void rejectsGapsOverlapsZeroSpansAndWrongFinalExtentRatherThanRepairing() {
    for (var map :
        List.of(
            new SparseSectionIndex.MapEntry(105, 5),
            new SparseSectionIndex.MapEntry(103, 5),
            new SparseSectionIndex.MapEntry(104, 0),
            new SparseSectionIndex.MapEntry(104, 65536))) {
      var maps = new ArrayList<>(raw().mapEntries());
      maps.set(1, map);
      assertThrows(
          SparseRecordException.class, () -> prepare(raw(raw().recordIds(), maps, raw().copies())));
    }
    var maps = new ArrayList<>(raw().mapEntries());
    maps.set(2, new SparseSectionIndex.MapEntry(109, 1));
    assertThrows(
        SparseRecordException.class, () -> prepare(raw(raw().recordIds(), maps, raw().copies())));
  }

  @Test
  void requiresUniqueNonphysicalCopyTargetsAndPhysicalSources() {
    for (var copy :
        List.of(
            new SparseSectionIndex.Copy(20, 10),
            new SparseSectionIndex.Copy(40, 41),
            new SparseSectionIndex.Copy(0, 20),
            new SparseSectionIndex.Copy(40, 40)))
      assertThrows(
          SparseRecordException.class,
          () -> prepare(raw(raw().recordIds(), raw().mapEntries(), List.of(copy))));
  }

  @Test
  void rejectsDuplicateCopyTargetsEvenWhenSuffixGeometryIsConsistent() {
    var original = raw();
    var duplicate =
        new SparseSectionIndex(
            111,
            123,
            139,
            157,
            169,
            original.recordIds(),
            List.of(new SparseSectionIndex.Copy(40, 10), new SparseSectionIndex.Copy(40, 20)),
            original.mapEntries(),
            original.mapIds(),
            Set.of());
    var section = new Wdc5Metadata.Section("0000000000000000", 100, 3, 0, 111, 12, 0, 3, 2, 169);
    assertEquals(
        SparseRecordException.Reason.UNSUPPORTED,
        assertThrows(
                SparseRecordException.class,
                () -> ValidatedSparseSection.prepare(duplicate, section, limits, () -> false))
            .reason());
  }

  @Test
  void exactSuffixGeometryCannotBeSubstituted() {
    var value = raw();
    var wrong =
        new SparseSectionIndex(
            111,
            124,
            131,
            149,
            161,
            value.recordIds(),
            value.copies(),
            value.mapEntries(),
            value.mapIds(),
            Set.of());
    assertThrows(SparseRecordException.class, () -> prepare(wrong));
    var encrypted = new Wdc5Metadata.Section("0000000000000001", 100, 3, 0, 111, 12, 0, 3, 1, 161);
    assertThrows(
        SparseRecordException.class,
        () -> ValidatedSparseSection.prepare(raw(), encrypted, limits, () -> false));
  }

  @Test
  void emptyAlternativeAndLimitsAreExplicitlyUnsupportedOrBounded() {
    var empty = new Wdc5Metadata.Section("0000000000000000", 100, 0, 0, 100, 0, 0, 0, 0, 100);
    var raw =
        new SparseSectionIndex(
            100, 100, 100, 100, 100, List.of(), List.of(), List.of(), List.of(), Set.of());
    assertEquals(
        SparseRecordException.Reason.UNSUPPORTED,
        assertThrows(
                SparseRecordException.class,
                () -> ValidatedSparseSection.prepare(raw, empty, limits, () -> false))
            .reason());
    for (var bound :
        List.of(
            new ValidatedSparseSection.Limits(2, 100, 1000),
            new ValidatedSparseSection.Limits(100, 0, 1000),
            new ValidatedSparseSection.Limits(100, 100, 10)))
      assertEquals(
          SparseRecordException.Reason.LIMIT,
          assertThrows(
                  SparseRecordException.class,
                  () -> ValidatedSparseSection.prepare(raw(), section(), bound, () -> false))
              .reason());
  }

  @Test
  void cancellationBeforeAndDuringValidationIsTyped() {
    assertEquals(
        SparseRecordException.Reason.CANCELLED,
        assertThrows(
                SparseRecordException.class,
                () -> ValidatedSparseSection.prepare(raw(), section(), limits, () -> true))
            .reason());
    AtomicInteger calls = new AtomicInteger();
    assertThrows(
        SparseRecordException.class,
        () ->
            ValidatedSparseSection.prepare(
                raw(), section(), limits, () -> calls.incrementAndGet() > 2));
  }

  @Test
  void randomizedContiguousFixtureOracleVerifiesEveryLocation() throws Exception {
    Random random = new Random(67);
    for (int attempt = 0; attempt < 1000; attempt++) {
      int count = 1 + random.nextInt(30);
      long offset = 500;
      var ids = new ArrayList<Long>();
      var maps = new ArrayList<SparseSectionIndex.MapEntry>();
      for (int i = 0; i < count; i++) {
        ids.add(100L + i * 2);
        int size = 1 + random.nextInt(100);
        maps.add(new SparseSectionIndex.MapEntry(offset, size));
        offset += size;
      }
      long copyStart = offset + count * 4L,
          mapStart = copyStart + 8,
          mapIdStart = mapStart + count * 6L,
          end = mapIdStart + count * 4L;
      var section =
          new Wdc5Metadata.Section(
              "0000000000000000", 500, count, 0, offset, count * 4L, 0, count, 1, end);
      int source = random.nextInt(count);
      var raw =
          new SparseSectionIndex(
              offset,
              copyStart,
              mapStart,
              mapIdStart,
              end,
              ids,
              List.of(new SparseSectionIndex.Copy(999, ids.get(source))),
              maps,
              ids,
              Set.of());
      var actual = ValidatedSparseSection.prepare(raw, section, limits, () -> false);
      for (int i = 0; i < count; i++)
        assertEquals(
            new ValidatedSparseSection.Location(
                ids.get(i), ids.get(i), maps.get(i).rawOffset(), maps.get(i).size(), false),
            actual.lookup(ids.get(i)).location().orElseThrow());
      assertEquals(
          maps.get(source).rawOffset(),
          actual.lookup(999).location().orElseThrow().decodedFileOffset());
    }
  }
}
