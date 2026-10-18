package io.vibeminer.desktop;

import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.ProductDiscoveryService;
import io.vibeminer.application.WorkspaceService;
import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.buildlibrary.BuildLibraryPage;
import io.vibeminer.application.buildlibrary.BuildLibraryService;
import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.content.MetadataRepairService;
import io.vibeminer.application.content.MetadataReuseService;
import io.vibeminer.application.item.ItemProjectionService;
import io.vibeminer.application.itemexport.ItemEvidenceService;
import io.vibeminer.application.itemnames.ItemNamesService;
import io.vibeminer.application.lookup.FileLookupService;
import io.vibeminer.application.selectedfile.SelectedFileService;
import io.vibeminer.desktop.itemexport.*;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.ProductPointer;
import io.vibeminer.domain.PublishedProduct;
import io.vibeminer.domain.Workspace;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Native Atlas shell. All discovery and data access are delegated to application ports. */
public final class AtlasApplication extends Application {
  private static DiscoveryService configuredService;
  private static boolean initialFixtureMode;
  private static WorkspaceService configuredWorkspaces;
  private static ProductDiscoveryService configuredProducts;
  private static BootstrapService configuredBootstrap;
  private static FileLookupService configuredLookup;
  private static SelectedFileService configuredSelected;
  private static ItemProjectionService configuredItem;
  private static MetadataReuseService configuredMetadataReuse;
  private static MetadataRepairService configuredRepair;
  private static ItemNamesService configuredNames;
  private static ItemEvidenceService configuredEvidence;
  private static ItemEvidenceDestinationChooser evidenceChooser;
  private static BuildLibraryService configuredBuildLibrary;
  private static BuildOpeningService configuredBuildOpening;
  private ItemEvidenceExportDialog evidenceDialog;
  private io.vibeminer.domain.item.PreparedItemProjection exportItem;
  private io.vibeminer.domain.item.PreparedItemNames exportPair;

  /** Optional guided-library binding. The runtime retains ownership of the borrowed service. */
  public static synchronized void configureBuildLibrary(BuildLibraryService library) {
    if (configuredService == null)
      throw new IllegalStateException("Configure the desktop before its build library");
    if (configuredBuildLibrary != null)
      throw new IllegalStateException("Build library has already been configured");
    configuredBuildLibrary = Objects.requireNonNull(library);
  }

  /** Optional exact-build metadata opening; the application runtime owns the service. */
  public static synchronized void configureBuildOpening(BuildOpeningService opening) {
    if (configuredBuildLibrary == null)
      throw new IllegalStateException("Configure the build library before build opening");
    if (configuredBuildOpening != null)
      throw new IllegalStateException("Build opening has already been configured");
    configuredBuildOpening = Objects.requireNonNull(opening);
  }

  public static synchronized void configureItemEvidenceDestinationChooser(
      ItemEvidenceDestinationChooser chooser) {
    evidenceChooser = Objects.requireNonNull(chooser);
  }

  public static synchronized void configure(DiscoveryService service, boolean fixtureMode) {
    configure(service, null, fixtureMode);
  }

  public static synchronized void configure(
      DiscoveryService service, WorkspaceService workspaces, boolean fixtureMode) {
    configure(service, workspaces, null, fixtureMode);
  }

  public static synchronized void configure(
      DiscoveryService service,
      WorkspaceService workspaces,
      ProductDiscoveryService products,
      boolean fixtureMode) {
    configure(service, workspaces, products, null, fixtureMode);
  }

  public static synchronized void configure(
      DiscoveryService service,
      WorkspaceService workspaces,
      ProductDiscoveryService products,
      BootstrapService bootstrap,
      boolean fixtureMode) {
    configure(service, workspaces, products, bootstrap, null, fixtureMode);
  }

  public static synchronized void configure(
      DiscoveryService service,
      WorkspaceService workspaces,
      ProductDiscoveryService products,
      BootstrapService bootstrap,
      FileLookupService lookup,
      boolean fixtureMode) {
    configure(service, workspaces, products, bootstrap, lookup, null, fixtureMode);
  }

  public static synchronized void configure(
      DiscoveryService service,
      WorkspaceService workspaces,
      ProductDiscoveryService products,
      BootstrapService bootstrap,
      FileLookupService lookup,
      SelectedFileService selected,
      boolean fixtureMode) {
    configure(service, workspaces, products, bootstrap, lookup, selected, null, fixtureMode);
  }

  public static synchronized void configure(
      DiscoveryService service,
      WorkspaceService workspaces,
      ProductDiscoveryService products,
      BootstrapService bootstrap,
      FileLookupService lookup,
      SelectedFileService selected,
      ItemProjectionService item,
      boolean fixtureMode) {
    configure(service, workspaces, products, bootstrap, lookup, selected, item, null, fixtureMode);
  }

  public static synchronized void configure(
      DiscoveryService service,
      WorkspaceService workspaces,
      ProductDiscoveryService products,
      BootstrapService bootstrap,
      FileLookupService lookup,
      SelectedFileService selected,
      ItemProjectionService item,
      MetadataReuseService reuse,
      boolean fixtureMode) {
    configure(
        service,
        workspaces,
        products,
        bootstrap,
        lookup,
        selected,
        item,
        reuse,
        null,
        null,
        fixtureMode);
  }

  public static synchronized void configure(
      DiscoveryService service,
      WorkspaceService workspaces,
      ProductDiscoveryService products,
      BootstrapService bootstrap,
      FileLookupService lookup,
      SelectedFileService selected,
      ItemProjectionService item,
      MetadataReuseService reuse,
      MetadataRepairService repair,
      ItemNamesService names,
      boolean fixtureMode) {
    configure(
        service,
        workspaces,
        products,
        bootstrap,
        lookup,
        selected,
        item,
        reuse,
        repair,
        names,
        null,
        fixtureMode);
  }

  public static synchronized void configure(
      DiscoveryService service,
      WorkspaceService workspaces,
      ProductDiscoveryService products,
      BootstrapService bootstrap,
      FileLookupService lookup,
      SelectedFileService selected,
      ItemProjectionService item,
      MetadataReuseService reuse,
      MetadataRepairService repair,
      ItemNamesService names,
      ItemEvidenceService evidence,
      boolean fixtureMode) {
    if (configuredService != null)
      throw new IllegalStateException("Desktop has already been configured");
    configuredService = Objects.requireNonNull(service);
    initialFixtureMode = fixtureMode;
    configuredWorkspaces = workspaces;
    configuredProducts = products;
    configuredBootstrap = bootstrap;
    configuredLookup = lookup;
    configuredSelected = selected;
    configuredItem = item;
    configuredMetadataReuse = reuse;
    configuredRepair = repair;
    configuredNames = names;
    configuredEvidence = evidence;
    configuredBuildLibrary = null;
    configuredBuildOpening = null;
  }

  private Stage stage;
  private BorderPane root;
  private VBox chrome;
  private FlowPane context;
  private Label mode;
  private Label status;
  private StackPane content;
  private DiscoveryPresentationModel model;
  private DiscoveryPresentationModel.State state;
  private GuidedDiscoveryPresentationModel guidedModel;
  private GuidedDiscoveryPane guidedPane;
  private ScrollPane guidedScroll;
  private BuildOpenPresentationModel openingModel;
  private Timeline openingPoll;
  private VBox openingFeedback;
  private Label openingProgress;
  private Button openingCancel;
  private boolean wasOpening;
  private BuildOverviewPane overviewPane;
  private ScrollPane overviewScroll;
  private BuildLibraryPage.Entry openedEntry;

  private ProductPresentationModel productModel;
  private ProductPresentationModel.State productState;
  private String productFilter = "";
  private InstallFilesPane installPane;
  private MetadataReusePresentationModel rootReuseModel, installReuseModel;
  private InstallPresentationModel installModel;
  private InstallPresentationModel.State installState;
  private boolean updatingContext;
  private boolean archiveInstallMode;
  private boolean archiveFindMode;
  private FileLookupPresentationModel lookupModel;
  private FileLookupPresentationModel.State lookupState;
  private FileLookupPane lookupPane;
  private SelectedFilePresentationModel selectedModel;
  private SelectedFilePresentationModel.State selectedState;
  private SelectedFileRetentionPresentationModel retentionModel;
  private SelectedFileRetentionPresentationModel.State retentionState;
  private ItemPresentationModel itemModel;
  private ItemPresentationModel.State itemState;
  private ItemProjectionPane itemPane;
  private ItemNamesPresentationModel namesModel;
  private ItemNamesPresentationModel.State namesState;
  private ItemNamesPane namesPane;
  private boolean namesMode;
  private final RetainedTableSources tableSources = new RetainedTableSources();
  private MetadataRepairPresentationModel rootRepairModel, installRepairModel;
  private io.vibeminer.application.lookup.RootPlan offeredRootPlan;
  private io.vibeminer.application.lookup.RootCandidate offeredRootCandidate;
  private io.vibeminer.application.bootstrap.InstallPlan offeredInstallPlan;
  private final ItemSourceReadiness itemReadiness = new ItemSourceReadiness();
  private String selectedFileVariant;
  private String installQuery = "";
  private String selectedInstallEntry;
  private WorkspacePresentationModel workspaceModel;
  private WorkspacePresentationModel.State workspaceState;
  private Workspace activeWorkspace;
  private String selectedSavedWorkspaceId;
  private FlowPane savedWorkspaceBar;
  private VBox workspaceFeedback;
  private String currentLocale = "enUS";
  private final java.util.Map<String, WorkspaceView> workspaceViews = new java.util.HashMap<>();

  private record WorkspaceView(
      String destination,
      String topic,
      String query,
      String note,
      boolean installMode,
      String installQuery,
      String installSelection,
      boolean findMode,
      String fileInput,
      String fileVariant) {}

  private String destination = "Research";
  private String researchTopic = "All research areas";
  private String searchText = "";
  private String privateNote = "";
  private String selectedProduct;
  private String selectedRegion = "us";
  private String selectedScale = "100%";
  private final ToggleGroup navigation = new ToggleGroup();
  private final String[][] topics = {
    {"01", "Collectibles", "Mounts, pets & appearances", "Which new appearances are present?"},
    {
      "02",
      "Classes & combat",
      "Spells, talents & tuning",
      "What changed in spell and talent values?"
    },
    {
      "03",
      "Crafting",
      "Recipes, reagents & professions",
      "Which recipes and reagent requirements changed?"
    },
    {
      "04",
      "Story & world",
      "Quests, dialogue & places",
      "What world and quest references were added?"
    },
    {
      "05",
      "Art & sound",
      "Models, textures & audio",
      "Which assets and sound IDs can I investigate?"
    },
    {"06", "Developer data", "IDs, tables & relationships", "How do these records connect?"}
  };

