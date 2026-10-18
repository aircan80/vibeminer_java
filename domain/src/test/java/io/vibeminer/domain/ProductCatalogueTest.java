package io.vibeminer.domain;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductCatalogueTest {
    @Test void pointerKindsArePublicationCapabilitiesRatherThanBuildNumbers() {
        var product = new PublishedProduct("wowf", List.of(new ProductPointer("", "42"), new ProductPointer("cdn", "43"),
            new ProductPointer("bgdl", "44"), new ProductPointer("new-capability", "45")));
        assertTrue(product.hasCurrentVersions()); assertTrue(product.hasCdnMetadata()); assertTrue(product.hasAdvertisedPreload());
        assertEquals(ProductPointer.Kind.UNKNOWN, product.pointers().getLast().kind());
        assertEquals("wowf", product.productId());
        assertThrows(UnsupportedOperationException.class, product.pointers()::clear);
    }
    @Test void cdnOnlyDoesNotClaimPublishedBuildOrPreloadAndInputsAreBounded() {
        var product = new PublishedProduct("unmapped_product", List.of(new ProductPointer("cdn", "42")));
        assertFalse(product.hasCurrentVersions()); assertFalse(product.hasAdvertisedPreload());
        assertThrows(IllegalArgumentException.class, () -> new ProductPointer("", "build-42"));
        assertThrows(IllegalArgumentException.class, () -> new PublishedProduct("../wow", product.pointers()));
        assertThrows(IllegalArgumentException.class, () -> new PublishedProduct("wow", List.of(new ProductPointer("", "1"), new ProductPointer("", "2"))));
    }
}
