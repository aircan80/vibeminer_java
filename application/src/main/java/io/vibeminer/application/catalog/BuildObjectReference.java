package io.vibeminer.application.catalog;

import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.SourceVerificationEvidence;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.StoredObject;
import java.util.Objects;

/** Persisted membership metadata; callers must request physical verification before claiming readiness. */
public record BuildObjectReference(String snapshotId, String role, ResolvedSourceIdentity source,
        StoredObject object, SourceVerificationEvidence evidence) {
    public BuildObjectReference {
        validateSnapshotId(snapshotId);
        validateRole(role);
        Objects.requireNonNull(source); Objects.requireNonNull(object); Objects.requireNonNull(evidence);
        if (!source.verificationSemantics().equals(evidence.verifierSemantics())) throw new IllegalArgumentException("Verification evidence does not match source semantics");
    }
    public static String validateSnapshotId(String snapshotId) {
        if (snapshotId == null || !snapshotId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid exact snapshot identity");
        return snapshotId;
    }
    public static String validateRole(String role) {
        if (role == null || role.isBlank() || !role.equals(role.strip()) || role.length() > 128 || role.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid object membership role");
        return role;
    }
}
