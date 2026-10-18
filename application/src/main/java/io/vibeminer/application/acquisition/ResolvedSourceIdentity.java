package io.vibeminer.application.acquisition;

/**
 * Immutable resolved object identity, including its protocol namespace and full source key.
 * Build memberships share this identity when bytes and verification inputs match; build provenance
 * stays in the catalog. Include configuration context only when it changes key interpretation.
 * verificationSemantics binds the verifier's algorithm, byte scope, dependencies and revision;
 * a filename, CDN URL or an encoding key without verification semantics is insufficient.
 */
public record ResolvedSourceIdentity(String namespace, String immutableSourceKey, String verificationSemantics) {
    public ResolvedSourceIdentity {
        namespace = required(namespace, "namespace");
        immutableSourceKey = required(immutableSourceKey, "immutableSourceKey");
        verificationSemantics = required(verificationSemantics, "verificationSemantics");
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be a bounded nonblank single-line value");
        }
        return value;
    }
}
