import io.vibeminer.format.wow.schema.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.schema.BundledItemSchemas;
import io.vibeminer.protocol.db2.*;
import io.vibeminer.protocol.tact.blte.BlteChunkPlanner;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.*;

/**
 * Diagnostic-only exact two-ID projection. Private row output is not public evidence or a search
 * feature.
 */
public final class ItemRowCohortProbe {
  static final List<Long> IDS = List.of(25L, 19019L);
  static final List<String> INTEGER_FIELDS =
      List.of("ClassID", "SubclassID", "InventoryType", "IconFileDataID");

  record Input(
      ArchivedItemHeaderProbe.Target target,
      CdnResource resource,
      long sourceWholeBytes,
      long childOffset,
      int headerBytes,
      int metadataEncodedBytes,
      int metadataDecodedBytes,
      long sectionStart,
      long sectionEnd) {}

  record Scope(Input item, Input sparse, long sparseRecordsEnd) {}

  static final Scope LIVE =
      new Scope(
          new Input(
              ArchivedItemHeaderProbe.ITEM,
              new CdnResource(
                  ArchivedItemHeaderProbe.ROUTE,
                  "31236b924da7e06973fb5603c537fa7b",
                  CdnResource.Kind.DATA),
              55261444,
              45153828,
              1428,
              2949,
              7152,
              7152,
              2136439),
          new Input(
              SparseSectionMetadataProbe.SPARSE.target(),
              new CdnResource(
                  ArchivedItemHeaderProbe.ROUTE,
                  "cab35eb8601260c12a5917c1bdacfda5",
                  CdnResource.Kind.DATA),
              7988612,
              0,
              90732,
              1950,
              5252,
              5252,
              62624722),
          60166704);

  interface Schemas {
    SelectedSchema select(
        BundledItemSchemas.Table table, Wdc5Header header, BooleanSupplier cancelled)
        throws Exception;
  }

  static final Schemas BUNDLED =
      (table, header, cancelled) ->
          new BundledItemSchemas()
              .select(
                  "wow",
                  "12.1.5.70448",
                  table,
                  new SchemaSelection.HeaderFacts(
                      header.tableHash(), header.layoutHash(), header.fieldCount(), header.flags()),
                  cancelled);

  record FixedLookup(
      String status, long requestedId, long physicalSourceId, int rowIndex, boolean copy) {}

  record IntegerProjection(
      String field,
      FieldValueDecoder.IntegerValue raw,
      long candidateValue,
      SelectedSchema.Sign candidateSign,
      int candidateBits) {}

  record Row(
      long id,
      String status,
      long itemPhysicalSourceId,
      long sparsePhysicalSourceId,
      boolean itemCopy,
      boolean sparseCopy,
      Map<String, IntegerProjection> integers,
      String name,
      String nameSHA256) {
    Row {
      integers = Map.copyOf(integers);
    }
  }

  final ArchivedItemHeaderProbe helper;
  final ArchivedItemHeaderProbe.Budget budget;
  final Consumer<String> out;
  final Schemas schemas;
  final BlteChunkPlanner decoder = new BlteChunkPlanner();

  ItemRowCohortProbe(
      ArchivedItemHeaderProbe.Source source,
      CdnRequest.Source route,
      ArchivedItemHeaderProbe.Budget budget,
      Consumer<String> out,
      Schemas schemas) {
    this.helper = new ArchivedItemHeaderProbe(source, route, budget, out);
    this.budget = budget;
    this.out = out;
    this.schemas = schemas;
  }

  static ArchivedItemHeaderProbe.Limits defaults() {
    return new ArchivedItemHeaderProbe.Limits(
        4L * 1024 * 1024,
        512,
        Duration.ofSeconds(120),
        4 * 1024 * 1024,
        1024 * 1024,
        8L * 1024 * 1024 * 1024,
        8L * 1024 * 1024);
  }

  final class ObjectAccess {
    final Input input;
    final BlteChunkPlanner.AuthenticatedPlan plan;

