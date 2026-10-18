package io.vibeminer.infrastructure.item;

import io.vibeminer.application.itemnames.ItemNamesException;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.itemnames.SparseNameProjectionSourceReader.CopyDeclaration;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Strict bounded derived metadata; decoding authenticates neither source bytes nor readiness. */
final class SparseNameRecords {
  static final int MAX_DEFINITION_BYTES = 262144;
  static final int MAX_CANDIDATE_BYTES = 65600;
  static final int MAX_EVIDENCE_BYTES = ItemProjectionRecords.MAX_RECORD_BYTES;
  static final int CANDIDATE_VERSION = 0x564d4e01;
  static final int COPY_VERSION = 0x564d4e02;

  private SparseNameRecords() {}

  static byte[] definition(SparseNameArtifactDefinition value) {
    return encode(
        MAX_DEFINITION_BYTES,
        out -> {
          out.writeInt(0x564d5301);
          var source = value.sources().getFirst();
          text(out, source.digest().value(), 64);
          out.writeLong(source.length());
          var i = value.interpretation();
          text(out, i.decoderRevision(), 16384);
          text(out, i.indexRevision(), 16384);
          out.writeLong(i.tableHash());
          out.writeLong(i.layoutHash());
          out.writeInt(i.sectionOrdinal());
          out.writeLong(i.metadataPrefixBytes());
          out.writeLong(i.recordAreaOffset());
          out.writeLong(i.recordAreaBytes());
          out.writeLong(i.suffixOffset());
          out.writeLong(i.suffixBytes());
          var s = i.schema();
          for (var text :
              List.of(
                  s.provider(),
                  s.revision(),
                  s.definitionPath(),
                  s.definitionSha256(),
                  s.selectionDigest(),
                  s.license(),
                  s.attribution())) text(out, text, 16384);
          out.writeInt(s.prefixFields().size());
          for (var field : s.prefixFields()) text(out, field, 16384);
          text(out, i.locale(), 16384);
          text(out, i.optionsRevision(), 16384);
          text(out, i.proofSemantics(), 16384);
        });
  }

  static SparseNameArtifactDefinition definition(byte[] bytes) {
    return decode(
        bytes,
        MAX_DEFINITION_BYTES,
        in -> {
          version(in, 0x564d5301);
          var source = new StoredObject(new ObjectDigest(text(in, 64)), in.readLong());
          String decoder = text(in, 16384), index = text(in, 16384);
          long table = in.readLong(), layout = in.readLong();
          int section = in.readInt();
          long metadata = in.readLong(),
              offset = in.readLong(),
              area = in.readLong(),
              suffix = in.readLong(),
              suffixBytes = in.readLong();
          String provider = text(in, 16384),
              revision = text(in, 16384),
              path = text(in, 16384),
              sha = text(in, 16384),
              selection = text(in, 16384),
              license = text(in, 16384),
              attribution = text(in, 16384);
          if (in.readInt() != 5) throw corrupt("Incorrect string-prefix binding count", null);
          var fields = new ArrayList<String>();
          for (int n = 0; n < 5; n++) fields.add(text(in, 16384));
          var schema =
              new SparseNameArtifactDefinition.Schema(
                  provider, revision, path, sha, selection, license, attribution, fields);
          return new SparseNameArtifactDefinition(
              List.of(source),
              new SparseNameArtifactDefinition.Interpretation(
                  decoder,
                  index,
                  table,
                  layout,
                  section,
                  metadata,
                  offset,
                  area,
                  suffix,
                  suffixBytes,
                  schema,
                  text(in, 16384),
                  text(in, 16384),
                  text(in, 16384)));
        });
  }

  static byte[] candidate(DisplayCandidate value) {
    return encode(
        MAX_CANDIDATE_BYTES,
        out -> {
          out.writeInt(CANDIDATE_VERSION);
          out.writeLong(value.physicalRecordId());
          out.writeInt(value.physicalOrdinal());
          out.writeInt(value.sectionOrdinal());
          out.writeLong(value.decodedOffset());
          out.writeInt(value.recordBytes());
          out.writeInt(value.prefixBytes());
          text(out, value.value(), 65530);
        });
  }

  static DisplayCandidate candidate(byte[] bytes) {
    return decode(
        bytes,
        MAX_CANDIDATE_BYTES,
        in -> {
          version(in, CANDIDATE_VERSION);
          long id = in.readLong();
          int ordinal = in.readInt(), section = in.readInt();
          long offset = in.readLong();
          int record = in.readInt(), prefix = in.readInt();
          return new DisplayCandidate(
              text(in, 65530), id, ordinal, section, offset, record, prefix);
        });
  }

  static byte[] copy(CopyDeclaration value) {
    return ByteBuffer.allocate(20)
        .putInt(COPY_VERSION)
        .putLong(value.targetId())
        .putLong(value.sourceId())
        .array();
  }

  static CopyDeclaration copy(byte[] bytes) {
    return decode(
        bytes,
        20,
        in -> {
          version(in, COPY_VERSION);
          return new CopyDeclaration(in.readLong(), in.readLong());
        });
  }

  static byte[] evidence(ItemProjectionEvidence value) {
    try {
      var encoded = ItemProjectionRecords.evidence(value);
      var restored =
          ItemProjectionRecords.evidence(
              encoded, value.association().descriptor().association().build());
      if (!restored.equals(value))
        throw corrupt("Names evidence cannot be encoded without loss", null);
      return encoded;
    } catch (io.vibeminer.application.item.ItemProjectionException failure) {
      throw corrupt("Invalid names evidence", failure);
    }
  }

  static ItemProjectionEvidence evidence(byte[] bytes, BuildSnapshot build) {
    try {
      return ItemProjectionRecords.evidence(bytes, build);
    } catch (io.vibeminer.application.item.ItemProjectionException failure) {
      throw corrupt("Invalid stored names evidence", failure);
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void text(DataOutputStream out, String value, int maximum) throws IOException {
    byte[] bytes;
    try {
      var buffer =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
    } catch (CharacterCodingException invalid) {
      throw corrupt("Text is not strict UTF-8", invalid);
    }
    if (bytes.length > maximum) throw corrupt("Metadata string exceeds bound", null);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String text(DataInputStream in, int maximum) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > maximum || count > in.available())
      throw corrupt("Invalid bounded UTF-8 string length", null);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(in.readNBytes(count)))
        .toString();
  }

  private static void version(DataInputStream in, int version) throws IOException {
    if (in.readInt() != version) throw corrupt("Unsupported names metadata version", null);
  }

  private static byte[] encode(int maximum, Writer writer) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        writer.write(out);
      }
      if (bytes.size() > maximum) throw corrupt("Names metadata exceeds bound", null);
      return bytes.toByteArray();
    } catch (IOException | IllegalArgumentException failure) {
      throw corrupt("Invalid names metadata", failure);
    }
  }

  private static <T> T decode(byte[] bytes, int maximum, Reader<T> reader) {
    if (bytes == null || bytes.length > maximum)
      throw corrupt("Invalid names metadata length", null);
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      T result = reader.read(in);
      if (in.available() != 0) throw corrupt("Trailing names metadata", null);
      return result;
    } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
      throw corrupt("Invalid names metadata", failure);
    }
  }

  private static ItemNamesException corrupt(String message, Throwable cause) {
    return new ItemNamesException(ItemNamesException.Code.CORRUPT, message, cause);
  }

  @FunctionalInterface
  private interface Writer {
    void write(DataOutputStream out) throws IOException;
  }

  @FunctionalInterface
  private interface Reader<T> {
    T read(DataInputStream in) throws IOException;
  }
}