  @Override
  public void start(Stage stage) {
    if (configuredService == null)
      throw new IllegalStateException("Bootstrap must configure discovery before launch");
    this.stage = stage;
    if (configuredBuildLibrary != null) destination = "Archive";
    stage.getIcons().setAll(BrandingImages.ICONS);
    root = new BorderPane();
    content = new StackPane();
    content.getStyleClass().add("content-host");
    root.setCenter(content);
    chrome = new VBox();
    chrome.getChildren().addAll(header(), navigationBar());
    context = new FlowPane(16, 8);
    context.getStyleClass().add("context-strip");
    chrome.getChildren().add(context);
    savedWorkspaceBar = new FlowPane(10, 8);
    savedWorkspaceBar.getStyleClass().add("saved-workspace-bar");
    workspaceFeedback = new VBox();
    workspaceFeedback.setManaged(false);
    workspaceFeedback.setVisible(false);
    if (configuredWorkspaces != null)
      chrome.getChildren().addAll(savedWorkspaceBar, workspaceFeedback);
    root.setTop(chrome);
    status = new Label();
    status.getStyleClass().add("status-text");
    HBox footer =
        new HBox(
            12,
            label("●", "connection-dot"),
            status,
            spacer(),
            label("CDN only  /  App-owned storage", "secondary"));
    footer.setAlignment(Pos.CENTER_LEFT);
    footer.getStyleClass().add("footer");
    root.setBottom(footer);
    Scene scene = new Scene(root, 1440, 1000);
    scene
        .getStylesheets()
        .add(Objects.requireNonNull(getClass().getResource("atlas.css")).toExternalForm());
    scene.setOnKeyPressed(
        event -> {
          if (event.isShortcutDown() && event.getCode() == KeyCode.K) {
            if (configuredBuildLibrary == null) showSearch();
            event.consume();
          }
        });
    stage.setTitle("VibeMiner · Atlas research workbench");
    stage.setMinWidth(940);
    stage.setMinHeight(650);
    stage.setScene(scene);
    if (configuredMetadataReuse != null) {
      rootReuseModel =
          new MetadataReusePresentationModel(
              configuredMetadataReuse,
              io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ROOT,
              Platform::runLater,
              this::onMetadataReuseState);
      installReuseModel =
          new MetadataReusePresentationModel(
              configuredMetadataReuse,
              io.vibeminer.domain.content.MetadataStructureAttestation.Kind.INSTALL,
              Platform::runLater,
              this::onMetadataReuseState);
    }
    if (configuredNames != null) {
      namesModel =
          new ItemNamesPresentationModel(configuredNames, Platform::runLater, this::onNamesState);
      namesState = namesModel.state();
    }
    if (configuredRepair != null) {
      rootRepairModel =
          new MetadataRepairPresentationModel(
              configuredRepair,
              io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ROOT,
              Platform::runLater,
              this::onRepairState,
              this::onRepairRevocation,
              java.time.Clock.systemUTC());
      installRepairModel =
          new MetadataRepairPresentationModel(
              configuredRepair,
              io.vibeminer.domain.content.MetadataStructureAttestation.Kind.INSTALL,
              Platform::runLater,
              this::onRepairState,
              this::onRepairRevocation,
              java.time.Clock.systemUTC());
    }
    if (configuredItem != null) {
      itemModel = new ItemPresentationModel(configuredItem, Platform::runLater, this::onItemState);
      itemState = itemModel.state();
    }
    if (configuredSelected != null) {
      retentionModel =
          new SelectedFileRetentionPresentationModel(
              configuredSelected, Platform::runLater, this::onRetentionState);
      retentionState = retentionModel.state();
      selectedModel =
          new SelectedFilePresentationModel(
              configuredSelected, Platform::runLater, this::onSelectedFileState);
      selectedState = selectedModel.state();
    }
    if (configuredLookup != null) {
      lookupModel =
          new FileLookupPresentationModel(
              configuredLookup, Platform::runLater, this::onLookupState);
      lookupState = lookupModel.state();
    }
    if (configuredBootstrap != null) {
      installModel =
          new InstallPresentationModel(
              configuredBootstrap, Platform::runLater, this::onInstallState);
      installState = installModel.state();
    }
    if (configuredProducts != null) {
      productModel =
          new ProductPresentationModel(
              configuredProducts, Platform::runLater, this::onProductState);
      productState = productModel.state();
    }
    if (configuredWorkspaces != null) {
      workspaceModel =
          new WorkspacePresentationModel(
              configuredWorkspaces, Platform::runLater, this::onWorkspaceState);
      workspaceState = workspaceModel.state();
    }
    if (configuredBuildLibrary != null) {
      if (configuredBuildOpening != null) {
        openingModel =
            new BuildOpenPresentationModel(
                configuredBuildOpening, Platform::runLater, this::onOpeningState);
        openingPoll =
            new Timeline(new KeyFrame(Duration.millis(150), e -> openingModel.pollProgress()));
        openingPoll.setCycleCount(Timeline.INDEFINITE);
      }
      guidedModel =
          new GuidedDiscoveryPresentationModel(
              configuredBuildLibrary,
              WarcraftProductLabels.options(),
              Platform::runLater,
              this::onGuidedState,
              entry ->
                  new GuidedDiscoveryPresentationModel.OpenAdmission(
                      openingModel != null && !openingModel.state().busy(),
                      openingModel == null
                          ? "Build metadata preparation is not connected yet."
                          : openingModel.state().busy()
                              ? "Metadata preparation is in progress."
                              : "Prepare verified metadata for this exact build and research"
                                  + " locale."),
              this::openGuidedBuild,
              this::guidedSourceDetails);
      guidedPane =
          new GuidedDiscoveryPane(
              guidedModel.viewData(),
              new GuidedDiscoveryPane.Callbacks(
                  product -> {
                    retireOpening();
                    guidedModel.selectProduct(product);
                  },
                  filter -> {
                    retireOpening();
                    guidedModel.filter(filter);
                  },
                  group -> {
                    retireOpening();
                    guidedModel.select(group);
                  },
                  ignored -> guidedModel.openSelected(),
                  () -> {
                    retireOpening();
                    guidedModel.refresh();
                  },
                  () -> {
                    retireOpening();
                    guidedModel.more();
                  },
                  ignored -> guidedModel.sourceDetails()));
      if (openingModel != null) {
        openingProgress = wrap("");
        openingProgress.setId("guided-opening-progress");
        openingProgress.setMinHeight(Region.USE_PREF_SIZE);
        openingCancel = button("Cancel preparation", () -> openingModel.cancel(), "quiet-button");
        openingCancel.setId("guided-opening-cancel");
        openingFeedback = new VBox(8, openingProgress, openingCancel);
        openingFeedback.setMinHeight(Region.USE_PREF_SIZE);
        openingFeedback.setVisible(false);
        openingFeedback.setManaged(false);
        guidedPane.setOpeningFeedback(openingFeedback);
      }
      Label aliases = wrap(WarcraftProductLabels.attribution());
      aliases.getStyleClass().add("secondary");
      aliases.setId("guided-alias-attribution");
      guidedPane.getChildren().add(aliases);
    }
    model =
        new DiscoveryPresentationModel(
            configuredService, Platform::runLater, this::onState, initialFixtureMode);
    state = model.state();
    if (initialFixtureMode) model.showFixtures();
    else onState(state);
    stage.show();
    if (workspaceModel != null && guidedModel == null) workspaceModel.load();
    if (getParameters().getRaw().contains("--smoke-test")) {
      PauseTransition pause = new PauseTransition(Duration.millis(650));
      pause.setOnFinished(
          event -> {
            System.out.println("VibeMiner UI smoke OK");
            Platform.exit();
          });
      pause.play();
    }
  }

  private static final class BrandingImages {
    private static final List<Image> ICONS =
        java.util.stream.IntStream.of(16, 24, 32, 48, 64, 128, 256, 512, 1024)
            .mapToObj(BrandingImages::load)
            .toList();

    private static Image load(int size) {
      String path = "/io/vibeminer/desktop/icons/vibeminer-" + size + ".png";
      try (var stream = AtlasApplication.class.getResourceAsStream(path)) {
        if (stream == null) throw new IllegalStateException("Missing application icon " + path);
        var image = new Image(stream);
        if (image.isError() || image.getWidth() != size || image.getHeight() != size)
          throw new IllegalStateException("Invalid application icon " + path, image.getException());
        return image;
      } catch (IOException failure) {
        throw new IllegalStateException("Cannot read application icon " + path, failure);
      }
    }
  }

  private Node header() {
    ImageView mark = new ImageView(BrandingImages.ICONS.get(2));
    mark.setId("vibeminer-brand-icon");
    mark.setFitWidth(32);
    mark.setFitHeight(32);
    mark.setPreserveRatio(true);
    mark.setSmooth(true);
    mark.setAccessibleText("VibeMiner Excavate logo");
    VBox brand =
        new VBox(label("VibeMiner", "brand"), label("ATLAS RESEARCH WORKBENCH", "brand-subtitle"));
    mode = label("", "mode-badge");
    Button search = button("Search workspace", this::showSearch, "quiet-button");
    search.setAccessibleText("Search workspace, shortcut Control or Command K");
    Button settings = button("Settings", () -> navigate("Settings"), "quiet-button");
    FlowPane actions = new FlowPane(10, 8, mode);
    if (configuredBuildLibrary == null) actions.getChildren().add(search);
    actions.getChildren().add(settings);
    actions.setAlignment(Pos.CENTER_RIGHT);
    actions.setPrefWidth(460);
    actions.setMinWidth(0);
    HBox header = new HBox(12, mark, brand, spacer(), actions);
    header.setAlignment(Pos.CENTER_LEFT);
    header.getStyleClass().add("header");
    return header;
  }

  private Node navigationBar() {
    FlowPane nav = new FlowPane(4, 4);
    nav.getStyleClass().add("navigation");
    List<String> destinations =
        configuredBuildLibrary == null
            ? List.of(
                "Research", "Archive", "Compare", "Database", "Assets", "Notebook", "Activity")
            : List.of("Archive");
    for (String name : destinations) {
      ToggleButton b =
          new ToggleButton(
              name.equals("Archive") && configuredBuildLibrary != null ? "Discover" : name);
      b.setToggleGroup(navigation);
      b.setUserData(name);
      b.getStyleClass().add("nav-button");
      b.setSelected(name.equals(destination));
      b.setOnAction(e -> navigate(name));
      nav.getChildren().add(b);
    }
    return nav;
  }

  private void onState(DiscoveryPresentationModel.State next) {
    boolean changedContext =
        state == null
            || !Objects.equals(state.selected(), next.selected())
            || rootRepairModel != null && !rootRepairModel.state().locale().equals(currentLocale);
    state = next;
    refreshExportContext();
    if (changedContext) {
      offeredRootPlan = null;
      offeredRootCandidate = null;
      offeredInstallPlan = null;
    }
    updatingContext = true;
    try {
      if (rootRepairModel != null
          && (!Objects.equals(rootRepairModel.state().build(), next.selected())
              || !rootRepairModel.state().locale().equals(currentLocale)))
        rootRepairModel.selectContext(next.selected(), currentLocale);
      if (installRepairModel != null
          && (!Objects.equals(installRepairModel.state().build(), next.selected())
              || !installRepairModel.state().locale().equals(currentLocale)))
        installRepairModel.selectContext(next.selected(), currentLocale);
      if (rootReuseModel != null) rootReuseModel.selectContext(next.selected(), currentLocale);
      if (installReuseModel != null)
        installReuseModel.selectContext(next.selected(), currentLocale);
      if (installModel != null) installModel.selectBuild(next.selected());
      if (lookupModel != null) lookupModel.selectContext(next.selected(), currentLocale);
    } finally {
      updatingContext = false;
    }
    syncItemContext();
    refreshContextStripe();
    updateSavedWorkspaceBar();
    status.setText(
        next.busy() ? "Discovery in progress · interface remains available" : next.message());
    render();
  }

  private void refreshContextStripe() {
    mode.setText(state.fixtureMode() ? "FICTIONAL FIXTURE MODE" : "OFFICIAL METADATA DISCOVERY");
    context.getChildren().clear();
    BuildSnapshot b = state.selected();
    boolean rootVerified =
        b != null
            && lookupState != null
            && lookupState.result() != null
            && lookupState.build() != null
            && lookupState.build().snapshotId().equals(b.snapshotId());
    boolean itemVerified =
        b != null
            && itemState != null
            && itemState.handle() != null
            && itemState.handle().snapshotId().equals(b.snapshotId())
            && itemState.locale().equals(currentLocale);
    var itemFailure = itemReadiness.failureFor(b);
    boolean itemNeedsVerification = itemReadiness.verificationRequiredFor(b);
    boolean namesFailure =
        b != null
            && namesMode
            && namesState != null
            && namesState.build() != null
            && namesState.build().snapshotId().equals(b.snapshotId())
            && namesState.locale().equals(currentLocale)
            && (namesState.code()
                    == io.vibeminer.application.itemnames.ItemNamesException.Code.MISSING_LOCAL
                || namesState.code()
                    == io.vibeminer.application.itemnames.ItemNamesException.Code.CORRUPT);
    boolean namesVerified =
        b != null
            && namesState != null
            && namesState.pair() != null
            && namesState.pair().snapshotId().equals(b.snapshotId())
            && namesState.locale().equals(currentLocale);
    boolean installVerified =
        b != null
            && installState != null
            && installState.inspection() != null
            && installState.build() != null
            && installState.build().snapshotId().equals(b.snapshotId());
    context
        .getChildren()
        .addAll(
            contextItem(
                "GAME / EDITION",
                b == null
                    ? "No workspace selected"
                    : b.editionLabel().replace("World of Warcraft", "WoW")),
            contextItem("PRODUCT ID", b == null ? "Not selected" : b.productId()),
            contextItem(
                "CHANNEL / REGION", b == null ? "Not selected" : b.channel() + " / " + b.region()),
            contextItem("EXACT BUILD", b == null ? "Not selected" : b.versionLabel()),
            contextItem(
                "LOCALE", b == null ? "Not selected" : currentLocale + " · view preference"),
            contextItem(
                "COVERAGE",
                b == null
                    ? "No data loaded"
                    : itemFailure.isPresent()
                        ? "Item scope not verified"
                        : itemNeedsVerification
                            ? "Item dependencies need verification"
                            : namesFailure
                                ? "Name pair not verified · Item scope independent"
                                : namesVerified
                                    ? "Item/name physical pair · copies unindexed"
                                    : itemVerified
                                        ? "Item physical rows · copies unindexed"
                                        : rootVerified
                                            ? "Root map verified"
                                            : installVerified
                                                ? "Install list verified"
                                                : "Metadata only"),
            contextItem(
                "CONTENT",
                b == null
                    ? "Not checked"
                    : itemFailure.isPresent()
                        ? ItemSourceReadiness.message(itemFailure.get())
                        : itemNeedsVerification
                            ? "Item scope needs re-verification"
                            : namesFailure
                                ? "Name pair needs verification"
                                : namesVerified
                                    ? "Partial Item/name scope"
                                    : itemVerified
                                        ? "Partial Item scope"
                                        : retentionState != null
                                                && retentionState.receipt() != null
                                                && retentionState
                                                    .receipt()
                                                    .descriptor()
                                                    .association()
                                                    .build()
                                                    .snapshotId()
                                                    .equals(b.snapshotId())
                                            ? "Selected encoded file verified; decoded unverified"
                                            : configuredSelected != null
                                                ? "Selected file not checked"
                                                : rootVerified
                                                    ? "Game files not acquired"
                                                    : installVerified
                                                        ? "Game files not acquired"
                                                        : "Unverified"));
    Button change =
        button(
            b == null ? "Choose a build →" : "Change build",
            () -> {
              archiveInstallMode = false;
              archiveFindMode = false;
              navigate("Archive");
            },
            "context-action");
    context.getChildren().add(change);
    if (b != null && installModel != null) {
      Button files = button("Install files", () -> openInstallFiles(b), "context-action");
      files.setId("open-install-files");
      context.getChildren().add(files);
    }
  }

  private void onLookupState(FileLookupPresentationModel.State next) {
    FileLookupPresentationModel.State previous = lookupState;
    lookupState = next;
    if (!updatingContext
        && next.build() != null
        && (next.claimCode()
                == io.vibeminer.application.content.ContentClaimException.Code.MISSING_LOCAL
            || next.claimCode()
                == io.vibeminer.application.content.ContentClaimException.Code.INTEGRITY_FAILURE))
      invalidateRootDependencies(next.build(), next.claimCode());
    if (rootRepairModel != null
        && next.plan() != null
        && (offeredRootPlan != next.plan()
            || !Objects.equals(offeredRootCandidate, next.candidate()))) {
      offeredRootPlan = next.plan();
      offeredRootCandidate = next.candidate();
      if (next.candidate() != null
          && next.candidate().encodedBytes() > 0
          && next.plan().repair().isEmpty())
        rootRepairModel.offerRoot(
            new io.vibeminer.application.lookup.RootSelectionPlan(next.plan(), next.candidate()));
      else rootRepairModel.selectContext(next.build(), next.locale());
    }
    if (selectedModel != null
        && (next.result() == null || previous == null || previous.result() != next.result()))
      selectedModel.select(null, currentLocale);
    if (updatingContext || state == null) return;
    if (previous == null || previous.result() != next.result()) refreshContextStripe();
    status.setText(next.message());
    if (destination.equals("Archive") && archiveFindMode && lookupPane != null) {
      boolean candidateOnly =
          previous != null
              && previous.stage() == next.stage()
              && Objects.equals(previous.input(), next.input())
              && previous.plan() == next.plan()
              && previous.result() == next.result();
      if (candidateOnly) return;
      Node focused = stage.getScene().getFocusOwner();
      String id = focused == null ? null : focused.getId();
      lookupPane.refreshState();
      if (id != null
          && List.of(
                  "file-check-requirements",
                  "file-download-metadata",
                  "file-reopen-evidence",
                  "file-cancel")
              .contains(id)) {
        String target =
            next.busy()
                ? "file-cancel"
                : next.stage() == FileLookupPresentationModel.Stage.READY
                    ? (next.result().variants().isEmpty()
                        ? "file-proof-summary"
                        : "file-variant-table")
                    : next.stage() == FileLookupPresentationModel.Stage.PLANNED
                        ? "file-root-candidate"
                        : "file-check-requirements";
        Platform.runLater(
            () -> {
              if (destination.equals("Archive") && archiveFindMode) {
                Node control = root.lookup("#" + target);
                if (control != null) control.requestFocus();
              }
            });
      }
    } else if (destination.equals("Activity")) render();
  }

