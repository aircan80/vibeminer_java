import io.vibeminer.infrastructure.acquisition.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Offline generated fixtures only. No live transport or ignored evidence files are used. */
public final class ItemSectionMetadataProbeSelfTest {
  private static int assertions;

  private static void check(boolean value, String reason) {
    assertions++;
    if (!value) throw new AssertionError(reason);
  }

  @FunctionalInterface
  interface Work {
    void run() throws Exception;
  }

  private static Throwable failure(Work work) throws Exception {
    try {
      work.run();
      throw new AssertionError("Expected scoped refusal");
    } catch (AssertionError error) {
      throw error;
    } catch (Exception expected) {
      return expected;
    }
  }

  private static final class Fixture implements ArchivedItemHeaderProbe.Source {
    final byte[] archive;
    final ItemSectionMetadataProbe.PriorMapping mapping;
    final List<String> calls = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    final List<CdnRangeRequest> ranges = new ArrayList<>();
    boolean corruptHeader, corruptChunk, shortExtent, substituteHead, substituteRange;
    Runnable afterHead = () -> {};

    Fixture() throws Exception {
      this(300, 0x50238ec2);
    }

    Fixture(int firstDecoded, int table) throws Exception {
      byte[] decoded = metadata(table);
      byte[] first = Arrays.copyOfRange(decoded, 0, firstDecoded);
      byte[] second = Arrays.copyOfRange(decoded, firstDecoded, decoded.length);
      byte[] encoded = blte(first, second);
      int header = ByteBuffer.wrap(encoded).getInt(4);
      String ekey = hash("MD5", Arrays.copyOf(encoded, header));
      var target =
          new ArchivedItemHeaderProbe.Target(
              841626,
              hash("MD5", decoded),
              ekey,
              encoded.length,
              decoded.length,
              "Authored synthetic root/EN prior proof; no real data.");
      mapping =
          new ItemSectionMetadataProbe.PriorMapping(
              target,
              "1".repeat(32),
              64,
              header,
              first.length + 1,
              first.length,
              "Authored synthetic archive-index prior proof; no fresh index scan.");
      archive = new byte[64 + encoded.length + 16];
      System.arraycopy(encoded, 0, archive, 64, encoded.length);
    }

    public AcquiredBytes configuration(CdnRequest request) {
      throw new AssertionError("No configuration refresh allowed");
    }

    public IndexFooterObservation footer(CdnIndexFooterRequest request) {
      throw new AssertionError("No index scan allowed");
    }

    public DataSizeObservation size(CdnSizeRequest request) {
      calls.add("HEAD");
      check(request.resource().key().equals(mapping.archiveKey()), "HEAD stays on prior archive");
      CdnSizeRequest actual = request;
      if (substituteHead) {
        actual =
            new CdnSizeRequest(
                new CdnResource(request.resource().source(), "2".repeat(32), CdnResource.Kind.DATA),
                request.maximumWholeBytes());
      }
      afterHead.run();
      return new DataSizeObservation(
          actual,
          actual.resource().uri(),
          Instant.EPOCH,
          shortExtent
              ? mapping.childOffset() + mapping.target().encodedBytes() - 1
              : archive.length,
          Optional.empty(),
          Optional.empty());
    }

    public AcquiredRange range(CdnRangeRequest request) throws Exception {
      calls.add("range");
      ranges.add(request);
      check(request.encodedKey().equals(mapping.archiveKey()), "Ranges stay on prior archive");
      check(request.wholeEncodedLength() == archive.length, "Fresh HEAD pins range total");
      byte[] body =
          Arrays.copyOfRange(
              archive, (int) request.start(), (int) request.start() + request.count());
      if ((corruptHeader && request.start() == mapping.childOffset())
          || (corruptChunk && request.start() > mapping.childOffset())) body[body.length - 1] ^= 1;
      CdnRangeRequest actual = request;
      if (substituteRange) {
        actual =
            new CdnRangeRequest(
                new CdnResource(request.source(), request.encodedKey(), request.resourceKind()),
                request.wholeEncodedLength() + 1,
                request.start(),
                request.count(),
                request.count());
      }
      return new AcquiredRange(actual, actual.uri(), Instant.EPOCH, body, hash("SHA-256", body));
    }
  }

