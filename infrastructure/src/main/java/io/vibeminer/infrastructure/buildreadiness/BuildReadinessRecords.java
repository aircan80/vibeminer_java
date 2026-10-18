package io.vibeminer.infrastructure.buildreadiness;

import io.vibeminer.application.buildoverview.BuildOverview;
import io.vibeminer.application.buildreadiness.BuildReadiness;
import io.vibeminer.application.buildretention.BuildRetention;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Bounded lossless summary codec. This stores no game body, source proof or repair authority. */
final class BuildReadinessRecords {
  record Stored(Optional<String> admission, BuildReadiness.Observation observation) {}

  static final int MAX_BYTES = 16384;

  static byte[] encode(
      String context, Optional<String> admission, BuildReadiness.Observation observation) {
    digest(context);
    Objects.requireNonNull(admission);
    Objects.requireNonNull(observation);
    admission.ifPresent(BuildReadinessRecords::digest);
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      text(out, "VM-READINESS-1");
      text(out, context);
      text(out, admission.orElse(""));
      text(out, observation.readiness().name());
      text(out, observation.measuredAt().toString());
      text(out, observation.overviewRevision());
      var origin = observation.origin();
      text(out, origin.eventId().toString());
      out.writeLong(origin.observationSequence());
      out.writeInt(origin.versionsRowOrdinal());
      text(out, origin.groupId());
      text(out, origin.snapshotId());
      text(out, origin.region());
      out.writeBoolean(observation.target().isPresent());
      if (observation.target().isPresent()) {
        var target = observation.target().get();
        for (var value :
            List.of(
                target.groupId(),
                target.snapshotId(),
                target.admissionId(),
                target.inventoryId(),
                Long.toString(target.inventoryGeneration()),
                target.scopeRevision(),
                target.scopeCommitment())) text(out, value);
      }
      out.flush();
      if (bytes.size() > MAX_BYTES)
        throw new IllegalArgumentException("Oversized readiness summary");
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static Stored decode(String context, byte[] bytes) {
    digest(context);
    if (bytes == null || bytes.length > MAX_BYTES)
      throw new IllegalArgumentException("Missing/oversized readiness summary");
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (!text(in).equals("VM-READINESS-1") || !text(in).equals(context))
        throw new IllegalArgumentException("Readiness summary changed context/version");
      var admission = text(in);
      if (!admission.isEmpty() && !admission.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid admission digest");
      var readiness = BuildOverview.Readiness.valueOf(text(in));
      var measured = Instant.parse(text(in));
      var revision = text(in);
      var origin =
          new BuildReadiness.Origin(
              UUID.fromString(text(in)), in.readLong(), in.readInt(), text(in), text(in), text(in));
      Optional<BuildRetention.Target> target = Optional.empty();
      int marker = in.readUnsignedByte();
      if (marker > 1) throw new IllegalArgumentException("Invalid target marker");
      if (marker == 1)
        target =
            Optional.of(
                new BuildRetention.Target(
                    text(in),
                    text(in),
                    text(in),
                    text(in),
                    Long.parseLong(text(in)),
                    text(in),
                    text(in)));
      if (in.read() != -1) throw new IllegalArgumentException("Trailing readiness summary");
      return new Stored(
          admission.isEmpty() ? Optional.empty() : Optional.of(admission),
          new BuildReadiness.Observation(readiness, measured, revision, origin, target));
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException("Invalid readiness summary", failure);
    }
  }

  static String context(BuildReadiness.Context context, String policy) {
    var values = new ArrayList<String>(context.key());
    values.add(policy);
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      text(out, "build-readiness-context-v1");
      for (var value : values) text(out, value);
      return hash(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Exact summary digest required");
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    if (!StandardCharsets.UTF_8.newEncoder().canEncode(value))
      throw new IllegalArgumentException("Lossless UTF8 required");
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 4096) throw new IllegalArgumentException("Oversized summary text");
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String text(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 0 || size > 4096 || size > in.available())
      throw new IllegalArgumentException("Invalid summary text length");
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(in.readNBytes(size)))
        .toString();
  }
}