  private void onSelectedFileState(SelectedFilePresentationModel.State next) {
    var previous = selectedState;
    selectedState = next;
    tableSources.selectVariant(next.variant());
    if (retentionModel != null) {
      retentionModel.selectVariant(next.variant(), next.locale());
      retentionModel.select(
          next.plan() != null && next.candidate() != null
              ? new io.vibeminer.application.selectedfile.CandidateChoice(
                  next.plan(), next.candidate())
              : null,
          next.locale());
    }
    if (updatingContext || state == null) return;
    status.setText(next.message());
    boolean choiceOnly =
        previous != null
            && previous.stage() == next.stage()
            && previous.plan() == next.plan()
            && Objects.equals(previous.variant(), next.variant());
    if (destination.equals("Archive") && archiveFindMode && lookupPane != null && !choiceOnly)
      lookupPane.refreshSelectedFileState();
  }

  private void onRetentionState(SelectedFileRetentionPresentationModel.State next) {
    retentionState = next;
    if (next.receipt() != null
        && (next.stage() == SelectedFileRetentionPresentationModel.Stage.RETAINED
            || next.stage() == SelectedFileRetentionPresentationModel.Stage.OFFLINE_VERIFIED)) {
      tableSources.verified(next.receipt());
      itemReadiness.verified(next.receipt());
    }
    if ((next.code()
                == io.vibeminer.application.selectedfile.SelectedFileException.Code.MISSING_LOCAL
            || next.code()
                == io.vibeminer.application.selectedfile.SelectedFileException.Code
                    .INTEGRITY_FAILURE)
        && retentionModel.variant() != null)
      tableSources.invalidate(
          retentionModel.variant().build().snapshotId(), retentionModel.variant().fileDataId());
    syncItemContext();
    if (updatingContext || state == null) return;
    status.setText(next.message());
    refreshContextStripe();
    if (destination.equals("Archive") && archiveFindMode && lookupPane != null)
      lookupPane.refreshRetentionState();
  }

  private void onMetadataReuseState(MetadataReusePresentationModel.State next) {
    if (updatingContext || state == null) return;
    boolean matching =
        state.selected() != null
            && next.build() != null
            && state.selected().snapshotId().equals(next.build().snapshotId());
    boolean rootKind =
        next.kind() == io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ROOT;
    if (matching
        && next.stage() == MetadataReusePresentationModel.Stage.FAILED
        && (next.code() == io.vibeminer.application.content.ContentClaimException.Code.MISSING_LOCAL
            || next.code()
                == io.vibeminer.application.content.ContentClaimException.Code.INTEGRITY_FAILURE)) {
      if (rootKind) {
        itemReadiness.failed(
            itemReadiness.historicalFor(next.build()).orElse(null),
            next.code() == io.vibeminer.application.content.ContentClaimException.Code.MISSING_LOCAL
                ? io.vibeminer.application.item.ItemProjectionException.Code.MISSING_LOCAL
                : io.vibeminer.application.item.ItemProjectionException.Code.CORRUPT);
        tableSources.invalidateDependencies(next.build().snapshotId());
        if (namesModel != null) namesModel.invalidateDependency(next.build().snapshotId());
        if (itemModel != null)
          itemModel.invalidateMetadataDependency(next.build().snapshotId(), next.code());
        if (lookupModel != null) lookupModel.invalidateRetainedEvidence(next.code());
      } else if (installModel != null) installModel.invalidateRetainedEvidence(next.code());
    }
    status.setText(next.message());
    if (destination.equals("Archive")) {
      if (rootKind && archiveFindMode && lookupPane != null) lookupPane.refreshMetadataReuseState();
      else if (!rootKind && archiveInstallMode && installPane != null)
        installPane.refreshMetadataReuseState();
    }
  }

  private void invalidateRootDependencies(
      BuildSnapshot build, io.vibeminer.application.content.ContentClaimException.Code code) {
    if (state == null
        || state.selected() == null
        || !state.selected().snapshotId().equals(build.snapshotId())) return;
    tableSources.invalidateDependencies(build.snapshotId());
    itemReadiness.failed(
        itemReadiness.historicalFor(build).orElse(null),
        code == io.vibeminer.application.content.ContentClaimException.Code.MISSING_LOCAL
            ? io.vibeminer.application.item.ItemProjectionException.Code.MISSING_LOCAL
            : io.vibeminer.application.item.ItemProjectionException.Code.CORRUPT);
    if (namesModel != null) namesModel.invalidateDependency(build.snapshotId());
    if (itemModel != null) itemModel.invalidateMetadataDependency(build.snapshotId(), code);
  }

  private void syncItemContext() {
    if (itemModel != null && state != null)
      itemModel.selectContext(
          state.selected(),
          currentLocale,
          tableSources.current(state.selected(), 841626).orElse(null));
    syncNamesContext();
  }

  private void syncNamesContext() {
    if (namesModel != null && state != null)
      namesModel.selectContext(
          state.selected(),
          currentLocale,
          itemState == null ? null : itemState.handle(),
          tableSources
              .current(state.selected(), 1572924)
              .map(io.vibeminer.domain.selectedfile.SelectedFileAssociation::descriptor)
              .orElse(null));
  }

  private void onNamesState(ItemNamesPresentationModel.State next) {
    var previous = namesState;
    namesState = next;
    refreshExportContext();
    if (updatingContext || state == null) return;
    if (previous == null || previous.pair() != next.pair() || previous.code() != next.code())
      refreshContextStripe();
    if (!destination.equals("Database") || !namesMode || namesPane == null) return;
    status.setText(next.message());
    boolean draft =
        previous != null
            && previous.stage() == next.stage()
            && previous.pair() == next.pair()
            && previous.page() == next.page()
            && previous.lookup() == next.lookup()
            && previous.searchPage() == next.searchPage()
            && Objects.equals(previous.executedQuery(), next.executedQuery())
            && previous.queryMode() == next.queryMode();
    if (draft) {
      namesPane.refreshDraftState();
      return;
    }
    Node focused = stage.getScene().getFocusOwner();
    String id = focused == null ? null : focused.getId();
    int caret = focused instanceof TextInputControl f ? f.getCaretPosition() : -1;
    int anchor = focused instanceof TextInputControl f ? f.getAnchor() : -1;
    namesPane.refreshState();
    if (id != null
        && List.of(
                "names-record-id",
                "names-search-input",
                "names-search",
                "names-search-clear",
                "names-prepare",
                "names-cancel",
                "names-lookup",
                "names-browse",
                "names-first-page",
                "names-next-page")
            .contains(id)) {
      String target;
      if (id.equals("names-record-id") || id.equals("names-search-input")) target = id;
      else if (id.equals("names-search-clear")) target = "names-browse";
      else if (next.busy()) target = "names-cancel";
      else if (next.pair() == null) target = "names-prepare";
      else if (next.searchPage() != null && !next.searchPage().rows().isEmpty()
          || next.page() != null && !next.page().rows().isEmpty()
          || next.selected() != null) target = "names-row-table";
      else
        target =
            next.queryMode() == ItemNamesPresentationModel.QueryMode.SEARCH
                ? "names-search-input"
                : "names-record-id";
      Platform.runLater(
          () -> {
            if (destination.equals("Database") && namesMode && namesPane != null) {
              Node n = namesPane.lookup("#" + target);
              if (n != null) {
                n.requestFocus();
                if (n instanceof TextInputControl f && caret >= 0)
                  f.selectRange(
                      Math.min(Math.max(0, anchor), f.getLength()), Math.min(caret, f.getLength()));
              }
            }
          });
    }
  }

  private void onRepairState(MetadataRepairPresentationModel.State next) {
    if (updatingContext || state == null) return;
    status.setText(next.message());
    if (destination.equals("Archive")) {
      if (next.kind() == io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ROOT
          && archiveFindMode
          && lookupPane != null) lookupPane.refreshMetadataRepairState();
      else if (next.kind() == io.vibeminer.domain.content.MetadataStructureAttestation.Kind.INSTALL
          && archiveInstallMode
          && installPane != null) installPane.refreshMetadataRepairState();
    }
  }

  private void onRepairRevocation(MetadataRepairPresentationModel.ReadinessRevocation revoke) {
    if (state == null
        || state.selected() == null
        || !state.selected().snapshotId().equals(revoke.snapshotId())) return;
    boolean repairRequired =
        revoke.reason() == MetadataRepairPresentationModel.Reason.REPAIR_REQUIRED;
    if (revoke.kind() == io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ROOT) {
      if (repairRequired) {
        tableSources.invalidateDependencies(revoke.snapshotId());
        itemReadiness.requiresVerification(
            itemReadiness.historicalFor(state.selected()).orElse(null));
        if (namesModel != null) namesModel.invalidateDependency(revoke.snapshotId());
        if (itemModel != null) itemModel.invalidateForRepair(revoke.snapshotId());
        if (lookupModel != null) lookupModel.invalidateForRepair();
      } else {
        invalidateRootDependencies(state.selected(), revoke.code());
        if (lookupModel != null) lookupModel.invalidateRetainedEvidence(revoke.code());
      }
    } else if (installModel != null) {
      if (repairRequired) installModel.invalidateForRepair();
      else installModel.invalidateRetainedEvidence(revoke.code());
    }
    refreshContextStripe();
  }

  private void inspectSparseSource() {
    if (lookupModel != null) lookupModel.setInput("1572924");
    openFileLookup();
  }

  private void onItemState(ItemPresentationModel.State next) {
    var previous = itemState;
    itemState = next;
    refreshExportContext();
    syncNamesContext();
    itemReadiness.observe(next.source());
    if (next.code() == io.vibeminer.application.item.ItemProjectionException.Code.MISSING_LOCAL
        || next.code() == io.vibeminer.application.item.ItemProjectionException.Code.CORRUPT)
      itemReadiness.failed(next.source(), next.code());
    if (previous != null
        && previous.stage() == ItemPresentationModel.Stage.PREPARING
        && next.stage() == ItemPresentationModel.Stage.READY
        && next.handle() != null) itemReadiness.verified(next.source());
    if (!updatingContext
        && state != null
        && (previous == null
            || previous.handle() != next.handle()
            || next.code()
                == io.vibeminer.application.item.ItemProjectionException.Code.MISSING_LOCAL
            || next.code() == io.vibeminer.application.item.ItemProjectionException.Code.CORRUPT))
      refreshContextStripe();
    if (updatingContext
        || state == null
        || !destination.equals("Database")
        || namesMode
        || itemPane == null) return;
    status.setText(next.message());
    boolean sameResults =
        previous != null
            && previous.stage() == next.stage()
            && previous.handle() == next.handle()
            && previous.page() == next.page()
            && previous.lookup() == next.lookup()
            && Objects.equals(previous.source(), next.source())
            && Objects.equals(previous.build(), next.build())
            && previous.locale().equals(next.locale());
    if (sameResults) {
      itemPane.refreshDraftState();
      return;
    }
    Node focused = stage.getScene().getFocusOwner();
    String id = focused == null ? null : focused.getId();
    int caret = focused instanceof TextField field ? field.getCaretPosition() : -1;
    itemPane.refreshState();
    if (id != null
        && List.of(
                "item-prepare",
                "item-cancel",
                "item-record-id",
                "item-lookup",
                "item-browse",
                "item-first-page",
                "item-next-page")
            .contains(id)) {
      String target =
          id.equals("item-record-id")
              ? id
              : next.busy()
                  ? "item-cancel"
                  : next.handle() == null
                      ? "item-prepare"
                      : next.page() != null && !next.page().rows().isEmpty()
                              || next.lookup() != null && next.lookup().row().isPresent()
                          ? "item-row-table"
                          : id.equals("item-lookup") ? "item-record-id" : "item-browse";
      Platform.runLater(
          () -> {
            if (destination.equals("Database") && itemPane != null && itemPane.getScene() != null) {
              Node control = itemPane.lookup("#" + target);
              if (control != null) {
                control.requestFocus();
                if (control instanceof TextField field && caret >= 0)
                  field.positionCaret(Math.min(caret, field.getText().length()));
              }
            }
          });
    }
  }

  private void openFileLookup() {
    archiveFindMode = true;
    archiveInstallMode = false;
    navigate("Archive");
  }

  private void onInstallState(InstallPresentationModel.State next) {
    installState = next;
    if (installRepairModel != null && next.plan() != null && offeredInstallPlan != next.plan()) {
      offeredInstallPlan = next.plan();
      installRepairModel.offerInstall(
          next.plan(), io.vibeminer.application.lookup.LookupBudgets.defaults());
    }
    if (updatingContext || state == null) return;
    refreshContextStripe();
    status.setText(next.message());
    if (destination.equals("Archive") && archiveInstallMode && !archiveFindMode) {
      Node focused = stage.getScene().getFocusOwner();
      String id = focused == null ? null : focused.getId();
      render();
      String target = id;
      if (id == null
          || List.of("install-check", "install-download", "install-reopen", "install-cancel")
              .contains(id)) {
        if (next.stage() == InstallPresentationModel.Stage.PLANNED) target = "install-download";
        else if (next.stage() == InstallPresentationModel.Stage.RETAINED)
          target = "install-entry-table";
        else if (!next.busy()) target = next.plan() == null ? "install-check" : "install-download";
        else target = "install-cancel";
      }
      String focusId = target;
      if (focusId != null)
        Platform.runLater(
            () -> {
              Node control = root.lookup("#" + focusId);
              if (control != null) control.requestFocus();
            });
    } else if (destination.equals("Activity")) render();
  }

