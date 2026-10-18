package io.vibeminer.infrastructure.encodedsource;

import static io.vibeminer.application.encodedsource.EncodedSourceException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.selectedfile.ArchiveMappingProof;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.tact.archive.*;
import io.vibeminer.protocol.tact.config.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Official loose/archive locator mechanics shared by independently authenticated consumers. */
public final class OfficialEncodedSourceLocator {
  public record Policy(
      int maximumConfigurationBytes,
      long maximumIndexBytes,
      long maximumEncodedBytes,
      int maximumArchives,
      long maximumArchiveBytes,
      long maximumWireBytes,
      int maximumRequests,
      Duration maximumDuration) {
    public Policy {
      Objects.requireNonNull(maximumDuration);
      if (maximumConfigurationBytes < 1
          || maximumIndexBytes < 1
          || maximumEncodedBytes < 1
          || maximumArchives < 1
          || maximumArchiveBytes < 1
          || maximumWireBytes < 1
          || maximumRequests < 1
          || maximumDuration.isZero()
          || maximumDuration.isNegative()
          || maximumDuration.compareTo(Duration.ofMinutes(10)) > 0)
        throw new IllegalArgumentException("Bounded locator policy required");
    }
  }

  public record SearchResult(
      Optional<EncodedSourcePlan.Location> located,
      int checkedArchives,
      int advertisedArchives,
      boolean complete) {}

  private final PinnedCdnTransport configurations;
  private final PinnedCdnRangeTransport ranges;
  private final CdnMetadataTransport metadata;
  private final LongSupplier nanos;

  public OfficialEncodedSourceLocator(
      PinnedCdnTransport configurations,
      PinnedCdnRangeTransport ranges,
      CdnMetadataTransport metadata) {
    this(configurations, ranges, metadata, System::nanoTime);
  }

  public OfficialEncodedSourceLocator(
      PinnedCdnTransport configurations,
      PinnedCdnRangeTransport ranges,
      CdnMetadataTransport metadata,
      LongSupplier nanos) {
    this.configurations = Objects.requireNonNull(configurations);
    this.ranges = Objects.requireNonNull(ranges);
    this.metadata = Objects.requireNonNull(metadata);
    this.nanos = Objects.requireNonNull(nanos);
  }

  /**
   * A 404 remains a typed AcquisitionException; only the consumer issuer may authorize archive
   * search.
   */
  public EncodedSourcePlan.Location probeLoose(
      BuildSnapshot build,
      String encodingKey,
      OptionalLong expectedBytes,
      Policy policy,
      CancellationToken cancellation) {
    var work =
        new Work(
            policy.maximumWireBytes(),
            policy.maximumRequests(),
            policy.maximumDuration(),
            cancellation,
            nanos);
    var resource =
        new CdnResource(CapturedBuildSource.resolve(build), encodingKey, CdnResource.Kind.DATA);
    work.reserve(0);
    var request = new CdnSizeRequest(resource, policy.maximumEncodedBytes());
    var observed = work.await(metadata.size(request));
    if (!observed.request().equals(request) || !observed.actualSourceUri().equals(resource.uri()))
      throw fail(INTEGRITY_FAILURE, "Loose size observation changed source");
    requireLength(expectedBytes, observed.observedWholeBytes(), policy.maximumEncodedBytes());
    return new EncodedSourcePlan.Location(
        EncodedSourcePlan.LocationKind.LOOSE,
        resource.uri().toString(),
        resource.key(),
        observed.observedWholeBytes(),
        0,
        observed.observedWholeBytes(),
        Optional.empty());
  }

