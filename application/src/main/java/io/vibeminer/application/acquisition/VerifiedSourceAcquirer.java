package io.vibeminer.application.acquisition;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.ResolvedSourceIdentity;

/**
 * Source-specific verified acquisition port. Its adapter owns bounded transfer, exact protocol
 * verification and physical publication through ObjectStore before returning its actual immutable acquisition evidence. This interface does
 * not interpret an EKey as whole-stream MD5. Unsupported verifier semantics must be rejected.
 * Cancellation is cooperative; adapters should also release blocked I/O when interrupted.
 */
@FunctionalInterface
public interface VerifiedSourceAcquirer {
    VerifiedSourceAlias acquire(ResolvedSourceIdentity identity, CancellationToken cancellation);
}
