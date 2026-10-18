package io.vibeminer.desktop;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
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
      String error) {
    public ViewData {
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
      Consumer<String> sourceDetails) {
    public Callbacks {
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
  private final Interactions events;
  private final ComboBox<ProductOption> products = new ComboBox<>();
  private final Button refresh = button("Refresh", "guided-refresh");
  private final FlowPane filters = new FlowPane(8, 8);
  private final Label message = copy("", "guided-message");
  private final Label error = copy("", "guided-error");
  private final ListView<BuildRow> rows = new ListView<>();
  private final VBox inspector = new VBox(12);
  private final Button more = button("Load more builds", "guided-more");
  private final SplitPane results = new SplitPane();
  private final ToggleGroup filterGroup = new ToggleGroup();

  public GuidedDiscoveryPane(ViewData initial, Callbacks callbacks) {
    super(16);
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
    products.setPrefWidth(460);
    products
        .valueProperty()
        .addListener(
            (o, before, after) -> events.productChanged(after == null ? null : after.productId()));
    refresh.setOnAction(e -> events.refresh());
    FlowPane productActions = new FlowPane(12, 10, products, refresh);
    productActions.getStyleClass().add("guided-product-actions");
    Label productLabel = copy("PRODUCT ID / EDITION", "guided-eyebrow");
    productLabel.setLabelFor(products);
    VBox productField = new VBox(6, productLabel, productActions);
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
      filters.getChildren().add(toggle);
    }
    rows.setId("guided-builds");
    rows.setAccessibleText(
        "Builds for the selected product. Selecting a row shows its inspector; use Open Build to"
            + " open it.");
    rows.setCellFactory(list -> new BuildCell());
    rows.setPrefHeight(365);
    rows.setMinHeight(180);
    rows.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, before, after) -> events.buildSelected(after == null ? null : after.groupId()));
    inspector.setId("guided-inspector");
    inspector.getStyleClass().add("guided-inspector");
    inspector.setMinWidth(0);
    more.setOnAction(e -> events.more());
    VBox catalog = new VBox(12, rows, more);
    catalog.setMinWidth(0);
    VBox.setVgrow(rows, Priority.ALWAYS);
    results.getItems().addAll(catalog, inspector);
    results.setDividerPositions(.63);
    results.getStyleClass().add("guided-results");
    results.setMinHeight(Region.USE_PREF_SIZE);
    widthProperty()
        .addListener(
            (o, before, after) ->
                results.setOrientation(
                    after.doubleValue() < 850 ? Orientation.VERTICAL : Orientation.HORIZONTAL));
    getChildren().addAll(heading, introduction, productField, filters, message, error, results);
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
          setNotice(message, next.message());
          setNotice(error, next.error());
          rows.getItems().setAll(next.rows());
          var selection = selected(next);
          if (selection == null) rows.getSelectionModel().clearSelection();
          else rows.getSelectionModel().select(selection);
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
            copy(selected.version(), "guided-version"), copy(selected.status(), "guided-status"));
    for (Detail detail : selected.details())
      inspector
          .getChildren()
          .add(
              new VBox(
                  3,
                  copy(detail.label(), "guided-eyebrow"),
                  copy(detail.value(), "guided-detail-value")));
    Button open = button("Open Build", "guided-open-build");
    open.getStyleClass().add("guided-primary");
    open.setDefaultButton(false);
    open.setDisable(data.busy() || !selected.openEnabled());
    open.setAccessibleText("Open Build " + selected.version() + ". " + selected.openReason());
    open.setOnAction(e -> events.openSelected());
    inspector.getChildren().add(open);
    if (!selected.openReason().isBlank())
      inspector.getChildren().add(copy(selected.openReason(), "guided-subtitle"));
    Button source = button("Source details", "guided-source-details");
    source.setOnAction(e -> events.sourceDetails());
    inspector.getChildren().add(source);
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
      Region spacer = new Region();
      HBox.setHgrow(spacer, Priority.ALWAYS);
      HBox top = new HBox(10, version, spacer);
      if (row.newToVibeMiner()) top.getChildren().add(copy("New to VibeMiner", "guided-new"));
      FlowPane facts = new FlowPane(8, 6);
      row.regions()
          .forEach(
              region ->
                  facts
                      .getChildren()
                      .add(copy(region.toUpperCase(java.util.Locale.ROOT), "guided-region")));
      if (row.local()) facts.getChildren().add(copy("Local", "guided-local"));
      if (row.currentlyAdvertised())
        facts.getChildren().add(copy("Currently advertised", "guided-current"));
      VBox card = new VBox(9, top, facts, copy(row.status(), "guided-row-status"));
      card.getStyleClass().add("guided-build-card");
      card.prefWidthProperty().bind(widthProperty().subtract(24));
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
