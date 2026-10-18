package io.vibeminer.desktop.itemexport;

import io.vibeminer.application.itemexport.*;
import io.vibeminer.domain.item.*;
import java.util.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

/** One explicit, local evidence snapshot and path-specific publication consent. */
public final class ItemEvidenceExportDialog extends Dialog<Void> {
  private final ItemEvidenceExportPresentationModel model;
  private final ItemEvidenceDestinationChooser chooser;
  private final Label context = label("", "export-context");
  private final Label status = label("", "export-status");
  private final Label preview = label("", "export-preview");
  private final Label destination = label("No destination chosen", "export-destination");
  private final Label receipt = label("", "export-receipt");
  private final RadioButton item = new RadioButton("Item evidence only");
  private final RadioButton names = new RadioButton("Include prepared name evidence");
  private final Button verify = new Button("Verify evidence");
  private final Button choose = new Button("Choose destination…");
  private final Button save = new Button("Save and allow replacement at this exact path");
  private final CheckBox authorize = new CheckBox("Allow replacement at the exact displayed path");
  private final TextArea proof = new TextArea();
  private final Button close;
  private Object lastSnapshot;
  private String lastDestination;
  private Runnable contextValidation = () -> {};

  public ItemEvidenceExportDialog(
      Window owner,
      ItemEvidenceService service,
      ItemEvidenceDestinationChooser chooser,
      PreparedItemProjection source,
      long recordId,
      Optional<PreparedItemNames> availableNames,
      double scale,
      java.util.function.Consumer<ItemEvidenceSelection> invalidation) {
    this.chooser = Objects.requireNonNull(chooser);
    initOwner(owner);
    setTitle("Export Item evidence");
    setResizable(true);
    model =
        new ItemEvidenceExportPresentationModel(
            service, Platform::runLater, this::refresh, invalidation);
    ToggleGroup group = new ToggleGroup();
    item.setToggleGroup(group);
    names.setToggleGroup(group);
    item.setSelected(true);
    item.setId("export-scope-item");
    names.setId("export-scope-names");
    item.setOnAction(
        e -> {
          model.includeNames(false);
          contextValidation.run();
        });
    names.setOnAction(
        e -> {
          model.includeNames(true);
          contextValidation.run();
        });
    verify.setId("export-verify");
    verify.setOnAction(e -> model.verifyEvidence());
    choose.setId("export-choose-destination");
    choose.setOnAction(
        e -> {
          authorize.setSelected(false);
          chooser
              .choose(getDialogPane().getScene().getWindow(), "item-" + recordId + "-evidence.json")
              .ifPresent(model::chooseDestination);
        });
    authorize.setId("export-authorize-replacement");
    authorize.setWrapText(true);
    authorize.selectedProperty().addListener((o, a, b) -> save.setDisable(!b || !model.canSave()));
    save.setId("export-save");
    save.setWrapText(true);
    save.setDefaultButton(false);
    save.setOnAction(
        e -> {
          if (authorize.isSelected())
            model.saveWithReplacementAuthorization(model.state().destination());
        });
    proof.setEditable(false);
    proof.setWrapText(true);
    proof.setPrefRowCount(8);
    TitledPane details = new TitledPane("Evidence and verification details", proof);
    details.setId("export-proof");
    details.setExpanded(false);
    Button copyPath = new Button("Copy exact path");
    copyPath.setId("export-copy-destination");
    copyPath.setOnAction(
        e -> {
          if (model.state().destination() != null) {
            var c = new javafx.scene.input.ClipboardContent();
            c.putString(model.state().destination());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
          }
        });
    ScrollPane pathScroll = new ScrollPane(destination);
    pathScroll.setId("export-destination-scroll");
    pathScroll.setFitToWidth(true);
    pathScroll.setPrefHeight(54 * scale);
    pathScroll.setMaxHeight(72 * scale);
    VBox consent =
        new VBox(
            10,
            label("Destination · JSON ceiling 2 MiB; no truncation", "export-limit"),
            pathScroll,
            copyPath,
            choose,
            authorize,
            save);
    VBox body =
        new VBox(
            14,
            label("1 · Evidence scope", "export-scope-heading"),
            item,
            names,
            label(
                "One physical Item row. Partial admitted scope; copies are not indexed. Names are"
                    + " candidates, not obtainability evidence.",
                "export-scope"),
            verify,
            status,
            preview,
            details,
            consent,
            receipt);
    ScrollPane scroll = new ScrollPane(body);
    scroll.setFitToWidth(true);
    scroll.setId("export-body-scroll");
    VBox content = new VBox(12, context, scroll);
    VBox.setVgrow(scroll, Priority.ALWAYS);
    getDialogPane().setContent(content);
    getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
    close = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
    close.setId("export-cancel");
    close.setDefaultButton(false);
    close.addEventFilter(
        javafx.event.ActionEvent.ACTION,
        e -> {
          if (model.state().busy()) {
            e.consume();
            model.cancel();
          }
        });
    setOnCloseRequest(
        e -> {
          if (model.state().busy()) {
            e.consume();
            model.cancel();
          }
        });
    setOnHidden(e -> model.close());
    getDialogPane().setStyle("-fx-font-size: " + (14 * scale) + "px; -fx-padding: 20;");
    var css = getClass().getResource("/io/vibeminer/desktop/atlas.css");
    if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());
    getDialogPane().setPrefWidth(Math.min(900, owner.getWidth() - 48));
    getDialogPane().setPrefHeight(Math.max(420, owner.getHeight() - 100));
    model.open(source, recordId, availableNames);
  }

  public ItemEvidenceExportPresentationModel.State state() {
    return model.state();
  }

  public void setContextValidation(Runnable validation) {
    contextValidation = Objects.requireNonNull(validation);
  }

  public void invalidateContext() {
    model.invalidateContext();
  }

  private void refresh(ItemEvidenceExportPresentationModel.State s) {
    var selection = s.selection();
    if (selection == null) return;
    var build = selection.item().evidence().association().descriptor().association().build();
    context.setText(
        build.productId()
            + " / "
            + build.editionLabel()
            + " / "
            + build.channel()
            + " / "
            + build.region()
            + " / "
            + build.versionLabel()
            + "\nItem record ID "
            + selection.recordId()
            + " · "
            + selection.item().locale()
            + "\nExact snapshot "
            + selection.item().snapshotId()
            + " · "
            + (selection.names().isPresent()
                ? "Item + prepared name evidence"
                : "Item evidence only")
            + (s.contextActive() ? "" : " · Previous context; not attached to current inspector"));
    status.setText(s.message());
    status.setAccessibleText(s.message());
    item.setDisable(s.busy() || !s.contextActive());
    names.setDisable(s.busy() || !s.contextActive() || s.availableNames().isEmpty());
    verify.setDisable(
        s.busy()
            || !s.contextActive()
            || s.stage() == ItemEvidenceExportPresentationModel.Stage.COMMIT_UNCERTAIN);
    choose.setDisable(
        s.busy()
            || !s.contextActive()
            || s.snapshot() == null
            || s.stage() == ItemEvidenceExportPresentationModel.Stage.COMMIT_UNCERTAIN);
    if (lastSnapshot != s.snapshot() || !Objects.equals(lastDestination, s.destination()))
      authorize.setSelected(false);
    lastSnapshot = s.snapshot();
    lastDestination = s.destination();
    destination.setText(s.destination() == null ? "No destination chosen" : s.destination());
    authorize.setDisable(!model.canSave());
    save.setDisable(!authorize.isSelected() || !model.canSave());
    preview.setText(
        s.snapshot() == null
            ? "No verified snapshot. Opening this dialog does not read sources or save a file."
            : "Verified immutable local snapshot · one row · encoded JSON size is bounded to 2 MiB"
                + " at save.");
    if (s.snapshot() != null) {
      var p = s.snapshot();
      StringBuilder content =
          new StringBuilder(
              "Physical Item record ID "
                  + p.row().recordId()
                  + " · section "
                  + p.row().sectionOrdinal()
                  + " · ordinal "
                  + p.row().physicalOrdinal());
      for (var value : p.row().values())
        content
            .append("\n")
            .append(value.field())
            .append(" candidate ")
            .append(
                value.candidateSign() == ItemArtifactDefinition.Sign.UNSIGNED
                    ? Long.toUnsignedString(value.candidateValue())
                    : Long.toString(value.candidateValue()))
            .append(" / ")
            .append(value.candidateBits())
            .append(" bits / ")
            .append(value.candidateSign())
            .append(" · raw 0x")
            .append(Long.toUnsignedString(value.rawBits(), 16))
            .append(" / ")
            .append(value.rawWidth())
            .append(" bits / ")
            .append(value.physicalInterpretation());
      p.name()
          .ifPresent(
              n -> {
                content.append("\nName outcome: ").append(n.outcome());
                n.candidate()
                    .ifPresent(
                        c ->
                            content
                                .append("\nDisplay candidate: ")
                                .append(c.value().isEmpty() ? "[empty string]" : c.value()));
                n.copySourceId()
                    .ifPresent(id -> content.append("\nUnindexed copy source ID: ").append(id));
              });
      if (p.name().isEmpty()) content.append("\nNames NOT INCLUDED");
      preview.setText(content.toString());
      proof.setText(
          "Current export verification interval: "
              + p.verificationStartedAt()
              + " → "
              + p.verificationCompletedAt()
              + "\nGeneration: "
              + p.generationFingerprint()
              + "\nItem artifact: "
              + selection.item().artifactId()
              + "\nOriginal Item preparation verification: "
              + selection.item().evidence().localVerification().verifiedAt()
              + "\nCoverage: "
              + selection.item().coverage()
              + "\nItem schema: "
              + selection.item().definition().interpretation().schema()
              + "\nOriginal Item source: "
              + selection.item().evidence().association().representation()
              + "\nNames included: "
              + selection.names().isPresent()
              + selection
                  .names()
                  .map(
                      pair ->
                          "\nSparse artifact: "
                              + pair.names().artifactId()
                              + "\nSparse coverage: "
                              + pair.names().coverage()
                              + "\nSparse definition: "
                              + pair.names().definition().interpretation().schema()
                              + "\nOriginal Sparse preparation verification: "
                              + pair.names().evidence().localVerification().verifiedAt()
                              + "\nOriginal Sparse source: "
                              + pair.names().evidence().association().representation())
                  .orElse("")
              + "\n"
              + "Historical source receipts remain historical; export verification does not upgrade"
              + " decoded proof.");
    } else proof.clear();
    receipt.setText(
        s.receipt() == null
            ? ""
            : "Saved · "
                + s.receipt().encodedUtf8Bytes()
                + " UTF-8 bytes\nSHA-256 "
                + s.receipt().sha256()
                + "\nCommit observed "
                + s.receipt().committedAt());
    close.setText(s.busy() ? "Request cancellation" : "Close");
  }

  private static Label label(String text, String id) {
    Label l = new Label(text);
    l.setId(id);
    l.setWrapText(true);
    l.setMaxWidth(Double.MAX_VALUE);
    return l;
  }
}
