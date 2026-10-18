package io.vibeminer.domain;

import java.util.Objects;

/** Immutable verified acquisition receipt; advertised candidate routes are separate from actual evidence. */
public record VerifiedSourceAlias(ResolvedSourceIdentity source, StoredObject object, SourceVerificationEvidence evidence) {
    public VerifiedSourceAlias {
        Objects.requireNonNull(source); Objects.requireNonNull(object); Objects.requireNonNull(evidence);
        if (!source.verificationSemantics().equals(evidence.verifierSemantics())) {
            throw new IllegalArgumentException("Acquisition evidence does not match source verification semantics");
        }
    }
}
