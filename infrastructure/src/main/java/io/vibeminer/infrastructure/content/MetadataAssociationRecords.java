package io.vibeminer.infrastructure.content;

import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/** Bounded declarations and original receipts; decoding grants no verification capability. */
public final class MetadataAssociationRecords {
  public static final int MAX_BYTES = 262144;

  private MetadataAssociationRecords() {}

  public static byte[] encode(MetadataBuildAssociation value) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      text(out, "VMMA1");
      var declaration = value.declaration();
      text(out, declaration.build().snapshotId());
      alias(out, declaration.configuration());
      text(out, declaration.kind().name());
      text(out, declaration.slot().namespace());
      text(out, declaration.slot().contentKey());
      out.writeBoolean(declaration.preferredEncodingKey().isPresent());
      if (declaration.preferredEncodingKey().isPresent())
        text(out, declaration.preferredEncodingKey().get());
      size(out, declaration.declaredEncodedBytes());
      size(out, declaration.declaredDecodedBytes());
      text(out, value.canonicalReservationToken().toString());
      var proof = CanonicalContentRecords.encode(value.actualRepresentation());
      if (!CanonicalContentRecords.decode(proof).equals(value.actualRepresentation()))
        throw invalid(null);
      out.writeInt(proof.length);
      out.write(proof);
      text(out, value.associatedAt().toString());
      if (bytes.size() > MAX_BYTES) throw invalid(null);
      return bytes.toByteArray();
    } catch (IOException | RuntimeException failure) {
      throw invalid(failure);
    }
  }

  public static MetadataBuildAssociation decode(byte[] bytes, BuildSnapshot storedBuild) {
    if (bytes == null || bytes.length > MAX_BYTES) throw invalid(null);
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (!text(in).equals("VMMA1") || !text(in).equals(storedBuild.snapshotId()))
        throw invalid(null);
      var config = alias(in);
      var kind = MetadataStructureAttestation.Kind.valueOf(text(in));
      var slot = new ContentSlotKey(text(in), text(in));
      var preferred = flag(in) ? Optional.of(text(in)) : Optional.<String>empty();
      var encoded = size(in);
      var decoded = size(in);
      var token = UUID.fromString(text(in));
      int length = in.readInt();
      if (length < 1 || length > CanonicalContentRecords.MAX_BYTES || length > in.available())
        throw invalid(null);
      var proof = CanonicalContentRecords.decode(in.readNBytes(length));
      if (!(proof instanceof MetadataContentProof actual)) throw invalid(null);
      var time = Instant.parse(text(in));
      if (in.read() != -1) throw invalid(null);
      return new MetadataBuildAssociation(
          new MetadataDeclaration(storedBuild, config, kind, slot, preferred, encoded, decoded),
          token,
          actual,
          time);
    } catch (IOException | RuntimeException failure) {
      throw invalid(failure);
    }
  }

  private static void alias(DataOutputStream out, VerifiedSourceAlias value) throws IOException {
    text(out, value.source().namespace());
    text(out, value.source().immutableSourceKey());
    text(out, value.source().verificationSemantics());
    text(out, value.object().digest().value());
    out.writeLong(value.object().length());
    text(out, value.evidence().verifierSemantics());
    text(out, value.evidence().sourceUrl());
    text(out, value.evidence().verifiedAt().toString());
  }

  private static VerifiedSourceAlias alias(DataInputStream in) throws IOException {
    return new VerifiedSourceAlias(
        new ResolvedSourceIdentity(text(in), text(in), text(in)),
        new StoredObject(new ObjectDigest(text(in)), in.readLong()),
        new SourceVerificationEvidence(text(in), text(in), Instant.parse(text(in))));
  }

  private static void size(DataOutputStream out, OptionalLong value) throws IOException {
    out.writeBoolean(value.isPresent());
    if (value.isPresent()) out.writeLong(value.getAsLong());
  }

  private static OptionalLong size(DataInputStream in) throws IOException {
    return flag(in) ? OptionalLong.of(in.readLong()) : OptionalLong.empty();
  }

  private static boolean flag(DataInputStream in) throws IOException {
    int value = in.readUnsignedByte();
    if (value > 1) throw invalid(null);
    return value == 1;
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    try {
      var encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(java.nio.CharBuffer.wrap(value));
      if (encoded.remaining() > 65536) throw invalid(null);
      out.writeInt(encoded.remaining());
      while (encoded.hasRemaining()) out.writeByte(encoded.get());
    } catch (CharacterCodingException failure) {
      throw invalid(failure);
    }
  }

  private static String text(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 0 || length > 65536 || length > in.available()) throw invalid(null);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(in.readNBytes(length)))
        .toString();
  }

  private static IllegalArgumentException invalid(Throwable cause) {
    return new IllegalArgumentException("Invalid bounded metadata association record", cause);
  }
}
