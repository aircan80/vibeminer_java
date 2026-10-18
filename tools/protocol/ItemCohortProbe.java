import io.vibeminer.format.wow.root.SelectedRootLookup;
import io.vibeminer.format.wow.root.SelectedRootMemberships;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.db2.*;
import io.vibeminer.protocol.tact.blte.BlteChunkPlanner;
import io.vibeminer.protocol.tact.config.*;
import io.vibeminer.protocol.tact.encoding.*;
import java.io.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Opt-in diagnostic only. No game content is written, cached on disk, or published as a stored
 * object.
 */
public final class ItemCohortProbe {
  static final String BUILD = "88d5e8ef05ec5a8446cdec93d142d7c6";
  static final String CDN = "f065eade5276a5ac05606e60a4c5a1f7";
  static final Set<Long> IDS = Set.of(841626L, 1572924L);
  static final CdnRequest.Source OFFICIAL =
      new CdnRequest.Source(
          "us.cdn.blizzard.com", "tpr/wow", "tpr/configs/data", List.of("us.cdn.blizzard.com"));
  static final IndexedEncodingParser.Limits EN_LIMITS =
      new IndexedEncodingParser.Limits(100_000, 4 * 1024 * 1024, 1024 * 1024, 65536, 255);

  interface Source {
    AcquiredBytes configuration(CdnRequest request) throws Exception;

    AcquiredRange range(CdnRangeRequest request) throws Exception;
  }

