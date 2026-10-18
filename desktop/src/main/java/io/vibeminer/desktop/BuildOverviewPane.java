package io.vibeminer.desktop;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * C-only exact-build overview. All measurements, readiness and available operations are supplied.
 */
public final class BuildOverviewPane extends VBox {
  public enum Readiness {
    NOT_READY("Not ready"),
    PARTIAL("Partial"),
    FULL("Fully downloaded");
    final String label;

    Readiness(String label) {
      this.label = label;
    }
  }

  public enum MetadataState {
    NOT_READY("Not ready"),
    PREPARING("Preparing"),
    READY("Ready"),
    NEEDS_ATTENTION("Needs attention");
    final String label;

    MetadataState(String label) {
      this.label = label;
    }
  }

  public enum InventoryState {
    UNKNOWN("Not measured"),
    PREPARING("Preparing"),
    SEALED("Sealed"),
    INCOMPLETE("Incomplete"),
    NEEDS_ATTENTION("Needs attention");
    final String label;

    InventoryState(String label) {
      this.label = label;
    }
  }

  public enum DownloadMode {
    ON_DEMAND("On demand"),
    DOWNLOAD_ALL("Download all");
    final String label;

    DownloadMode(String label) {
      this.label = label;
    }
  }

  public record Identity(
      String contextId,
      String gameEdition,
      String productId,
      String channel,
      String region,
      String exactBuild,
      String locale) {
    public Identity {
      for (String value :
          List.of(contextId, gameEdition, productId, channel, region, exactBuild, locale))
        required(value);
    }
  }

  public record Scope(String revision, String description) {
    public Scope {
      required(revision);
      required(description);
    }
  }

  /**
   * One coherent observation; encoded coverage and physical object attribution are different
   * measures.
   */
  public record Metrics(
      String scopeRevision,
      Instant measuredAt,
      OptionalLong distinctFileIds,
      OptionalLong variantMemberships,
      OptionalLong plannedEncodedBytes,
      OptionalLong retainedScopeEncodedBytes,
      boolean denominatorComplete,
      OptionalLong localPhysicalBytes,
      OptionalLong sharedPhysicalBytes,
      OptionalLong metadataDerivedBytes) {
    public Metrics {
      required(scopeRevision);
      Objects.requireNonNull(measuredAt);
      for (var value :
          List.of(
              distinctFileIds,
              variantMemberships,
              plannedEncodedBytes,
              retainedScopeEncodedBytes,
              localPhysicalBytes,
              sharedPhysicalBytes,
              metadataDerivedBytes))
        if (value.isPresent() && value.getAsLong() < 0)
          throw new IllegalArgumentException("Negative measured value");
      if (denominatorComplete && plannedEncodedBytes.isEmpty())
        throw new IllegalArgumentException("Complete denominator requires its measured total");
      if (plannedEncodedBytes.isPresent()
          && retainedScopeEncodedBytes.isPresent()
          && denominatorComplete
          && retainedScopeEncodedBytes.getAsLong() > plannedEncodedBytes.getAsLong())
        throw new IllegalArgumentException("Retained scope exceeds complete denominator");
      if (localPhysicalBytes.isPresent()
          && sharedPhysicalBytes.isPresent()
          && sharedPhysicalBytes.getAsLong() > localPhysicalBytes.getAsLong())
        throw new IllegalArgumentException("Shared subset exceeds local physical measure");
    }
  }

  public record Capabilities(
      boolean modeAvailable,
      boolean busy,
      boolean retryMetadata,
      boolean retryRetention,
      String modeReason) {
    public Capabilities {
      Objects.requireNonNull(modeReason);
    }
  }

