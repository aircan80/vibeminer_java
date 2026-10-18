package io.vibeminer.application;

import io.vibeminer.domain.ProductCatalogue;
import java.util.concurrent.CompletionStage;

/** Explicit refresh of raw official product pointers. Construction and UI startup perform no refresh. */
@FunctionalInterface
public interface ProductDiscoveryService {
    CompletionStage<ProductCatalogue> refreshProducts();
}
