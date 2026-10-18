package io.vibeminer.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObjectIdentityTest {
    @Test void physicalAndExpectedSourceDigestsHaveExplicitDifferentAlgorithms() {
        var physical = new ObjectDigest("A".repeat(64));
        assertEquals("a".repeat(64), physical.value());
        var source = new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, "B".repeat(32), 7);
        assertEquals("b".repeat(32), source.hexDigest());
        assertEquals(ChecksumAlgorithm.MD5, source.algorithm());
        assertNotEquals(physical.value(), source.hexDigest());
    }
    @Test void invalidPathsHashesAndLengthsCannotBecomeObjectIdentities() {
        for (var invalid : new String[]{"../file", "g".repeat(64), "a".repeat(32), ""}) {
            assertThrows(IllegalArgumentException.class, () -> new ObjectDigest(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, "a".repeat(64), 1));
        assertThrows(IllegalArgumentException.class, () -> new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, "a".repeat(64), -1));
        assertThrows(IllegalArgumentException.class, () -> new StoredObject(new ObjectDigest("a".repeat(64)), -1));
    }
}
