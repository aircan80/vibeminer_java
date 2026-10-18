package io.vibeminer.format.wow.item;

import static io.vibeminer.format.wow.item.ItemProjectionException.Reason.*;

import io.vibeminer.format.wow.schema.*;
import io.vibeminer.protocol.db2.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Exact-cohort physical Item projection. No source authentication, copy expansion or publication.
 * Callers keep supplied arrays unchanged during calls and discard provisional sink output on
 * failure.
 */
public final class ItemPhysicalProjector {
  public static final String SCHEMA_REVISION = "b9a63096cdd99b26967f17f9515377ae62cadfd7";
  public static final String SCHEMA_SHA256 =
      "076ea4c7e80c891b399cc77f285c807071080c0fed95701e447f7c98024ae120";
  public static final String PROJECTION_REVISION = "item-70448-physical-four-integers-v1";
  private static final List<String> NAMES =
      List.of("ClassID", "SubclassID", "InventoryType", "IconFileDataID");
  private static final List<Integer> ORDINALS = List.of(0, 1, 3, 6);

  public record Limits(
      int maxSectionBytes,
      int maxPhysicalRows,
      int maxCopyDeclarations,
      int maxMetadataBytes,
      int maxPalletBytes) {
    public Limits {
      if (maxSectionBytes < 0
          || maxSectionBytes > 4 * 1024 * 1024
          || maxPhysicalRows < 0
          || maxCopyDeclarations < 0
          || maxMetadataBytes < 0
          || maxPalletBytes < 0)
        throw new IllegalArgumentException("Invalid bounded Item projection limits");
    }
  }

  private record Binding(
      String name, SelectedSchema.Field field, Wdc5Metadata.Storage storage, byte[] pallet) {}

  /** Privately prepared immutable interpretation recipe, not an authenticated-source proof. */
  public static final class Prepared {
    private final Wdc5Metadata metadata;
    private final int sectionOrdinal;
    private final Limits limits;
    private final SelectedSchema.Provenance provenance;
    private final List<Binding> bindings;

    private Prepared(
        Wdc5Metadata metadata,
        int ordinal,
        Limits limits,
        SelectedSchema.Provenance provenance,
        List<Binding> bindings) {
      this.metadata = metadata;
      sectionOrdinal = ordinal;
      this.limits = limits;
      this.provenance = provenance;
      this.bindings = List.copyOf(bindings);
    }

    public SelectedSchema.Provenance provenance() {
      return provenance;
    }

    public int sectionOrdinal() {
      return sectionOrdinal;
    }

    public String projectionRevision() {
      return PROJECTION_REVISION;
    }
  }

  public record IntegerCandidate(
      String name,
      FieldValueDecoder.IntegerValue raw,
      long candidateValue,
      SelectedSchema.Sign candidateSign,
      int candidateBits) {}

  /** recordId is an Item record ID, never the Item table's FileDataID. */
  public record Row(
      long recordId, int physicalOrdinal, int sectionOrdinal, List<IntegerCandidate> values) {
    public Row {
      values = List.copyOf(values);
    }
  }

  /** Structural completion only; copies and all other sections remain unindexed. */
  public record Completion(
      long physicalRows,
      long unindexedCopyDeclarations,
      int excludedSections,
      int sectionOrdinal,
      String projectionRevision,
      SelectedSchema.Provenance provenance) {}

  @FunctionalInterface
  public interface RowSink {
    void accept(Row provisionalRow);
  }

