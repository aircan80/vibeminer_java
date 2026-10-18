package io.vibeminer.format.wow.item;

import static io.vibeminer.format.wow.item.SparseNameProjectionException.Reason.*;

import io.vibeminer.format.wow.schema.*;
import io.vibeminer.protocol.db2.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Pure exact-cohort physical name prefix projection. It authenticates no source bytes and never
 * expands copies. Supplied metadata/schema must come from independently validated inputs.
 */
public final class SparsePhysicalNameProjector {
  public static final String SCHEMA_REVISION = "b9a63096cdd99b26967f17f9515377ae62cadfd7";
  public static final String SCHEMA_SHA256 =
      "8664c3b5862dfe4c3a4322941b48b032c57b90412c070deb2028f3e448cf674a";
  public static final String PROJECTION_REVISION = "item-sparse-70448-physical-display-prefix-v1";
  private static final List<String> STRINGS =
      List.of(
          "Description_lang", "Display3_lang", "Display2_lang", "Display1_lang", "Display_lang");

  public record Limits(
      int maxMetadataBytes,
      int maxSuffixBytes,
      int maxPhysicalRows,
      int maxCopyDeclarations,
      long maxRecordAreaBytes,
      int maxRecordBytes,
      int maxStringBytes,
      int maxPrefixBytes,
      long maxProjectedUtf8Bytes) {
    public Limits {
      if (maxMetadataBytes < 0
          || maxMetadataBytes > 131072
          || maxSuffixBytes < 0
          || maxSuffixBytes > 4 * 1024 * 1024
          || maxPhysicalRows < 0
          || maxPhysicalRows > 200000
          || maxCopyDeclarations < 0
          || maxCopyDeclarations > 1000
          || maxRecordAreaBytes < 0
          || maxRecordAreaBytes > 64L * 1024 * 1024
          || maxRecordBytes < 0
          || maxRecordBytes > 65535
          || maxStringBytes < 0
          || maxStringBytes > 65535
          || maxPrefixBytes < 0
          || maxPrefixBytes > 65535
          || maxProjectedUtf8Bytes < 0
          || maxProjectedUtf8Bytes > 64L * 1024 * 1024)
        throw new IllegalArgumentException("Invalid bounded Sparse name projection limits");
    }
  }

  /** Privately constructed and immutable; suffix input and record body are never retained. */
  public static final class Prepared {
    private final long[] ids, offsets;
    private final int[] sizes;
    private final Limits limits;
    private final long recordAreaBytes, copies;
    private final int excludedSections;
    private final SelectedSchema.Provenance provenance;
    private final SelectedSchema.Field candidate;

    private Prepared(
        long[] ids,
        long[] offsets,
        int[] sizes,
        Limits limits,
        long area,
        long copies,
        int excluded,
        SelectedSchema.Provenance provenance,
        SelectedSchema.Field candidate) {
      this.ids = ids;
      this.offsets = offsets;
      this.sizes = sizes;
      this.limits = limits;
      recordAreaBytes = area;
      this.copies = copies;
      excludedSections = excluded;
      this.provenance = provenance;
      this.candidate = candidate;
    }

    public SelectedSchema.Provenance provenance() {
      return provenance;
    }

    public SelectedSchema.Field candidateField() {
      return candidate;
    }

    public int sectionOrdinal() {
      return 0;
    }

    public String projectionRevision() {
      return PROJECTION_REVISION;
    }
  }

  public record Row(
      long recordId,
      int physicalOrdinal,
      int sectionOrdinal,
      long decodedOffset,
      int recordBytes,
      String displayCandidate,
      int prefixBytes) {}

  /** Complete only for this physical prefix projection, never for a table/source or copy rows. */
  public record Completion(
      long physicalRows,
      long unindexedCopyDeclarations,
      int excludedSections,
      int sectionOrdinal,
      long recordAreaBytes,
      long projectedUtf8Bytes,
      String projectionRevision,
      SelectedSchema.Provenance provenance,
      SelectedSchema.Field candidateField) {}

  @FunctionalInterface
  public interface RowSink {
    void accept(Row provisionalRow);
  }

