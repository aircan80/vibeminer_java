import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.db2.*;
import io.vibeminer.protocol.tact.blte.BlteChunkPlanner;
import java.nio.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Authenticated full selected sparse suffix metadata; never decodes record bodies or repairs
 * coordinates.
 */
public final class SparseIndexMetadataProbe {
  record PriorScope(
      SparseSectionMetadataProbe.PriorPrefix prefix,
      int sectionIndex,
      long recordsStart,
      long recordsEnd,
      long suffixEnd,
      String priorProof) {
    PriorScope {
      Objects.requireNonNull(prefix);
      if (sectionIndex < 0
          || recordsStart < 204
          || recordsEnd < recordsStart
          || suffixEnd < recordsEnd
          || suffixEnd > prefix.target().decodedBytes()
          || priorProof == null
          || priorProof.isBlank())
        throw new IllegalArgumentException("Invalid attributed sparse suffix scope");
    }
  }

  static final PriorScope SPARSE =
      new PriorScope(
          SparseSectionMetadataProbe.SPARSE,
          0,
          5252,
          60166704,
          62624722,
          "Prior #61 zero-key section0 declarations; fresh metadata must agree before suffix"
              + " acquisition. Pinned DBCD writer records absolute file positions; reader differs,"
              + " so raw origin remains unverified in the production parser.");

  record Coherence(
      long records,
      long maps,
      long copies,
      boolean idListsEqual,
      boolean idSetsEqual,
      boolean recordIdsStrictlyIncreasing,
      boolean mapIdsStrictlyIncreasing,
      long duplicateRecordIds,
      long duplicateMapIds,
      long zeroRecordIds,
      long zeroMapIds,
      long zeroSizeMaps,
      long outOfBoundsMaps,
      long nonContiguousTransitions,
      long sizeSum,
      long declaredRecordSpan,
      long firstRawOffset,
      long lastRawEnd,
      boolean absoluteBoundsCoherent,
      boolean physicalOrderContiguous,
      boolean sizeCoverageMatches,
      boolean absoluteCoordinateCandidateCoherent,
      long duplicateCopyTargets,
      long copyTargetsInPhysicalIds,
      long copySourcesOutsidePhysicalIds,
      long selfCopies,
      String recordIdsSHA256,
      String mapIdsSHA256,
      String mapsSHA256,
      String copiesSHA256) {}

  record Result(
      Wdc5Metadata metadata,
      SparseSectionIndex index,
      Coherence coherence,
      List<Integer> selectedChunks) {
    Result {
      selectedChunks = List.copyOf(selectedChunks);
    }
  }

  final ArchivedItemHeaderProbe helper;
  final ArchivedItemHeaderProbe.Budget budget;
  final Consumer<String> out;