    ObjectAccess(Input input) throws Exception {
      this.input = input;
      byte[] header = encodedRange(0, input.headerBytes());
      plan =
          decoder.authenticate(
              header,
              HexFormat.of().parseHex(input.target().encodingKey()),
              input.target().encodedBytes(),
              input.target().decodedBytes(),
              new BlteChunkPlanner.Limits(
                  input.headerBytes(),
                  100000,
                  input.target().encodedBytes(),
                  input.target().decodedBytes(),
                  64 * 1024 * 1024,
                  64 * 1024 * 1024),
              budget::cancelled);
      var first = plan.chunks().getFirst();
      if (first.encodedOffset() != input.headerBytes()
          || first.decodedOffset() != 0
          || first.encodedBytes() != input.metadataEncodedBytes()
          || first.decodedBytes() != input.metadataDecodedBytes())
        throw new IllegalArgumentException(
            "Known metadata chunk differs from authenticated header");
      out.accept(
          "VERIFIED COHORT HEADER EKey="
              + input.target().encodingKey()
              + " source="
              + input.resource().uri()
              + " childOffset="
              + input.childOffset());
    }

    byte[] encodedRange(long start, int count) throws Exception {
      if (start < 0 || count < 1 || start > input.target().encodedBytes() - count)
        throw new IllegalArgumentException("Child request outside prior encoded extent");
      long translated = Math.addExact(input.childOffset(), start);
      if (Math.addExact(translated, count) > input.sourceWholeBytes())
        throw new IllegalArgumentException("Child outside prior source extent");
      return helper.range(input.resource(), input.sourceWholeBytes(), translated, count);
    }

    List<BlteChunkPlanner.Chunk> selected(List<Window> windows) {
      return plan.chunks().stream()
          .filter(
              chunk ->
                  windows.stream()
                      .anyMatch(
                          w ->
                              chunk.decodedOffset() < w.end()
                                  && chunk.decodedOffset() + chunk.decodedBytes() > w.start()))
          .toList();
    }

    byte[] chunk(BlteChunkPlanner.Chunk chunk) throws Exception {
      if (chunk.encodedBytes() > budget.limits.chunkBytes()
          || chunk.decodedBytes() > budget.limits.chunkBytes())
        throw new IllegalArgumentException("Selected cohort chunk exceeds buffer ceiling");
      budget.decode(chunk.decodedBytes());
      byte[] encoded = encodedRange(chunk.encodedOffset(), chunk.encodedBytes());
      byte[] bytes = decoder.decodeSelected(plan, chunk, encoded, budget::cancelled).decodedBytes();
      out.accept(
          "VERIFIED COHORT CHUNK EKey="
              + input.target().encodingKey()
              + " index="
              + chunk.index()
              + " encodedBytes="
              + chunk.encodedBytes()
              + " decodedBytes="
              + chunk.decodedBytes());
      return bytes;
    }

    byte[][] windows(List<Window> windows, List<BlteChunkPlanner.Chunk> chunks) throws Exception {
      byte[][] result = new byte[windows.size()][];
      int[] filled = new int[windows.size()];
      for (int i = 0; i < result.length; i++) {
        if (windows.get(i).length() < 0 || windows.get(i).length() > budget.limits.chunkBytes())
          throw new IllegalArgumentException("Decoded cohort window exceeds bound");
        result[i] = new byte[windows.get(i).length()];
      }
      for (var chunk : chunks) {
        byte[] decoded = chunk(chunk);
        for (int i = 0; i < result.length; i++)
          filled[i] +=
              SparseIndexMetadataProbe.copyIntersection(
                  chunk, decoded, windows.get(i).start(), windows.get(i).end(), result[i]);
      }
      for (int i = 0; i < result.length; i++)
        if (filled[i] != result[i].length)
          throw new IllegalArgumentException(
              "Missing authenticated coverage for explicit cohort window");
      return result;
    }
  }

  record Window(long start, int length) {
    long end() {
      return Math.addExact(start, length);
    }
  }