  private void openInstallFiles(BuildSnapshot build) {
    if (state.selected() == null || !state.selected().snapshotId().equals(build.snapshotId())) {
      leaveSavedWorkspace();
      model.select(build);
    }
    archiveInstallMode = true;
    archiveFindMode = false;
    navigate("Archive");
  }

  private VBox contextItem(String title, String value) {
    Label text = label(value, "context-value");
    text.setWrapText(false);
    text.setMaxWidth(Double.MAX_VALUE);
    text.setMinHeight(Region.USE_PREF_SIZE);
    VBox box = new VBox(3, label(title, "eyebrow"), text);
    box.setMinHeight(Region.USE_PREF_SIZE);
    box.setAccessibleText(title + ": " + value);
    return box;
  }

  private void navigate(String name) {
    if (configuredBuildLibrary != null
        && !name.equals("Archive")
        && !name.equals("Settings")
        && !(name.equals("BuildHome") && overviewPane != null)) return;
    if (openingModel != null && name.equals("Archive") && destination.equals("BuildHome"))
      retireOpening();
    if (openingModel != null
        && name.equals("Archive")
        && destination.equals("Settings")
        && overviewPane != null) name = "BuildHome";
    destination = name;
    if (openingModel != null) openingModel.setDiscoveryActive(!name.equals("Settings"));
    navigation.getToggles().forEach(t -> t.setSelected(destination.equals(t.getUserData())));
    render();
  }

  private void render() {
    if (state == null) return;
    if (configuredBuildLibrary != null
        && !destination.equals("Archive")
        && !destination.equals("Settings")
        && !(destination.equals("BuildHome") && overviewPane != null)) destination = "Archive";
    boolean guided = guidedModel != null && destination.equals("Archive");
    context.setVisible(configuredBuildLibrary == null);
    context.setManaged(configuredBuildLibrary == null);
    if (destination.equals("BuildHome") && overviewPane != null) {
      savedWorkspaceBar.setVisible(false);
      savedWorkspaceBar.setManaged(false);
      workspaceFeedback.setVisible(false);
      workspaceFeedback.setManaged(false);
      mode.setText("EXACT BUILD · METADATA READY");
      status.setText("Metadata verified. Build content coverage has not been measured.");
      if (overviewScroll == null) {
        overviewScroll = new ScrollPane(overviewPane);
        overviewScroll.setFitToWidth(true);
        overviewScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        overviewScroll.getStyleClass().add("workspace-scroll");
        overviewScroll.setId("build-overview-scroll");
      }
      content.getChildren().setAll(overviewScroll);
      return;
    }
    if (guided) {
      savedWorkspaceBar.setVisible(false);
      savedWorkspaceBar.setManaged(false);
      workspaceFeedback.setVisible(false);
      workspaceFeedback.setManaged(false);
      mode.setText("OFFICIAL BUILD LIBRARY");
      var guidedState = guidedModel.state();
      status.setText(guidedState.error().isBlank() ? guidedState.message() : guidedState.error());
      if (guidedScroll == null) {
        guidedScroll = new ScrollPane(guidedPane);
        guidedScroll.setFitToWidth(true);
        guidedScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        guidedScroll.getStyleClass().add("workspace-scroll");
        guidedScroll.setId("guided-discovery-scroll");
      }
      if (content.getChildren().size() != 1 || content.getChildren().getFirst() != guidedScroll)
        content.getChildren().setAll(guidedScroll);
      return;
    }
    savedWorkspaceBar.setVisible(configuredWorkspaces != null && configuredBuildLibrary == null);
    savedWorkspaceBar.setManaged(configuredWorkspaces != null && configuredBuildLibrary == null);
    mode.setText(
        configuredBuildLibrary != null
            ? "OFFICIAL BUILD LIBRARY"
            : state.fixtureMode() ? "FICTIONAL FIXTURE MODE" : "OFFICIAL METADATA DISCOVERY");
    Node node =
        switch (destination) {
          case "Archive" -> archive();
          case "Compare" -> comparison();
          case "Database" -> database();
          case "Assets" -> assets();
          case "Notebook" -> notebook();
          case "Activity" -> activity();
          case "Settings" -> settings();
          case "Search" -> search();
          default -> research();
        };
    ScrollPane scroll = new ScrollPane(node);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.getStyleClass().add("workspace-scroll");
    content.getChildren().setAll(scroll);
  }

  private VBox research() {
    VBox page =
        page(
            "RESEARCH WORKSPACE",
            "Follow the evidence.",
            "Start with a question. Keep every answer tied to an exact build.");
    HBox heading =
        new HBox(
            16,
            label("What are you looking for?", "section-heading"),
            spacer(),
            button("Browse build archive →", () -> navigate("Archive"), "primary"));
    heading.setAlignment(Pos.CENTER_LEFT);
    GridPane cards = new GridPane();
    cards.setHgap(12);
    cards.setVgap(10);
    java.util.ArrayList<Button> topicButtons = new java.util.ArrayList<>();
    for (int i = 0; i < topics.length; i++) {
      String[] t = topics[i];
      Label topicTitle = label(t[0] + " / " + t[1], "topic-title");
      topicTitle.setWrapText(true);
      Label topicDescription = label(t[2], "secondary");
      topicDescription.setWrapText(true);
      VBox words = new VBox(4, topicTitle, topicDescription);
      words.setAlignment(Pos.CENTER_LEFT);
      Button card = new Button();
      card.setGraphic(words);
      card.setMaxWidth(Double.MAX_VALUE);
      card.getStyleClass().add("topic-card");
      words.prefWidthProperty().bind(card.widthProperty().subtract(28));
      words.maxWidthProperty().bind(card.widthProperty().subtract(28));
      double topicScale = Integer.parseInt(selectedScale.replace("%", "")) / 100.0;
      card.setPrefHeight(68 * topicScale);
      card.setMinHeight(Region.USE_PREF_SIZE);
      card.setMaxHeight(Region.USE_PREF_SIZE);
      card.setAccessibleText(t[1] + ". " + t[2] + ". " + t[3]);
      card.setOnAction(
          e -> {
            researchTopic = t[1];
            searchText = "";
            showSearch();
          });
      card.setMinWidth(0);
      topicButtons.add(card);
      cards.add(card, i % 3, i / 3);
      GridPane.setHgrow(card, Priority.ALWAYS);
    }
    for (int i = 0; i < 3; i++) {
      ColumnConstraints c = new ColumnConstraints();
      c.setPercentWidth(100d / 3);
      cards.getColumnConstraints().add(c);
    }
    cards
        .widthProperty()
        .addListener(
            (observable, oldWidth, newWidth) -> {
              double scaleFactor = Integer.parseInt(selectedScale.replace("%", "")) / 100.0;
              int cols = newWidth.doubleValue() < 1020 * scaleFactor ? 2 : 3;
              if (cards.getColumnConstraints().size() != cols) {
                cards.getColumnConstraints().clear();
                for (int i = 0; i < cols; i++) {
                  ColumnConstraints c = new ColumnConstraints();
                  c.setPercentWidth(100d / cols);
                  cards.getColumnConstraints().add(c);
                }
                for (int i = 0; i < topicButtons.size(); i++) {
                  GridPane.setColumnIndex(topicButtons.get(i), i % cols);
                  GridPane.setRowIndex(topicButtons.get(i), i / cols);
                }
              }
            });
    VBox launcher = panel(heading, cards);
    launcher.getStyleClass().add("research-launcher");
    page.getChildren().add(launcher);
    VBox left =
        panel(
            label("YOUR NEXT STEP", "eyebrow"),
            label(
                state.selected() == null
                    ? "Choose the evidence first."
                    : "A precise starting point.",
                "section-heading"),
            wrap(
                state.selected() == null
                    ? "Select a published build or a clearly labelled fixture. Research results"
                        + " will only appear when its required tables are supported and indexed."
                    : "Research findings require supported decoded and indexed tables. Retained"
                        + " encoded files and source metadata alone do not establish research"
                        + " findings."),
            button(
                state.selected() == null ? "Select a build →" : "Inspect selected source →",
                () -> {
                  if (state.selected() == null) navigate("Archive");
                  else buildDetails(state.selected());
                },
                "primary"));
    left.getChildren().add(new Separator());
    left.getChildren()
        .addAll(
            label("A disciplined research workflow", "section-heading"),
            step(
                "01",
                "Find",
                "Search names, stable IDs or supported tables within a selected scope."),
            step(
                "02", "Inspect", "Follow raw fields, related records and their source provenance."),
            step(
                "03",
                "Investigate",
                "Preserve questions and exact evidence before drawing conclusions."));
    VBox right =
        panel(
            label("FIELD NOTES", "eyebrow"),
            label("Evidence, with context.", "section-heading"),
            wrap(
                "A new client record does not establish release timing, obtainability or server"
                    + " behavior."),
            note(
                "SOURCE DISCIPLINE",
                "Blizzard content and community interpretation are different sources. A future"
                    + " inspector will identify schema and naming providers separately."),
            label("AVAILABLE IN THIS FIRST SLICE", "eyebrow"),
            wrap(
                "Published metadata and pinned source identity. Install-file list inspection is a"
                    + " separate, explicitly scoped action."),
            button("View available capabilities", this::boundaries, "quiet-button"));
    HBox body = new HBox(16, left, right);
    HBox.setHgrow(left, Priority.ALWAYS);
    left.setMaxWidth(Double.MAX_VALUE);
    right.setPrefWidth(350);
    right.setMinWidth(280);
    page.getChildren().add(body);
    return page;
  }

