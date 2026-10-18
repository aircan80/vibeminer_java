package io.vibeminer.desktop;

import io.vibeminer.application.content.*;
import java.util.Objects;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

/**
 * Explicit original-source repair; this view borrows the model and never initiates IO on render.
 */
public final class MetadataRepairPane extends VBox {
  private final MetadataRepairPresentationModel model;
  private final Runnable openOffline, verifyLocal;
  private final String prefix;
  private boolean evidenceExpanded, limitsExpanded;
  private final Timeline expiry;

  public MetadataRepairPane(
      MetadataRepairPresentationModel model, Runnable openOffline, Runnable verifyLocal) {
    super(10);
    this.model = Objects.requireNonNull(model);
    this.openOffline = Objects.requireNonNull(openOffline);
    this.verifyLocal = Objects.requireNonNull(verifyLocal);
    prefix = model.state().kind().name().toLowerCase(java.util.Locale.ROOT) + "-repair-";
    getStyleClass().add("install-cost-card");
    expiry =
        new Timeline(
            new KeyFrame(
                Duration.seconds(1),
                event -> {
                  if (model.state().stage() == MetadataRepairPresentationModel.Stage.PLAN_READY)
                    model.refreshExpiry();
                }));
    expiry.setCycleCount(Timeline.INDEFINITE);
    sceneProperty()
        .addListener(
            (o, old, value) -> {
              if (value == null) expiry.stop();
              else expiry.play();
            });
    refreshState();
  }

  public void refreshState() {
    Node focus = getScene() == null ? null : getScene().getFocusOwner();
    boolean restore = focus != null && focus.getId() != null && focus.getId().startsWith(prefix);
    var s = model.state();
    getChildren().clear();
    getChildren().add(copy("REPAIR ORIGINAL RETAINED METADATA · " + s.kind(), "eyebrow"));
    getChildren().add(copy("Restore the original representation", "notice-heading"));
    Label status = copy(s.message(), "body-copy");
    status.setId(prefix + "status");
    status.setFocusTraversable(true);
    getChildren().add(status);
    if (s.build() != null)
      getChildren()
          .add(
              copy(
                  "Exact snapshot " + s.build().snapshotId() + " · locale " + s.locale(),
                  "secondary"));
    if (s.busy()) {
      ProgressIndicator progress = new ProgressIndicator();
      progress.setMaxSize(24, 24);
      progress.setAccessibleText("Repair operation in progress; percentage unknown");
      getChildren()
          .add(
              new FlowPane(
                  10, 8, progress, action("Cancel repair request", "cancel", model::cancel)));
    } else
      switch (actionFor(s)) {
        case "check" -> {
          getChildren()
              .add(
                  copy(
                      "Availability check: at most 1 HEAD request, zero response-body bytes, 15"
                          + " seconds. This does not download or repair metadata, or guarantee a"
                          + " later download.",
                      "secondary"));
          getChildren().add(action("Check original source availability", "check", model::check));
        }
        case "download" -> {
          var p = s.plan();
          VBox consent = new VBox(8);
          consent.setId(prefix + "consent");
          Label cost = copy(consentText(p), "body-copy");
          cost.setId(prefix + "cost");
          consent
              .getChildren()
              .addAll(
                  cost,
                  action(
                      "Download original and verify",
                      "download",
                      () -> downloadDisplayed(model, p)));
          getChildren().add(consent);
        }
        case "open" ->
            getChildren().add(action("Open retained metadata offline", "open", openOffline));
        case "verify" ->
            getChildren().add(action("Verify retained bytes locally", "verify", verifyLocal));
        default -> {}
      }
    MetadataRepairPlan proof = s.plan();
    if (proof == null && s.result() != null && s.result().completedAudit().isPresent())
      proof = s.result().completedAudit().get().issued();
    VBox evidence = new VBox(8);
    if (proof != null) {
      var original = proof.originalProof();
      evidence
          .getChildren()
          .add(
              proof(
                  "Original acquisition · historical, not a fresh check",
                  "original-proof",
                  "CKey: "
                      + original.declaredContentKey()
                      + "\nActual original EKey: "
                      + original.actualEncodingKey()
                      + "\nSHA-256: "
                      + original.object().digest().value()
                      + "\nEncoded bytes: "
                      + original.object().length()
                      + "\nOriginal URL: "
                      + original.originalAlias().evidence().sourceUrl()
                      + "\nOriginal verification time: "
                      + original.originalAlias().evidence().verifiedAt()));
      evidence
          .getChildren()
          .add(
              proof(
                  "Availability observation · HEAD only",
                  "head-proof",
                  "Pinned URL: "
                      + proof.sourceObservation().resourceUrl()
                      + "\nObserved: "
                      + proof.sourceObservation().observedAt()
                      + "\nConsent expires: "
                      + proof.expiresAt()
                      + "\nOriginal EKey: "
                      + proof.sourceObservation().resourceKey()
                      + "\nSHA-256 to restore: "
                      + original.object().digest().value()));
      var c = proof.cost();
      Label limits =
          copy(
              "Additional physical storage ceiling: "
                  + c.maximumAdditionalPhysicalBytes()
                  + " bytes. Local decoding/verification work ceiling: "
                  + c.maximumDecodedWorkBytes()
                  + " bytes. Payload requests: at most "
                  + c.maximumPayloadRequests()
                  + "; duration: "
                  + c.maximumPayloadDuration()
                  + ". Availability HEAD is separate. Root verification work includes both required"
                  + " passes.",
              "secondary");
      TitledPane limitPane = new TitledPane("Repair limits", limits);
      limitPane.setId(prefix + "limits");
      limitPane.setExpanded(limitsExpanded);
      limitPane.expandedProperty().addListener((o, a, b) -> limitsExpanded = b);
      getChildren().add(limitPane);
    }
    if (s.result() != null) {
      var result = s.result();
      if (result.completedAudit().isPresent()) {
        var audit = result.completedAudit().get();
        var actual = audit.events().getLast().observation().orElseThrow();
        evidence
            .getChildren()
            .add(
                proof(
                    "Completed repair · fresh acquisition evidence",
                    "audit-proof",
                    "Repair attempt: "
                        + audit.attemptId()
                        + "\nActual URL: "
                        + actual.actualTransfer().evidence().sourceUrl()
                        + "\nVerified at: "
                        + actual.actualTransfer().evidence().verifiedAt()
                        + "\nPayload requests: "
                        + actual.payloadRequestCount()
                        + "\nActual encoded bytes: "
                        + actual.payloadResponseBytes()
                        + "\nSHA-256: "
                        + actual.actualTransfer().object().digest().value()
                        + "\nObservation digest: "
                        + actual.observationDigest()));
      } else {
        var original = result.association().actualRepresentation();
        evidence
            .getChildren()
            .add(
                proof(
                    "Already verified locally · no repair payload needed",
                    "audit-proof",
                    "No completed download audit was issued. Original historical receipt remains"
                        + " unchanged.\n"
                        + "Original EKey: "
                        + original.actualEncodingKey()
                        + "\nSHA-256: "
                        + original.object().digest().value()
                        + "\nOriginal URL: "
                        + original.originalAlias().evidence().sourceUrl()
                        + "\nOriginal time: "
                        + original.originalAlias().evidence().verifiedAt()));
      }
    }
    if (!evidence.getChildren().isEmpty()) {
      TitledPane details =
          new TitledPane("Original receipt, availability and repair evidence", evidence);
      details.setId(prefix + "evidence");
      details.setExpanded(evidenceExpanded);
      details.expandedProperty().addListener((o, a, b) -> evidenceExpanded = b);
      getChildren().add(details);
    }
    if (restore)
      Platform.runLater(
          () -> {
            if (getScene() != null) {
              Node next = lookup("#" + prefix + actionFor(model.state()));
              if (next == null) next = lookup("#" + prefix + "status");
              if (next != null) next.requestFocus();
            }
          });
  }

