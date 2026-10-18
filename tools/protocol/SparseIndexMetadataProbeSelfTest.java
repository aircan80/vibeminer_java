import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.db2.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Synthetic complete suffix fixtures, authenticated through selected BLTE chunks; no live data. */
public final class SparseIndexMetadataProbeSelfTest {
  static int assertions;

  static void check(boolean value, String why) {
    assertions++;
    if (!value) throw new AssertionError(why);
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

  static final class Fixture implements ArchivedItemHeaderProbe.Source {
    final byte[] encoded;
    final SparseIndexMetadataProbe.PriorScope scope;
    final List<CdnRangeRequest> requests = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    boolean corruptHeader, corruptSelectedChunk, substituteSource, corruptUnrequestedTail;
    Runnable afterHeader = () -> {}, afterMetadata = () -> {};

    Fixture() throws Exception {
      this(300, false, false);
    }

    Fixture(int firstDecoded, boolean oneChunk, boolean relativeOffsets) throws Exception {
      byte[] decoded = metadata(relativeOffsets);
      byte[][] chunks =
          oneChunk
              ? new byte[][] {decoded}
              : new byte[][] {
                Arrays.copyOfRange(decoded, 0, firstDecoded),
                Arrays.copyOfRange(decoded, firstDecoded, 330),
                Arrays.copyOfRange(decoded, 330, 350),
                Arrays.copyOfRange(decoded, 350, 356),
                Arrays.copyOfRange(decoded, 356, 400)
              };
      encoded = ItemCohortProbeSelfTest.blte(chunks);
      int header = ByteBuffer.wrap(encoded).getInt(4);
      var target =
          new ArchivedItemHeaderProbe.Target(
              1572924,
              ItemCohortProbeSelfTest.hash("MD5", decoded),
              ItemCohortProbeSelfTest.ekey(encoded),
              encoded.length,
              decoded.length,
              "Authored prior root/EN metadata only.");
      var prefix =
          new SparseSectionMetadataProbe.PriorPrefix(
              target,
              header,
              chunks[0].length + 1,
              chunks[0].length,
              "Authored prior chunk0 scope.");
      scope =
          new SparseIndexMetadataProbe.PriorScope(
              prefix, 0, 300, 320, 356, "Authored prior sparse suffix geometry.");
    }

    public AcquiredBytes configuration(CdnRequest request) {
      throw new AssertionError("No config refresh");
    }

    public IndexFooterObservation footer(CdnIndexFooterRequest request) {
      throw new AssertionError("No archive-index refresh");
    }

    public DataSizeObservation size(CdnSizeRequest request) {
      throw new AssertionError("No HEAD");
    }

    public AcquiredRange range(CdnRangeRequest request) throws Exception {
      requests.add(request);
      check(
          request.encodedKey().equals(scope.prefix().target().encodingKey()),
          "Only prior exact loose EKey");
      check(request.wholeEncodedLength() == encoded.length, "Whole encoded length stays pinned");
      byte[] bytes =
          Arrays.copyOfRange(
              encoded, (int) request.start(), (int) request.start() + request.count());
      if ((corruptHeader && request.start() == 0)
          || (corruptSelectedChunk
              && request.start()
                  > scope.prefix().headerBytes() + scope.prefix().firstEncodedBytes()))
        bytes[bytes.length - 1] ^= 1;
      if (corruptUnrequestedTail && request.start() >= encoded.length - 45)
        bytes[bytes.length - 1] ^= 1;
      var actual =
          substituteSource
              ? new CdnRangeRequest(
                  request.source(),
                  "f".repeat(32),
                  request.wholeEncodedLength(),
                  request.start(),
                  request.count(),
                  request.count())
              : request;
      var result =
          new AcquiredRange(
              actual,
              actual.uri(),
              Instant.EPOCH,
              bytes,
              ItemCohortProbeSelfTest.hash("SHA-256", bytes));
      if (request.start() == 0) afterHeader.run();
      else if (request.start() == scope.prefix().headerBytes()) afterMetadata.run();
      return result;
    }
  }

  static byte[] metadata(boolean relative) {
    byte[] data = new byte[400];
    var b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {'W', 'D', 'C', '5'})
        .putInt(5)
        .putInt(136, 2)
        .putInt(140, 1)
        .putInt(144, 4)
        .putInt(152, 0x919be54e)
        .putInt(156, 0x1c17d17f)
        .putShort(172, (short) 5)
        .putShort(174, (short) 0xffff)
        .putInt(176, 1)
        .putInt(188, 24)
        .putInt(200, 1);
    b.putLong(204, 0)
        .putInt(212, 300)
        .putInt(216, 2)
        .putInt(224, 320)
        .putInt(228, 8)
        .putInt(236, 2)
        .putInt(240, 1);
    Arrays.fill(data, 300, 320, (byte) 0x55);
    b.position(320);
    b.putInt(42)
        .putInt(43)
        .putInt(90)
        .putInt(42)
        .putInt(relative ? 0 : 300)
        .putShort((short) 8)
        .putInt(relative ? 8 : 308)
        .putShort((short) 12)
        .putInt(42)
        .putInt(43);
    return data;
  }

