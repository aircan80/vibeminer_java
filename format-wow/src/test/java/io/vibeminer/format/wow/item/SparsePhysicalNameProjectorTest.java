package io.vibeminer.format.wow.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.format.wow.schema.*;
import io.vibeminer.protocol.db2.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SparsePhysicalNameProjectorTest {
  final SparsePhysicalNameProjector parser = new SparsePhysicalNameProjector();
  final SparsePhysicalNameProjector.Limits limits =
      new SparsePhysicalNameProjector.Limits(4096, 4096, 100, 100, 10000, 1000, 100, 500, 1000);
  final byte[] first =
      "description\0third\0second\0first\0Spark α\0uninspected".getBytes(StandardCharsets.UTF_8);
  final byte[] second = new byte[] {0, 0, 0, 0, 0, (byte) 0xff};

  byte[] area() {
    var a = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, a, first.length, second.length);
    return a;
  }

  Wdc5Metadata metadata() {
    return metadata(5);
  }

  Wdc5Metadata metadata(int flags) {
    var h =
        new Wdc5Header(
            5,
            "SYNTHETIC",
            2,
            67,
            328,
            0,
            0x919be54eL,
            0x1c17d17fL,
            101,
            202,
            1,
            flags,
            0,
            67,
            328,
            0,
            1608,
            0,
            0,
            1);
    return new Wdc5Metadata(
        h,
        List.of(
            new Wdc5Metadata.Section(
                "0000000000000000",
                4096,
                2,
                0,
                4096 + area().length,
                8,
                0,
                2,
                1,
                4096 + area().length + 36)),
        Collections.nCopies(67, new Wdc5Metadata.Field((short) 0, (short) 0)),
        Collections.nCopies(
            67,
            new Wdc5Metadata.Storage(
                0, 0, 0, 0, Optional.of(Wdc5Metadata.StorageKind.NONE), 0, 0, 0)),
        2120,
        2120,
        Set.of());
  }

  byte[] suffix() {
    var b = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(101)
        .putInt(202)
        .putInt(303)
        .putInt(101)
        .putInt(4096)
        .putShort((short) first.length)
        .putInt(4096 + first.length)
        .putShort((short) second.length)
        .putInt(101)
        .putInt(202);
    return b.array();
  }

  SelectedSchema schema() {
    var f = new ArrayList<SelectedSchema.Field>();
    f.add(
        new SelectedSchema.Field(
            "ID",
            "ID",
            SelectedSchema.Type.INT,
            false,
            Optional.empty(),
            32,
            SelectedSchema.Sign.SIGNED,
            1,
            Set.of("noninline", "id")));
    var strings =
        List.of(
            "Description_lang", "Display3_lang", "Display2_lang", "Display1_lang", "Display_lang");
    for (int i = 0; i < 67; i++)
      f.add(
          new SelectedSchema.Field(
              i < 5 ? strings.get(i) : "Authored" + i,
              i < 5 ? strings.get(i) : "Authored" + i,
              i < 5 ? SelectedSchema.Type.LOCSTRING : SelectedSchema.Type.INT,
              false,
              Optional.empty(),
              i < 5 ? 0 : 32,
              i < 5 ? SelectedSchema.Sign.NOT_SPECIFIED : SelectedSchema.Sign.SIGNED,
              1,
              Set.of()));
    return new SelectedSchema(
        SelectedSchema.Status.MATCH,
        new SchemaSelection(
            "wow", "12.1.5.70448", "ItemSparse", 0x919be54eL, 0x1c17d17fL, 67, true, true),
        new SelectedSchema.Provenance(
            "WoWDBDefs",
            "b9a63096cdd99b26967f17f9515377ae62cadfd7",
            "https://example.invalid/authored-fixture",
            "8664c3b5862dfe4c3a4322941b48b032c57b90412c070deb2028f3e448cf674a",
            "CC BY-SA-4.0",
            "Authored synthetic values",
            false),
        f,
        List.of());
  }

  SparsePhysicalNameProjector.Prepared prepare() throws Exception {
    return parser.prepare(metadata(), schema(), suffix(), 0, limits, () -> false);
  }

  @Test
  void projectsOnlyFifthPhysicalStringWithExactUtf8CountAndEmptyName() throws Exception {
    var rows = new ArrayList<SparsePhysicalNameProjector.Row>();
    var complete =
        parser.project(prepare(), new ByteArrayInputStream(area()), rows::add, () -> false);
    assertEquals(
        List.of(101L, 202L), rows.stream().map(SparsePhysicalNameProjector.Row::recordId).toList());
    assertEquals("Spark α", rows.getFirst().displayCandidate());
    assertEquals("", rows.getLast().displayCandidate());
    assertEquals("Spark α".getBytes(StandardCharsets.UTF_8).length, complete.projectedUtf8Bytes());
    assertEquals(2, complete.physicalRows());
    assertEquals(1, complete.unindexedCopyDeclarations());
    assertEquals(0, complete.excludedSections());
    assertEquals(4096, rows.getFirst().decodedOffset());
    assertEquals(4096 + first.length, rows.getLast().decodedOffset());
  }

  @Test
  void shortReadsAndUninspectedTrailingBytesDoNotImplyWholeRecordInterpretation() throws Exception {
    var stream =
        new ByteArrayInputStream(area()) {
          @Override
          public synchronized int read(byte[] b, int off, int len) {
            return super.read(b, off, Math.min(1, len));
          }
        };
    assertEquals(2, parser.project(prepare(), stream, row -> {}, () -> false).physicalRows());
  }

  @Test
  void suffixIsCopiedIntoPrivatePlan() throws Exception {
    var suffix = suffix();
    var plan = parser.prepare(metadata(), schema(), suffix, 0, limits, () -> false);
    Arrays.fill(suffix, (byte) 0);
    assertEquals(
        2,
        parser
            .project(plan, new ByteArrayInputStream(area()), row -> {}, () -> false)
            .physicalRows());
  }

  @Test
  void lateInvalidUtf8AndTrailingWindowBytesCannotComplete() throws Exception {
    var bytes = area();
    bytes[first.length] = (byte) 0xff;
    var rows = new ArrayList<SparsePhysicalNameProjector.Row>();
    assertEquals(
        SparseNameProjectionException.Reason.MALFORMED,
        assertThrows(
                SparseNameProjectionException.class,
                () ->
                    parser.project(
                        prepare(), new ByteArrayInputStream(bytes), rows::add, () -> false))
            .reason());
    assertEquals(1, rows.size());
    var extra = Arrays.copyOf(area(), area().length + 1);
    assertEquals(
        SparseNameProjectionException.Reason.MALFORMED,
        assertThrows(
                SparseNameProjectionException.class,
                () ->
                    parser.project(
                        prepare(), new ByteArrayInputStream(extra), row -> {}, () -> false))
            .reason());
  }

  @Test
  void cancellationAfterProvisionalRowCannotComplete() throws Exception {
    var cancelled = new AtomicBoolean();
    var rows = new ArrayList<SparsePhysicalNameProjector.Row>();
    assertEquals(
        SparseNameProjectionException.Reason.CANCELLED,
        assertThrows(
                SparseNameProjectionException.class,
                () ->
                    parser.project(
                        prepare(),
                        new ByteArrayInputStream(area()),
                        row -> {
                          rows.add(row);
                          cancelled.set(true);
                        },
                        cancelled::get))
            .reason());
    assertEquals(1, rows.size());
  }

  @Test
  void exactSchemaFlagsAndStringOrderAreMandatory() throws Exception {
    for (int flags : new int[] {0, 1, 4, 7, 0x25})
      assertEquals(
          SparseNameProjectionException.Reason.INCOMPATIBLE,
          assertThrows(
                  SparseNameProjectionException.class,
                  () -> parser.prepare(metadata(flags), schema(), suffix(), 0, limits, () -> false))
              .reason());
    var base = schema();
    var fields = new ArrayList<>(base.fields());
    Collections.swap(fields, 4, 5);
    var reordered =
        new SelectedSchema(base.status(), base.selection(), base.provenance(), fields, List.of());
    assertEquals(
        SparseNameProjectionException.Reason.INCOMPATIBLE,
        assertThrows(
                SparseNameProjectionException.class,
                () -> parser.prepare(metadata(), reordered, suffix(), 0, limits, () -> false))
            .reason());
    var selection =
        new SchemaSelection(
            "wow", "12.1.5.70449", "ItemSparse", 0x919be54eL, 0x1c17d17fL, 67, true, true);
    var changed =
        new SelectedSchema(base.status(), selection, base.provenance(), base.fields(), List.of());
    assertEquals(
        SparseNameProjectionException.Reason.INCOMPATIBLE,
        assertThrows(
                SparseNameProjectionException.class,
                () -> parser.prepare(metadata(), changed, suffix(), 0, limits, () -> false))
            .reason());
    assertEquals(
        SparseNameProjectionException.Reason.UNSUPPORTED,
        assertThrows(
                SparseNameProjectionException.class,
                () -> parser.prepare(metadata(), base, suffix(), 1, limits, () -> false))
            .reason());
  }

  @Test
  void malformedSparseIdentitiesCoordinatesAndCopiesAreNeverRepaired() {
    for (int which = 0; which < 5; which++) {
      var raw = suffix();
      var b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      switch (which) {
        case 0 -> b.putInt(4, 101);
        case 1 -> b.putInt(28, 102);
        case 2 -> b.putInt(16, 0);
        case 3 -> b.putShort(20, (short) 0);
        case 4 -> b.putInt(12, 999);
        default -> throw new AssertionError();
      }
      assertThrows(
          SparseNameProjectionException.class,
          () -> parser.prepare(metadata(), schema(), raw, 0, limits, () -> false));
    }
  }

  @Test
  void invalidUtf8InEveryConsumedStringIsRejected() throws Exception {
    int start = 0;
    for (int field = 0; field < 5; field++) {
      byte[] invalid = area();
      invalid[start] = (byte) 0xff;
      assertEquals(
          SparseNameProjectionException.Reason.MALFORMED,
          assertThrows(
                  SparseNameProjectionException.class,
                  () ->
                      parser.project(
                          prepare(),
                          new ByteArrayInputStream(invalid),
                          row -> fail("Invalid prefix cannot emit a row"),
                          () -> false))
              .reason());
      while (first[start] != 0) start++;
      start++;
    }
  }

  @Test
  void everyTruncatedRecordWindowAndMissingTerminatorCannotComplete() throws Exception {
    var plan = prepare();
    for (int size = 0; size < area().length; size++) {
      var truncated = Arrays.copyOf(area(), size);
      assertEquals(
          SparseNameProjectionException.Reason.MALFORMED,
          assertThrows(
                  SparseNameProjectionException.class,
                  () ->
                      parser.project(
                          plan, new ByteArrayInputStream(truncated), row -> {}, () -> false))
              .reason());
    }
    var missing = area();
    Arrays.fill(missing, first.length, missing.length, (byte) 'x');
    assertEquals(
        SparseNameProjectionException.Reason.MALFORMED,
        assertThrows(
                SparseNameProjectionException.class,
                () ->
                    parser.project(plan, new ByteArrayInputStream(missing), row -> {}, () -> false))
            .reason());
  }

  @Test
  void explicitPerStringRecordSuffixAndActualUtf8AggregateLimits() throws Exception {
    var textLimit =
        new SparsePhysicalNameProjector.Limits(
            4096, 4096, 100, 100, 10000, 1000, 100, 500, "Spark α".length());
    var plan = parser.prepare(metadata(), schema(), suffix(), 0, textLimit, () -> false);
    assertEquals(
        SparseNameProjectionException.Reason.LIMIT,
        assertThrows(
                SparseNameProjectionException.class,
                () ->
                    parser.project(
                        plan,
                        new ByteArrayInputStream(area()),
                        row -> fail("UTF-8 byte budget must reject before callback"),
                        () -> false))
            .reason());
    var shortString =
        new SparsePhysicalNameProjector.Limits(4096, 4096, 100, 100, 10000, 1000, 2, 500, 1000);
    var stringPlan = parser.prepare(metadata(), schema(), suffix(), 0, shortString, () -> false);
    assertEquals(
        SparseNameProjectionException.Reason.LIMIT,
        assertThrows(
                SparseNameProjectionException.class,
                () ->
                    parser.project(
                        stringPlan, new ByteArrayInputStream(area()), row -> {}, () -> false))
            .reason());
    for (var bound :
        List.of(
            new SparsePhysicalNameProjector.Limits(4096, 35, 100, 100, 10000, 1000, 100, 500, 1000),
            new SparsePhysicalNameProjector.Limits(4096, 4096, 100, 100, 10000, 1, 100, 500, 1000),
            new SparsePhysicalNameProjector.Limits(4096, 4096, 100, 100, 1, 1000, 100, 500, 1000)))
      assertEquals(
          SparseNameProjectionException.Reason.LIMIT,
          assertThrows(
                  SparseNameProjectionException.class,
                  () -> parser.prepare(metadata(), schema(), suffix(), 0, bound, () -> false))
              .reason());
  }

  @Test
  void callerOwnsStreamAndSinkFailureIsPreserved() throws Exception {
    var closed = new AtomicBoolean();
    var stream =
        new ByteArrayInputStream(area()) {
          @Override
          public void close() {
            closed.set(true);
          }
        };
    parser.project(prepare(), stream, row -> {}, () -> false);
    assertFalse(closed.get());
    var failure = new IllegalStateException("Discard provisional staging");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                parser.project(
                    prepare(),
                    new ByteArrayInputStream(area()),
                    row -> {
                      throw failure;
                    },
                    () -> false)));
    var io =
        new InputStream() {
          public int read() throws IOException {
            throw new IOException("auth window failed");
          }
        };
    assertEquals(
        SparseNameProjectionException.Reason.IO_FAILURE,
        assertThrows(
                SparseNameProjectionException.class,
                () -> parser.project(prepare(), io, row -> {}, () -> false))
            .reason());
  }
}
