package io.vibeminer.desktop;

import io.vibeminer.domain.item.*;
import java.util.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

/**
 * Explicit offline source-pair preparation, fixed Item-ID queries and attributed name candidates.
 */
public final class ItemNamesPane extends VBox {
  private final ItemNamesPresentationModel model;
  private final double scale;
  private final Runnable inspectSparse;
  private final java.util.function.BiConsumer<PreparedItemNames, NamedItemRow> export;
  private Label status, mode, searchError;
  private TableView<NamedItemRow> table;
  private LiteralEditor searchInput;
  private Button searchButton, nextButton;
  private TextArea executedText;

  public ItemNamesPane(ItemNamesPresentationModel model, double scale, Runnable inspectSparse) {
    this(model, scale, inspectSparse, null);
  }

  public ItemNamesPane(
      ItemNamesPresentationModel model,
      double scale,
      Runnable inspectSparse,
      java.util.function.BiConsumer<PreparedItemNames, NamedItemRow> export) {
    super(12);
    this.export = export;
    this.model = Objects.requireNonNull(model);
    this.scale = scale;
    this.inspectSparse = inspectSparse;
    getStyleClass().add("panel");
    refreshState();
  }

  public void refreshDraftState() {
    var s = model.state();
    if (table != null && s.selected() == null) table.getSelectionModel().clearSelection();
    if (status != null) {
      status.setText(s.message());
      status.setAccessibleText(s.message());
    }
    if (mode != null) mode.setText(s.mode() + " · fixed page limit " + model.pageSize());
    if (searchInput != null) searchInput.restoreFromModel();
    if (searchButton != null) searchButton.setDisable(s.busy() || s.searchBlocked());
    refreshSearchError(s);
    if (nextButton != null) nextButton.setDisable(!canContinue(s));
    if (executedText != null && s.executedQuery() != null)
      executedText.setText(s.executedQuery().text());
  }

