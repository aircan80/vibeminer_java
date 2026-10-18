package io.vibeminer.desktop;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.bootstrap.InstallInspection;
import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.domain.BuildSnapshot;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Presentation of one exact build's install-list scope; all acquisition and verification are use cases. */
public final class InstallPresentationModel implements AutoCloseable {
    public enum Stage { NO_BUILD, UNCHECKED, CHECKING, PLANNED, DOWNLOADING, REOPENING, RETAINED, FAILED, CANCELLED }
    public record State(BuildSnapshot build,Stage stage,InstallPlan plan,InstallInspection inspection,
                        String message,BootstrapException.Reason reason) {
        public boolean busy(){return stage==Stage.CHECKING||stage==Stage.DOWNLOADING||stage==Stage.REOPENING;}
    }
    private final BootstrapService service;
    private final Executor executor;
    private final Consumer<State> observer;
    private State state=new State(null,Stage.NO_BUILD,null,null,"Choose an exact build to inspect its installation file list.",null);
    private CompletableFuture<?> pending;
    private long generation;
    private boolean closed;
    public InstallPresentationModel(BootstrapService service,Executor executor,Consumer<State> observer){this.service=Objects.requireNonNull(service);this.executor=Objects.requireNonNull(executor);this.observer=Objects.requireNonNull(observer);}
    public State state(){return state;}
    public void selectBuild(BuildSnapshot build){
        if(closed)return;
        if(state.build()!=null&&build!=null&&state.build().snapshotId().equals(build.snapshotId()))return;
        retire();publish(new State(build,build==null?Stage.NO_BUILD:Stage.UNCHECKED,null,null,
                build==null?"Choose an exact build to inspect its installation file list.":"Not checked. Configuration and retained-list actions are explicit; no network request has been made.",null));
    }
    public void check(){
        if(!ready())return;BuildSnapshot build=state.build();
        run(Stage.CHECKING,null,"Checking this build's configuration. No install list or game files are being downloaded.",()->service.inspectConfiguration(build),plan->{
            if(plan==null||!sameBuild(plan.build())){scopeFailure();return;}
            publish(new State(state.build(),Stage.PLANNED,plan,state.inspection(),"Install-list plan ready. Review its cost before choosing Download list.",null));
        });
    }
    public void download(){
        if(!ready())return;
        if(state.plan()==null){publish(new State(state.build(),Stage.UNCHECKED,null,state.inspection(),"Check the install list first to review the exact download scope and cost.",null));return;}
        InstallPlan plan=state.plan();run(Stage.DOWNLOADING,plan,"Obtaining and checking the list. No listed file contents are downloaded.",()->service.download(plan),inspection->{
            if(inspection==null||!samePlan(plan,inspection.plan())){scopeFailure();return;}
            acceptInspection(inspection);
        });
    }
    public void reopen(){if(!ready())return;BuildSnapshot build=state.build();run(Stage.REOPENING,state.plan(),"Verifying the retained list locally. This operation makes no network request.",()->service.reopen(build),this::acceptInspection);}
    private void acceptInspection(InstallInspection inspection){
        if(inspection==null||!sameBuild(inspection.plan().build())){scopeFailure();return;}
        publish(new State(state.build(),Stage.RETAINED,inspection.plan(),inspection,"Verified install list retained · "+inspection.entries().size()+" entries. Listed game files are not acquired by this operation.",null));
    }
    public List<InstallInspection.Entry> matching(String query){
        if(state.inspection()==null)return List.of();String q=query==null?"":query.strip().toLowerCase(Locale.ROOT);
        return state.inspection().entries().stream().filter(e->e.name().toLowerCase(Locale.ROOT).contains(q)||e.tags().stream().anyMatch(t->t.name().toLowerCase(Locale.ROOT).contains(q))).toList();
    }
    public void cancel(){
        if(closed||!state.busy())return;retire();publish(new State(state.build(),Stage.CANCELLED,state.plan(),state.inspection(),"Cancelled this view's request. Other consumers may continue; no unverified list was opened.",BootstrapException.Reason.CANCELLED));
    }
    private boolean ready(){return !closed&&state.build()!=null&&!state.busy();}
    private boolean sameBuild(BuildSnapshot b){return b!=null&&state.build()!=null&&b.snapshotId().equals(state.build().snapshotId());}
    private boolean samePlan(InstallPlan expected,InstallPlan actual){return actual!=null&&sameBuild(actual.build())&&expected.contentKey().equals(actual.contentKey())&&expected.encodingKey().equals(actual.encodingKey())&&expected.encodedBytes()==actual.encodedBytes()&&expected.decodedBytes()==actual.decodedBytes();}
    private void scopeFailure(){publish(new State(state.build(),Stage.FAILED,null,state.inspection(),"The returned list does not match this exact source. No new result was opened.",BootstrapException.Reason.INTEGRITY));}
    private <T> void run(Stage stage,InstallPlan plan,String message,Supplier<CompletionStage<T>> action,Consumer<T> success){
        long request=++generation;publish(new State(state.build(),stage,plan,state.inspection(),message,null));
        try{CompletionStage<T> result=action.get();pending=result.toCompletableFuture();result.whenComplete((value,error)->executor.execute(()->{
            if(closed||request!=generation)return;pending=null;
            if(error!=null)failed(error);else success.accept(value);
        }));}catch(RuntimeException failure){pending=null;failed(failure);}
    }
    private void failed(Throwable error){
        Throwable cause=error;for(int i=0;i<8&&(cause instanceof java.util.concurrent.CompletionException||cause instanceof java.util.concurrent.ExecutionException)&&cause.getCause()!=null&&cause.getCause()!=cause;i++)cause=cause.getCause();
        BootstrapException.Reason reason=cause instanceof BootstrapException failure?failure.reason():cause instanceof CancellationException?BootstrapException.Reason.CANCELLED:null;
        String message=reason==null?"The install-list operation could not finish. Retry the explicit action.":switch(reason){
            case UNSUPPORTED->"This build's install list is not supported.";
            case UNAVAILABLE->"The CDN list is unavailable for this exact source.";
            case LIMIT->"The list exceeds supported limits.";
            case INTEGRITY->"The downloaded list did not pass verification.";
            case MALFORMED->"The list format could not be read.";
            case MISSING_LOCAL->"No verified retained list exists for this exact build. Connect and check the list to acquire it.";
            case CANCELLED->"Cancelled; no unverified list was opened.";
            case CAPACITY->"The app is busy. Try again shortly.";
            case CLOSED->"This workspace operation is no longer available.";
            case STORAGE->"App-owned storage could not be accessed.";
            case TRANSPORT->"The download could not finish. Check your connection and retry.";
        };
        publish(new State(state.build(),reason==BootstrapException.Reason.CANCELLED?Stage.CANCELLED:Stage.FAILED,state.plan(),state.stage()==Stage.REOPENING?null:state.inspection(),message,reason));
    }
    private void retire(){generation++;CompletableFuture<?> old=pending;pending=null;if(old!=null)old.cancel(true);}
    private void publish(State next){state=next;observer.accept(next);}
    @Override public void close(){if(!closed){closed=true;retire();}}
}