  public Prepared prepare(
      Wdc5Metadata metadata,
      SelectedSchema schema,
      byte[] suffix,
      int sectionOrdinal,
      Limits limits,
      BooleanSupplier cancelled)
      throws SparseNameProjectionException {
    Objects.requireNonNull(metadata);
    Objects.requireNonNull(schema);
    Objects.requireNonNull(suffix);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    var h = metadata.header();
    var selection = schema.selection();
    var provenance = schema.provenance();
    if (schema.status() != SelectedSchema.Status.MATCH
        || selection == null
        || provenance == null
        || !selection.product().equals("wow")
        || !selection.build().equals("12.1.5.70448")
        || !selection.tableName().equals("ItemSparse")
        || selection.tableHash() != 0x919be54eL
        || selection.layoutHash() != 0x1c17d17fL
        || selection.physicalFieldCount() != 67
        || !selection.sparse()
        || !selection.noninlineId()
        || !provenance.provider().equals("WoWDBDefs")
        || !provenance.revision().equals(SCHEMA_REVISION)
        || !provenance.sha256().equals(SCHEMA_SHA256)
        || provenance.modified()
        || h.schemaVersion() != 5
        || h.tableHash() != selection.tableHash()
        || h.layoutHash() != selection.layoutHash()
        || h.flags() != 5
        || h.fieldCount() != 67
        || h.totalFieldCount() != 67
        || h.recordSize() != 328
        || h.lookupColumnCount() != 0
        || h.commonDataSize() != 0
        || h.palletDataSize() != 0
        || h.fieldStorageInfoSize() != 1608
        || h.idFieldIndex() != 0
        || h.packedDataOffset() != 328
        || h.stringTableSize() != 0)
      throw error(INCOMPATIBLE, "Requires exact reviewed sparse Item name schema/header cohort");
    if (sectionOrdinal != 0
        || metadata.sections().isEmpty()
        || h.sectionCount() != metadata.sections().size()
        || metadata.fields().size() != 67
        || metadata.storage().size() != 67)
      throw error(UNSUPPORTED, "Requires first admitted section and complete exact descriptors");
    if (metadata.metadataBytesRead() != 204L + metadata.sections().size() * 40L + 67L * 28
        || metadata.declaredAuxiliaryDataEnd() != metadata.metadataBytesRead())
      throw error(MALFORMED, "Sparse metadata geometry is inconsistent");
    if (metadata.metadataBytesRead() > limits.maxMetadataBytes()
        || suffix.length > limits.maxSuffixBytes())
      throw error(LIMIT, "Metadata/suffix exceeds explicit bounds");
    for (var storage : metadata.storage()) {
      check(cancelled);
      if (storage.rawKind() != 0
          || storage.kind().orElse(null) != Wdc5Metadata.StorageKind.NONE
          || storage.additionalDataSize() != 0)
        throw error(
            UNSUPPORTED,
            "Requires observed sparse NONE storage descriptors without auxiliary data");
    }
    var fields = schema.fields();
    var inline = fields.stream().filter(f -> !f.noninline()).toList();
    var ids = fields.stream().filter(SelectedSchema.Field::id).toList();
    if (inline.size() != 67
        || ids.size() != 1
        || !ids.getFirst().noninline()
        || ids.getFirst().type() != SelectedSchema.Type.INT
        || ids.getFirst().integerBits() != 32
        || ids.getFirst().arrayLength() != 1
        || !ids.getFirst().name().equals("ID")
        || ids.getFirst().unverified())
      throw error(INCOMPATIBLE, "Requires noninline primary ID and exact inline field count");
    var names = new HashSet<String>();
    for (var field : fields) {
      check(cancelled);
      if (!names.add(field.name())) throw error(INCOMPATIBLE, "Duplicate schema field binding");
    }
    for (int i = 0; i < 5; i++) {
      var field = inline.get(i);
      if (!field.name().equals(STRINGS.get(i))
          || field.type() != SelectedSchema.Type.LOCSTRING
          || field.unverified()
          || field.arrayLength() != 1
          || field.noninline()
          || field.id()
          || field.relation().isPresent()
          || !field.annotations().isEmpty())
        throw error(INCOMPATIBLE, "Five ordered localized string bindings are required");
    }
    var section = metadata.sections().getFirst();
    if (section.fileOffset() < metadata.declaredAuxiliaryDataEnd()
        || section.stringTableSize() != 0)
      throw error(
          UNSUPPORTED,
          "Requires inline sparse record area after metadata and no external string table");
    long area = section.offsetRecordsEndOffset() - section.fileOffset();
    if (area < 0) throw error(MALFORMED, "Sparse record area is reversed");
    if (area > limits.maxRecordAreaBytes()
        || section.recordCount() > limits.maxPhysicalRows()
        || section.copyTableCount() > limits.maxCopyDeclarations())
      throw error(LIMIT, "Sparse admitted area/count exceeds explicit bounds");
    try {
      var raw =
          new SparseSectionIndexParser()
              .parse(
                  suffix,
                  h,
                  section,
                  new SparseSectionIndexParser.Limits(
                      limits.maxSuffixBytes(),
                      limits.maxPhysicalRows(),
                      limits.maxCopyDeclarations(),
                      limits.maxPhysicalRows()),
                  cancelled);
      // This independently validates absolute map origin, contiguity, strict IDs/map IDs and the
      // narrow direct-physical-source copy shape. Its copy lookup is deliberately never invoked.
      ValidatedSparseSection.prepare(
          raw,
          section,
          new ValidatedSparseSection.Limits(
              limits.maxPhysicalRows(), limits.maxCopyDeclarations(), limits.maxRecordAreaBytes()),
          cancelled);
      var recordIds = new long[raw.recordIds().size()];
      var offsets = new long[recordIds.length];
      var sizes = new int[recordIds.length];
      for (int i = 0; i < recordIds.length; i++) {
        check(cancelled);
        var map = raw.mapEntries().get(i);
        if (map.size() > limits.maxRecordBytes())
          throw error(LIMIT, "Physical record exceeds explicit per-record bound");
        recordIds[i] = raw.recordIds().get(i);
        offsets[i] = map.rawOffset();
        sizes[i] = map.size();
      }
      check(cancelled);
      return new Prepared(
          recordIds,
          offsets,
          sizes,
          limits,
          area,
          raw.copies().size(),
          metadata.sections().size() - 1,
          provenance,
          inline.get(4));
    } catch (Db2HeaderException e) {
      throw new SparseNameProjectionException(map(e.reason().name()), e.getMessage(), e);
    } catch (SparseRecordException e) {
      throw new SparseNameProjectionException(map(e.reason().name()), e.getMessage(), e);
    }
  }