  public record ViewData(
      Identity identity,
      Scope scope,
      Readiness readiness,
      MetadataState metadata,
      Metrics metrics,
      DownloadMode mode,
      String activity,
      String jobSummary,
      List<String> problems,
      Capabilities capabilities,
      InventoryState inventory,
      boolean retryPreparationAvailable,
      boolean refreshCoverageAvailable,
      boolean coverageBusy) {
    public ViewData(
        Identity identity,
        Scope scope,
        Readiness readiness,
        MetadataState metadata,
        Metrics metrics,
        DownloadMode mode,
        String activity,
        String jobSummary,
        List<String> problems,
        Capabilities capabilities) {
      this(
          identity,
          scope,
          readiness,
          metadata,
          metrics,
          mode,
          activity,
          jobSummary,
          problems,
          capabilities,
          metrics.denominatorComplete() ? InventoryState.SEALED : InventoryState.UNKNOWN,
          false,
          false,
          false);
    }

    public ViewData {
      Objects.requireNonNull(identity);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(readiness);
      Objects.requireNonNull(metadata);
      Objects.requireNonNull(metrics);
      Objects.requireNonNull(mode);
      Objects.requireNonNull(activity);
      Objects.requireNonNull(jobSummary);
      Objects.requireNonNull(capabilities);
      Objects.requireNonNull(inventory);
      problems = List.copyOf(problems);
      problems.forEach(BuildOverviewPane::required);
      if (!scope.revision().equals(metrics.scopeRevision()))
        throw new IllegalArgumentException("Measurement scope revision differs");
      if (readiness != Readiness.NOT_READY && metadata != MetadataState.READY)
        throw new IllegalArgumentException(
            "Ready metadata required before partial/full content readiness");
      if (readiness == Readiness.FULL
          && (!metrics.denominatorComplete()
              || metrics.retainedScopeEncodedBytes().isEmpty()
              || metrics.plannedEncodedBytes().isEmpty()
              || metrics.retainedScopeEncodedBytes().getAsLong()
                  != metrics.plannedEncodedBytes().getAsLong()))
        throw new IllegalArgumentException(
            "Full display requires complete measured scope coverage");
    }
  }

  public record ModeRequest(String contextId, String scopeRevision, DownloadMode requested) {
    public ModeRequest {
      required(contextId);
      required(scopeRevision);
      Objects.requireNonNull(requested);
    }
  }

  public record Callbacks(
      Consumer<ModeRequest> requestMode,
      Consumer<Identity> sourceDetails,
      Runnable backToBuilds,
      Runnable retryMetadata,
      Runnable retryRetention,
      Runnable retryPreparation,
      Runnable refreshCoverage) {
    public Callbacks(
        Consumer<ModeRequest> requestMode,
        Consumer<Identity> sourceDetails,
        Runnable backToBuilds,
        Runnable retryMetadata,
        Runnable retryRetention) {
      this(
          requestMode,
          sourceDetails,
          backToBuilds,
          retryMetadata,
          retryRetention,
          () -> {},
          () -> {});
    }

    public Callbacks {
      Objects.requireNonNull(requestMode);
      Objects.requireNonNull(sourceDetails);
      Objects.requireNonNull(backToBuilds);
      Objects.requireNonNull(retryMetadata);
      Objects.requireNonNull(retryRetention);
      Objects.requireNonNull(retryPreparation);
      Objects.requireNonNull(refreshCoverage);
    }
  }

  private ViewData data;
  private final Callbacks callbacks;
  private final FlowPane identityFields = new FlowPane(22, 14);
  private final HBox facts = new HBox(16);
  private final VBox storage = new VBox(12), readiness = new VBox(11), attention = new VBox(11);
  private final VBox sidebar = new VBox(16);
  private final SplitPane body = new SplitPane();
  private final Label scope = copy("", "overview-subtitle");