  public void refreshState() {
    var s = model.state();
    getChildren().clear();
    table = null;
    searchInput = null;
    searchButton = null;
    searchError = null;
    nextButton = null;
    executedText = null;
    getChildren()
        .addAll(
            copy("DATABASE / NAME CANDIDATES", "eyebrow"),
            copy("Item names in paired physical scope", "section-heading"),
            copy(
                "Item record IDs anchor the join. Display is a community-attributed candidate; a"
                    + " missing name never removes a physical Item row.",
                "body-copy"));
    Label scope =
        copy(
            s.pair() == null
                ? "Names not prepared · Item integers remain independent"
                : coverage(s.pair()),
            "body-copy");
    scope.setId("names-coverage");
    getChildren().add(scope);
    if (s.pair() != null) {
      var schema = s.pair().names().definition().interpretation().schema();
      Label attribution =
          copy(
              "Display candidate · " + schema.provider() + " · " + schema.attribution(),
              "secondary");
      attribution.setId("names-schema-attribution");
      getChildren().add(attribution);
    }
    status = copy(s.message(), s.code() == null ? "body-copy" : "notice-heading");
    status.setId("names-status");
    status.setAccessibleText(s.message());
    getChildren().add(status);
    if (s.pair() == null) {
      getChildren()
          .add(
              copy(
                  "SOURCE PAIR · Item 841626: "
                      + (s.item() == null ? "not prepared" : "prepared physical scope")
                      + " · ItemSparse 1572924: "
                      + (s.sparseSource() == null
                          ? "not verified in this view"
                          : "retained exact descriptor selected"),
                  "secondary"));
      if (s.item() == null)
        getChildren()
            .add(
                copy(
                    "Prepare Item physical rows in the other Database view first. Selecting a"
                        + " Sparse file preserves that independent Item workspace.",
                    "secondary"));
      if (s.sparseSource() == null && inspectSparse != null)
        getChildren()
            .add(button("Inspect ItemSparse file 1572924", inspectSparse, "names-inspect-sparse"));
      if (s.item() != null
          && s.sparseSource() != null
          && s.stage() != ItemNamesPresentationModel.Stage.UNSUPPORTED
          && !s.busy()) {
        getChildren()
            .add(
                copy(
                    "Prepare locally from both retained sources. No download, repair or implicit"
                        + " query. Duration unknown; Item and Sparse proof are checked"
                        + " independently.",
                    "body-copy"));
        Button prepare = button("Prepare name candidates", model::prepare, "names-prepare");
        prepare.getStyleClass().add("primary");
        getChildren().add(prepare);
      }
    }
    if (s.busy()) {
      ProgressIndicator p = new ProgressIndicator();
      p.setMaxSize(25, 25);
      p.setAccessibleText("Local source-pair work in progress; percentage unknown.");
      getChildren()
          .add(
              new FlowPane(
                  10, 8, p, button("Cancel local request", model::cancel, "names-cancel")));
    }
    if (s.pair() != null) {
      getChildren().add(searchForm(s));
      TextField input = new TextField(s.input());
      input.setId("names-record-id");
      input.setPromptText("Positive Item record ID");
      input.setAccessibleText(
          "Exact Item record ID from one to 4294967295; not a FileDataID or name text search");
      input.setMaxWidth(360);
      input.textProperty().addListener((o, a, b) -> model.setInput(b));
      Button lookup = button("Look up ID", model::lookup, "names-lookup");
      lookup.setDisable(s.busy());
      input.setOnAction(
          e -> {
            if (!lookup.isDisabled()) model.lookup();
            e.consume();
          });
      Button browse = button("Browse Item IDs", model::firstPage, "names-browse");
      browse.setDisable(s.busy());
      Button first = button("First Browse page", model::firstPage, "names-first-page");
      first.setDisable(s.busy());
      Button next = button("Next page", model::nextPage, "names-next-page");
      nextButton = next;
      next.setText(
          s.queryMode() == ItemNamesPresentationModel.QueryMode.SEARCH
              ? "Next matches"
              : "Next page");
      next.setDisable(!canContinue(s));
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
      mode = copy(s.mode() + " · fixed page limit " + model.pageSize(), "secondary");
      getChildren().add(mode);
      List<NamedItemRow> rows =
          s.searchPage() != null
              ? s.searchPage().rows()
              : s.page() != null
                  ? s.page().rows()
                  : s.lookup() != null && s.lookup().result().item().row().isPresent()
                      ? List.of(
                          new NamedItemRow(
                              s.lookup().result().item().row().orElseThrow(),
                              s.lookup().result().name().orElseThrow()))
                      : List.of();
      if (!rows.isEmpty()) addRows(rows, s);
      if (s.executedQuery() != null) {
        executedText =
            value(
                s.executedQuery().text(),
                "Exact executed literal query; preserved whitespace, case and normalization");
        executedText.setId("names-executed-query");
        executedText.setPrefRowCount(2);
        String generation =
            s.searchPage() == null
                ? "No current result generation"
                : s.searchPage().generationFingerprint();
        TitledPane queryProof =
            new TitledPane(
                "Executed query and result scope",
                new VBox(
                    8,
                    copy(
                        "Literal and case-sensitive · " + ItemNameLiteralQuery.MODE_REVISION,
                        "secondary"),
                    executedText,
                    copy(
                        "Current result generation: "
                            + generation
                            + ". Query text is not persisted as an artifact or result ledger.",
                        "secondary")));
        queryProof.setId("names-search-proof");
        queryProof.setExpanded(false);
        getChildren().add(queryProof);
      } else if (s.lookup() != null
          && s.lookup().result().item().outcome() == ItemLookupResult.Outcome.COPY_UNINDEXED)
        getChildren()
            .add(
                copy(
                    "Item copy declaration: requested "
                        + s.lookup().result().item().requestedId()
                        + " → source "
                        + s.lookup().result().item().copySourceId().orElseThrow()
                        + ". No physical anchor or borrowed name is inferred.",
                    "body-copy"));
      getChildren()
          .add(
              copy(
                  "Preparation proof timestamps below are historical. A successful current query"
                      + " independently checks the retained source pair; no fresh decoded"
                      + " attestation is invented.",
                  "secondary"));
      getChildren().add(proof(s.pair(), false));
    } else if (s.historicalPair() != null) {
      var history = proof(s.historicalPair(), true);
      getChildren().add(history);
    }
    var l = model.limits();
    TitledPane limits =
        new TitledPane(
            "Local work limits",
            copy(
                "Sparse extraction: encoded up to "
                    + l.maxEncodedBytes()
                    + " bytes; decoded chunk up to "
                    + l.maxChunkDecodedBytes()
                    + " bytes; decoded work up to "
                    + l.maxDecodedWorkBytes()
                    + " bytes; "
                    + l.maxPhysicalRows()
                    + " physical names; record up to "
                    + l.maxRecordBytes()
                    + " bytes. Both selected sources independently replay retained root proof, up"
                    + " to 512 MiB decoded work per pass. These are separate ceilings, not transfer"
                    + " costs. No network work.",
                "secondary"));
    limits.setExpanded(false);
    limits.setId("names-local-limits");
    getChildren().add(limits);
  }

