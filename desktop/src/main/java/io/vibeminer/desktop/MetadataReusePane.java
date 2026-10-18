package io.vibeminer.desktop;

import io.vibeminer.application.content.*;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import java.util.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Explicit local alternative to acquiring a second encoding. Primary actions never fetch bytes. */
public final class MetadataReusePane extends VBox {
  private final MetadataReusePresentationModel model;
  private final Runnable openRetained;
  private final String prefix;

  public MetadataReusePane(MetadataReusePresentationModel model, Runnable openRetained) {
    super(10);
    this.model = Objects.requireNonNull(model);
    this.openRetained = Objects.requireNonNull(openRetained);
    prefix =
        model.state().kind() == MetadataStructureAttestation.Kind.ROOT
            ? "root-reuse"
            : "install-reuse";
    getStyleClass().add("install-cost-card");
    refreshState();
  }

  public void refreshState() {
    Node focused = getScene() == null ? null : getScene().getFocusOwner();
    String focusedId = focused == null ? null : focused.getId();
    var s = model.state();
    getChildren().clear();
    getChildren()
        .addAll(
            copy("RETAINED ALTERNATIVE · " + s.kind(), "eyebrow"),
            copy("Use existing local metadata", "section-heading"),
            copy(
                "Check an already retained configuration and canonical representation. No"
                    + " configuration or payload is fetched, replaced or repaired by this branch.",
                "body-copy"));
    if (s.build() == null || s.stage() == MetadataReusePresentationModel.Stage.UNSUPPORTED) {
      getChildren().add(copy(s.message(), "body-copy"));
      return;
    }
    getChildren()
        .add(
            copy(
                s.build().productId()
                    + " / "
                    + s.build().region()
                    + " / "
                    + s.build().versionLabel()
                    + " · locale view "
                    + s.locale(),
                "secondary"));
    Label status = copy(s.message(), s.code() != null ? "notice-heading" : "body-copy");
    status.setId(prefix + "-status");
    status.setAccessibleText(s.message());
    getChildren().add(status);
    if (s.busy()) {
      ProgressIndicator progress = new ProgressIndicator();
      progress.setMaxSize(25, 25);
      progress.setAccessibleText(
          "Local retained metadata operation in progress. Percentage unknown.");
      getChildren()
          .add(
              new FlowPane(
                  10,
                  8,
                  progress,
                  button("Cancel local request", model::cancel, prefix + "-cancel")));
    } else if (s.plan() != null) {
      var p = s.plan();
      getChildren()
          .addAll(
              copy(
                  "Local verification required · additional payload transfer "
                      + bytes(p.additionalPayloadTransferBytes()),
                  "body-copy"),
              facts(p.declaration(), p.actualRepresentation()));
      Button apply = button("Verify and use retained metadata", model::apply, prefix + "-apply");
      apply.getStyleClass().add("primary");
      apply.setAccessibleText(
          "Verify and use retained "
              + s.kind()
              + " metadata for exact build "
              + s.build().versionLabel()
              + ". Zero additional payload transfer; local verification required.");
      Label consent =
          copy(
              "0 additional payload bytes · Local verification required · Not yet ready.\n"
                  + s.kind()
                  + " declaration: "
                  + p.declaration()
                      .preferredEncodingKey()
                      .map(MetadataReusePane::shortKey)
                      .orElse("encoding not declared")
                  + "; actual retained encoding: "
                  + shortKey(p.actualRepresentation().actualEncodingKey())
                  + ".",
              "body-copy");
      consent.setId(prefix + "-consent");
      getChildren().add(new VBox(8, consent, apply));
    } else if (s.association() != null) {
      var a = s.association();
      getChildren()
          .addAll(
              copy(
                  "Locally verified "
                      + s.kind()
                      + " metadata associated · additional payload transfer 0 bytes",
                  "body-copy"),
              facts(a.declaration(), a.actualRepresentation()),
              copy(
                  "Association recorded at "
                      + a.associatedAt()
                      + ". Original acquisition facts are preserved; no new alias or transfer is"
                      + " invented.",
                  "secondary"));
      getChildren()
          .add(
              button(
                  s.kind() == MetadataStructureAttestation.Kind.ROOT
                      ? "Look up this FileDataID offline"
                      : "Open retained installation list",
                  openRetained,
                  prefix + "-open"));
      if (s.kind() == MetadataStructureAttestation.Kind.ROOT)
        getChildren()
            .add(
                copy(
                    "Enter a FileDataID above first. This explicit action queries retained root"
                        + " metadata; it does not download a file or select an encoding.",
                    "secondary"));
    }
    if (!s.busy()) {
      getChildren()
          .add(
              button(
                  s.plan() != null || s.association() != null
                      ? "Check retained alternative again"
                      : "Check retained alternative",
                  model::checkRetained,
                  prefix + "-check"));
      TitledPane limits =
          new TitledPane(
              "Local work limits",
              copy(
                  "Local limits: encoded metadata up to "
                      + bytes(model.budgets().maximumRootEncodedBytes())
                      + ", decoded metadata up to "
                      + bytes(model.budgets().maximumRootDecodedBytes())
                      + ". Missing retained configuration uses the existing Check action"
                      + " separately; it is never fetched automatically here.",
                  "secondary"));
      limits.setExpanded(false);
      getChildren().add(limits);
    }
    if (s.historicalPlan() != null && s.plan() == null && s.association() == null) {
      TitledPane historic =
          new TitledPane(
              "Historical retained proposal · not current verification",
              facts(s.historicalPlan().declaration(), s.historicalPlan().actualRepresentation()));
      historic.setExpanded(false);
      getChildren().add(historic);
    }
    if (focusedId != null
        && Set.of(prefix + "-check", prefix + "-apply", prefix + "-cancel").contains(focusedId)) {
      String target =
          s.busy()
              ? prefix + "-cancel"
              : s.plan() != null
                  ? prefix + "-apply"
                  : s.association() != null ? prefix + "-open" : prefix + "-check";
      javafx.application.Platform.runLater(
          () -> {
            if (getScene() != null) {
              Node n = lookup("#" + target);
              if (n != null) n.requestFocus();
            }
          });
    }
  }

