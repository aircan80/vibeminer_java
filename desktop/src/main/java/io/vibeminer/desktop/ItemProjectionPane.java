package io.vibeminer.desktop;

import io.vibeminer.domain.item.*;
import java.util.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

/**
 * Bounded physical-row UI. Names, icons, copies, search and relationships are deliberately absent.
 */
public final class ItemProjectionPane extends VBox {
  private final ItemPresentationModel model;
  private final double scale;
  private final Runnable inspectSource;
  private TableView<ItemPhysicalRow> rowTable;
  private TextField input;
  private Label statusLabel;
  private Label modeLabel;

  public ItemProjectionPane(ItemPresentationModel model, double scale) {
    this(model, scale, null);
  }

  public ItemProjectionPane(ItemPresentationModel model, double scale, Runnable inspectSource) {
    super(12);
    this.inspectSource = inspectSource;
    this.model = Objects.requireNonNull(model);
    this.scale = scale;
    getStyleClass().add("panel");
    refreshState();
  }

  public void refreshDraftState() {
    var s = model.state();
    if (rowTable != null && s.selected() == null) rowTable.getSelectionModel().clearSelection();
    if (statusLabel != null) {
      statusLabel.setText(s.message());
      statusLabel.setAccessibleText(s.message());
      statusLabel.getStyleClass().setAll(s.code() == null ? "body-copy" : "notice-heading");
    }
    if (modeLabel != null) modeLabel.setText(s.mode() + " · fixed page limit " + model.pageSize());
  }

