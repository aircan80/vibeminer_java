import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.db2.*;
import io.vibeminer.protocol.tact.archive.ArchiveIndexParser;
import io.vibeminer.protocol.tact.blte.BlteChunkPlanner;
import io.vibeminer.protocol.tact.config.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Explicit archive diagnostic. It consumes prior root/EN proof inputs; no new root scan is claimed.
 */
public final class ArchivedItemHeaderProbe {
  static final String CONFIG = "f065eade5276a5ac05606e60a4c5a1f7";
  static final CdnRequest.Source ROUTE =
      new CdnRequest.Source(
          "us.cdn.blizzard.com", "tpr/wow", "tpr/configs/data", List.of("us.cdn.blizzard.com"));

  record Target(
      long fileDataId,
      String contentKey,
      String encodingKey,
      long encodedBytes,
      long decodedBytes,
      String priorProof) {
    Target {
      if (fileDataId < 0
          || fileDataId > 0xffffffffL
          || !contentKey.matches("[0-9a-f]{32}")
          || !encodingKey.matches("[0-9a-f]{32}")
          || encodedBytes < 9
          || decodedBytes < 204
          || priorProof.isBlank())
        throw new IllegalArgumentException("Exact prior-proof target required");
    }
  }

  static final Target ITEM =
      new Target(
          841626,
          "1df6b6dbae36878cea020a62b15b9bd6",
          "a928b6c9c7ce92d85b638400f81b2fc2",
          738344,
          2139665,
          "Prior #45 2026-10-18: wow/us 12.1.5.70448 buildConfig 88d5e8ef05ec5a8446cdec93d142d7c6"
              + " complete decoded root CKey e84483ee756c69a7dc5fbff04af81316 plus authenticated"
              + " selected EN pages. No root/EN scan this run.");

  record Limits(
      long wireBytes,
      int requests,
      Duration time,
      int chunkBytes,
      long indexBytes,
      long archiveBytes,
      long decodedWorkBytes) {
    Limits {
      if (wireBytes < 1
          || requests < 1
          || time.isZero()
          || time.isNegative()
          || time.compareTo(Duration.ofHours(1)) > 0
          || chunkBytes < 204
          || chunkBytes > 64 * 1024 * 1024
          || indexBytes < 28
          || indexBytes > 2L * 1024 * 1024 * 1024
          || archiveBytes < 1
          || archiveBytes > 8L * 1024 * 1024 * 1024
          || decodedWorkBytes < 204)
        throw new IllegalArgumentException("Invalid bounded archive diagnostic policy");
    }

    static Limits defaults() {
      return new Limits(
          16L * 1024 * 1024,
          5000,
          Duration.ofMinutes(10),
          4 * 1024 * 1024,
          2L * 1024 * 1024,
          8L * 1024 * 1024 * 1024,
          64L * 1024 * 1024);
    }
  }

  static final class Refusal extends RuntimeException {
    Refusal(String message) {
      super(message);
    }
  }

  static final class Budget {
    final Limits limits;
    final LongSupplier nanos;
    final BooleanSupplier cancelled;
    final long started;
    long wire, decoded;
    int requests;

    Budget(Limits limits, LongSupplier nanos, BooleanSupplier cancelled) {
      this.limits = limits;
      this.nanos = nanos;
      this.cancelled = cancelled;
      started = nanos.getAsLong();
    }

    long remainingNanos() {
      return limits.time.toNanos() - (nanos.getAsLong() - started);
    }

    boolean cancelled() {
      return cancelled.getAsBoolean() || remainingNanos() <= 0;
    }

    void check() {
      if (cancelled.getAsBoolean()) throw new CancellationException("Archive diagnostic cancelled");
      if (remainingNanos() <= 0) throw new Refusal("Total archive diagnostic time budget exceeded");
    }

    void require(long bytes, int count, long decode) {
      check();
      if (bytes < 0
          || bytes > limits.wireBytes - wire
          || count < 0
          || count > limits.requests - requests
          || decode < 0
          || decode > limits.decodedWorkBytes - decoded)
        throw new Refusal("Planned archive scope exceeds remaining wire/request/decode budget");
    }

    void reserve(long bytes) {
      require(bytes, 1, 0);
      wire += bytes;
      requests++;
    }

