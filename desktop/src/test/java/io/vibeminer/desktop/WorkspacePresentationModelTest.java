package io.vibeminer.desktop;

import io.vibeminer.application.WorkspaceService;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.Workspace;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspacePresentationModelTest {
    static BuildSnapshot build(String version){return new BuildSnapshot("wowt","Retail","PTR","us",version,"a".repeat(32),"b".repeat(32),Instant.EPOCH,true);}
    static Workspace workspace(String id,String version,String locale){return Workspace.create(build(version),locale,"Research "+version,Instant.EPOCH);}
    static final class Fake implements WorkspaceService {
        CompletableFuture<List<Workspace>> listing=new CompletableFuture<>();
        CompletableFuture<Workspace> saving=new CompletableFuture<>();
        CompletableFuture<Workspace> renaming=new CompletableFuture<>();
        CompletableFuture<Void> removing=new CompletableFuture<>();
        BuildSnapshot savedBuild;String savedLocale,savedName,removedId;
        public CompletionStage<List<Workspace>> list(){return listing;}
        public CompletionStage<Workspace> save(BuildSnapshot b,String l,String n){savedBuild=b;savedLocale=l;savedName=n;return saving;}
        public CompletionStage<Workspace> rename(String id,String name){return renaming;}
        public CompletionStage<Void> remove(String id){removedId=id;return removing;}
    }
    @Test void savedWorkspacesLoadWithoutInventingSelectionOrNetwork(){var f=new Fake();var m=new WorkspacePresentationModel(f,Runnable::run,s->{});m.load();assertTrue(m.state().busy());var w=workspace("a","OLD","deDE");f.listing.complete(List.of(w));assertEquals(List.of(w),m.state().workspaces());assertFalse(m.state().busy());}
    @Test void saveUsesCapturedExactBuildAndLocale(){var f=new Fake();var m=new WorkspacePresentationModel(f,Runnable::run,s->{});var b=build("OLD");m.save(b,"frFR","  First look  ");assertEquals(b,f.savedBuild);assertEquals("frFR",f.savedLocale);assertEquals("First look",f.savedName);var w=workspace("a","OLD","frFR");f.saving.complete(w);assertEquals(List.of(w),m.state().workspaces());}
    @Test void differentBuildLocaleMembershipsRemainSeparate(){var f=new Fake();var m=new WorkspacePresentationModel(f,Runnable::run,s->{});m.load();var a=workspace("a","OLD","enUS");f.listing.complete(List.of(a));m.save(build("NEW"),"deDE","Second");var b=workspace("b","NEW","deDE");f.saving.complete(b);assertEquals(List.of(a,b),m.state().workspaces());}
    @Test void failedRemovalPreservesExactSavedMembership(){var f=new Fake();var m=new WorkspacePresentationModel(f,Runnable::run,s->{});m.load();var w=workspace("a","OLD","enUS");f.listing.complete(List.of(w));m.remove(w.id());f.removing.completeExceptionally(new RuntimeException("private path"));assertEquals(List.of(w),m.state().workspaces());assertFalse(m.state().error().contains("private path"));assertFalse(m.state().error().isBlank());}
    @Test void successfulRemovalOnlyChangesNamedMembership(){var f=new Fake();var m=new WorkspacePresentationModel(f,Runnable::run,s->{});m.load();var a=workspace("a","OLD","enUS");var b=workspace("b","NEW","enUS");f.listing.complete(List.of(a,b));m.remove(a.id());f.removing.complete(null);assertEquals(a.id(),f.removedId);assertEquals(List.of(b),m.state().workspaces());}
    @Test void mutationCannotRaceAnotherWrite(){var f=new Fake();var m=new WorkspacePresentationModel(f,Runnable::run,s->{});m.save(build("OLD"),"enUS","First");m.remove("anything");assertNull(f.removedId);assertTrue(m.state().busy());}
    @Test void closedModelIgnoresLateSaveCompletion(){var f=new Fake();var notifications=new ArrayList<WorkspacePresentationModel.State>();var m=new WorkspacePresentationModel(f,Runnable::run,notifications::add);m.save(build("OLD"),"enUS","First");m.close();int before=notifications.size();f.saving.complete(workspace("a","OLD","enUS"));assertEquals(before,notifications.size());}
    @Test void malformedInputDoesNotReachPersistence(){var f=new Fake();var m=new WorkspacePresentationModel(f,Runnable::run,s->{});m.save(build("OLD"),"invalid"," ");assertNull(f.savedBuild);assertFalse(m.state().error().isBlank());assertFalse(m.state().busy());}
    @Test void renameKeepsSourceAndLocaleFromReturnedWorkspace(){var f=new Fake();var m=new WorkspacePresentationModel(f,Runnable::run,s->{});m.load();var old=workspace("a","OLD","deDE");f.listing.complete(List.of(old));m.rename(old.id(),"Renamed");var changed=new Workspace(old.id(),old.build(),old.locale(),"Renamed",old.savedAt());f.renaming.complete(changed);assertEquals(List.of(changed),m.state().workspaces());}
    @Test void callbackWaitsForPresentationExecutor(){var f=new Fake();Queue<Runnable> ui=new ArrayDeque<>();var m=new WorkspacePresentationModel(f,ui::add,s->{});m.load();f.listing.complete(List.of(workspace("a","OLD","enUS")));assertTrue(m.state().busy());ui.remove().run();assertFalse(m.state().busy());}
}
