package io.vibeminer.bootstrap;

import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.infrastructure.discovery.OfficialDiscoveryService;
import io.vibeminer.desktop.AtlasApplication;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Composition root; launching never discovers game installations or starts network requests. */
public final class Main {
    private Main() { }
    public static void main(String[] args) {
        if (List.of(args).contains("--self-test")) {
            var builds = FixtureDiscovery.builds();
            if (builds.size() != 2 || builds.getFirst().snapshotId().equals(builds.getLast().snapshotId())) {
                throw new IllegalStateException("Identity self-test failed");
            }
            System.out.println("VibeMiner self-test OK (bundled runtime; synthetic identity; no network)");
            return;
        }
        var official = new OfficialDiscoveryService();
        DiscoveryService discovery = new DiscoveryService() {
            @Override public List<BuildSnapshot> fixtureBuilds() { return FixtureDiscovery.builds(); }
            @Override public CompletionStage<List<BuildSnapshot>> refresh(String productId, String region) {
                return official.refresh(productId, region);
            }
        };
        AtlasApplication.configure(discovery, List.of(args).contains("--fixtures"));
        javafx.application.Application.launch(AtlasApplication.class, args);
    }
}
