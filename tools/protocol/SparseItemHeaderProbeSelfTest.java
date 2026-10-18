import io.vibeminer.infrastructure.acquisition.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Generated EN/index/archive/BLTE fixtures; no game files or network. */
public final class SparseItemHeaderProbeSelfTest {
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
      throw new AssertionError("Expected failure");
    } catch (AssertionError e) {
      throw e;
    } catch (Exception expected) {
      return expected;
    }
  }

  static String hash(String algorithm, byte[] bytes) throws Exception {
    return ItemCohortProbeSelfTest.hash(algorithm, bytes);
  }

  static byte[] md5(byte[] bytes) throws Exception {
    return ItemCohortProbeSelfTest.md5(bytes);
  }

  static byte[] concat(byte[]... bytes) throws Exception {
    return ItemCohortProbeSelfTest.concat(bytes);
  }

  static final class Fixture implements ArchivedItemHeaderProbe.Source {
    final Map<String, byte[]> configs = new HashMap<>(), indexes = new HashMap<>();
    final List<String> calls = new ArrayList<>(), events = new ArrayList<>();
    final List<CdnRangeRequest> ranges = new ArrayList<>();
    final byte[] sparse, encoding, archive;
    final String ckey, ekey, enKey, archiveKey, buildKey, cdnKey;
    final int offset = 64, headerBytes;
    Exception looseFailure =
        new AcquisitionException(
            AcquisitionException.Reason.UNAVAILABLE, "Pinned range unavailable (HTTP 404)");
    boolean corruptENPage, corruptChild, changedRangeSource, lateLoose404;

    Fixture() throws Exception {
      byte[] decoded = ItemCohortProbeSelfTest.table(0x919be54eL, 0x1c17d17fL, 1);
      ckey = hash("MD5", decoded);
      sparse =
          ItemCohortProbeSelfTest.blte(
              Arrays.copyOfRange(decoded, 0, 204),
              Arrays.copyOfRange(decoded, 204, decoded.length));
      ekey = ItemCohortProbeSelfTest.ekey(sparse);
      headerBytes = ByteBuffer.wrap(sparse).getInt(4);
      var missing = ArchivedItemHeaderProbeSelfTest.index(String.format("%032x", 1), 7, 0);
      var mapping = ArchivedItemHeaderProbeSelfTest.index(ekey, sparse.length, offset);
      archiveKey = mapping.key();
      indexes.put(missing.key(), missing.bytes());
      indexes.put(mapping.key(), mapping.bytes());
      archive = new byte[offset + sparse.length + 16];
      System.arraycopy(sparse, 0, archive, offset, sparse.length);
      var content = ByteBuffer.allocate(1024);
      content.put((byte) 1);
      ItemCohortProbeSelfTest.u40(content, decoded.length);
      content.put(HexFormat.of().parseHex(ckey)).put(HexFormat.of().parseHex(ekey));
      byte[] ci = concat(HexFormat.of().parseHex(ckey), md5(content.array()));
      var encoded = ByteBuffer.allocate(1024);
      encoded.put(HexFormat.of().parseHex(ekey)).putInt(0);
      ItemCohortProbeSelfTest.u40(encoded, sparse.length);
      byte[] ei = concat(HexFormat.of().parseHex(ekey), md5(encoded.array()));
      var en = ByteBuffer.allocate(22);
      en.put(new byte[] {'E', 'N', 1, 16, 16})
          .putShort((short) 1)
          .putShort((short) 1)
          .putInt(1)
          .putInt(1)
          .put((byte) 0)
          .putInt(0);
      byte[] enDecoded = concat(en.array(), ci, content.array(), ei, encoded.array());
      encoding = ItemCohortProbeSelfTest.blte(en.array(), ci, content.array(), ei, encoded.array());
      enKey = ItemCohortProbeSelfTest.ekey(encoding);
      byte[] build =
          ("root = "
                  + SparseItemHeaderProbe.ROOT
                  + "\nencoding = "
                  + hash("MD5", enDecoded)
                  + " "
                  + enKey
                  + "\nencoding-size = "
                  + enDecoded.length
                  + " "
                  + encoding.length
                  + "\n")
              .getBytes(StandardCharsets.UTF_8);
      byte[] cdn =
          ("archives = "
                  + missing.key()
                  + " "
                  + mapping.key()
                  + "\narchives-index-size = 135988 4148\n")
              .getBytes(StandardCharsets.UTF_8);
      buildKey = hash("MD5", build);
      cdnKey = hash("MD5", cdn);
      configs.put(buildKey, build);
      configs.put(cdnKey, cdn);
    }

    List<SparseItemHeaderProbe.RootVariant> variants() {
      return List.of(
          SparseItemHeaderProbe.variant("1".repeat(32), 4),
          SparseItemHeaderProbe.variant(ckey, 0x202));
    }

    public AcquiredBytes configuration(CdnRequest request) throws Exception {
      calls.add("config");
      byte[] bytes = configs.get(request.key());
      return new AcquiredBytes(request, Instant.EPOCH, bytes, hash("SHA-256", bytes), true);
    }

    public AcquiredRange range(CdnRangeRequest request) throws Exception {
      calls.add("range:" + request.encodedKey());
      ranges.add(request);
      byte[] whole;
      if (request.encodedKey().equals(enKey)) whole = encoding;
      else if (request.resourceKind() == CdnResource.Kind.INDEX)
        whole = indexes.get(request.encodedKey());
      else if (request.encodedKey().equals(ekey)) {
        if (looseFailure != null) throw looseFailure;
        if (lateLoose404 && request.count() != 8)
          throw new AcquisitionException(
              AcquisitionException.Reason.UNAVAILABLE, "Pinned range unavailable (HTTP 404)");
        whole = sparse;
      } else if (request.encodedKey().equals(archiveKey)) whole = archive;
      else throw new AssertionError("Unknown fixture resource");
      check(
          whole.length == request.wholeEncodedLength(),
          "All ranges retain correct independent total");
      byte[] bytes =
          Arrays.copyOfRange(
              whole, (int) request.start(), (int) (request.start() + request.count()));
      if (corruptENPage && request.encodedKey().equals(enKey) && request.count() == 1024)
        bytes[0] ^= 1;
      if (corruptChild
          && ((request.encodedKey().equals(archiveKey) && request.start() >= offset + headerBytes)
              || (request.encodedKey().equals(ekey) && request.start() >= headerBytes)))
        bytes[bytes.length - 1] ^= 1;
      if (changedRangeSource) {
        var other =
            new CdnRangeRequest(
                request.source(),
                "f".repeat(32),
                request.wholeEncodedLength(),
                request.start(),
                request.count(),
                request.maximumBytes(),
                request.resourceKind());
        return new AcquiredRange(other, other.uri(), Instant.EPOCH, bytes, hash("SHA-256", bytes));
      }
      return new AcquiredRange(
          request, request.uri(), Instant.EPOCH, bytes, hash("SHA-256", bytes));
    }

    public IndexFooterObservation footer(CdnIndexFooterRequest request) throws Exception {
      calls.add("footer");
      byte[] bytes = indexes.get(request.resource().key());
      byte[] footer = Arrays.copyOfRange(bytes, bytes.length - 28, bytes.length);
      return new IndexFooterObservation(
          request,
          request.resource().uri(),
          Instant.EPOCH,
          bytes.length,
          footer,
          hash("SHA-256", footer),
          Optional.empty(),
          Optional.empty());
    }

    public DataSizeObservation size(CdnSizeRequest request) {
      calls.add("HEAD");
      return new DataSizeObservation(
          request,
          request.resource().uri(),
          Instant.EPOCH,
          archive.length,
          Optional.empty(),
          Optional.empty());
    }

    SparseItemHeaderProbe probe(ArchivedItemHeaderProbe.Limits limits) {
      return new SparseItemHeaderProbe(
          this,
          ArchivedItemHeaderProbe.ROUTE,
          new ArchivedItemHeaderProbe.Budget(limits, () -> 0L, () -> false),
          events::add);
    }

    SparseItemHeaderProbe run() throws Exception {
      var probe = probe(SparseItemHeaderProbe.defaults());
      probe.run(buildKey, cdnKey, variants());
      return probe;
    }
  }

  static void localeSelectionPreservesSharedContentAndRejectsAmbiguity() throws Exception {
    check(
        SparseItemHeaderProbe.selectEnUS(SparseItemHeaderProbe.PRIOR_VARIANTS)
            .equals(SparseItemHeaderProbe.EN_US_CKEY),
        "Real prior metadata selects exact shared0x202 CKey by enUS bit");
    var f = new Fixture();
    check(
        SparseItemHeaderProbe.selectEnUS(
                List.of(
                    SparseItemHeaderProbe.variant(f.ckey, 0x202),
                    SparseItemHeaderProbe.variant(f.ckey, 2)))
            .equals(f.ckey),
        "Shared CKey membership does not require duplicate content");
    failure(
        () -> SparseItemHeaderProbe.selectEnUS(List.of(SparseItemHeaderProbe.variant(f.ckey, 4))));
    failure(
        () ->
            SparseItemHeaderProbe.selectEnUS(
                List.of(
                    SparseItemHeaderProbe.variant(f.ckey, 2),
                    SparseItemHeaderProbe.variant("f".repeat(32), 2))));
  }

  static void archiveFallbackHasOneGlobalCounterAndHeaderOnlyEvidence() throws Exception {
    var f = new Fixture();
    var probe = f.run();
    check(
        probe.budget.requests == f.calls.size(),
        "Global counter charges each raw source call once");
    check(
        probe.budget.wire == probe.source.receivedBodyBytes + 8,
        "404 failed prefix retains conservative eight-byte reservation");
    check(
        f.calls.stream().anyMatch(s -> s.equals("footer")),
        "Explicit404 branch uses archive index proof");
    check(
        f.events.stream()
            .anyMatch(
                s ->
                    s.startsWith("SPARSE HEADER OBSERVED")
                        && s.contains("tableHash=919BE54E")
                        && s.contains("layoutHash=1C17D17F")
                        && s.contains("candidateTableLayoutMatch=true")
                        && s.contains("fullTableCKeyVerified=false")
                        && s.contains("encryptedSectionsUnknown=true")),
        "Sparse prefix identity observed without body/encryption claims");
    check(
        f.events.stream()
            .anyMatch(s -> s.startsWith("PRIOR ROOT PROOF") && s.contains("rootScanThisRun=false")),
        "No new root scan claimed");
    check(
        f.events.stream()
            .anyMatch(
                s ->
                    s.startsWith("EXPLICIT LOCALE SELECTION")
                        && s.contains("noRegionLocaleInference=true")),
        "Region does not imply locale");
  }

  static void looseSuccessAvoidsArchiveAndOtherFailuresNeverFallback() throws Exception {
    var loose = new Fixture();
    loose.looseFailure = null;
    var probe = loose.run();
    check(
        loose.calls.stream().noneMatch(s -> s.equals("footer") || s.equals("HEAD")),
        "Successful loose header needs no archive scan");
    check(
        probe.budget.wire == probe.source.receivedBodyBytes,
        "No failed reservations on loose success");
    for (int status : new int[] {403, 410, 416, 500}) {
      var f = new Fixture();
      f.looseFailure =
          new AcquisitionException(
              status == 410 || status == 416
                  ? AcquisitionException.Reason.UNAVAILABLE
                  : AcquisitionException.Reason.TRANSPORT,
              "Pinned range unavailable (HTTP " + status + ")");
      failure(f::run);
      check(
          f.calls.stream().noneMatch(s -> s.equals("footer")),
          "Only exact404 permits archive fallback status=" + status);
    }
    var late = new Fixture();
    late.looseFailure = null;
    late.lateLoose404 = true;
    failure(late::run);
    check(
        late.calls.stream().noneMatch(s -> s.equals("footer")),
        "404 after initial loose probe cannot trigger a new fallback");
    check(
        SparseItemHeaderProbe.exactLoose404(
            new ExecutionException(
                new AcquisitionException(
                    AcquisitionException.Reason.UNAVAILABLE,
                    "Pinned range unavailable (HTTP 404)"))),
        "Known exact404 wrapper accepted");
    check(
        !SparseItemHeaderProbe.exactLoose404(
            new AcquisitionException(
                AcquisitionException.Reason.TRANSPORT, "Pinned range unavailable (HTTP 404)")),
        "Wrong typed reason cannot trigger fallback");
    check(
        !SparseItemHeaderProbe.exactLoose404(new RuntimeException("HTTP 404")),
        "Unknown errors do not trigger fallback");
  }

  static void corruptedProofAndSourceNeverProduceHeader() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var f = new Fixture();
      f.corruptENPage = mode == 0;
      f.corruptChild = mode == 1;
      f.changedRangeSource = mode == 2;
      failure(f::run);
      check(
          f.events.stream().noneMatch(s -> s.startsWith("SPARSE HEADER OBSERVED")),
          "Corrupt EN/chunk/source cannot produce header mode=" + mode);
    }
  }

  static ArchivedItemHeaderProbe.Limits limits(long wire, int requests, int chunk, long decode) {
    var d = SparseItemHeaderProbe.defaults();
    return new ArchivedItemHeaderProbe.Limits(
        wire, requests, d.time(), chunk, d.indexBytes(), d.archiveBytes(), decode);
  }

  static void globalAndStageBudgetsStopBeforePayload() throws Exception {
    var d = SparseItemHeaderProbe.defaults();
    var wire = new Fixture();
    failure(
        () ->
            wire.probe(limits(100, 5064, d.chunkBytes(), d.decodedWorkBytes()))
                .run(wire.buildKey, wire.cdnKey, wire.variants()));
    check(wire.calls.isEmpty(), "Global wire refusal precedes config call");
    var count = new Fixture();
    failure(
        () ->
            count
                .probe(limits(d.wireBytes(), 2, d.chunkBytes(), d.decodedWorkBytes()))
                .run(count.buildKey, count.cdnKey, count.variants()));
    check(count.ranges.isEmpty(), "Global request refusal precedes EN ranges");
    var chunk = new Fixture();
    chunk.looseFailure = null;
    failure(
        () ->
            chunk
                .probe(limits(d.wireBytes(), 5064, 204, d.decodedWorkBytes()))
                .run(chunk.buildKey, chunk.cdnKey, chunk.variants()));
    check(
        chunk.ranges.stream().noneMatch(r -> r.encodedKey().equals(chunk.ekey) && r.count() == 205),
        "Sparse buffer bound checked before payload");
    var decode = new Fixture();
    decode.looseFailure = null;
    failure(
        () ->
            decode
                .probe(limits(d.wireBytes(), 5064, d.chunkBytes(), 2337))
                .run(decode.buildKey, decode.cdnKey, decode.variants()));
    check(
        decode.ranges.stream()
            .noneMatch(r -> r.encodedKey().equals(decode.ekey) && r.start() >= decode.headerBytes),
        "Aggregate EN+Sparse decoded work bound precedes selected payload");
  }

  public static void main(String[] args) throws Exception {
    localeSelectionPreservesSharedContentAndRejectsAmbiguity();
    archiveFallbackHasOneGlobalCounterAndHeaderOnlyEvidence();
    looseSuccessAvoidsArchiveAndOtherFailuresNeverFallback();
    corruptedProofAndSourceNeverProduceHeader();
    globalAndStageBudgetsStopBeforePayload();
    System.out.println(
        "Scoped Sparse header synthetic self-test passed: "
            + assertions
            + " assertions; zero network; generated bytes only.");
  }
}
