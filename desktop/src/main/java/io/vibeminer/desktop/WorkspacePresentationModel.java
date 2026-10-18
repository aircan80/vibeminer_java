package io.vibeminer.desktop;

import io.vibeminer.application.WorkspaceService;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** UI-only catalog state. Writes are serialized; persistence and identity belong to the application. */
public final class WorkspacePresentationModel implements AutoCloseable {
    public record State(List<Workspace> workspaces, boolean busy, String message, String error) {
        public State { workspaces=List.copyOf(workspaces); }
    }
    private final WorkspaceService service;
    private final Executor presentationExecutor;
    private final Consumer<State> observer;
    private State state=new State(List.of(),false,"Saved metadata workspaces have not been loaded.","");
    private long generation;
    private boolean closed;
    public WorkspacePresentationModel(WorkspaceService service,Executor executor,Consumer<State> observer){
        this.service=Objects.requireNonNull(service);this.presentationExecutor=Objects.requireNonNull(executor);this.observer=Objects.requireNonNull(observer);
    }
    public State state(){return state;}
    public void load(){
        run(service::list,rows->new State(rows,false,rows.isEmpty()?"No saved metadata workspaces yet.":"Saved metadata is available without discovery.",""),"Loading saved metadata…");
    }
    public void save(BuildSnapshot build,String locale,String name){
        if(closed||state.busy())return;
        try {
            String valid=Workspace.validateName(name);
            if(build==null||locale==null||!locale.matches("[a-z]{2}[A-Z]{2}"))throw new IllegalArgumentException("Choose an exact build and a valid locale such as enUS.");
            run(()->service.save(build,locale,valid),saved->new State(upsert(saved),false,"Saved metadata workspace “"+saved.name()+"” / "+saved.locale()+". Reopen it from the saved list to switch locale; the current view is unchanged. No game content was downloaded.",""),"Saving exact source metadata…");
        } catch(IllegalArgumentException failure){invalid(failure.getMessage());}
    }
    public void rename(String id,String name){
        if(closed||state.busy())return;
        try {String valid=Workspace.validateName(name);run(()->service.rename(id,valid),saved->new State(upsert(saved),false,"Workspace label updated. Source identity is unchanged.",""),"Updating label…");}
        catch(IllegalArgumentException failure){invalid(failure.getMessage());}
    }
    public void remove(String id){
        run(()->service.remove(id),ignored->new State(state.workspaces().stream().filter(w->!w.id().equals(id)).toList(),false,"Saved workspace membership removed. No game payload was deleted.",""),"Removing saved metadata membership…");
    }
    private List<Workspace> upsert(Workspace saved){var list=new ArrayList<>(state.workspaces());int i=-1;for(int n=0;n<list.size();n++)if(list.get(n).id().equals(saved.id()))i=n;if(i<0)list.add(saved);else list.set(i,saved);return list;}
    private <T> void run(Supplier<CompletionStage<T>> request,java.util.function.Function<T,State> complete,String message){
        if(closed||state.busy())return;
        long epoch=++generation;publish(new State(state.workspaces(),true,message,""));
        try {request.get().whenComplete((result,error)->presentationExecutor.execute(()->{
            if(closed||epoch!=generation)return;
            if(error!=null)failed();else publish(complete.apply(result));
        }));}catch(RuntimeException failure){failed();}
    }
    private void failed(){publish(new State(state.workspaces(),false,"The storage result could not be confirmed.","Could not confirm the workspace operation. Refresh the saved list before retrying; the currently displayed metadata remains available."));}
    private void invalid(String text){publish(new State(state.workspaces(),false,state.message(),text));}
    private void publish(State next){state=next;observer.accept(next);}
    @Override public void close(){closed=true;generation++;}
}