  void preflight(
      String phase, List<BlteChunkPlanner.Chunk> first, List<BlteChunkPlanner.Chunk> second) {
    long wire = 0, decoded = 0;
    for (var list : List.of(first, second))
      for (var chunk : list) {
        if (chunk.encodedBytes() > budget.limits.chunkBytes()
            || chunk.decodedBytes() > budget.limits.chunkBytes())
          throw new IllegalArgumentException("Planned cohort chunk exceeds buffer ceiling");
        wire += chunk.encodedBytes();
        decoded += chunk.decodedBytes();
      }
    out.accept(
        "COHORT PLAN phase="
            + phase
            + " remainingBodyBytes="
            + wire
            + " remainingRequests="
            + (first.size() + second.size())
            + " decodedWorkBytes="
            + decoded
            + " cumulativePlannedBodyBytes="
            + (budget.wire + wire)
            + " cumulativePlannedDecode="
            + (budget.decoded + decoded)
            + " noBudgetIncrease=true");
    budget.require(wire, first.size() + second.size(), decoded);
  }

  Wdc5Metadata metadata(byte[] prefix, Input input, boolean sparse) throws Exception {
    var result =
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
    var h = result.header();
    long table = sparse ? 0x919be54eL : 0x50238ec2L, layout = sparse ? 0x1c17d17fL : 0x996192aaL;
    if (h.tableHash() != table
        || h.layoutHash() != layout
        || h.schemaVersion() != 5
        || h.flags() != (sparse ? 5 : 4))
      throw new IllegalArgumentException("Observed cohort header identity mismatch");
    var section = result.sections().getFirst();
    if (!section.encryptionKeyIdentifier().equals("0000000000000000")
        || section.fileOffset() != input.sectionStart()
        || section.declaredEndOffset() != input.sectionEnd())
      throw new IllegalArgumentException(
          "Fresh zero-key cohort section differs from attributed prior extent");
    return result;
  }

  static List<SelectedSchema.Field> inline(SelectedSchema schema) {
    return schema.fields().stream().filter(f -> !f.noninline()).toList();
  }

  static void requireSchema(SelectedSchema schema, Wdc5Metadata metadata, boolean sparse) {
    if (schema.status() != SelectedSchema.Status.MATCH
        || !schema.selection().product().equals("wow")
        || !schema.selection().build().equals("12.1.5.70448")
        || schema.selection().sparse() != sparse
        || schema.selection().tableHash() != metadata.header().tableHash()
        || schema.selection().layoutHash() != metadata.header().layoutHash()
        || inline(schema).size() != metadata.storage().size())
      throw new IllegalArgumentException("Pinned candidate schema/header/physical-field mismatch");
  }

  static FixedLookup fixedLookup(FixedSectionIndex index, long id) {
    int physical = -1, count = 0;
    for (int i = 0; i < index.recordIds().size(); i++)
      if (index.recordIds().get(i) == id) {
        physical = i;
        count++;
      }
    var copies = index.copies().stream().filter(copy -> copy.targetId() == id).toList();
    if (count > 1 || copies.size() > 1 || count > 0 && !copies.isEmpty())
      return new FixedLookup("UNSUPPORTED_AMBIGUOUS_ID", id, 0, -1, false);
    if (count == 1) return new FixedLookup("FOUND", id, id, physical, false);
    if (copies.isEmpty()) return new FixedLookup("NOT_IN_SELECTED_SECTION", id, 0, -1, false);
    long source = copies.getFirst().sourceId();
    int row = -1, matches = 0;
    for (int i = 0; i < index.recordIds().size(); i++)
      if (index.recordIds().get(i) == source) {
        row = i;
        matches++;
      }
    return matches == 1
        ? new FixedLookup("FOUND", id, source, row, true)
        : new FixedLookup("UNSUPPORTED_UNRESOLVED_DIRECT_COPY", id, source, -1, true);
  }

  static int stringPrefixCount(SelectedSchema schema) {
    int count = 0;
    for (var field : inline(schema)) {
      if ((field.type() != SelectedSchema.Type.LOCSTRING
              && field.type() != SelectedSchema.Type.STRING)
          || field.arrayLength() != 1
          || field.unverified())
        throw new IllegalArgumentException(
            "Display prefix is not a verified scalar string sequence");
      count++;
      if (field.name().equals("Display_lang")) {
        if (field.type() != SelectedSchema.Type.LOCSTRING)
          throw new IllegalArgumentException("Display candidate must be localized string");
        return count;
      }
    }
    throw new IllegalArgumentException("Display_lang absent from candidate prefix");
  }

