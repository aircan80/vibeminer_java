package io.vibeminer.infrastructure.content;

import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

/** Strict bounded audit metadata; original receipts and fresh verification stay separate. */
public final class MetadataRepairAuditRecords {
  public static final int MAX_BYTES = 262144;

  private MetadataRepairAuditRecords() {}

  @FunctionalInterface
  private interface Writer {
    void write(DataOutputStream out) throws IOException;
  }

  @FunctionalInterface
  private interface Reader<T> {
    T read(DataInputStream in) throws IOException;
  }

  public static byte[] plan(MetadataRepairPlan p) {
    return encode(
        out -> {
          text(out, "VMRA1");
          text(out, p.issuanceId().toString());
          text(out, p.reservationToken().toString());
          var r = p.request();
          text(out, r.build().snapshotId());
          alias(out, r.configuration());
          text(out, r.kind().name());
          text(out, r.slot().namespace());
          text(out, r.slot().contentKey());
          text(out, r.requestedEncodingKey());
          out.writeLong(r.requestedEncodedBytes());
          size(out, r.declaredDecodedBytes());
          proof(out, p.originalProof());
          var h = p.sourceObservation();
          text(out, h.resourceUrl());
          text(out, h.resourceKey());
          out.writeLong(h.wholeEncodedBytes());
          text(out, h.observedAt().toString());
          text(out, h.observationDigest());
          var c = p.cost();
          out.writeLong(c.encodedTransferBytes());
          out.writeLong(c.maximumAdditionalPhysicalBytes());
          out.writeInt(c.maximumPayloadRequests());
          out.writeLong(c.maximumDecodedWorkBytes());
          out.writeLong(c.maximumPayloadDuration().getSeconds());
          out.writeInt(c.maximumPayloadDuration().getNano());
          text(out, p.issuedAt().toString());
          text(out, p.expiresAt().toString());
        });
  }

  public static MetadataRepairPlan plan(byte[] bytes, BuildSnapshot build) {
    return decode(
        bytes,
        in -> {
          if (!text(in).equals("VMRA1")) throw invalid(null);
          var issuance = UUID.fromString(text(in));
          var token = UUID.fromString(text(in));
          if (!text(in).equals(build.snapshotId())) throw invalid(null);
          var config = alias(in);
          var kind = MetadataStructureAttestation.Kind.valueOf(text(in));
          var slot = new ContentSlotKey(text(in), text(in));
          var key = text(in);
          long length = in.readLong();
          var decoded = size(in);
          var request = new MetadataContentRequest(build, config, kind, slot, key, length, decoded);
          var original = proof(in);
          var head =
              new MetadataRepairSourceObservation(
                  text(in), text(in), in.readLong(), Instant.parse(text(in)), text(in));
          long transfer = in.readLong(), additional = in.readLong();
          int requests = in.readInt();
          long work = in.readLong();
          long seconds = in.readLong();
          int nanos = in.readInt();
          if (nanos < 0 || nanos > 999999999) throw invalid(null);
          var cost =
              new MetadataRepairCost(
                  transfer, additional, requests, work, Duration.ofSeconds(seconds, nanos));
          return new MetadataRepairPlan(
              issuance,
              token,
              request,
              original,
              head,
              cost,
              Instant.parse(text(in)),
              Instant.parse(text(in)));
        });
  }

  public static byte[] event(MetadataRepairAuditEvent value) {
    return encode(
        out -> {
          text(out, "VMRE1");
          out.writeInt(value.sequence());
          text(out, value.kind().name());
          text(out, value.recordedAt().toString());
          out.writeBoolean(value.observation().isPresent());
          if (value.observation().isPresent()) observation(out, value.observation().get());
        });
  }

  public static MetadataRepairAuditEvent event(byte[] bytes) {
    return decode(
        bytes,
        in -> {
          if (!text(in).equals("VMRE1")) throw invalid(null);
          int sequence = in.readInt();
          var kind = MetadataRepairAuditEvent.Kind.valueOf(text(in));
          var at = Instant.parse(text(in));
          return new MetadataRepairAuditEvent(
              sequence, kind, at, flag(in) ? Optional.of(observation(in)) : Optional.empty());
        });
  }

  private static void observation(DataOutputStream out, MetadataRepairObservation value)
      throws IOException {
    text(out, value.repairAttemptId().toString());
    proof(out, value.originalProof());
    alias(out, value.actualTransfer());
    var decoded = value.decodedAttestation();
    text(out, decoded.contentKey());
    out.writeLong(decoded.actualDecodedBytes());
    text(out, decoded.verifierRevision());
    out.writeBoolean(value.structuralAttestation().isPresent());
    if (value.structuralAttestation().isPresent()) {
      var grammar = value.structuralAttestation().get();
      text(out, grammar.kind().name());
      text(out, grammar.parserRevision());
    }
    out.writeInt(value.payloadRequestCount());
    out.writeLong(value.payloadResponseBytes());
    text(out, value.observationDigest());
  }

  private static MetadataRepairObservation observation(DataInputStream in) throws IOException {
    var attempt = UUID.fromString(text(in));
    var original = proof(in);
    var transfer = alias(in);
    var decoded = new DecodedContentAttestation(text(in), in.readLong(), text(in));
    var grammar =
        flag(in)
            ? Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.valueOf(text(in)), text(in)))
            : Optional.<MetadataStructureAttestation>empty();
    return new MetadataRepairObservation(
        attempt, original, transfer, decoded, grammar, in.readInt(), in.readLong(), text(in));
  }

  private static void proof(DataOutputStream out, MetadataContentProof proof) throws IOException {
    var bytes = CanonicalContentRecords.encode(proof);
    if (!CanonicalContentRecords.decode(bytes).equals(proof)) throw invalid(null);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static MetadataContentProof proof(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 1 || length > CanonicalContentRecords.MAX_BYTES || length > in.available())
      throw invalid(null);
    var proof = CanonicalContentRecords.decode(in.readNBytes(length));
    if (!(proof instanceof MetadataContentProof metadata)) throw invalid(null);
    return metadata;
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
    var encoded =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value));
    if (encoded.remaining() > 65536) throw invalid(null);
    out.writeInt(encoded.remaining());
    while (encoded.hasRemaining()) out.writeByte(encoded.get());
  }

  private static String text(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 0 || size > 65536 || size > in.available()) throw invalid(null);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(in.readNBytes(size)))
        .toString();
  }

  private static byte[] encode(Writer writer) {
    try {
      var bytes = new ByteArrayOutputStream();
      writer.write(new DataOutputStream(bytes));
      if (bytes.size() > MAX_BYTES) throw invalid(null);
      return bytes.toByteArray();
    } catch (IOException | RuntimeException failure) {
      throw invalid(failure);
    }
  }

  private static <T> T decode(byte[] bytes, Reader<T> reader) {
    if (bytes == null || bytes.length > MAX_BYTES) throw invalid(null);
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      var value = reader.read(in);
      if (in.read() != -1) throw invalid(null);
      return value;
    } catch (IOException | RuntimeException failure) {
      throw invalid(failure);
    }
  }

  private static IllegalArgumentException invalid(Throwable cause) {
    return new IllegalArgumentException("Invalid bounded repair audit record", cause);
  }
}
