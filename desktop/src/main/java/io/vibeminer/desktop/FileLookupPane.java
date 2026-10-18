package io.vibeminer.desktop;

import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.VerifiedSourceAlias;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

/** Exact-ID membership metadata, never a filename catalogue or payload downloader. */
final class FileLookupPane extends VBox {
  private final FileLookupPresentationModel model;
  private FileLookupPresentationModel.State state;
  private final VBox body = new VBox(10);
  private TextField input;
  private Button check, offline;
  private final double scale;
  private final Consumer<String> selectionChanged;
  private String selectedKey;
  private final SelectedFilePresentationModel selectedModel;
  private SelectedFileCandidatesPane candidatesPane;
  private final SelectedFileRetentionPresentationModel retentionModel;
  private MetadataReusePane metadataReusePane;
  private MetadataRepairPane metadataRepairPane;

  FileLookupPane(
      FileLookupPresentationModel model,
      double scale,
      String selectedKey,
      Consumer<String> selectionChanged) {
    this(model, scale, selectedKey, selectionChanged, null);
  }

  FileLookupPane(
      FileLookupPresentationModel model,
      double scale,
      String selectedKey,
      Consumer<String> selectionChanged,
      SelectedFilePresentationModel selectedModel) {
    this(model, scale, selectedKey, selectionChanged, selectedModel, null);
  }

  FileLookupPane(
      FileLookupPresentationModel model,
      double scale,
      String selectedKey,
      Consumer<String> selectionChanged,
      SelectedFilePresentationModel selectedModel,
      SelectedFileRetentionPresentationModel retentionModel) {
    this(model, scale, selectedKey, selectionChanged, selectedModel, retentionModel, null);
  }

  FileLookupPane(
      FileLookupPresentationModel model,
      double scale,
      String selectedKey,
      Consumer<String> selectionChanged,
      SelectedFilePresentationModel selectedModel,
      SelectedFileRetentionPresentationModel retentionModel,
      MetadataReusePresentationModel metadataReuseModel) {
    this(
        model,
        scale,
        selectedKey,
        selectionChanged,
        selectedModel,
        retentionModel,
        metadataReuseModel,
        null);
  }

  FileLookupPane(
      FileLookupPresentationModel model,
      double scale,
      String selectedKey,
      Consumer<String> selectionChanged,
      SelectedFilePresentationModel selectedModel,
      SelectedFileRetentionPresentationModel retentionModel,
      MetadataReusePresentationModel metadataReuseModel,
      MetadataRepairPresentationModel metadataRepairModel) {
    super(10);
    this.retentionModel = retentionModel;
    this.selectedModel = selectedModel;

    this.model = model;
    this.state = model.state();
    this.scale = scale;
    this.selectedKey = selectedKey;
    this.selectionChanged = selectionChanged;
    getChildren()
        .addAll(
            copy("Find a file by ID", "section-heading"),
            copy(
                "Root lookup acquires exact-build membership metadata only. It does not acquire"
                    + " file contents, filenames or item records. Retaining encoded file bytes"
                    + " below requires separate consent.",
                "body-copy"));
    if (state.build() == null) {
      getChildren().add(copy("Choose an exact build first.", "notice-heading"));
      return;
    }
    input = new TextField(state.input());
    input.setId("file-id-input");
    input.setPromptText("Unsigned ID, e.g. 123456");
    input.setAccessibleText("FileDataID, unsigned decimal from zero to 4294967295");
    input.setPrefColumnCount(15);
    input.setPrefWidth(250);
    input.setMaxWidth(310);
    check =
        button(
            "Check lookup requirements", model::check, "quiet-button", "file-check-requirements");
    offline =
        button(
            "Look up retained metadata",
            model::lookupOffline,
            "quiet-button",
            "file-reopen-evidence");
    check.setDisable(state.busy());
    offline.setDisable(state.busy());
    input.textProperty().addListener((o, a, b) -> model.setInput(b));
    input.setOnAction(
        e -> {
          if (!check.isDisabled()) {
            model.check();
          }
          e.consume();
        });
    FlowPane form =
        new FlowPane(10, 8, new VBox(4, copy("FILEDATAID", "eyebrow"), input), check, offline);
    form.setAlignment(Pos.BOTTOM_LEFT);
    getChildren()
        .addAll(
            form,
            copy(
                "Check: up to 8 MiB metadata wire traffic. Whole-map download is separate. Retained"
                    + " lookup: offline only.",
                "secondary"));
    getChildren().add(body);
    refreshState();
    if (metadataReuseModel != null) {
      metadataReusePane = new MetadataReusePane(metadataReuseModel, model::lookupOffline);
      getChildren().add(metadataReusePane);
    }
    if (metadataRepairModel != null) {
      metadataRepairPane =
          new MetadataRepairPane(metadataRepairModel, model::lookupOffline, model::lookupOffline);
      getChildren().add(metadataRepairPane);
    }
  }

