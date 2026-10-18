package io.vibeminer.desktop;

import io.vibeminer.application.ProductDiscoveryService;
import io.vibeminer.domain.ProductCatalogue;
import io.vibeminer.domain.PublishedProduct;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Published pointer state, separate from selected exact-build workspaces and content availability. */
public final class ProductPresentationModel implements AutoCloseable {
    public record State(ProductCatalogue catalogue,boolean busy,String message,String error) {}
    private final ProductDiscoveryService service;
    private final Executor executor;
    private final Consumer<State> observer;
    private State state=new State(null,false,"Refresh products to discover published raw identifiers. No network request has been made.","");
    private long epoch;
    private boolean closed;
    public ProductPresentationModel(ProductDiscoveryService service,Executor executor,Consumer<State> observer){this.service=Objects.requireNonNull(service);this.executor=Objects.requireNonNull(executor);this.observer=Objects.requireNonNull(observer);}
    public State state(){return state;}
    public List<PublishedProduct> matching(String query){
        if(state.catalogue()==null)return List.of();
        String filter=query==null?"":query.strip().toLowerCase(Locale.ROOT);
        return state.catalogue().products().stream().filter(p->p.productId().toLowerCase(Locale.ROOT).contains(filter)).sorted(Comparator.comparing(PublishedProduct::productId)).toList();
    }
    public boolean canRequestCurrent(String productId){return matching("").stream().anyMatch(p->p.productId().equals(productId)&&p.hasCurrentVersions());}
    public void refresh(){
        if(closed)return;
        long request=++epoch;publish(new State(state.catalogue(),true,"Checking the official product summary. No build content is being downloaded.",""));
        try{service.refreshProducts().whenComplete((catalogue,error)->executor.execute(()->{
            if(closed||request!=epoch)return;
            if(error!=null||catalogue==null)failed();else publish(new State(catalogue,false,"Published pointers loaded. Product presence does not establish readable content or a supported decoder.",""));
        }));}catch(RuntimeException failure){failed();}
    }
    public void stopWaiting(){if(closed)return;epoch++;publish(new State(state.catalogue(),false,"Stopped waiting. Late summary results will not be applied; the underlying request may continue.",""));}
    private void failed(){publish(new State(state.catalogue(),false,"Product summary was not refreshed. Any prior catalog still refers to its displayed capture time.","Could not load the official product summary. Check your connection and retry."));}
    private void publish(State next){state=next;observer.accept(next);}
    @Override public void close(){closed=true;epoch++;}
}
