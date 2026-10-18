package io.vibeminer.infrastructure.discovery;

import io.vibeminer.application.DiscoveryService;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.BuildEvidence;
import io.vibeminer.domain.SourceDocument;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Explicit metadata-only discovery. Construction never performs network I/O. */
public final class OfficialDiscoveryService implements DiscoveryService {
    private static final String ORIGIN = "https://us.version.battle.net";
    private static final Pattern PRODUCT = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Pattern REGION = Pattern.compile("[a-z]{2,8}");
    private static final Pattern CONFIG = Pattern.compile("[0-9a-fA-F]{32}");
    private final MetadataTransport transport;
    private final Clock clock;
    private final BlizzardMetadataParser parser = new BlizzardMetadataParser();
    private final Semaphore refreshes = new Semaphore(4);
    private final AtomicReference<DiscoveryObservation> observation = new AtomicReference<>();

    public OfficialDiscoveryService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    public OfficialDiscoveryService(HttpClient client) {
        this(new BoundedMetadataHttp(client, Clock.systemUTC()), Clock.systemUTC());
    }

    OfficialDiscoveryService(MetadataTransport transport, Clock clock) {
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public List<BuildSnapshot> fixtureBuilds() { return List.of(); }

    @Override public CompletionStage<List<BuildSnapshot>> refresh(String productId, String region) {
        if (productId == null || !PRODUCT.matcher(productId).matches()
                || region == null || !REGION.matcher(region).matches()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid product or region identifier"));
        }
        if (!refreshes.tryAcquire()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Too many discovery refreshes are already running"));
        }
        try {
            var base = ORIGIN + "/v2/products/" + productId;
            var versions = transport.fetch(URI.create(base + "/versions")).toCompletableFuture();
            CompletableFuture<CapturedMetadata> cdns;
            try {
                cdns = transport.fetch(URI.create(base + "/cdns")).toCompletableFuture();
            } catch (RuntimeException cannotStartCdn) {
                versions.cancel(true);
                throw cannotStartCdn;
            }
            var result = versions.thenCombine(cdns, (versionBytes, cdnBytes) -> {
                var versionDocument = parser.parse(versionBytes.bytes());
                var cdnDocument = parser.parse(cdnBytes.bytes());
                versionDocument.requireColumns("Region", "BuildConfig", "CDNConfig", "VersionsName");
                cdnDocument.requireColumns("Name", "Path", "Hosts");
                var matchingCdn = cdnDocument.rows().stream().filter(row -> region.equals(row.get("Name"))).toList();
                if (matchingCdn.size() != 1 || matchingCdn.stream().anyMatch(row -> row.get("Path").isBlank() || row.get("Hosts").isBlank())) {
                    throw new MetadataFormatException("No usable CDN metadata for the requested region");
                }
                var now = clock.instant();
                var sourceDocuments = List.of(sourceDocument(versionBytes), sourceDocument(cdnBytes));
                var builds = versionDocument.rows().stream().filter(row -> region.equals(row.get("Region")))
                        .map(row -> {
                            var optionalProductConfig = row.getOrDefault("ProductConfig", "");
                            if (!optionalProductConfig.isEmpty() && !CONFIG.matcher(optionalProductConfig).matches()) {
                                throw new MetadataFormatException("Invalid product configuration identity");
                            }
                            var buildId = row.getOrDefault("BuildId", "");
                            if (!buildId.isEmpty() && !buildId.matches("[0-9]{1,20}")) {
                                throw new MetadataFormatException("Invalid upstream build identifier");
                            }
                            try {
                                return new BuildSnapshot(productId, productId + " (edition unverified)", "Published",
                                        region, row.get("VersionsName"), row.get("BuildConfig"), row.get("CDNConfig"), now, false,
                                        new BuildEvidence(buildId, optionalProductConfig, sourceDocuments));
                            } catch (IllegalArgumentException malformed) {
                                throw new MetadataFormatException("Invalid published build identity", malformed);
                            }
                        }).toList();
                if (builds.isEmpty()) throw new MetadataFormatException("No published build for the requested region");
                observation.set(new DiscoveryObservation(productId, region, now, versionBytes, cdnBytes,
                        versionDocument, cdnDocument, builds));
                return builds;
            }).toCompletableFuture();
            result.whenComplete((value, failure) -> {
                if (result.isCancelled()) {
                    versions.cancel(true);
                    cdns.cancel(true);
                }
                refreshes.release();
            });
            return result;
        } catch (RuntimeException failedToStart) {
            refreshes.release();
            return CompletableFuture.failedFuture(failedToStart);
        }
    }

    private static SourceDocument sourceDocument(CapturedMetadata metadata) {
        return new SourceDocument(metadata.source().toString(), metadata.observedAt(), metadata.sha256(), metadata.bytes());
    }

    public Optional<DiscoveryObservation> lastObservation() { return Optional.ofNullable(observation.get()); }
}
