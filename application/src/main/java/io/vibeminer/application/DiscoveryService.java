package io.vibeminer.application;

import io.vibeminer.domain.BuildSnapshot;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** UI-facing discovery use case. Refresh is explicit and asynchronous. */
public interface DiscoveryService {
    List<BuildSnapshot> fixtureBuilds();
    CompletionStage<List<BuildSnapshot>> refresh(String productId, String region);
}
