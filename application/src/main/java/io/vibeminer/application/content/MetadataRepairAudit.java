package io.vibeminer.application.content;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Original consent/proof and bounded append-only history; no current-readiness assertion. */
public record MetadataRepairAudit(
    UUID attemptId,
    MetadataRepairPlan issued,
    Instant begunAt,
    List<MetadataRepairAuditEvent> events) {
  public MetadataRepairAudit {
    Objects.requireNonNull(attemptId);
    Objects.requireNonNull(issued);
    var kind = issued.request().kind();
    if (kind != io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ROOT
        && kind != io.vibeminer.domain.content.MetadataStructureAttestation.Kind.INSTALL)
      throw new ContentClaimException(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          "Metadata repair audits support only ROOT and INSTALL");
    Objects.requireNonNull(begunAt);
    events = List.copyOf(events);
    if (events.size() > 4) throw new IllegalArgumentException("Repair audit history exceeds bound");
    if (!issued.isUsableAt(begunAt))
      throw new IllegalArgumentException("Audit began outside issued consent lifetime");
    for (int i = 0; i < events.size(); i++) {
      var event = events.get(i);
      if (event.sequence() != i + 1
          || event.recordedAt().isBefore(begunAt)
          || i > 0 && event.recordedAt().isBefore(events.get(i - 1).recordedAt()))
        throw new IllegalArgumentException("Repair audit ordering differs");
      if (event.observation().isPresent()) {
        var observation = event.observation().get();
        if (!observation.repairAttemptId().equals(attemptId)
            || !observation.originalProof().equals(issued.originalProof()))
          throw new IllegalArgumentException("Repair audit observation belongs to another attempt");
        if (!observation
                .actualTransfer()
                .evidence()
                .sourceUrl()
                .equals(issued.sourceObservation().resourceUrl())
            || observation.payloadRequestCount() > issued.cost().maximumPayloadRequests()
            || observation.actualTransfer().evidence().verifiedAt().isBefore(begunAt)
            || observation.actualTransfer().evidence().verifiedAt().isAfter(event.recordedAt()))
          throw new IllegalArgumentException("Repair transfer differs from issued facts");
      }
      var previous = i == 0 ? null : events.get(i - 1);
      if (previous != null
          && (previous.kind() == MetadataRepairAuditEvent.Kind.COMPLETED
              || previous.kind() == MetadataRepairAuditEvent.Kind.CONCLUSIVELY_UNPUBLISHED
              || previous.kind() == MetadataRepairAuditEvent.Kind.UNCERTAIN))
        throw new IllegalArgumentException("Terminal repair audit cannot grow");
      switch (event.kind()) {
        case PROSPECTIVE -> {
          boolean root =
              issued.request().kind()
                  == io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ROOT;
          if (i != 0
              || event.observation().orElseThrow().structuralAttestation().isPresent() == root)
            throw new IllegalArgumentException("Repair prospective grammar ordering differs");
        }
        case STRUCTURE -> {
          if (previous == null
              || previous.kind() != MetadataRepairAuditEvent.Kind.PROSPECTIVE
              || issued.request().kind()
                  != io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ROOT
              || event.observation().orElseThrow().structuralAttestation().isEmpty()
              || !sameTransfer(
                  previous.observation().orElseThrow(), event.observation().orElseThrow()))
            throw new IllegalArgumentException(
                "Repair grammar must complete original prospective transfer");
        }
        case COMPLETED -> {
          if (previous == null
              || previous.observation().isEmpty()
              || !previous.observation().equals(event.observation())
              || event.observation().orElseThrow().structuralAttestation().isEmpty()
              || issued.request().kind()
                      == io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ROOT
                  && previous.kind() != MetadataRepairAuditEvent.Kind.STRUCTURE)
            throw new IllegalArgumentException(
                "Repair completion requires prior complete verification");
        }
        case CONCLUSIVELY_UNPUBLISHED -> {
          if (i != 0)
            throw new IllegalArgumentException(
                "Prospective repair cannot be conclusively unpublished");
        }
        case UNCERTAIN -> {}
      }
    }
  }

  private static boolean sameTransfer(
      MetadataRepairObservation first, MetadataRepairObservation second) {
    return first.repairAttemptId().equals(second.repairAttemptId())
        && first.originalProof().equals(second.originalProof())
        && first.actualTransfer().equals(second.actualTransfer())
        && first.decodedAttestation().equals(second.decodedAttestation())
        && first.payloadRequestCount() == second.payloadRequestCount()
        && first.payloadResponseBytes() == second.payloadResponseBytes()
        && first.observationDigest().equals(second.observationDigest());
  }
}
