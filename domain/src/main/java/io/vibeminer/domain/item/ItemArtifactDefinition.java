package io.vibeminer.domain.item;

import io.vibeminer.domain.StoredObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Shared derivation identity. Consumer builds, association receipts and timestamps are absent. */
public record ItemArtifactDefinition(List<StoredObject> sources, Interpretation interpretation) {
  public enum Field {
    CLASS_ID,
    SUBCLASS_ID,
    INVENTORY_TYPE,
    ICON_FILE_DATA_ID
  }

  public enum Sign {
    SIGNED,
    UNSIGNED
  }

  public record FieldBinding(Field field, int physicalFieldOrdinal, int candidateBits, Sign sign) {
    public FieldBinding {
      Objects.requireNonNull(field);
      Objects.requireNonNull(sign);
      if (physicalFieldOrdinal < 0
          || physicalFieldOrdinal > 65535
          || candidateBits < 1
          || candidateBits > 64) throw new IllegalArgumentException("Invalid field binding");
    }
  }

  public record Schema(
      String provider,
      String revision,
      String definitionPath,
      String definitionSha256,
      String selectionDigest,
      String license,
      String attribution,
      List<FieldBinding> fields) {
    public Schema {
      provider = ItemValues.text(provider);
      revision = ItemValues.text(revision);
      definitionPath = ItemValues.text(definitionPath);
      definitionSha256 = ItemValues.digest(definitionSha256);
      selectionDigest = ItemValues.digest(selectionDigest);
      license = ItemValues.text(license);
      attribution = ItemValues.text(attribution);
      fields = List.copyOf(fields);
      if (fields.size() != 4)
        throw new IllegalArgumentException("Exactly four candidate fields required");
      var ordinals = new HashSet<Integer>();
      for (int i = 0; i < 4; i++)
        if (fields.get(i).field() != Field.values()[i]
            || !ordinals.add(fields.get(i).physicalFieldOrdinal()))
          throw new IllegalArgumentException(
              "Candidate bindings must be distinct and in fixed field order");
    }
  }

  public record Interpretation(
      String decoderRevision,
      String indexRevision,
      long tableHash,
      long layoutHash,
      int sectionOrdinal,
      long sectionDecodedOffset,
      long sectionDecodedBytes,
      Schema schema,
      String locale,
      String optionsRevision,
      String proofSemantics) {
    public Interpretation {
      decoderRevision = ItemValues.text(decoderRevision);
      indexRevision = ItemValues.text(indexRevision);
      ItemValues.id(tableHash);
      ItemValues.id(layoutHash);
      if (sectionOrdinal < 0) throw new IllegalArgumentException("Nonnegative section required");
      ItemValues.extent(sectionDecodedOffset, sectionDecodedBytes);
      Objects.requireNonNull(schema);
      locale = ItemValues.locale(locale);
      optionsRevision = ItemValues.text(optionsRevision);
      proofSemantics = ItemValues.text(proofSemantics);
    }
  }

  public ItemArtifactDefinition {
    sources = List.copyOf(sources);
    Objects.requireNonNull(interpretation);
    if (sources.isEmpty()
        || sources.size() > 8
        || sources.stream().anyMatch(source -> source.length() < 1))
      throw new IllegalArgumentException("Bounded complete source inputs required");
  }

  public String artifactId() {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      text(out, "item-artifact-v1");
      out.writeInt(sources.size());
      for (var source : sources) {
        text(out, source.digest().value());
        out.writeLong(source.length());
      }
      var i = interpretation;
      text(out, i.decoderRevision());
      text(out, i.indexRevision());
      out.writeLong(i.tableHash());
      out.writeLong(i.layoutHash());
      out.writeInt(i.sectionOrdinal());
      out.writeLong(i.sectionDecodedOffset());
      out.writeLong(i.sectionDecodedBytes());
      var s = i.schema();
      text(out, s.provider());
      text(out, s.revision());
      text(out, s.definitionPath());
      text(out, s.definitionSha256());
      text(out, s.selectionDigest());
      text(out, s.license());
      text(out, s.attribution());
      for (var field : s.fields()) {
        text(out, field.field().name());
        out.writeInt(field.physicalFieldOrdinal());
        out.writeInt(field.candidateBits());
        text(out, field.sign().name());
      }
      text(out, i.locale());
      text(out, i.optionsRevision());
      text(out, i.proofSemantics());
      out.flush();
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }
}
