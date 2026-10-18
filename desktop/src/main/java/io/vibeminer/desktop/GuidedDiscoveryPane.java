package io.vibeminer.desktop;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Guided build-library view. Identity grouping, observations and opening belong to application
 * ports.
 */
public final class GuidedDiscoveryPane extends VBox {
  public enum Filter {
    ALL,
    LOCAL,
    CURRENTLY_ADVERTISED
  }

  public record ProductOption(String productId, String description) {
    public ProductOption {
      required(productId);
      required(description);
    }

    @Override
    public String toString() {
      return productId + " · " + description;
    }
  }

  public record Detail(String label, String value) {
    public Detail {
      required(label);
      Objects.requireNonNull(value);
    }
  }

  public record BuildRow(
      String groupId,
      String version,
      List<String> regions,
      String status,
      boolean local,
      boolean currentlyAdvertised,
      boolean newToVibeMiner,
      List<Detail> details,
      boolean openEnabled,
      String openReason) {
    public BuildRow {
      required(groupId);
      required(version);
      required(status);
      regions = List.copyOf(regions);
      details = List.copyOf(details);
      Objects.requireNonNull(openReason);
      regions.forEach(GuidedDiscoveryPane::required);
    }
  }

  /** Display-only projection: all facts and capability decisions are supplied by the caller. */
  public record ViewData(
      List<ProductOption> products,
      String selectedProductId,
      List<BuildRow> rows,
      String selectedBuildId,
      Filter filter,
      boolean busy,
      boolean canRefresh,
      boolean moreAvailable,
      String message,
      String error,
      String query,
      String region,
      List<String> availableRegions) {
    public ViewData(
        List<ProductOption> products,
        String selectedProductId,
        List<BuildRow> rows,
        String selectedBuildId,
        Filter filter,
        boolean busy,
        boolean canRefresh,
        boolean moreAvailable,
        String message,
        String error) {
      this(
          products,
          selectedProductId,
          rows,
          selectedBuildId,
          filter,
          busy,
          canRefresh,
          moreAvailable,
          message,
          error,
          "",
          "",
          List.of());
    }

    public ViewData {
      Objects.requireNonNull(query);
      Objects.requireNonNull(region);
      availableRegions = List.copyOf(availableRegions);
      products = List.copyOf(products);
      rows = List.copyOf(rows);
      Objects.requireNonNull(filter);
      Objects.requireNonNull(message);
      Objects.requireNonNull(error);
      var ids = new HashSet<String>();
      for (var row : rows)
        if (!ids.add(row.groupId())) throw new IllegalArgumentException("Duplicate build group");
      if (selectedBuildId != null && !ids.contains(selectedBuildId))
        throw new IllegalArgumentException("Selection is not in this page");
      ids.clear();
      for (var product : products)
        if (!ids.add(product.productId())) throw new IllegalArgumentException("Duplicate product");
      if (selectedProductId != null && !ids.contains(selectedProductId))
        throw new IllegalArgumentException("Selected product is unavailable");
    }
  }

  public record Callbacks(
      Consumer<String> productSelected,
      Consumer<Filter> filterChanged,
      Consumer<String> buildSelected,
      Consumer<String> openBuild,
      Runnable refresh,
      Runnable more,
      Consumer<String> sourceDetails,
      Consumer<String> queryChanged,
      Consumer<String> regionChanged) {
    public Callbacks(
        Consumer<String> productSelected,
        Consumer<Filter> filterChanged,
        Consumer<String> buildSelected,
        Consumer<String> openBuild,
        Runnable refresh,
        Runnable more,
        Consumer<String> sourceDetails) {
      this(
          productSelected,
          filterChanged,
          buildSelected,
          openBuild,
          refresh,
          more,
          sourceDetails,
          ignored -> {},
          ignored -> {});
    }

    public Callbacks {
      Objects.requireNonNull(queryChanged);
      Objects.requireNonNull(regionChanged);
      Objects.requireNonNull(productSelected);
      Objects.requireNonNull(filterChanged);
      Objects.requireNonNull(buildSelected);
      Objects.requireNonNull(openBuild);
      Objects.requireNonNull(refresh);
      Objects.requireNonNull(more);
      Objects.requireNonNull(sourceDetails);
    }
  }

