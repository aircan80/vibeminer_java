package io.vibeminer.desktop;

import io.vibeminer.application.ProductDiscoveryService;
import io.vibeminer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductPresentationModelTest {
    static ProductCatalogue catalogue(){return new ProductCatalogue(List.of(
            new PublishedProduct("wowt",List.of(new ProductPointer("","41"),new ProductPointer("cdn","42"))),
            new PublishedProduct("cdn_only",List.of(new ProductPointer("cdn","10"))),
            new PublishedProduct("future_product",List.of(new ProductPointer("bgdl","77"),new ProductPointer("newflag","1")))),
            new SourceDocument("https://us.version.battle.net/v2/summary",Instant.EPOCH,"fixture summary".getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    static final class Fake implements ProductDiscoveryService {
        final Queue<CompletableFuture<ProductCatalogue>> requests=new ArrayDeque<>();
        public CompletionStage<ProductCatalogue> refreshProducts(){var f=new CompletableFuture<ProductCatalogue>();requests.add(f);return f;}
    }
    @Test void startsEmptyAndMakesNoAutomaticRequest(){var f=new Fake();var m=new ProductPresentationModel(f,Runnable::run,s->{});assertNull(m.state().catalogue());assertTrue(m.matching("").isEmpty());assertTrue(f.requests.isEmpty());}
    @Test void catalogueIncludesUnknownAndCdnOnlyIdsWithoutInventedLabels(){var f=new Fake();var m=new ProductPresentationModel(f,Runnable::run,s->{});m.refresh();f.requests.remove().complete(catalogue());assertEquals(List.of("cdn_only","future_product","wowt"),m.matching("").stream().map(PublishedProduct::productId).toList());assertEquals("newflag",m.matching("future").getFirst().pointers().getLast().flag());}
    @Test void onlyCurrentPointerEnablesCurrentBuildRequest(){var f=new Fake();var m=new ProductPresentationModel(f,Runnable::run,s->{});m.refresh();f.requests.remove().complete(catalogue());assertTrue(m.canRequestCurrent("wowt"));assertFalse(m.canRequestCurrent("cdn_only"));assertFalse(m.canRequestCurrent("future_product"));assertFalse(m.canRequestCurrent("missing"));}
    @Test void filteringIsCaseInsensitiveAndDoesNotChangeSourceEvidence(){var f=new Fake();var m=new ProductPresentationModel(f,Runnable::run,s->{});m.refresh();var c=catalogue();f.requests.remove().complete(c);assertEquals("wowt",m.matching("WoW").getFirst().productId());assertTrue(m.matching("none").isEmpty());assertEquals(c.evidence(),m.state().catalogue().evidence());}
    @Test void outdatedRefreshCannotReplaceNewerEvidence(){var f=new Fake();var m=new ProductPresentationModel(f,Runnable::run,s->{});m.refresh();var old=f.requests.remove();m.refresh();var latest=f.requests.remove();latest.complete(catalogue());old.completeExceptionally(new IllegalStateException("old error"));assertEquals(catalogue(),m.state().catalogue());assertTrue(m.state().error().isBlank());}
    @Test void stopWaitingSuppressesLateCatalogAndDescribesScope(){var f=new Fake();var m=new ProductPresentationModel(f,Runnable::run,s->{});m.refresh();var pending=f.requests.remove();m.stopWaiting();pending.complete(catalogue());assertNull(m.state().catalogue());assertFalse(m.state().busy());assertTrue(m.state().message().contains("Stopped waiting"));}
    @Test void failureRetainsLastSuccessfulCatalogueWithError(){var f=new Fake();var m=new ProductPresentationModel(f,Runnable::run,s->{});m.refresh();f.requests.remove().complete(catalogue());m.refresh();f.requests.remove().completeExceptionally(new IllegalStateException("private detail"));assertNotNull(m.state().catalogue());assertFalse(m.state().error().isBlank());assertFalse(m.state().error().contains("private detail"));}
    @Test void completionMarshalsAndClosedViewIgnoresIt(){var f=new Fake();Queue<Runnable> ui=new ArrayDeque<>();var m=new ProductPresentationModel(f,ui::add,s->{});m.refresh();f.requests.remove().complete(catalogue());assertTrue(m.state().busy());m.close();ui.remove().run();assertNull(m.state().catalogue());}
}