  void refreshMetadataRepairState() {
    if (metadataRepairPane != null) metadataRepairPane.refreshState();
  }

  void refreshMetadataReuseState() {
    if (metadataReusePane != null) metadataReusePane.refreshState();
  }

  void refreshRetentionState() {
    if (candidatesPane == null) return;
    Node focused = getScene() == null ? null : getScene().getFocusOwner();
    String id = focused == null ? null : focused.getId();
    candidatesPane.refreshRetentionState();
    if (id != null
        && java.util.Set.of(
                "selected-retention-locate",
                "selected-retention-search",
                "selected-retention-retain",
                "selected-retention-offline",
                "selected-retention-cancel")
            .contains(id)) {
      var state = retentionModel.state();
      String target = retentionFocusTarget(state);
      javafx.application.Platform.runLater(
          () -> {
            if (candidatesPane != null && candidatesPane.getScene() != null) {
              Node control = candidatesPane.lookup("#" + target);
              if (control != null) control.requestFocus();
            }
          });
    }
  }

  static String retentionFocusTarget(SelectedFileRetentionPresentationModel.State state) {
    return state.busy()
        ? "selected-retention-cancel"
        : state.plan() != null
            ? "selected-retention-retain"
            : state.receipt() != null || state.choice() == null
                ? "selected-retention-offline"
                : state.missing() != null
                    ? "selected-retention-search"
                    : "selected-retention-locate";
  }

  void refreshSelectedFileState() {
    if (candidatesPane == null) return;
    Node focused = getScene() == null ? null : getScene().getFocusOwner();
    String id = focused == null ? null : focused.getId();
    candidatesPane.refreshState();
    if (id != null
        && java.util.Set.of("selected-file-check", "selected-file-cancel").contains(id)) {
      String target =
          selectedModel.state().busy()
              ? "selected-file-cancel"
              : selectedModel.state().plan() != null
                  ? "selected-file-candidates"
                  : "selected-file-check";
      javafx.application.Platform.runLater(
          () -> {
            if (candidatesPane != null && candidatesPane.getScene() != null) {
              Node control = candidatesPane.lookup("#" + target);
              if (control != null) control.requestFocus();
            }
          });
    }
  }

  void refreshState() {
    state = model.state();
    if (input != null && !input.getText().equals(state.input())) input.setText(state.input());
    if (check != null) {
      check.setDisable(state.busy());
      offline.setDisable(state.busy());
    }
    body.getChildren().clear();
    Label outcome =
        copy(
            state.message(),
            state.stage() == FileLookupPresentationModel.Stage.FAILED
                    || state.stage() == FileLookupPresentationModel.Stage.INVALID
                ? "notice-heading"
                : "body-copy");
    outcome.setId("file-proof-summary");
    outcome.setFocusTraversable(true);
    outcome.setAccessibleText(state.message());
    body.getChildren().add(outcome);
    if (state.busy()) {
      ProgressIndicator progress = new ProgressIndicator();
      progress.setMaxSize(25, 25);
      progress.setAccessibleText("File lookup operation in progress. Percentage unknown.");
      FlowPane busy =
          new FlowPane(
              12,
              8,
              progress,
              button("Cancel request", model::cancel, "quiet-button", "file-cancel"));
      busy.setAlignment(Pos.CENTER_LEFT);
      body.getChildren().add(busy);
    }
    if (state.plan() != null && !state.busy() && state.result() == null)
      body.getChildren().add(planCard());
    if (state.result() != null) body.getChildren().add(results());
    if (state.plan() != null || state.result() != null || model.historicalRoot().isPresent())
      body.getChildren().add(evidence());
  }

