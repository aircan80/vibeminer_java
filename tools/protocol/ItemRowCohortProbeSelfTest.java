import io.vibeminer.format.wow.schema.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.schema.BundledItemSchemas;
import io.vibeminer.protocol.db2.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Complete generated two-table fixtures only; no game payload or network request. */
public final class ItemRowCohortProbeSelfTest {
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

  static byte[] strings(String... values) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    for (String value : values) {
      out.write(value.getBytes(StandardCharsets.UTF_8));
      out.write(0);
    }
    return out.toByteArray();
  }

  static void header(
      ByteBuffer b, int fields, int recordSize, int flags, long table, long layout, int records) {
    b.put(new byte[] {'W', 'D', 'C', '5'})
        .putInt(5)
        .putInt(136, records)
        .putInt(140, fields)
        .putInt(144, recordSize)
        .putInt(152, (int) table)
        .putInt(156, (int) layout)
        .putShort(172, (short) flags)
        .putShort(174, (short) 0xffff)
        .putInt(176, fields)
        .putInt(188, fields * 24)
        .putInt(200, 1);
  }

  static SelectedSchema.Field field(
      String name,
      SelectedSchema.Type type,
      int bits,
      SelectedSchema.Sign sign,
      Set<String> annotations) {
    return new SelectedSchema.Field(
        name, name, type, false, Optional.empty(), bits, sign, 1, annotations);
  }

  static SelectedSchema schema(
      BundledItemSchemas.Table table, Wdc5Header header, boolean mismatch, boolean badPrefix) {
    var fields = new ArrayList<SelectedSchema.Field>();
    fields.add(
        field(
            "ID",
            SelectedSchema.Type.INT,
            32,
            SelectedSchema.Sign.SIGNED,
            Set.of("id", "noninline")));
    if (table == BundledItemSchemas.Table.ITEM) {
      for (String name : ItemRowCohortProbe.INTEGER_FIELDS)
        fields.add(
            field(
                name,
                SelectedSchema.Type.INT,
                name.equals("SubclassID") || name.equals("InventoryType") ? 8 : 32,
                name.equals("SubclassID")
                    ? SelectedSchema.Sign.UNSIGNED
                    : SelectedSchema.Sign.SIGNED,
                Set.of()));
    } else {
      for (String name :
          List.of(
              "Description_lang",
              "Display3_lang",
              "Display2_lang",
              "Display1_lang",
              "Display_lang"))
        fields.add(
            field(
                name,
                badPrefix && name.equals("Description_lang")
                    ? SelectedSchema.Type.INT
                    : SelectedSchema.Type.LOCSTRING,
                0,
                SelectedSchema.Sign.NOT_SPECIFIED,
                Set.of()));
    }
    return new SelectedSchema(
        mismatch ? SelectedSchema.Status.INCOMPATIBLE : SelectedSchema.Status.MATCH,
        new SchemaSelection(
            "wow",
            "12.1.5.70448",
            table == BundledItemSchemas.Table.ITEM ? "Item" : "ItemSparse",
            header.tableHash(),
            header.layoutHash(),
            (int) header.fieldCount(),
            (header.flags() & 1) != 0,
            true),
        new SelectedSchema.Provenance(
            "Authored fixture",
            "synthetic",
            "https://example.invalid/fixture",
            "0".repeat(64),
            "CC0-1.0",
            "Authored synthetic fields",
            false),
        fields,
        List.of());
  }

  static final class Fixture implements ArchivedItemHeaderProbe.Source {
    final byte[] itemArchive, sparse;
    final ItemRowCohortProbe.Scope scope;
    final List<CdnRangeRequest> requests = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    final long sparseRecordsEnd;
    boolean corruptItemMain, swapSource;
    Runnable afterItemMetadata = () -> {}, afterSparseHeader = () -> {};

    Fixture() throws Exception {
      this(false, false);
    }

    Fixture(boolean badUTF8, boolean unresolvedCopy) throws Exception {
      byte[] item = new byte[500];
      var i = ByteBuffer.wrap(item).order(ByteOrder.LITTLE_ENDIAN);
      header(i, 4, 4, 4, 0x50238ec2L, 0x996192aaL, 2);
      i.putLong(204, 0).putInt(212, 400).putInt(216, 2).putInt(228, 8).putInt(240, 1);
      for (int n = 0; n < 4; n++) {
        int at = 260 + n * 24;
        i.putShort(at, (short) (n * 8))
            .putShort(at + 2, (short) 8)
            .putInt(at + 8, 1)
            .putInt(at + 12, n * 8)
            .putInt(at + 16, 8);
      }
      i.position(400);
      i.put(new byte[] {4, 1, 5, 100, 2, 8, 13, (byte) 200})
          .putInt(25)
          .putInt(42)
          .putInt(19019)
          .putInt(unresolvedCopy ? 999 : 42);
      byte[] itemEncoded =
          ItemCohortProbeSelfTest.blte(
              Arrays.copyOfRange(item, 0, 400),
              Arrays.copyOfRange(item, 400, 424),
              Arrays.copyOfRange(item, 424, 500));
      var itemTarget =
          new ArchivedItemHeaderProbe.Target(
              841626,
              ItemCohortProbeSelfTest.hash("MD5", item),
              ItemCohortProbeSelfTest.ekey(itemEncoded),
              itemEncoded.length,
              item.length,
              "Synthetic Item prior context.");
      itemArchive = new byte[64 + itemEncoded.length + 16];
      System.arraycopy(itemEncoded, 0, itemArchive, 64, itemEncoded.length);
      byte[] row25 = strings("description-a", "alt3-a", "", "x", "Fixture Shirt"),
          row26 = strings("", "b", "c", "d", "Épée Synthétique");
      if (badUTF8) {
        row26 = row26.clone();
        row26[7] = (byte) 0xc3;
        row26[8] = (byte) 0x28;
      }
      int recordsEnd = 400 + row25.length + row26.length, end = recordsEnd + 36;
      sparseRecordsEnd = recordsEnd;
      byte[] sparseDecoded = new byte[end + 20];
      var s = ByteBuffer.wrap(sparseDecoded).order(ByteOrder.LITTLE_ENDIAN);
      header(s, 5, 4, 5, 0x919be54eL, 0x1c17d17fL, 2);
      s.putLong(204, 0)
          .putInt(212, 400)
          .putInt(216, 2)
          .putInt(224, recordsEnd)
          .putInt(228, 8)
          .putInt(236, 2)
          .putInt(240, 1);
      System.arraycopy(row25, 0, sparseDecoded, 400, row25.length);
      System.arraycopy(row26, 0, sparseDecoded, 400 + row25.length, row26.length);
      s.position(recordsEnd);
      s.putInt(25)
          .putInt(26)
          .putInt(19019)
          .putInt(26)
          .putInt(400)
          .putShort((short) row25.length)
          .putInt(400 + row25.length)
          .putShort((short) row26.length)
          .putInt(25)
          .putInt(26);
      sparse =
          ItemCohortProbeSelfTest.blte(
              Arrays.copyOfRange(sparseDecoded, 0, 400),
              Arrays.copyOfRange(sparseDecoded, 400, recordsEnd),
              Arrays.copyOfRange(sparseDecoded, recordsEnd, end),
              Arrays.copyOfRange(sparseDecoded, end, sparseDecoded.length));
      var sparseTarget =
          new ArchivedItemHeaderProbe.Target(
              1572924,
              ItemCohortProbeSelfTest.hash("MD5", sparseDecoded),
              ItemCohortProbeSelfTest.ekey(sparse),
              sparse.length,
              sparseDecoded.length,
              "Synthetic Sparse prior context.");
      scope =
          new ItemRowCohortProbe.Scope(
              new ItemRowCohortProbe.Input(
                  itemTarget,
                  new CdnResource(
                      ArchivedItemHeaderProbe.ROUTE, "1".repeat(32), CdnResource.Kind.DATA),
                  itemArchive.length,
                  64,
                  ByteBuffer.wrap(itemEncoded).getInt(4),
                  401,
                  400,
                  400,
                  424),
              new ItemRowCohortProbe.Input(
                  sparseTarget,
                  new CdnResource(
                      ArchivedItemHeaderProbe.ROUTE,
                      sparseTarget.encodingKey(),
                      CdnResource.Kind.DATA),
                  sparse.length,
                  0,
                  ByteBuffer.wrap(sparse).getInt(4),
                  401,
                  400,
                  400,
                  end),
              recordsEnd);
    }

    public AcquiredBytes configuration(CdnRequest request) {
      throw new AssertionError("No configuration refresh");
    }

    public IndexFooterObservation footer(CdnIndexFooterRequest request) {
      throw new AssertionError("No index scan");
    }

    public DataSizeObservation size(CdnSizeRequest request) {
      throw new AssertionError("No HEAD refresh");
    }

    public AcquiredRange range(CdnRangeRequest request) throws Exception {
      requests.add(request);
      boolean item = request.encodedKey().equals(scope.item().resource().key());
      byte[] whole = item ? itemArchive : sparse;
      check(
          request.wholeEncodedLength() == whole.length,
          "Fresh exact response uses attributed source total");
      byte[] bytes =
          Arrays.copyOfRange(whole, (int) request.start(), (int) request.start() + request.count());
      if (corruptItemMain && item && request.start() >= 64 + scope.item().headerBytes() + 401)
        bytes[bytes.length - 1] ^= 1;
      var actual =
          swapSource
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
      if (item && request.start() == 64 + scope.item().headerBytes()) afterItemMetadata.run();
      if (!item && request.start() == 0) afterSparseHeader.run();
      return result;
    }

    ItemRowCohortProbe probe(
        ArchivedItemHeaderProbe.Budget budget, boolean mismatch, boolean badPrefix) {
      return new ItemRowCohortProbe(
          this,
          ArchivedItemHeaderProbe.ROUTE,
          budget,
          events::add,
          (table, h, cancel) -> schema(table, h, mismatch, badPrefix));
    }
  }

  static ArchivedItemHeaderProbe.Budget budget(
      ArchivedItemHeaderProbe.Limits limits, AtomicLong time, AtomicBoolean stop) {
    return new ArchivedItemHeaderProbe.Budget(limits, time::get, stop::get);
  }

  static List<ItemRowCohortProbe.Row> run(Fixture f) throws Exception {
    return f.probe(
            budget(ItemRowCohortProbe.defaults(), new AtomicLong(), new AtomicBoolean()),
            false,
            false)
        .run(f.scope, ItemRowCohortProbe.IDS);
  }

  static void exactProjectionCopiesStringsAndPublicScope() throws Exception {
    var f = new Fixture();
    var rows = run(f);
    check(
        rows.size() == 2 && rows.stream().allMatch(row -> row.status().equals("JOINED_CANDIDATE")),
        "Two exact IDs joined under explicit context");
    check(
        rows.get(0).name().equals("Fixture Shirt") && rows.get(1).name().equals("Épée Synthétique"),
        "Fifth variable-length UTF8 string selected in schema order");
    check(
        rows.get(1).itemPhysicalSourceId() == 42
            && rows.get(1).sparsePhysicalSourceId() == 26
            && rows.get(1).itemCopy()
            && rows.get(1).sparseCopy(),
        "Independent direct physical copy sources preserved");
    check(
        rows.get(1).integers().get("IconFileDataID").candidateValue() == 200,
        "Four candidate integer fields decoded with raw values retained");
    check(
        f.requests.size() == 7,
        "Headers, metadata, fixed span, full suffix and deduplicated Sparse row chunk only");
    String publicLines =
        String.join("\n", f.events.stream().filter(e -> e.startsWith("PUBLIC ROW")).toList());
    check(
        !publicLines.contains("Fixture Shirt")
            && !publicLines.contains("Synthétique")
            && publicLines.contains("displaySHA256="),
        "Public summary excludes actual names and numeric contents");
    check(
        f.events.stream()
            .anyMatch(
                e ->
                    e.contains("fullTableCKeysVerified=false")
                        && e.contains("generalNameSearch=false")),
        "No general row/table/search claims");
  }

  static void mismatchesAndMalformedNamesCannotPublishJoinedRows() throws Exception {
    var schemaBad = new Fixture();
    failure(
        () ->
            schemaBad
                .probe(
                    budget(ItemRowCohortProbe.defaults(), new AtomicLong(), new AtomicBoolean()),
                    true,
                    false)
                .run(schemaBad.scope, ItemRowCohortProbe.IDS));
    check(
        schemaBad.requests.size() == 4,
        "Schema mismatch stops after metadata before body/index ranges");
    var badPrefix = new Fixture();
    failure(
        () ->
            badPrefix
                .probe(
                    budget(ItemRowCohortProbe.defaults(), new AtomicLong(), new AtomicBoolean()),
                    false,
                    true)
                .run(badPrefix.scope, ItemRowCohortProbe.IDS));
    check(
        badPrefix.requests.size() == 4, "Nonstring predecessor cannot be skipped by fixed offset");
    for (int mode = 0; mode < 3; mode++) {
      var f = new Fixture(mode == 0, false);
      f.corruptItemMain = mode == 1;
      f.swapSource = mode == 2;
      failure(() -> run(f));
      check(
          f.events.stream().noneMatch(e -> e.startsWith("PUBLIC ROW")),
          "BadUTF8/chunk/source fails before joined publication");
    }
    var copy = new Fixture(false, true);
    var rows = run(copy);
    check(
        rows.get(1).status().contains("UNSUPPORTED_UNRESOLVED_DIRECT_COPY")
            && rows.get(1).name().isEmpty(),
        "Unresolved copy is scoped unsupported, never recursively repaired");
  }

  static void preflightAndCancellationBoundWholeWork() throws Exception {
    var d = ItemRowCohortProbe.defaults();
    for (int mode = 0; mode < 4; mode++) {
      var f = new Fixture();
      long initial = scopeInitialWire(f);
      var limits =
          new ArchivedItemHeaderProbe.Limits(
              mode == 0 ? initial - 1 : d.wireBytes(),
              mode == 1 ? 5 : d.requests(),
              d.time(),
              mode == 3 ? 400 : d.chunkBytes(),
              d.indexBytes(),
              d.archiveBytes(),
              mode == 2 ? 859 : d.decodedWorkBytes());
      failure(
          () ->
              f.probe(budget(limits, new AtomicLong(), new AtomicBoolean()), false, false)
                  .run(f.scope, ItemRowCohortProbe.IDS));
      check(
          f.requests.size() == 2,
          "Actual initial plan refuses after two headers, before any payload");
    }
    var time = new AtomicLong();
    var timeout = new Fixture();
    timeout.afterSparseHeader = () -> time.set(d.time().toNanos() + 1);
    failure(
        () ->
            timeout
                .probe(budget(d, time, new AtomicBoolean()), false, false)
                .run(timeout.scope, ItemRowCohortProbe.IDS));
    check(timeout.requests.size() == 2, "Deadline stops initial chunk payloads");
    var stop = new AtomicBoolean();
    var cancel = new Fixture();
    cancel.afterItemMetadata = () -> stop.set(true);
    failure(
        () ->
            cancel
                .probe(budget(d, new AtomicLong(), stop), false, false)
                .run(cancel.scope, ItemRowCohortProbe.IDS));
    check(cancel.requests.size() == 3, "Cancellation stops remaining metadata and body work");
  }

  static long scopeInitialWire(Fixture f) {
    return f.scope.item().headerBytes() + f.scope.sparse().headerBytes() + 401 + 25 + 401 + 37;
  }

  static void bundledCandidateResourcesAndCopyAmbiguity() throws Exception {
    var bundle = new BundledItemSchemas();
    var item =
        bundle.select(
            "wow",
            "12.1.5.70448",
            BundledItemSchemas.Table.ITEM,
            new SchemaSelection.HeaderFacts(0x50238ec2L, 0x996192aaL, 15, 4),
            () -> false);
    var sparse =
        bundle.select(
            "wow",
            "12.1.5.70448",
            BundledItemSchemas.Table.ITEM_SPARSE,
            new SchemaSelection.HeaderFacts(0x919be54eL, 0x1c17d17fL, 67, 5),
            () -> false);
    check(
        item.status() == SelectedSchema.Status.MATCH
            && sparse.status() == SelectedSchema.Status.MATCH,
        "Pinned classpath schemas loaded and digest-validated");
    check(
        ItemRowCohortProbe.stringPrefixCount(sparse) == 5,
        "Actual candidate Display_lang is fifth ordered string");
    var duplicate =
        new FixedSectionIndex(
            100,
            4,
            108,
            108,
            116,
            124,
            List.of(25L, 25L),
            List.of(new FixedSectionIndex.Copy(19019, 25)));
    check(
        ItemRowCohortProbe.fixedLookup(duplicate, 25).status().equals("UNSUPPORTED_AMBIGUOUS_ID"),
        "Duplicate physical IDs cannot be repaired");
    check(
        ItemRowCohortProbe.fixedLookup(duplicate, 19019)
            .status()
            .equals("UNSUPPORTED_UNRESOLVED_DIRECT_COPY"),
        "Ambiguous copy source remains unsupported");
  }

  public static void main(String[] args) throws Exception {
    exactProjectionCopiesStringsAndPublicScope();
    mismatchesAndMalformedNamesCannotPublishJoinedRows();
    preflightAndCancellationBoundWholeWork();
    bundledCandidateResourcesAndCopyAmbiguity();
    System.out.println(
        "Item row cohort synthetic self-test passed: "
            + assertions
            + " assertions; networkRequests=0; generated row contents only.");
  }
}