  public BuildOverviewPane(ViewData initial, Callbacks callbacks) {
    super(18);
    this.callbacks = Objects.requireNonNull(callbacks);
    setId("build-overview");
    getStyleClass().add("build-overview");
    getStylesheets()
        .add(Objects.requireNonNull(getClass().getResource("build-overview.css")).toExternalForm());
    identityFields.setId("overview-identity");
    scope.setId("overview-scope");
    facts.setId("overview-facts");
    facts.setMinHeight(Region.USE_PREF_SIZE);
    facts.setAlignment(Pos.TOP_LEFT);
    facts.setFillHeight(true);
    body.setId("overview-body");
    Button source =
        button(
            "Source details",
            "overview-source",
            () -> this.callbacks.sourceDetails().accept(data.identity()));
    Button back = button("Choose build", "overview-back", this.callbacks.backToBuilds());
    FlowPane actions = new FlowPane(8, 8, source, back);
    HBox title = new HBox(12, copy("Build overview", "overview-heading"), spacer(), actions);
    title.setMinWidth(0);
    storage.getStyleClass().add("overview-panel");
    storage.setId("overview-storage");
    readiness.getStyleClass().add("overview-panel");
    readiness.setId("overview-readiness");
    attention.getStyleClass().add("overview-panel");
    attention.setId("overview-attention");
    sidebar.getChildren().addAll(readiness, attention);
    sidebar.setMinWidth(0);
    storage.setMinWidth(0);
    body.getItems().addAll(storage, sidebar);
    body.setDividerPositions(.62);
    body.getStyleClass().add("overview-body");
    body.prefHeightProperty()
        .bind(
            Bindings.createDoubleBinding(
                () -> {
                  double leftWidth =
                      storage.getWidth() > 1 ? storage.getWidth() : Math.max(240, getWidth() * .62);
                  double rightWidth =
                      sidebar.getWidth() > 1 ? sidebar.getWidth() : Math.max(240, getWidth() * .38);
                  if (body.getOrientation() == Orientation.VERTICAL)
                    return storage.prefHeight(Math.max(1, body.getWidth()))
                        + sidebar.prefHeight(Math.max(1, body.getWidth()))
                        + 20;
                  return Math.max(storage.prefHeight(leftWidth), sidebar.prefHeight(rightWidth))
                      + 2;
                },
                storage.widthProperty(),
                sidebar.widthProperty(),
                storage.getChildren(),
                readiness.getChildren(),
                attention.getChildren(),
                scope.fontProperty(),
                body.widthProperty(),
                body.orientationProperty()));
    body.setMinHeight(Region.USE_PREF_SIZE);
    widthProperty()
        .addListener(
            (o, before, after) ->
                body.setOrientation(
                    after.doubleValue() < 900 ? Orientation.VERTICAL : Orientation.HORIZONTAL));
    getChildren().addAll(identityFields, title, scope, facts, body);
    update(initial);
  }

  /**
   * Repaints supplied facts only. This method does not verify bytes, start jobs or change policy.
   */
  public void update(ViewData next) {
    data = Objects.requireNonNull(next);
    Node focused = getScene() == null ? null : getScene().getFocusOwner();
    String focusedId = focused == null ? null : focused.getId();
    identityFields
        .getChildren()
        .setAll(
            field("GAME / EDITION", next.identity().gameEdition(), "overview-game"),
            field("PRODUCT ID", next.identity().productId(), "overview-product"),
            field(
                "CHANNEL / REGION",
                next.identity().channel() + " · " + next.identity().region(),
                "overview-channel-region"),
            field("EXACT BUILD", next.identity().exactBuild(), "overview-build"),
            field("LOCALE", next.identity().locale(), "overview-locale"),
            field("STATUS", next.readiness().label, "overview-status"),
            field("RETAINED", retainedPercentage(next.metrics()), "overview-retained"),
            field(
                "DOWNLOAD MODE",
                next.mode().label + (next.activity().isBlank() ? "" : " · " + next.activity()),
                "overview-mode"));
    scope.setText("Content scope: " + next.scope().description());
    String count =
        next.metrics().distinctFileIds().isPresent()
            ? NumberFormat.getIntegerInstance(Locale.ROOT)
                .format(next.metrics().distinctFileIds().getAsLong())
            : "Not measured";
    String variants =
        next.metrics().variantMemberships().isPresent()
            ? NumberFormat.getIntegerInstance(Locale.ROOT)
                    .format(next.metrics().variantMemberships().getAsLong())
                + " variant memberships"
            : "Variant membership count not measured";
    String total =
        next.metrics().denominatorComplete()
            ? bytes(next.metrics().plannedEncodedBytes())
            : "Not known";
    facts
        .getChildren()
        .setAll(
            fact("Distinct catalog file IDs", count, variants),
            fact(
                "Full selected encoded content",
                total,
                next.metrics().denominatorComplete()
                    ? "Complete authenticated scope denominator"
                    : "Inventory or required sizes remain incomplete"));
    storage
        .getChildren()
        .setAll(
            copy("Local content & shared storage", "overview-panel-heading"),
            measurement(
                "Verified selected encoded content",
                bytes(next.metrics().retainedScopeEncodedBytes())),
            measurement(
                "Unique local object bytes referenced", bytes(next.metrics().localPhysicalBytes())),
            measurement(
                "Of those, shared with other builds", bytes(next.metrics().sharedPhysicalBytes())),
            measurement("Additional encoded content needed", additional(next.metrics())),
            measurement(
                "Metadata and derived indexes", bytes(next.metrics().metadataDerivedBytes())));
    Label measures =
        copy(
            "Shared bytes are a subset, not another copy. Encoded coverage and physical disk"
                + " attribution are separate measurements.",
            "overview-help");
    measures.setTooltip(
        new Tooltip(
            "Retained percentage uses the complete encoded scope, not wire-transfer progress."
                + " Physical bytes are counted once within the provided attribution set."
                + " Metadata/index accounting can overlap other measures; these values are not"
                + " additive disk totals or reclaimable space."));
    storage.getChildren().add(measures);
    if (!next.jobSummary().isBlank()) {
      VBox job =
          new VBox(
              7,
              copy(
                  next.activity().isBlank() ? "Current operation" : next.activity(),
                  "overview-job-title"),
              copy(next.jobSummary(), "overview-subtitle"));
      job.setId("overview-job");
      job.getStyleClass().add("overview-job");
      storage.getChildren().add(job);
    }
    storage.getChildren().add(copy(measurementSummary(next.metrics()), "overview-help"));
    updateReadiness();
    updateAttention();
    if (focusedId != null) {
      Node restored = lookup("#" + focusedId);
      if (restored != null && !restored.isDisabled()) restored.requestFocus();
    }
  }

