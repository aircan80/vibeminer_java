package io.vibeminer.application.acquisition;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.StoredObject;

/**
 * Source-specific verified acquisition port. Its adapter owns bounded transfer, exact protocol
 * verification and physical publication through ObjectStore before returning. This interface does
 * not interpret an EKey as whole-stream MD5. Unsupported verifier semantics must be rejected.
 * Cancellation is cooperative; adapters should also release blocked I/O when interrupted.
 */
@FunctionalInterface
public interface VerifiedSourceAcquirer {
    StoredObject acquire(ResolvedSourceIdentity identity, CancellationToken cancellation);
}