    void settle(long reserved, long actual) {
      if (actual < 0 || actual > reserved)
        throw new Refusal("Response exceeded reserved body bytes");
      wire -= reserved - actual;
      check();
    }

    void decode(long bytes) {
      require(0, 0, bytes);
      decoded += bytes;
    }
  }

  interface Source {
    AcquiredBytes configuration(CdnRequest request) throws Exception;

    IndexFooterObservation footer(CdnIndexFooterRequest request) throws Exception;

    DataSizeObservation size(CdnSizeRequest request) throws Exception;

    AcquiredRange range(CdnRangeRequest request) throws Exception;
  }

  final Source source;
  final CdnRequest.Source route;
  final Budget budget;
  final Consumer<String> out;
  final ArchiveIndexParser indexes = new ArchiveIndexParser();
  final BlteChunkPlanner blte = new BlteChunkPlanner();

  ArchivedItemHeaderProbe(
      Source source, CdnRequest.Source route, Budget budget, Consumer<String> out) {
    this.source = source;
    this.route = route;
    this.budget = budget;
    this.out = out;
  }

  CdnResource resource(String key, CdnResource.Kind kind) {
    return new CdnResource(route, key, kind);
  }

  TactConfig config(String key) throws Exception {
    var request = CdnRequest.configuration(route, key);
    out.accept(
        "TRANSFER config source=" + request.uri() + " maximumBodyBytes=" + request.maximumBytes());
    budget.reserve(request.maximumBytes());
    var received = source.configuration(request);
    if (!request.equals(received.request())) throw new Refusal("Config source identity changed");
    byte[] bytes = received.bytes();
    budget.settle(request.maximumBytes(), bytes.length);
    var parsed =
        new TactConfigParser()
            .parse(
                bytes,
                key,
                new TactConfigParser.Limits(
                    1048576,
                    20000,
                    1048576,
                    20000,
                    2L * 1024 * 1024 * 1024,
                    2L * 1024 * 1024 * 1024),
                budget::cancelled);
    out.accept(
        "VERIFIED configMD5="
            + parsed.rawMd5()
            + " source="
            + request.uri()
            + " received="
            + received.receivedAt()
            + " bodyBytes="
            + bytes.length);
    return parsed;
  }

  IndexFooterObservation footer(CdnResource resource) throws Exception {
    var request = new CdnIndexFooterRequest(resource, budget.limits.indexBytes);
    out.accept(
        "TRANSFER suffix source="
            + resource.uri()
            + " Range=bytes=-28 maxObservedTotal="
            + request.maximumWholeBytes());
    budget.reserve(28);
    var result = source.footer(request);
    if (!request.equals(result.request()) || !resource.uri().equals(result.actualSourceUri()))
      throw new Refusal("Footer source identity changed");
    budget.settle(28, result.bytes().length);
    out.accept(
        "RECEIVED suffix source="
            + result.actualSourceUri()
            + " observedTotal="
            + result.observedWholeBytes()
            + " at="
            + result.receivedAt()
            + " footerSHA256="
            + result.footerSha256());
    return result;
  }

  byte[] range(CdnResource resource, long whole, long start, int count) throws Exception {
    var request = new CdnRangeRequest(resource, whole, start, count, count);
    out.accept(
        "TRANSFER exact range source="
            + resource.uri()
            + " start="
            + start
            + " count="
            + count
            + " pinnedWhole="
            + whole);
    budget.reserve(count);
    var result = source.range(request);
    if (!request.equals(result.request()) || !resource.uri().equals(result.actualSourceUri()))
      throw new Refusal("Exact range source identity changed");
    byte[] bytes = result.bytes();
    budget.settle(count, bytes.length);
    out.accept(
        "RECEIVED range source="
            + result.actualSourceUri()
            + " at="
            + result.receivedAt()
            + " rangeSHA256="
            + result.rangeSha256());
    return bytes;
  }

