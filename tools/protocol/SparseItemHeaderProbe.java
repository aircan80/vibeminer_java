import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.db2.*;
import io.vibeminer.protocol.tact.blte.BlteChunkPlanner;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Scoped ItemSparse diagnostic: prior complete root proof, fresh selected EN pages, then one locale
 * target.
 */
public final class SparseItemHeaderProbe {
  static final String BUILD = "88d5e8ef05ec5a8446cdec93d142d7c6",
      CDN = "f065eade5276a5ac05606e60a4c5a1f7",
      ROOT = "e84483ee756c69a7dc5fbff04af81316";
  static final String EN_US_CKEY = "bb0ecbdc2fd4988ddd3c1c5e95c34165";
  static final long EN_US = 0x2;

  record RootVariant(
      String contentKey,
      long localeMask,
      long contentMask,
      long rawFirst,
      long rawSecond,
      int rawThird) {}

  static RootVariant variant(String key, long mask) {
    return new RootVariant(key, mask, 0x12200000L, 0, 0x12000000L, 0x10);
  }

  static final List<RootVariant> PRIOR_VARIANTS =
      List.of(
          variant("d63d64b71fcf522e44ee5031b2176888", 4),
          variant("842b6c12e5966bd775af52aaee2b9a5f", 16),
          variant("7427f6b2e9f1dcd62acf5032b11976ae", 32),
          variant("e2da73586386082e6e040d6337e611fd", 64),
          variant("ac1e3b7844901639026329905d7d0404", 128),
          variant("2285987256838968da916eb4f4847126", 256),
          variant(EN_US_CKEY, 514),
          variant("d11175c3fa6b8b960ea6567f414228d7", 4096),
          variant("340d97eb88f0f5a8395ea61a9a2d7ef6", 8192),
          variant("1f7277e9ac766c2e32bac8fcda7e8ed7", 32768),
          variant("3beaa95acbcb3db0d9e4dcc648635ca3", 81920));

  interface Source extends ArchivedItemHeaderProbe.Source, ItemCohortProbe.Source {}

  static ArchivedItemHeaderProbe.Limits defaults() {
    return new ArchivedItemHeaderProbe.Limits(
        24L * 1024 * 1024,
        5064,
        Duration.ofMinutes(10),
        4 * 1024 * 1024,
        2L * 1024 * 1024,
        8L * 1024 * 1024 * 1024,
        64L * 1024 * 1024);
  }

  /**
   * A single supervisor counts actual network calls across the reused, independently bounded
   * stages.
   */
  static final class BudgetedSource implements Source {
    final ArchivedItemHeaderProbe.Source delegate;
    final ArchivedItemHeaderProbe.Budget budget;
    long receivedBodyBytes;

    BudgetedSource(ArchivedItemHeaderProbe.Source delegate, ArchivedItemHeaderProbe.Budget budget) {
      this.delegate = delegate;
      this.budget = budget;
    }

    public AcquiredBytes configuration(CdnRequest request) throws Exception {
      budget.reserve(request.maximumBytes());
      var result = delegate.configuration(request);
      if (!request.equals(result.request()))
        throw new ArchivedItemHeaderProbe.Refusal("Supervised configuration source changed");
      budget.settle(request.maximumBytes(), result.bytes().length);
      receivedBodyBytes += result.bytes().length;
      return result;
    }

    public AcquiredRange range(CdnRangeRequest request) throws Exception {
      budget.reserve(request.count());
      var result = delegate.range(request);
      if (!request.equals(result.request()) || !request.uri().equals(result.actualSourceUri()))
        throw new ArchivedItemHeaderProbe.Refusal("Supervised exact range source changed");
      budget.settle(request.count(), result.bytes().length);
      receivedBodyBytes += result.bytes().length;
      return result;
    }

    public IndexFooterObservation footer(CdnIndexFooterRequest request) throws Exception {
      budget.reserve(28);
      var result = delegate.footer(request);
      if (!request.equals(result.request())
          || !request.resource().uri().equals(result.actualSourceUri()))
        throw new ArchivedItemHeaderProbe.Refusal("Supervised footer source changed");
      budget.settle(28, result.bytes().length);
      receivedBodyBytes += result.bytes().length;
      return result;
    }

    public DataSizeObservation size(CdnSizeRequest request) throws Exception {
      budget.reserve(0);
      var result = delegate.size(request);
      if (!request.equals(result.request())
          || !request.resource().uri().equals(result.actualSourceUri()))
        throw new ArchivedItemHeaderProbe.Refusal("Supervised HEAD source changed");
      budget.settle(0, 0);
      return result;
    }
  }

