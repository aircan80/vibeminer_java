package io.vibeminer.desktop;

import io.vibeminer.application.DiscoveryService;
import io.vibeminer.domain.BuildSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** UI-independent state machine. A retired request can never repaint a newer context. */
public final class DiscoveryPresentationModel implements AutoCloseable {
    public record State(List<BuildSnapshot> builds, BuildSnapshot selected, boolean busy,
                        String message, String error, boolean fixtureMode) {
        public State { builds = List.copyOf(builds); }
    }
    private final DiscoveryService service;
    private final Executor presentationExecutor;
    private final Consumer<State> observer;
    private long generation;
    private boolean closed;
    private State state;

    public DiscoveryPresentationModel(DiscoveryService service, Executor presentationExecutor,
                                     Consumer<State> observer, boolean fixtureMode) {
        this.service = Objects.requireNonNull(service);
        this.presentationExecutor = Objects.requireNonNull(presentationExecutor);
        this.observer = Objects.requireNonNull(observer);
        state = new State(List.of(), null, false, "Choose a source to begin.", "", fixtureMode);
    }
    public State state() { return state; }
    public void showFixtures() {
        if (closed) return;
        generation++;
        publish(new State(service.fixtureBuilds(), null, false,
                "Fictional metadata only. Select a fixture build to explore the interface.", "", true));
    }
    public void refresh(String productId, String region) {
        if (closed) return;
        if (productId == null || !productId.matches("[a-z0-9_]+") || region == null || region.isBlank()) {
            generation++;
            publish(new State(state.builds(), state.selected(), false, state.message(),
                    "Choose a valid product identifier and region.", state.fixtureMode()));
            return;
        }
        final long request = ++generation;
        publish(new State(List.of(), null, true,
                "Checking official published metadata. No game content is being downloaded.", "", false));
        try {
            service.refresh(productId, region).whenComplete((builds, failure) ->
                presentationExecutor.execute(() -> {
                    if (closed || request != generation) return;
                    if (failure != null) {
                        publish(new State(List.of(), null, false,
                                "No verified metadata was loaded. Retry or open fictional fixtures.",
                                "Discovery could not complete. Check your connection and try again.", false));
                    } else {
                        List<BuildSnapshot> result = builds == null ? List.of() : builds;
                        publish(new State(result, null, false,
                                result.isEmpty() ? "No published builds were returned for this source."
                                        : "Published metadata loaded. Content availability has not been checked.",
                                "", false));
                    }
                }));
        } catch (RuntimeException failure) {
            if (request == generation) publish(new State(List.of(), null, false,
                    "No verified metadata was loaded.", "Discovery could not start. Try again.", false));
        }
    }
    /** Stops observing this request; underlying shared work may continue until the port supports cancellation. */
    public void stopWaiting() {
        if (closed) return;
        generation++;
        publish(new State(state.builds(), state.selected(), false,
                "Stopped waiting. No data from that request will be applied.", "", state.fixtureMode()));
    }
    public void select(BuildSnapshot build) {
        if (closed || !state.builds().contains(build)) return;
        generation++;
        publish(new State(state.builds(), build, false,
                "Metadata workspace opened. Files, tables and archive acquisition are not implemented yet.", "",
                build.fixture()));
    }
    /** Reopens a captured source without consulting discovery or replacing it with the latest pointer. */
    public void restore(BuildSnapshot saved) {
        if(closed)return;
        Objects.requireNonNull(saved);
        generation++;
        publish(new State(List.of(saved),saved,false,
                "Saved source reopened offline. Metadata only; no game content has been retained.","",saved.fixture()));
    }
    private void publish(State next) { state = next; observer.accept(next); }
    @Override public void close() { closed = true; generation++; }
}
