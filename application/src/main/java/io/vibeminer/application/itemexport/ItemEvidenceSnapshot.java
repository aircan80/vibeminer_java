package io.vibeminer.application.itemexport;

import io.vibeminer.domain.item.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable observation, retaining historical source evidence without restamping it. */
public record ItemEvidenceSnapshot(
    ItemEvidenceSelection selection,
    ItemPhysicalRow row,
    Optional<ItemNameMatch> name,
    Instant verificationStartedAt,
    Instant verificationCompletedAt,
    String generationFingerprint) {
  public ItemEvidenceSnapshot {
    Objects.requireNonNull(selection);
    Objects.requireNonNull(row);
    Objects.requireNonNull(name);
    Objects.requireNonNull(verificationStartedAt);
    Objects.requireNonNull(verificationCompletedAt);
    Objects.requireNonNull(generationFingerprint);
    var coverage = selection.item().coverage();
    if (row.recordId() != selection.recordId()
        || row.sectionOrdinal() != coverage.sectionOrdinal()
        || row.physicalOrdinal() >= coverage.physicalRows()
        || verificationCompletedAt.isBefore(verificationStartedAt)
        || !generationFingerprint.matches("[0-9a-f]{64}")
        || name.isPresent() != selection.names().isPresent()
        || name.isPresent() && name.get().requestedId() != selection.recordId())
      throw new IllegalArgumentException(
          "Snapshot differs from pinned physical scope or observation");
    var fields = selection.item().definition().interpretation().schema().fields();
    for (int i = 0; i < fields.size(); i++) {
      var binding = fields.get(i);
      var candidate = row.values().get(i);
      if (candidate.field() != binding.field()
          || candidate.candidateBits() != binding.candidateBits()
          || candidate.candidateSign() != binding.sign())
        throw new IllegalArgumentException("Physical candidate differs from admitted binding");
    }
    if (name.isPresent()) {
      selection.names().orElseThrow().names().requireMatch(name.orElseThrow());
    }
  }
}
