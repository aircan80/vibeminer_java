package io.vibeminer.infrastructure.content;

import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.selectedfile.SelectedFileRecords;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/** Bounded typed ledger codec. Existing VMER records remain byte-for-byte compatible. */
public final class CanonicalContentRecords {
  public static final int MAX_BYTES = 65536;

  private CanonicalContentRecords() {}

  public static byte[] encode(CanonicalContentProof proof) {
    Objects.requireNonNull(proof);
    if (proof instanceof EncodedContentProof encoded)
      return SelectedFileRecords.representation(encoded.receipt());
    var metadata = (MetadataContentProof) proof;
    try {
      var buffer = new ByteArrayOutputStream();
      var out = new DataOutputStream(buffer);
      text(out, "VMMP1");
      var alias = metadata.originalAlias();
      text(out, alias.source().namespace());
      text(out, alias.source().immutableSourceKey());
      text(out, alias.source().verificationSemantics());
      text(out, alias.object().digest().value());
      out.writeLong(alias.object().length());
      text(out, alias.evidence().verifierSemantics());
      text(out, alias.evidence().sourceUrl());
      text(out, alias.evidence().verifiedAt().toString());
      text(out, metadata.actualEncodingKey());
      text(out, metadata.declaredContentKey());
      out.writeBoolean(metadata.declaredDecodedBytes().isPresent());
      if (metadata.declaredDecodedBytes().isPresent())
        out.writeLong(metadata.declaredDecodedBytes().getAsLong());
      out.writeBoolean(metadata.decodedAttestation().isPresent());
      if (metadata.decodedAttestation().isPresent()) {
        var value = metadata.decodedAttestation().get();
        text(out, value.contentKey());
        out.writeLong(value.actualDecodedBytes());
        text(out, value.verifierRevision());
      }
      out.writeBoolean(metadata.structuralAttestation().isPresent());
      if (metadata.structuralAttestation().isPresent()) {
        var value = metadata.structuralAttestation().get();
        text(out, value.kind().name());
        text(out, value.parserRevision());
      }
      if (buffer.size() > MAX_BYTES) throw invalid("Oversized canonical proof", null);
      return buffer.toByteArray();
    } catch (IOException failure) {
      throw invalid("Cannot encode canonical proof", failure);
    }
  }

  public static CanonicalContentProof decode(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_BYTES)
      throw invalid("Missing or oversized canonical proof", null);
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      var version = text(in);
      if (version.equals("VMER1") || version.equals("VMER2"))
        return new EncodedContentProof(SelectedFileRecords.representation(bytes));
      if (!version.equals("VMMP1")) throw invalid("Unknown canonical proof version", null);
      var source = new ResolvedSourceIdentity(text(in), text(in), text(in));
      var object = new StoredObject(new ObjectDigest(text(in)), in.readLong());
      var evidence = new SourceVerificationEvidence(text(in), text(in), Instant.parse(text(in)));
      var alias = new VerifiedSourceAlias(source, object, evidence);
      var ekey = text(in);
      var ckey = text(in);
      var length = in.readBoolean() ? OptionalLong.of(in.readLong()) : OptionalLong.empty();
      Optional<DecodedContentAttestation> decoded =
          in.readBoolean()
              ? Optional.of(new DecodedContentAttestation(text(in), in.readLong(), text(in)))
              : Optional.empty();
      Optional<MetadataStructureAttestation> structure =
          in.readBoolean()
              ? Optional.of(
                  new MetadataStructureAttestation(
                      MetadataStructureAttestation.Kind.valueOf(text(in)), text(in)))
              : Optional.empty();
      var value = new MetadataContentProof(alias, ekey, ckey, length, decoded, structure);
      if (in.read() != -1) throw invalid("Trailing canonical proof bytes", null);
      return value;
    } catch (IOException | RuntimeException failure) {
      throw invalid("Invalid canonical proof", failure);
    }
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    if (!StandardCharsets.UTF_8.newEncoder().canEncode(value))
      throw invalid("Canonical proof text must be lossless UTF8", null);
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 16384) throw invalid("Oversized canonical proof field", null);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String text(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 0 || size > 16384 || size > in.available())
      throw invalid("Invalid canonical field length", null);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(in.readNBytes(size)))
        .toString();
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
