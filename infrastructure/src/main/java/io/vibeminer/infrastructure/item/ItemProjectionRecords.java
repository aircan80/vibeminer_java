package io.vibeminer.infrastructure.item;

import io.vibeminer.application.item.ItemProjectionException;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.DecodedContentAttestation;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.selectedfile.SelectedFileRecords;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/**
 * Versioned, bounded Item metadata records. No game payload or Java serialization is accepted.
 * Artifact identity is always derived from the decoded definition, never read as a trusted key.
 * Decoding reconstructs ledger facts; it does not verify source bytes or publish an artifact.
 */
public final class ItemProjectionRecords {
  public static final int MAX_RECORD_BYTES = 2 * 1024 * 1024;
  public static final int MAX_ROW_BYTES = 1024;
  public static final int MAX_DEFINITION_BYTES = 262144;
  private static final int MAX_TEXT_BYTES = 16384;
  private static final int MAX_ASSOCIATION_BYTES = 65536;

  private ItemProjectionRecords() {}

  public static byte[] definition(ItemArtifactDefinition value) {
    Objects.requireNonNull(value);
    return encode(
        MAX_DEFINITION_BYTES,
        out -> {
          text(out, "VMID1");
          out.writeInt(value.sources().size());
          for (var source : value.sources()) object(out, source);
          var interpretation = value.interpretation();
          text(out, interpretation.decoderRevision());
          text(out, interpretation.indexRevision());
          out.writeLong(interpretation.tableHash());
          out.writeLong(interpretation.layoutHash());
          out.writeInt(interpretation.sectionOrdinal());
          out.writeLong(interpretation.sectionDecodedOffset());
          out.writeLong(interpretation.sectionDecodedBytes());
          var schema = interpretation.schema();
          text(out, schema.provider());
          text(out, schema.revision());
          text(out, schema.definitionPath());
          text(out, schema.definitionSha256());
          text(out, schema.selectionDigest());
          text(out, schema.license());
          text(out, schema.attribution());
          out.writeInt(schema.fields().size());
          for (var field : schema.fields()) {
            text(out, field.field().name());
            out.writeInt(field.physicalFieldOrdinal());
            out.writeInt(field.candidateBits());
            text(out, field.sign().name());
          }
          text(out, interpretation.locale());
          text(out, interpretation.optionsRevision());
          text(out, interpretation.proofSemantics());
        });
  }

  public static ItemArtifactDefinition definition(byte[] bytes) {
    return decode(
        bytes,
        MAX_DEFINITION_BYTES,
        in -> {
          version(in, "VMID1");
          int count = count(in, 1, 8);
          var sources = new ArrayList<StoredObject>(count);
          for (int i = 0; i < count; i++) sources.add(object(in));
          String decoder = text(in), index = text(in);
          long table = in.readLong(), layout = in.readLong();
          int section = in.readInt();
          long offset = in.readLong(), length = in.readLong();
          String provider = text(in),
              revision = text(in),
              path = text(in),
              digest = text(in),
              selection = text(in),
              license = text(in),
              attribution = text(in);
          int fieldCount = count(in, 4, 4);
          var fields = new ArrayList<ItemArtifactDefinition.FieldBinding>(fieldCount);
          for (int i = 0; i < fieldCount; i++)
            fields.add(
                new ItemArtifactDefinition.FieldBinding(
                    ItemArtifactDefinition.Field.valueOf(text(in)),
                    in.readInt(),
                    in.readInt(),
                    ItemArtifactDefinition.Sign.valueOf(text(in))));
          var schema =
              new ItemArtifactDefinition.Schema(
                  provider, revision, path, digest, selection, license, attribution, fields);
          return new ItemArtifactDefinition(
              sources,
              new ItemArtifactDefinition.Interpretation(
                  decoder, index, table, layout, section, offset, length, schema, text(in),
                  text(in), text(in)));
        });
  }

