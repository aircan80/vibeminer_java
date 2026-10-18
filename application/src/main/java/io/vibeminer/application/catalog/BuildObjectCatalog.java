package io.vibeminer.application.catalog;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.SourceVerificationEvidence;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.StoredObject;
import java.util.List;
import java.util.Optional;

/** Blocking catalog port; implementations preserve immutable mappings and verify local readiness. */
public interface BuildObjectCatalog {
    void registerSnapshot(BuildSnapshot snapshot);
    Optional<VerifiedSourceAlias> findVerifiedSourceAlias(ResolvedSourceIdentity source, CancellationToken cancellation);
    default Optional<StoredObject> findVerifiedAlias(ResolvedSourceIdentity source, CancellationToken cancellation) {
        return findVerifiedSourceAlias(source, cancellation).map(VerifiedSourceAlias::object);
    }
    /** Call only after source verification and CAS publication; alias and membership commit together. */
    BuildObjectReference attachVerifiedObject(String snapshotId, String role, ResolvedSourceIdentity source,
            StoredObject object, SourceVerificationEvidence evidence, CancellationToken cancellation);
    /** Returns persisted metadata, including references whose bytes are currently missing. */
    List<BuildObjectReference> listReferences(String snapshotId);
    Optional<BuildObjectReference> findVerifiedObject(String snapshotId, String role, CancellationToken cancellation);
}