  /**
   * Caller must first authenticate a same-source loose404; this method does not grant transfer
   * authority.
   */
  public SearchResult searchArchives(
      BuildSnapshot build,
      String encodingKey,
      OptionalLong expectedBytes,
      Policy policy,
      CancellationToken cancellation) {
    var work =
        new Work(
            policy.maximumWireBytes(),
            policy.maximumRequests(),
            policy.maximumDuration(),
            cancellation,
            nanos);
    int checked = 0, advertised = 0;
    try {
      var route = CapturedBuildSource.resolve(build);
      int cap = (int) policy.maximumConfigurationBytes();
      var configRequest = new CdnRequest(route, CdnRequest.Kind.CONFIG, build.cdnConfig(), cap, -1);
      work.reserve(cap);
      var bytes = work.await(configurations.fetch(configRequest));
      work.settle(cap, bytes.bytes().length);
      if (!bytes.request().equals(configRequest) || !bytes.configurationMd5Verified())
        throw fail(INTEGRITY_FAILURE, "Pinned CDN configuration receipt changed");
      var config =
          new TactConfigParser()
              .parse(
                  bytes.bytes(),
                  configRequest.key(),
                  new TactConfigParser.Limits(cap, 20000, cap, 100000, 2L << 30, 2L << 30),
                  work::cancelled);
      var keys = config.values().get("archives");
      if (keys == null)
        throw fail(UNSUPPORTED, "Pinned CDN configuration has no supported archive list");
      advertised = keys.size();
      var hints = config.values().getOrDefault("archives-index-size", List.of());
      if (!hints.isEmpty() && hints.size() != keys.size())
        throw fail(INTEGRITY_FAILURE, "Advertised archive/index size association is ambiguous");
      var parser = new ArchiveIndexParser();
      long maxIndex = policy.maximumIndexBytes();
      var parserLimits = new ArchiveIndexParser.Limits(100_000_000, 100_000, maxIndex);
      for (int index = 0; index < keys.size() && checked < policy.maximumArchives(); index++) {
        work.check();
        String key = keys.get(index);
        var parent = new CdnResource(route, key, CdnResource.Kind.DATA);
        var source = new CdnResource(route, key, CdnResource.Kind.INDEX);
        var footerRequest = new CdnIndexFooterRequest(source, maxIndex);
        work.reserve(28);
        var observed = work.await(metadata.footer(footerRequest));
        if (!observed.request().equals(footerRequest)
            || !observed.actualSourceUri().equals(source.uri()))
          throw fail(INTEGRITY_FAILURE, "Archive footer observation changed source");
        var footer =
            parser.authenticateFooter(
                observed.bytes(),
                key,
                observed.observedWholeBytes(),
                parserLimits,
                work::cancelled);
        OptionalLong advertisedIndexBytes = OptionalLong.empty();
        if (!hints.isEmpty()) {
          try {
            long hint = Long.parseLong(hints.get(index));
            if (hint <= 0) throw new NumberFormatException();
            advertisedIndexBytes = OptionalLong.of(hint);
          } catch (NumberFormatException invalid) {
            throw fail(
                UNSUPPORTED,
                "Advertised archive index hint is not a supported positive size",
                invalid);
          }
        }
        byte[] tocBytes =
            footer.tocBytes() == 0
                ? new byte[0]
                : range(work, source, footer.indexBytes(), footer.tocOffset(), footer.tocBytes());
        var toc = parser.authenticateToc(footer, tocBytes, work::cancelled);
        var page = toc.pageFor(encodingKey);
        if (page.isEmpty()) {
          checked++;
          continue;
        }
        byte[] pageBytes =
            range(work, source, footer.indexBytes(), page.get().indexOffset(), page.get().length());
        var match = parser.lookup(toc, page.get(), pageBytes, encodingKey, work::cancelled);
        checked++;
        if (match.isEmpty()) continue;
        var child = match.get();
        requireLength(expectedBytes, child.encodedSize(), policy.maximumEncodedBytes());
        work.reserve(0);
        var parentRequest = new CdnSizeRequest(parent, policy.maximumArchiveBytes());
        var size = work.await(metadata.size(parentRequest));
        if (!size.request().equals(parentRequest)
            || !size.actualSourceUri().equals(parent.uri())
            || child.archiveEndExclusive() > size.observedWholeBytes())
          throw fail(
              INTEGRITY_FAILURE, "Authenticated child span exceeds observed archive geometry");
        var proof =
            new ArchiveMappingProof(
                key,
                observed.footerSha256(),
                sha256(tocBytes),
                sha256(pageBytes),
                "archive-index-footer-toc-page-v1",
                advertisedIndexBytes,
                footer.indexBytes());
        var locator =
            new EncodedSourcePlan.Location(
                EncodedSourcePlan.LocationKind.ARCHIVE,
                parent.uri().toString(),
                key,
                size.observedWholeBytes(),
                child.archiveOffset(),
                child.encodedSize(),
                Optional.of(proof));
        return new SearchResult(Optional.of(locator), checked, advertised, checked == advertised);
      }
      return new SearchResult(Optional.empty(), checked, advertised, checked == advertised);
    } catch (Exception error) {
      var typed = classify(error);
      if (typed.code() == BUDGET_EXCEEDED)
        return new SearchResult(Optional.empty(), checked, advertised, false);
      throw typed;
    }
  }

