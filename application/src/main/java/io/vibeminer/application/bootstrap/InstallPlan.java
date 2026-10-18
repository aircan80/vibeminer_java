package io.vibeminer.application.bootstrap;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.SourceDocument;
import java.util.Objects;

/** Exact, small install-manifest scope. This does not describe the game's root/asset catalogue. */
public record InstallPlan(BuildSnapshot build, SourceDocument configuration, String contentKey,
        String encodingKey, long decodedBytes, long encodedBytes) {
    public InstallPlan {
        Objects.requireNonNull(build); Objects.requireNonNull(configuration);
        if (contentKey == null || !contentKey.matches("[0-9a-f]{32}")
                || encodingKey == null || !encodingKey.matches("[0-9a-f]{32}")
                || decodedBytes < 10 || encodedBytes < 9)
            throw new IllegalArgumentException("Invalid install-manifest plan");
    }
}