  static Map<String, IntegerProjection> integers(
      byte[] record, Wdc5Metadata metadata, byte[] prefix, SelectedSchema schema) throws Exception {
    List<SelectedSchema.Field> fields = inline(schema);
    Map<String, IntegerProjection> result = new LinkedHashMap<>();
    long palletAt = metadata.metadataBytesRead();
    var decoder = new FieldValueDecoder();
    for (int i = 0; i < fields.size(); i++) {
      var storage = metadata.storage().get(i);
      boolean pallet = storage.rawKind() == 3 || storage.rawKind() == 4;
      long start = palletAt;
      if (pallet) palletAt = Math.addExact(palletAt, storage.additionalDataSize());
      var field = fields.get(i);
      if (!INTEGER_FIELDS.contains(field.name())) continue;
      if (field.type() != SelectedSchema.Type.INT
          || field.arrayLength() != 1
          || field.unverified()
          || field.integerBits() < 1
          || field.integerBits() > 32
          || field.sign() == SelectedSchema.Sign.NOT_SPECIFIED)
        throw new IllegalArgumentException("Selected integer field has unsupported candidate type");
      byte[] values = new byte[0];
      if (pallet) {
        if (palletAt > metadata.declaredAuxiliaryDataEnd() || palletAt > prefix.length)
          throw new IllegalArgumentException(
              "Per-field pallet lies outside authenticated metadata prefix");
        values = Arrays.copyOfRange(prefix, (int) start, (int) palletAt);
      }
      var raw = decoder.decode(record, 0, record.length, storage, values, 0, values.length);
      long candidate = interpret(raw, field);
      result.put(
          field.name(),
          new IntegerProjection(field.name(), raw, candidate, field.sign(), field.integerBits()));
    }
    if (result.size() != 4)
      throw new IllegalArgumentException("Four integer candidate fields not uniquely bound");
    return Map.copyOf(result);
  }

  static long interpret(FieldValueDecoder.IntegerValue raw, SelectedSchema.Field field) {
    int bits = field.integerBits();
    long value;
    if (raw.signedPhysicalValue().isPresent()) value = raw.signedPhysicalValue().getAsLong();
    else if (field.sign() == SelectedSchema.Sign.SIGNED && raw.bitWidth() == 32 && bits == 32)
      value = (int) raw.rawBits();
    else value = raw.rawBits();
    long min = field.sign() == SelectedSchema.Sign.SIGNED ? -(1L << (bits - 1)) : 0,
        max =
            field.sign() == SelectedSchema.Sign.SIGNED ? (1L << (bits - 1)) - 1 : (1L << bits) - 1;
    if (value < min || value > max)
      throw new IllegalArgumentException(
          "Physical integer cannot be interpreted within candidate signedness/width");
    return value;
  }

