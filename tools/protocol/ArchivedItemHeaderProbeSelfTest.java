import io.vibeminer.infrastructure.acquisition.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Generated synthetic archive/index/BLTE fixtures only; no live or saved game content. */
public final class ArchivedItemHeaderProbeSelfTest {
  static int assertions;

  static void check(boolean value, String reason) {
    assertions++;
    if (!value) throw new AssertionError(reason);
  }

  interface Work {
    void run() throws Exception;
  }

  static Throwable failure(Work work) throws Exception {
    try {
      work.run();
      throw new AssertionError("Expected refusal");
    } catch (AssertionError e) {
      throw e;
    } catch (Exception expected) {
      return expected;
    }
  }

  static byte[] digest(byte[] bytes) throws Exception {
    return ItemCohortProbeSelfTest.md5(bytes);
  }

  static String hash(String algorithm, byte[] bytes) throws Exception {
    return ItemCohortProbeSelfTest.hash(algorithm, bytes);
  }

  record Index(String key, byte[] bytes) {}

  static Index index(String ekey, int size, int offset) throws Exception {
    byte[] page = new byte[4096];
    var p = ByteBuffer.wrap(page);
    p.put(HexFormat.of().parseHex(ekey)).putInt(size).putInt(offset);
    byte[] toc = new byte[24];
    System.arraycopy(HexFormat.of().parseHex(ekey), 0, toc, 0, 16);
    System.arraycopy(digest(page), 0, toc, 16, 8);
    byte[] footer = new byte[28];
    System.arraycopy(digest(toc), 0, footer, 0, 8);
    footer[8] = 1;
    footer[11] = 4;
    footer[12] = 4;
    footer[13] = 4;
    footer[14] = 16;
    footer[15] = 8;
    ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1);
    System.arraycopy(digest(Arrays.copyOfRange(footer, 8, 28)), 0, footer, 20, 8);
    return new Index(hash("MD5", footer), ItemCohortProbeSelfTest.concat(page, toc, footer));
  }

  static final class Fixture implements ArchivedItemHeaderProbe.Source {
    final Map<String, byte[]> indices = new LinkedHashMap<>();
    final List<String> calls = new ArrayList<>(), events = new ArrayList<>();
    final List<CdnRangeRequest> ranges = new ArrayList<>();
    final ArchivedItemHeaderProbe.Target target;
    final String first, mapping, last;
    final byte[] archive;
    final int offset = 64, headerBytes;
    byte[] config;
    String configKey;
    boolean badConfig, badFooter, badGeometry, badToc, badPage, badSpan, badChunk, substituteSource;

    Fixture() throws Exception {
      this(204, false, false);
    }

    Fixture(int split, boolean sizeConflict, boolean noMatch) throws Exception {
      byte[] decoded = ItemCohortProbeSelfTest.table(0x50238ec2L, 0x996192aaL, 0);
      byte[] encoded =
          ItemCohortProbeSelfTest.blte(
              Arrays.copyOfRange(decoded, 0, split),
              Arrays.copyOfRange(decoded, split, decoded.length));
      String ekey = ItemCohortProbeSelfTest.ekey(encoded);
      headerBytes = ByteBuffer.wrap(encoded).getInt(4);
      target =
          new ArchivedItemHeaderProbe.Target(
              841626,
              hash("MD5", decoded),
              ekey,
              encoded.length,
              decoded.length,
              "Synthetic prior root/EN input only; root not scanned here.");
      var a = index(String.format("%032x", 1), 7, 0);
      var b =
          index(
              noMatch ? String.format("%032x", 2) : ekey,
              encoded.length + (sizeConflict ? 1 : 0),
              offset);
      var c = index(String.format("%032x", 3), 7, 0);
      first = a.key;
      mapping = b.key;
      last = c.key;
      indices.put(a.key, a.bytes);
      indices.put(b.key, b.bytes);
      indices.put(c.key, c.bytes);
      archive = new byte[offset + encoded.length + 16];
      System.arraycopy(encoded, 0, archive, offset, encoded.length);
      setConfig("135988 4148 4148");
    }

    void setConfig(String hints) throws Exception {
      config =
          ("archives = "
                  + first
                  + " "
                  + mapping
                  + " "
                  + last
                  + "\narchives-index-size = "
                  + hints
                  + "\n")
              .getBytes(StandardCharsets.UTF_8);
      configKey = hash("MD5", config);
    }

    public AcquiredBytes configuration(CdnRequest request) throws Exception {
      calls.add("config");
      byte[] data = config.clone();
      if (badConfig) data[0] ^= 1;
      return new AcquiredBytes(request, Instant.EPOCH, data, hash("SHA-256", data), true);
    }

    public IndexFooterObservation footer(CdnIndexFooterRequest request) throws Exception {
      calls.add("footer:" + request.resource().key());
      byte[] full = indices.get(request.resource().key());
      byte[] data = Arrays.copyOfRange(full, full.length - 28, full.length);
      if (badFooter) data[0] ^= 1;
      if (substituteSource) {
        var other =
            new CdnIndexFooterRequest(
                new CdnResource(
                    request.resource().source(), "f".repeat(32), CdnResource.Kind.INDEX),
                request.maximumWholeBytes());
        return new IndexFooterObservation(
            other,
            other.resource().uri(),
            Instant.EPOCH,
            full.length,
            data,
            hash("SHA-256", data),
            Optional.empty(),
            Optional.empty());
      }
      return new IndexFooterObservation(
          request,
          request.resource().uri(),
          Instant.EPOCH,
          full.length + (badGeometry ? 1 : 0),
          data,
          hash("SHA-256", data),
          Optional.empty(),
          Optional.empty());
    }

    public DataSizeObservation size(CdnSizeRequest request) {
      calls.add("HEAD:" + request.resource().key());
      check(
          request.resource().key().equals(mapping), "HEAD only the authenticated mapping archive");
      return new DataSizeObservation(
          request,
          request.resource().uri(),
          Instant.EPOCH,
          badSpan ? offset + target.encodedBytes() - 1 : archive.length,
          Optional.empty(),
          Optional.empty());
    }

    public AcquiredRange range(CdnRangeRequest request) throws Exception {
      calls.add("range:" + request.resourceKind() + ":" + request.encodedKey());
      ranges.add(request);
      byte[] whole =
          request.resourceKind() == CdnResource.Kind.INDEX
              ? indices.get(request.encodedKey())
              : archive;
      check(
          whole.length == request.wholeEncodedLength(),
          "Every exact range pins actual independently authenticated/observed total");
      byte[] data =
          Arrays.copyOfRange(
              whole, (int) request.start(), (int) (request.start() + request.count()));
      if (request.resourceKind() == CdnResource.Kind.INDEX
          && ((badToc && request.start() == 4096) || (badPage && request.start() == 0)))
        data[0] ^= 1;
      if (request.resourceKind() == CdnResource.Kind.DATA
          && badChunk
          && request.start() >= offset + headerBytes) data[data.length - 1] ^= 1;
      return new AcquiredRange(request, request.uri(), Instant.EPOCH, data, hash("SHA-256", data));
    }

    ArchivedItemHeaderProbe probe(ArchivedItemHeaderProbe.Limits limits) {
      return new ArchivedItemHeaderProbe(
          this,
          ArchivedItemHeaderProbe.ROUTE,
          new ArchivedItemHeaderProbe.Budget(limits, () -> 0L, () -> false),
          events::add);
    }

    WdcResult run() throws Exception {
      var result = probe(ArchivedItemHeaderProbe.Limits.defaults()).run(configKey, target);
      return new WdcResult(result.tableHash(), result.layoutHash());
    }
  }

  record WdcResult(long table, long layout) {}

  static void completeSyntheticChainTranslatesAndKeepsPrefixScope() throws Exception {
    var f = new Fixture();
    var result = f.run();
    check(
        result.table == 0x50238ec2L && result.layout == 0x996192aaL,
        "Observed synthetic candidate identity matches");
    check(
        f.calls.stream().noneMatch(call -> call.equals("footer:" + f.last)),
        "Stop scanning on first verified mapping");
    check(
        f.events.stream().anyMatch(e -> e.contains("advertisedSizeHint=135988 hintConflict=true")),
        "Size conflicts retained without controlling routing");
    check(
        f.events.stream()
            .anyMatch(
                e ->
                    e.startsWith("PRIOR PROOF INPUT")
                        && e.contains("root not scanned here")
                        && e.contains("thisRunProof=")),
        "Prior proof report explicit");
    check(
        f.events.stream()
            .anyMatch(
                e ->
                    e.startsWith("HEADER OBSERVED")
                        && e.contains("rootScanThisRun=false")
                        && e.contains("fullTableCKeyVerified=false")
                        && e.contains("bodyValidated=false")
                        && e.contains("rowsSupported=false")),
        "Header-only support never upgraded to full content or records");
    var data = f.ranges.stream().filter(r -> r.resourceKind() == CdnResource.Kind.DATA).toList();
    check(data.size() == 3, "Only child prefix/header and one required chunk acquired");
    check(
        data.getFirst().start() == f.offset && data.getFirst().count() == 8,
        "Initial child offset translated into archive");
    check(
        data.getLast().start() == f.offset + f.headerBytes && data.getLast().count() == 205,
        "Complete selected chunk verified at translated archive offset");
    check(
        data.stream().noneMatch(r -> r.start() >= f.offset + f.headerBytes + 205),
        "Unneeded table tail chunk never fetched");
  }

  static void corruptedProofsNeverPublishHeaderEvidence() throws Exception {
    for (int mode = 0; mode < 7; mode++) {
      var f = new Fixture();
      f.badConfig = mode == 0;
      f.badFooter = mode == 1;
      f.badGeometry = mode == 2;
      f.badToc = mode == 3;
      f.badPage = mode == 4;
      f.badChunk = mode == 5;
      f.substituteSource = mode == 6;
      failure(f::run);
      check(
          f.events.stream().noneMatch(e -> e.startsWith("HEADER OBSERVED")),
          "Invalid proof/source fails before header result mode=" + mode);
    }
  }

  static void identityLengthAndSpanConflictsStopBeforeChildPayload() throws Exception {
    var size = new Fixture(204, true, false);
    failure(size::run);
    check(
        size.calls.stream().noneMatch(call -> call.startsWith("HEAD:")),
        "Target encoded-size mismatch refuses before archive HEAD");
    var span = new Fixture();
    span.badSpan = true;
    failure(span::run);
    check(
        span.ranges.stream().noneMatch(r -> r.resourceKind() == CdnResource.Kind.DATA),
        "Invalid archive child span refuses all child bytes");
    var hints = new Fixture();
    hints.setConfig("1 2");
    hints.run();
    check(
        hints.events.stream().anyMatch(e -> e.startsWith("SIZE HINT VECTOR CONFLICT")),
        "Misaligned hint vector is retained but never remapped");
    var absent = new Fixture(204, false, true);
    var error = failure(absent::run);
    check(
        error.getMessage().contains("checked pinned archive index scope=3"),
        "No match reports only checked archive scope");
    check(
        absent.calls.stream().noneMatch(call -> call.startsWith("HEAD:")),
        "No mapping means no archive payload acquisition");
  }

  static ArchivedItemHeaderProbe.Limits limits(long wire, int requests, int chunk, long decode) {
    var d = ArchivedItemHeaderProbe.Limits.defaults();
    return new ArchivedItemHeaderProbe.Limits(
        wire, requests, d.time(), chunk, d.indexBytes(), d.archiveBytes(), decode);
  }

  static void independentBudgetsRefuseBeforeUnboundedWork() throws Exception {
    var d = ArchivedItemHeaderProbe.Limits.defaults();
    var wire = new Fixture();
    failure(
        () ->
            wire.probe(limits(100, 5000, d.chunkBytes(), d.decodedWorkBytes()))
                .run(wire.configKey, wire.target));
    check(wire.calls.isEmpty(), "Config wire budget checked before any source call");
    var requests = new Fixture();
    failure(
        () ->
            requests
                .probe(limits(d.wireBytes(), 2, d.chunkBytes(), d.decodedWorkBytes()))
                .run(requests.configKey, requests.target));
    check(requests.ranges.isEmpty(), "Request budget refuses TOC before range source call");
    var chunk = new Fixture();
    failure(
        () ->
            chunk
                .probe(limits(d.wireBytes(), 5000, 204, d.decodedWorkBytes()))
                .run(chunk.configKey, chunk.target));
    check(
        chunk.ranges.stream()
            .noneMatch(r -> r.resourceKind() == CdnResource.Kind.DATA && r.count() == 205),
        "Per-buffer refusal precedes oversized child chunk transfer");
    var decode = new Fixture(128, false, false);
    failure(
        () ->
            decode
                .probe(limits(d.wireBytes(), 5000, d.chunkBytes(), 204))
                .run(decode.configKey, decode.target));
    check(
        decode.ranges.stream()
            .noneMatch(
                r ->
                    r.resourceKind() == CdnResource.Kind.DATA
                        && r.start() >= decode.offset + decode.headerBytes),
        "Aggregate decode refusal precedes every selected chunk");
    var cache = new Fixture();
    var smallIndex =
        new ArchivedItemHeaderProbe.Limits(
            d.wireBytes(),
            5000,
            d.time(),
            d.chunkBytes(),
            1000,
            d.archiveBytes(),
            d.decodedWorkBytes());
    failure(() -> cache.probe(smallIndex).run(cache.configKey, cache.target));
    check(cache.ranges.isEmpty(), "Index size ceiling refuses before TOC/page allocation");
    var time = new AtomicLong();
    var expired = new ArchivedItemHeaderProbe.Budget(d, time::get, () -> false);
    time.set(d.time().toNanos());
    failure(() -> expired.reserve(0));
    check(expired.requests == 0, "Time budget checked before zero-body HEAD request");
    var cancelled = new ArchivedItemHeaderProbe.Budget(d, () -> 0L, () -> true);
    failure(() -> cancelled.reserve(0));
    check(cancelled.requests == 0, "Pre-cancel checked before request");
    var head = new ArchivedItemHeaderProbe.Budget(d, () -> 0L, () -> false);
    head.reserve(0);
    check(
        head.wire == 0 && head.requests == 1,
        "HEAD consumes one request but zero response-body bytes");
  }

  public static void main(String[] args) throws Exception {
    completeSyntheticChainTranslatesAndKeepsPrefixScope();
    corruptedProofsNeverPublishHeaderEvidence();
    identityLengthAndSpanConflictsStopBeforeChildPayload();
    independentBudgetsRefuseBeforeUnboundedWork();
    System.out.println(
        "Archived item header synthetic self-test passed: "
            + assertions
            + " assertions; zero network requests; generated bytes only.");
  }
}