  public void refreshState() {
    var s = model.state();
    getChildren().clear();
    rowTable = null;
    getChildren()
        .addAll(
            copy("DATABASE / ITEM", "eyebrow"),
            copy("Item physical rows", "section-heading"),
            copy(
                "Required Item table file · FileDataID 841626. Item record IDs identify rows;"
                    + " IconFileDataID is a separate candidate field.",
                "body-copy"));
    Label coverage =
        copy(
            s.handle() == null
                ? "Not prepared · Admitted zero-key physical rows only · Copies and other sections"
                    + " are not indexed"
                : coverage(s.handle()),
            "body-copy");
    coverage.setId("item-coverage");
    getChildren().add(coverage);
    Label status = copy(s.message(), s.code() != null ? "notice-heading" : "body-copy");
    statusLabel = status;
    status.setId("item-status");
    status.setAccessibleText(s.message());
    getChildren().add(status);
    if (s.stage() == ItemPresentationModel.Stage.NO_SOURCE
        || s.stage() == ItemPresentationModel.Stage.UNSUPPORTED) {
      if (s.source() != null) getChildren().add(sourceIdentity(s));
      if (inspectSource != null)
        getChildren()
            .add(button("Inspect retained file scope", inspectSource, "item-inspect-source"));
      return;
    }
    if (s.busy()) {
      ProgressIndicator p = new ProgressIndicator();
      p.setMaxSize(25, 25);
      p.setAccessibleText("Local Item operation in progress. Percentage unknown.");
      getChildren()
          .add(
              new FlowPane(10, 8, p, button("Cancel local request", model::cancel, "item-cancel")));
    }
    if (s.handle() == null) {
      if (!s.busy()) {
        var l = model.limits();
        getChildren()
            .add(
                copy(
                    "Offline work only. Authenticate admitted physical rows and build a local"
                        + " index. Copies and other sections are not indexed. Duration unknown; no"
                        + " acquisition or repair.",
                    "body-copy"));
        TitledPane workLimits =
            new TitledPane(
                "Local work limits",
                copy(
                    "Verify up to "
                        + bytes(l.maxEncodedBytes())
                        + " encoded bytes; admit at most "
                        + bytes(l.maxSectionBytes())
                        + ", "
                        + l.maxPhysicalRows()
                        + " physical rows and "
                        + l.maxCopyDeclarations()
                        + " copy declarations (not indexed). Metadata limit "
                        + bytes(l.maxMetadataBytes())
                        + ", pallet work "
                        + bytes(l.maxPalletBytes())
                        + ". Duration unknown. No acquisition or repair.",
                    "secondary"));
        workLimits.setExpanded(false);
        workLimits.setId("item-local-limits");
        Button prepare = button("Prepare supported rows", model::prepare, "item-prepare");
        prepare.getStyleClass().add("primary");
        getChildren().addAll(prepare, workLimits);
      }
      getChildren().add(sourceIdentity(s));
      return;
    }
    var h = s.handle();
    Label attribution =
        copy(
            "Candidate fields · " + h.definition().interpretation().schema().attribution(),
            "secondary");
    attribution.setId("item-schema-attribution");
    getChildren().add(attribution);
    input = new TextField(s.input());
    input.setId("item-record-id");
    input.setPromptText("Positive Item record ID");
    input.setAccessibleText(
        "Exact Item record ID from one to 4294967295; not the source FileDataID");
    input.setPrefColumnCount(15);
    input.setMaxWidth(360);
    input.textProperty().addListener((o, a, b) -> model.setInput(b));
    Button lookup = button("Look up ID", model::lookup, "item-lookup");
    lookup.setDisable(s.busy());
    input.setOnAction(
        e -> {
          if (!lookup.isDisabled()) model.lookup();
          e.consume();
        });
    Button browse = button("Browse physical rows", model::firstPage, "item-browse");
    browse.setDisable(s.busy());
    Button first = button("First page", model::firstPage, "item-first-page");
    first.setDisable(s.busy());
    Button next = button("Next page", model::nextPage, "item-next-page");
    next.setDisable(s.busy() || s.page() == null || s.page().nextCursor().isEmpty());
    next.setAccessibleText("Next bounded page of physical Item rows ordered by numeric record ID");
    getChildren()
        .add(
            new FlowPane(
                10,
                8,
                new VBox(4, copy("EXACT ITEM RECORD ID", "eyebrow"), input),
                lookup,
                browse,
                first,
                next));
    modeLabel = copy(s.mode() + " · fixed page limit " + model.pageSize(), "secondary");
    getChildren().add(modeLabel);
    List<ItemPhysicalRow> rows =
        s.page() != null
            ? s.page().rows()
            : s.lookup() != null ? s.lookup().row().stream().toList() : List.of();
    if (!rows.isEmpty()) {
      TableView<ItemPhysicalRow> table = new TableView<>(FXCollections.observableArrayList(rows));
      rowTable = table;
      table.setId("item-row-table");
      table.setAccessibleText(
          "Item physical rows: Item record ID and four community-attributed integer candidates."
              + " Enter inspects selected row.");
      table
          .getColumns()
          .add(column("Item record ID", r -> Long.toUnsignedString(r.recordId()), 220));
      String[] labels = {"ClassID", "SubclassID", "InventoryType", "IconFileDataID"};
      for (int i = 0; i < 4; i++) {
        int n = i;
        table
            .getColumns()
            .add(column(labels[i], r -> candidateValue(r.values().get(n)), i == 3 ? 260 : 180));
      }
      table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
      double row = Math.max(43, 16 * scale + 16);
      table.setFixedCellSize(row);
      table.setPrefHeight(Math.max(44, 20 * scale + 24) + Math.min(rows.size(), 6) * row + 2);
      table.setMinHeight(Region.USE_PREF_SIZE);
      table.setMaxHeight(Region.USE_PREF_SIZE);
      if (s.selected() != null) table.getSelectionModel().select(s.selected());
      VBox detail = new VBox(10);
      detail.setId("item-row-detail");
      showRow(detail, s.selected(), h);
      table
          .getSelectionModel()
          .selectedItemProperty()
          .addListener(
              (o, a, b) -> {
                model.selectRow(b);
                showRow(detail, b, h);
              });
      table.setOnKeyPressed(
          e -> {
            if (e.getCode() == KeyCode.ENTER) {
              Node target = detail.lookup("#item-row-value");
              if (target != null) target.requestFocus();
              e.consume();
            }
          });
      detail.setOnKeyPressed(
          e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
              table.requestFocus();
              e.consume();
            }
          });
      getChildren().addAll(table, detail);
    } else if (s.lookup() != null
        && s.lookup().outcome() == ItemLookupResult.Outcome.COPY_UNINDEXED)
      getChildren()
          .add(
              copy(
                  "Requested Item record ID "
                      + s.lookup().requestedId()
                      + " · declared physical source ID "
                      + s.lookup().copySourceId().getAsLong()
                      + ". This copy declaration is not indexed; no row values are inferred.",
                  "body-copy"));
    getChildren().add(proof(h));
  }

  private static String coverage(PreparedItemProjection h) {
    var c = h.coverage();
    return "Admitted zero-key section "
        + c.sectionOrdinal()
        + " · "
        + c.physicalRows()
        + " physical rows · "
        + c.unindexedCopyDeclarations()
        + " copy declarations not indexed · "
        + c.excludedSections()
        + " other sections not indexed";
  }

  private static void showRow(VBox detail, ItemPhysicalRow row, PreparedItemProjection h) {
    detail.getChildren().clear();
    if (row == null) {
      detail
          .getChildren()
          .add(
              copy(
                  "Select a physical row to inspect raw values and candidate interpretation.",
                  "body-copy"));
      return;
    }
    StringBuilder values =
        new StringBuilder(
            "Item record ID: "
                + row.recordId()
                + "\nSource FileDataID: "
                + h.evidence().association().descriptor().association().fileDataId()
                + "\nPhysical row ordinal: "
                + row.physicalOrdinal()
                + " / section "
                + row.sectionOrdinal()
                + "\nArtifact: "
                + h.artifactId());
    for (var v : row.values())
      values
          .append("\n\n")
          .append(v.field())
          .append(" candidate: ")
          .append(candidateValue(v))
          .append(" (")
          .append(v.candidateBits())
          .append(" bits, ")
          .append(v.candidateSign())
          .append(")\nRaw bits: 0x")
          .append(Long.toUnsignedString(v.rawBits(), 16))
          .append(" / width ")
          .append(v.rawWidth())
          .append(" / ")
          .append(v.physicalInterpretation());
    TextArea full =
        value(values.toString(), "Full physical Item row and four raw candidate values");
    full.setId("item-row-value");
    full.setPrefRowCount(8);
    detail
        .getChildren()
        .addAll(copy("Item record " + row.recordId() + " · Physical row", "section-heading"), full);
  }

  static TitledPane proof(PreparedItemProjection h) {
    var e = h.evidence();
    var a = e.association();
    var i = h.definition().interpretation();
    var schema = i.schema();
    VBox body =
        new VBox(
            10,
            value(
                "Exact source: "
                    + a.descriptor().association().build().productId()
                    + " / "
                    + a.descriptor().association().build().channel()
                    + " / "
                    + a.descriptor().association().build().region()
                    + " / "
                    + a.descriptor().association().build().versionLabel()
                    + " / "
                    + h.locale()
                    + "\nBuild config: "
                    + a.descriptor().association().build().buildConfig()
                    + "\nCDN config: "
                    + a.descriptor().association().build().cdnConfig()
                    + "\nSelected raw variant: "
                    + a.descriptor().association()
                    + "\nSource role: "
                    + h.sourceRole()
                    + "\nArtifact: "
                    + h.artifactId(),
                "Exact Item source and artifact context"),
            copy("1 · Complete encoded object / local physical SHA", "eyebrow"),
            value(
                "Encoded verifier: "
                    + a.representation().encoded().evidence().verifierSemantics()
                    + "\nActual encoding: "
                    + a.descriptor().actualEncodingKey()
                    + "\nOriginal acquisition URL: "
                    + a.representation().sourceReceipt().actualResourceUrl()
                    + "\nOriginal observation: "
                    + a.representation().sourceReceipt().observedAt()
                    + "\nLocal physical SHA-256: "
                    + e.localVerification().object().digest().value()
                    + "\nLocal verified at: "
                    + e.localVerification().verifiedAt()
                    + " / "
                    + e.localVerification().verifierRevision(),
                "Complete encoded and current local physical verification"),
            copy("2 · Consumed decoded chunks / admitted windows", "eyebrow"),
            value(
                "Authenticated chunks: "
                    + e.chunks()
                    + "\nAdmitted windows: "
                    + e.windows()
                    + "\nCoverage: "
                    + coverage(h),
                "Authenticated consumed chunk and admitted window scope"),
            copy("3 · Declared decoded content association", "eyebrow"),
            value(
                "Declared CKey: "
                    + a.descriptor().association().contentKey()
                    + "\nRoot source: "
                    + a.proof().rootSource()
                    + "\nEncoding source: "
                    + a.proof().encodingBootstrapSource()
                    + "\nAssociation verifier: "
                    + a.proof().verifierRevision()
                    + " / "
                    + a.proof().proofDigest(),
                "Declared content association is not full decoded verification"),
            copy("4 · Complete decoded length / CKey", "eyebrow"),
            copy(
                e.fullDecoded().isPresent()
                    ? "Established by explicit attestation: " + e.fullDecoded().get()
                    : "Not established. Ready physical rows do not verify the complete decoded"
                        + " table.",
                "body-copy"),
            copy("Candidate definition · " + schema.attribution(), "eyebrow"),
            value(
                "Provider: "
                    + schema.provider()
                    + "\nRevision: "
                    + schema.revision()
                    + "\nDefinition: "
                    + schema.definitionPath()
                    + "\nDefinition SHA-256: "
                    + schema.definitionSha256()
                    + "\nSelection evidence: "
                    + schema.selectionDigest()
                    + "\nLicense: "
                    + schema.license()
                    + "\nUpstream definition: https://github.com/wowdev/WoWDBDefs/blob/"
                    + schema.revision()
                    + "/"
                    + schema.definitionPath()
                    + "\nUpstream license: https://github.com/wowdev/WoWDBDefs/blob/"
                    + schema.revision()
                    + "/LICENSE.md"
                    + "\nField bindings: "
                    + schema.fields()
                    + "\nTable/layout: "
                    + Long.toHexString(i.tableHash())
                    + " / "
                    + Long.toHexString(i.layoutHash())
                    + "\nDecoder/index: "
                    + i.decoderRevision()
                    + " / "
                    + i.indexRevision()
                    + "\nOptions/proof: "
                    + i.optionsRevision()
                    + " / "
                    + i.proofSemantics(),
                "Community candidate definition attribution and pinned interpretation"));
    TitledPane pane =
        new TitledPane("Source, four proof dimensions and candidate attribution", body);
    pane.setId("item-source-proof");
    pane.setExpanded(false);
    return pane;
  }

  private static TitledPane sourceIdentity(ItemPresentationModel.State s) {
    var a = s.source();
    String text =
        a == null
            ? "No current verified retained descriptor."
            : "Selected build: "
                + a.descriptor().association().build().versionLabel()
                + "\nSelected FileDataID: "
                + a.descriptor().association().fileDataId()
                + "\nDeclared content: "
                + a.descriptor().association().contentKey()
                + "\nActual encoding: "
                + a.descriptor().actualEncodingKey()
                + "\nLocale view: "
                + s.locale()
                + "\nOriginal encoded receipt URL: "
                + a.representation().sourceReceipt().actualResourceUrl()
                + "\nOriginal observation: "
                + a.representation().sourceReceipt().observedAt()
                + "\nOriginal physical SHA-256: "
                + a.representation().encoded().object().digest().value()
                + "\n"
                + "Historical receipt is inspectable; current Item evidence readiness is reported"
                + " separately.";
    var p =
        new TitledPane(
            "Retained source identity and original receipt",
            value(text, "Historical source receipt; current verification status is separate"));
    p.setExpanded(false);
    return p;
  }

  private static String candidateValue(ItemPhysicalRow.IntegerCandidate v) {
    return v.candidateSign() == ItemArtifactDefinition.Sign.UNSIGNED
        ? Long.toUnsignedString(v.candidateValue())
        : Long.toString(v.candidateValue());
  }

  private static TableColumn<ItemPhysicalRow, String> column(
      String title, java.util.function.Function<ItemPhysicalRow, String> fn, double width) {
    var c = new TableColumn<ItemPhysicalRow, String>(title);
    c.setCellValueFactory(v -> new ReadOnlyStringWrapper(fn.apply(v.getValue())));
    c.setPrefWidth(width);
    c.setSortable(false);
    return c;
  }

  private static Label copy(String text, String style) {
    var l = new Label(text);
    l.setWrapText(true);
    l.setMaxWidth(Double.MAX_VALUE);
    l.getStyleClass().add(style);
    return l;
  }

  private static TextArea value(String text, String accessible) {
    var a = new TextArea(text);
    a.setWrapText(true);
    a.setEditable(false);
    a.setPrefRowCount(4);
    a.setAccessibleText(accessible);
    a.getStyleClass().add("identity-value");
    return a;
  }

  private static Button button(String text, Runnable action, String id) {
    var b = new Button(text);
    b.setId(id);
    b.setOnAction(e -> action.run());
    b.getStyleClass().add("quiet-button");
    return b;
  }

  private static String bytes(long n) {
    return String.format(Locale.ROOT, "%,d bytes", n);
  }
}