  List<Row> run(Scope scope, List<Long> ids) throws Exception {
    if (ids.size() > 2 || new HashSet<>(ids).size() != ids.size())
      throw new IllegalArgumentException("Only two distinct explicit cohort IDs");
    for (long id : ids)
      if (id < 1 || id > 0xffffffffL) throw new IllegalArgumentException("Invalid cohort ID");
    out.accept(
        "SCHEMA ATTRIBUTION provider=WoWDBDefs-community-contributors candidate=true"
            + " revision=b9a63096cdd99b26967f17f9515377ae62cadfd7 license=CC-BY-SA-4.0"
            + " licenseURL=https://creativecommons.org/licenses/by-sa/4.0/"
            + " ItemSource=https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/Item.dbd"
            + " SparseSource=https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/ItemSparse.dbd");
    out.accept(
        "PRIOR EXACT COHORT product=wow build=12.1.5.70448 requestedLocale=enUS rootMask0x202 Item="
            + scope.item()
            + " Sparse="
            + scope.sparse()
            + " rootENArchiveIndexRefreshed=false priorSourceTotalsUsed=true");
    var item = new ObjectAccess(scope.item());
    var sparse = new ObjectAccess(scope.sparse());
    var itemMain =
        new Window(
            scope.item().sectionStart(),
            Math.toIntExact(scope.item().sectionEnd() - scope.item().sectionStart()));
    var sparseSuffix =
        new Window(
            scope.sparseRecordsEnd(),
            Math.toIntExact(scope.sparse().sectionEnd() - scope.sparseRecordsEnd()));
    var itemInitial =
        item.selected(List.of(new Window(0, scope.item().metadataDecodedBytes()), itemMain));
    var sparseInitial =
        sparse.selected(
            List.of(new Window(0, scope.sparse().metadataDecodedBytes()), sparseSuffix));
    preflight("metadata-and-selected-section-indices", itemInitial, sparseInitial);
    byte[] itemPrefix = item.chunk(item.plan.chunks().getFirst());
    byte[] sparsePrefix = sparse.chunk(sparse.plan.chunks().getFirst());
    var itemMetadata = metadata(itemPrefix, scope.item(), false);
    var sparseMetadata = metadata(sparsePrefix, scope.sparse(), true);
    if (sparseMetadata.sections().getFirst().offsetRecordsEndOffset() != scope.sparseRecordsEnd())
      throw new IllegalArgumentException("Fresh sparse record/suffix boundary mismatch");
    var itemSchema =
        schemas.select(BundledItemSchemas.Table.ITEM, itemMetadata.header(), budget::cancelled);
    var sparseSchema =
        schemas.select(
            BundledItemSchemas.Table.ITEM_SPARSE, sparseMetadata.header(), budget::cancelled);
    requireSchema(itemSchema, itemMetadata, false);
    requireSchema(sparseSchema, sparseMetadata, true);
    int strings = stringPrefixCount(sparseSchema);
    out.accept(
        "CANDIDATE BINDINGS item="
            + itemSchema.provenance()
            + " sparse="
            + sparseSchema.provenance()
            + " DisplayPrefixStrings="
            + strings
            + " schemaSemanticsGeneralized=false");
    byte[] itemSpan =
        item.windows(List.of(itemMain), itemInitial.stream().filter(c -> c.index() != 0).toList())[
            0];
    byte[] suffix =
        sparse
            .windows(
                List.of(sparseSuffix), sparseInitial.stream().filter(c -> c.index() != 0).toList())[
            0];
    var fixed =
        new FixedSectionIndexParser()
            .parse(
                itemSpan,
                itemMetadata.header(),
                itemMetadata.sections().getFirst(),
                new FixedSectionIndexParser.Limits(budget.limits.chunkBytes(), 1000000, 1000000),
                budget::cancelled);
    var rawSparse =
        new SparseSectionIndexParser()
            .parse(
                suffix,
                sparseMetadata.header(),
                sparseMetadata.sections().getFirst(),
                new SparseSectionIndexParser.Limits(
                    budget.limits.chunkBytes(), 1000000, 100000, 1000000),
                budget::cancelled);
    var locator =
        ValidatedSparseSection.prepare(
            rawSparse,
            sparseMetadata.sections().getFirst(),
            new ValidatedSparseSection.Limits(
                1000000, 100000, scope.sparseRecordsEnd() - scope.sparse().sectionStart()),
            budget::cancelled);
    var fixedLocations = new ArrayList<FixedLookup>();
    var sparseLocations = new ArrayList<ValidatedSparseSection.Lookup>();
    var rowWindows = new ArrayList<Window>();
    var rowPositions = new HashMap<Long, Integer>();
    for (long id : ids) {
      var left = fixedLookup(fixed, id);
      var right = locator.lookup(id);
      fixedLocations.add(left);
      sparseLocations.add(right);
      out.accept("SCOPED COHORT LOOKUP id=" + id + " item=" + left + " sparse=" + right);
      if (left.status().equals("FOUND") && right.status() == ValidatedSparseSection.Status.FOUND) {
        var location = right.location().orElseThrow();
        rowPositions.put(id, rowWindows.size());
        rowWindows.add(new Window(location.decodedFileOffset(), location.length()));
      }
    }
    var rowChunks = sparse.selected(rowWindows);
    if (rowChunks.stream()
        .anyMatch(c -> sparseInitial.stream().anyMatch(prior -> prior.index() == c.index())))
      throw new IllegalArgumentException(
          "Selected row overlaps previously consumed metadata suffix chunk; no implicit duplicate"
              + " work");
    preflight("selected-sparse-records", List.of(), rowChunks);
    byte[][] records = sparse.windows(rowWindows, rowChunks);
    var result = new ArrayList<Row>();
    for (int i = 0; i < ids.size(); i++) {
      long id = ids.get(i);
      var left = fixedLocations.get(i);
      var right = sparseLocations.get(i);
      if (!rowPositions.containsKey(id)) {
        result.add(
            new Row(
                id,
                "SCOPED_UNAVAILABLE item=" + left.status() + " sparse=" + right.status(),
                left.physicalSourceId(),
                0,
                left.copy(),
                false,
                Map.of(),
                "",
                ""));
        continue;
      }
      int at = Math.toIntExact(fixed.recordOffset(left.rowIndex()) - scope.item().sectionStart());
      byte[] itemRecord =
          Arrays.copyOfRange(
              itemSpan, at, at + Math.toIntExact(itemMetadata.header().recordSize()));
      var numeric = integers(itemRecord, itemMetadata, itemPrefix, itemSchema);
      byte[] sparseRecord = records[rowPositions.get(id)];
      var text =
          new CStringPrefixReader()
              .read(
                  sparseRecord,
                  strings,
                  new CStringPrefixReader.Limits(65535, 16, 16384, 65535),
                  budget::cancelled);
      String name = text.strings().getLast();
      var location = right.location().orElseThrow();
      result.add(
          new Row(
              id,
              "JOINED_CANDIDATE",
              left.physicalSourceId(),
              location.physicalSourceId(),
              left.copy(),
              location.copy(),
              numeric,
              name,
              hexName(name)));
    }
    budget.check();
    for (var row : result) {
      out.accept(
          "PRIVATE ROW id="
              + row.id()
              + " status="
              + row.status()
              + " integerCandidates="
              + row.integers()
              + " Display_lang="
              + row.name().replace("\n", "\\n").replace("\r", "\\r"));
      out.accept(
          "PUBLIC ROW id="
              + row.id()
              + " status="
              + row.status()
              + " itemPhysicalSourceId="
              + row.itemPhysicalSourceId()
              + " sparsePhysicalSourceId="
              + row.sparsePhysicalSourceId()
              + " itemCopy="
              + row.itemCopy()
              + " sparseCopy="
              + row.sparseCopy()
              + " projectedIntegerCount="
              + row.integers().size()
              + " displaySHA256="
              + row.nameSHA256()
              + " exactBuild=12.1.5.70448 requestedLocale=enUS");
    }
    out.accept(
        "COHORT COVERAGE requested="
            + ids.size()
            + " joined="
            + result.stream().filter(row -> row.status().equals("JOINED_CANDIDATE")).count()
            + " directCopySourcesPreserved=true priorRootENIndexNotRefreshed=true"
            + " consumedChunkProofOnly=true fullTableCKeysVerified=false"
            + " otherSectionReadabilityUnknown=true generalSchemaSupport=false"
            + " generalNameSearch=false noPayloadWrites=true");
    cost();
    return List.copyOf(result);
  }