  private boolean canContinue(ItemNamesPresentationModel.State s) {
    return s.queryMode() == ItemNamesPresentationModel.QueryMode.SEARCH
        ? model.canContinueSearch()
        : !s.busy() && s.page() != null && s.page().nextCursor().isPresent();
  }

  private VBox searchForm(ItemNamesPresentationModel.State state) {
    searchInput = new LiteralEditor(model);
    searchInput.setId("names-search-input");
    searchInput.setPromptText("Literal Display candidate text");
    searchInput.setAccessibleText(
        "Literal case-sensitive Display candidate search. One to 128 Unicode scalar values, no"
            + " normalization. Enter searches; Shift Enter inserts line feed. Tab inserts a literal"
            + " tab; Ctrl Tab moves focus. Carriage return and unsupported control characters are"
            + " rejected.");
    searchInput.setPrefRowCount(2);
    searchInput.setMaxWidth(Double.MAX_VALUE);
    searchError = copy("Input rejected. Edit or Clear before searching.", "notice-heading");
    searchError.setId("names-search-error");
    refreshSearchError(state);
    searchButton = button("Search Display candidates", model::search, "names-search");
    searchButton.setDisable(state.busy() || state.searchBlocked());
    searchButton.getStyleClass().add("primary");
    searchInput.addEventFilter(
        javafx.scene.input.KeyEvent.KEY_PRESSED,
        event -> {
          if (event.getCode() == KeyCode.ENTER && !searchInput.composing) {
            if (event.isShiftDown()) searchInput.replaceSelection("\n");
            else if (!searchButton.isDisabled()) model.search();
            event.consume();
          }
        });
    Button clear = button("Clear to Browse", model::clearSearch, "names-search-clear");
    Label cost =
        copy(
            "Bounded local linear scan. A page limits displayed matches, not total proof or scan"
                + " work. Source replay and stored-index integrity checks are additional. No"
                + " network.",
            "secondary");
    cost.setId("names-search-cost");
    TitledPane limits =
        new TitledPane(
            "Literal search limits",
            copy(
                "One to 128 Unicode scalar values and at most 512 strict UTF-8 bytes, preserved"
                    + " verbatim. No case folding, normalization, regex or wildcards. At most"
                    + " 200,000 physical Item anchors and 200,000 physical names, 64 MiB projected"
                    + " UTF-8, scanned in batches of at most 128. Existing per-record and"
                    + " independent source-replay limits also apply. Duration unknown; there is no"
                    + " count-all pass.",
                "secondary"));
    limits.setId("names-search-limits");
    limits.setExpanded(false);
    VBox form =
        new VBox(
            8,
            copy("DISPLAY CANDIDATES · EXACT TEXT, CASE-SENSITIVE", "eyebrow"),
            searchInput,
            searchError,
            copy(
                "Enter searches · Shift+Enter inserts a line break · Ctrl+Tab moves focus · LF and"
                    + " tab are preserved",
                "secondary"),
            cost,
            new FlowPane(10, 8, searchButton, clear),
            limits);
    form.getStyleClass().add("install-cost-card");
    return form;
  }

