import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.db2.*;
import io.vibeminer.protocol.tact.blte.BlteChunkPlanner;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

/** Reauthenticates one known loose prefix; prior root/EN/locale mapping is not refreshed. */
public final class SparseSectionMetadataProbe {
  record PriorPrefix(
      ArchivedItemHeaderProbe.Target target,
      int headerBytes,
      int firstEncodedBytes,
      int firstDecodedBytes,
      String priorProof) {
    PriorPrefix {
      Objects.requireNonNull(target);
      if (headerBytes < 12
          || firstEncodedBytes < 1
          || firstDecodedBytes < 204
          || firstDecodedBytes > target.decodedBytes()
          || priorProof == null
          || priorProof.isBlank())
        throw new IllegalArgumentException("Invalid known Sparse prefix");
      if (Math.addExact((long) headerBytes, firstEncodedBytes) > target.encodedBytes())
        throw new IllegalArgumentException("Known Sparse chunk exceeds encoded target extent");
    }
  }

  static final PriorPrefix SPARSE =
      new PriorPrefix(
          new ArchivedItemHeaderProbe.Target(
              1572924,
              "bb0ecbdc2fd4988ddd3c1c5e95c34165",
              "cab35eb8601260c12a5917c1bdacfda5",
              7988612,
              62715198,
              "Prior #45 complete root CKey e84483ee756c69a7dc5fbff04af81316 and selected EN pages,"
                  + " explicit enUS bit0x2/shared rootmask0x202; no root or EN scan this run."),
          90732,
          1950,
          5252,
          "Recorded docs/evidence/item-sparse-header-12.1.5.70448-enUS.json: prior loose"
              + " header/chunk proof; fresh header/chunk authentication only in this run.");
  final ArchivedItemHeaderProbe helper;
  final ArchivedItemHeaderProbe.Budget budget;
  final Consumer<String> out;

  SparseSectionMetadataProbe(
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
        128 * 1024,
        16,
        Duration.ofMinutes(1),
        4 * 1024 * 1024,
        1024 * 1024,
        8L * 1024 * 1024 * 1024,
        4 * 1024 * 1024);
  }

  Wdc5Metadata run(PriorPrefix input) throws Exception {
    Objects.requireNonNull(input);
    long wire = Math.addExact((long) input.headerBytes(), input.firstEncodedBytes());
    budget.require(wire, 2, input.firstDecodedBytes());
    if (input.headerBytes() > budget.limits.chunkBytes()
        || input.firstEncodedBytes() > budget.limits.chunkBytes()
        || input.firstDecodedBytes() > budget.limits.chunkBytes())
      throw new IllegalArgumentException("Known Sparse scope exceeds work-buffer bound");
    out.accept(
        "PRIOR SPARSE INPUT "
            + input
            + " priorRootENProofRevalidated=false requestedLocale=enUS rootLocaleBit=0x2"
            + " rootLocaleMask=0x202 rawDB2LocaleRetainedSeparately=true");
    out.accept(
        "SPARSE METADATA PLAN requests=2 responseBodyBytes="
            + wire
            + " decodedWorkBytes="
            + input.firstDecodedBytes()
            + " noHEAD=true noConfigRootENIndexRefresh=true noAdditionalChunks=true");
    var resource = helper.resource(input.target().encodingKey(), CdnResource.Kind.DATA);
    byte[] header = helper.range(resource, input.target().encodedBytes(), 0, input.headerBytes());
    var planner = new BlteChunkPlanner();
    var plan =
        planner.authenticate(
            header,
            HexFormat.of().parseHex(input.target().encodingKey()),
            input.target().encodedBytes(),
            input.target().decodedBytes(),
            new BlteChunkPlanner.Limits(
                input.headerBytes(),
                100000,
                input.target().encodedBytes(),
                input.target().decodedBytes(),
                budget.limits.chunkBytes(),
                budget.limits.chunkBytes()),
            budget::cancelled);
    var chunk = plan.chunks().getFirst();
    if (chunk.index() != 0
        || chunk.decodedOffset() != 0
        || chunk.encodedOffset() != input.headerBytes()
        || chunk.encodedBytes() != input.firstEncodedBytes()
        || chunk.decodedBytes() != input.firstDecodedBytes())
      throw new IllegalArgumentException(
          "Authenticated Sparse first chunk differs from authorized scope");
    budget.require(chunk.encodedBytes(), 1, chunk.decodedBytes());
    byte[] encoded =
        helper.range(
            resource, input.target().encodedBytes(), chunk.encodedOffset(), chunk.encodedBytes());
    budget.decode(chunk.decodedBytes());
    byte[] prefix = planner.decodeSelected(plan, chunk, encoded, budget::cancelled).decodedBytes();
    var metadata =
        new Wdc5MetadataInspector()
            .inspect(
                prefix,
                input.target().decodedBytes(),
                new Wdc5MetadataInspector.Limits(
                    budget.limits.chunkBytes(),
                    input.target().decodedBytes(),
                    20000000,
                    10000,
                    10000,
                    1024 * 1024,
                    input.target().decodedBytes()),
                budget::cancelled);
    var observed = metadata.header();
    if (observed.tableHash() != 0x919be54eL
        || observed.layoutHash() != 0x1c17d17fL
        || observed.schemaVersion() != 5)
      throw new IllegalArgumentException(
          "Observed Sparse metadata disagrees with explicit table/layout/schema identity");
    budget.check();
    out.accept("SPARSE SECTION METADATA OBSERVED header=" + observed);
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
        "headerEKeyVerified=true firstChunkMD5Verified=true priorRootENProofRevalidated=false"
            + " fullTableCKeyVerified=false rowsDecoded=false schemaSemanticsVerified=false"
            + " sectionKeysAreDeclarations=true sectionReadabilityUnknown=true"
            + " noPayloadWrites=true");
    cost();
    return metadata;
  }

  void cost() {
    out.accept(
        "SPARSE METADATA COST requests="
            + budget.requests
            + " responseBodyBytes="
            + budget.wire
            + " decodedWorkBytes="
            + budget.decoded
            + " HTTP/TLS overhead excluded; failed reservations remain conservative");
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      SparseSectionMetadataProbeSelfTest.main(new String[0]);
      return;
    }
    if (args.length != 1 || !args[0].equals("--live"))
      throw new IllegalArgumentException(
          "Use --self-test or separately authorized --live with fixed scope");
    var budget =
        new ArchivedItemHeaderProbe.Budget(
            defaults(), System::nanoTime, () -> Thread.currentThread().isInterrupted());
    var probe =
        new SparseSectionMetadataProbe(
            new ArchivedItemHeaderProbe.LiveSource(budget),
            ArchivedItemHeaderProbe.ROUTE,
            budget,
            System.out::println);
    try {
      probe.run(SPARSE);
    } catch (Exception failure) {
      probe.cost();
      System.out.println(
          "SPARSE METADATA STOP outcome="
              + failure.getClass().getSimpleName()
              + " message="
              + failure.getMessage()
              + " noAdditionalAcquisition=true noPayloadWrites=true");
      throw failure;
    }
  }
}
