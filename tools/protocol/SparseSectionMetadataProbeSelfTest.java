import io.vibeminer.infrastructure.acquisition.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

/** Deterministic authored fixtures only; no live source or ignored local data inputs. */
public final class SparseSectionMetadataProbeSelfTest {
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

  static final class Fixture implements ArchivedItemHeaderProbe.Source {
    final byte[] bytes;
    final SparseSectionMetadataProbe.PriorPrefix input;
    final List<CdnRangeRequest> requests = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    boolean corruptHeader, corruptChunk, substituteSource;
    Runnable afterHeader = () -> {};

    Fixture() throws Exception {
      this(300, 0x919be54e);
    }

    Fixture(int firstDecoded, int table) throws Exception {
      byte[] decoded = metadata(table);
      bytes =
          ItemCohortProbeSelfTest.blte(
              Arrays.copyOfRange(decoded, 0, firstDecoded),
              Arrays.copyOfRange(decoded, firstDecoded, decoded.length));
      int header = ByteBuffer.wrap(bytes).getInt(4);
      var target =
          new ArchivedItemHeaderProbe.Target(
              1572924,
              ItemCohortProbeSelfTest.hash("MD5", decoded),
              ItemCohortProbeSelfTest.ekey(bytes),
              bytes.length,
              decoded.length,
              "Authored prior-root/EN synthetic fixture; no fresh lookup.");
      input =
          new SparseSectionMetadataProbe.PriorPrefix(
              target,
              header,
              firstDecoded + 1,
              firstDecoded,
              "Authored known loose synthetic header/chunk prefix.");
    }

    public AcquiredBytes configuration(CdnRequest request) {
      throw new AssertionError("No config request authorized");
    }

    public IndexFooterObservation footer(CdnIndexFooterRequest request) {
      throw new AssertionError("No index request authorized");
    }

    public DataSizeObservation size(CdnSizeRequest request) {
      throw new AssertionError("No HEAD request authorized");
    }