  static void downloadDisplayed(MetadataRepairPresentationModel model, MetadataRepairPlan shown) {
    if (model.state().stage() != MetadataRepairPresentationModel.Stage.PLAN_READY
        || model.state().plan() != shown) return;
    model.refreshExpiry();
    if (model.state().stage() == MetadataRepairPresentationModel.Stage.PLAN_READY
        && model.state().plan() == shown) model.download();
  }

  static String consentText(MetadataRepairPlan p) {
    return "Original A · EKey "
        + p.originalProof().actualEncodingKey()
        + "\nMaximum download: "
        + p.cost().encodedTransferBytes()
        + " bytes · metadata object only\nMaximum additional storage: "
        + p.cost().maximumAdditionalPhysicalBytes()
        + " bytes · local verification work: "
        + p.cost().maximumDecodedWorkBytes()
        + " bytes\nConsent expires: "
        + p.expiresAt();
  }

  static String actionFor(MetadataRepairPresentationModel.State s) {
    if (s.publication() == MetadataAcquisitionFailure.Publication.UNCERTAIN) return "verify";
    return switch (s.stage()) {
      case CHECKING, REPAIRING -> "cancel";
      case PLAN_READY -> "download";
      case REPAIRED, VERIFIED_LOCAL -> "open";
      case CHECK_REQUIRED, CANCELLED, EXPIRED, UNAVAILABLE -> "check";
      case FAILED ->
          s.claimCode() == ContentClaimException.Code.REPLAN_REQUIRED ? "status" : "check";
      default -> "status";
    };
  }

  private Label copy(String text, String style) {
    Label l = new Label(text);
    l.setWrapText(true);
    l.getStyleClass().add(style);
    l.setMaxWidth(Double.MAX_VALUE);
    return l;
  }

  private Button action(String text, String id, Runnable command) {
    Button b = new Button(text);
    b.setId(prefix + id);
    b.setWrapText(true);
    b.setMaxWidth(Double.MAX_VALUE);
    b.getStyleClass().add(id.equals("download") ? "primary" : "quiet-button");
    b.setOnAction(e -> command.run());
    return b;
  }

  private VBox proof(String heading, String id, String text) {
    TextArea area = new TextArea(text);
    area.setId(prefix + id);
    area.setEditable(false);
    area.setWrapText(true);
    area.setPrefRowCount(5);
    area.setAccessibleText(heading + "; selectable and copyable evidence");
    return new VBox(4, copy(heading, "secondary"), area);
  }
}