  DataSizeObservation size(CdnResource resource) throws Exception {
    var request = new CdnSizeRequest(resource, budget.limits.archiveBytes);
    out.accept(
        "TRANSFER HEAD source="
            + resource.uri()
            + " maxObservedTotal="
            + request.maximumWholeBytes());
    budget.reserve(0);
    var result = source.size(request);
    if (!request.equals(result.request()) || !resource.uri().equals(result.actualSourceUri()))
      throw new Refusal("Archive size source identity changed");
    budget.settle(0, 0);
    out.accept(
        "RECEIVED HEAD source="
            + result.actualSourceUri()
            + " observedTotal="
            + result.observedWholeBytes()
            + " at="
            + result.receivedAt()
            + " ETag="
            + result.etag()
            + " wholeArchiveChecksumVerified=false");
    return result;
  }

  record Mapping(ArchiveIndexParser.IndexMatch match, DataSizeObservation size) {}

  Mapping locate(TactConfig config, Target target) throws Exception {
    var archives = config.values().get("archives");
    if (archives == null) throw new Refusal("Pinned CDN config has no archives field");
    var hints = config.values().getOrDefault("archives-index-size", List.of());
    out.accept(
        "ARCHIVE SCAN PLAN archives="
            + archives.size()
            + " sizeHints="
            + hints.size()
            + " maximumWire="
            + budget.limits.wireBytes
            + " maximumRequests="
            + budget.limits.requests
            + " eachIndexMaximum="
            + budget.limits.indexBytes
            + " order=pinned-config scope=footer+TOC+one-selected-page stopOnVerifiedMapping=true");
    if (!hints.isEmpty() && hints.size() != archives.size())
      out.accept(
          "SIZE HINT VECTOR CONFLICT count mismatch; no positional hints applied and no"
              + " reordering");
    int checked = 0;
    for (int ordinal = 0; ordinal < archives.size(); ordinal++) {
      String archive = archives.get(ordinal);
      var index = resource(archive, CdnResource.Kind.INDEX);
      var observed = footer(index);
      var authenticated =
          indexes.authenticateFooter(
              observed.bytes(),
              archive,
              observed.observedWholeBytes(),
              new ArchiveIndexParser.Limits(100_000_000, 600000, budget.limits.indexBytes),
              budget::cancelled);
      String hint = hints.size() == archives.size() ? hints.get(ordinal) : "unbound-or-unavailable";
      out.accept(
          "VERIFIED INDEX FOOTER ordinal="
              + ordinal
              + " key="
              + archive
              + " authenticatedWhole="
              + authenticated.indexBytes()
              + " advertisedSizeHint="
              + hint
              + " hintConflict="
              + (hints.size() == archives.size()
                  && !hint.equals(Long.toString(authenticated.indexBytes())))
              + " hintNeverControlsRouting=true");
      byte[] tocBytes =
          authenticated.tocBytes() == 0
              ? new byte[0]
              : range(
                  index,
                  authenticated.indexBytes(),
                  authenticated.tocOffset(),
                  authenticated.tocBytes());
      var toc = indexes.authenticateToc(authenticated, tocBytes, budget::cancelled);
      var page = toc.pageFor(target.encodingKey);
      checked++;
      if (page.isEmpty()) {
        out.accept(
            "SCOPED INDEX RESULT ordinal="
                + ordinal
                + " noSelectedTOCInterval=true completeArchiveBytesVerified=false");
        continue;
      }
      var reference = page.get();
      byte[] pageBytes =
          range(index, authenticated.indexBytes(), reference.indexOffset(), reference.length());
      var match = indexes.lookup(toc, reference, pageBytes, target.encodingKey, budget::cancelled);
      if (match.isEmpty()) {
        out.accept("SCOPED INDEX RESULT ordinal=" + ordinal + " selectedPageNoMatch=true");
        continue;
      }
      var found = match.get();
      if (!found.indexIdentity().equals(archive)
          || !found.encodingKey().equals(target.encodingKey)
          || found.encodedSize() != target.encodedBytes)
        throw new Refusal(
            "Authenticated mapping conflicts with prior target identity/encoded length");
      out.accept(
          "VERIFIED ARCHIVE MAPPING ordinal="
              + ordinal
              + " checkedIndexes="
              + checked
              + " archive="
              + archive
              + " targetEKey="
              + found.encodingKey()
              + " offset="
              + found.archiveOffset()
              + " encodedBytes="
              + found.encodedSize()
              + " completeArchiveChecksumVerified=false");
      var whole = size(resource(archive, CdnResource.Kind.DATA));
      if (found.archiveEndExclusive() > whole.observedWholeBytes())
        throw new Refusal("Authenticated child span exceeds observed whole archive length");
      return new Mapping(found, whole);
    }
    throw new Refusal(
        "Target mapping not found in checked pinned archive index scope="
            + checked
            + "; no game-record absence claim");
  }