  private void refreshSearchError(ItemNamesPresentationModel.State state) {
    if (searchError == null) return;
    searchError.setVisible(state.searchBlocked());
    searchError.setManaged(state.searchBlocked());
    searchError.setAccessibleText(
        state.searchBlocked()
            ? "Input rejected. This native editor cannot preserve CR, NUL, other control characters"
                + " or DEL. LF and tab are supported. Edit valid text or Clear before searching."
            : "No rejected input.");
  }

  /** Raw editor ingress is checked before TextInputControl can remove unsupported characters. */
  public static final class LiteralEditor extends TextArea {
    private final ItemNamesPresentationModel model;
    private boolean internal;
    private boolean composing;

    public LiteralEditor(ItemNamesPresentationModel model) {
      this.model = Objects.requireNonNull(model);
      restoreFromModel();
      addEventFilter(
          InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
          event -> composing = !event.getComposed().isEmpty());
      textProperty()
          .addListener(
              (observable, before, after) -> {
                if (!internal && !Objects.equals(after, model.state().searchInput())) {
                  model.rejectSearchIngress();
                  restoreFromModel();
                }
              });
    }

    public void restoreFromModel() {
      String value = model.state().searchInput();
      if (Objects.equals(getText(), value)) return;
      if (!ItemNamesPresentationModel.acceptsEditorText(value)) {
        model.rejectSearchIngress();
        return;
      }
      internal = true;
      try {
        setText(value);
        if (!Objects.equals(getText(), value)) model.rejectSearchIngress();
      } finally {
        internal = false;
      }
    }

    @Override
    public void replaceText(int start, int end, String raw) {
      if (internal) {
        super.replaceText(start, end, raw);
        return;
      }
      String proposed = getText().substring(0, start) + raw + getText().substring(end);
      if (!ItemNamesPresentationModel.acceptsEditorText(raw)
          || !ItemNamesPresentationModel.acceptsEditorText(proposed)) {
        model.rejectSearchIngress();
        return;
      }
      internal = true;
      try {
        super.replaceText(start, end, raw);
      } finally {
        internal = false;
      }
      if (!getText().equals(proposed)) {
        model.rejectSearchIngress();
        restoreFromModel();
        return;
      }
      model.setSearchInput(getText());
    }

    @Override
    public void replaceText(IndexRange range, String raw) {
      replaceText(range.getStart(), range.getEnd(), raw);
    }

    @Override
    public void replaceSelection(String raw) {
      replaceText(getSelection(), raw);
    }

    @Override
    public void paste() {
      String raw = Clipboard.getSystemClipboard().getString();
      if (raw != null) replaceSelection(raw);
    }
  }

