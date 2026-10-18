package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.lookup.RootSelectionPlan;
import java.util.UUID;

/**
 * Blocking execution of newly issued, exact-original-A repair consent. This port defines no
 * ordinary acquisition fallback, default implementation or database access. It is not runtime
 * enablement.
 *
 * <p>Before invocation, the actual worker must own its permit, reread canonical authority and local
 * readiness, and begin a durable audit attempt bound to this issued plan. Healthy A requires local
 * replay instead. The attempt ID comes from that audit, never from the executor. The observer
 * durably fences prospective facts before CAS promotion; caller cancellation cannot retire the
 * worker until actual I/O and verifier cleanup ends.
 *
 * <p>Implementations replay the same issuer's complete consent and borrowed ordinary-plan authority
 * before payload, enforce the issued known cost and pinned route without redirects or fallback, and
 * return the strongest fresh observation after both mandatory callbacks succeed. Its attempt ID,
 * original proof and actual transfer facts must match the callback observations. Original canonical
 * aliases, evidence and capabilities are never overwritten. The return value alone does not
 * finalize an audit, establish readiness or attach a consumer membership.
 *
 * <p>Execution failures use {@link MetadataAcquisitionFailure} to preserve physical-publication
 * certainty, including cancellation as its cause. Implementations check cancellation immediately
 * after each mandatory callback, before promotion and before final return. Before a successful
 * prospective gate, failure is conclusively unpublished; after that gate it is conservatively
 * uncertain. Callers must preserve that distinction and the underlying typed cause before retiring
 * their own permit. If a callback persisted a prospective event and then threw, physical
 * publication remains conclusively absent, but the audit conservatively retains its durable
 * prospective fact as uncertain.
 */
public interface MetadataRepairExecutionGateway {
  MetadataRepairObservation repairInstall(
      InstallPlan ordinaryPlan,
      MetadataRepairPlan issued,
      UUID repairAttemptId,
      MetadataRepairObserver observer,
      CancellationToken cancellation);

  MetadataRepairObservation repairRoot(
      RootSelectionPlan ordinarySelection,
      MetadataRepairPlan issued,
      UUID repairAttemptId,
      MetadataRepairObserver observer,
      CancellationToken cancellation);
}