  /** Event binding guards only; async state and application decisions remain outside this view. */
  static final class Interactions {
    private final Supplier<ViewData> data;
    private final Callbacks callbacks;
    private int restoring;

    Interactions(Supplier<ViewData> data, Callbacks callbacks) {
      this.data = Objects.requireNonNull(data);
      this.callbacks = Objects.requireNonNull(callbacks);
    }

    void restore(Runnable action) {
      restoring++;
      try {
        action.run();
      } finally {
        restoring--;
      }
    }

    void productChanged(String id) {
      if (restoring != 0 || id == null) return;
      var current = data.get();
      if (!id.equals(current.selectedProductId())
          && current.products().stream().anyMatch(p -> p.productId().equals(id)))
        callbacks.productSelected().accept(id);
    }

    void filterChanged(Filter filter) {
      if (restoring == 0 && filter != null && filter != data.get().filter())
        callbacks.filterChanged().accept(filter);
    }

    void queryChanged(String query) {
      if (restoring == 0 && query != null && !query.equals(data.get().query()))
        callbacks.queryChanged().accept(query);
    }

    void regionChanged(String region) {
      if (restoring == 0 && region != null && !region.equals(data.get().region()))
        callbacks.regionChanged().accept(region);
    }

    void buildSelected(String id) {
      if (restoring == 0
          && id != null
          && !id.equals(data.get().selectedBuildId())
          && data.get().rows().stream().anyMatch(r -> r.groupId().equals(id)))
        callbacks.buildSelected().accept(id);
    }

    void openSelected() {
      if (restoring != 0 || data.get().busy()) return;
      var selected = selected(data.get());
      if (selected != null && selected.openEnabled())
        callbacks.openBuild().accept(selected.groupId());
    }

    void refresh() {
      if (restoring == 0 && !data.get().busy() && data.get().canRefresh())
        callbacks.refresh().run();
    }

    void more() {
      if (restoring == 0 && !data.get().busy() && data.get().moreAvailable())
        callbacks.more().run();
    }

    void sourceDetails() {
      if (restoring != 0) return;
      var selected = selected(data.get());
      if (selected != null) callbacks.sourceDetails().accept(selected.groupId());
    }
  }

  private ViewData data;
  private long selectionChange;
  private final Interactions events;
  private final ComboBox<ProductOption> products = new ComboBox<>();
  private final Button refresh = button("↻ Refresh builds", "guided-refresh");
  private final FlowPane filters = new FlowPane(10, 8);
  private final TextField search = new TextField();
  private final ComboBox<String> regions = new ComboBox<>();
  private final Label message = copy("", "guided-message");
  private final Label error = copy("", "guided-error");
  private final ListView<BuildRow> rows = new ListView<>();
  private final VBox inspector = new VBox(12);
  private Node openingFeedback;
  private final Button more = button("Next page", "guided-more");
  private final SplitPane results = new SplitPane();
  private final ToggleGroup filterGroup = new ToggleGroup();