  static ArchivedItemHeaderProbe.Budget budget(
      ArchivedItemHeaderProbe.Limits limits, AtomicLong time, AtomicBoolean cancel) {
    return new ArchivedItemHeaderProbe.Budget(limits, time::get, cancel::get);
  }

  static SparseIndexMetadataProbe probe(Fixture f, ArchivedItemHeaderProbe.Budget budget) {
    return new SparseIndexMetadataProbe(f, ArchivedItemHeaderProbe.ROUTE, budget, f.events::add);
  }

  static SparseIndexMetadataProbe.Result run(Fixture f) throws Exception {
    return probe(
            f, budget(SparseIndexMetadataProbe.defaults(), new AtomicLong(), new AtomicBoolean()))
        .run(f.scope);
  }

  static void fullSuffixCoverageAndDeduplicatedSelection() throws Exception {
    var f = new Fixture();
    var result = run(f);
    check(
        result.selectedChunks().equals(List.of(0, 1, 2, 3)),
        "Only metadata0 and complete suffix-overlap chunks selected");
    check(f.requests.size() == 5, "One header plus four deduplicated selected chunks");
    check(
        result.index().recordIds().equals(List.of(42L, 43L))
            && result.index().mapIds().equals(List.of(42L, 43L)),
        "All raw IDs and original order preserved");
    check(
        result.index().copies().equals(List.of(new SparseSectionIndex.Copy(90, 42))),
        "Raw copy pair preserved without resolving");
    check(
        result.coherence().absoluteCoordinateCandidateCoherent()
            && result.coherence().idListsEqual(),
        "Every absolute offset/size/order/boundary is coherent");
    check(
        result.coherence().firstRawOffset() == 300
            && result.coherence().lastRawEnd() == 320
            && result.coherence().sizeSum() == 20,
        "Exact full record-span coverage checked");
    check(
        result
            .index()
            .capabilities()
            .contains(SparseSectionIndex.Capability.RAW_OFFSET_ORIGIN_UNVERIFIED),
        "Production raw-origin capability retained despite diagnostic evidence");
    check(
        f.requests.stream().noneMatch(r -> r.start() >= f.encoded.length - 45),
        "Unrelated record/tail chunks never requested");
    var same = new Fixture(400, true, false);
    var dedup = run(same);
    check(
        dedup.selectedChunks().equals(List.of(0)) && same.requests.size() == 2,
        "Chunk0 covering suffix is not fetched twice");
    var tail = new Fixture();
    tail.corruptUnrequestedTail = true;
    run(tail);
    check(
        tail.events.stream().anyMatch(e -> e.contains("fullTableCKeyVerified=false")),
        "Unrequested corrupted tail cannot imply whole-file verification");
  }

  static void rawCoordinateMismatchIsReportedWithoutRepair() throws Exception {
    var relative = new Fixture(300, false, true);
    var result = run(relative);
    check(
        !result.coherence().absoluteCoordinateCandidateCoherent()
            && result.coherence().outOfBoundsMaps() == 2,
        "Relative-looking raw offsets fail absolute candidate without adjustment");
    check(result.index().mapEntries().getFirst().rawOffset() == 0, "Raw origin remains unchanged");
    var section = new Wdc5Metadata.Section("0000000000000000", 300, 2, 0, 320, 8, 0, 2, 2, 364);
    var raw =
        new SparseSectionIndex(
            320,
            328,
            344,
            356,
            364,
            List.of(43L, 42L),
            List.of(new SparseSectionIndex.Copy(42, 42), new SparseSectionIndex.Copy(42, 999)),
            List.of(
                new SparseSectionIndex.MapEntry(300, 8), new SparseSectionIndex.MapEntry(308, 12)),
            List.of(42L, 43L),
            Set.of(SparseSectionIndex.Capability.RAW_OFFSET_ORIGIN_UNVERIFIED));
    var facts =
        SparseIndexMetadataProbe.analyze(
            raw,
            section,
            budget(SparseIndexMetadataProbe.defaults(), new AtomicLong(), new AtomicBoolean()));
    check(
        !facts.idListsEqual() && facts.idSetsEqual() && !facts.recordIdsStrictlyIncreasing(),
        "ID sequence mismatch distinct from equal unordered sets");
    check(
        facts.duplicateCopyTargets() == 1
            && facts.copyTargetsInPhysicalIds() == 2
            && facts.copySourcesOutsidePhysicalIds() == 1
            && facts.selfCopies() == 1,
        "Copy facts remain unresolved declarations, not repaired rows");
  }

