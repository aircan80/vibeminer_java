package io.vibeminer.domain;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceEvidenceTest {
    @Test void capturedBytesAreImmutableAndTamperedEvidenceIsRejected() {
        var bytes = new byte[]{1,2,3};
        var document = new SourceDocument("https://example.test/metadata", Instant.EPOCH, bytes);
        bytes[0] = 9;
        var read = document.bytes(); read[1] = 9;
        assertArrayEquals(new byte[]{1,2,3}, document.bytes());
        assertThrows(IllegalArgumentException.class, () -> new SourceDocument(document.sourceUrl(), document.capturedAt(),
            document.sha256(), new byte[]{9,2,3}));
    }
    @Test void productConfigurationAndUpstreamBuildIdentityRemainDistinct() {
        var first = FixtureBuild.withEvidence("1".repeat(32), "42");
        var second = FixtureBuild.withEvidence("2".repeat(32), "42");
        var third = FixtureBuild.withEvidence("1".repeat(32), "43");
        assertNotEquals(first.snapshotId(), second.snapshotId());
        assertNotEquals(first.snapshotId(), third.snapshotId());
        var workspace = Workspace.create(first, "enUS", "Original", Instant.EPOCH);
        assertEquals(first, workspace.renamed("New name").build());
        assertEquals(workspace.id(), workspace.renamed("New name").id());
        assertNotEquals(workspace.id(), Workspace.create(first, "deDE", "German", Instant.EPOCH).id());
    }
    private static class FixtureBuild {
        static BuildSnapshot withEvidence(String productConfig, String upstreamId) {
            return new BuildSnapshot("wow", "Unverified edition", "Published", "us", "DEMO-1", "a".repeat(32),
                "b".repeat(32), Instant.EPOCH, true, new BuildEvidence(upstreamId, productConfig, List.of()));
        }
    }
}
