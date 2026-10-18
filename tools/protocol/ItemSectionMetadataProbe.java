import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.db2.*;
import io.vibeminer.protocol.tact.blte.BlteChunkPlanner;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

/** Explicit diagnostic using a previously authenticated archive mapping; never scans indices. */
public final class ItemSectionMetadataProbe {
  record PriorMapping(
      ArchivedItemHeaderProbe.Target target,
      String archiveKey,
      long childOffset,
      int headerBytes,
      int firstEncodedBytes,
      int firstDecodedBytes,
      String priorProof) {
    PriorMapping {
      Objects.requireNonNull(target);
      if (archiveKey == null
          || !archiveKey.matches("[0-9a-f]{32}")
          || childOffset < 0
          || headerBytes < 12
          || firstEncodedBytes < 1
          || firstDecodedBytes < 204
          || priorProof == null
          || priorProof.isBlank()) throw new IllegalArgumentException("Invalid prior mapping");
      if (Math.addExact((long) headerBytes, firstEncodedBytes) > target.encodedBytes())
        throw new IllegalArgumentException("Prior chunk exceeds child extent");
      Math.addExact(childOffset, target.encodedBytes());
    }
  }

  static final PriorMapping ITEM =
      new PriorMapping(
          ArchivedItemHeaderProbe.ITEM,
          "31236b924da7e06973fb5603c537fa7b",
          45153828,
          1428,
          2949,
          7152,
          "Recorded issue #51 authenticated archive-index mapping; this run does not reacquire its"
              + " index.");
  private final ArchivedItemHeaderProbe helper;
  private final ArchivedItemHeaderProbe.Budget budget;
  private final Consumer<String> out;

  ItemSectionMetadataProbe(
      ArchivedItemHeaderProbe.Source source,
      CdnRequest.Source route,
      ArchivedItemHeaderProbe.Budget budget,
      Consumer<String> out) {
    this.budget = Objects.requireNonNull(budget);
    this.out = Objects.requireNonNull(out);
    helper = new ArchivedItemHeaderProbe(source, route, budget, out);
  }

  static ArchivedItemHeaderProbe.Limits defaults() {
    return new ArchivedItemHeaderProbe.Limits(
        65536,
        16,
        Duration.ofMinutes(1),
        4 * 1024 * 1024,
        1024 * 1024,
        8L * 1024 * 1024 * 1024,
        4 * 1024 * 1024);
  }

  Wdc5Metadata run(PriorMapping mapping) throws Exception {
    Objects.requireNonNull(mapping);
    budget.require(
        (long) mapping.headerBytes() + mapping.firstEncodedBytes(), 3, mapping.firstDecodedBytes());
    if (mapping.headerBytes() > budget.limits.chunkBytes()
        || mapping.firstEncodedBytes() > budget.limits.chunkBytes()
        || mapping.firstDecodedBytes() > budget.limits.chunkBytes())
      throw new IllegalArgumentException("Known prefix exceeds work-buffer bound");
    out.accept("PRIOR mapping=" + mapping + " priorArchiveProofRevalidated=false");
    var archive = helper.resource(mapping.archiveKey(), CdnResource.Kind.DATA);
    long whole = helper.size(archive).observedWholeBytes();
    if (Math.addExact(mapping.childOffset(), mapping.target().encodedBytes()) > whole)
      throw new IllegalArgumentException("Fresh archive extent excludes recorded child");
    byte[] header = helper.range(archive, whole, mapping.childOffset(), mapping.headerBytes());
    var planner = new BlteChunkPlanner();
    var plan =
        planner.authenticate(
            header,
            HexFormat.of().parseHex(mapping.target().encodingKey()),
            mapping.target().encodedBytes(),
            mapping.target().decodedBytes(),
            new BlteChunkPlanner.Limits(
                mapping.headerBytes(),
                100000,
                mapping.target().encodedBytes(),
                mapping.target().decodedBytes(),
                budget.limits.chunkBytes(),
                budget.limits.chunkBytes()),
            budget::cancelled);
    var chunk = plan.chunks().getFirst();
    if (chunk.index() != 0
        || chunk.decodedOffset() != 0
        || chunk.encodedOffset() != mapping.headerBytes()
        || chunk.encodedBytes() != mapping.firstEncodedBytes()
        || chunk.decodedBytes() != mapping.firstDecodedBytes())
      throw new IllegalArgumentException(
          "Authenticated first chunk differs from authorized prefix scope");
    budget.require(chunk.encodedBytes(), 1, chunk.decodedBytes());
    byte[] encoded =
        helper.range(
            archive,
            whole,
            Math.addExact(mapping.childOffset(), chunk.encodedOffset()),
            chunk.encodedBytes());
    budget.decode(chunk.decodedBytes());
    byte[] prefix = planner.decodeSelected(plan, chunk, encoded, budget::cancelled).decodedBytes();
    var metadata =
        new Wdc5MetadataInspector()
            .inspect(
                prefix,
                mapping.target().decodedBytes(),
                new Wdc5MetadataInspector.Limits(
                    budget.limits.chunkBytes(),
                    mapping.target().decodedBytes(),
                    20000000,
                    10000,
                    10000,
                    1024 * 1024,
                    mapping.target().decodedBytes()),
                budget::cancelled);
    var h = metadata.header();
    if (h.tableHash() != 0x50238ec2L || h.layoutHash() != 0x996192aaL || h.schemaVersion() != 5)
      throw new IllegalArgumentException("Observed header disagrees with explicit Item identity");
    budget.check();
    out.accept("ITEM SECTION METADATA OBSERVED header=" + h);
    for (int i = 0; i < metadata.sections().size(); i++)
      out.accept("section[" + i + "]=" + metadata.sections().get(i));
    for (int i = 0; i < metadata.fields().size(); i++)
      out.accept("field[" + i + "]=" + metadata.fields().get(i));
    for (int i = 0; i < metadata.storage().size(); i++)
      out.accept("storage[" + i + "]=" + metadata.storage().get(i));
    out.accept(
        "metadataBytesRead="
            + metadata.metadataBytesRead()
            + " declaredAuxiliaryDataEnd="
            + metadata.declaredAuxiliaryDataEnd()
            + " capabilities="
            + metadata.capabilities());
    out.accept(
        "headerEKeyVerified=true firstChunkMD5Verified=true priorArchiveProofRevalidated=false"
            + " fullArchiveVerified=false fullTableCKeyVerified=false rowsDecoded=false"
            + " schemaSemanticsVerified=false");
    cost();
    return metadata;
  }

  private void cost() {
    out.accept(
        "COST requests="
            + budget.requests
            + " responseBodyBytes="
            + budget.wire
            + " decodedWorkBytes="
            + budget.decoded
            + " HTTP/TLS overhead excluded; failed reservations remain conservative");
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      ItemSectionMetadataProbeSelfTest.main(new String[0]);
      return;
    }
    if (args.length != 1 || !args[0].equals("--live"))
      throw new IllegalArgumentException("Use --self-test or explicitly authorized --live");
    var budget =
        new ArchivedItemHeaderProbe.Budget(
            defaults(), System::nanoTime, () -> Thread.currentThread().isInterrupted());
    var probe =
        new ItemSectionMetadataProbe(
            new ArchivedItemHeaderProbe.LiveSource(budget),
            ArchivedItemHeaderProbe.ROUTE,
            budget,
            System.out::println);
    try {
      probe.run(ITEM);
    } catch (Exception failure) {
      probe.cost();
      throw failure;
    }
  }
}
