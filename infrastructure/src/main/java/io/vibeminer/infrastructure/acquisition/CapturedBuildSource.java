package io.vibeminer.infrastructure.acquisition;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.infrastructure.discovery.BlizzardMetadataParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves only captured source observations for the selected exact build; no latest pointer I/O.
 */
public final class CapturedBuildSource {
    private CapturedBuildSource() {}

    public static CdnRequest.Source resolve(BuildSnapshot build) {
        try {
            return resolveCaptured(build);
        } catch (io.vibeminer.infrastructure.discovery.MetadataFormatException invalid) {
            throw new SourceContextException(
                    SourceContextException.Reason.MALFORMED,
                    "Captured build/CDN metadata is malformed",
                    invalid);
        }
    }

    private static CdnRequest.Source resolveCaptured(BuildSnapshot build) {
        String base = "https://us.version.battle.net/v2/products/" + build.productId();
        var versions =
                build.evidence().documents().stream()
                        .filter(d -> d.sourceUrl().equals(base + "/versions"))
                        .toList();
        var cdns =
                build.evidence().documents().stream()
                        .filter(d -> d.sourceUrl().equals(base + "/cdns"))
                        .toList();
        if (versions.size() != 1 || cdns.size() != 1)
            throw unsupported("Exact captured versions/CDN observations are required");
        var parser = new BlizzardMetadataParser();
        var published = parser.parse(versions.getFirst().bytes());
        published.requireColumns("Region", "BuildConfig", "CDNConfig", "VersionsName");
        var rows =
                published.rows().stream()
                        .filter(
                                row ->
                                        build.region().equals(row.get("Region"))
                                                && build.buildConfig()
                                                        .equalsIgnoreCase(row.get("BuildConfig"))
                                                && build.cdnConfig()
                                                        .equalsIgnoreCase(row.get("CDNConfig"))
                                                && build.versionLabel()
                                                        .equals(row.get("VersionsName"))
                                                && (build.evidence().upstreamBuildId().isEmpty()
                                                        || build.evidence()
                                                                .upstreamBuildId()
                                                                .equals(row.get("BuildId")))
                                                && (build.evidence().productConfig().isEmpty()
                                                        || build.evidence()
                                                                .productConfig()
                                                                .equalsIgnoreCase(
                                                                        row.get("ProductConfig"))))
                        .toList();
        if (rows.size() != 1)
            throw unsupported("Captured publication does not uniquely identify this exact build");
        var advertised = parser.parse(cdns.getFirst().bytes());
        advertised.requireColumns("Name", "Path", "Hosts", "ConfigPath");
        if (!advertised.columns().contains("Servers"))
            throw unsupported("Captured CDN metadata has no explicit HTTPS server advertisements");
        List<Map<String, String>> sources =
                advertised.rows().stream()
                        .filter(row -> build.region().equals(row.get("Name")))
                        .toList();
        if (sources.size() != 1) throw unsupported("Captured CDN source is absent or ambiguous");
        var row = sources.getFirst();
        var hosts = new ArrayList<String>();
        for (String advertisedServer : row.get("Servers").split(" +")) {
            try {
                URI server = URI.create(advertisedServer);
                if (!"https".equals(server.getScheme())
                        || server.getHost() == null
                        || server.getUserInfo() != null
                        || server.getPort() != -1
                        || server.getFragment() != null
                        || !(server.getRawPath().isEmpty() || server.getRawPath().equals("/")))
                    continue;
                String query = server.getRawQuery();
                if (query != null
                        && !query.matches(
                                "(?:maxhosts=[0-9]{1,3}|fallback=[01])(?:&(?:maxhosts=[0-9]{1,3}|fallback=[01]))*"))
                    continue;
                // Source validates the supported official host and captured Path/ConfigPath.
                new CdnRequest.Source(
                        server.getHost(),
                        row.get("Path"),
                        row.get("ConfigPath"),
                        List.of(server.getHost()));
                if (!hosts.contains(server.getHost())) hosts.add(server.getHost());
            } catch (IllegalArgumentException rejected) {
                /* Unsupported/malformed endpoint stays in captured raw evidence only. */
            }
        }
        // Prefer the direct official HTTPS endpoint used by our bounded live evidence, then the
        // advertised SSL alias.
        hosts.sort(
                Comparator.comparingInt(
                        host ->
                                host.equals(build.region() + ".cdn.blizzard.com")
                                        ? 0
                                        : host.equals("us.cdn.blizzard.com") ? 1 : 2));
        for (String host : hosts)
            return new CdnRequest.Source(host, row.get("Path"), row.get("ConfigPath"), hosts);
        throw unsupported("Captured CDN route/hosts are unsupported");
    }

    /** Passive proof URL validation; never authorizes a download from the proof itself. */
    public static void validateConfigurationProof(String url, String key) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme())
                    || !Set.of(
                                    "us.cdn.blizzard.com",
                                    "kr.cdn.blizzard.com",
                                    "level3.ssl.blizzard.com",
                                    "level3.blizzard.com")
                            .contains(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getRawQuery() != null
                    || uri.getFragment() != null
                    || !uri.getRawPath()
                            .matches(
                                    "/tpr/[A-Za-z0-9_-]{1,64}/config/"
                                            + key.substring(0, 2)
                                            + "/"
                                            + key.substring(2, 4)
                                            + "/"
                                            + key))
                throw new IllegalArgumentException("Noncanonical official configuration proof URL");
        } catch (IllegalArgumentException invalid) {
            throw new SourceContextException(
                    SourceContextException.Reason.INTEGRITY,
                    "Configuration proof is not a supported official route for its exact key",
                    invalid);
        }
    }

    private static SourceContextException unsupported(String message) {
        return new SourceContextException(SourceContextException.Reason.UNSUPPORTED, message);
    }
}
