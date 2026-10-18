package io.vibeminer.application.content;

import io.vibeminer.domain.content.MetadataContentProof;
import java.util.Objects;
import java.util.Optional;

/**
 * Attached original content and this execution's actual payload evidence. An empty audit denotes
 * verified local restoration observed during execution, with zero payload transfer. Constructors
 * check consistency only; the service must perform the actual verification and attachment.
 */
public record MetadataRepairResult(
    MetadataBuildAssociation association, Optional<MetadataRepairAudit> completedAudit) {
  public MetadataRepairResult {
    Objects.requireNonNull(association);
    Objects.requireNonNull(completedAudit);
    if (completedAudit.isPresent()) validate(association, completedAudit.get());
  }

  private static void validate(MetadataBuildAssociation association, MetadataRepairAudit audit) {
    if (audit.events().isEmpty()
        || audit.events().getLast().kind() != MetadataRepairAuditEvent.Kind.COMPLETED
        || audit.events().getLast().observation().isEmpty())
      throw new IllegalArgumentException("Repair result requires completed payload evidence");
    var issued = audit.issued();
    var request = issued.request();
    var declaration = association.declaration();
    if (!association.canonicalReservationToken().equals(issued.reservationToken())
        || !declaration.build().snapshotId().equals(request.build().snapshotId())
        || !declaration.configuration().equals(request.configuration())
        || declaration.kind() != request.kind()
        || !declaration.slot().equals(request.slot())
        || declaration.preferredEncodingKey().isPresent()
            && !declaration.preferredEncodingKey().get().equals(request.requestedEncodingKey())
        || declaration.declaredEncodedBytes().isPresent()
            && declaration.declaredEncodedBytes().getAsLong() != request.requestedEncodedBytes()
        || declaration.declaredDecodedBytes().isPresent()
            && !declaration.declaredDecodedBytes().equals(request.declaredDecodedBytes()))
      throw new IllegalArgumentException(
          "Repair result belongs to a different declaration or claim");
    var fresh = audit.events().getLast().observation().orElseThrow();
    var original = issued.originalProof();
    MetadataContentProof expected =
        original.withAttestations(
            original.decodedAttestation().isPresent()
                ? original.decodedAttestation()
                : Optional.of(fresh.decodedAttestation()),
            original.structuralAttestation().isPresent()
                ? original.structuralAttestation()
                : fresh.structuralAttestation());
    if (!expected.equals(association.actualRepresentation()))
      throw new IllegalArgumentException(
          "Repair result changed the original receipt or add-missing capability facts");
    // associatedAt may be the first historical attachment, earlier than this repair attempt.
  }
}
