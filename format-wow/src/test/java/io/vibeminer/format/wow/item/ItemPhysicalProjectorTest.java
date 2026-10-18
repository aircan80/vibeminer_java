package io.vibeminer.format.wow.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.format.wow.schema.*;
import io.vibeminer.protocol.db2.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ItemPhysicalProjectorTest {
  final ItemPhysicalProjector parser = new ItemPhysicalProjector();
  final ItemPhysicalProjector.Limits limits =
      new ItemPhysicalProjector.Limits(4096, 20, 20, 4096, 1024);

  static Wdc5Metadata.Storage storage(int offset, int width, int kind, int extra) {
    return new Wdc5Metadata.Storage(
        offset,
        width,
        extra,
        kind,
        Optional.of(Wdc5Metadata.StorageKind.values()[kind]),
        offset,
        width,
        0);
  }

  Wdc5Metadata metadata() {
    var h =
        new Wdc5Header(
            5,
            "SYNTHETIC",
            2,
            15,
            11,
            0,
            0x50238ec2L,
            0x996192aaL,
            1,
            2,
            0xffffffffL,
            4,
            0,
            15,
            0,
            0,
            360,
            0,
            12,
            1);
    var descriptors = new ArrayList<Wdc5Metadata.Storage>();
    for (int i = 0; i < 15; i++) descriptors.add(storage(0, 1, 1, 0));
    descriptors.set(0, storage(0, 2, 3, 8)); // requested Class palette
    descriptors.set(1, storage(2, 8, 1, 0));
    descriptors.set(2, storage(10, 1, 3, 4)); // unprojected palette shifts later slices
    descriptors.set(3, storage(11, 8, 5, 0));
    descriptors.set(6, storage(19, 16, 1, 0));
    return new Wdc5Metadata(
        h,
        List.of(new Wdc5Metadata.Section("0000000000000000", 100, 2, 0, 0, 8, 0, 0, 1, 138)),
        Collections.nCopies(15, new Wdc5Metadata.Field((short) 0, (short) 0)),
        descriptors,
        32,
        44,
        Set.of());
  }

  SelectedSchema schema() {
    var fields = new ArrayList<SelectedSchema.Field>();
    fields.add(field("ID", 32, SelectedSchema.Sign.UNSIGNED, Set.of("noninline", "id")));
    for (int i = 0; i < 15; i++)
      fields.add(
          field(
              switch (i) {
                case 0 -> "ClassID";
                case 1 -> "SubclassID";
                case 3 -> "InventoryType";
                case 6 -> "IconFileDataID";
                default -> "Synthetic" + i;
              },
              i == 1 || i == 3 ? 8 : 32,
              i == 1 ? SelectedSchema.Sign.UNSIGNED : SelectedSchema.Sign.SIGNED,
              Set.of()));
    return new SelectedSchema(
        SelectedSchema.Status.MATCH,
        new SchemaSelection(
            "wow", "12.1.5.70448", "Item", 0x50238ec2L, 0x996192aaL, 15, false, true),
        new SelectedSchema.Provenance(
            "WoWDBDefs",
            ItemPhysicalProjector.SCHEMA_REVISION,
            "https://example.invalid/authored-fixture",
            ItemPhysicalProjector.SCHEMA_SHA256,
            "CC BY-SA-4.0",
            "Synthetic test values; pinned interpretation metadata",
            false),
        fields,
        List.of());
  }

  static SelectedSchema.Field field(
      String name, int bits, SelectedSchema.Sign sign, Set<String> annotations) {
    return new SelectedSchema.Field(
        name, name, SelectedSchema.Type.INT, false, Optional.empty(), bits, sign, 1, annotations);
  }

  byte[] prefix() {
    var p = new byte[44];
    ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 3).putInt(36, 9).putInt(40, 77);
    return p;
  }

  static void bits(byte[] target, int start, int width, long value) {
    for (int i = 0; i < width; i++)
      if (((value >>> i) & 1) != 0) target[(start + i) / 8] |= (byte) (1 << ((start + i) % 8));
  }

  byte[] section() {
    byte[] s = new byte[38];
    bits(s, 0, 2, 1);
    bits(s, 2, 8, 7);
    bits(s, 11, 8, 0xfd);
    bits(s, 19, 16, 123);
    bits(s, 88, 2, 0);
    bits(s, 90, 8, 8);
    bits(s, 99, 8, 4);
    bits(s, 107, 16, 456);
    ByteBuffer.wrap(s)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(22, 101)
        .putInt(26, 202)
        .putInt(30, 303)
        .putInt(34, 101);
    return s;
  }

  ItemPhysicalProjector.Prepared prepare() throws Exception {
    return parser.prepare(metadata(), schema(), prefix(), 0, limits, () -> false);
  }

  @Test
  void projectsPhysicalIdsAndFourCandidatesWithoutExpandingCopies() throws Exception {
    var rows = new ArrayList<ItemPhysicalProjector.Row>();
    var done = parser.project(prepare(), section(), rows::add, () -> false);
    assertEquals(
        List.of(101L, 202L), rows.stream().map(ItemPhysicalProjector.Row::recordId).toList());
    assertEquals(9, rows.getFirst().values().get(0).candidateValue());
    assertEquals(-3, rows.getFirst().values().get(2).candidateValue());
    assertEquals(123, rows.getFirst().values().get(3).candidateValue());
    assertEquals(2, done.physicalRows());
    assertEquals(1, done.unindexedCopyDeclarations());
    assertEquals(253, rows.getFirst().values().get(2).raw().rawBits());
    assertThrows(UnsupportedOperationException.class, () -> rows.getFirst().values().clear());
  }

  @Test
  void preparedPlanDoesNotRetainMutablePaletteInputs() throws Exception {
    var p = prefix();
    var plan = parser.prepare(metadata(), schema(), p, 0, limits, () -> false);
    Arrays.fill(p, (byte) 0);
    var rows = new ArrayList<ItemPhysicalProjector.Row>();
    parser.project(plan, section(), rows::add, () -> false);
    assertEquals(9, rows.getFirst().values().getFirst().candidateValue());
  }

  @Test
  void rejectsWrongSchemaAndMissingBindingsBeforeRows() {
    var s = schema();
    var wrong =
        new SelectedSchema(
            s.status(),
            new SchemaSelection(
                "wow", "12.1.5.70391", "Item", 0x50238ec2L, 0x996192aaL, 15, false, true),
            s.provenance(),
            s.fields(),
            List.of());
    assertThrows(
        ItemProjectionException.class,
        () -> parser.prepare(metadata(), wrong, prefix(), 0, limits, () -> false));
    var fields = new ArrayList<>(s.fields());
    fields.set(7, field("Other", 32, SelectedSchema.Sign.SIGNED, Set.of()));
    assertThrows(
        ItemProjectionException.class,
        () ->
            parser.prepare(
                metadata(),
                new SelectedSchema(s.status(), s.selection(), s.provenance(), fields, List.of()),
                prefix(),
                0,
                limits,
                () -> false));
  }

  @Test
  void rejectsDuplicateAndTruncatedIdsBeforeAnyCallback() throws Exception {
    var plan = prepare();
    var bytes = section();
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(26, 101);
    var calls = new AtomicInteger();
    assertThrows(
        ItemProjectionException.class,
        () -> parser.project(plan, bytes, r -> calls.incrementAndGet(), () -> false));
    assertEquals(0, calls.get());
    assertThrows(
        ItemProjectionException.class,
        () ->
            parser.project(
                plan, Arrays.copyOf(section(), 37), r -> calls.incrementAndGet(), () -> false));
    assertEquals(0, calls.get());
  }

  @Test
  void lateNumericFailureLeavesOnlyProvisionalEarlierCallbacks() throws Exception {
    var bytes = section();
    bits(bytes, 88, 2, 3);
    var calls = new AtomicInteger();
    assertThrows(
        ItemProjectionException.class,
        () -> parser.project(prepare(), bytes, r -> calls.incrementAndGet(), () -> false));
    assertEquals(1, calls.get());
  }

  @Test
  void sinkAndLateCancellationNeverProduceCompletion() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            parser.project(
                prepare(),
                section(),
                r -> {
                  throw new IllegalStateException("stage failed");
                },
                () -> false));
    var calls = new AtomicInteger();
    var plan = prepare();
    assertThrows(
        ItemProjectionException.class,
        () -> parser.project(plan, section(), r -> calls.incrementAndGet(), () -> calls.get() > 0));
    assertEquals(1, calls.get());
  }

  @Test
  void sectionAndPalletLimitsAreExplicit() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ItemPhysicalProjector.Limits(4 * 1024 * 1024 + 1, 1, 1, 100, 100));
    assertThrows(
        ItemProjectionException.class,
        () ->
            parser.prepare(
                metadata(),
                schema(),
                prefix(),
                0,
                new ItemPhysicalProjector.Limits(4096, 20, 20, 4096, 4),
                () -> false));
    assertThrows(
        ItemProjectionException.class,
        () ->
            parser.prepare(
                metadata(),
                schema(),
                prefix(),
                0,
                new ItemPhysicalProjector.Limits(4096, 1, 20, 4096, 1024),
                () -> false));
  }

  @Test
  void unsignedRecordIdsArePreservedAndZeroIdsRejected() throws Exception {
    var bytes = section();
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, -1);
    var rows = new ArrayList<ItemPhysicalProjector.Row>();
    parser.project(prepare(), bytes, rows::add, () -> false);
    assertEquals(0xffffffffL, rows.getFirst().recordId());
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, 0);
    assertThrows(
        ItemProjectionException.class,
        () -> parser.project(prepare(), bytes, r -> fail("No callback for bad ID"), () -> false));
  }

  @Test
  void rejectsUnsupportedRequestedDescriptorAndCandidateType() {
    var m = metadata();
    var stores = new ArrayList<>(m.storage());
    stores.set(0, storage(0, 2, 4, 8));
    var bad =
        new Wdc5Metadata(
            m.header(),
            m.sections(),
            m.fields(),
            stores,
            m.metadataBytesRead(),
            m.declaredAuxiliaryDataEnd(),
            m.capabilities());
    assertThrows(
        ItemProjectionException.class,
        () -> parser.prepare(bad, schema(), prefix(), 0, limits, () -> false));
    var s = schema();
    var fields = new ArrayList<>(s.fields());
    fields.set(4, field("InventoryType", 32, SelectedSchema.Sign.SIGNED, Set.of()));
    assertThrows(
        ItemProjectionException.class,
        () ->
            parser.prepare(
                metadata(),
                new SelectedSchema(s.status(), s.selection(), s.provenance(), fields, List.of()),
                prefix(),
                0,
                limits,
                () -> false));
  }

  @Test
  void overflowAndPreCancellationFailWithoutOutput() {
    var m = metadata();
    var bad =
        new Wdc5Metadata(
            m.header(),
            List.of(
                new Wdc5Metadata.Section(
                    "0000000000000000", Long.MAX_VALUE, 2, 0, 0, 8, 0, 0, 1, 138)),
            m.fields(),
            m.storage(),
            32,
            44,
            Set.of());
    assertThrows(
        ItemProjectionException.class,
        () -> parser.prepare(bad, schema(), prefix(), 0, limits, () -> false));
    assertEquals(
        ItemProjectionException.Reason.CANCELLED,
        assertThrows(
                ItemProjectionException.class,
                () -> parser.prepare(metadata(), schema(), prefix(), 0, limits, () -> true))
            .reason());
  }

  @Test
  void unsigned64HighBitCannotBecomeNegativeSignedCandidate() throws Exception {
    var m = metadata();
    var stores = new ArrayList<>(m.storage());
    stores.set(6, storage(0, 64, 1, 0));
    var wide = new Wdc5Metadata(m.header(), m.sections(), m.fields(), stores, 32, 44, Set.of());
    var plan = parser.prepare(wide, schema(), prefix(), 0, limits, () -> false);
    var bytes = section();
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(0, -3L);
    assertEquals(
        ItemProjectionException.Reason.INCOMPATIBLE,
        assertThrows(
                ItemProjectionException.class,
                () -> parser.project(plan, bytes, r -> {}, () -> false))
            .reason());
  }

  @Test
  void signed64NegativeValueCanFitSignedCandidate() throws Exception {
    var m = metadata();
    var stores = new ArrayList<>(m.storage());
    stores.set(6, storage(0, 64, 5, 0));
    var wide = new Wdc5Metadata(m.header(), m.sections(), m.fields(), stores, 32, 44, Set.of());
    var plan = parser.prepare(wide, schema(), prefix(), 0, limits, () -> false);
    var bytes = section();
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(0, -3L);
    var rows = new ArrayList<ItemPhysicalProjector.Row>();
    parser.project(plan, bytes, rows::add, () -> false);
    assertEquals(-3L, rows.getFirst().values().get(3).candidateValue());
  }

  @Test
  void physicalIntegerCannotBeSilentlyTruncatedToCandidateWidth() throws Exception {
    var m = metadata();
    var stores = new ArrayList<>(m.storage());
    stores.set(1, storage(2, 16, 1, 0));
    var wide = new Wdc5Metadata(m.header(), m.sections(), m.fields(), stores, 32, 44, Set.of());
    var plan = parser.prepare(wide, schema(), prefix(), 0, limits, () -> false);
    var bytes = new byte[38];
    bits(bytes, 2, 16, 256);
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, 101).putInt(26, 202);
    assertEquals(
        ItemProjectionException.Reason.INCOMPATIBLE,
        assertThrows(
                ItemProjectionException.class,
                () ->
                    parser.project(
                        plan, bytes, r -> fail("Overflow row must not be emitted"), () -> false))
            .reason());
  }
}