  private static VBox facts(
      MetadataDeclaration d, io.vibeminer.domain.content.MetadataContentProof actual) {
    var box = new VBox(8);
    box.getChildren()
        .addAll(
            copy("Build declaration · " + d.kind(), "eyebrow"),
            copy(
                "Declared encoding: "
                    + d.preferredEncodingKey().orElse("Not declared")
                    + " · declared encoded size: "
                    + (d.declaredEncodedBytes().isPresent()
                        ? bytes(d.declaredEncodedBytes().getAsLong())
                        : "Unknown")
                    + " · declared decoded size: "
                    + (d.declaredDecodedBytes().isPresent()
                        ? bytes(d.declaredDecodedBytes().getAsLong())
                        : "Unknown"),
                "body-copy"),
            copy("Actual retained representation", "eyebrow"),
            copy(
                "Actual encoding: "
                    + actual.actualEncodingKey()
                    + " · "
                    + bytes(actual.object().length())
                    + " retained encoded · "
                    + bytes(actual.decodedAttestation().orElseThrow().actualDecodedBytes())
                    + " decoded",
                "body-copy"));
    var detail =
        new VBox(
            8,
            value(
                "Exact build: "
                    + d.build().productId()
                    + " / "
                    + d.build().channel()
                    + " / "
                    + d.build().region()
                    + " / "
                    + d.build().versionLabel()
                    + "\nBuild config: "
                    + d.build().buildConfig()
                    + "\nCDN config: "
                    + d.build().cdnConfig()
                    + "\nCurrent configuration source: "
                    + d.configuration().evidence().sourceUrl()
                    + "\nConfiguration physical SHA-256: "
                    + d.configuration().object().digest().value()
                    + "\nDeclared content: "
                    + d.slot().contentKey(),
                "Exact configuration declarations separate from actual representation"),
            value(
                "Original actual source: "
                    + actual.originalAlias().evidence().sourceUrl()
                    + "\nOriginal verification at: "
                    + actual.originalAlias().evidence().verifiedAt()
                    + "\nOriginal verifier: "
                    + actual.originalAlias().evidence().verifierSemantics()
                    + "\nActual source identity: "
                    + actual.originalAlias().source()
                    + "\nActual physical SHA-256: "
                    + actual.object().digest().value()
                    + "\nFull decoded attestation: "
                    + actual.decodedAttestation().orElseThrow()
                    + "\nSupported metadata structure: "
                    + actual.structuralAttestation().orElseThrow(),
                "Original retained representation and scoped capabilities; no invented new"
                    + " transfer"),
            copy(
                "Declared and actual encodings remain separate. A root declaration may omit"
                    + " encoding or size facts; they stay unknown. Metadata capability does not"
                    + " establish readable game assets or a complete build archive.",
                "secondary"));
    TitledPane proof =
        new TitledPane("Declaration, original receipt and verification scope", detail);
    proof.setExpanded(false);
    box.getChildren().add(proof);
    return box;
  }

  private static Label copy(String text, String style) {
    var label = new Label(text);
    label.setWrapText(true);
    label.setMaxWidth(Double.MAX_VALUE);
    label.getStyleClass().add(style);
    return label;
  }

  private static TextArea value(String text, String accessible) {
    var v = new TextArea(text);
    v.setEditable(false);
    v.setWrapText(true);
    v.setPrefRowCount(4);
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

  private static String shortKey(String key) {
    return key.substring(0, 8) + "…";
  }

  private static String bytes(long n) {
    return String.format(Locale.ROOT, "%,d bytes", n);
  }
}
