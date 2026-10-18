package io.vibeminer.domain;

import java.util.HashSet;
import java.util.List;

/** Raw official product identity plus advertised pointer families; edition mapping is unresolved. */
public record PublishedProduct(String productId, List<ProductPointer> pointers) {
    public PublishedProduct {
        if (productId == null || !productId.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid official product identifier");
        pointers = List.copyOf(pointers);
        if (pointers.isEmpty() || pointers.size() > 64) throw new IllegalArgumentException("Unsupported product pointer count");
        var flags = new HashSet<String>();
        for (var pointer : pointers) if (!flags.add(pointer.flag())) throw new IllegalArgumentException("Ambiguous duplicate product pointer");
    }
    public boolean hasCurrentVersions() { return pointers.stream().anyMatch(p -> p.kind() == ProductPointer.Kind.CURRENT_VERSIONS); }
    public boolean hasCdnMetadata() { return pointers.stream().anyMatch(p -> p.kind() == ProductPointer.Kind.CDN_METADATA); }
    public boolean hasAdvertisedPreload() { return pointers.stream().anyMatch(p -> p.kind() == ProductPointer.Kind.BACKGROUND_DOWNLOAD); }
}