  private VBox planCard() {
    RootPlan plan = state.plan();
    VBox card = new VBox(10);
    card.getStyleClass().add("install-cost-card");
    card.getChildren()
        .addAll(
            copy("WHOLE FILE LOOKUP MAP · METADATA ONLY", "eyebrow"),
            copy(
                "Decoded map size: "
                    + bytes(plan.decodedBytes())
                    + ". Choose one encoding candidate; no candidate is selected automatically.",
                "body-copy"));
    ComboBox<RootCandidate> candidates =
        new ComboBox<>(FXCollections.observableArrayList(plan.candidates()));
    candidates.setId("file-root-candidate");
    candidates.setMaxWidth(Double.MAX_VALUE);
    candidates.setPromptText("Choose a root encoding candidate");
    candidates.setAccessibleText("All returned root encoding candidates; explicit choice required");
    candidates.setConverter(
        new javafx.util.StringConverter<>() {
          public String toString(RootCandidate c) {
            return c == null
                ? ""
                : c.encodingKey()
                    + " · "
                    + (c.encodedBytes() < 1
                        ? "Transfer size unknown"
                        : bytes(c.encodedBytes()) + " maximum transfer");
          }

          public RootCandidate fromString(String text) {
            return null;
          }
        });
    candidates.setValue(state.candidate());
    Label cost = copy("", "body-copy");
    cost.setId("file-root-cost");
    Button download =
        button("Download lookup metadata", model::download, "primary", "file-download-metadata");
    download.setAccessibleText(
        "Download and verify the entire selected root lookup map, then look up this FileDataID"
            + " locally. No game file contents.");
    Runnable update =
        () -> {
          RootCandidate candidate = candidates.getValue();
          boolean allowed =
              candidate != null
                  && candidate.encodedBytes() > 0
                  && candidate.encodedBytes() <= plan.budgets().maximumRootEncodedBytes()
                  && plan.decodedBytes() <= plan.budgets().maximumRootDecodedBytes();
          download.setDisable(!allowed);
          cost.setText(
              candidate == null
                  ? "Select a candidate to review its transfer bound."
                  : candidate.encodedBytes() < 1
                      ? "Transfer size is unknown. This candidate cannot authorize a download."
                      : "Maximum encoded transfer: "
                          + bytes(candidate.encodedBytes())
                          + " · decoded metadata: "
                          + bytes(plan.decodedBytes())
                          + (allowed ? "" : " · exceeds supported root limits"));
        };
    candidates
        .valueProperty()
        .addListener(
            (o, a, b) -> {
              model.selectCandidate(b);
              update.run();
            });
    update.run();
    card.getChildren()
        .addAll(
            candidates,
            cost,
            download,
            copy(
                "Known object size is an upper transfer bound, not measured additional bytes."
                    + " Verified identical bytes may be reused. This operation retains the complete"
                    + " lookup map, not the selected game's file contents.",
                "secondary"));
    if (plan.candidates().isEmpty())
      card.getChildren()
          .add(
              copy(
                  "No encoding candidates were returned. The ID has not been ruled out.",
                  "notice-heading"));
    return card;
  }

