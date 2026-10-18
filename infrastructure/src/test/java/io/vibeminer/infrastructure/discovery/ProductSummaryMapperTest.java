package io.vibeminer.infrastructure.discovery;

import io.vibeminer.domain.SourceDocument;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductSummaryMapperTest {
    private SourceDocument source(String rows) {
        return new SourceDocument("https://us.version.battle.net/v2/summary", Instant.EPOCH,
            ("Product!STRING:0|Seqn!DEC:4|Flags!STRING:0\n" + rows).getBytes(StandardCharsets.UTF_8));
    }
    @Test void mergePointerRowsRetainsRawProductsUnknownFlagsAndOneSourceObservation() {
        var source = source("wowf|12|cdn\nwowf|13|\nwowf|14|bgdl\nwowf|15|future\nagent_beta|2|cdn\n");
        var result = new ProductSummaryMapper().map(source);
        assertEquals(2, result.products().size()); assertEquals("agent_beta", result.products().getFirst().productId());
        var product = result.products().getLast();
        assertEquals("wowf", product.productId()); assertTrue(product.hasCurrentVersions());
        assertTrue(product.hasAdvertisedPreload()); assertEquals(4, product.pointers().size());
        assertEquals(source, result.evidence());
    }
    @Test void cdnOnlyRemainsVisibleWithoutInventedBuildIdentity() {
        var product = new ProductSummaryMapper().map(source("wow_unknown|500|cdn\n")).products().getFirst();
        assertFalse(product.hasCurrentVersions()); assertFalse(product.hasAdvertisedPreload());
        assertTrue(product.hasCdnMetadata()); assertEquals("500", product.pointers().getFirst().sequence());
    }
    @Test void exactDuplicateRowsMergeButConflictingPointerSequencesFailClosed() {
        assertEquals(1, new ProductSummaryMapper().map(source("wow|42|\nwow|42|\n")).products().getFirst().pointers().size());
        assertThrows(MetadataFormatException.class, () -> new ProductSummaryMapper().map(source("wow|42|\nwow|43|\n")));
    }
    @Test void invalidIdentifiersSequencesAndMissingColumnsAreRejected() {
        assertThrows(MetadataFormatException.class, () -> new ProductSummaryMapper().map(source("../wow|42|\n")));
        assertThrows(MetadataFormatException.class, () -> new ProductSummaryMapper().map(source("wow|not-build|\n")));
        assertThrows(MetadataFormatException.class, () -> new ProductSummaryMapper().map(new SourceDocument(
            "https://us.version.battle.net/v2/summary", Instant.EPOCH, "Product!STRING:0\nwow\n".getBytes(StandardCharsets.UTF_8))));
        assertTrue(new ProductSummaryMapper().map(source("")).products().isEmpty());
    }
}