  private VBox archive() {
    if (guidedPane != null) return guidedPane;
    VBox page;
    if ((archiveInstallMode && installModel != null || archiveFindMode && lookupModel != null)
        && state.selected() != null) {
      page = new VBox(14);
      page.getStyleClass().add("workspace");
      page.getChildren()
          .add(
              label(
                  archiveFindMode ? "ARCHIVE / FIND FILE" : "ARCHIVE / INSTALL FILES", "eyebrow"));
    } else
      page =
          page(
              "CDN SOURCES / BUILD ARCHIVE",
              "Choose a build. Keep its identity.",
              "Discovery lists published pointers. It does not guarantee that historical content"
                  + " remains available.");
    if (state.selected() != null && (installModel != null || lookupModel != null)) {
      FlowPane views =
          new FlowPane(
              8,
              8,
              button(
                  "Builds & sources",
                  () -> {
                    archiveInstallMode = false;
                    archiveFindMode = false;
                    render();
                  },
                  archiveInstallMode || archiveFindMode ? "quiet-button" : "primary"));
      if (installModel != null)
        views
            .getChildren()
            .add(
                button(
                    "Install files",
                    () -> {
                      archiveInstallMode = true;
                      archiveFindMode = false;
                      render();
                    },
                    archiveInstallMode ? "primary" : "quiet-button"));
      if (lookupModel != null) {
        Button find =
            button("Find file", this::openFileLookup, archiveFindMode ? "primary" : "quiet-button");
        find.setId("open-file-lookup");
        views.getChildren().add(find);
      }
      views.setId("archive-views");
      page.getChildren().add(views);
      if (archiveFindMode && lookupModel != null) {
        lookupPane =
            new FileLookupPane(
                lookupModel,
                Integer.parseInt(selectedScale.replace("%", "")) / 100.0,
                selectedFileVariant,
                key -> selectedFileVariant = key,
                selectedModel,
                retentionModel,
                rootReuseModel,
                rootRepairModel);
        page.getChildren().add(lookupPane);
        return page;
      }
      if (archiveInstallMode && installModel != null) {
        installPane =
            new InstallFilesPane(
                installModel,
                Integer.parseInt(selectedScale.replace("%", "")) / 100.0,
                installQuery,
                selectedInstallEntry,
                q -> installQuery = q,
                key -> selectedInstallEntry = key,
                installReuseModel,
                installRepairModel);
        page.getChildren().add(installPane);
        return page;
      }
    }
    ComboBox<String> product = new ComboBox<>();
    product.setId("discovery-product");
    product.setPrefWidth(240);
    product.setPromptText("Refresh products first");
    product.setAccessibleText("Discovered raw official product identifier");
    ComboBox<String> region =
        new ComboBox<>(FXCollections.observableArrayList("us", "eu", "kr", "tw"));
    region.setId("discovery-region");
    region.setValue(selectedRegion);
    region.valueProperty().addListener((o, a, b) -> selectedRegion = b);
    region.setAccessibleText("Discovery region");
    Button refresh =
        button(
            "Check official metadata",
            () -> {
              leaveSavedWorkspace();
              model.refresh(product.getValue(), region.getValue());
            },
            "primary");
    refresh.setId("check-build-metadata");
    Label pointerStatus = wrap("");
    Runnable selectionChanged =
        () -> {
          selectedProduct = product.getValue();
          boolean supported =
              productModel != null
                  ? productModel.canRequestCurrent(selectedProduct)
                  : state.fixtureMode() && selectedProduct != null;
          refresh.setDisable(state.busy() || !supported);
          PublishedProduct selected = publishedProduct(selectedProduct);
          pointerStatus.setText(
              selected == null
                  ? (productModel == null
                      ? "Fixture IDs only; official product discovery is unavailable in this test"
                          + " configuration."
                      : "Choose a raw product ID. No game files are downloaded.")
                  : pointerSummary(selected) + " · Content and decoder support unverified.");
        };
    updateProductItems(product);
    product.valueProperty().addListener((o, a, b) -> selectionChanged.run());
    selectionChanged.run();
    product.setCellFactory(
        list ->
            new ListCell<>() {
              protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                PublishedProduct p = publishedProduct(value);
                setText(empty ? null : value + (p == null ? "" : " · " + pointerSummary(p)));
                setTooltip(empty ? null : new Tooltip(getText()));
              }
            });
    Label matchingCount = label(productCountText(), "secondary");
    TextField filter = new TextField(productFilter);
    filter.setId("product-filter");
    filter.setPromptText("Filter discovered product IDs…");
    filter.setAccessibleText("Filter discovered raw product identifiers");
    filter.setPrefWidth(280);
    filter
        .textProperty()
        .addListener(
            (o, a, b) -> {
              productFilter = b;
              updateProductItems(product);
              matchingCount.setText(productCountText());
              selectionChanged.run();
            });
    Button refreshProducts =
        button("Refresh products", () -> productModel.refresh(), "quiet-button");
    refreshProducts.setId("refresh-products");
    refreshProducts.setDisable(productModel == null || productState.busy());
    Button pointers =
        button(
            "Inspect published pointers",
            () -> {
              PublishedProduct p = publishedProduct(product.getValue());
              if (p != null) productDetails(p);
            },
            "quiet-button");
    pointers
        .disableProperty()
        .bind(
            product
                .valueProperty()
                .isNull()
                .or(new javafx.beans.property.SimpleBooleanProperty(productModel == null)));
    FlowPane catalogActions = new FlowPane(10, 8, refreshProducts, filter, pointers);
    VBox sources =
        panel(label("OFFICIAL PRODUCT SUMMARY / BLIZZARD CDN", "eyebrow"), catalogActions);
    sources.setSpacing(8);
    sources.getStyleClass().add("discovery-sources");
    if (productState != null) {
      if (productState.busy())
        sources
            .getChildren()
            .add(
                busyPanel(
                    "Checking published products…",
                    "This does not fetch game content or change your selected exact-build"
                        + " workspace.",
                    () -> productModel.stopWaiting()));
      else if (!productState.error().isBlank())
        sources
            .getChildren()
            .add(
                notice(
                    "Product discovery unavailable",
                    productState.error() + " " + productState.message()));
      sources.getChildren().add(matchingCount);
    }
    FlowPane actions =
        new FlowPane(
            10,
            8,
            field("PRODUCT ID", product),
            field("REGION", region),
            refresh,
            button(
                "Open fictional fixtures",
                () -> {
                  leaveSavedWorkspace();
                  model.showFixtures();
                },
                "quiet-button"));
    actions.setAlignment(Pos.BOTTOM_LEFT);
    sources.getChildren().addAll(actions, pointerStatus);
    if (workspaceModel != null
        && (!workspaceState.workspaces().isEmpty()
            || workspaceState.busy()
            || !workspaceState.error().isBlank())) page.getChildren().add(savedWorkspacesPanel());
    page.getChildren().add(sources);
    if (state.busy())
      page.getChildren()
          .add(
              busyPanel(
                  "Checking published metadata…",
                  "You can navigate while this request runs. Stopping observation prevents late"
                      + " results from entering your workspace.",
                  () -> model.stopWaiting()));
    if (!state.error().isBlank())
      page.getChildren().add(notice("Discovery unavailable", state.error()));
    if (state.fixtureMode())
      page.getChildren()
          .add(
              label(
                  "FICTIONAL FIXTURES · These builds and configuration IDs are test examples, not"
                      + " Blizzard findings.",
                  "fixture-inline"));
    TableView<BuildSnapshot> table =
        new TableView<>(FXCollections.observableArrayList(state.builds()));
    table.setId("build-table");
    table.setAccessibleText(
        "Published build metadata. Select a build and press Enter to open its workspace.");
    table
        .getColumns()
        .add(column("Edition / product", b -> b.editionLabel() + " / " + b.productId(), 190));
    table.getColumns().add(column("Exact version", BuildSnapshot::versionLabel, 180));
    table.getColumns().add(column("Channel / region", b -> b.channel() + " / " + b.region(), 160));
    table
        .getColumns()
        .add(column("Source", b -> b.fixture() ? "Fictional fixture" : "Official discovery", 160));
    table.getColumns().add(column("Coverage", b -> "Metadata only", 155));
    if (state.selected() != null) table.getSelectionModel().select(state.selected());
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    sizeMetadataTable(table, state.builds().size());
    table.setPlaceholder(
        wrap(
            state.busy()
                ? "Loading metadata…"
                : "No build metadata loaded. Choose an official source or open fictional"
                    + " fixtures."));
    table.setOnKeyPressed(
        e -> {
          if (e.getCode() == KeyCode.ENTER) {
            openSelection(table);
            e.consume();
          }
        });
    table.setRowFactory(
        tv -> {
          TableRow<BuildSnapshot> row = new TableRow<>();
          row.setOnMouseClicked(
              e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                  openBuild(row.getItem());
                }
              });
          return row;
        });
    Button open = button("Open metadata workspace →", () -> openSelection(table), "primary");
    open.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
    Button inspect =
        button(
            "Inspect identity",
            () -> {
              BuildSnapshot b = table.getSelectionModel().getSelectedItem();
              if (b != null) buildDetails(b);
            },
            "quiet-button");
    inspect.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
    Button install =
        button(
            "Install files…",
            () -> {
              BuildSnapshot selected = table.getSelectionModel().getSelectedItem();
              if (selected != null) openInstallFiles(selected);
            },
            "quiet-button");
    install.setId("inspect-install-files");
    install
        .disableProperty()
        .bind(
            table
                .getSelectionModel()
                .selectedItemProperty()
                .isNull()
                .or(new javafx.beans.property.SimpleBooleanProperty(installModel == null)));
    VBox buildResults =
        panel(
            label("OBSERVED BUILD METADATA · EXACT SOURCE PER ROW", "eyebrow"),
            table,
            new FlowPane(10, 8, open, inspect, install));
    if (state.builds().isEmpty()) page.getChildren().add(buildResults);
    else
      page.getChildren()
          .add(
              state.selected() != null && (installModel != null || lookupModel != null) ? 2 : 1,
              buildResults);
    page.getChildren()
        .add(
            note(
                "RETAINED SCOPE",
                "Exact build metadata, verified install lists, supported root lookup maps and"
                    + " explicitly selected encoded files can be retained locally; identical bytes"
                    + " are shared. Selected encoded integrity does not establish decoded content."
                    + " Complete build archives and cleanup controls remain unavailable. Unmeasured"
                    + " storage totals stay unknown."));
    return page;
  }

  private void onGuidedState(GuidedDiscoveryPresentationModel.State next) {
    if (guidedPane != null) guidedPane.update(guidedModel.viewData());
    if (status != null && destination.equals("Archive"))
      status.setText(next.error().isBlank() ? next.message() : next.error());
  }

  private void retireOpening() {
    if (openingModel != null) openingModel.clear();
    overviewPane = null;
    overviewScroll = null;
    openedEntry = null;
  }

  private void openGuidedBuild(BuildLibraryPage.Entry entry) {
    if (openingModel == null) return;
    openedEntry = entry;
    openingModel.select(entry.row().group().preferredReference(), currentLocale);
    openingModel.open();
  }

  private void onOpeningState(BuildOpenPresentationModel.State next) {
    boolean revealProgress = next.busy() && !wasOpening;
    wasOpening = next.busy();
    if (openingFeedback != null) {
      boolean visible = next.busy() || !next.error().isBlank();
      openingFeedback.setVisible(visible);
      openingFeedback.setManaged(visible);
      openingCancel.setVisible(next.busy());
      openingCancel.setManaged(next.busy());
      if (next.progress() != null && next.busy()) {
        var progress = next.progress();
        String requirements =
            progress.verifiedRequirements()
                + "/"
                + (progress.requiredRequirements().isPresent()
                    ? progress.requiredRequirements().getAsInt()
                    : "unknown")
                + " metadata requirements verified";
        String wire =
            progress.transferredWireBytes().isPresent()
                ? progress.transferredWireBytes().getAsLong() + " wire bytes received"
                : "Wire bytes not measured";
        if (progress.expectedWireBytes().isPresent())
          wire += " of " + progress.expectedWireBytes().getAsLong();
        openingProgress.setText(
            progress.stage().name().replace('_', ' ') + " · " + requirements + " · " + wire);
      } else openingProgress.setText(next.error());
    }
    if (openingPoll != null) {
      if (next.busy()) openingPoll.play();
      else openingPoll.stop();
    }
    if (guidedPane != null) guidedPane.update(guidedModel.viewData());
    if (revealProgress) Platform.runLater(this::revealOpeningProgress);
    if (next.admission() != null) {
      var admission = next.admission();
      var view = admittedOverview(admission, java.time.Instant.now());
      var admittedEntry = openedEntry;
      var actions =
          openingModel.bindOverviewActions(
              admission,
              () -> {
                if (admittedEntry != null) guidedSourceDetails(admittedEntry);
              },
              () -> {
                retireOpening();
                navigate("Archive");
              });
      overviewPane =
          new BuildOverviewPane(
              view,
              new BuildOverviewPane.Callbacks(
                  ignored -> {},
                  ignored -> actions.source().run(),
                  actions.back(),
                  () -> {},
                  () -> {}));
      overviewScroll = null;
      // Settings preserves context and never gets interrupted by a completion.
      if (destination.equals("Archive") && openingModel.overviewRequested()) {
        destination = "BuildHome";
        render();
      }
    }
  }

  private void revealOpeningProgress() {
    if (openingModel == null
        || !openingModel.state().busy()
        || !destination.equals("Archive")
        || guidedScroll == null) return;
    guidedPane.applyCss();
    guidedPane.layout();
    double viewport = guidedScroll.getViewportBounds().getHeight();
    double extent = guidedPane.getBoundsInLocal().getHeight() - viewport;
    if (extent > 0) {
      double bottom =
          guidedPane
              .sceneToLocal(openingCancel.localToScene(openingCancel.getBoundsInLocal()))
              .getMaxY();
      guidedScroll.setVvalue(Math.max(0, Math.min(1, (bottom - viewport + 16) / extent)));
    }
    openingCancel.requestFocus();
  }

  /** Presentation projection of committed metadata admission, not a payload inventory. */
  static BuildOverviewPane.ViewData admittedOverview(
      BuildMetadataAdmission admission, java.time.Instant observedAt) {
    var reference = admission.reference();
    var key = reference.key();
    String edition =
        WarcraftProductLabels.options().stream()
            .filter(p -> p.productId().equals(key.productId()))
            .map(GuidedDiscoveryPane.ProductOption::description)
            .findFirst()
            .orElse(key.productId());
    var empty = java.util.OptionalLong.empty();
    return new BuildOverviewPane.ViewData(
        new BuildOverviewPane.Identity(
            reference.snapshotId() + ":" + admission.locale(),
            edition,
            key.productId(),
            key.channel(),
            reference.region(),
            key.versionLabel(),
            admission.locale()),
        new BuildOverviewPane.Scope(
            admission.scopeRevision(),
            "All declared languages and platforms · research locale " + admission.locale()),
        BuildOverviewPane.Readiness.PARTIAL,
        BuildOverviewPane.MetadataState.READY,
        new BuildOverviewPane.Metrics(
            admission.scopeRevision(),
            observedAt,
            empty,
            empty,
            empty,
            empty,
            false,
            empty,
            empty,
            empty),
        BuildOverviewPane.DownloadMode.ON_DEMAND,
        "No bulk download running",
        "",
        List.of(),
        new BuildOverviewPane.Capabilities(
            false,
            false,
            false,
            false,
            "Build inventory and bulk downloading are not connected yet. Retained content"
                + " coverage is unknown."));
  }

  private void guidedSourceDetails(BuildLibraryPage.Entry entry) {
    var group = entry.row().group();
    var key = group.key();
    var text = new StringBuilder();
    text.append("Product: ")
        .append(key.productId())
        .append("\nSource channel: ")
        .append(key.channel())
        .append("\nVersion: ")
        .append(key.versionLabel())
        .append("\nUpstream build ID: ")
        .append(key.upstreamBuildId())
        .append("\nBuild configuration: ")
        .append(key.buildConfig())
        .append("\nCDN configuration: ")
        .append(key.cdnConfig())
        .append("\nProduct configuration: ")
        .append(key.productConfig())
        .append("\nGroup identity: ")
        .append(key.identity())
        .append("\nPreferred metadata reference: ")
        .append(group.preferredReference().region())
        .append("\nThis preference is not proof of an available download route.\n");
    new java.util.TreeMap<>(key.additionalReferences())
        .forEach((name, value) -> text.append(name).append(": ").append(value).append('\n'));
    var regions =
        new java.util.LinkedHashMap<
            String, io.vibeminer.application.buildlibrary.BuildLibraryReference>();
    group.references().forEach(ref -> regions.putIfAbsent(ref.region(), ref));
    for (var ref : regions.values()) {
      text.append("\nREGION ")
          .append(ref.region())
          .append(" · event ")
          .append(ref.eventId())
          .append("\nSnapshot: ")
          .append(ref.snapshotId())
          .append("\nObserved: ")
          .append(ref.observedAt());
      for (var receipt : List.of(ref.versions(), ref.cdns()))
        text.append("\n")
            .append(receipt.sourceUrl())
            .append("\nCaptured: ")
            .append(receipt.capturedAt())
            .append(" · bytes: ")
            .append(receipt.length())
            .append("\nSHA-256: ")
            .append(receipt.sha256());
      text.append("\nCDN row: ")
          .append(new java.util.TreeMap<>(ref.selectedCdn().values()))
          .append('\n');
    }
    TextArea receipts = new TextArea(text.toString());
    receipts.setEditable(false);
    receipts.setWrapText(true);
    receipts.setPrefRowCount(16);
    receipts.setAccessibleText("Exact build identity and preserved regional source receipts");
    dialog(
        "Build source · " + key.versionLabel(),
        new VBox(12, wrap(WarcraftProductLabels.sources()), receipts));
  }

  private void onProductState(ProductPresentationModel.State next) {
    productState = next;
    status.setText(next.error().isBlank() ? next.message() : next.error());
    if (destination.equals("Archive") && !archiveInstallMode && !archiveFindMode) {
      Node focused = stage.getScene().getFocusOwner();
      String focusId = focused == null ? null : focused.getId();
      render();
      if (focusId != null)
        Platform.runLater(
            () -> {
              Node restored = root.lookup("#" + focusId);
              if (restored != null) restored.requestFocus();
            });
    } else if (destination.equals("Activity")) render();
  }

  private String productCountText() {
    return productState == null || productState.catalogue() == null
        ? "No product catalog loaded · Refresh products explicitly to begin."
        : productModel.matching(productFilter).size()
            + " matching raw IDs / "
            + productState.catalogue().products().size()
            + " published · captured "
            + productState.catalogue().evidence().capturedAt();
  }

  private PublishedProduct publishedProduct(String id) {
    return productModel == null || id == null
        ? null
        : productModel.matching("").stream()
            .filter(p -> p.productId().equals(id))
            .findFirst()
            .orElse(null);
  }

  private void updateProductItems(ComboBox<String> picker) {
    List<String> ids =
        productModel != null
            ? productModel.matching(productFilter).stream()
                .map(PublishedProduct::productId)
                .toList()
            : state.fixtureMode()
                ? state.builds().stream()
                    .map(BuildSnapshot::productId)
                    .distinct()
                    .filter(id -> id.contains(productFilter))
                    .toList()
                : List.of();
    String prior = selectedProduct;
    picker.getItems().setAll(ids);
    if (prior != null && ids.contains(prior)) picker.setValue(prior);
    else if (productModel == null && !ids.isEmpty()) picker.setValue(ids.getFirst());
    else {
      picker.setValue(null);
      selectedProduct = null;
    }
    picker.setPlaceholder(
        wrap(
            productState == null || productState.catalogue() == null
                ? "No product catalog loaded. Refresh products first."
                : "No matching raw product IDs. Clear the filter."));
  }

  private String pointerSummary(PublishedProduct p) {
    var labels = new java.util.ArrayList<String>();
    if (p.hasCurrentVersions()) labels.add("current versions");
    if (p.hasCdnMetadata()) labels.add("CDN metadata");
    if (p.hasAdvertisedPreload()) labels.add("preload advertised");
    if (p.pointers().stream().anyMatch(pointer -> pointer.kind() == ProductPointer.Kind.UNKNOWN))
      labels.add("unrecognized flags");
    return String.join(" / ", labels);
  }

  private void productDetails(PublishedProduct p) {
    VBox body =
        new VBox(
            12,
            label(p.productId(), "section-heading"),
            wrap(
                "Raw published product ID. Edition mapping and decoding capabilities are not"
                    + " established by the product name."));
    for (var pointer : p.pointers())
      body.getChildren()
          .add(
              identity(
                  pointer.kind().name(),
                  "Flag: "
                      + (pointer.flag().isEmpty() ? "(empty: current versions)" : pointer.flag())
                      + " · Publication sequence: "
                      + pointer.sequence()));
    body.getChildren()
        .add(
            wrap(
                "Preload is advertised metadata only; this interface does not request preload"
                    + " builds. A CDN pointer is not evidence of a downloadable complete build."
                    + " Unknown flags are preserved without interpretation."));
    if (productState != null && productState.catalogue() != null) {
      var evidence = productState.catalogue().evidence();
      body.getChildren()
          .addAll(
              identity("SUMMARY SOURCE URL", evidence.sourceUrl()),
              identity("CAPTURED / SHA-256", evidence.capturedAt() + " / " + evidence.sha256()));
    }
    dialog("Published pointers · " + p.productId(), body);
  }

  private void onWorkspaceState(WorkspacePresentationModel.State next) {
    workspaceState = next;
    if (activeWorkspace != null) {
      String id = activeWorkspace.id();
      activeWorkspace =
          next.workspaces().stream().filter(w -> w.id().equals(id)).findFirst().orElse(null);
    }
    updateSavedWorkspaceBar();
    boolean showFeedback =
        !next.error().isBlank()
            || next.message().startsWith("Saved metadata workspace")
            || next.message().startsWith("Workspace label updated")
            || next.message().startsWith("Saved workspace membership removed");
    workspaceFeedback.getChildren().clear();
    workspaceFeedback.setManaged(showFeedback);
    workspaceFeedback.setVisible(showFeedback);
    if (showFeedback) {
      Label message = wrap(next.error().isBlank() ? next.message() : next.error());
      HBox.setHgrow(message, Priority.ALWAYS);
      Button dismiss =
          button(
              "Dismiss",
              () -> {
                workspaceFeedback.setVisible(false);
                workspaceFeedback.setManaged(false);
              },
              "quiet-button");
      HBox row = new HBox(12, message, dismiss);
      row.setAlignment(Pos.CENTER_LEFT);
      workspaceFeedback.getChildren().add(row);
      workspaceFeedback.getStyleClass().setAll("workspace-feedback");
    }
    status.setText(next.error().isBlank() ? next.message() : next.error());
    if (destination.equals("Archive") && !archiveInstallMode && !archiveFindMode) {
      Node focused = stage.getScene().getFocusOwner();
      String focusId = focused == null ? null : focused.getId();
      render();
      if (focusId != null)
        Platform.runLater(
            () -> {
              Node restored = root.lookup("#" + focusId);
              if (restored != null) restored.requestFocus();
            });
    }
  }

  private void updateSavedWorkspaceBar() {
    if (workspaceModel == null || workspaceState == null) return;
    ComboBox<Workspace> picker =
        new ComboBox<>(FXCollections.observableArrayList(workspaceState.workspaces()));
    picker.setId("saved-workspace-picker");
    picker.setPromptText("Open saved metadata workspace…");
    picker.setPrefWidth(360);
    picker.setAccessibleText(
        "Saved metadata workspaces. Reopen exact build offline without refreshing discovery.");
    picker.setConverter(
        new javafx.util.StringConverter<>() {
          public String toString(Workspace w) {
            return w == null
                ? ""
                : w.name()
                    + " · "
                    + w.build().productId()
                    + " / "
                    + w.build().channel()
                    + " / "
                    + w.build().region()
                    + " · "
                    + w.build().versionLabel()
                    + " / "
                    + w.locale();
          }

          public Workspace fromString(String text) {
            return null;
          }
        });
    if (activeWorkspace != null) {
      Tooltip full = new Tooltip(picker.getConverter().toString(activeWorkspace));
      full.setWrapText(true);
      full.setMaxWidth(640);
      picker.setTooltip(full);
    }
    picker.setCellFactory(
        list ->
            new ListCell<>() {
              protected void updateItem(Workspace item, boolean empty) {
                super.updateItem(item, empty);
                String text = empty ? null : picker.getConverter().toString(item);
                setText(text);
                if (empty) setTooltip(null);
                else {
                  Tooltip full = new Tooltip(text);
                  full.setWrapText(true);
                  full.setMaxWidth(640);
                  setTooltip(full);
                }
              }
            });
    picker.setValue(activeWorkspace);
    picker.setOnAction(
        e -> {
          if (picker.getValue() != null
              && (activeWorkspace == null || !activeWorkspace.id().equals(picker.getValue().id())))
            openWorkspace(picker.getValue());
        });
    Button save = button("Save metadata…", this::saveWorkspaceDialog, "quiet-button");
    save.setAccessibleText("Save current exact-build metadata with a local label and locale");
    save.setId("save-workspace");
    save.setDisable(state == null || state.selected() == null || workspaceState.busy());
    Button reload = button("Refresh saved list", () -> workspaceModel.load(), "quiet-button");
    reload.setDisable(workspaceState.busy());
    Label operation =
        label(
            workspaceState.busy()
                ? workspaceState.message()
                : workspaceState.error().isBlank()
                    ? "Offline metadata · " + workspaceState.workspaces().size() + " saved"
                    : workspaceState.error(),
            "secondary");
    operation.setWrapText(false);
    operation.setMaxWidth(380);
    savedWorkspaceBar.getChildren().setAll(picker, save, reload, operation);
  }

  private VBox savedWorkspacesPanel() {
    VBox p =
        panel(
            label("SAVED METADATA WORKSPACES", "eyebrow"),
            label("Reopen the source. Stay offline.", "section-heading"));
    if (!workspaceState.error().isBlank())
      p.getChildren().add(notice("Saved workspaces unavailable", workspaceState.error()));
    if (workspaceState.workspaces().isEmpty()) {
      p.getChildren()
          .add(
              wrap(
                  workspaceState.busy()
                      ? "Loading app-owned metadata…"
                      : "No saved workspaces. Select a build below, then save its exact identity"
                          + " with a label and locale. Saving metadata does not retain game"
                          + " content."));
      return p;
    }
    TableView<Workspace> table =
        new TableView<>(FXCollections.observableArrayList(workspaceState.workspaces()));
    table.setId("saved-workspace-table");
    table.setAccessibleText("Saved exact-build metadata workspaces");
    table.getColumns().add(workspaceColumn("Workspace", Workspace::name, 200));
    table
        .getColumns()
        .add(
            workspaceColumn(
                "Product / exact build",
                w ->
                    w.build().productId()
                        + " / "
                        + w.build().channel()
                        + " / "
                        + w.build().region()
                        + " / "
                        + w.build().versionLabel(),
                290));
    table.getColumns().add(workspaceColumn("Locale", Workspace::locale, 85));
    table
        .getColumns()
        .add(
            workspaceColumn(
                "Source / coverage",
                w ->
                    (w.build().fixture() ? "Fictional fixture" : "Captured official metadata")
                        + " · metadata only",
                260));
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    sizeMetadataTable(table, workspaceState.workspaces().size());
    String selectedId =
        selectedSavedWorkspaceId != null
            ? selectedSavedWorkspaceId
            : activeWorkspace == null ? null : activeWorkspace.id();
    if (selectedId != null)
      workspaceState.workspaces().stream()
          .filter(w -> w.id().equals(selectedId))
          .findFirst()
          .ifPresent(w -> table.getSelectionModel().select(w));
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, old, w) -> {
              if (w != null) selectedSavedWorkspaceId = w.id();
            });
    Runnable open =
        () -> {
          Workspace w = table.getSelectionModel().getSelectedItem();
          if (w != null) openWorkspace(w);
        };
    table.setOnKeyPressed(
        e -> {
          if (e.getCode() == KeyCode.ENTER) {
            open.run();
            e.consume();
          }
        });
    table.setRowFactory(
        tv -> {
          TableRow<Workspace> row = new TableRow<>();
          row.setOnMouseClicked(
              e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) openWorkspace(row.getItem());
              });
          return row;
        });
    Button openButton = button("Reopen offline →", open, "primary");
    openButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
    Button inspect =
        button(
            "Inspect source",
            () -> {
              Workspace w = table.getSelectionModel().getSelectedItem();
              if (w != null) buildDetails(w.build(), w.locale());
            },
            "quiet-button");
    inspect.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
    Button rename =
        button(
            "Rename…",
            () -> {
              Workspace w = table.getSelectionModel().getSelectedItem();
              if (w != null) renameWorkspaceDialog(w);
            },
            "quiet-button");
    rename
        .disableProperty()
        .bind(
            table
                .getSelectionModel()
                .selectedItemProperty()
                .isNull()
                .or(new javafx.beans.property.SimpleBooleanProperty(workspaceState.busy())));
    Button remove =
        button(
            "Remove saved metadata…",
            () -> {
              Workspace w = table.getSelectionModel().getSelectedItem();
              if (w != null) removeWorkspaceDialog(w);
            },
            "quiet-button");
    remove
        .disableProperty()
        .bind(
            table
                .getSelectionModel()
                .selectedItemProperty()
                .isNull()
                .or(new javafx.beans.property.SimpleBooleanProperty(workspaceState.busy())));
    FlowPane actions = new FlowPane(8, 8, openButton, inspect, rename, remove);
    p.getChildren()
        .addAll(
            table,
            actions,
            wrap(
                "Each entry pins its original build/configuration identity and locale. Reopening"
                    + " does not contact the CDN or substitute a newer build. Game files and"
                    + " decoded records are not retained by this metadata save."));
    return p;
  }

  private void rememberWorkspaceView() {
    if (activeWorkspace != null)
      workspaceViews.put(
          activeWorkspace.id(),
          new WorkspaceView(
              destination,
              researchTopic,
              searchText,
              privateNote,
              archiveInstallMode,
              installQuery,
              selectedInstallEntry,
              archiveFindMode,
              lookupState == null ? "" : lookupState.input(),
              selectedFileVariant));
  }

  private void leaveSavedWorkspace() {
    rememberWorkspaceView();
    activeWorkspace = null;
    currentLocale = "enUS";
    researchTopic = "All research areas";
    searchText = "";
    privateNote = "";
    archiveInstallMode = false;
    installQuery = "";
    selectedInstallEntry = null;
    archiveFindMode = false;
    selectedFileVariant = null;
    if (lookupModel != null) lookupModel.setInput("");
  }

  private void openWorkspace(Workspace w) {
    rememberWorkspaceView();
    activeWorkspace = w;
    currentLocale = w.locale();
    WorkspaceView view = workspaceViews.get(w.id());
    destination = view == null ? "Research" : view.destination();
    researchTopic = view == null ? "All research areas" : view.topic();
    searchText = view == null ? "" : view.query();
    privateNote = view == null ? "" : view.note();
    archiveInstallMode = view != null && view.installMode();
    installQuery = view == null ? "" : view.installQuery();
    selectedInstallEntry = view == null ? null : view.installSelection();
    archiveFindMode = view != null && view.findMode();
    selectedFileVariant = view == null ? null : view.fileVariant();
    navigation.getToggles().forEach(t -> t.setSelected(destination.equals(t.getUserData())));
    model.restore(w.build());
    if (lookupModel != null) lookupModel.setInput(view == null ? "" : view.fileInput());
    Platform.runLater(
        () -> {
          Node picker = root.lookup("#saved-workspace-picker");
          if (picker != null) picker.requestFocus();
        });
  }

  private void saveWorkspaceDialog() {
    if (state.selected() == null || workspaceModel == null || workspaceState.busy()) return;
    BuildSnapshot captured = state.selected();
    TextField name =
        new TextField(
            activeWorkspace == null
                ? captured.versionLabel() + " research"
                : activeWorkspace.name());
    name.setId("workspace-name");
    name.setAccessibleText("Workspace label, 1 to 120 printable characters");
    TextField locale = new TextField(currentLocale);
    locale.setId("workspace-locale");
    locale.setAccessibleText("Workspace locale, such as enUS or deDE");
    Label validation = wrap("");
    validation.getStyleClass().add("validation-error");
    validation.setVisible(false);
    validation.setManaged(false);
    VBox body =
        new VBox(
            12,
            wrap(
                "Save this exact source identity in app-owned storage. No game content is"
                    + " downloaded and no latest pointer is resolved when reopening."),
            identity(
                "PINNED SOURCE",
                captured.productId() + " / " + captured.region() + " / " + captured.versionLabel()),
            field("LOCAL LABEL", name),
            field("LOCALE VIEW", locale),
            validation);
    Dialog<Void> dialog = editorDialog("Save metadata workspace", body);
    ButtonType save = new ButtonType("Save metadata", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
    dialog
        .getDialogPane()
        .lookupButton(save)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            e -> {
              try {
                String label = Workspace.validateName(name.getText());
                String selectedLocale = locale.getText().strip();
                if (!selectedLocale.matches("[a-z]{2}[A-Z]{2}"))
                  throw new IllegalArgumentException("Choose a locale such as enUS, enGB or deDE.");
                workspaceModel.save(captured, selectedLocale, label);
              } catch (IllegalArgumentException invalid) {
                e.consume();
                validation.setText(invalid.getMessage());
                validation.setVisible(true);
                validation.setManaged(true);
                if (!locale.getText().strip().matches("[a-z]{2}[A-Z]{2}")) locale.requestFocus();
                else name.requestFocus();
              }
            });
    dialog.setOnShown(e -> name.requestFocus());
    dialog.showAndWait();
  }

  private void renameWorkspaceDialog(Workspace w) {
    TextInputDialog dialog = new TextInputDialog(w.name());
    dialog.initOwner(stage);
    dialog.setTitle("Rename saved workspace");
    dialog.setHeaderText("Change the label, preserve the source.");
    dialog.setContentText("Local label:");
    dialog.getDialogPane().setStyle(root.getStyle());
    dialog
        .getDialogPane()
        .getStylesheets()
        .add(getClass().getResource("atlas.css").toExternalForm());
    Label validation = wrap("");
    dialog.getDialogPane().setExpandableContent(validation);
    dialog
        .getDialogPane()
        .lookupButton(ButtonType.OK)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            e -> {
              try {
                workspaceModel.rename(w.id(), Workspace.validateName(dialog.getEditor().getText()));
              } catch (IllegalArgumentException failure) {
                e.consume();
                validation.setText(failure.getMessage());
                dialog.getDialogPane().setExpanded(true);
              }
            });
    dialog.showAndWait();
  }

  private void removeWorkspaceDialog(Workspace w) {
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
    confirm.initOwner(stage);
    confirm.setTitle("Remove saved metadata workspace?");
    confirm.setHeaderText("Remove “" + w.name() + "” from saved workspaces?");
    confirm.setContentText(
        w.build().productId()
            + " / "
            + w.build().versionLabel()
            + " / "
            + w.locale()
            + "\n\n"
            + "Only this saved metadata membership is removed. No game payload is deleted; other"
            + " exact-build and locale views remain. The current source can remain open as an"
            + " unsaved view.");
    confirm.getDialogPane().setStyle(root.getStyle());
    confirm
        .getDialogPane()
        .getStylesheets()
        .add(getClass().getResource("atlas.css").toExternalForm());
    confirm
        .showAndWait()
        .filter(b -> b == ButtonType.OK)
        .ifPresent(b -> workspaceModel.remove(w.id()));
  }

  private Dialog<Void> editorDialog(String title, Node body) {
    Dialog<Void> dialog = new Dialog<>();
    dialog.initOwner(stage);
    dialog.setTitle(title);
    dialog.getDialogPane().setHeaderText(title);
    ScrollPane scroll = new ScrollPane(body);
    scroll.setFitToWidth(true);
    scroll.setMaxHeight(410);
    scroll.setMinHeight(0);
    dialog.getDialogPane().setContent(scroll);
    dialog.getDialogPane().setStyle(root.getStyle());
    dialog
        .getDialogPane()
        .getStylesheets()
        .add(getClass().getResource("atlas.css").toExternalForm());
    dialog.getDialogPane().setPrefWidth(620);
    dialog.setResizable(true);
    return dialog;
  }

  private void sizeMetadataTable(TableView<?> table, int count) {
    double scale = Integer.parseInt(selectedScale.replace("%", "")) / 100.0;
    double rowHeight = Math.max(43, 16 * scale + 16), headerHeight = Math.max(42, 13 * scale + 22);
    table.setFixedCellSize(rowHeight);
    table.setPrefHeight(headerHeight + Math.min(Math.max(count, 1), 5) * rowHeight + 2);
    table.setMinHeight(Region.USE_PREF_SIZE);
    table.setMaxHeight(Region.USE_PREF_SIZE);
  }

  private static TableColumn<Workspace, String> workspaceColumn(
      String title, Function<Workspace, String> value, double width) {
    TableColumn<Workspace, String> c = new TableColumn<>(title);
    c.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
    c.setPrefWidth(width);
    return c;
  }

  private void openSelection(TableView<BuildSnapshot> table) {
    BuildSnapshot b = table.getSelectionModel().getSelectedItem();
    if (b != null) openBuild(b);
  }

  private void openBuild(BuildSnapshot b) {
    leaveSavedWorkspace();
    model.select(b);
    buildDetails(b);
    Platform.runLater(
        () -> {
          Node table = root.lookup("#build-table");
          if (table != null) table.requestFocus();
        });
  }

  private void buildDetails(BuildSnapshot b) {
    buildDetails(
        b,
        state.selected() != null && state.selected().snapshotId().equals(b.snapshotId())
            ? currentLocale
            : null);
  }

  private void buildDetails(BuildSnapshot b, String locale) {
    VBox info =
        new VBox(
            12,
            notice(
                b.fixture() ? "Fictional source identity" : "Official metadata identity",
                b.fixture()
                    ? "Test fixture only; not a live Blizzard build."
                    : "Captured published metadata. Content availability remains unchecked."),
            identity("EDITION / PRODUCT", b.editionLabel() + " / " + b.productId()),
            identity("EXACT BUILD", b.versionLabel()),
            identity("CHANNEL / REGION", b.channel() + " / " + b.region()),
            identity("BUILD CONFIGURATION", b.buildConfig()),
            identity("CDN CONFIGURATION", b.cdnConfig()),
            identity("SNAPSHOT ID", b.snapshotId()),
            identity(
                "OBSERVED",
                DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z")
                    .withZone(ZoneId.systemDefault())
                    .format(b.observedAt())),
            wrap(
                "Coverage: metadata only. Files, decoded records, previews and complete offline"
                    + " archive availability are not established by this source."));
    if (locale != null) info.getChildren().add(1, identity("LOCALE VIEW", locale));
    info.getChildren().add(label("CAPTURED SOURCE EVIDENCE", "eyebrow"));
    if (b.evidence().documents().isEmpty())
      info.getChildren()
          .add(
              wrap(
                  "No source documents recorded for this fixture or legacy metadata. Configuration"
                      + " identity is retained; no document provenance is invented."));
    else
      for (var document : b.evidence().documents())
        info.getChildren()
            .add(
                new VBox(
                    6,
                    identity("SOURCE URL", document.sourceUrl()),
                    identity(
                        "SHA-256 / CAPTURED", document.sha256() + " / " + document.capturedAt())));
    if (!b.evidence().upstreamBuildId().isBlank())
      info.getChildren().add(identity("UPSTREAM BUILD ID", b.evidence().upstreamBuildId()));
    if (!b.evidence().productConfig().isBlank())
      info.getChildren().add(identity("PRODUCT CONFIGURATION", b.evidence().productConfig()));
    dialog("Build source · " + b.versionLabel(), info);
  }

  private VBox comparison() {
    return unavailable(
        "COMPARE BUILDS",
        "Put changes in context.",
        "A comparison needs two exact builds from the same edition, compatible schemas and verified"
            + " input coverage.",
        "Comparison engine not implemented",
        "No added, changed or removed records can be asserted yet. Missing or encrypted inputs will"
            + " be shown as not compared, rather than removed.",
        "Choose build metadata",
        () -> navigate("Archive"));
  }

  private VBox database() {
    if (itemModel != null) {
      syncItemContext();
      VBox page = new VBox(14);
      page.getStyleClass().add("workspace");
      if (namesModel != null) {
        Button physical =
            button(
                "Item physical rows",
                () -> {
                  namesMode = false;
                  render();
                },
                namesMode ? "quiet-button" : "primary");
        physical.setId("database-item-mode");
        Button names =
            button(
                "Name candidates",
                () -> {
                  namesMode = true;
                  render();
                },
                namesMode ? "primary" : "quiet-button");
        names.setId("database-names-mode");
        page.getChildren().add(new FlowPane(10, 8, physical, names));
      }
      if (namesMode && namesModel != null) {
        namesPane =
            new ItemNamesPane(
                namesModel,
                Integer.parseInt(selectedScale.replace("%", "")) / 100.0,
                this::inspectSparseSource,
                exportAvailable() ? this::exportNames : null);
        page.getChildren().add(namesPane);
        return page;
      }
      itemPane =
          new ItemProjectionPane(
              itemModel,
              Integer.parseInt(selectedScale.replace("%", "")) / 100.0,
              () -> {
                if (state.selected() != null) openFileLookup();
                else navigate("Archive");
              },
              exportAvailable() ? this::exportItem : null);
      page.getChildren().add(itemPane);
      if (itemState != null
          && itemState.source() == null
          && itemReadiness.historicalFor(state.selected()).isPresent()) {
        var historical = itemReadiness.historicalFor(state.selected()).get();
        TitledPane historicalPane =
            new TitledPane(
                "Historical Item source receipt · not current readiness",
                identity(
                    "ORIGINAL SELECTED-FILE RECEIPT",
                    historical.descriptor().association().build().versionLabel()
                        + " / FileDataID "
                        + historical.descriptor().association().fileDataId()
                        + "\nOriginal source: "
                        + historical.representation().sourceReceipt().actualResourceUrl()
                        + "\nOriginal observation: "
                        + historical.representation().sourceReceipt().observedAt()
                        + "\nPhysical SHA-256: "
                        + historical.representation().encoded().object().digest().value()));
        historicalPane.setExpanded(false);
        page.getChildren().add(historicalPane);
      }
      return page;
    }
    VBox page =
        page(
            "DATABASE WORKSPACE",
            "Records, before interpretation.",
            "Browse supported tables with raw values, readable fields and source-aware"
                + " relationships.");
    TextField filter = new TextField();
    filter.setPromptText("Filter supported tables…");
    filter.setAccessibleText("Filter supported tables");
    filter.setDisable(true);
    VBox list =
        panel(
            label("TABLE CATALOG", "eyebrow"),
            filter,
            wrap(
                "No decoded table catalog. Exact-build DB2 decoding is not implemented in this"
                    + " slice."));
    list.setPrefWidth(230);
    VBox table =
        panel(
            label("INDEX COVERAGE", "eyebrow"),
            label("Not indexed. Not searched.", "section-heading"),
            wrap(
                "An empty table here does not mean this build contains no records. Download,"
                    + " exact-schema decoding and indexing must be supported first."),
            button(
                "Inspect source scope",
                () -> {
                  if (state.selected() != null) buildDetails(state.selected());
                  else navigate("Archive");
                },
                "primary"));
    SplitPane split = new SplitPane(list, table);
    split.setDividerPositions(.27);
    split.setPrefHeight(340);
    page.getChildren().add(split);
    return page;
  }

  private VBox assets() {
    return unavailable(
        "ASSET WORKSPACE",
        "Look behind the references.",
        "Models, textures, audio, maps and localized text belong to an exact source build.",
        "Previews are staged capabilities",
        "Asset-file acquisition and decoders are not available here. Install-list inspection is"
            + " metadata only. No copyrighted game assets, simulated playback or fabricated model"
            + " results are bundled. Unknown names, encryption and unsupported formats will remain"
            + " distinct states.",
        "Inspect build source",
        () -> {
          if (state.selected() != null) buildDetails(state.selected());
          else navigate("Archive");
        });
  }

  private VBox notebook() {
    VBox page =
        page(
            "INVESTIGATION NOTEBOOK",
            "Keep the question with the evidence.",
            "Future investigations will preserve exact source links, notes, tags and comparison"
                + " scope.");
    TextArea note = new TextArea(privateNote);
    note.setPromptText("Draft a question for your next investigation…");
    note.setAccessibleText("Session draft research note");
    note.setPrefRowCount(6);
    note.textProperty().addListener((o, a, b) -> privateNote = b);
    page.getChildren()
        .add(
            panel(
                label("SESSION DRAFT · NOT SAVED TO DISK", "eyebrow"),
                note,
                wrap(
                    "This draft stays only while the application is running. Persistent"
                        + " investigations and evidence attachment are not implemented yet.")));
    page.getChildren()
        .add(
            notice(
                "No evidence attached",
                "Discovery metadata does not produce decoded findings. Save/reopen/export will"
                    + " become available with the investigation use cases."));
    return page;
  }

  private VBox activity() {
    VBox page =
        page(
            "BACKGROUND ACTIVITY",
            "Keep the work visible.",
            "Acquisition, verification and metadata operations run outside the JavaFX application"
                + " thread.");
    boolean any = false;
    if (lookupState != null && lookupState.busy()) {
      any = true;
      VBox job =
          busyPanel(
              "FileDataID lookup metadata", lookupState.message(), () -> lookupModel.cancel());
      job.lookupAll(".button").stream()
          .filter(n -> n instanceof Button)
          .map(n -> (Button) n)
          .forEach(button -> button.setText("Cancel request"));
      page.getChildren().add(job);
    }
    if (installState != null && installState.busy()) {
      any = true;
      VBox installJob =
          busyPanel("Install file list", installState.message(), () -> installModel.cancel());
      installJob.lookupAll(".button").stream()
          .filter(n -> n instanceof Button)
          .map(n -> (Button) n)
          .forEach(button -> button.setText("Cancel request"));
      page.getChildren().add(installJob);
    }
    if (productState != null && productState.busy()) {
      any = true;
      page.getChildren()
          .add(
              busyPanel(
                  "Official product summary",
                  "Reading published metadata pointers. Transfer bytes are not reported by this"
                      + " operation.",
                  () -> productModel.stopWaiting()));
    }
    if (state.busy()) {
      any = true;
      page.getChildren()
          .add(
              busyPanel(
                  "Official metadata discovery",
                  "Waiting for the application discovery service. Progress bytes are unknown for"
                      + " this operation.",
                  () -> model.stopWaiting()));
    }
    if (!any)
      page.getChildren()
          .add(
              panel(
                  label("NO ACTIVE UI REQUEST", "eyebrow"),
                  label("A quiet workspace.", "section-heading"),
                  wrap(
                      "Choose a source in Archive, or inspect an exact build's installation file"
                          + " list. No unrelated acquisition or export jobs are started"
                          + " automatically."),
                  button("Open archive →", () -> navigate("Archive"), "primary")));
    page.getChildren()
        .add(
            note(
                "CANCELLATION SCOPE",
                "Metadata discovery's Stop waiting detaches its view. Cancelling an install-list or"
                    + " lookup request stops only that request. Other requests using the same"
                    + " download may continue."));
    return page;
  }

  private VBox settings() {
    VBox page =
        page(
            "WORKSPACE PREFERENCES",
            "A careful local workspace.",
            "No game installations, Battle.net folders or game-generated caches are read.");
    ComboBox<String> scale =
        new ComboBox<>(FXCollections.observableArrayList("100%", "125%", "150%", "200%"));
    scale.setValue(selectedScale);
    scale.setAccessibleText("Application text scale");
    scale.setOnAction(
        e -> {
          selectedScale = scale.getValue();
          String value = scale.getValue().replace("%", "");
          root.setStyle("-fx-font-size: " + (14 * Integer.parseInt(value) / 100.0) + "px;");
        });
    page.getChildren()
        .add(
            panel(
                label("APPEARANCE", "eyebrow"),
                label("Atlas · ivory & teal", "section-heading"),
                wrap(
                    "Standard JavaFX controls preserve keyboard focus and native accessibility"
                        + " semantics. Text scaling applies to this session."),
                field("TEXT SCALE", scale)));
    page.getChildren()
        .add(
            panel(
                label("STORAGE POLICY", "eyebrow"),
                wrap(
                    "App-owned content-addressed storage. Build manifests reference shared verified"
                        + " objects; archived and cached content are retention states over the same"
                        + " data. Physical usage and reclaimable bytes are not yet measured."),
                notice(
                    "Storage management not implemented",
                    "No storage location chooser, quota change or deletion is performed by this"
                        + " shell.")));
    return page;
  }

  private void showSearch() {
    if (configuredBuildLibrary != null) return;
    destination = "Search";
    navigation.getToggles().forEach(t -> t.setSelected(false));
    render();
  }

  private VBox search() {
    VBox page =
        page(
            "FIND / INSPECT / INVESTIGATE",
            "What are you looking for?",
            researchTopic
                + " · results require supported, indexed content in your selected build.");
    TextField query = new TextField(searchText);
    query.setPromptText("Search names, record IDs, tables or paths…");
    query.setAccessibleText("Research search query");
    Label feedback =
        wrap(
            "Not searched: no decoded records are indexed. This is distinct from a search with no"
                + " matching results.");
    query
        .textProperty()
        .addListener(
            (o, a, b) -> {
              searchText = b;
              feedback.setText(
                  b.isBlank()
                      ? "Not searched: no decoded records are indexed."
                      : "Query retained: “"
                          + b
                          + "”. Not searched: exact-build decoding and indexing are not available"
                          + " yet.");
            });
    page.getChildren()
        .add(panel(label("QUERY / " + researchTopic.toUpperCase(), "eyebrow"), query, feedback));
    page.getChildren()
        .add(
            notice(
                state.selected() == null ? "No workspace selected" : "Coverage: metadata only",
                state.selected() == null
                    ? "Choose an exact build before running a scoped investigation."
                    : "Selected: "
                        + state.selected().versionLabel()
                        + " / "
                        + state.selected().region()
                        + ". Metadata contains no searchable item, spell or asset records."));
    page.getChildren().add(button("Choose build scope →", () -> navigate("Archive"), "primary"));
    Platform.runLater(query::requestFocus);
    return page;
  }

  private VBox unavailable(
      String eyebrow,
      String title,
      String sub,
      String heading,
      String explanation,
      String action,
      Runnable run) {
    VBox page = page(eyebrow, title, sub);
    page.getChildren()
        .add(
            panel(
                label("CAPABILITY NOT IMPLEMENTED", "eyebrow"),
                label(heading, "section-heading"),
                wrap(explanation),
                button(action, run, "primary")));
    return page;
  }

  private void boundaries() {
    dialog(
        "Available capabilities",
        new VBox(
            14,
            wrap(
                "Available: published product/build metadata, exact source inspection and saved"
                    + " metadata workspaces. Install-list support, when enabled, acquires only the"
                    + " explicitly reviewed list object."),
            wrap(
                "Not yet available: game-asset archives, listed-file downloads, DB2 decoding,"
                    + " indexed research, comparison, investigation persistence or previews."
                    + " Disabled or explanatory states do not represent completed mining"
                    + " capabilities."),
            wrap("No local game installation or cache import is used.")));
  }

  private void dialog(String title, Node node) {
    Dialog<Void> dialog = new Dialog<>();
    dialog.initOwner(stage);
    dialog.setTitle(title);
    dialog.getDialogPane().setHeaderText(title);
    ScrollPane scroll = new ScrollPane(node);
    scroll.setFitToWidth(true);
    scroll.setPrefViewportHeight(510);
    scroll.setStyle("-fx-background-color: transparent;");
    dialog.getDialogPane().setContent(scroll);
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    dialog.getDialogPane().setStyle(root.getStyle());
    dialog
        .getDialogPane()
        .getStylesheets()
        .add(Objects.requireNonNull(getClass().getResource("atlas.css")).toExternalForm());
    dialog.getDialogPane().setPrefWidth(690);
    dialog.setResizable(true);
    dialog.showAndWait();
  }

  private VBox page(String eyebrow, String title, String subtitle) {
    VBox page = new VBox(18);
    page.getStyleClass().add("workspace");
    Label sub = wrap(subtitle);
    sub.getStyleClass().add("subtitle");
    page.getChildren()
        .addAll(new VBox(8, label(eyebrow, "eyebrow"), label(title, "page-heading"), sub));
    return page;
  }

  private VBox panel(Node... nodes) {
    VBox p = new VBox(14, nodes);
    p.getStyleClass().add("panel");
    return p;
  }

  private VBox note(String title, String text) {
    VBox box = new VBox(7, label(title, "eyebrow"), wrap(text));
    box.getStyleClass().add("note");
    return box;
  }

  private VBox notice(String title, String text) {
    VBox box = new VBox(5, label(title, "notice-heading"), wrap(text));
    box.getStyleClass().add("notice");
    return box;
  }

  private VBox busyPanel(String title, String text, Runnable cancel) {
    ProgressIndicator indicator = new ProgressIndicator();
    indicator.setMaxSize(26, 26);
    HBox row =
        new HBox(
            12,
            indicator,
            label(title, "section-heading"),
            spacer(),
            button("Stop waiting", cancel, "quiet-button"));
    row.setAlignment(Pos.CENTER_LEFT);
    return panel(row, wrap(text));
  }

  private HBox step(String n, String title, String text) {
    Label number = label(n, "step-number");
    VBox words = new VBox(4, label(title, "topic-title"), wrap(text));
    HBox row = new HBox(16, number, words);
    return row;
  }

  private VBox field(String title, Control control) {
    return new VBox(6, label(title, "eyebrow"), control);
  }

  private VBox identity(String title, String value) {
    TextArea text = new TextArea(value);
    text.setEditable(false);
    text.setWrapText(true);
    text.setPrefRowCount(1);
    text.setAccessibleText(title);
    text.getStyleClass().add("identity-value");
    return new VBox(4, label(title, "eyebrow"), text);
  }

  private static Label label(String text, String style) {
    Label l = new Label(text);
    l.getStyleClass().add(style);
    if (style.equals("section-heading") || style.equals("page-heading")) {
      l.setWrapText(true);
      l.setMinWidth(0);
    }
    return l;
  }

  private static Label wrap(String text) {
    Label l = new Label(text);
    l.setWrapText(true);
    l.getStyleClass().add("body-copy");
    l.setMaxWidth(Double.MAX_VALUE);
    return l;
  }

  private static Region spacer() {
    Region r = new Region();
    HBox.setHgrow(r, Priority.ALWAYS);
    return r;
  }

  private static Button button(String title, Runnable action, String style) {
    Button b = new Button(title);
    b.getStyleClass().add(style);
    b.setOnAction(e -> action.run());
    return b;
  }

  private static TableColumn<BuildSnapshot, String> column(
      String title, Function<BuildSnapshot, String> value, double width) {
    TableColumn<BuildSnapshot, String> c = new TableColumn<>(title);
    c.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
    c.setPrefWidth(width);
    return c;
  }

  private boolean exportAvailable() {
    return configuredEvidence != null && evidenceChooser != null;
  }

  private void exportItem(
      io.vibeminer.domain.item.PreparedItemProjection h,
      io.vibeminer.domain.item.ItemPhysicalRow row) {
    if (!exportAvailable() || itemState == null || !h.equals(itemState.handle())) return;
    var pair = namesState == null ? null : namesState.pair();
    openEvidence(
        h,
        row.recordId(),
        pair != null && pair.item().equals(h)
            ? java.util.Optional.of(pair)
            : java.util.Optional.empty(),
        false);
  }

  private void exportNames(
      io.vibeminer.domain.item.PreparedItemNames pair, io.vibeminer.domain.item.NamedItemRow row) {
    if (!exportAvailable() || namesState == null || !pair.equals(namesState.pair())) return;
    openEvidence(pair.item(), row.item().recordId(), java.util.Optional.of(pair), true);
  }

  private void openEvidence(
      io.vibeminer.domain.item.PreparedItemProjection h,
      long id,
      java.util.Optional<io.vibeminer.domain.item.PreparedItemNames> pair,
      boolean fromNames) {
    if (evidenceDialog != null && evidenceDialog.isShowing()) return;
    exportItem = h;
    exportPair = fromNames ? pair.orElseThrow() : null;
    evidenceDialog =
        new ItemEvidenceExportDialog(
            stage,
            configuredEvidence,
            evidenceChooser,
            h,
            id,
            pair,
            Integer.parseInt(selectedScale.replace("%", "")) / 100.0,
            this::onExportEvidenceInvalidated);
    evidenceDialog.setContextValidation(this::refreshExportContext);
    evidenceDialog.show();
  }

  private static boolean sameExportItem(
      io.vibeminer.domain.item.PreparedItemProjection a,
      io.vibeminer.domain.item.PreparedItemProjection b) {
    if (a == null || b == null) return false;
    return a.definition().equals(b.definition())
        && a.coverage().equals(b.coverage())
        && a.evidence().association().equals(b.evidence().association())
        && a.evidence().chunks().equals(b.evidence().chunks())
        && a.evidence().windows().equals(b.evidence().windows())
        && a.evidence().fullDecoded().equals(b.evidence().fullDecoded())
        && a.evidence()
            .localVerification()
            .object()
            .equals(b.evidence().localVerification().object())
        && a.evidence()
            .localVerification()
            .verifierRevision()
            .equals(b.evidence().localVerification().verifierRevision());
  }

  private void onExportEvidenceInvalidated(
      io.vibeminer.application.itemexport.ItemEvidenceSelection selection) {
    if (state == null
        || state.selected() == null
        || !selection.item().snapshotId().equals(state.selected().snapshotId())
        || !selection.item().locale().equals(currentLocale)) return;
    if (selection.names().isPresent()) {
      if (namesState == null || !selection.names().get().equals(namesState.pair())) return;
      tableSources.invalidate(selection.item().snapshotId(), 1572924);
      if (namesModel != null) namesModel.invalidateDependency(selection.item().snapshotId());
    } else {
      if (itemState == null || !sameExportItem(selection.item(), itemState.handle())) return;
      tableSources.invalidate(selection.item().snapshotId(), 841626);
      itemReadiness.requiresVerification(selection.item().evidence().association());
      if (namesModel != null) namesModel.invalidateDependency(selection.item().snapshotId());
      if (itemModel != null) itemModel.invalidateForRepair(selection.item().snapshotId());
    }
    refreshContextStripe();
  }

  private void refreshExportContext() {
    if (evidenceDialog == null || !evidenceDialog.isShowing()) return;
    boolean valid =
        state != null
            && state.selected() != null
            && exportItem.snapshotId().equals(state.selected().snapshotId())
            && exportItem.locale().equals(currentLocale);
    var selectedPair = evidenceDialog.state().selection().names();
    valid &=
        selectedPair.isPresent()
            ? namesState != null && selectedPair.get().equals(namesState.pair())
            : itemState != null && sameExportItem(exportItem, itemState.handle());
    if (!valid) evidenceDialog.invalidateContext();
  }

  @Override
  public void stop() {
    if (evidenceDialog != null) evidenceDialog.close();
    if (model != null) model.close();
    if (guidedModel != null) guidedModel.close();
    if (openingPoll != null) openingPoll.stop();
    if (openingModel != null) openingModel.close();
    if (workspaceModel != null) workspaceModel.close();
    if (productModel != null) productModel.close();
    if (installModel != null) installModel.close();
    if (lookupModel != null) lookupModel.close();
    if (selectedModel != null) selectedModel.close();
    if (retentionModel != null) retentionModel.close();
    if (itemModel != null) itemModel.close();
    if (rootReuseModel != null) rootReuseModel.close();
    if (installReuseModel != null) installReuseModel.close();
    if (namesModel != null) namesModel.close();
    if (rootRepairModel != null) rootRepairModel.close();
    if (installRepairModel != null) installRepairModel.close();
  }
}