  final BudgetedSource source;
  final CdnRequest.Source route;
  final ArchivedItemHeaderProbe.Budget budget;
  final Consumer<String> out;
  ItemCohortProbe.Budget encodingBudget;
  boolean encodingWorkAccounted;

  SparseItemHeaderProbe(
      ArchivedItemHeaderProbe.Source raw,
      CdnRequest.Source route,
      ArchivedItemHeaderProbe.Budget budget,
      Consumer<String> out) {
    this.source = new BudgetedSource(raw, budget);
    this.route = route;
    this.budget = budget;
    this.out = out;
  }

  static String selectEnUS(List<RootVariant> variants) {
    var keys = new LinkedHashSet<String>();
    for (var variant : variants) {
      if ((variant.localeMask & EN_US) != 0) keys.add(variant.contentKey);
    }
    if (keys.size() != 1)
      throw new ArchivedItemHeaderProbe.Refusal(
          "enUS locale bit must select exactly one distinct prior-root CKey; candidate count="
              + keys.size());
    String selected = keys.iterator().next();
    if (!selected.matches("[0-9a-f]{32}"))
      throw new ArchivedItemHeaderProbe.Refusal("Invalid prior-root locale identity");
    return selected;
  }

  /** The existing transport has no numeric status field: recognize its exact known404 form only. */
  static boolean exactLoose404(Throwable failure) {
    while ((failure instanceof ExecutionException || failure instanceof CompletionException)
        && failure.getCause() != null) failure = failure.getCause();
    return failure instanceof AcquisitionException acquisition
        && acquisition.reason() == AcquisitionException.Reason.UNAVAILABLE
        && "Pinned range unavailable (HTTP 404)".equals(acquisition.getMessage());
  }

  long decodedWork() {
    return budget.decoded
        + (!encodingWorkAccounted && encodingBudget != null ? encodingBudget.decoded : 0);
  }

  ArchivedItemHeaderProbe.Limits remainingArchiveLimits() {
    budget.check();
    long bytes = budget.limits.wireBytes() - budget.wire;
    int requests = Math.min(5000, budget.limits.requests() - budget.requests);
    if (bytes < 1 || requests < 1 || budget.limits.decodedWorkBytes() - budget.decoded < 204)
      throw new ArchivedItemHeaderProbe.Refusal("No remaining global archive budget");
    return new ArchivedItemHeaderProbe.Limits(
        bytes,
        requests,
        Duration.ofNanos(budget.remainingNanos()),
        budget.limits.chunkBytes(),
        budget.limits.indexBytes(),
        budget.limits.archiveBytes(),
        budget.limits.decodedWorkBytes() - budget.decoded);
  }

  @FunctionalInterface
  interface EncodedRange {
    byte[] read(long start, int count) throws Exception;
  }