  final class Child {
    final Target target;
    final Mapping mapping;

    Child(Target target, Mapping mapping) {
      this.target = target;
      this.mapping = mapping;
    }

    byte[] range(long start, int count) throws Exception {
      if (start < 0 || count < 1 || start > target.encodedBytes - count)
        throw new Refusal("Child range exceeds prior pinned encoded target length");
      long translated = Math.addExact(mapping.match.archiveOffset(), start);
      if (Math.addExact(translated, count) > mapping.size.observedWholeBytes())
        throw new Refusal("Translated child range exceeds observed archive bounds");
      return ArchivedItemHeaderProbe.this.range(
          mapping.size.request().resource(), mapping.size.observedWholeBytes(), translated, count);
    }
  }

  Wdc5Header inspect(Target target, Mapping mapping) throws Exception {
    out.accept(
        "TARGET PLAN priorFileDataID="
            + target.fileDataId
            + " CKey="
            + target.contentKey
            + " EKey="
            + target.encodingKey
            + " encoded="
            + target.encodedBytes
            + " decoded="
            + target.decodedBytes
            + " requestedScope=204-byte-header rootScanThisRun=false");
    var child = new Child(target, mapping);
    byte[] prefix = child.range(0, 8);
    if (prefix[0] != 'B' || prefix[1] != 'L' || prefix[2] != 'T' || prefix[3] != 'E')
      throw new Refusal("Unsupported child encoded signature");
    long header =
        ((long) (prefix[4] & 255) << 24)
            | ((long) (prefix[5] & 255) << 16)
            | ((long) (prefix[6] & 255) << 8)
            | (prefix[7] & 255);
    if (header < 12 || header > 1024 * 1024 || header >= target.encodedBytes)
      throw new Refusal("Unsupported/over-budget child BLTE header size");
    var plan =
        blte.authenticate(
            child.range(0, (int) header),
            HexFormat.of().parseHex(target.encodingKey),
            target.encodedBytes,
            target.decodedBytes,
            new BlteChunkPlanner.Limits(
                1024 * 1024,
                40000,
                target.encodedBytes,
                target.decodedBytes,
                64 * 1024 * 1024,
                64 * 1024 * 1024),
            budget::cancelled);
    out.accept(
        "VERIFIED CHILD BLTE HEADER targetEKey="
            + target.encodingKey
            + " archiveSource="
            + mapping.size.actualSourceUri()
            + " archiveOffset="
            + mapping.match.archiveOffset());
    var selected = plan.chunks().stream().filter(chunk -> chunk.decodedOffset() < 204).toList();
    long cost = 0, work = 0;
    for (var chunk : selected) {
      if (chunk.encodedBytes() > budget.limits.chunkBytes
          || chunk.decodedBytes() > budget.limits.chunkBytes)
        throw new Refusal("Selected child chunk exceeds encoded/decoded buffer budget");
      cost += chunk.encodedBytes();
      work += chunk.decodedBytes();
    }
    out.accept(
        "HEADER CHUNK PLAN count="
            + selected.size()
            + " encodedTransferBytes="
            + cost
            + " decodedWorkBytes="
            + work
            + " fullTableCKeyVerified=false");
    budget.require(cost, selected.size(), work);
    byte[] mandatory = new byte[204];
    int copied = 0;
    for (var chunk : selected) {
      budget.decode(chunk.decodedBytes());
      byte[] encoded = child.range(chunk.encodedOffset(), chunk.encodedBytes());
      byte[] decoded = blte.decodeSelected(plan, chunk, encoded, budget::cancelled).decodedBytes();
      int amount = (int) Math.min(decoded.length, 204 - chunk.decodedOffset());
      System.arraycopy(decoded, 0, mandatory, (int) chunk.decodedOffset(), amount);
      copied += amount;
      out.accept(
          "VERIFIED CHILD CHUNK index="
              + chunk.index()
              + " encodedMD5+decodedMode scope=consumed-chunk-only");
    }
    if (copied != 204)
      throw new Refusal("Authenticated child chunks did not cover complete mandatory header");
    var observed =
        new Wdc5HeaderInspector()
            .inspect(
                mandatory,
                new Wdc5HeaderInspector.Limits(
                    204, 20_000_000, 10000, 10000, 1024 * 1024, 2L * 1024 * 1024 * 1024),
                budget::cancelled);
    out.accept(
        "HEADER OBSERVED FileDataID="
            + target.fileDataId
            + " signature=WDC5 tableHash="
            + String.format("%08X", observed.tableHash())
            + " layoutHash="
            + String.format("%08X", observed.layoutHash())
            + " schema="
            + observed.schemaVersion()
            + " flags="
            + observed.flags()
            + " headerData="
            + observed
            + " candidateTableLayoutMatch="
            + (observed.tableHash() == 0x50238ec2L && observed.layoutHash() == 0x996192aaL)
            + " candidateRevision=b9a63096cdd99b26967f17f9515377ae62cadfd7 rootScanThisRun=false"
            + " fullTableCKeyVerified=false bodyValidated=false rowsSupported=false gates="
            + observed.gate(null));
    return observed;
  }

