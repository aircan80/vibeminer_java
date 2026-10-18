package io.vibeminer.domain.item;

import io.vibeminer.domain.StoredObject;
import io.vibeminer.domain.content.DecodedContentAttestation;
import io.vibeminer.domain.selectedfile.SelectedFileAssociation;
import java.time.Instant;
import java.util.*;

/**
 * Four distinct proof dimensions; required window proof never implies a full decoded table CKey.
 */
public record ItemProjectionEvidence(
    SelectedFileAssociation association,
    LocalVerification localVerification,
    List<ChunkAttestation> chunks,
    List<DecodedWindow> windows,
    Optional<DecodedContentAttestation> fullDecoded) {
  public record LocalVerification(
      StoredObject object, Instant verifiedAt, String verifierRevision) {
    public LocalVerification {
      Objects.requireNonNull(object);
      Objects.requireNonNull(verifiedAt);
      verifierRevision = ItemValues.text(verifierRevision);
    }
  }

  public record ChunkAttestation(
      int ordinal,
      long encodedOffset,
      long encodedBytes,
      String encodedMd5,
      long decodedOffset,
      long decodedBytes,
      String verifierRevision) {
    public ChunkAttestation {
      if (ordinal < 0) throw new IllegalArgumentException("Nonnegative chunk required");
      ItemValues.extent(encodedOffset, encodedBytes);
      ItemValues.extent(decodedOffset, decodedBytes);
      encodedMd5 = ItemValues.key(encodedMd5);
      verifierRevision = ItemValues.text(verifierRevision);
    }
  }

  public record DecodedWindow(long offset, long length) {
    public DecodedWindow {
      ItemValues.extent(offset, length);
    }
  }

  public ItemProjectionEvidence {
    Objects.requireNonNull(association);
    Objects.requireNonNull(localVerification);
    Objects.requireNonNull(fullDecoded);
    chunks = List.copyOf(chunks);
    windows = List.copyOf(windows);
    if (!association.representation().encoded().object().equals(localVerification.object())
        || chunks.isEmpty()
        || chunks.size() > 4096
        || windows.isEmpty()
        || windows.size() > 64)
      throw new IllegalArgumentException(
          "Bounded current physical and consumed window evidence required");
    var declaredDecoded = association.proof().declaredDecodedBytes();
    if (fullDecoded.isPresent()
        && declaredDecoded.isPresent()
        && fullDecoded.get().actualDecodedBytes() != declaredDecoded.getAsLong()) {
      throw new IllegalArgumentException(
          "Complete decoded length conflicts with authenticated EN declaration");
    }
    long lastEncodedEnd = -1, lastDecodedEnd = -1;
    int lastOrdinal = -1;
    for (var chunk : chunks) {
      if (chunk.ordinal() <= lastOrdinal
          || chunk.encodedOffset() < lastEncodedEnd
          || chunk.decodedOffset() < lastDecodedEnd
          || chunk.encodedOffset() + chunk.encodedBytes() > localVerification.object().length())
        throw new IllegalArgumentException("Chunk evidence overlaps or exceeds physical source");
      long decodedEnd = chunk.decodedOffset() + chunk.decodedBytes();
      if (declaredDecoded.isPresent() && decodedEnd > declaredDecoded.getAsLong()
          || fullDecoded.isPresent() && decodedEnd > fullDecoded.get().actualDecodedBytes()) {
        throw new IllegalArgumentException("Consumed chunk exceeds known decoded object boundary");
      }
      lastOrdinal = chunk.ordinal();
      lastEncodedEnd = chunk.encodedOffset() + chunk.encodedBytes();
      lastDecodedEnd = chunk.decodedOffset() + chunk.decodedBytes();
    }
    for (var window : windows) {
      long covered = window.offset(), end = window.offset() + window.length();
      for (var chunk : chunks) {
        if (chunk.decodedOffset() > covered) break;
        if (chunk.decodedOffset() + chunk.decodedBytes() > covered)
          covered = Math.min(end, chunk.decodedOffset() + chunk.decodedBytes());
        if (covered == end) break;
      }
      if (covered != end)
        throw new IllegalArgumentException(
            "Required decoded window is not covered by authenticated chunk evidence");
    }
    if (fullDecoded.isPresent()) {
      var proof = fullDecoded.get();
      if (!proof.contentKey().equals(association.descriptor().association().contentKey()))
        throw new IllegalArgumentException(
            "Complete decoded attestation belongs to another declared content key");
      for (var window : windows)
        if (window.offset() + window.length() > proof.actualDecodedBytes())
          throw new IllegalArgumentException(
              "Window exceeds independently verified complete decoded length");
    }
  }
}