  private VBox results() {
    RootLookupResult result = state.result();
    VBox panel = new VBox(10);
    panel.getStyleClass().add("panel");
    panel
        .getChildren()
        .addAll(
            copy("VERIFIED ROOT MEMBERSHIP · ALL RETURNED VARIANTS", "eyebrow"),
            copy(
                result.variants().size()
                    + " variants · "
                    + result.scannedRecords()
                    + " root records scanned · requested ID "
                    + state.fileDataId()
                    + ". Locale flags are observed metadata; workspace locale does not select a"
                    + " variant.",
                "body-copy"));
    if (result.variants().isEmpty()) return panel;
    TableView<RootVariant> table =
        new TableView<>(FXCollections.observableArrayList(result.variants()));
    table.setId("file-variant-table");
    table.setAccessibleText(
        "All observed variants for this FileDataID in the completely verified root. Enter opens"
            + " full raw detail.");
    table.getColumns().add(column("FileDataID", v -> Long.toUnsignedString(v.fileDataId()), 145));
    table.getColumns().add(column("Locale flags", v -> hex(v.localeFlags()), 190));
    table.getColumns().add(column("Content flags", v -> hex(v.contentFlags()), 190));
    table.getColumns().add(column("Content identity", RootVariant::contentKey, 340));
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    double row = Math.max(43, 16 * scale + 16);
    table.setFixedCellSize(row);
    table.setPrefHeight(
        Math.max(42, 13 * scale + 22) + Math.min(result.variants().size(), 5) * row + 2);
    table.setMinHeight(Region.USE_PREF_SIZE);
    table.setMaxHeight(Region.USE_PREF_SIZE);
    VBox detail = new VBox(8);
    detail.setId("file-variant-detail");
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (b != null) {
                selectedKey = key(b);
                selectionChanged.accept(selectedKey);
              }
              showVariant(detail, b);
            });
    if (selectedKey != null)
      result.variants().stream()
          .filter(v -> key(v).equals(selectedKey))
          .findFirst()
          .ifPresent(v -> table.getSelectionModel().select(v));
    showVariant(detail, table.getSelectionModel().getSelectedItem());
    table.setOnKeyPressed(
        e -> {
          if (e.getCode() == KeyCode.ENTER) {
            Node full = detail.lookup("#file-variant-value");
            if (full != null) full.requestFocus();
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
    panel.getChildren().addAll(table, detail);
    return panel;
  }

  private void showVariant(VBox detail, RootVariant variant) {
    detail.getChildren().clear();
    candidatesPane = null;
    if (selectedModel != null) {
      var association = variantAssociation(state.build(), state.result().root().source(), variant);
      selectedModel.select(association, state.locale());
    }

    if (variant == null) {
      detail
          .getChildren()
          .add(
              copy(
                  "Select a variant to inspect all raw fields. Selection does not download"
                      + " content.",
                  "body-copy"));
      return;
    }
    TextArea value =
        value(
            "FileDataID: "
                + variant.fileDataId()
                + "\nFilename: unavailable\nContent identity: "
                + variant.contentKey()
                + "\nLocale flags: "
                + hex(variant.localeFlags())
                + "\nContent flags: "
                + hex(variant.contentFlags())
                + "\nRaw flags: "
                + hex(variant.rawFlagsFirst())
                + " / "
                + hex(variant.rawFlagsSecond())
                + " / "
                + Integer.toUnsignedString(variant.rawFlagsThird())
                + "\nName hash: "
                + variant.nameHash().orElse("Not present")
                + " (a hash is not a filename)",
            "Full selected root variant raw metadata");
    value.setId("file-variant-value");
    value.setPrefRowCount(7);
    detail
        .getChildren()
        .addAll(
            copy("SELECTED VARIANT · MEMBERSHIP ONLY", "eyebrow"),
            value,
            copy(
                "No filename or item record is inferred. File-content acquisition, encryption"
                    + " interpretation and asset previews are outside this view.",
                "secondary"));
    if (selectedModel != null && !state.build().productId().equals("wow")) {
      detail
          .getChildren()
          .add(
              copy(
                  "Representation lookup is not supported for this exact product. Root membership"
                      + " metadata remains available.",
                  "body-copy"));
    }
    if (selectedModel != null && state.build().productId().equals("wow")) {
      candidatesPane = new SelectedFileCandidatesPane(selectedModel, scale, retentionModel);
      detail.getChildren().add(candidatesPane);
    }
  }

  private TitledPane evidence() {
    VBox body = new VBox(10);
    body.getChildren()
        .add(
            field(
                "SELECTED EXACT SOURCE",
                state.build().productId()
                    + " / "
                    + state.build().channel()
                    + " / "
                    + state.build().region()
                    + " / "
                    + state.build().versionLabel()
                    + "\nBuild config: "
                    + state.build().buildConfig()
                    + "\nCDN config: "
                    + state.build().cdnConfig()
                    + "\nLocale view: "
                    + state.locale()));
    if (state.plan() != null) {
      body.getChildren()
          .add(
              field(
                  "PLAN CONTENT / DECODED SIZE",
                  state.plan().contentKey() + " / " + bytes(state.plan().decodedBytes())));
      body.getChildren()
          .add(proof("ACTUAL CONFIGURATION VERIFICATION", state.plan().configuration()));
    }
    if (state.result() == null && model.historicalRoot().isPresent())
      body.getChildren()
          .add(
              proof(
                  "HISTORICAL ROOT RECEIPT · NOT CURRENT VERIFICATION",
                  model.historicalRoot().get()));
    if (state.result() != null) {
      body.getChildren().add(proof("ACTUAL RETAINED ROOT VERIFICATION", state.result().root()));
      body.getChildren()
          .add(
              copy(
                  "Complete supported root structure and protocol verification reached EOF for the"
                      + " requested ID. This verifies lookup metadata, not game file contents.",
                  "body-copy"));
    }
    body.getChildren()
        .add(
            copy(
                "Actual acquisition evidence can refer to an earlier verified source reused by this"
                    + " build. It is not replaced with the current advertised host.",
                "secondary"));
    TitledPane pane = new TitledPane("Source and verification evidence", body);
    pane.setId("file-source-details");
    pane.setExpanded(false);
    return pane;
  }

  private static VBox proof(String title, VerifiedSourceAlias alias) {
    return field(
        title,
        alias.evidence().sourceUrl()
            + "\nVerified: "
            + alias.evidence().verifiedAt()
            + "\nVerifier: "
            + alias.evidence().verifierSemantics()
            + "\nSource namespace/key: "
            + alias.source().namespace()
            + " / "
            + alias.source().immutableSourceKey()
            + "\nPhysical SHA-256: "
            + alias.object().digest().value()
            + "\nRetained encoded length: "
            + bytes(alias.object().length()));
  }

  private static VBox field(String title, String text) {
    return new VBox(4, copy(title, "eyebrow"), value(text, title));
  }

  private static TextArea value(String text, String accessible) {
    TextArea a = new TextArea(text);
    a.setWrapText(true);
    a.setEditable(false);
    a.setPrefRowCount(3);
    a.setAccessibleText(accessible);
    a.getStyleClass().add("identity-value");
    return a;
  }

  static io.vibeminer.domain.selectedfile.FileVariantAssociation variantAssociation(
      io.vibeminer.domain.BuildSnapshot build,
      io.vibeminer.domain.ResolvedSourceIdentity source,
      RootVariant variant) {
    if (variant == null || !build.productId().equals("wow")) return null;
    return new io.vibeminer.domain.selectedfile.FileVariantAssociation(
        build,
        source,
        variant.fileDataId(),
        variant.contentKey(),
        variant.localeFlags(),
        variant.contentFlags(),
        variant.rawFlagsFirst(),
        variant.rawFlagsSecond(),
        variant.rawFlagsThird(),
        variant.nameHash());
  }

  private static String key(RootVariant v) {
    return v.fileDataId()
        + "/"
        + v.contentKey()
        + "/"
        + v.localeFlags()
        + "/"
        + v.contentFlags()
        + "/"
        + v.rawFlagsFirst()
        + "/"
        + v.rawFlagsSecond()
        + "/"
        + v.rawFlagsThird()
        + "/"
        + v.nameHash();
  }

  private static String hex(long value) {
    return "0x" + Long.toUnsignedString(value, 16).toUpperCase(Locale.ROOT);
  }

  private static String bytes(long value) {
    return String.format(Locale.ROOT, "%,d bytes", value);
  }

  private static Label copy(String text, String style) {
    Label l = new Label(text);
    l.getStyleClass().add(style);
    l.setWrapText(true);
    l.setMaxWidth(Double.MAX_VALUE);
    return l;
  }

  private static Button button(String text, Runnable action, String style, String id) {
    Button b = new Button(text);
    b.setOnAction(e -> action.run());
    b.getStyleClass().add(style);
    b.setId(id);
    return b;
  }

  private static TableColumn<RootVariant, String> column(
      String title, Function<RootVariant, String> fn, double width) {
    TableColumn<RootVariant, String> c = new TableColumn<>(title);
    c.setCellValueFactory(v -> new ReadOnlyStringWrapper(fn.apply(v.getValue())));
    c.setPrefWidth(width);
    return c;
  }
}
