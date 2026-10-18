package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.content.ContentWorkerPermit;
import java.util.Optional;
import java.util.UUID;

/** Append-only repair evidence, separate from canonical publication and consumer attachment. */
public interface MetadataRepairAuditRepository {
  int MAXIMUM_ATTEMPTS_PER_SLOT = 128;

  /**
   * Caller must replay issued consent and establish missing/corrupt/uncertain original A under this
   * actual worker permit. Construction alone authenticates neither consent nor source facts. A
   * fresh bounded attempt is returned; this method does not inspect or fetch payload bytes.
   */
  UUID begin(ContentWorkerPermit permit, MetadataRepairPlan issued, CancellationToken cancellation);

  /**
   * Mandatory durable gate before CAS promotion, after original physical SHA/length/EOF and full
   * decoded CKey verification. Root grammar must still be absent; install grammar is required.
   */
  void recordProspective(
      ContentWorkerPermit permit,
      UUID attemptId,
      MetadataRepairObservation observation,
      CancellationToken cancellation);

  /** Root only, after the actual complete local second parser pass; transfer facts stay fixed. */
  void recordStructure(
      ContentWorkerPermit permit,
      UUID attemptId,
      MetadataRepairObservation observation,
      CancellationToken cancellation);

  /**
   * Revalidates original physical bytes under a lease and appends completed evidence. Does not
   * publish/upgrade canonical state, register an alias, attach a consumer, pin or retire a worker.
   */
  MetadataRepairAudit complete(
      ContentWorkerPermit permit,
      UUID attemptId,
      MetadataRepairObservation observation,
      CancellationToken cancellation);

  /**
   * Actual-worker cleanup after IO retirement. Prospective evidence forces uncertainty; terminal
   * audit success cannot downgrade. A canceled facade never owns this cleanup authority.
   */
  void fail(
      ContentWorkerPermit permit,
      UUID attemptId,
      MetadataAcquisitionFailure.Publication publication,
      CancellationToken cancellation);

  /** Historical evidence only; never current readability or permission to fetch. */
  Optional<MetadataRepairAudit> find(UUID attemptId, CancellationToken cancellation);
}