  Wdc5Header run(String configKey, Target target) throws Exception {
    out.accept(
        "PRIOR PROOF INPUT "
            + target.priorProof
            + " target="
            + target
            + " thisRunProof=CDN-config/index/archive-ranges/consumed-BLTE-chunks-only");
    var mapping = locate(config(configKey), target);
    var result = inspect(target, mapping);
    out.accept(
        "COST requests="
            + budget.requests
            + " responseBodyBytes="
            + budget.wire
            + " decodedWorkBytes="
            + budget.decoded
            + " elapsedNanos="
            + (budget.nanos.getAsLong() - budget.started));
    return result;
  }

  static final class LiveSource implements Source {
    final PinnedCdnTransport configs = new PinnedCdnTransport();
    final PinnedCdnRangeTransport ranges = new PinnedCdnRangeTransport();
    final CdnMetadataTransport metadata = new CdnMetadataTransport();
    final Budget budget;

    LiveSource(Budget budget) {
      this.budget = budget;
    }

    <T> T await(CompletableFuture<T> future) throws Exception {
      try {
        return future.get(Math.max(1, budget.remainingNanos()), TimeUnit.NANOSECONDS);
      } catch (InterruptedException stop) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw stop;
      } catch (TimeoutException timeout) {
        future.cancel(true);
        throw new Refusal("Total diagnostic time expired during transfer");
      }
    }

    public AcquiredBytes configuration(CdnRequest request) throws Exception {
      return await(configs.fetch(request));
    }

    public AcquiredRange range(CdnRangeRequest request) throws Exception {
      return await(ranges.fetch(request));
    }

    public IndexFooterObservation footer(CdnIndexFooterRequest request) throws Exception {
      return await(metadata.footer(request));
    }

    public DataSizeObservation size(CdnSizeRequest request) throws Exception {
      return await(metadata.size(request));
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      ArchivedItemHeaderProbeSelfTest.main(new String[0]);
      return;
    }
    if (args.length != 1 || !args[0].equals("--live"))
      throw new IllegalArgumentException(
          "Use --self-test (offline) or explicit --live (reviewed fixed default budgets)");
    var limits = Limits.defaults();
    var budget = new Budget(limits, System::nanoTime, () -> Thread.currentThread().isInterrupted());
    System.out.println(
        "LIVE OPT-IN archived Item target build=12.1.5.70448 pinnedCDNConfig="
            + CONFIG
            + " budgets="
            + limits
            + " noPayloadWrites=true rootScanThisRun=false");
    try {
      new ArchivedItemHeaderProbe(new LiveSource(budget), ROUTE, budget, System.out::println)
          .run(CONFIG, ITEM);
    } catch (Exception failure) {
      System.out.println(
          "DIAGNOSTIC STOP outcome="
              + failure.getClass().getSimpleName()
              + " message="
              + failure.getMessage()
              + " reservedOrReceivedBodyBytes="
              + budget.wire
              + " requestReservations="
              + budget.requests
              + " decodedWorkReservations="
              + budget.decoded
              + " remainingScopeUnacquired=true noPayloadWrites=true");
      throw failure;
    }
  }
}