  public static byte[] evidence(ItemProjectionEvidence value) {
    Objects.requireNonNull(value);
    return encode(
        MAX_RECORD_BYTES,
        out -> {
          text(out, "VMIE1");
          var original = value.association();
          byte[] representation = SelectedFileRecords.representation(original.representation());
          byte[] association = SelectedFileRecords.association(original);
          // Delegated legacy codecs must not normalize a supplied immutable provenance fact.
          var restored =
              SelectedFileRecords.association(
                  association,
                  original.descriptor().association().build(),
                  SelectedFileRecords.representation(representation));
          if (!original.equals(restored))
            throw corrupt("Nested Item source evidence did not round-trip exactly", null);
          nested(out, representation);
          nested(out, association);
          var local = value.localVerification();
          object(out, local.object());
          text(out, local.verifiedAt().toString());
          text(out, local.verifierRevision());
          out.writeInt(value.chunks().size());
          for (var chunk : value.chunks()) {
            out.writeInt(chunk.ordinal());
            out.writeLong(chunk.encodedOffset());
            out.writeLong(chunk.encodedBytes());
            text(out, chunk.encodedMd5());
            out.writeLong(chunk.decodedOffset());
            out.writeLong(chunk.decodedBytes());
            text(out, chunk.verifierRevision());
          }
          out.writeInt(value.windows().size());
          for (var window : value.windows()) {
            out.writeLong(window.offset());
            out.writeLong(window.length());
          }
          out.writeBoolean(value.fullDecoded().isPresent());
          if (value.fullDecoded().isPresent()) {
            var full = value.fullDecoded().get();
            text(out, full.contentKey());
            out.writeLong(full.actualDecodedBytes());
            text(out, full.verifierRevision());
          }
        });
  }

  /** The supplied catalog snapshot reconstructs the exact stored consumer association. */
  public static ItemProjectionEvidence evidence(byte[] bytes, BuildSnapshot exactStoredBuild) {
    return decode(
        bytes,
        MAX_RECORD_BYTES,
        in -> {
          Objects.requireNonNull(exactStoredBuild, "Exact stored snapshot required");
          version(in, "VMIE1");
          var representation = SelectedFileRecords.representation(nested(in));
          var association =
              SelectedFileRecords.association(nested(in), exactStoredBuild, representation);
          var local =
              new ItemProjectionEvidence.LocalVerification(
                  object(in), Instant.parse(text(in)), text(in));
          int chunkCount = count(in, 1, 4096);
          var chunks = new ArrayList<ItemProjectionEvidence.ChunkAttestation>(chunkCount);
          for (int i = 0; i < chunkCount; i++)
            chunks.add(
                new ItemProjectionEvidence.ChunkAttestation(
                    in.readInt(),
                    in.readLong(),
                    in.readLong(),
                    text(in),
                    in.readLong(),
                    in.readLong(),
                    text(in)));
          int windowCount = count(in, 1, 64);
          var windows = new ArrayList<ItemProjectionEvidence.DecodedWindow>(windowCount);
          for (int i = 0; i < windowCount; i++)
            windows.add(new ItemProjectionEvidence.DecodedWindow(in.readLong(), in.readLong()));
          Optional<DecodedContentAttestation> full =
              bool(in)
                  ? Optional.of(new DecodedContentAttestation(text(in), in.readLong(), text(in)))
                  : Optional.empty();
          return new ItemProjectionEvidence(association, local, chunks, windows, full);
        });
  }

  public static byte[] row(ItemPhysicalRow value) {
    Objects.requireNonNull(value);
    return encode(
        MAX_ROW_BYTES,
        out -> {
          text(out, "VMIR1");
          out.writeLong(value.recordId());
          out.writeInt(value.physicalOrdinal());
          out.writeInt(value.sectionOrdinal());
          out.writeInt(value.values().size());
          for (var field : value.values()) {
            text(out, field.field().name());
            out.writeLong(field.rawBits());
            out.writeInt(field.rawWidth());
            text(out, field.physicalInterpretation().name());
            out.writeLong(field.candidateValue());
            out.writeInt(field.candidateBits());
            text(out, field.candidateSign().name());
          }
        });
  }