  private void addRows(List<NamedItemRow> rows, ItemNamesPresentationModel.State s) {
    table = new TableView<>(FXCollections.observableArrayList(rows));
    table.setId("names-row-table");
    table.setAccessibleText(
        "Physical Item ID left join with Display candidate; missing names retain Item rows. Enter"
            + " opens inspector.");
    table.getColumns().add(column("Item record ID", r -> Long.toString(r.item().recordId()), 220));
    TableColumn<NamedItemRow, String> display =
        column("Display candidate", r -> display(r.name()), 600);
    display.setCellFactory(
        c ->
            new TableCell<>() {
              @Override
              protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(null);
                setGraphic(null);
                if (!empty && getTableRow() != null && getTableRow().getItem() != null) {
                  var r = getTableRow().getItem();
                  var body = new VBox(4, copy(value, "body-copy"));
                  if (scale >= 2)
                    body.getChildren()
                        .add(copy(ItemNamesPresentationModel.nameStatus(r.name()), "secondary"));
                  setGraphic(body);
                }
              }
            });
    table.getColumns().add(display);
    if (scale < 2)
      table
          .getColumns()
          .add(column("Name status", r -> ItemNamesPresentationModel.nameStatus(r.name()), 360));
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    double row = scale >= 2 ? 112 : 56;
    table.setFixedCellSize(row);
    table.setPrefHeight(Math.max(56, 32 * scale) + Math.min(rows.size(), 4) * row + 4);
    table.setMinHeight(Region.USE_PREF_SIZE);
    table.setMaxHeight(Region.USE_PREF_SIZE);
    if (s.selected() != null) table.getSelectionModel().select(s.selected());
    var detail = new VBox(10);
    detail.setId("names-row-detail");
    showRow(detail, s.selected());
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              model.selectRow(b);
              showRow(detail, b);
            });
    table.setOnKeyPressed(
        e -> {
          if (e.getCode() == KeyCode.ENTER) {
            Node value = detail.lookup("#names-row-value");
            if (value != null) value.requestFocus();
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
  }

  private void showRow(VBox detail, NamedItemRow row) {
    detail.getChildren().clear();
    if (row == null) {
      detail
          .getChildren()
          .add(
              copy(
                  "Select a physical Item row to inspect the full candidate, scope and four integer"
                      + " fields.",
                  "body-copy"));
      return;
    }
    var pair = model.state().pair();
    if (export != null && pair != null) {
      Button button = new Button("Export evidence…");
      button.setId("names-export-evidence");
      button.setOnAction(e -> export.accept(pair, row));
      detail.getChildren().add(button);
    }
    var name = row.name();
    StringBuilder text =
        new StringBuilder(
            "Item record ID: "
                + row.item().recordId()
                + "\nName status: "
                + ItemNamesPresentationModel.nameStatus(name)
                + "\nItem physical ordinal: "
                + row.item().physicalOrdinal()
                + " / section "
                + row.item().sectionOrdinal());
    if (name.candidate().isPresent()) {
      var c = name.candidate().get();
      text.append("\nDisplay candidate (unmodified): ")
          .append(c.value())
          .append("\nUTF-8 bytes: ")
          .append(c.utf8Bytes())
          .append("\nSparse physical ordinal: ")
          .append(c.physicalOrdinal())
          .append(" / section ")
          .append(c.sectionOrdinal())
          .append("\nDecoded record offset: ")
          .append(c.decodedOffset())
          .append(" / bytes ")
          .append(c.recordBytes());
    }
    if (name.copySourceId().isPresent())
      text.append("\nUnindexed name-copy source ID: ").append(name.copySourceId().getAsLong());
    for (var v : row.item().values())
      text.append("\n\n")
          .append(v.field())
          .append(" candidate: ")
          .append(v.candidateValue())
          .append(" / ")
          .append(v.candidateBits())
          .append(" bits ")
          .append(v.candidateSign())
          .append("\nRaw bits: 0x")
          .append(Long.toUnsignedString(v.rawBits(), 16))
          .append(" / width ")
          .append(v.rawWidth())
          .append(" / ")
          .append(v.physicalInterpretation());
    var full =
        value(
            text.toString(),
            "Full Item row, unmodified Display candidate and four raw integer candidates");
    full.setId("names-row-value");
    full.setPrefRowCount(8);
    detail
        .getChildren()
        .addAll(copy("Item record " + row.item().recordId(), "section-heading"), full);
    if (name.candidate().isPresent()) {
      var exact =
          value(
              name.candidate().get().value(),
              "Copyable unmodified Display candidate; empty is a valid empty physical candidate");
      exact.setId("names-display-value");
      exact.setPrefRowCount(2);
      detail.getChildren().add(exact);
    }
  }

  private static TitledPane proof(PreparedItemNames p, boolean historical) {
    var item = ItemProjectionPane.proof(p.item());
    item.setId("names-item-proof");
    var n = p.names();
    var e = n.evidence();
    var a = e.association();
    var i = n.definition().interpretation();
    var schema = i.schema();
    var sparse =
        value(
            "Exact Sparse variant: "
                + a.descriptor().association()
                + "\nActual encoding: "
                + a.descriptor().actualEncodingKey()
                + "\nSource role: "
                + n.sourceRole()
                + "\nArtifact: "
                + n.artifactId()
                + "\nBuild config: "
                + a.descriptor().association().build().buildConfig()
                + "\nCDN config: "
                + a.descriptor().association().build().cdnConfig()
                + "\nConfiguration proof: "
                + a.configuration()
                + "\nRoot proof: "
                + a.root()
                + "\nOriginal acquisition URL: "
                + a.representation().sourceReceipt().actualResourceUrl()
                + "\nOriginal observation: "
                + a.representation().sourceReceipt().observedAt()
                + "\n1 · Complete encoded / preparation physical verification: "
                + e.localVerification()
                + "\n2 · Consumed decoded chunks: "
                + e.chunks()
                + "\nAdmitted windows: "
                + e.windows()
                + "\n3 · Declared association: "
                + a.proof()
                + "\n4 · Complete decoded CKey: "
                + e.fullDecoded()
                    .map(Object::toString)
                    .orElse("Not established; admitted windows do not verify the full table")
                + "\nSparse coverage: "
                + n.coverage()
                + "\nProvider: "
                + schema.provider()
                + "\nAttribution: "
                + schema.attribution()
                + " / "
                + schema.license()
                + "\nDefinition: "
                + schema.definitionPath()
                + "\nRevision: "
                + schema.revision()
                + "\nDefinition SHA: "
                + schema.definitionSha256()
                + "\nInterpretation: "
                + i,
            "Independent Sparse source receipt, four proof dimensions, coverage and pinned schema"
                + " attribution");
    sparse.setId("names-sparse-proof-value");
    var pane =
        new TitledPane(
            historical
                ? "Historical pair proof · not current readiness"
                : "Two sources, proof dimensions and candidate attribution",
            new VBox(10, copy(coverage(p), "body-copy"), item, sparse));
    pane.setExpanded(false);
    pane.setId("names-source-proof");
    return pane;
  }

  private static String coverage(PreparedItemNames p) {
    var a = p.item().coverage();
    var b = p.names().coverage();
    return "Item: "
        + a.physicalRows()
        + " physical / "
        + a.unindexedCopyDeclarations()
        + " copies unindexed / "
        + a.excludedSections()
        + " sections excluded · Sparse: "
        + b.physicalRows()
        + " physical / "
        + b.unindexedCopyDeclarations()
        + " copies unindexed / "
        + b.excludedSections()
        + " sections excluded · same exact snapshot / "
        + p.locale();
  }

  private static String display(ItemNameMatch n) {
    return n.candidate()
        .map(c -> c.value().isEmpty() ? "(Empty name candidate)" : c.value())
        .orElse(
            n.outcome() == ItemNameMatch.Outcome.COPY_UNINDEXED
                ? "Name copy unindexed"
                : "No physical name in paired scope");
  }

  private static TableColumn<NamedItemRow, String> column(
      String name, java.util.function.Function<NamedItemRow, String> fn, double width) {
    var c = new TableColumn<NamedItemRow, String>(name);
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
    var v = new TextArea(text);
    v.setWrapText(true);
    v.setEditable(false);
    v.setPrefRowCount(5);
    v.setAccessibleText(accessible);
    v.getStyleClass().add("identity-value");
    return v;
  }

  private static Button button(String text, Runnable action, String id) {
    var b = new Button(text);
    b.setId(id);
    b.setOnAction(e -> action.run());
    b.getStyleClass().add("quiet-button");
    return b;
  }
}
