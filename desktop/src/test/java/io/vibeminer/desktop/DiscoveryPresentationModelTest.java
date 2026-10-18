package io.vibeminer.desktop;

import io.vibeminer.application.DiscoveryService;
import io.vibeminer.domain.BuildSnapshot;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryPresentationModelTest {
    private static BuildSnapshot build(String product,String version,boolean fixture) {
        return new BuildSnapshot(product,product.equals("wow")?"Retail":"Forever","test","us",version,
                "a".repeat(32),"b".repeat(32),Instant.parse("2026-10-18T00:00:00Z"),fixture);
    }
    private static final class Service implements DiscoveryService {
        final Queue<CompletableFuture<List<BuildSnapshot>>> requests=new ArrayDeque<>();
        @Override public List<BuildSnapshot> fixtureBuilds(){return List.of(build("wow","DEMO-105",true));}
        @Override public CompletableFuture<List<BuildSnapshot>> refresh(String p,String r){var future=new CompletableFuture<List<BuildSnapshot>>();requests.add(future);return future;}
    }
    @Test void firstLaunchHasNoInventedSelectedSource(){
        var m=new DiscoveryPresentationModel(new Service(),Runnable::run,s->{},true);
        assertNull(m.state().selected());assertTrue(m.state().builds().isEmpty());
        m.showFixtures();assertTrue(m.state().fixtureMode());assertNull(m.state().selected());
    }
    @Test void delayedDifferentEditionCannotReplaceNewerRequest(){
        var service=new Service();var m=new DiscoveryPresentationModel(service,Runnable::run,s->{},false);
        m.refresh("wow_forever","us");var old=service.requests.remove();
        m.refresh("wow","us");var current=service.requests.remove();
        var retail=build("wow","R-2",false);current.complete(List.of(retail));
        old.complete(List.of(build("wow_forever","F-1",false)));
        assertEquals(List.of(retail),m.state().builds());assertNull(m.state().selected());assertFalse(m.state().busy());
    }
    @Test void completionIsAppliedOnlyThroughPresentationExecutor(){
        var service=new Service();Queue<Runnable> ui=new ArrayDeque<>();var m=new DiscoveryPresentationModel(service,ui::add,s->{},false);
        m.refresh("wow","us");service.requests.remove().complete(List.of(build("wow","R-2",false)));
        assertTrue(m.state().busy());ui.remove().run();assertFalse(m.state().busy());
    }
    @Test void invalidNewSubmissionRetiresPreviousRequest(){
        var service=new Service();var m=new DiscoveryPresentationModel(service,Runnable::run,s->{},false);
        m.refresh("wow","us");var old=service.requests.remove();m.refresh("invalid path/","us");
        old.complete(List.of(build("wow","R-2",false)));
        assertFalse(m.state().error().isBlank());assertTrue(m.state().builds().isEmpty());
    }
    @Test void stoppedRequestCannotOverwriteFixtureMode(){
        var service=new Service();var m=new DiscoveryPresentationModel(service,Runnable::run,s->{},false);
        m.refresh("wow","us");var old=service.requests.remove();m.stopWaiting();m.showFixtures();
        old.completeExceptionally(new IllegalStateException("old failure"));
        assertTrue(m.state().fixtureMode());assertEquals("DEMO-105",m.state().builds().getFirst().versionLabel());assertTrue(m.state().error().isBlank());
    }
    @Test void selectedIdentityMustBeMemberOfCurrentResults(){
        var m=new DiscoveryPresentationModel(new Service(),Runnable::run,s->{},true);m.showFixtures();
        m.select(build("wow","OTHER",false));assertNull(m.state().selected());
        var selected=m.state().builds().getFirst();m.select(selected);assertEquals(selected,m.state().selected());
    }
    @Test void closedViewDoesNotReceiveLateResults(){
        var service=new Service();var observed=new ArrayList<DiscoveryPresentationModel.State>();
        var m=new DiscoveryPresentationModel(service,Runnable::run,observed::add,false);m.refresh("wow","us");var old=service.requests.remove();m.close();
        int before=observed.size();old.complete(List.of(build("wow","R-2",false)));assertEquals(before,observed.size());
    }
    @Test void emptyResultsAreNotInventedAndFailureCanRetry(){
        var service=new Service();var m=new DiscoveryPresentationModel(service,Runnable::run,s->{},false);
        m.refresh("wow","us");service.requests.remove().completeExceptionally(new IllegalArgumentException("details not public"));assertFalse(m.state().error().isBlank());
        m.refresh("wow","us");service.requests.remove().complete(List.of());assertTrue(m.state().error().isBlank());assertTrue(m.state().message().contains("No published builds"));
    }
}