  record Limits(
      long wireBytes,
      int requests,
      Duration time,
      long decodedBytes,
      int chunkBytes,
      int cacheBytes) {
    Limits {
      if (wireBytes < 1
          || requests < 1
          || time.isNegative()
          || time.isZero()
          || time.compareTo(Duration.ofHours(1)) > 0
          || decodedBytes < 1
          || chunkBytes < 204
          || chunkBytes > 64 * 1024 * 1024
          || cacheBytes < 1
          || cacheBytes > 16 * 1024 * 1024)
        throw new IllegalArgumentException("Invalid diagnostic budgets");
    }

    static Limits defaults() {
      return new Limits(
          96L * 1024 * 1024,
          768,
          Duration.ofMinutes(10),
          256L * 1024 * 1024,
          4 * 1024 * 1024,
          2 * 1024 * 1024);
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

    boolean cancelled() {
      return cancelled.getAsBoolean() || nanos.getAsLong() - started >= limits.time.toNanos();
    }

    void check() {
      if (cancelled.getAsBoolean()) throw new CancellationException("Diagnostic cancelled");
      if (remainingNanos() <= 0) throw new Refusal("Total diagnostic time budget exceeded");
    }

    long remainingNanos() {
      return limits.time.toNanos() - (nanos.getAsLong() - started);
    }

    void reserve(long bytes) {
      check();
      if (bytes < 1 || bytes > limits.wireBytes - wire || requests >= limits.requests)
        throw new Refusal("Wire-byte/request budget refuses next transfer: " + bytes);
      wire += bytes;
      requests++;
    }

    void settle(long reserved, long actual) {
      if (actual < 0 || actual > reserved)
        throw new Refusal("Source exceeded reserved wire budget");
      wire -= reserved - actual;
      check();
    }

    void requireWork(long bytes, long decodedBytes, int requestCount) {
      check();
      if (bytes < 0
          || bytes > limits.wireBytes - wire
          || decodedBytes < 0
          || decodedBytes > limits.decodedBytes - decoded
          || requestCount < 0
          || requestCount > limits.requests - requests)
        throw new Refusal("Planned scope exceeds remaining wire/request/decode budget");
    }

    void decode(long bytes) {
      check();
      if (bytes < 0 || bytes > limits.decodedBytes - decoded)
        throw new Refusal("Total decoded-work budget exceeded");
      decoded += bytes;
    }
  }

  final Source source;
  final CdnRequest.Source route;
  final Budget budget;
  final Consumer<String> output;
  final Map<String, Wdc5Header> headerResults = new LinkedHashMap<>();
  final BlteChunkPlanner blte = new BlteChunkPlanner();
  final IndexedEncodingParser en = new IndexedEncodingParser();

  ItemCohortProbe(Source source, CdnRequest.Source route, Budget budget, Consumer<String> output) {
    this.source = source;
    this.route = route;
    this.budget = budget;
    this.output = output;
  }

  TactConfig config(String key) throws Exception {
    var request = CdnRequest.configuration(route, key);
    output.accept("TRANSFER config " + request.uri() + " maximumBytes=" + request.maximumBytes());
    budget.reserve(request.maximumBytes());
    var result = source.configuration(request);
    if (!request.equals(result.request()))
      throw new Refusal("Configuration source identity changed");
    byte[] bytes = result.bytes();
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
    output.accept(
        "VERIFIED configMD5="
            + parsed.rawMd5()
            + " source="
            + request.uri()
            + " received="
            + result.receivedAt()
            + " bytes="
            + bytes.length);
    return parsed;
  }

  byte[] range(String key, long whole, long start, int count) throws Exception {
    var request =
        new CdnRangeRequest(
            route, key, whole, start, count, Math.min(64 * 1024 * 1024, Math.max(count, 1)));
    output.accept(
        "TRANSFER range "
            + request.uri()
            + " start="
            + start
            + " count="
            + count
            + " whole="
            + whole);
    budget.reserve(count);
    var result = source.range(request);
    if (!request.equals(result.request()) || !request.uri().equals(result.actualSourceUri()))
      throw new Refusal("Range source identity changed");
    byte[] bytes = result.bytes();
    budget.settle(count, bytes.length);
    output.accept(
        "RECEIVED range source="
            + result.actualSourceUri()
            + " at="
            + result.receivedAt()
            + " sha256="
            + result.rangeSha256());
    return bytes;
  }

  final class ObjectPlan {
    final String key;
    final BlteChunkPlanner.AuthenticatedPlan plan;
    final Map<Integer, byte[]> cache = new LinkedHashMap<>();
    int cached;

    ObjectPlan(String key, long encoded, long decoded) throws Exception {
      this.key = key;
      output.accept(
          "PLAN object EKey="
              + key
              + " encoded="
              + encoded
              + " decoded="
              + decoded
              + " scope=authenticated-selected-chunks-only");
      byte[] prefix = range(key, encoded, 0, 8);
      if (prefix[0] != 'B' || prefix[1] != 'L' || prefix[2] != 'T' || prefix[3] != 'E')
        throw new Refusal("Unsupported encoded signature");
      long header =
          ((long) (prefix[4] & 255) << 24)
              | ((long) (prefix[5] & 255) << 16)
              | ((long) (prefix[6] & 255) << 8)
              | (prefix[7] & 255);
      if (header < 12 || header > 1024 * 1024 || header >= encoded)
        throw new Refusal("Unsupported/over-budget BLTE header size=" + header);
      byte[] bytes = range(key, encoded, 0, (int) header);
      plan =
          blte.authenticate(
              bytes,
              HexFormat.of().parseHex(key),
              encoded,
              decoded,
              new BlteChunkPlanner.Limits(
                  1024 * 1024,
                  40000,
                  2L * 1024 * 1024 * 1024,
                  2L * 1024 * 1024 * 1024,
                  512 * 1024 * 1024,
                  512 * 1024 * 1024),
              budget::cancelled);
      output.accept("VERIFIED BLTE header EKey=" + key + " chunks=" + plan.chunks().size());
    }

    byte[] chunk(BlteChunkPlanner.Chunk chunk, boolean retain) throws Exception {
      byte[] existing = cache.get(chunk.index());
      if (existing != null) return existing;
      if (chunk.encodedBytes() > budget.limits.chunkBytes
          || chunk.decodedBytes() > budget.limits.chunkBytes)
        throw new Refusal(
            "Full chunk buffer budget refuses chunk="
                + chunk.index()
                + " encoded="
                + chunk.encodedBytes()
                + " decoded="
                + chunk.decodedBytes());
      if (retain && chunk.decodedBytes() > budget.limits.cacheBytes - cached)
        throw new Refusal("Retained decoded-chunk cache budget refuses transfer");
      budget.decode(chunk.decodedBytes());
      byte[] encoded =
          range(key, plan.wholeEncodedBytes(), chunk.encodedOffset(), chunk.encodedBytes());
      byte[] decoded = blte.decodeSelected(plan, chunk, encoded, budget::cancelled).decodedBytes();
      output.accept(
          "VERIFIED chunk EKey="
              + key
              + " index="
              + chunk.index()
              + " encodedMD5+decodedMode scope=chunk-only");
      if (retain) {
        if (decoded.length > budget.limits.cacheBytes - cached)
          throw new Refusal("Retained decoded-chunk cache budget exceeded");
        cache.put(chunk.index(), decoded);
        cached += decoded.length;
      }
      return decoded;
    }

    byte[] authenticatedWindow(long start, int length) throws Exception {
      return authenticatedWindow(start, length, true);
    }

    byte[] authenticatedWindow(long start, int length, boolean retain) throws Exception {
      if (length < 0
          || length > budget.limits.cacheBytes
          || start < 0
          || start > plan.wholeDecodedBytes() - length)
        throw new Refusal("Decoded window bounds exceeded");
      byte[] result = new byte[length];
      int written = 0;
      for (var chunk : plan.chunks()) {
        long from = Math.max(start, chunk.decodedOffset()),
            to = Math.min(start + length, chunk.decodedOffset() + chunk.decodedBytes());
        if (from >= to) continue;
        byte[] decoded = chunk(chunk, retain);
        int count = (int) (to - from);
        System.arraycopy(
            decoded, (int) (from - chunk.decodedOffset()), result, (int) (from - start), count);
        written += count;
      }
      if (written != length)
        throw new Refusal("Decoded window lacks complete authenticated chunk coverage");
      return result;
    }

    /**
     * Bytes are NOT authenticated here. The EN parser must authenticate the selected page against
     * its authenticated index before use.
     */
    byte[] untrustedPageWindow(long start, int length) throws Exception {
      if (length < 1 || length > 1024 * 1024) throw new Refusal("Selected EN page exceeds bounds");
      for (var chunk : plan.chunks()) {
        if (start < chunk.decodedOffset()
            || start > chunk.decodedOffset() + chunk.decodedBytes() - length) continue;
        if (chunk.encodedBytes() == (long) chunk.decodedBytes() + 1) {
          byte[] mode = range(key, plan.wholeEncodedBytes(), chunk.encodedOffset(), 1);
          if (mode[0] != 'N')
            throw new Refusal(
                "Selected-page direct range requires raw N representation; mode alone is not a"
                    + " proof");
          budget.decode(length);
          return range(
              key,
              plan.wholeEncodedBytes(),
              chunk.encodedOffset() + 1 + (start - chunk.decodedOffset()),
              length);
        }
        byte[] bytes = chunk(chunk, false);
        return Arrays.copyOfRange(
            bytes,
            (int) (start - chunk.decodedOffset()),
            (int) (start - chunk.decodedOffset()) + length);
      }
      throw new Refusal("Selected EN page crosses unsupported chunk boundaries");
    }

    InputStream completeDecodedStream() {
      return new InputStream() {
        int index, offset;
        byte[] current = new byte[0];

        @Override
        public int read(byte[] bytes, int start, int length) throws IOException {
          Objects.checkFromIndexSize(start, length, bytes.length);
          if (length == 0) return 0;
          try {
            budget.check();
            while (offset == current.length) {
              current = new byte[0];
              offset = 0;
              if (index == plan.chunks().size()) return -1;
              current = chunk(plan.chunks().get(index++), false);
            }
            int count = Math.min(length, current.length - offset);
            System.arraycopy(current, offset, bytes, start, count);
            offset += count;
            return count;
          } catch (Exception failure) {
            throw new IOException("Authenticated root chunk stream failed", failure);
          }
        }

        @Override
        public int read() throws IOException {
          byte[] one = new byte[1];
          return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public void close() {
          current = new byte[0];
        }
      };
    }
  }

  final class EncodingLookup {
    final ObjectPlan object;
    final IndexedEncodingParser.Layout layout;
    final IndexedEncodingParser.PageIndex content, encoding;

    EncodingLookup(TactConfig.Bootstrap bootstrap) throws Exception {
      object =
          new ObjectPlan(bootstrap.encodingKey(), bootstrap.encodedSize(), bootstrap.decodedSize());
      layout = en.parseHeader(object.authenticatedWindow(0, 22), EN_LIMITS);
      if (layout.minimumDecodedBytes() > bootstrap.decodedSize())
        throw new Refusal("EN layout exceeds pinned decoded source length");
      content = index(IndexedEncodingParser.Table.CONTENT);
      encoding = index(IndexedEncodingParser.Table.ENCODING);
    }

    IndexedEncodingParser.PageIndex index(IndexedEncodingParser.Table table) throws Exception {
      long size = layout.pageCount(table) * 32;
      if (size > EN_LIMITS.maxIndexBytes()) throw new Refusal("EN index budget exceeded");
      return en.parseIndex(
          layout,
          table,
          object.authenticatedWindow(layout.indexOffset(table), (int) size),
          EN_LIMITS,
          budget::cancelled);
    }

    List<TactConfig.Bootstrap> resolve(String contentKey) throws Exception {
      var page =
          content
              .pageFor(contentKey)
              .orElseThrow(
                  () -> new Refusal("No authenticated EN candidate page for CKey=" + contentKey));
      var found =
          en.lookupContent(
                  content,
                  page,
                  object.untrustedPageWindow(page.decodedOffset(), page.length()),
                  contentKey,
                  EN_LIMITS,
                  budget::cancelled)
              .orElseThrow(
                  () ->
                      new Refusal("CKey absent from authenticated selected EN page=" + contentKey));
      var results = new ArrayList<TactConfig.Bootstrap>();
      for (String key : found.encodingKeys()) {
        var reference =
            encoding
                .pageFor(key)
                .orElseThrow(
                    () -> new Refusal("No authenticated EN encoded metadata page for EKey=" + key));
        var metadata =
            en.lookupEncoding(
                    encoding,
                    reference,
                    object.untrustedPageWindow(reference.decodedOffset(), reference.length()),
                    key,
                    EN_LIMITS,
                    budget::cancelled)
                .orElseThrow(() -> new Refusal("EKey metadata unavailable=" + key));
        results.add(
            new TactConfig.Bootstrap(contentKey, key, found.decodedSize(), metadata.encodedSize()));
      }
      output.accept(
          "VERIFIED selected EN pages CKey="
              + contentKey
              + " candidates="
              + results
              + " wholeENCKeyVerified=false");
      return List.copyOf(results);
    }
  }

  SelectedRootMemberships verifiedRoot(TactConfig.Bootstrap root) throws Exception {
    var object = new ObjectPlan(root.encodingKey(), root.encodedSize(), root.decodedSize());
    long bodyBytes =
        object.plan.chunks().stream().mapToLong(BlteChunkPlanner.Chunk::encodedBytes).sum();
    output.accept(
        "ROOT COMPLETE STREAM PLAN transferBytes="
            + bodyBytes
            + " decodedWorkBytes="
            + root.decodedSize()
            + " chunks="
            + object.plan.chunks().size());
    budget.requireWork(bodyBytes, root.decodedSize(), object.plan.chunks().size());
    for (var chunk : object.plan.chunks()) {
      if (chunk.encodedBytes() > budget.limits.chunkBytes()
          || chunk.decodedBytes() > budget.limits.chunkBytes()) {
        throw new Refusal("Root stream preflight refuses oversized later chunk=" + chunk.index());
      }
    }
    MessageDigest digest = MessageDigest.getInstance("MD5");
    SelectedRootMemberships memberships;
    try (var decoded = object.completeDecodedStream();
        var stream = new java.security.DigestInputStream(decoded, digest)) {
      memberships =
          new SelectedRootLookup()
              .read(
                  stream,
                  IDS,
                  new SelectedRootLookup.Limits(
                      root.decodedSize(), 20_000_000, 20000, 20_000_000, 2, 256),
                  budget::cancelled);
    }
    if (memberships.decodedBytesRead() != root.decodedSize()
        || !HexFormat.of().formatHex(digest.digest()).equals(root.contentKey()))
      throw new Refusal("Complete root decoded CKey/length mismatch; memberships withheld");
    output.accept(
        "VERIFIED COMPLETE ROOT CKey="
            + root.contentKey()
            + " decoded="
            + memberships.decodedBytesRead()
            + " retainedVariants="
            + memberships.matchCount());
    return memberships;
  }

  void run(String buildConfig, String cdnConfig) throws Exception {
    var build = config(buildConfig);
    config(cdnConfig);
    if (buildConfig.equals(BUILD)
        && (!build
                .values()
                .getOrDefault("build-name", List.of())
                .equals(List.of("WOW-70448patch12.1.5_Retail"))
            || !build.values().getOrDefault("build-uid", List.of()).equals(List.of("wow"))))
      throw new Refusal("Pinned build metadata differs from selected exact product/version");
    output.accept(
        "PINNED BUILD observedBuildName="
            + build.values().getOrDefault("build-name", List.of())
            + " observedBuildUid="
            + build.values().getOrDefault("build-uid", List.of()));
    var encoding = new EncodingLookup(build.encodingBootstrap());
    var rootKeys = build.values().get("root");
    if (rootKeys == null || rootKeys.size() != 1 || !rootKeys.getFirst().matches("[0-9a-fA-F]{32}"))
      throw new Refusal("Exactly one pinned root CKey required");
    var candidates = encoding.resolve(rootKeys.getFirst().toLowerCase(Locale.ROOT));
    // Alternative EKeys encode identical decoded root content; choose by declared cost, explicitly.
    var selected =
        candidates.stream()
            .min(
                Comparator.comparingLong(TactConfig.Bootstrap::encodedSize)
                    .thenComparing(TactConfig.Bootstrap::encodingKey))
            .orElseThrow();
    output.accept(
        "ROOT CANDIDATES preserved="
            + candidates
            + " selectedPolicy=smallest-declared-encoded-size selected="
            + selected.encodingKey());
    var memberships = verifiedRoot(selected);
    for (long id : new TreeSet<>(IDS)) {
      var variants = memberships.findFileDataId(id);
      output.accept("VERIFIED ROOT MEMBERSHIP FileDataID=" + id + " variants=" + variants);
    }
    for (long id : new TreeSet<>(IDS))
      for (var variant : memberships.findFileDataId(id))
        for (var candidate : encoding.resolve(variant.contentKey()))
          inspect(id, variant, candidate);
    output.accept(
        "COST requests="
            + budget.requests
            + " wireBytes="
            + budget.wire
            + " decodedWorkBytes="
            + budget.decoded
            + " elapsedNanos="
            + (budget.nanos.getAsLong() - budget.started));
  }

  void inspect(
      long id,
      io.vibeminer.format.wow.root.WowRootManifest.Entry variant,
      TactConfig.Bootstrap candidate)
      throws Exception {
    output.accept(
        "TABLE PLAN FileDataID="
            + id
            + " localeFlags="
            + variant.localeFlags()
            + " contentFlags="
            + variant.contentFlags()
            + " rawFlags="
            + variant.rawContentFlags()
            + " CKey="
            + candidate.contentKey()
            + " EKey="
            + candidate.encodingKey()
            + " wholeEncoded="
            + candidate.encodedSize()
            + " wholeDecoded="
            + candidate.decodedSize()
            + " requestedScope=204-byte-header");
    String identity = candidate.contentKey() + "/" + candidate.encodingKey();
    Wdc5Header header = headerResults.get(identity);
    if (header == null) {
      if (headerResults.size() >= 128)
        throw new Refusal("Retained header-result metadata budget exceeded");
      var object =
          new ObjectPlan(candidate.encodingKey(), candidate.encodedSize(), candidate.decodedSize());
      long cost = 0, decoded = 0;
      int count = 0;
      for (var chunk : object.plan.chunks())
        if (chunk.decodedOffset() < Wdc5HeaderInspector.HEADER_BYTES) {
          cost += chunk.encodedBytes();
          decoded += chunk.decodedBytes();
          count++;
        }
      output.accept(
          "HEADER CHUNK PLAN FileDataID="
              + id
              + " transferBytes="
              + cost
              + " decodedWorkBytes="
              + decoded
              + " fullTableCKeyVerified=false");
      budget.requireWork(cost, decoded, count);
      byte[] prefix = object.authenticatedWindow(0, Wdc5HeaderInspector.HEADER_BYTES, false);
      try {
        header =
            new Wdc5HeaderInspector()
                .inspect(
                    prefix,
                    new Wdc5HeaderInspector.Limits(
                        204, 20_000_000, 10000, 10000, 1024 * 1024, 2L * 1024 * 1024 * 1024),
                    budget::cancelled);
      } catch (Db2HeaderException unsupported) {
        output.accept(
            "HEADER OUTCOME FileDataID="
                + id
                + " "
                + unsupported.reason()
                + " "
                + unsupported.getMessage()
                + " fullTableCKeyVerified=false");
        return;
      }
      headerResults.put(identity, header);
    } else
      output.accept(
          "REUSE authenticated header-only result CKey/EKey=" + identity + " noNewTransfer=true");
    long expectedTable = id == 841626L ? 0x50238ec2L : 0x919be54eL,
        expectedLayout = id == 841626L ? 0x996192aaL : 0x1c17d17fL;
    output.accept(
        "HEADER OBSERVED FileDataID="
            + id
            + " signature=WDC5 schema="
            + header.schemaVersion()
            + " schemaString="
            + header.schemaString()
            + " tableHash="
            + String.format("%08X", header.tableHash())
            + " layoutHash="
            + String.format("%08X", header.layoutHash())
            + " headerData="
            + header
            + " flags="
            + header.flags()
            + " records="
            + header.recordCount()
            + " sections="
            + header.sectionCount()
            + " candidateTableLayoutMatch="
            + (header.tableHash() == expectedTable && header.layoutHash() == expectedLayout)
            + " candidateRevision=b9a63096cdd99b26967f17f9515377ae62cadfd7"
            + " fullTableCKeyVerified=false bodyValidated=false rowsSupported=false gate="
            + header.gate(null));
  }

  static final class LiveSource implements Source {
    final PinnedCdnTransport configs = new PinnedCdnTransport();
    final PinnedCdnRangeTransport ranges = new PinnedCdnRangeTransport();
    final Budget budget;

    LiveSource(Budget budget) {
      this.budget = budget;
    }

    <T> T await(CompletableFuture<T> future) throws Exception {
      try {
        return future.get(Math.max(1, budget.remainingNanos()), TimeUnit.NANOSECONDS);
      } catch (InterruptedException interrupted) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw interrupted;
      } catch (TimeoutException timeout) {
        future.cancel(true);
        throw new Refusal("Total diagnostic time budget expired during transfer");
      }
    }

    public AcquiredBytes configuration(CdnRequest request) throws Exception {
      return await(configs.fetch(request));
    }

    public AcquiredRange range(CdnRangeRequest request) throws Exception {
      return await(ranges.fetch(request));
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      ItemCohortProbeSelfTest.main(new String[0]);
      return;
    }
    if (args.length < 1 || !args[0].equals("--live"))
      throw new IllegalArgumentException(
          "Use --self-test (offline) or explicit --live [--max-wire-bytes=N --max-requests=N"
              + " --max-seconds=N --max-decoded-bytes=N --max-chunk-bytes=N --max-cache-bytes=N]");
    var defaults = Limits.defaults();
    var options = new HashMap<String, Long>();
    for (int i = 1; i < args.length; i++) {
      if (!args[i].matches(
          "--max-(wire-bytes|requests|seconds|decoded-bytes|chunk-bytes|cache-bytes)=[0-9]+"))
        throw new IllegalArgumentException("Unsupported diagnostic option");
      int at = args[i].indexOf('=');
      if (options.putIfAbsent(args[i].substring(2, at), Long.parseLong(args[i].substring(at + 1)))
          != null) throw new IllegalArgumentException("Duplicate diagnostic option");
    }
    var limits =
        new Limits(
            options.getOrDefault("max-wire-bytes", defaults.wireBytes),
            Math.toIntExact(options.getOrDefault("max-requests", (long) defaults.requests)),
            Duration.ofSeconds(options.getOrDefault("max-seconds", defaults.time.toSeconds())),
            options.getOrDefault("max-decoded-bytes", defaults.decodedBytes),
            Math.toIntExact(options.getOrDefault("max-chunk-bytes", (long) defaults.chunkBytes)),
            Math.toIntExact(options.getOrDefault("max-cache-bytes", (long) defaults.cacheBytes)));
    var budget = new Budget(limits, System::nanoTime, () -> Thread.currentThread().isInterrupted());
    System.out.println(
        "LIVE OPT-IN product=wow region=us version=12.1.5.70448 buildConfig="
            + BUILD
            + " cdnConfig="
            + CDN
            + " route="
            + OFFICIAL
            + " budgets="
            + limits
            + " noGameBytesSaved=true");
    try {
      new ItemCohortProbe(new LiveSource(budget), OFFICIAL, budget, System.out::println)
          .run(BUILD, CDN);
    } catch (Exception failure) {
      System.out.println(
          "DIAGNOSTIC STOP outcome="
              + failure.getClass().getSimpleName()
              + " message="
              + failure.getMessage()
              + " reservedOrReceivedWireBytes="
              + budget.wire
              + " requestReservations="
              + budget.requests
              + " decodedWorkReservations="
              + budget.decoded
              + " remainingCandidatesNotAcquired=true noGameBytesSaved=true");
      throw failure;
    }
  }
}