    public AcquiredRange range(CdnRangeRequest request) throws Exception {
      requests.add(request);
      check(request.resourceKind() == CdnResource.Kind.DATA, "Loose DATA route only");
      check(request.encodedKey().equals(input.target().encodingKey()), "Only prior exact EKey");
      check(request.wholeEncodedLength() == bytes.length, "Pinned exact whole length retained");
      byte[] body =
          Arrays.copyOfRange(bytes, (int) request.start(), (int) request.start() + request.count());
      if ((corruptHeader && request.start() == 0) || (corruptChunk && request.start() != 0))
        body[body.length - 1] ^= 1;
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
              body,
              ItemCohortProbeSelfTest.hash("SHA-256", body));
      if (request.start() == 0) afterHeader.run();
      return result;
    }
  }

  static byte[] metadata(int table) {
    byte[] data = new byte[400];
    var b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {'W', 'D', 'C', '5'}).putInt(5);
    b.putInt(136, 1)
        .putInt(140, 1)
        .putInt(144, 4)
        .putInt(152, table)
        .putInt(156, 0x1c17d17f)
        .putInt(168, 1)
        .putShort(172, (short) 5)
        .putShort(174, (short) 0xffff)
        .putInt(176, 1)
        .putInt(188, 24)
        .putInt(200, 1);
    b.putLong(204, 0xfedcba9876543210L)
        .putInt(212, 300)
        .putInt(216, 1)
        .putInt(224, 304)
        .putInt(228, 4);
    return data;
  }

  static ArchivedItemHeaderProbe.Budget budget(
      ArchivedItemHeaderProbe.Limits limits, AtomicLong time, AtomicBoolean cancelled) {
    return new ArchivedItemHeaderProbe.Budget(limits, time::get, cancelled::get);
  }

  static SparseSectionMetadataProbe probe(Fixture fixture, ArchivedItemHeaderProbe.Budget budget) {
    return new SparseSectionMetadataProbe(
        fixture, ArchivedItemHeaderProbe.ROUTE, budget, fixture.events::add);
  }

  static void happyExactTwoRangeProofAndDeclarations() throws Exception {
    var fixture = new Fixture();
    var budget =
        budget(SparseSectionMetadataProbe.defaults(), new AtomicLong(), new AtomicBoolean());
    var metadata = probe(fixture, budget).run(fixture.input);
    check(fixture.requests.size() == 2, "Exactly two ranges, no refresh/HEAD");
    check(
        fixture.requests.getFirst().start() == 0
            && fixture.requests.getFirst().count() == fixture.input.headerBytes(),
        "Exact known header range");
    check(
        fixture.requests.getLast().start() == fixture.input.headerBytes()
            && fixture.requests.getLast().count() == fixture.input.firstEncodedBytes(),
        "Exact known chunk0 range");
    check(
        budget.wire == fixture.input.headerBytes() + fixture.input.firstEncodedBytes()
            && budget.decoded == fixture.input.firstDecodedBytes(),
        "Exact scoped costs");
    check(metadata.metadataBytesRead() == 272, "Complete authored descriptors only");
    check(
        metadata.sections().getFirst().encryptionKeyIdentifier().equals("fedcba9876543210"),
        "Unsigned raw key declaration preserved");
    check(metadata.header().locale() == 1, "Raw DB2 locale remains independent of prior root mask");
    String report = String.join("\n", fixture.events);
    check(
        report.contains("sectionReadabilityUnknown=true")
            && report.contains("fullTableCKeyVerified=false")
            && report.contains("rowsDecoded=false")
            && report.contains("schemaSemanticsVerified=false"),
        "No readability/schema/whole content claim");
  }

  static void proofIdentityAndIncompletePrefixFailuresHaveNoEscalation() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var fixture = new Fixture();
      fixture.corruptHeader = mode == 0;
      fixture.corruptChunk = mode == 1;
      fixture.substituteSource = mode == 2;
      failure(
          () ->
              probe(
                      fixture,
                      budget(
                          SparseSectionMetadataProbe.defaults(),
                          new AtomicLong(),
                          new AtomicBoolean()))
                  .run(fixture.input));
      check(
          fixture.events.stream().noneMatch(e -> e.startsWith("SPARSE SECTION METADATA OBSERVED")),
          "No successful metadata after failed source/proof");
      check(
          fixture.requests.size() <= (mode == 1 ? 2 : 1),
          "No fallback or extra chunk after proof failure");
    }
    var incomplete = new Fixture(204, 0x919be54e);
    failure(
        () ->
            probe(
                    incomplete,
                    budget(
                        SparseSectionMetadataProbe.defaults(),
                        new AtomicLong(),
                        new AtomicBoolean()))
                .run(incomplete.input));
    check(
        incomplete.requests.size() == 2,
        "Descriptor truncation refuses instead of fetching extra chunk");
    var wrong = new Fixture(300, 123);
    failure(
        () ->
            probe(
                    wrong,
                    budget(
                        SparseSectionMetadataProbe.defaults(),
                        new AtomicLong(),
                        new AtomicBoolean()))
                .run(wrong.input));
    check(
        wrong.events.stream().noneMatch(e -> e.startsWith("SPARSE SECTION METADATA OBSERVED")),
        "Wrong table identity cannot publish metadata");
    var scope = new Fixture();
    var inconsistent =
        new SparseSectionMetadataProbe.PriorPrefix(
            scope.input.target(),
            scope.input.headerBytes(),
            scope.input.firstEncodedBytes() + 1,
            scope.input.firstDecodedBytes(),
            "Mismatched requested scope");
    failure(
        () ->
            probe(
                    scope,
                    budget(
                        SparseSectionMetadataProbe.defaults(),
                        new AtomicLong(),
                        new AtomicBoolean()))
                .run(inconsistent));
    check(scope.requests.size() == 1, "Authenticated chunk size mismatch blocks body transfer");
  }

  static void budgetDeadlineAndCancellationRefuseBeforeFurtherRequests() throws Exception {
    var defaults = SparseSectionMetadataProbe.defaults();
    for (int mode = 0; mode < 4; mode++) {
      var fixture = new Fixture();
      var limits =
          new ArchivedItemHeaderProbe.Limits(
              mode == 0 ? 1 : defaults.wireBytes(),
              mode == 1 ? 1 : defaults.requests(),
              defaults.time(),
              mode == 3 ? 204 : defaults.chunkBytes(),
              defaults.indexBytes(),
              defaults.archiveBytes(),
              mode == 2 ? 204 : defaults.decodedWorkBytes());
      failure(
          () ->
              probe(fixture, budget(limits, new AtomicLong(), new AtomicBoolean()))
                  .run(fixture.input));
      check(fixture.requests.isEmpty(), "Whole known scope refused before first header request");
    }
    var time = new AtomicLong();
    var timeout = new Fixture();
    timeout.afterHeader = () -> time.set(defaults.time().toNanos() + 1);
    failure(() -> probe(timeout, budget(defaults, time, new AtomicBoolean())).run(timeout.input));
    check(timeout.requests.size() == 1, "Deadline after header stops chunk request");
    var cancelled = new AtomicBoolean();
    var stop = new Fixture();
    stop.afterHeader = () -> cancelled.set(true);
    check(
        failure(() -> probe(stop, budget(defaults, new AtomicLong(), cancelled)).run(stop.input))
            instanceof CancellationException,
        "Cancellation remains explicit");
    check(stop.requests.size() == 1, "Cancellation after header stops chunk request");
    check(
        (long) SparseSectionMetadataProbe.SPARSE.headerBytes()
                + SparseSectionMetadataProbe.SPARSE.firstEncodedBytes()
            == 92682,
        "Live plan exact92682 bytes");
    check(
        SparseSectionMetadataProbe.SPARSE.firstDecodedBytes() == 5252,
        "Live plan exact5252 decoded bytes");
  }

  public static void main(String[] args) throws Exception {
    happyExactTwoRangeProofAndDeclarations();
    proofIdentityAndIncompletePrefixFailuresHaveNoEscalation();
    budgetDeadlineAndCancellationRefuseBeforeFurtherRequests();
    System.out.println(
        "Sparse section metadata synthetic self-test passed: "
            + assertions
            + " assertions; networkRequests=0; generated bytes only.");
  }
}