  public static ItemPhysicalRow row(byte[] bytes) {
    return decode(
        bytes,
        MAX_ROW_BYTES,
        in -> {
          version(in, "VMIR1");
          long id = in.readLong();
          int ordinal = in.readInt(), section = in.readInt();
          int count = count(in, 4, 4);
          var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>(count);
          for (int i = 0; i < count; i++)
            values.add(
                new ItemPhysicalRow.IntegerCandidate(
                    ItemArtifactDefinition.Field.valueOf(text(in)),
                    in.readLong(),
                    in.readInt(),
                    ItemPhysicalRow.PhysicalInterpretation.valueOf(text(in)),
                    in.readLong(),
                    in.readInt(),
                    ItemArtifactDefinition.Sign.valueOf(text(in))));
          return new ItemPhysicalRow(id, ordinal, section, values);
        });
  }

  @FunctionalInterface
  private interface Writer {
    void write(DataOutputStream out) throws IOException;
  }

  @FunctionalInterface
  private interface Reader<T> {
    T read(DataInputStream in) throws IOException;
  }

  private static byte[] encode(int limit, Writer writer) {
    try {
      var bytes = new BoundedBytes(limit);
      writer.write(new DataOutputStream(bytes));
      return bytes.toByteArray();
    } catch (IOException failure) {
      throw new ItemProjectionException(
          ItemProjectionException.Code.CORRUPT, "Cannot encode Item metadata", failure);
    } catch (RuntimeException failure) {
      if (failure instanceof ItemProjectionException typed) throw typed;
      throw corrupt("Cannot encode nested Item metadata", failure);
    }
  }

  private static <T> T decode(byte[] bytes, int limit, Reader<T> reader) {
    if (bytes == null || bytes.length > limit)
      throw corrupt("Missing or oversized Item metadata", null);
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      var value = reader.read(in);
      if (in.read() != -1) throw corrupt("Trailing Item metadata bytes", null);
      return value;
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof ItemProjectionException typed) throw typed;
      throw corrupt("Invalid Item metadata", failure);
    }
  }

  private static void version(DataInputStream in, String expected) throws IOException {
    if (!text(in).equals(expected)) throw corrupt("Unsupported Item metadata version", null);
  }

  private static int count(DataInputStream in, int minimum, int maximum) throws IOException {
    int count = in.readInt();
    if (count < minimum || count > maximum)
      throw corrupt("Invalid Item metadata collection count", null);
    return count;
  }

  private static boolean bool(DataInputStream in) throws IOException {
    int value = in.readUnsignedByte();
    if (value > 1) throw corrupt("Invalid optional Item metadata marker", null);
    return value == 1;
  }

  private static void object(DataOutputStream out, StoredObject object) throws IOException {
    text(out, object.digest().value());
    out.writeLong(object.length());
  }

  private static StoredObject object(DataInputStream in) throws IOException {
    return new StoredObject(new ObjectDigest(text(in)), in.readLong());
  }

  private static void nested(DataOutputStream out, byte[] value) throws IOException {
    if (value.length > MAX_ASSOCIATION_BYTES) throw limit("Nested Item evidence exceeds bound");
    out.writeInt(value.length);
    out.write(value);
  }

  private static byte[] nested(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 0 || length > MAX_ASSOCIATION_BYTES || length > in.available())
      throw corrupt("Invalid nested Item evidence length", null);
    return in.readNBytes(length);
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    var encoded =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value));
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    if (bytes.length > MAX_TEXT_BYTES) throw limit("Item metadata field exceeds bound");
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String text(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 0 || length > MAX_TEXT_BYTES || length > in.available())
      throw corrupt("Invalid Item metadata field length", null);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(in.readNBytes(length)))
        .toString();
  }

  private static final class BoundedBytes extends ByteArrayOutputStream {
    private final int limit;

    BoundedBytes(int limit) {
      this.limit = limit;
    }

    @Override
    public synchronized void write(int value) {
      if (count >= limit) throw limit("Item metadata record exceeds bound");
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length > limit - count) throw limit("Item metadata record exceeds bound");
      super.write(bytes, offset, length);
    }
  }

  private static ItemProjectionException corrupt(String message, Throwable cause) {
    return new ItemProjectionException(ItemProjectionException.Code.CORRUPT, message, cause);
  }

  private static ItemProjectionException limit(String message) {
    return new ItemProjectionException(ItemProjectionException.Code.LIMIT_EXCEEDED, message);
  }
}
