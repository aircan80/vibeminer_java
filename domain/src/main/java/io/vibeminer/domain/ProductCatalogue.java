package io.vibeminer.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One captured official summary observation; advertised pointers do not prove content availability. */
public record ProductCatalogue(List<PublishedProduct> products, SourceDocument evidence) {
    public ProductCatalogue {
        products = List.copyOf(products);
        Objects.requireNonNull(evidence);
        if (products.size() > 10_000) throw new IllegalArgumentException("Product catalogue exceeds supported limit");
        var ids = new HashSet<String>();
        for (var product : products) if (!ids.add(product.productId())) throw new IllegalArgumentException("Duplicate product identity");
    }
}