  static void proofScopeAndFreshGeometryFailuresDoNotEscalate() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var f = new Fixture();
      f.corruptHeader = mode == 0;
      f.corruptSelectedChunk = mode == 1;
      f.substituteSource = mode == 2;
      failure(() -> run(f));
      check(
          f.events.stream().noneMatch(e -> e.startsWith("SPARSE FULL SUFFIX OBSERVED")),
          "Failed authentication/source cannot publish full suffix");
      if (mode != 1) check(f.requests.size() == 1, "Failed header/source stops all chunks");
    }
    var fresh = new Fixture();
    var wrongScope =
        new SparseIndexMetadataProbe.PriorScope(
            fresh.scope.prefix(), 0, 300, 319, 356, "Wrong prior declaration");
    failure(
        () ->
            probe(
                    fresh,
                    budget(
                        SparseIndexMetadataProbe.defaults(), new AtomicLong(), new AtomicBoolean()))
                .run(wrongScope));
    check(fresh.requests.size() == 2, "Fresh geometry mismatch stops before suffix payload");
    var incomplete = new Fixture(204, false, false);
    failure(() -> run(incomplete));
    check(
        incomplete.requests.size() == 2,
        "Incomplete metadata chunk refuses extra metadata acquisition");
  }

  static ArchivedItemHeaderProbe.Limits limits(long wire, int requests, int chunk, long decode) {
    var d = SparseIndexMetadataProbe.defaults();
    return new ArchivedItemHeaderProbe.Limits(
        wire, requests, d.time(), chunk, d.indexBytes(), d.archiveBytes(), decode);
  }

  static void completePlanPreflightAndCancellation() throws Exception {
    var d = SparseIndexMetadataProbe.defaults();
    for (int mode = 0; mode < 4; mode++) {
      var f = new Fixture();
      var bound =
          limits(
              mode == 0 ? 491 : d.wireBytes(),
              mode == 1 ? 4 : d.requests(),
              mode == 3 ? 300 : d.chunkBytes(),
              mode == 2 ? 355 : d.decodedWorkBytes());
      failure(() -> probe(f, budget(bound, new AtomicLong(), new AtomicBoolean())).run(f.scope));
      check(
          f.requests.size() == 1,
          "Actual full selected plan refused after header, before any chunk payload");
    }
    var time = new AtomicLong();
    var expired = new Fixture();
    expired.afterHeader = () -> time.set(d.time().toNanos() + 1);
    failure(() -> probe(expired, budget(d, time, new AtomicBoolean())).run(expired.scope));
    check(expired.requests.size() == 1, "Deadline stops payload after header");
    var cancelled = new AtomicBoolean();
    var stop = new Fixture();
    stop.afterMetadata = () -> cancelled.set(true);
    failure(() -> probe(stop, budget(d, new AtomicLong(), cancelled)).run(stop.scope));
    check(stop.requests.size() == 2, "Cancellation after metadata stops all suffix chunks");
    check(
        SparseIndexMetadataProbe.SPARSE.suffixEnd() - SparseIndexMetadataProbe.SPARSE.recordsEnd()
            == 2458018,
        "Exact prior full-suffix length");
  }

  public static void main(String[] args) throws Exception {
    fullSuffixCoverageAndDeduplicatedSelection();
    rawCoordinateMismatchIsReportedWithoutRepair();
    proofScopeAndFreshGeometryFailuresDoNotEscalate();
    completePlanPreflightAndCancellation();
    System.out.println(
        "Sparse full index metadata synthetic self-test passed: "
            + assertions
            + " assertions; networkRequests=0; authored bytes only.");
  }
}