  public Prepared prepare(
      Wdc5Metadata metadata,
      SelectedSchema schema,
      byte[] prefix,
      int ordinal,
      Limits limits,
      BooleanSupplier cancelled)
      throws ItemProjectionException {
    Objects.requireNonNull(metadata);
    Objects.requireNonNull(schema);
    Objects.requireNonNull(prefix);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    var h = metadata.header();
    var selected = schema.selection();
    if (schema.status() != SelectedSchema.Status.MATCH
        || selected == null
        || !selected.product().equals("wow")
        || !selected.build().equals("12.1.5.70448")
        || !selected.tableName().equals("Item")
        || selected.tableHash() != 0x50238ec2L
        || selected.layoutHash() != 0x996192aaL
        || selected.physicalFieldCount() != 15
        || selected.sparse()
        || !selected.noninlineId()
        || h.tableHash() != selected.tableHash()
        || h.layoutHash() != selected.layoutHash()
        || h.schemaVersion() != 5
        || h.flags() != 4
        || h.fieldCount() != 15
        || h.totalFieldCount() != 15
        || h.recordSize() != 11
        || h.lookupColumnCount() != 0
        || h.commonDataSize() != 0
        || !schema.provenance().provider().equals("WoWDBDefs")
        || !schema.provenance().revision().equals(SCHEMA_REVISION)
        || !schema.provenance().sha256().equals(SCHEMA_SHA256))
      throw error(
          INCOMPATIBLE, "Requires the exact reviewed Item build/layout/schema interpretation");
    if (ordinal != 0
        || metadata.sections().isEmpty()
        || h.sectionCount() != metadata.sections().size()
        || metadata.storage().size() != 15
        || metadata.fields().size() != 15)
      throw error(UNSUPPORTED, "Requires admitted first section and consistent field metadata");
    var section = metadata.sections().get(ordinal);
    if (section.encryptionKeyDeclared()
        || section.relationshipDataSize() != 0
        || section.offsetMapIdCount() != 0
        || section.offsetRecordsEndOffset() != 0)
      throw error(UNSUPPORTED, "Only fixed zero-key section without relationships is admitted");
    long span;
    try {
      span = Math.subtractExact(section.declaredEndOffset(), section.fileOffset());
      if (span < 0
          || Math.addExact(
                  section.fileOffset(),
                  Math.addExact(
                      Math.multiplyExact(section.recordCount(), h.recordSize()),
                      Math.addExact(
                          section.stringTableSize(),
                          Math.addExact(
                              section.idListSize(),
                              Math.multiplyExact(section.copyTableCount(), 8L)))))
              != section.declaredEndOffset())
        throw error(MALFORMED, "Invalid admitted section geometry");
    } catch (ArithmeticException overflow) {
      throw new ItemProjectionException(MALFORMED, "Section geometry overflow", overflow);
    }
    if (span > limits.maxSectionBytes()
        || section.recordCount() > limits.maxPhysicalRows()
        || section.copyTableCount() > limits.maxCopyDeclarations()
        || prefix.length > limits.maxMetadataBytes()
        || h.palletDataSize() > limits.maxPalletBytes())
      throw error(LIMIT, "Item preparation exceeds declared bounds");
    if (metadata.metadataBytesRead() < 0
        || metadata.declaredAuxiliaryDataEnd() != metadata.metadataBytesRead() + h.palletDataSize()
        || metadata.declaredAuxiliaryDataEnd() > prefix.length)
      throw error(MALFORMED, "Auxiliary data lies outside metadata prefix");
    var inline = new ArrayList<SelectedSchema.Field>();
    int ids = 0;
    for (var field : schema.fields()) {
      check(cancelled);
      if (field.noninline()) {
        if (!field.id()
            || !field.name().equals("ID")
            || field.type() != SelectedSchema.Type.INT
            || field.integerBits() != 32
            || field.arrayLength() != 1)
          throw error(UNSUPPORTED, "Unsupported noninline schema field");
        ids++;
      } else {
        if (field.id())
          throw error(INCOMPATIBLE, "Inline identity conflicts with noninline Item header");
        inline.add(field);
      }
    }
    if (ids != 1 || inline.size() != 15)
      throw error(INCOMPATIBLE, "Schema physical field cardinality mismatch");
    var bindings = new ArrayList<Binding>();
    long palletAt = metadata.metadataBytesRead();
    for (int i = 0; i < 15; i++) {
      check(cancelled);
      var storage = metadata.storage().get(i);
      long start = palletAt;
      if (storage.rawKind() != 1 && storage.rawKind() != 3 && storage.rawKind() != 5)
        throw error(UNSUPPORTED, "Unobserved Item storage mode");
      if (storage.additionalDataSize() < 0
          || storage.additionalDataSize() > limits.maxPalletBytes())
        throw error(LIMIT, "Invalid per-field auxiliary bound");
      if (storage.rawKind() == 3) {
        try {
          palletAt = Math.addExact(palletAt, storage.additionalDataSize());
        } catch (ArithmeticException overflow) {
          throw error(MALFORMED, "Palette offset overflow");
        }
      } else if (storage.additionalDataSize() != 0)
        throw error(MALFORMED, "Immediate field declares auxiliary bytes");
      if (palletAt > metadata.declaredAuxiliaryDataEnd())
        throw error(MALFORMED, "Per-field palette exceeds auxiliary range");
      var field = inline.get(i);
      int which = NAMES.indexOf(field.name());
      if (which < 0) continue;
      int expectedBits = which == 1 || which == 2 ? 8 : 32;
      var expectedSign = which == 1 ? SelectedSchema.Sign.UNSIGNED : SelectedSchema.Sign.SIGNED;
      if (i != ORDINALS.get(which)
          || field.type() != SelectedSchema.Type.INT
          || field.arrayLength() != 1
          || field.unverified()
          || field.integerBits() != expectedBits
          || field.sign() != expectedSign)
        throw error(INCOMPATIBLE, "Requested candidate field is incompatible or ambiguous");
      byte[] pallet = Arrays.copyOfRange(prefix, (int) start, (int) palletAt);
      // Validate descriptor parameters and record bounds before any output; palette index zero is
      // supported.
      try {
        new FieldValueDecoder().decode(new byte[11], 0, 11, storage, pallet, 0, pallet.length);
      } catch (FieldValueException invalid) {
        throw new ItemProjectionException(
            UNSUPPORTED, "Unsupported requested field descriptor", invalid);
      }
      bindings.add(new Binding(field.name(), field, storage, pallet));
    }
    if (palletAt != metadata.declaredAuxiliaryDataEnd()
        || bindings.size() != 4
        || !bindings.stream().map(Binding::name).toList().equals(NAMES))
      throw error(INCOMPATIBLE, "Four unique candidate bindings and exact palettes required");
    check(cancelled);
    return new Prepared(metadata, ordinal, limits, schema.provenance(), bindings);
  }