  private static ArchivedItemHeaderProbe.Budget budget(
      ArchivedItemHeaderProbe.Limits limits, AtomicLong time, AtomicBoolean cancelled) {
    return new ArchivedItemHeaderProbe.Budget(limits, time::get, cancelled::get);
  }

  private static ItemSectionMetadataProbe probe(
      Fixture fixture, ArchivedItemHeaderProbe.Budget budget) {
    return new ItemSectionMetadataProbe(
        fixture, ArchivedItemHeaderProbe.ROUTE, budget, fixture.events::add);
  }

  public static void main(String[] args) throws Exception {
    Fixture positive = new Fixture();
    var budget = budget(ItemSectionMetadataProbe.defaults(), new AtomicLong(), new AtomicBoolean());
    var result = probe(positive, budget).run(positive.mapping);
    check(positive.calls.equals(List.of("HEAD", "range", "range")), "Only three scoped requests");
    check(result.sections().size() == 1 && result.storage().size() == 1, "Pure metadata observed");
    check(result.metadataBytesRead() == 272, "Exact descriptor prefix size");
    check(
        result.sections().getFirst().encryptionKeyIdentifier().equals("fedcba9876543210"),
        "Raw encryption key identifier retained as declaration");
    check(
        budget.wire == positive.mapping.headerBytes() + positive.mapping.firstEncodedBytes(),
        "Exact body cost");
    check(budget.decoded == positive.mapping.firstDecodedBytes(), "Consumed chunk work only");
    check(positive.ranges.get(0).start() == positive.mapping.childOffset(), "Header coordinate");
    check(
        positive.ranges.get(1).start()
            == positive.mapping.childOffset() + positive.mapping.headerBytes(),
        "Translated first chunk coordinate");
    String report = String.join("\n", positive.events);
    check(
        report.contains("priorArchiveProofRevalidated=false"), "Prior mapping remains prior input");
    check(report.contains("fullTableCKeyVerified=false"), "No whole CKey claim");
    check(report.contains("rowsDecoded=false"), "No row claim");
    check(report.contains("schemaSemanticsVerified=false"), "No schema semantics claim");

    for (int mutation = 0; mutation < 5; mutation++) {
      Fixture bad = new Fixture();
      if (mutation == 0) bad.corruptHeader = true;
      if (mutation == 1) bad.corruptChunk = true;
      if (mutation == 2) bad.shortExtent = true;
      if (mutation == 3) bad.substituteHead = true;
      if (mutation == 4) bad.substituteRange = true;
      failure(
          () ->
              probe(
                      bad,
                      budget(
                          ItemSectionMetadataProbe.defaults(),
                          new AtomicLong(),
                          new AtomicBoolean()))
                  .run(bad.mapping));
      check(
          bad.events.stream()
              .noneMatch(event -> event.startsWith("ITEM SECTION METADATA OBSERVED")),
          "Failure cannot publish observed metadata: " + mutation);
      check(bad.calls.size() <= 3, "No integrity/source fallback");
      if (mutation == 0) check(bad.ranges.size() == 1, "Bad EKey header blocks chunk transfer");
      if (mutation == 2 || mutation == 3) check(bad.ranges.isEmpty(), "Bad HEAD blocks ranges");
    }

    Fixture incomplete = new Fixture(204, 0x50238ec2);
    failure(
        () ->
            probe(
                    incomplete,
                    budget(
                        ItemSectionMetadataProbe.defaults(), new AtomicLong(), new AtomicBoolean()))
                .run(incomplete.mapping));
    check(
        incomplete.ranges.size() == 2,
        "Incomplete metadata never fetches an extra chunk implicitly");
    Fixture wrongIdentity = new Fixture(300, 123);
    failure(
        () ->
            probe(
                    wrongIdentity,
                    budget(
                        ItemSectionMetadataProbe.defaults(), new AtomicLong(), new AtomicBoolean()))
                .run(wrongIdentity.mapping));

    var defaults = ItemSectionMetadataProbe.defaults();
    for (int restriction = 0; restriction < 3; restriction++) {
      Fixture fixture = new Fixture();
      var limited =
          new ArchivedItemHeaderProbe.Limits(
              restriction == 0 ? 1 : defaults.wireBytes(),
              restriction == 1 ? 2 : defaults.requests(),
              defaults.time(),
              defaults.chunkBytes(),
              defaults.indexBytes(),
              defaults.archiveBytes(),
              restriction == 2 ? 204 : defaults.decodedWorkBytes());
      failure(
          () ->
              probe(fixture, budget(limited, new AtomicLong(), new AtomicBoolean()))
                  .run(fixture.mapping));
      check(fixture.calls.isEmpty(), "Known scope over budget refused before HEAD");
    }

    Fixture timeout = new Fixture();
    AtomicLong time = new AtomicLong();
    timeout.afterHead = () -> time.set(defaults.time().toNanos() + 1);
    failure(() -> probe(timeout, budget(defaults, time, new AtomicBoolean())).run(timeout.mapping));
    check(timeout.calls.equals(List.of("HEAD")), "Deadline stops before header");
    Fixture cancel = new Fixture();
    AtomicBoolean cancellation = new AtomicBoolean();
    cancel.afterHead = () -> cancellation.set(true);
    check(
        failure(
                () ->
                    probe(cancel, budget(defaults, new AtomicLong(), cancellation))
                        .run(cancel.mapping))
            instanceof CancellationException,
        "Cancellation distinct from format/integrity error");
    check(cancel.calls.equals(List.of("HEAD")), "Cancellation prevents header transfer");
    System.out.println(
        "ItemSectionMetadataProbe self-test OK assertions=" + assertions + " networkRequests=0");
  }