  public GuidedDiscoveryPane(ViewData initial, Callbacks callbacks) {
    super(0);
    data = Objects.requireNonNull(initial);
    events = new Interactions(() -> data, callbacks);
    setId("guided-discovery");
    getStyleClass().add("guided-discovery");
    getStylesheets()
        .add(
            Objects.requireNonNull(getClass().getResource("guided-discovery.css"))
                .toExternalForm());
    Label heading = copy("Which builds do you want to explore?", "guided-heading");
    Label introduction =
        copy(
            "Choose a Warcraft product to check its advertised builds. Game content stays on"
                + " demand.",
            "guided-subtitle");
    products.setId("guided-product");
    products.setAccessibleText("Warcraft product: exact product identifier and descriptive name");
    products.setPromptText("Choose a Warcraft product");
    products.setMaxWidth(Double.MAX_VALUE);
    products.setMinWidth(0);
    products
        .valueProperty()
        .addListener(
            (o, before, after) -> events.productChanged(after == null ? null : after.productId()));
    refresh.setOnAction(e -> events.refresh());
    FlowPane productActions = new FlowPane(12, 10, products);
    productActions.getStyleClass().add("guided-product-actions");
    products
        .prefWidthProperty()
        .bind(
            Bindings.createDoubleBinding(
                () ->
                    Math.min(
                        Math.max(0, productActions.getWidth()),
                        850 * heading.getFont().getSize() / 30),
                productActions.widthProperty(),
                heading.fontProperty()));
    Label productLabel = copy("Product", "guided-product-label");
    productLabel.setLabelFor(products);
    VBox productField =
        new VBox(
            9,
            new FlowPane(
                15,
                6,
                productLabel,
                copy("Select the exact CDN product you want to mine.", "guided-subtitle")),
            productActions,
            copy(
                "Exact product codes identify the research source. Descriptive names explain the"
                    + " code.",
                "guided-product-help"));
    productField.getStyleClass().add("guided-product-panel");
    VBox.setMargin(productField, new Insets(20, 0, 20, 0));
    VBox.setMargin(filters, new Insets(18, 0, 18, 0));
    VBox.setMargin(error, new Insets(0, 0, 18, 0));
    VBox.setMargin(message, new Insets(0, 0, 12, 0));
    search.setId("guided-search");
    search.setPromptText("Filter version or build number");
    search.setAccessibleText("Search saved builds by version or build identifier");
    search.setPrefColumnCount(18);
    search.setTextFormatter(
        new TextFormatter<String>(
            change -> validSearch(change.getControlNewText()) ? change : null));
    search.textProperty().addListener((o, before, after) -> events.queryChanged(after));
    regions.setId("guided-region-filter");
    regions.setAccessibleText("Build region");
    regions
        .valueProperty()
        .addListener(
            (o, before, after) ->
                events.regionChanged(after == null || after.equals("All regions") ? "" : after));
    HBox filterSegments = new HBox(0);
    filterSegments.setId("guided-filter-segments");
    filterSegments.setMinWidth(Region.USE_PREF_SIZE);
    filters.getChildren().addAll(search, regions, filterSegments);
    for (Filter filter : Filter.values()) {
      ToggleButton toggle = new ToggleButton(filterLabel(filter));
      toggle.setUserData(filter);
      toggle.setId("guided-filter-" + filter.name().toLowerCase(java.util.Locale.ROOT));
      toggle.setToggleGroup(filterGroup);
      toggle.setOnAction(
          e -> {
            events.filterChanged(filter);
            events.restore(() -> selectFilter(data.filter()));
          });
      filterSegments.getChildren().add(toggle);
    }
    regions.prefWidthProperty().bind(heading.fontProperty().map(font -> font.getSize() * 190 / 30));
    regions.setMinWidth(Region.USE_PREF_SIZE);
    search
        .prefWidthProperty()
        .bind(
            Bindings.createDoubleBinding(
                () -> {
                  double other =
                      Math.ceil(regions.prefWidth(-1)) + Math.ceil(filterSegments.prefWidth(-1));
                  return Math.max(
                      140 * heading.getFont().getSize() / 30,
                      filters.getWidth() - other - 2 * filters.getHgap() - 3);
                },
                filters.widthProperty(),
                heading.fontProperty()));
    rows.setId("guided-builds");
    rows.setAccessibleText(
        "Builds for the selected product. Selecting a row shows its inspector; use Open Build to"
            + " open it.");
    rows.setCellFactory(list -> new BuildCell());
    rows.setPrefHeight(365);
    rows.setMaxHeight(520);
    rows.setMinHeight(180);
    rows.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, before, after) -> {
              long change = ++selectionChange;
              if (events.restoring != 0 || after == null) return;
              // A model callback can synchronously render a loading state. Wait until JavaFX has
              // finished notifying its selected-indices change before that render mutates
              // selection.
              javafx.application.Platform.runLater(
                  () -> {
                    if (selectionChange == change
                        && Objects.equals(rows.getSelectionModel().getSelectedItem(), after))
                      events.buildSelected(after.groupId());
                  });
            });
    inspector.setId("guided-inspector");
    inspector.getStyleClass().add("guided-inspector");
    inspector.setMinWidth(0);
    more.setOnAction(e -> events.more());
    VBox buildsTitle =
        new VBox(
            3,
            copy("Builds", "guided-section-heading"),
            copy(
                "Currently advertised and locally saved observations in one list.",
                "guided-subtitle"));
    HBox.setHgrow(buildsTitle, Priority.ALWAYS);
    HBox buildsHeading = new HBox(15, buildsTitle, refresh);
    buildsHeading.getStyleClass().add("guided-builds-heading");
    VBox catalog =
        new VBox(
            0,
            copy("A SIMPLE STARTING POINT", "guided-eyebrow"),
            heading,
            introduction,
            productField,
            buildsHeading,
            filters,
            message,
            error,
            rows,
            more);
    catalog.getStyleClass().add("guided-main");
    catalog.setMinWidth(0);
    VBox.setVgrow(rows, Priority.ALWAYS);
    results.getItems().addAll(catalog, inspector);
    results.setDividerPositions(.76);
    results.getStyleClass().add("guided-results");
    // SplitPaneSkin asks children for prefHeight(-1), losing wrapped text's width bias.
    // Keep the outer scrollable page tall enough for the inspector at its allocated width.
    results
        .prefHeightProperty()
        .bind(
            Bindings.createDoubleBinding(
                () -> {
                  double inspectorWidth = inspector.getWidth() > 1 ? inspector.getWidth() : 330;
                  double catalogWidth =
                      catalog.getWidth() > 1 ? catalog.getWidth() : Math.max(1, getWidth() - 330);
                  if (results.getOrientation() == Orientation.VERTICAL)
                    return catalog.prefHeight(Math.max(1, results.getWidth()))
                        + inspector.prefHeight(Math.max(1, results.getWidth()))
                        + 20;
                  return Math.max(
                          catalog.prefHeight(catalogWidth), inspector.prefHeight(inspectorWidth))
                      + 2;
                },
                inspector.widthProperty(),
                catalog.widthProperty(),
                inspector.getChildren(),
                heading.fontProperty(),
                results.orientationProperty(),
                results.widthProperty()));
    results.setMinHeight(Region.USE_PREF_SIZE);
    Runnable resizeColumns =
        () -> {
          double scale = heading.getFont().getSize() / 30;
          boolean stacked = getWidth() < 930 * scale;
          results.setOrientation(stacked ? Orientation.VERTICAL : Orientation.HORIZONTAL);
          if (!stacked && getWidth() > 0)
            results.setDividerPositions(Math.max(.4, 1 - 330 * scale / getWidth()));
        };
    widthProperty().addListener((o, before, after) -> resizeColumns.run());
    heading.fontProperty().addListener((o, before, after) -> resizeColumns.run());
    VBox.setVgrow(results, Priority.ALWAYS);
    getChildren().add(results);
    update(initial);
  }

  /** Restoring a projection never causes a product refresh or an Open Build action. */
  public void update(ViewData next) {
    Objects.requireNonNull(next);
    Node focused = getScene() == null ? null : getScene().getFocusOwner();
    String focusedId = focused == null ? null : focused.getId();
    events.restore(
        () -> {
          data = next;
          products.getItems().setAll(next.products());
          products.setValue(
              next.products().stream()
                  .filter(p -> p.productId().equals(next.selectedProductId()))
                  .findFirst()
                  .orElse(null));
          ProductOption selectedProduct = products.getValue();
          String accessibleProduct =
              selectedProduct == null
                  ? "Warcraft product: exact product identifier and descriptive name"
                  : "Warcraft product: " + selectedProduct;
          products.setAccessibleText(accessibleProduct);
          products.setTooltip(
              selectedProduct == null ? null : new Tooltip(selectedProduct.toString()));
          refresh.setDisable(next.busy() || !next.canRefresh());
          selectFilter(next.filter());
          if (!search.getText().equals(next.query())) search.setText(next.query());
          regions.getItems().setAll("All regions");
          regions.getItems().addAll(next.availableRegions());
          if (!next.region().isEmpty() && !regions.getItems().contains(next.region()))
            regions.getItems().add(next.region());
          regions.setValue(next.region().isEmpty() ? "All regions" : next.region());
          setNotice(message, next.busy() || next.rows().isEmpty() ? next.message() : "");
          setNotice(error, next.error());
          if (!rows.getItems().equals(next.rows())) rows.getItems().setAll(next.rows());
          var selection = selected(next);
          if (!Objects.equals(rows.getSelectionModel().getSelectedItem(), selection)) {
            if (selection == null) rows.getSelectionModel().clearSelection();
            else rows.getSelectionModel().select(selection);
          }
          rows.setPlaceholder(
              copy(
                  next.busy() ? "Checking official metadata…" : "No builds match this view.",
                  "guided-empty"));
          more.setVisible(next.moreAvailable());
          more.setManaged(next.moreAvailable());
          more.setDisable(next.busy());
          updateInspector(selection);
        });
    if (focusedId != null) {
      Node restored = lookup("#" + focusedId);
      if (restored != null && !restored.isDisabled()) restored.requestFocus();
    }
  }

  /**
   * Optional application-owned metadata progress, placed beside this exact selection's Open action.
   */
  public void setOpeningFeedback(Node feedback) {
    openingFeedback = Objects.requireNonNull(feedback);
    updateInspector(selected(data));
  }

  private void updateInspector(BuildRow selected) {
    inspector.getChildren().clear();
    inspector.getChildren().add(copy("SELECTED BUILD", "guided-eyebrow"));
    if (selected == null) {
      inspector
          .getChildren()
          .add(
              copy(
                  "Select a build to see its exact identity and available next step.",
                  "guided-subtitle"));
      return;
    }
    inspector
        .getChildren()
        .addAll(
            copy(selected.version(), "guided-version"),
            copy(inspectorSubtitle(data, selected), "guided-subtitle"));
    VBox statusBlock = new VBox(5, copy(selected.status(), "guided-status"));
    statusBlock.getStyleClass().add("guided-status-block");
    selected.details().stream()
        .filter(d -> d.label().equals("READINESS NOTE"))
        .findFirst()
        .ifPresent(
            d -> {
              Label summary =
                  copy(readinessSummary(selected.status(), d.value()), "guided-subtitle");
              summary.setTooltip(new Tooltip(d.value()));
              summary.setAccessibleHelp(d.value());
              statusBlock.getChildren().add(summary);
            });
    inspector.getChildren().add(statusBlock);
    String description =
        data.products().stream()
            .filter(p -> p.productId().equals(data.selectedProductId()))
            .map(ProductOption::description)
            .findFirst()
            .orElse(data.selectedProductId());
    addInspectorFact("Game / edition", description, description, false);
    selected.details().stream()
        .filter(d -> d.label().equals("PUBLICATION CHECK"))
        .findFirst()
        .ifPresent(d -> addInspectorFact("Publication", d.value(), d.value(), false));
    selected.details().stream()
        .filter(d -> d.label().equals("FIRST SEEN BY VIBEMINER"))
        .findFirst()
        .ifPresent(
            d ->
                addInspectorFact(
                    "Observed by VibeMiner", observedTime(d.value()), d.value(), false));
    selected.details().stream()
        .filter(d -> d.label().equals("BUILD CONFIGURATION"))
        .findFirst()
        .ifPresent(d -> addInspectorFact("Build configuration", d.value(), d.value(), true));
    selected.details().stream()
        .filter(d -> d.label().equals("REGION"))
        .findFirst()
        .ifPresent(
            d ->
                inspector
                    .getChildren()
                    .add(
                        copy(
                            "Preferred metadata source: "
                                + d.value().toUpperCase(java.util.Locale.ROOT),
                            "guided-subtitle")));
    Button open = button("Open Build", "guided-open-build");
    open.getStyleClass().add("guided-primary");
    open.setDefaultButton(false);
    open.setDisable(data.busy() || !selected.openEnabled());
    open.setAccessibleText("Open Build " + selected.version() + ". " + selected.openReason());
    open.setOnAction(e -> events.openSelected());
    open.setMaxWidth(Double.MAX_VALUE);
    inspector.getChildren().add(open);
    if (!selected.openReason().isBlank()) {
      Label reason = copy(selected.openReason(), "guided-subtitle");
      reason.setId("guided-open-reason");
      inspector.getChildren().add(reason);
    }
    if (openingFeedback != null) inspector.getChildren().add(openingFeedback);
    Button source = button("Source details", "guided-source-details");
    source.setMaxWidth(Double.MAX_VALUE);
    source.setOnAction(e -> events.sourceDetails());
    inspector.getChildren().add(source);
  }

  private void addInspectorFact(String title, String value, String original, boolean mono) {
    Label label = copy(value, "guided-detail-value");
    if (mono) label.getStyleClass().add("guided-mono");
    label.setTooltip(new Tooltip(original));
    label.setAccessibleHelp(original);
    inspector.getChildren().add(new VBox(3, copy(title, "guided-detail-label"), label));
  }

  static String observedTime(String original) {
    try {
      return java.time.format.DateTimeFormatter.ofPattern(
              "d MMM uuuu, HH:mm 'UTC'", java.util.Locale.ENGLISH)
          .withZone(java.time.ZoneOffset.UTC)
          .format(java.time.Instant.parse(original));
    } catch (java.time.DateTimeException malformedOrOutsideCalendar) {
      return original;
    }
  }

  static boolean validSearch(String value) {
    return value != null
        && value.length() <= 128
        && java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value)
        && value.chars().noneMatch(Character::isISOControl);
  }

  static String readinessSummary(String status, String fullNote) {
    if (status.startsWith("Last checked:"))
      return "Saved assessment; open to verify current files.";
    if (status.equals("Not ready")) return "Open to download and verify build metadata.";
    return fullNote;
  }

  static String inspectorSubtitle(ViewData data, BuildRow row) {
    String product =
        data.products().stream()
            .filter(p -> p.productId().equals(data.selectedProductId()))
            .map(ProductOption::description)
            .findFirst()
            .orElse("");
    String source =
        row.details().stream()
            .filter(d -> d.label().equals("PRODUCT / SOURCE CHANNEL"))
            .map(Detail::value)
            .findFirst()
            .orElse("");
    int divider = source.indexOf('/');
    if (divider >= 0) source = source.substring(divider + 1).strip();
    String region =
        row.details().stream()
            .filter(d -> d.label().equals("REGION"))
            .map(Detail::value)
            .findFirst()
            .orElse(String.join(" · ", row.regions()));
    return java.util.stream.Stream.of(product, source, region)
        .filter(part -> !part.isBlank())
        .collect(java.util.stream.Collectors.joining(" · "));
  }

  private void selectFilter(Filter filter) {
    filterGroup.getToggles().stream()
        .filter(t -> t.getUserData() == filter)
        .findFirst()
        .ifPresent(filterGroup::selectToggle);
  }

  private static BuildRow selected(ViewData value) {
    return value.rows().stream()
        .filter(r -> r.groupId().equals(value.selectedBuildId()))
        .findFirst()
        .orElse(null);
  }

  private static String filterLabel(Filter value) {
    return switch (value) {
      case ALL -> "All";
      case LOCAL -> "Local";
      case CURRENTLY_ADVERTISED -> "Currently advertised";
    };
  }

  private static void setNotice(Label label, String value) {
    label.setText(value);
    label.setVisible(!value.isBlank());
    label.setManaged(!value.isBlank());
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

  private static Button button(String text, String id) {
    Button button = new Button(text);
    button.setId(id);
    return button;
  }

  private static void required(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Display identity is blank");
  }

  private static final class BuildCell extends ListCell<BuildRow> {
    @Override
    protected void updateItem(BuildRow row, boolean empty) {
      super.updateItem(row, empty);
      setText(null);
      setGraphic(null);
      setAccessibleText(null);
      if (empty || row == null) return;
      Label version = copy(row.version(), "guided-row-version");
      VBox identity = new VBox(3, version);
      row.details().stream()
          .filter(d -> d.label().equals("UPSTREAM BUILD ID") && !d.value().isBlank())
          .findFirst()
          .ifPresent(
              d -> identity.getChildren().add(copy("Build " + d.value(), "guided-row-meta")));
      identity.getChildren().add(copy("Build status: " + row.status(), "guided-row-status"));
      row.details().stream()
          .filter(d -> d.label().equals("RESEARCH LOCALE") && !d.value().isBlank())
          .findFirst()
          .ifPresent(
              d ->
                  identity
                      .getChildren()
                      .add(copy("Research locale: " + d.value(), "guided-row-meta")));
      identity.setMinWidth(0);
      FlowPane regions = new FlowPane(3, 3);
      row.regions()
          .forEach(
              region ->
                  regions
                      .getChildren()
                      .add(copy(region.toUpperCase(java.util.Locale.ROOT), "guided-region")));
      VBox memberships = new VBox(5);
      memberships.setMinWidth(0);
      if (row.local()) memberships.getChildren().add(copy("Local", "guided-local"));
      if (row.currentlyAdvertised())
        memberships.getChildren().add(copy("Currently advertised", "guided-current"));
      if (row.newToVibeMiner())
        memberships.getChildren().add(copy("New to VibeMiner", "guided-new"));
      GridPane card = new GridPane();
      card.setHgap(12);
      ColumnConstraints titleColumn = new ColumnConstraints();
      titleColumn.setHgrow(Priority.ALWAYS);
      titleColumn.setMinWidth(0);
      ColumnConstraints regionColumn = new ColumnConstraints();
      regionColumn.setMinWidth(0);
      regionColumn
          .prefWidthProperty()
          .bind(version.fontProperty().map(font -> font.getSize() * 100 / 16));
      ColumnConstraints membershipColumn = new ColumnConstraints();
      membershipColumn.setMinWidth(0);
      membershipColumn
          .prefWidthProperty()
          .bind(version.fontProperty().map(font -> font.getSize() * 110 / 16));
      card.getColumnConstraints().addAll(titleColumn, regionColumn, membershipColumn);
      card.add(identity, 0, 0);
      card.add(regions, 1, 0);
      card.add(memberships, 2, 0);
      card.getStyleClass().add("guided-build-card");
      card.prefWidthProperty().bind(widthProperty().subtract(10));
      setGraphic(card);
      setAccessibleText(
          row.version()
              + "; regions "
              + String.join(", ", row.regions())
              + "; "
              + row.status()
              + (row.local() ? "; Local" : "")
              + (row.currentlyAdvertised() ? "; Currently advertised" : "")
              + (row.newToVibeMiner() ? "; New to VibeMiner" : ""));
    }
  }
}
