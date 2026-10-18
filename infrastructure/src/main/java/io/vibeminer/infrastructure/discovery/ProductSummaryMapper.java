package io.vibeminer.infrastructure.discovery;

import io.vibeminer.domain.ProductCatalogue;
import io.vibeminer.domain.ProductPointer;
import io.vibeminer.domain.PublishedProduct;
import io.vibeminer.domain.SourceDocument;
import java.util.TreeMap;

/** Pure mapping of bounded summary bytes; no transport, persistence or marketing-name inference. */
public final class ProductSummaryMapper {
    public ProductCatalogue map(SourceDocument source) {
        var document = new BlizzardMetadataParser().parse(source.bytes());
        document.requireColumns("Product", "Seqn", "Flags");
        var grouped = new TreeMap<String, TreeMap<String, ProductPointer>>();
        try {
            for (var row : document.rows()) {
                var product = row.get("Product");
                var pointer = new ProductPointer(row.get("Flags"), row.get("Seqn"));
                var pointers = grouped.computeIfAbsent(product, ignored -> new TreeMap<>());
                var previous = pointers.putIfAbsent(pointer.flag(), pointer);
                if (previous != null && !previous.equals(pointer)) {
                    throw new MetadataFormatException("Conflicting publication sequences for one product pointer");
                }
            }
            return new ProductCatalogue(grouped.entrySet().stream()
                .map(entry -> new PublishedProduct(entry.getKey(), java.util.List.copyOf(entry.getValue().values())))
                .toList(), source);
        } catch (IllegalArgumentException invalid) {
            throw new MetadataFormatException("Invalid official product summary identity", invalid);
        }
    }
}