  private static byte[] metadata(int table) {
    byte[] data = new byte[400];
    var b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {'W', 'D', 'C', '5'});
    b.putInt(4, 5);
    b.putInt(136, 1);
    b.putInt(140, 1);
    b.putInt(144, 4);
    b.putInt(152, table);
    b.putInt(156, 0x996192aa);
    b.putShort(172, (short) 4);
    b.putShort(174, (short) 0xffff);
    b.putInt(176, 1);
    b.putInt(188, 24);
    b.putInt(200, 1);
    b.putLong(204, 0xfedcba9876543210L);
    b.putInt(212, 300);
    b.putInt(216, 1);
    b.putInt(228, 4);
    return data;
  }

  private static byte[] blte(byte[]... chunks) throws Exception {
    int header = 12 + 24 * chunks.length;
    int size = header;
    for (byte[] chunk : chunks) size += chunk.length + 1;
    var out = ByteBuffer.allocate(size);
    out.put(new byte[] {'B', 'L', 'T', 'E'}).putInt(header).put((byte) 15);
    out.put((byte) 0).put((byte) 0).put((byte) chunks.length);
    for (byte[] chunk : chunks) {
      byte[] encoded = new byte[chunk.length + 1];
      encoded[0] = 'N';
      System.arraycopy(chunk, 0, encoded, 1, chunk.length);
      out.putInt(encoded.length)
          .putInt(chunk.length)
          .put(MessageDigest.getInstance("MD5").digest(encoded));
    }
    for (byte[] chunk : chunks) out.put((byte) 'N').put(chunk);
    return out.array();
  }

  private static String hash(String algorithm, byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
  }
}