  Wdc5Header inspect(
      ArchivedItemHeaderProbe.Target target, byte[] prefix, EncodedRange reader, String provenance)
      throws Exception {
    if (prefix.length != 8
        || prefix[0] != 'B'
        || prefix[1] != 'L'
        || prefix[2] != 'T'
        || prefix[3] != 'E')
      throw new ArchivedItemHeaderProbe.Refusal("Unsupported Sparse BLTE prefix");
    long header =
        ((long) (prefix[4] & 255) << 24)
            | ((long) (prefix[5] & 255) << 16)
            | ((long) (prefix[6] & 255) << 8)
            | (prefix[7] & 255);
    if (header < 12 || header > 1024 * 1024 || header >= target.encodedBytes())
      throw new ArchivedItemHeaderProbe.Refusal("Unsupported Sparse BLTE header size");
    out.accept("SPARSE BLTE HEADER PLAN bytes=" + header + " provenance=" + provenance);
    var decoder = new BlteChunkPlanner();
    var plan =
        decoder.authenticate(
            reader.read(0, (int) header),
            HexFormat.of().parseHex(target.encodingKey()),
            target.encodedBytes(),
            target.decodedBytes(),
            new BlteChunkPlanner.Limits(
                1024 * 1024,
                40000,
                target.encodedBytes(),
                target.decodedBytes(),
                64 * 1024 * 1024,
                64 * 1024 * 1024),
            budget::cancelled);
    out.accept(
        "VERIFIED SPARSE BLTE HEADER EKey="
            + target.encodingKey()
            + " headerBytes="
            + header
            + " chunks="
            + plan.chunks().size()
            + " provenance="
            + provenance);
    var selected = plan.chunks().stream().filter(chunk -> chunk.decodedOffset() < 204).toList();
    long bytes = 0, decoded = 0;
    for (var chunk : selected) {
      if (chunk.encodedBytes() > budget.limits.chunkBytes()
          || chunk.decodedBytes() > budget.limits.chunkBytes())
        throw new ArchivedItemHeaderProbe.Refusal(
            "Sparse header chunk exceeds bounded encoded/decoded buffer");
      bytes += chunk.encodedBytes();
      decoded += chunk.decodedBytes();
    }
    out.accept(
        "SPARSE HEADER CHUNK PLAN EKey="
            + target.encodingKey()
            + " provenance="
            + provenance
            + " encodedBytes="
            + bytes
            + " decodedWorkBytes="
            + decoded
            + " chunkCount="
            + selected.size()
            + " fullTableCKeyVerified=false");
    budget.require(bytes, selected.size(), decoded);
    byte[] mandatory = new byte[204];
    int copied = 0;
    for (var chunk : selected) {
      budget.decode(chunk.decodedBytes());
      byte[] encoded = reader.read(chunk.encodedOffset(), chunk.encodedBytes());
      byte[] data = decoder.decodeSelected(plan, chunk, encoded, budget::cancelled).decodedBytes();
      int count = (int) Math.min(data.length, 204 - chunk.decodedOffset());
      System.arraycopy(data, 0, mandatory, (int) chunk.decodedOffset(), count);
      copied += count;
      out.accept(
          "VERIFIED SPARSE CHUNK EKey="
              + target.encodingKey()
              + " chunk="
              + chunk.index()
              + " encodedMD5+supportedDecode scope=consumed-chunk-only");
    }
    if (copied != 204)
      throw new ArchivedItemHeaderProbe.Refusal("Incomplete authenticated Sparse mandatory prefix");
    var observed =
        new Wdc5HeaderInspector()
            .inspect(
                mandatory,
                new Wdc5HeaderInspector.Limits(
                    204, 20_000_000, 10000, 10000, 1024 * 1024, 2L * 1024 * 1024 * 1024),
                budget::cancelled);
    out.accept(
        "SPARSE HEADER OBSERVED FileDataID=1572924 requestedLocale=enUS localeBit=0x2 CKey="
            + target.contentKey()
            + " EKey="
            + target.encodingKey()
            + " provenance="
            + provenance
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
            + (observed.tableHash() == 0x919be54eL && observed.layoutHash() == 0x1c17d17fL)
            + " candidateRevision=b9a63096cdd99b26967f17f9515377ae62cadfd7"
            + " fullTableCKeyVerified=false bodyValidated=false rowsSupported=false"
            + " encryptedSectionsUnknown=true rootScanThisRun=false gate="
            + observed.gate(null));
    return observed;
  }

