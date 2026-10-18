package io.vibeminer.infrastructure.discovery;

import static io.vibeminer.application.discoveryhistory.DiscoveryHistoryException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Explicit metadata-only discovery. Construction never performs network or catalog I/O. */
public final class OfficialDiscoveryService
    implements DiscoveryService, DiscoveryObservationGateway, BuildLibraryProductGateway {
  private static final String ORIGIN = "https://us.version.battle.net";
  private static final Pattern PRODUCT = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
  private static final Pattern REGION = Pattern.compile("[a-z]{2,8}");
  private final MetadataTransport transport;
  private final Clock clock;
  private final DiscoveryObservationMapper mapper = new DiscoveryObservationMapper();
  private final Semaphore refreshes = new Semaphore(4);
  private final AtomicReference<DiscoveryObservation> observation = new AtomicReference<>();

  public OfficialDiscoveryService() {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  public OfficialDiscoveryService(HttpClient client) {
    this(new BoundedMetadataHttp(client, Clock.systemUTC()), Clock.systemUTC());
  }

  OfficialDiscoveryService(MetadataTransport transport, Clock clock) {
    this.transport = Objects.requireNonNull(transport);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public List<BuildSnapshot> fixtureBuilds() {
    return List.of();
  }

  @Override
  public CompletionStage<List<BuildSnapshot>> refresh(String productId, String region) {
    var flight = start(productId, region);
    var facade = new CompletableFuture<List<BuildSnapshot>>();
    flight
        .result()
        .whenComplete(
            (event, failure) -> {
              if (failure != null) facade.completeExceptionally(unwrap(failure));
              else
                facade.complete(
                    event.builds().stream().map(DiscoveryEvent.ObservedBuild::build).toList());
            });
    facade.whenComplete(
        (value, failure) -> {
          if (facade.isCancelled()) flight.result().cancel(true);
        });
    return facade;
  }

  /**
   * Blocking gateway retaining its permit until transport futures and private mapping retire.
   * Future termination describes the transport port's observable lifecycle, not OS socket finality.
   */
  @Override
  public DiscoveryEvent observe(String productId, String region, CancellationToken cancellation) {
    Objects.requireNonNull(cancellation).throwIfCancellationRequested();
    return await(start(productId, region), cancellation);
  }

  @Override
  public BuildLibraryProductObservation observeProduct(
      String productId, CancellationToken cancellation) {
    Objects.requireNonNull(cancellation).throwIfCancellationRequested();
    return await(
        start(productId, (versions, cdns) -> productObservation(productId, versions, cdns)),
        cancellation);
  }

  private <T> T await(Flight<T> flight, CancellationToken cancellation) {
    boolean interrupted = false;
    try {
      while (true) {
        if (cancellation.isCancellationRequested()) {
          flight.result().cancel(true);
          throw new CancellationException("Discovery observation canceled");
        }
        try {
          var result = flight.result().get(25, TimeUnit.MILLISECONDS);
          cancellation.throwIfCancellationRequested();
          return result;
        } catch (TimeoutException waiting) {
          // Poll cancellation while the transport's own strict deadlines remain authoritative.
        } catch (InterruptedException stopped) {
          interrupted = true;
          flight.result().cancel(true);
          throw new CancellationException("Discovery observation interrupted");
        } catch (ExecutionException failure) {
          var cause = unwrap(failure);
          if (cause instanceof RuntimeException runtime) throw runtime;
          throw new CompletionException(cause);
        }
      }
    } finally {
      // Public cancellation cannot retire pending transport futures or private mapping.
      flight.retired().join();
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private record Flight<T>(CompletableFuture<T> result, CompletableFuture<Void> retired) {}

  private Flight<DiscoveryEvent> start(String productId, String region) {
    if (region == null || !REGION.matcher(region).matches())
      return failed(new DiscoveryHistoryException(INVALID_REQUEST, "Invalid region identifier"));
    return start(
        productId, (versions, cdns) -> regionalObservation(productId, region, versions, cdns));
  }

  private <T> Flight<T> start(
      String productId, BiFunction<CapturedMetadata, CapturedMetadata, T> interpret) {
    if (productId == null || !PRODUCT.matcher(productId).matches())
      return failed(new DiscoveryHistoryException(INVALID_REQUEST, "Invalid product identifier"));
    if (!refreshes.tryAcquire())
      return failed(
          new DiscoveryHistoryException(BUSY, "Four actual discovery captures are already active"));
    CompletableFuture<CapturedMetadata> versions;
    try {
      versions =
          transport
              .fetch(URI.create(ORIGIN + "/v2/products/" + productId + "/versions"))
              .toCompletableFuture();
    } catch (RuntimeException failedStart) {
      refreshes.release();
      return failed(failedStart);
    }
    CompletableFuture<CapturedMetadata> cdns;
    try {
      cdns =
          transport
              .fetch(URI.create(ORIGIN + "/v2/products/" + productId + "/cdns"))
              .toCompletableFuture();
    } catch (RuntimeException failedStart) {
      versions.cancel(true);
      var retired =
          versions
              .handle((value, failure) -> (Void) null)
              .whenComplete((value, failure) -> refreshes.release());
      var failedResult = new CompletableFuture<T>();
      retired.whenComplete((value, failure) -> failedResult.completeExceptionally(failedStart));
      return new Flight<>(failedResult, retired);
    }
    var result = new CompletableFuture<T>();
    // Private completion cannot be canceled by a public subscriber while mapping still runs.
    var processing = new CompletableFuture<Void>();
    var retired =
        CompletableFuture.allOf(
                versions.handle((value, failure) -> null),
                cdns.handle((value, failure) -> null),
                processing)
            .whenComplete((value, failure) -> refreshes.release());
    result.whenComplete(
        (value, failure) -> {
          if (result.isCancelled()) {
            versions.cancel(true);
            cdns.cancel(true);
          }
        });
    versions
        .thenCombine(
            cdns,
            (versionBytes, cdnBytes) -> {
              if (result.isCancelled())
                throw new CancellationException("Discovery observation canceled");
              var event = interpret.apply(versionBytes, cdnBytes);
              if (result.isCancelled())
                throw new CancellationException("Discovery observation canceled");
              return event;
            })
        .whenComplete(
            (event, failure) -> {
              processing.complete(null);
              retired.whenComplete(
                  (ignored, retiredFailure) -> {
                    // Release precedes public terminal callbacks, allowing safe callback reentry.
                    if (failure == null) result.complete(event);
                    else result.completeExceptionally(unwrap(failure));
                  });
            });
    return new Flight<>(result, retired);
  }

  private static <T> Flight<T> failed(RuntimeException failure) {
    return new Flight<>(
        CompletableFuture.failedFuture(failure), CompletableFuture.completedFuture(null));
  }

  private DiscoveryEvent regionalObservation(
      String productId, String region, CapturedMetadata versions, CapturedMetadata cdns) {
    var event =
        mapper.map(
            UUID.randomUUID(),
            productId,
            region,
            clock.instant(),
            sourceDocument(versions),
            sourceDocument(cdns));
    var parser = new BlizzardMetadataParser();
    observation.set(
        new DiscoveryObservation(
            productId,
            region,
            event.completedAt(),
            versions,
            cdns,
            parser.parse(versions.bytes()),
            parser.parse(cdns.bytes()),
            event.builds().stream().map(DiscoveryEvent.ObservedBuild::build).toList()));
    return event;
  }

  private BuildLibraryProductObservation productObservation(
      String productId, CapturedMetadata versions, CapturedMetadata cdns) {
    var completedAt = clock.instant();
    try {
      var versionSource = sourceDocument(versions);
      var cdnSource = sourceDocument(cdns);
      var parser = new BlizzardMetadataParser();
      var published = parser.parse(versionSource.bytes());
      var advertised = parser.parse(cdnSource.bytes());
      published.requireColumns("Region", "BuildConfig", "CDNConfig", "VersionsName");
      advertised.requireColumns("Name", "Path", "Hosts");
      var regions = new LinkedHashSet<String>();
      int malformed = 0;
      for (var row : published.rows()) {
        var region = row.get("Region");
        if (!REGION.matcher(region).matches()) {
          malformed++;
          continue;
        }
        regions.add(region);
        if (regions.size() > 64)
          throw new DiscoveryHistoryException(
              LIMIT_EXCEEDED, "Product capture exceeds64 regional outcomes");
      }
      var events = new ArrayList<DiscoveryEvent>();
      var failures = new ArrayList<BuildLibraryProductObservation.RegionalFailure>();
      for (var region : regions) {
        try {
          events.add(
              mapper.map(
                  UUID.randomUUID(), productId, region, completedAt, versionSource, cdnSource));
        } catch (DiscoveryHistoryException failure) {
          failures.add(
              new BuildLibraryProductObservation.RegionalFailure(
                  new BuildLibraryScope(productId, region),
                  failure.code(),
                  "Published region could not be interpreted: " + failure.getMessage()));
        }
      }
      Optional<String> diagnostic =
          malformed > 0
              ? Optional.of(
                  malformed
                      + " publication rows contain malformed region identifiers; original values"
                      + " remain in captured metadata. Publication coverage is unknown for those"
                      + " rows.")
              : regions.isEmpty()
                  ? Optional.of(
                      "No published regional rows were observed; publication coverage is unknown.")
                  : Optional.empty();
      return new BuildLibraryProductObservation(
          productId,
          completedAt,
          DiscoveryObservationMapper.PARSER_REVISION,
          new DiscoveryEvent.ParsedSource(versionSource, published.columns(), published.sequence()),
          new DiscoveryEvent.ParsedSource(cdnSource, advertised.columns(), advertised.sequence()),
          events,
          failures,
          diagnostic);
    } catch (IllegalArgumentException malformed) {
      throw new DiscoveryHistoryException(
          CORRUPT_OBSERVATION,
          "Captured product metadata cannot be interpreted consistently",
          malformed);
    }
  }

  private static SourceDocument sourceDocument(CapturedMetadata metadata) {
    return new SourceDocument(
        metadata.source().toString(), metadata.observedAt(), metadata.sha256(), metadata.bytes());
  }

  private static Throwable unwrap(Throwable failure) {
    while ((failure instanceof CompletionException || failure instanceof ExecutionException)
        && failure.getCause() != null) failure = failure.getCause();
    return failure;
  }

  public Optional<DiscoveryObservation> lastObservation() {
    return Optional.ofNullable(observation.get());
  }
}
