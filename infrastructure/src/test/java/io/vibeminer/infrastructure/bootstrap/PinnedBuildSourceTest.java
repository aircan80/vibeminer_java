package io.vibeminer.infrastructure.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.domain.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PinnedBuildSourceTest {
    private BuildSnapshot source(String hosts, String servers) {
        var original = BootstrapWorkflowIntegrationTest.build("synthetic config".getBytes(StandardCharsets.UTF_8));
        String cdns = "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\nus|tpr/wow|" + hosts + "|" + servers + "|tpr/configs/data\n";
        var evidence = new BuildEvidence("", "", List.of(original.evidence().documents().getFirst(),
                new SourceDocument(original.evidence().documents().getLast().sourceUrl(), original.observedAt(), cdns.getBytes(StandardCharsets.UTF_8))));
        return new BuildSnapshot(original.productId(), original.editionLabel(), original.channel(), original.region(), original.versionLabel(), original.buildConfig(), original.cdnConfig(), original.observedAt(), false, evidence);
    }
    @Test void realisticAdvertisementPrefersExplicitDirectHttpsAndDoesNotUpgradeLegacyFirstHost() {
        var build = source("level3.blizzard.com us.cdn.blizzard.com", "http://level3.blizzard.com/?maxhosts=8 http://us.cdn.blizzard.com/?maxhosts=4&fallback=1 https://level3.ssl.blizzard.com/?maxhosts=4&fallback=1 https://us.cdn.blizzard.com/?maxhosts=4&fallback=1");
        var resolved = PinnedBuildSource.resolve(build);
        assertEquals("us.cdn.blizzard.com", resolved.host());
        assertEquals("tpr/configs/data", resolved.configPath());
        assertFalse(resolved.advertisedHosts().contains("level3.blizzard.com"));
    }
    @Test void explicitSslAliasMayDifferFromLegacyHostsField() {
        var resolved = PinnedBuildSource.resolve(source("level3.blizzard.com", "http://level3.blizzard.com/?maxhosts=8 https://level3.ssl.blizzard.com/?maxhosts=8&fallback=1"));
        assertEquals("level3.ssl.blizzard.com", resolved.host());
    }
    @Test void absentOrUnsafeHttpsRoutesFailExplicitlyWithoutGuessing() {
        for (String endpoint : List.of("http://us.cdn.blizzard.com/", "https://evil.example/", "https://user@us.cdn.blizzard.com/", "https://us.cdn.blizzard.com:444/", "https://us.cdn.blizzard.com/other", "https://us.cdn.blizzard.com/?redirect=evil", "https://us.cdn.blizzard.com/#fragment")) {
            var failure = assertThrows(BootstrapException.class, () -> PinnedBuildSource.resolve(source("us.cdn.blizzard.com", endpoint)), endpoint);
            assertEquals(BootstrapException.Reason.UNSUPPORTED, failure.reason(), endpoint);
        }
    }
    @Test void retainedProofRequiresCanonicalOfficialConfigRouteAndExactKey() {
        String key = "a".repeat(32);
        PinnedBuildSource.validateConfigurationProof("https://level3.ssl.blizzard.com/tpr/wow/config/aa/aa/" + key, key);
        for (String url : List.of("https://evil.example/fake", "https://us.cdn.blizzard.com/tpr/wow/data/aa/aa/" + key,
                "https://us.cdn.blizzard.com/tpr/wow/config/aa/aa/" + "b".repeat(32), "https://user@us.cdn.blizzard.com/tpr/wow/config/aa/aa/" + key))
            assertEquals(BootstrapException.Reason.INTEGRITY, assertThrows(BootstrapException.class, () -> PinnedBuildSource.validateConfigurationProof(url, key)).reason());
    }
}
