package io.vibeminer.domain;

import java.time.Instant;
import java.util.Objects;

/** Attestation from the protocol verifier; this record does not itself verify protocol content. */
public record SourceVerificationEvidence(String verifierSemantics, String sourceUrl, Instant verifiedAt) {
    public SourceVerificationEvidence {
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        if (verifierSemantics == null || verifierSemantics.isBlank() || verifierSemantics.length() > 4096
                || verifierSemantics.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid verifier semantics");
        if (sourceUrl == null || sourceUrl.length() > 2048 || !sourceUrl.matches("https://[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::[0-9]{1,5})?(?:[/?#][^\\s]*)?")
                || sourceUrl.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Verification provenance must be a bounded HTTPS URL");
    }
}