  SparseIndexMetadataProbe(
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
        4L * 1024 * 1024,
        256,
        Duration.ofSeconds(120),
        4 * 1024 * 1024,
        1024 * 1024,
        8L * 1024 * 1024 * 1024,
        8L * 1024 * 1024);
  }

  static boolean intersects(BlteChunkPlanner.Chunk chunk, long start, long end) {
    return chunk.decodedOffset() < end && chunk.decodedOffset() + chunk.decodedBytes() > start;
  }

  Result run(PriorScope scope) throws Exception {
    var prior = scope.prefix();
    long suffixLength = scope.suffixEnd() - scope.recordsEnd();
    if (suffixLength < 0 || suffixLength > budget.limits.chunkBytes())
      throw new IllegalArgumentException("Full suffix exceeds fixed metadata-buffer bound");
    budget.require(prior.headerBytes(), 1, 0);
    out.accept(
        "PRIOR SPARSE INDEX INPUT "
            + scope
            + " rootENRefreshed=false rawOffsetOriginInParser=UNVERIFIED");
    var resource = helper.resource(prior.target().encodingKey(), CdnResource.Kind.DATA);
    byte[] header = helper.range(resource, prior.target().encodedBytes(), 0, prior.headerBytes());
    var planner = new BlteChunkPlanner();
    var plan =
        planner.authenticate(
            header,
            HexFormat.of().parseHex(prior.target().encodingKey()),
            prior.target().encodedBytes(),
            prior.target().decodedBytes(),
            new BlteChunkPlanner.Limits(
                prior.headerBytes(),
                100000,
                prior.target().encodedBytes(),
                prior.target().decodedBytes(),
                64 * 1024 * 1024,
                64 * 1024 * 1024),
            budget::cancelled);
    var first = plan.chunks().getFirst();
    if (first.index() != 0
        || first.decodedOffset() != 0
        || first.encodedOffset() != prior.headerBytes()
        || first.encodedBytes() != prior.firstEncodedBytes()
        || first.decodedBytes() != prior.firstDecodedBytes())
      throw new IllegalArgumentException(
          "Authenticated metadata chunk differs from prior authorized scope");
    var selected =
        plan.chunks().stream()
            .filter(
                chunk ->
                    chunk.index() == 0 || intersects(chunk, scope.recordsEnd(), scope.suffixEnd()))
            .toList();
    long encoded = 0, decoded = 0;
    for (var chunk : selected) {
      if (chunk.encodedBytes() > budget.limits.chunkBytes()
          || chunk.decodedBytes() > budget.limits.chunkBytes())
        throw new IllegalArgumentException(
            "A selected complete chunk exceeds fixed buffer ceiling");
      encoded += chunk.encodedBytes();
      decoded += chunk.decodedBytes();
    }
    out.accept(
        "AUTHENTICATED SUFFIX PLAN chunks="
            + selected.stream().map(BlteChunkPlanner.Chunk::index).toList()
            + " remainingBodyBytes="
            + encoded
            + " remainingRequests="
            + selected.size()
            + " decodedWorkBytes="
            + decoded
            + " totalPlannedBodyBytes="
            + (budget.wire + encoded)
            + " totalPlannedRequests="
            + (budget.requests + selected.size())
            + " suffixBytes="
            + suffixLength
            + " noBudgetIncrease=true");
    budget.require(encoded, selected.size(), decoded);
    byte[] prefix = decode(resource, prior, planner, plan, first);
    var metadata =
        new Wdc5MetadataInspector()
            .inspect(
                prefix,
                prior.target().decodedBytes(),
                new Wdc5MetadataInspector.Limits(
                    budget.limits.chunkBytes(),
                    prior.target().decodedBytes(),
                    20000000,
                    10000,
                    10000,
                    1024 * 1024,
                    prior.target().decodedBytes()),
                budget::cancelled);
    var h = metadata.header();
    if (h.tableHash() != 0x919be54eL
        || h.layoutHash() != 0x1c17d17fL
        || h.schemaVersion() != 5
        || h.flags() != 5)
      throw new IllegalArgumentException(
          "Fresh metadata disagrees with exact Sparse table/layout/schema/flags");
    if (scope.sectionIndex() >= metadata.sections().size())
      throw new IllegalArgumentException("Selected section absent from fresh declarations");
    var section = metadata.sections().get(scope.sectionIndex());
    if (!section.encryptionKeyIdentifier().equals("0000000000000000")
        || section.fileOffset() != scope.recordsStart()
        || section.offsetRecordsEndOffset() != scope.recordsEnd()
        || section.declaredEndOffset() != scope.suffixEnd())
      throw new IllegalArgumentException(
          "Fresh zero-key section geometry differs from attributed scope; no suffix acquired");
    out.accept(
        "FRESH ZERO-KEY SECTION "
            + section
            + " keyIsDeclarationNotReadability=true rawDB2Locale="
            + h.locale()
            + " priorRootLocaleMask=0x202");
    byte[] suffix = new byte[(int) suffixLength];
    int filled = copyIntersection(first, prefix, scope.recordsEnd(), scope.suffixEnd(), suffix);
    for (var chunk : selected) {
      if (chunk.index() == 0) continue;
      byte[] bytes = decode(resource, prior, planner, plan, chunk);
      filled += copyIntersection(chunk, bytes, scope.recordsEnd(), scope.suffixEnd(), suffix);
    }
    if (filled != suffix.length)
      throw new IllegalArgumentException(
          "Authenticated complete chunks do not cover exact full suffix");
    var index =
        new SparseSectionIndexParser()
            .parse(
                suffix,
                h,
                section,
                new SparseSectionIndexParser.Limits(suffix.length, 1000000, 100000, 1000000),
                budget::cancelled);
    Coherence coherence = analyze(index, section, budget);
    verifyRawDigests(suffix, index, coherence);
    budget.check();
    out.accept(
        "SPARSE FULL SUFFIX OBSERVED counts="
            + index.recordIds().size()
            + "/"
            + index.mapIds().size()
            + "/"
            + index.mapEntries().size()
            + " copies="
            + index.copies().size()
            + " suffixSHA256="
            + hash(suffix));
    out.accept("COHERENCE " + coherence);
    for (int i = 0; i < index.recordIds().size(); i++)
      if (i < 4 || i >= index.recordIds().size() - 4)
        out.accept(
            "rawEntry["
                + i
                + "] recordId="
                + index.recordIds().get(i)
                + " mapId="
                + index.mapIds().get(i)
                + " map="
                + index.mapEntries().get(i));
    for (int i = 0; i < index.copies().size(); i++)
      if (index.copies().size() <= 1024 || i < 4 || i >= index.copies().size() - 4)
        out.accept("rawCopy[" + i + "]=" + index.copies().get(i));
    out.accept(
        "allSuffixEntriesChecked=true listsPreservedWithoutRepair=true"
            + " digestInput=all-ordered-raw-fields parserCapabilities="
            + index.capabilities()
            + " absoluteCoordinateResultAppliesToThisSourceOnly=true"
            + " adjacentBoundaryBytesNotInspected=true fullTableCKeyVerified=false"
            + " recordBodiesInspected=false rowsDecoded=false schemaSemanticsVerified=false"
            + " sectionReadabilityUnknown=true noPayloadWrites=true");
    cost();
    return new Result(
        metadata, index, coherence, selected.stream().map(BlteChunkPlanner.Chunk::index).toList());
  }

  byte[] decode(
      CdnResource resource,
      SparseSectionMetadataProbe.PriorPrefix prior,
      BlteChunkPlanner planner,
      BlteChunkPlanner.AuthenticatedPlan plan,
      BlteChunkPlanner.Chunk chunk)
      throws Exception {
    budget.decode(chunk.decodedBytes());
    byte[] encoded =
        helper.range(
            resource, prior.target().encodedBytes(), chunk.encodedOffset(), chunk.encodedBytes());
    byte[] decoded = planner.decodeSelected(plan, chunk, encoded, budget::cancelled).decodedBytes();
    out.accept(
        "VERIFIED SUFFIX CHUNK index="
            + chunk.index()
            + " encodedBytes="
            + chunk.encodedBytes()
            + " decodedBytes="
            + chunk.decodedBytes()
            + " decodedOffset="
            + chunk.decodedOffset()
            + " encodedMD5+supportedDecode=true");
    return decoded;
  }

  static int copyIntersection(
      BlteChunkPlanner.Chunk chunk, byte[] bytes, long start, long end, byte[] output) {
    long from = Math.max(start, chunk.decodedOffset()),
        to = Math.min(end, chunk.decodedOffset() + chunk.decodedBytes());
    if (from >= to) return 0;
    int length = (int) (to - from);
    System.arraycopy(
        bytes, (int) (from - chunk.decodedOffset()), output, (int) (from - start), length);
    return length;
  }

  static Coherence analyze(
      SparseSectionIndex index, Wdc5Metadata.Section section, ArchivedItemHeaderProbe.Budget budget)
      throws Exception {
    var recordSet = new HashSet<Long>();
    var mapSet = new HashSet<Long>();
    long recordDuplicates = 0, mapDuplicates = 0, recordZero = 0, mapZero = 0;
    boolean recordOrder = true, mapOrder = true;
    long previousRecord = -1, previousMap = -1;
    var recordHash = MessageDigest.getInstance("SHA-256");
    var mapIdHash = MessageDigest.getInstance("SHA-256");
    var mapHash = MessageDigest.getInstance("SHA-256");
    var copyHash = MessageDigest.getInstance("SHA-256");
    var buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    for (long id : index.recordIds()) {
      budget.check();
      if (!recordSet.add(id)) recordDuplicates++;
      if (id == 0) recordZero++;
      if (id <= previousRecord) recordOrder = false;
      previousRecord = id;
      buffer.clear();
      buffer.putInt((int) id);
      recordHash.update(buffer.array(), 0, 4);
    }
    for (long id : index.mapIds()) {
      budget.check();
      if (!mapSet.add(id)) mapDuplicates++;
      if (id == 0) mapZero++;
      if (id <= previousMap) mapOrder = false;
      previousMap = id;
      buffer.clear();
      buffer.putInt((int) id);
      mapIdHash.update(buffer.array(), 0, 4);
    }
    long zeroSize = 0,
        outOfBounds = 0,
        transitions = 0,
        sizeSum = 0,
        expected = section.fileOffset(),
        first = -1,
        last = -1;
    for (var map : index.mapEntries()) {
      budget.check();
      if (first < 0) first = map.rawOffset();
      long end = Math.addExact(map.rawOffset(), map.size());
      if (map.size() == 0) zeroSize++;
      if (map.rawOffset() < section.fileOffset()
          || end > section.offsetRecordsEndOffset()
          || map.size() == 0) outOfBounds++;
      if (map.rawOffset() != expected) transitions++;
      expected = end;
      last = end;
      sizeSum += map.size();
      buffer.clear();
      buffer.putInt((int) map.rawOffset()).putShort((short) map.size());
      mapHash.update(buffer.array(), 0, 6);
    }
    var copyTargets = new HashSet<Long>();
    long dupTargets = 0, targetsPhysical = 0, sourcesOutside = 0, self = 0;
    for (var copy : index.copies()) {
      budget.check();
      if (!copyTargets.add(copy.targetId())) dupTargets++;
      if (recordSet.contains(copy.targetId())) targetsPhysical++;
      if (!recordSet.contains(copy.sourceId())) sourcesOutside++;
      if (copy.targetId() == copy.sourceId()) self++;
      buffer.clear();
      buffer.putInt((int) copy.targetId()).putInt((int) copy.sourceId());
      copyHash.update(buffer.array(), 0, 8);
    }
    long span = section.offsetRecordsEndOffset() - section.fileOffset();
    boolean bounds = outOfBounds == 0,
        contiguous =
            transitions == 0
                && (index.mapEntries().isEmpty()
                    ? span == 0
                    : first == section.fileOffset() && last == section.offsetRecordsEndOffset()),
        sizeMatch = sizeSum == span;
    return new Coherence(
        index.recordIds().size(),
        index.mapEntries().size(),
        index.copies().size(),
        index.recordIds().equals(index.mapIds()),
        recordSet.equals(mapSet),
        recordOrder,
        mapOrder,
        recordDuplicates,
        mapDuplicates,
        recordZero,
        mapZero,
        zeroSize,
        outOfBounds,
        transitions,
        sizeSum,
        span,
        first,
        last,
        bounds,
        contiguous,
        sizeMatch,
        bounds && contiguous && sizeMatch,
        dupTargets,
        targetsPhysical,
        sourcesOutside,
        self,
        hex(recordHash.digest()),
        hex(mapIdHash.digest()),
        hex(mapHash.digest()),
        hex(copyHash.digest()));
  }

  static void verifyRawDigests(byte[] suffix, SparseSectionIndex index, Coherence facts)
      throws Exception {
    int copy = (int) (index.copyTableOffset() - index.suffixOffset()),
        maps = (int) (index.offsetMapOffset() - index.suffixOffset()),
        mapIds = (int) (index.mapIdListOffset() - index.suffixOffset());
    if (!hash(Arrays.copyOfRange(suffix, 0, copy)).equals(facts.recordIdsSHA256())
        || !hash(Arrays.copyOfRange(suffix, copy, maps)).equals(facts.copiesSHA256())
        || !hash(Arrays.copyOfRange(suffix, maps, mapIds)).equals(facts.mapsSHA256())
        || !hash(Arrays.copyOfRange(suffix, mapIds, suffix.length)).equals(facts.mapIdsSHA256()))
      throw new IllegalArgumentException(
          "Parser raw field/order digest differs from authenticated suffix bytes");
  }

  static String hash(byte[] bytes) throws Exception {
    return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  void cost() {
    out.accept(
        "SPARSE INDEX COST requests="
            + budget.requests
            + " responseBodyBytes="
            + budget.wire
            + " decodedWorkBytes="
            + budget.decoded
            + " elapsedNanos="
            + (budget.nanos.getAsLong() - budget.started)
            + " HTTP/TLS overhead excluded; failed reservations conservative");
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      SparseIndexMetadataProbeSelfTest.main(new String[0]);
      return;
    }
    if (args.length != 1 || !args[0].equals("--live"))
      throw new IllegalArgumentException(
          "Use --self-test or separately authorized --live, fixed scope only");
    var budget =
        new ArchivedItemHeaderProbe.Budget(
            defaults(), System::nanoTime, () -> Thread.currentThread().isInterrupted());
    var probe =
        new SparseIndexMetadataProbe(
            new ArchivedItemHeaderProbe.LiveSource(budget),
            ArchivedItemHeaderProbe.ROUTE,
            budget,
            System.out::println);
    try {
      probe.run(SPARSE);
    } catch (Exception failure) {
      probe.cost();
      System.out.println(
          "SPARSE INDEX STOP outcome="
              + failure.getClass().getSimpleName()
              + " message="
              + failure.getMessage()
              + " noAdditionalScope=true noPayloadWrites=true");
      throw failure;
    }
  }
}