  /**
   * Caller owns a stream of exactly the admitted record-area window, positioned at its beginning.
   * EOF means that window ends, not that the decoded table/file is complete. The stream is never
   * closed here. Cancellation is cooperative between reads; callers bound/cancel blocking I/O.
   * Every callback remains provisional until this method returns Completion successfully.
   */
  public Completion project(
      Prepared prepared, InputStream recordArea, RowSink sink, BooleanSupplier cancelled)
      throws SparseNameProjectionException {
    Objects.requireNonNull(prepared);
    Objects.requireNonNull(recordArea);
    Objects.requireNonNull(sink);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    long textBytes = 0;
    var limits = prepared.limits;
    var reader = new CStringPrefixReader();
    var stringLimits =
        new CStringPrefixReader.Limits(
            limits.maxRecordBytes(), 5, limits.maxStringBytes(), limits.maxPrefixBytes());
    try {
      for (int i = 0; i < prepared.ids.length; i++) {
        check(cancelled);
        byte[] record = new byte[prepared.sizes[i]];
        int cursor = 0;
        while (cursor < record.length) {
          check(cancelled);
          int count = recordArea.read(record, cursor, record.length - cursor);
          if (count < 0) throw error(MALFORMED, "Admitted record window is truncated");
          if (count == 0) {
            check(cancelled);
            int value = recordArea.read();
            if (value < 0) throw error(MALFORMED, "Admitted record window is truncated");
            record[cursor++] = (byte) value;
          } else cursor += count;
        }
        check(cancelled);
        var prefix = reader.read(record, 5, stringLimits, cancelled);
        String display = prefix.strings().get(4);
        long bytes = display.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxProjectedUtf8Bytes() - textBytes)
          throw error(LIMIT, "Aggregate projected UTF-8 bytes exceed explicit bound");
        textBytes += bytes;
        check(cancelled);
        sink.accept(
            new Row(
                prepared.ids[i],
                i,
                0,
                prepared.offsets[i],
                record.length,
                display,
                prefix.bytesConsumed()));
      }
      check(cancelled);
      if (recordArea.read() != -1)
        throw error(MALFORMED, "Admitted record window has trailing bytes");
      check(cancelled);
      return new Completion(
          prepared.ids.length,
          prepared.copies,
          prepared.excludedSections,
          0,
          prepared.recordAreaBytes,
          textBytes,
          PROJECTION_REVISION,
          prepared.provenance,
          prepared.candidate);
    } catch (SparseRecordException e) {
      throw new SparseNameProjectionException(map(e.reason().name()), e.getMessage(), e);
    } catch (IOException e) {
      throw new SparseNameProjectionException(IO_FAILURE, "Cannot read admitted record window", e);
    }
  }

  private static SparseNameProjectionException.Reason map(String reason) {
    return switch (reason) {
      case "CANCELLED" -> CANCELLED;
      case "LIMIT" -> LIMIT;
      case "UNSUPPORTED" -> UNSUPPORTED;
      default -> MALFORMED;
    };
  }

  private static void check(BooleanSupplier cancelled) throws SparseNameProjectionException {
    if (cancelled.getAsBoolean()) throw error(CANCELLED, "Sparse name projection cancelled");
  }

  private static SparseNameProjectionException error(
      SparseNameProjectionException.Reason reason, String message) {
    return new SparseNameProjectionException(reason, message);
  }
}
