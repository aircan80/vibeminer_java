package io.vibeminer.domain.item;

import io.vibeminer.domain.StoredObject;
import java.io.*;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Shared physical name derivation identity, independent of Item companion/build observations. */
public record SparseNameArtifactDefinition(
    List<StoredObject> sources, Interpretation interpretation) {
  public static final List<String> PREFIX_FIELDS =
      List.of(
          "Description_lang", "Display3_lang", "Display2_lang", "Display1_lang", "Display_lang");

  public record Schema(
      String provider,
      String revision,
      String definitionPath,
      String definitionSha256,
      String selectionDigest,
      String license,
      String attribution,
      List<String> prefixFields) {
    public Schema {
      provider = strictText(provider);
      revision = strictText(revision);
      definitionPath = strictText(definitionPath);
      definitionSha256 = ItemValues.digest(definitionSha256);
      selectionDigest = ItemValues.digest(selectionDigest);
      license = strictText(license);
      attribution = strictText(attribution);
      prefixFields = List.copyOf(prefixFields);
      if (!prefixFields.equals(PREFIX_FIELDS))
        throw new IllegalArgumentException(
            "Exact five ordered localized-string prefix bindings required");
    }
  }

  public record Interpretation(
      String decoderRevision,
      String indexRevision,
      long tableHash,
      long layoutHash,
      int sectionOrdinal,
      long metadataPrefixBytes,
      long recordAreaOffset,
      long recordAreaBytes,
      long suffixOffset,
      long suffixBytes,
      Schema schema,
      String locale,
      String optionsRevision,
      String proofSemantics) {
    public Interpretation {
      decoderRevision = strictText(decoderRevision);
      indexRevision = strictText(indexRevision);
      ItemValues.id(tableHash);
      ItemValues.id(layoutHash);
      ItemValues.extent(0, metadataPrefixBytes);
      ItemValues.extent(recordAreaOffset, recordAreaBytes);
      ItemValues.extent(suffixOffset, suffixBytes);
      if (sectionOrdinal < 0
          || metadataPrefixBytes > recordAreaOffset
          || suffixOffset != recordAreaOffset + recordAreaBytes)
        throw new IllegalArgumentException("Invalid admitted name prefix/record/suffix geometry");
      Objects.requireNonNull(schema);
      locale = ItemValues.locale(locale);
      optionsRevision = strictText(optionsRevision);
      proofSemantics = strictText(proofSemantics);
    }
  }

  public SparseNameArtifactDefinition {
    sources = List.copyOf(sources);
    Objects.requireNonNull(interpretation);
    if (sources.size() != 1 || sources.getFirst().length() < 1)
      throw new IllegalArgumentException("One complete encoded physical name source required");
  }

  public String artifactId() {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      text(out, "sparse-name-artifact-v1");
      text(out, sources.getFirst().digest().value());
      out.writeLong(sources.getFirst().length());
      var i = interpretation;
      text(out, i.decoderRevision());
      text(out, i.indexRevision());
      out.writeLong(i.tableHash());
      out.writeLong(i.layoutHash());
      out.writeInt(i.sectionOrdinal());
      out.writeLong(i.metadataPrefixBytes());
      out.writeLong(i.recordAreaOffset());
      out.writeLong(i.recordAreaBytes());
      out.writeLong(i.suffixOffset());
      out.writeLong(i.suffixBytes());
      var s = i.schema();
      text(out, s.provider());
      text(out, s.revision());
      text(out, s.definitionPath());
      text(out, s.definitionSha256());
      text(out, s.selectionDigest());
      text(out, s.license());
      text(out, s.attribution());
      out.writeInt(s.prefixFields().size());
      for (var field : s.prefixFields()) text(out, field);
      text(out, i.locale());
      text(out, i.optionsRevision());
      text(out, i.proofSemantics());
      out.flush();
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String strictText(String value) {
    ItemValues.text(value);
    try {
      StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(value));
      return value;
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException(
          "Artifact identity text must be strict encodable UTF-8", invalid);
    }
  }
}
