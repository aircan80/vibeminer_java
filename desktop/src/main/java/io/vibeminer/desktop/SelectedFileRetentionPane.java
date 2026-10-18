package io.vibeminer.desktop;

import io.vibeminer.application.selectedfile.*;
import java.util.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Actual staged encoded-file consent and receipt, separate from decoded interpretation. */
public final class SelectedFileRetentionPane extends VBox {
  private final SelectedFileRetentionPresentationModel model;

  public SelectedFileRetentionPane(SelectedFileRetentionPresentationModel model) {
    super(10);
    this.model = Objects.requireNonNull(model);
    getStyleClass().add("install-cost-card");
    refreshState();
  }

  public void refreshState() {
    var s = model.state();
    getChildren().clear();
    if (model.variant() == null) return;
    getChildren()
        .addAll(
            copy("ENCODED FILE · SELECTED REPRESENTATION", "eyebrow"),
            copy("Retain encoded bytes", "section-heading"),
            copy(
                "Encoded integrity only. Decoded size and declared content identity are unverified;"
                    + " no preview or readable records are provided.",
                "body-copy"));
    var v = model.variant();
    getChildren()
        .add(
            copy(
                v.build().productId()
                    + " / "
                    + v.build().versionLabel()
                    + " · FileDataID "
                    + v.fileDataId()
                    + " · selected encoding "
                    + (s.choice() == null ? "Not selected" : s.choice().candidate().encodingKey()),
                "secondary"));
    Label status =
        copy(
            s.message(),
            s.stage() == SelectedFileRetentionPresentationModel.Stage.FAILED
                ? "notice-heading"
                : "body-copy");
    status.setId("selected-retention-status");
    status.setAccessibleText(s.message());
    getChildren().add(status);
    if (!s.busy() && s.receipt() == null)
      getChildren()
          .add(
              button(
                  "Inspect retained bytes offline", model::reopen, "selected-retention-offline"));
    if (s.busy()) {
      ProgressIndicator p = new ProgressIndicator();
      p.setMaxSize(25, 25);
      p.setAccessibleText("Selected-file operation in progress. Percentage unknown.");
      getChildren()
          .add(
              new FlowPane(
                  10, 8, p, button("Cancel request", model::cancel, "selected-retention-cancel")));
      return;
    }
    if (s.plan() != null) {
      var p = s.plan();
      String actual =
          p.reuse().isPresent()
              ? p.reuse().get().encoded().source().immutableSourceKey()
              : p.choice().candidate().encodingKey();
      getChildren().add(copy("Actual encoding: " + actual, "body-copy"));
      getChildren()
          .add(
              copy(
                  "Planned encoded transfer: "
                      + bytes(p.cost().encodedTransferBytes())
                      + " · additional physical storage: at most "
                      + bytes(p.cost().maximumAdditionalPhysicalBytes())
                      + " (bounded estimate)",
                  "body-copy"));
      String route =
          p.reuse().isPresent()
              ? "Reuse verified retained bytes; no payload transfer planned."
              : p.acquisition().orElseThrow().locator().kind() == FileLocator.Kind.ARCHIVE
                  ? "Selected archive section only; the whole archive is not verified."
                  : "Complete selected direct encoded object.";
      getChildren().add(copy(route, "secondary"));
      Button retain =
          button(
              p.reuse().isPresent() ? "Reuse retained encoding" : "Retain encoded file",
              model::retain,
              "selected-retention-retain");
      retain.getStyleClass().add("primary");
      retain.setAccessibleText(
          retain.getText()
              + "; planned encoded transfer "
              + bytes(p.cost().encodedTransferBytes())
              + ", additional storage at most "
              + bytes(p.cost().maximumAdditionalPhysicalBytes())
              + "; decoded content unverified");
      getChildren().add(retain);
    } else if (s.receipt() != null) {
      var receipt = s.receipt();
      var representation = receipt.representation();
      getChildren()
          .add(
              copy(
                  "Complete encoded bytes verified · "
                      + bytes(representation.encoded().object().length())
                      + " retained · actual encoding "
                      + receipt.descriptor().actualEncodingKey(),
                  "body-copy"));
      getChildren()
          .add(
              button(
                  "Inspect retained bytes offline", model::reopen, "selected-retention-offline"));
      var original = representation.sourceReceipt();
      VBox evidence =
          new VBox(
              8,
              value(
                  "Actual encoding: "
                      + receipt.descriptor().actualEncodingKey()
                      + "\nVerifier: "
                      + representation.encoded().evidence().verifierSemantics()
                      + "\nPhysical SHA-256: "
                      + representation.encoded().object().digest().value()
                      + "\nOriginal encoded verification at: "
                      + representation.encoded().evidence().verifiedAt(),
                  "Encoded verification and local physical identity"),
              value(
                  "Original acquisition URL: "
                      + original.actualResourceUrl()
                      + "\nObserved at: "
                      + original.observedAt()
                      + "\nOriginal acquisition traffic: "
                      + bytes(original.payloadResponseBytes())
                      + " / "
                      + original.payloadRequestCount()
                      + " requests\nResource geometry: "
                      + bytes(original.parentWholeBytes())
                      + "; child offset "
                      + original.childOffset()
                      + "; child encoded bytes "
                      + original.childEncodedBytes()
                      + "\nObservation digest: "
                      + original.observationDigest(),
                  "Original encoded acquisition receipt, not current reused traffic"),
              value(
                  "Current build: "
                      + receipt.descriptor().association().build().versionLabel()
                      + "\nRoot source: "
                      + receipt.root().source()
                      + "\nEncoding association source: "
                      + receipt.proof().encodingBootstrapSource()
                      + "\nAssociation verifier: "
                      + receipt.proof().verifierRevision()
                      + "\nAssociation proof: "
                      + receipt.proof().proofDigest(),
                  "Current exact-build root and encoding association evidence"),
              copy(
                  "Original acquisition observations stay unchanged when bytes are reused. Parent"
                      + " archive geometry is not a download total or a whole-archive integrity"
                      + " claim. Decoded content remains unverified.",
                  "secondary"));
      TitledPane source =
          new TitledPane("Encoded receipt and current association evidence", evidence);
      source.setExpanded(false);
      getChildren().add(source);
    } else if (s.missing() != null) {
      var b = model.archiveBudgets();
      getChildren()
          .add(
              copy(
                  "Archive search only: at most "
                      + b.maximumArchives()
                      + " archives, "
                      + bytes(b.maximumWireBytes())
                      + " metadata traffic, "
                      + b.maximumRequests()
                      + " requests within "
                      + b.maximumDuration().toMinutes()
                      + " minutes. No payload consent.",
                  "secondary"));
      getChildren()
          .add(
              button(
                  "Search archives within these limits",
                  model::searchArchives,
                  "selected-retention-search"));
    } else if (s.stage() == SelectedFileRetentionPresentationModel.Stage.SEARCH_INCOMPLETE
        || s.stage() == SelectedFileRetentionPresentationModel.Stage.SEARCH_COMPLETE) {
      getChildren()
          .add(
              copy(
                  s.checkedArchives()
                      + " / "
                      + s.advertisedArchives()
                      + " advertised archives checked. A new lookup requires new explicit"
                      + " approval.",
                  "secondary"));
    }
    if (s.choice() != null && s.plan() == null && s.receipt() == null && s.missing() == null) {
      var b = s.choice().plan().budgets();
      getChildren()
          .add(
              copy(
                  "Locate metadata only: at most "
                      + bytes(b.lookup().maximumMetadataWireBytes())
                      + ", "
                      + b.lookup().maximumMetadataRequests()
                      + " requests within "
                      + b.maximumMetadataDuration().toMinutes()
                      + " minutes. Unknown encoded size cannot authorize retention until an exact"
                      + " known-size plan is returned.",
                  "secondary"));
      getChildren()
          .add(
              button("Locate selected representation", model::locate, "selected-retention-locate"));
    }
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
    a.setEditable(false);
    a.setWrapText(true);
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
