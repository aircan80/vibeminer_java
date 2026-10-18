package io.vibeminer.application.bootstrap;

import io.vibeminer.domain.StoredObject;
import io.vibeminer.domain.SourceVerificationEvidence;
import java.util.List;
import java.util.Objects;

/** Verified retained install metadata only; entry names are display values, never local paths. */
public record InstallInspection(InstallPlan plan, StoredObject object, SourceVerificationEvidence verification, List<Entry> entries, List<Tag> tags) {
    public record Tag(String name, int type) { public Tag { Objects.requireNonNull(name); } }
    public record Entry(String name, String contentKey, long decodedBytes, List<Tag> tags) {
        public Entry { Objects.requireNonNull(name); Objects.requireNonNull(contentKey); tags = List.copyOf(tags); }
    }
    public InstallInspection {
        Objects.requireNonNull(plan); Objects.requireNonNull(object); Objects.requireNonNull(verification);
        entries = List.copyOf(entries); tags = List.copyOf(tags);
    }
}
