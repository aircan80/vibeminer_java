package io.vibeminer.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class BuildSnapshotTest {
    private BuildSnapshot build(String product, String config, Instant time) {
        return new BuildSnapshot(product, "Test edition", "PTR", "us", "DEMO-105", config,
                "22222222222222222222222222222222", time, true);
    }
    @Test void changedConfigOrProductCannotAliasSameDisplayedVersion() {
        var first = build("wowt", "11111111111111111111111111111111", Instant.EPOCH);
        assertNotEquals(first.snapshotId(), build("wow", first.buildConfig(), Instant.EPOCH).snapshotId());
        assertNotEquals(first.snapshotId(), build("wowt", "33333333333333333333333333333333", Instant.EPOCH).snapshotId());
        assertEquals(first.snapshotId(), build("wowt", first.buildConfig(), Instant.now()).snapshotId());
    }
    @Test void channelIsPartOfPinnedContextButHumanLabelIsNot() {
        var first = build("wowt", "11111111111111111111111111111111", Instant.EPOCH);
        var preload = new BuildSnapshot(first.productId(), first.editionLabel(), "Preload", first.region(),
            first.versionLabel(), first.buildConfig(), first.cdnConfig(), first.observedAt(), true);
        var renamed = new BuildSnapshot(first.productId(), "Renamed display", first.channel(), first.region(),
            first.versionLabel(), first.buildConfig(), first.cdnConfig(), first.observedAt(), true);
        assertNotEquals(first.snapshotId(), preload.snapshotId());
        assertEquals(first.snapshotId(), renamed.snapshotId());
    }
    @Test void malformedIdentityAndAmbiguousFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> build("wowt", "latest", Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> build("wow\nt", "11111111111111111111111111111111", Instant.EPOCH));
    }
}