  Wdc5Header run(String buildKey, String cdnKey, List<RootVariant> variants) throws Exception {
    out.accept(
        "PRIOR ROOT PROOF completeRootCKey="
            + ROOT
            + " version=12.1.5.70448 FileDataID=1572924 variants="
            + variants
            + " rootScanThisRun=false");
    String ckey = selectEnUS(variants);
    out.accept(
        "EXPLICIT LOCALE SELECTION requested=enUS bit=0x2 CKey="
            + ckey
            + " retainedOtherVariants="
            + variants.size()
            + " sharedMask0x202=enUS+enGB noRegionLocaleInference=true");
    var enLimits =
        new ItemCohortProbe.Limits(
            Math.min(4L * 1024 * 1024, budget.limits.wireBytes() - budget.wire),
            Math.min(64, budget.limits.requests() - budget.requests),
            Duration.ofNanos(budget.remainingNanos()),
            budget.limits.decodedWorkBytes() - budget.decoded,
            budget.limits.chunkBytes(),
            2 * 1024 * 1024);
    encodingBudget = new ItemCohortProbe.Budget(enLimits, budget.nanos, budget.cancelled);
    var helper = new ItemCohortProbe(source, route, encodingBudget, out);
    var build = helper.config(buildKey);
    var cdn = helper.config(cdnKey);
    if (buildKey.equals(BUILD)
        && (!build
                .values()
                .getOrDefault("build-name", List.of())
                .equals(List.of("WOW-70448patch12.1.5_Retail"))
            || !build.values().getOrDefault("build-uid", List.of()).equals(List.of("wow"))
            || !build.values().getOrDefault("root", List.of()).equals(List.of(ROOT))))
      throw new ArchivedItemHeaderProbe.Refusal(
          "Pinned build metadata/root identity differs from prior exact proof");
    var lookup = helper.new EncodingLookup(build.encodingBootstrap());
    var candidates = lookup.resolve(ckey);
    budget.decode(encodingBudget.decoded);
    encodingWorkAccounted = true;
    var candidate =
        candidates.stream()
            .min(
                Comparator.comparingLong(
                        io.vibeminer.protocol.tact.config.TactConfig.Bootstrap::encodedSize)
                    .thenComparing(
                        io.vibeminer.protocol.tact.config.TactConfig.Bootstrap::encodingKey))
            .orElseThrow();
    out.accept(
        "SPARSE EN CANDIDATES preserved="
            + candidates
            + " chosenPolicy=smallest-declared-encoded-size chosen="
            + candidate.encodingKey()
            + " wholeENCKeyVerified=false");
    var target =
        new ArchivedItemHeaderProbe.Target(
            1572924,
            candidate.contentKey(),
            candidate.encodingKey(),
            candidate.encodedSize(),
            candidate.decodedSize(),
            "Prior complete root CKey "
                + ROOT
                + " plus this-run authenticated selected EN pages; enUS bit0x2 membership; no new"
                + " root scan.");
    out.accept(
        "SPARSE TARGET PLAN "
            + target
            + " looseFirstBytes=8 archiveFallback=explicit-exact-404-only globalBudgets="
            + budget.limits);
    var looseResource = new CdnResource(route, target.encodingKey(), CdnResource.Kind.DATA);
    EncodedRange loose =
        (start, count) -> {
          out.accept(
              "TRANSFER SPARSE LOOSE source="
                  + looseResource.uri()
                  + " start="
                  + start
                  + " count="
                  + count
                  + " whole="
                  + target.encodedBytes());
          var result =
              source.range(
                  new CdnRangeRequest(looseResource, target.encodedBytes(), start, count, count));
          out.accept(
              "RECEIVED SPARSE LOOSE range source="
                  + result.actualSourceUri()
                  + " start="
                  + start
                  + " count="
                  + count
                  + " at="
                  + result.receivedAt()
                  + " SHA256="
                  + result.rangeSha256());
          return result.bytes();
        };
    byte[] prefix;
    try {
      prefix = loose.read(0, 8);
    } catch (Exception failure) {
      if (!exactLoose404(failure)) throw failure;
      out.accept(
          "EXPLICIT LOOSE404 ARCHIVE CHOICE source="
              + looseResource.uri()
              + " preservedFailureReservationBytes=8 noOtherErrorFallback=true");
      var archiveBudget =
          new ArchivedItemHeaderProbe.Budget(
              remainingArchiveLimits(), budget.nanos, budget.cancelled);
      var archives = new ArchivedItemHeaderProbe(source, route, archiveBudget, out);
      var mapping = archives.locate(cdn, target);
      var child = archives.new Child(target, mapping);
      var result =
          inspect(
              target,
              child.range(0, 8),
              child::range,
              "archive="
                  + mapping.size().actualSourceUri()
                  + " offset="
                  + mapping.match().archiveOffset()
                  + " priorRootProof=true");
      cost();
      return result;
    }
    var result =
        inspect(target, prefix, loose, "loose=" + looseResource.uri() + " priorRootProof=true");
    cost();
    return result;
  }

  void cost() {
    out.accept(
        "SPARSE COST globalRequests="
            + budget.requests
            + " completedResponseBodyBytes="
            + source.receivedBodyBytes
            + " reservedOrReceivedBodyBytes="
            + budget.wire
            + " decodedWorkBytes="
            + decodedWork()
            + " elapsedNanos="
            + (budget.nanos.getAsLong() - budget.started));
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      SparseItemHeaderProbeSelfTest.main(new String[0]);
      return;
    }
    if (args.length != 1 || !args[0].equals("--live"))
      throw new IllegalArgumentException(
          "Use --self-test or explicit --live with fixed reviewed budgets");
    var limits = defaults();
    var budget =
        new ArchivedItemHeaderProbe.Budget(
            limits, System::nanoTime, () -> Thread.currentThread().isInterrupted());
    var probe =
        new SparseItemHeaderProbe(
            new ArchivedItemHeaderProbe.LiveSource(budget),
            ArchivedItemHeaderProbe.ROUTE,
            budget,
            System.out::println);
    System.out.println(
        "LIVE OPT-IN enUS ItemSparse exact build12.1.5.70448 globalBudgets="
            + limits
            + " ENscope<=4MiB/64requests noPayloadWrites=true");
    try {
      probe.run(BUILD, CDN, PRIOR_VARIANTS);
    } catch (Exception failure) {
      probe.cost();
      System.out.println(
          "SPARSE STOP outcome="
              + failure.getClass().getSimpleName()
              + " message="
              + failure.getMessage()
              + " remainingScopeUnacquired=true noPayloadWrites=true");
      throw failure;
    }
  }
}