  public Completion project(
      Prepared plan, byte[] sectionBytes, RowSink sink, BooleanSupplier cancelled)
      throws ItemProjectionException {
    Objects.requireNonNull(plan);
    Objects.requireNonNull(sectionBytes);
    Objects.requireNonNull(sink);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    var section = plan.metadata.sections().get(plan.sectionOrdinal);
    FixedSectionIndex index;
    try {
      index =
          new FixedSectionIndexParser()
              .parse(
                  sectionBytes,
                  plan.metadata.header(),
                  section,
                  new FixedSectionIndexParser.Limits(
                      plan.limits.maxSectionBytes(),
                      plan.limits.maxPhysicalRows(),
                      plan.limits.maxCopyDeclarations()),
                  cancelled);
    } catch (Db2HeaderException invalid) {
      throw new ItemProjectionException(
          switch (invalid.reason()) {
            case CANCELLED -> CANCELLED;
            case LIMIT -> LIMIT;
            case UNSUPPORTED -> UNSUPPORTED;
            default -> MALFORMED;
          },
          "Invalid admitted section index",
          invalid);
    }
    var seen = new HashSet<Long>();
    for (long id : index.recordIds()) {
      check(cancelled);
      if (id == 0 || !seen.add(id))
        throw error(MALFORMED, "Invalid or duplicate physical Item record ID");
    }
    var decoder = new FieldValueDecoder();
    for (int i = 0; i < index.recordIds().size(); i++) {
      check(cancelled);
      int at = Math.toIntExact(index.recordOffset(i) - section.fileOffset());
      var values = new ArrayList<IntegerCandidate>(4);
      for (var binding : plan.bindings) {
        check(cancelled);
        try {
          var raw =
              decoder.decode(
                  sectionBytes, at, 11, binding.storage, binding.pallet, 0, binding.pallet.length);
          values.add(
              new IntegerCandidate(
                  binding.name,
                  raw,
                  interpret(raw, binding.field),
                  binding.field.sign(),
                  binding.field.integerBits()));
        } catch (FieldValueException invalid) {
          throw new ItemProjectionException(
              MALFORMED, "Physical integer projection failed", invalid);
        }
      }
      sink.accept(new Row(index.recordIds().get(i), i, plan.sectionOrdinal, values));
    }
    check(cancelled);
    return new Completion(
        index.recordIds().size(),
        index.copies().size(),
        plan.metadata.sections().size() - 1,
        plan.sectionOrdinal,
        PROJECTION_REVISION,
        plan.provenance);
  }

  private static long interpret(FieldValueDecoder.IntegerValue raw, SelectedSchema.Field field)
      throws ItemProjectionException {
    int bits = field.integerBits();
    if (raw.signedPhysicalValue().isEmpty() && raw.bitWidth() == 64 && raw.rawBits() < 0)
      throw error(INCOMPATIBLE, "Unsigned physical value exceeds candidate signedness/width");
    long value = raw.signedPhysicalValue().orElse(raw.rawBits());
    if (raw.signedPhysicalValue().isEmpty()
        && field.sign() == SelectedSchema.Sign.SIGNED
        && raw.bitWidth() == 32
        && bits == 32) value = (int) raw.rawBits();
    long min = field.sign() == SelectedSchema.Sign.SIGNED ? -(1L << (bits - 1)) : 0,
        max =
            field.sign() == SelectedSchema.Sign.SIGNED ? (1L << (bits - 1)) - 1 : (1L << bits) - 1;
    if (value < min || value > max)
      throw error(INCOMPATIBLE, "Physical value exceeds candidate signedness/width");
    return value;
  }

  private static void check(BooleanSupplier cancelled) throws ItemProjectionException {
    if (cancelled.getAsBoolean()) throw error(CANCELLED, "Item physical projection cancelled");
  }

  private static ItemProjectionException error(
      ItemProjectionException.Reason reason, String message) {
    return new ItemProjectionException(reason, message);
  }
}
