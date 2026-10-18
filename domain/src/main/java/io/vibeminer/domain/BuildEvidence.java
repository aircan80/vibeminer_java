package io.vibeminer.domain;

import java.util.List;
import java.util.Objects;

/** Source observations attached to a selected build, separate from its presentation label. */
public record BuildEvidence(String upstreamBuildId, String productConfig, List<SourceDocument> documents) {
    public BuildEvidence {
        Objects.requireNonNull(upstreamBuildId); Objects.requireNonNull(productConfig);
        documents = List.copyOf(documents);
        if (!upstreamBuildId.isEmpty() && !upstreamBuildId.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("Invalid upstream build ID");
        }
        if (!productConfig.isEmpty() && !productConfig.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("Invalid product configuration identity");
        }
        productConfig = productConfig.toLowerCase(java.util.Locale.ROOT);
        if (documents.size() > 8) throw new IllegalArgumentException("Too many source documents");
    }
    public static BuildEvidence unrecorded() { return new BuildEvidence("", "", List.of()); }
}