  static String measurementSummary(Metrics metrics) {
    boolean measured =
        java.util.stream.Stream.of(
                metrics.distinctFileIds(),
                metrics.variantMemberships(),
                metrics.plannedEncodedBytes(),
                metrics.retainedScopeEncodedBytes(),
                metrics.localPhysicalBytes(),
                metrics.sharedPhysicalBytes(),
                metrics.metadataDerivedBytes())
            .anyMatch(OptionalLong::isPresent);
    return measured
        ? "Measured: " + metrics.measuredAt()
        : "No inventory or storage measurements yet.";
  }

  private void updateReadiness() {
    ViewData rendered = data;
    readiness
        .getChildren()
        .setAll(
            copy("Build readiness", "overview-panel-heading"),
            measurement("Required metadata", data.metadata().label),
            measurement("Content inventory", data.inventory().label),
            copy("Download policy", "overview-eyebrow"));
    ToggleGroup group = new ToggleGroup();
    FlowPane modes = new FlowPane(0, 0);
    modes.setId("overview-mode-control");
    for (var mode : DownloadMode.values()) {
      ToggleButton choice = new ToggleButton(mode.label);
      choice.setToggleGroup(group);
      choice.setSelected(mode == data.mode());
      choice.setId(mode == DownloadMode.ON_DEMAND ? "overview-on-demand" : "overview-download-all");
      choice.setAccessibleText(mode.label + " for " + data.scope().description());
      choice.setDisable(!data.capabilities().modeAvailable() || data.capabilities().busy());
      choice.setOnAction(
          e -> {
            if (data != rendered) return;
            requestMode(rendered, mode, callbacks.requestMode());
            group.getToggles().forEach(t -> t.setSelected(t.getUserData() == data.mode()));
          });
      choice.setUserData(mode);
      modes.getChildren().add(choice);
    }
    readiness.getChildren().add(modes);
    if (!data.capabilities().modeReason().isBlank())
      readiness.getChildren().add(copy(data.capabilities().modeReason(), "overview-help"));
    readiness
        .getChildren()
        .add(
            copy(
                "Fully downloaded means the required scoped encoded objects are retained."
                    + " Encryption, decoding and preview support are separate.",
                "overview-help"));
    if (data.retryPreparationAvailable())
      readiness
          .getChildren()
          .add(
              button(
                  "Retry preparation",
                  "overview-retry-preparation",
                  () -> {
                    if (data == rendered) callbacks.retryPreparation().run();
                  }));
    if (data.refreshCoverageAvailable()) {
      Button refresh =
          button(
              "Refresh coverage",
              "overview-refresh-coverage",
              () -> {
                if (data == rendered && !data.coverageBusy()) callbacks.refreshCoverage().run();
              });
      refresh.setDisable(data.coverageBusy());
      readiness.getChildren().add(refresh);
    }
    if (data.capabilities().retryMetadata())
      readiness
          .getChildren()
          .add(
              button(
                  "Retry metadata",
                  "overview-retry-metadata",
                  () -> {
                    if (data == rendered) callbacks.retryMetadata().run();
                  }));
  }