  private byte[] range(Work work, CdnResource resource, long whole, long start, int count) {
    work.reserve(count);
    var request = new CdnRangeRequest(resource, whole, start, count, count);
    var result = work.await(ranges.fetch(request));
    if (!result.request().equals(request) || !result.actualSourceUri().equals(resource.uri()))
      throw fail(INTEGRITY_FAILURE, "Selected archive index receipt changed source");
    return result.bytes();
  }

  private static void requireLength(OptionalLong expected, long length, long maximum) {
    if (length > maximum) throw fail(BUDGET_EXCEEDED, "Encoded file exceeds payload size budget");
    if (expected.isPresent() && expected.getAsLong() != length)
      throw fail(INTEGRITY_FAILURE, "Observed encoding size conflicts with authenticated size");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static EncodedSourceException fail(EncodedSourceException.Code code, String message) {
    return new EncodedSourceException(code, message);
  }

  private static EncodedSourceException fail(
      EncodedSourceException.Code code, String message, Throwable cause) {
    return new EncodedSourceException(code, message, cause);
  }

  private static EncodedSourceException classify(Throwable error) {
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) error = error.getCause();
    if (error instanceof CancellationException cancelled) throw cancelled;
    if (error instanceof EncodedSourceException typed) return typed;
    if (error instanceof AcquisitionException acquisition)
      return fail(
          switch (acquisition.reason()) {
            case LIMIT -> BUDGET_EXCEEDED;
            case TRANSPORT, UNAVAILABLE -> UNAVAILABLE;
            case CANCELLED -> throw new CancellationException();
            case INTEGRITY -> INTEGRITY_FAILURE;
          },
          acquisition.getMessage(),
          acquisition);
    return fail(INTEGRITY_FAILURE, "Encoded locator source/protocol failure", error);
  }

  private static final class Work {
    final long maximumWire;
    final int maximumRequests;
    final long deadline;
    final long started;
    final LongSupplier nanos;
    final CancellationToken cancellation;
    long wire;
    int requests;

    Work(
        long wire,
        int requests,
        Duration duration,
        CancellationToken cancellation,
        LongSupplier nanos) {
      this.nanos = nanos;
      this.started = nanos.getAsLong();
      this.maximumWire = wire;
      this.maximumRequests = requests;
      this.deadline = duration.toNanos();
      this.cancellation = cancellation;
    }

    void check() {
      cancellation.throwIfCancellationRequested();
      if (nanos.getAsLong() - started >= deadline)
        throw fail(BUDGET_EXCEEDED, "Encoded-source metadata operation deadline exceeded");
    }

    boolean cancelled() {
      check();
      return false;
    }

    CancellationToken token() {
      return this::cancelled;
    }

    void reserve(long bytes) {
      check();
      if (bytes < 0 || bytes > maximumWire - wire || requests >= maximumRequests)
        throw fail(BUDGET_EXCEEDED, "Next encoded-source metadata request exceeds explicit scope");
      wire += bytes;
      requests++;
    }

    void settle(long reserved, long actual) {
      if (actual < 0 || actual > reserved)
        throw fail(INTEGRITY_FAILURE, "Metadata response exceeds reservation");
      wire -= reserved - actual;
      check();
    }

    <T> T await(CompletableFuture<T> future) {
      try {
        while (true) {
          check();
          try {
            T completed = future.get(25, TimeUnit.MILLISECONDS);
            check();
            return completed;
          } catch (TimeoutException waiting) {
          }
        }
      } catch (InterruptedException interrupted) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw new CancellationException();
      } catch (ExecutionException failure) {
        throw new CompletionException(failure.getCause());
      } catch (RuntimeException failure) {
        future.cancel(true);
        throw failure;
      }
    }
  }
}