  static String hexName(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  void cost() {
    out.accept(
        "COHORT COST requests="
            + budget.requests
            + " responseBodyBytes="
            + budget.wire
            + " decodedWorkBytes="
            + budget.decoded
            + " elapsedNanos="
            + (budget.nanos.getAsLong() - budget.started)
            + " failedReservationsConservative=true");
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      ItemRowCohortProbeSelfTest.main(new String[0]);
      return;
    }
    if (args.length != 1 || !args[0].equals("--live"))
      throw new IllegalArgumentException(
          "Use --self-test or separately authorized --live; fixed cohort and budgets only");
    var budget =
        new ArchivedItemHeaderProbe.Budget(
            defaults(), System::nanoTime, () -> Thread.currentThread().isInterrupted());
    var probe =
        new ItemRowCohortProbe(
            new ArchivedItemHeaderProbe.LiveSource(budget),
            ArchivedItemHeaderProbe.ROUTE,
            budget,
            System.out::println,
            BUNDLED);
    try {
      probe.run(LIVE, IDS);
    } catch (Exception failure) {
      probe.cost();
      System.out.println(
          "COHORT STOP outcome="
              + failure.getClass().getSimpleName()
              + " message="
              + failure.getMessage()
              + " noScopeExpansion=true noPayloadWrites=true");
      throw failure;
    }
  }
}