  private void updateAttention() {
    ViewData rendered = data;
    attention.getChildren().setAll(copy("Needs attention", "overview-panel-heading"));
    if (data.problems().isEmpty())
      attention
          .getChildren()
          .add(copy("No reported problems in this observation.", "overview-subtitle"));
    else
      data.problems()
          .forEach(problem -> attention.getChildren().add(copy(problem, "overview-problem")));
    if (data.capabilities().retryRetention())
      attention
          .getChildren()
          .add(
              button(
                  "Retry retention",
                  "overview-retry-retention",
                  () -> {
                    if (data == rendered) callbacks.retryRetention().run();
                  }));
  }

  static void requestMode(ViewData data, DownloadMode requested, Consumer<ModeRequest> callback) {
    Objects.requireNonNull(requested);
    Objects.requireNonNull(callback);
    if (!data.capabilities().modeAvailable()
        || data.capabilities().busy()
        || requested == data.mode()) return;
    callback.accept(
        new ModeRequest(data.identity().contextId(), data.scope().revision(), requested));
  }

  static String retainedPercentage(Metrics metrics) {
    if (!metrics.denominatorComplete()
        || metrics.plannedEncodedBytes().isEmpty()
        || metrics.retainedScopeEncodedBytes().isEmpty()) return "Unknown";
    long total = metrics.plannedEncodedBytes().getAsLong();
    if (total == 0) return "Not applicable";
    return BigDecimal.valueOf(metrics.retainedScopeEncodedBytes().getAsLong())
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        + "%";
  }

  static String bytes(OptionalLong amount) {
    if (amount.isEmpty()) return "Not measured";
    BigDecimal value = BigDecimal.valueOf(amount.getAsLong());
    String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
    int unit = 0;
    while (value.compareTo(BigDecimal.valueOf(1024)) >= 0 && unit < units.length - 1) {
      value = value.divide(BigDecimal.valueOf(1024));
      unit++;
    }
    return value
            .setScale(unit == 0 ? 0 : 2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        + " "
        + units[unit];
  }

  private static String additional(Metrics metrics) {
    if (!metrics.denominatorComplete()
        || metrics.plannedEncodedBytes().isEmpty()
        || metrics.retainedScopeEncodedBytes().isEmpty()) return "Unknown";
    return bytes(
        OptionalLong.of(
            metrics.plannedEncodedBytes().getAsLong()
                - metrics.retainedScopeEncodedBytes().getAsLong()));
  }

  private VBox field(String title, String value, String id) {
    Label label = copy(value, "overview-field-value");
    label.setId(id);
    VBox field = new VBox(4, copy(title, "overview-eyebrow"), label);
    field.setMinWidth(0);
    field
        .maxWidthProperty()
        .bind(
            Bindings.createDoubleBinding(
                () ->
                    Math.max(
                        1,
                        Math.min(
                            Math.max(1, getWidth() - 56), 360 * scope.getFont().getSize() / 13)),
                widthProperty(),
                scope.fontProperty()));
    field.setAccessibleText(title + ": " + value);
    return field;
  }

  private static VBox fact(String title, String value, String description) {
    VBox fact =
        new VBox(
            6,
            copy(title, "overview-eyebrow"),
            copy(value, "overview-fact-value"),
            copy(description, "overview-help"));
    fact.getStyleClass().add("overview-fact");
    fact.setPrefWidth(380);
    fact.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(fact, Priority.ALWAYS);
    fact.setMinWidth(0);
    fact.setMinHeight(Region.USE_PREF_SIZE);
    return fact;
  }

  private static HBox measurement(String title, String value) {
    Label label = copy(title, "overview-subtitle"), amount = copy(value, "overview-measurement");
    HBox.setHgrow(label, Priority.ALWAYS);
    HBox row = new HBox(15, label, amount);
    row.getStyleClass().add("overview-measurement-row");
    row.setMinWidth(0);
    return row;
  }

  private static Label copy(String value, String style) {
    Label label = new Label(value);
    label.setWrapText(true);
    label.setMinWidth(0);
    label.setMinHeight(Region.USE_PREF_SIZE);
    label.setMaxWidth(Double.MAX_VALUE);
    label.getStyleClass().add(style);
    return label;
  }

  private static Region spacer() {
    Region node = new Region();
    HBox.setHgrow(node, Priority.ALWAYS);
    return node;
  }

  private static Button button(String title, String id, Runnable action) {
    Button button = new Button(title);
    button.setId(id);
    button.setOnAction(e -> action.run());
    return button;
  }

  private static void required(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Nonblank display context required");
  }
}
