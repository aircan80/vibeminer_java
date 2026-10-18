package io.vibeminer.desktop;

import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.WorkspaceService;
import io.vibeminer.application.ProductDiscoveryService;
import io.vibeminer.domain.PublishedProduct;
import io.vibeminer.domain.ProductPointer;
import io.vibeminer.domain.Workspace;
import io.vibeminer.domain.BuildSnapshot;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Native Atlas shell. All discovery and data access are delegated to application ports. */
public final class AtlasApplication extends Application {
    private static DiscoveryService configuredService;
    private static boolean initialFixtureMode;
    private static WorkspaceService configuredWorkspaces;
    private static ProductDiscoveryService configuredProducts;
    public static synchronized void configure(DiscoveryService service, boolean fixtureMode) {
        configure(service,null,fixtureMode);
    }
    public static synchronized void configure(DiscoveryService service, WorkspaceService workspaces, boolean fixtureMode) {
        configure(service,workspaces,null,fixtureMode);
    }
    public static synchronized void configure(DiscoveryService service, WorkspaceService workspaces, ProductDiscoveryService products, boolean fixtureMode) {
        if (configuredService != null) throw new IllegalStateException("Desktop has already been configured");
        configuredService = Objects.requireNonNull(service);
        initialFixtureMode = fixtureMode;
        configuredWorkspaces = workspaces;
        configuredProducts = products;
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
    private ProductPresentationModel productModel;
    private ProductPresentationModel.State productState;
    private String productFilter="";
    private WorkspacePresentationModel workspaceModel;
    private WorkspacePresentationModel.State workspaceState;
    private Workspace activeWorkspace;
    private String selectedSavedWorkspaceId;
    private FlowPane savedWorkspaceBar;
    private VBox workspaceFeedback;
    private String currentLocale = "enUS";
    private final java.util.Map<String,WorkspaceView> workspaceViews = new java.util.HashMap<>();
    private record WorkspaceView(String destination,String topic,String query,String note) {}
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
        {"02", "Classes & combat", "Spells, talents & tuning", "What changed in spell and talent values?"},
        {"03", "Crafting", "Recipes, reagents & professions", "Which recipes and reagent requirements changed?"},
        {"04", "Story & world", "Quests, dialogue & places", "What world and quest references were added?"},
        {"05", "Art & sound", "Models, textures & audio", "Which assets and sound IDs can I investigate?"},
        {"06", "Developer data", "IDs, tables & relationships", "How do these records connect?"}
    };

    @Override public void start(Stage stage) {
        if (configuredService == null) throw new IllegalStateException("Bootstrap must configure discovery before launch");
        this.stage = stage;
        root = new BorderPane();
        content = new StackPane(); content.getStyleClass().add("content-host");
        root.setCenter(content);
        chrome = new VBox();
        chrome.getChildren().addAll(header(), navigationBar());
        context = new FlowPane(16, 8); context.getStyleClass().add("context-strip");
        chrome.getChildren().add(context);
        savedWorkspaceBar=new FlowPane(10,8);savedWorkspaceBar.getStyleClass().add("saved-workspace-bar");
        workspaceFeedback=new VBox();workspaceFeedback.setManaged(false);workspaceFeedback.setVisible(false);
        if(configuredWorkspaces!=null)chrome.getChildren().addAll(savedWorkspaceBar,workspaceFeedback);root.setTop(chrome);
        status = new Label(); status.getStyleClass().add("status-text");
        HBox footer = new HBox(12, label("●", "connection-dot"), status, spacer(), label("CDN only  /  App-owned storage", "secondary"));
        footer.setAlignment(Pos.CENTER_LEFT); footer.getStyleClass().add("footer"); root.setBottom(footer);
        Scene scene = new Scene(root, 1440, 1000);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("atlas.css")).toExternalForm());
        scene.setOnKeyPressed(event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.K) {
                showSearch(); event.consume();
            }
        });
        stage.setTitle("VibeMiner · Atlas research workbench"); stage.setMinWidth(940); stage.setMinHeight(650);
        stage.setScene(scene);
        if(configuredProducts!=null){productModel=new ProductPresentationModel(configuredProducts,Platform::runLater,this::onProductState);productState=productModel.state();}
        if(configuredWorkspaces!=null){
            workspaceModel=new WorkspacePresentationModel(configuredWorkspaces,Platform::runLater,this::onWorkspaceState);
            workspaceState=workspaceModel.state();
        }
        model = new DiscoveryPresentationModel(configuredService, Platform::runLater, this::onState, initialFixtureMode);
        state = model.state();
        if (initialFixtureMode) model.showFixtures(); else onState(state);
        stage.show();
        if(workspaceModel!=null)workspaceModel.load();
        if (getParameters().getRaw().contains("--smoke-test")) {
            PauseTransition pause = new PauseTransition(Duration.millis(650));
            pause.setOnFinished(event -> { System.out.println("VibeMiner UI smoke OK"); Platform.exit(); });
            pause.play();
        }
    }

    private Node header() {
        Label mark = label("◈", "brand-mark");
        VBox brand = new VBox(label("VibeMiner", "brand"), label("ATLAS RESEARCH WORKBENCH", "brand-subtitle"));
        mode = label("", "mode-badge");
        Button search = button("Search workspace", this::showSearch, "quiet-button");
        search.setAccessibleText("Search workspace, shortcut Control or Command K");
        Button settings = button("Settings", () -> navigate("Settings"), "quiet-button");
        FlowPane actions=new FlowPane(10,8,mode,search,settings);actions.setAlignment(Pos.CENTER_RIGHT);actions.setPrefWidth(460);actions.setMinWidth(0);
        HBox header = new HBox(12, mark, brand, spacer(), actions);
        header.setAlignment(Pos.CENTER_LEFT); header.getStyleClass().add("header"); return header;
    }
    private Node navigationBar() {
        FlowPane nav = new FlowPane(4, 4); nav.getStyleClass().add("navigation");
        for (String name : List.of("Research", "Archive", "Compare", "Database", "Assets", "Notebook", "Activity")) {
            ToggleButton b = new ToggleButton(name); b.setToggleGroup(navigation); b.setUserData(name);
            b.getStyleClass().add("nav-button"); b.setSelected(name.equals(destination));
            b.setOnAction(e -> navigate(name)); nav.getChildren().add(b);
        }
        return nav;
    }
    private void onState(DiscoveryPresentationModel.State next) {
        state = next;
        mode.setText(next.fixtureMode() ? "FICTIONAL FIXTURE MODE" : "OFFICIAL METADATA DISCOVERY");
        context.getChildren().clear();
        BuildSnapshot b = next.selected();
        context.getChildren().addAll(contextItem("GAME / EDITION", b == null ? "No workspace selected" : b.editionLabel().replace("World of Warcraft", "WoW")),
                contextItem("PRODUCT ID", b == null ? "Not selected" : b.productId()),
                contextItem("CHANNEL / REGION", b == null ? "Not selected" : b.channel()+" / "+b.region()),
                contextItem("EXACT BUILD", b == null ? "Not selected" : b.versionLabel()),
                contextItem("LOCALE", b == null ? "Not selected" : currentLocale+" · view preference"),
                contextItem("COVERAGE", b == null ? "No data loaded" : "Metadata only"),
                contextItem("CONTENT", b == null ? "Not checked" : "Unverified"));
        Button change = button(b == null ? "Choose a build →" : "Change build", () -> navigate("Archive"), "context-action");
        context.getChildren().add(change);
        updateSavedWorkspaceBar();
        status.setText(next.busy() ? "Discovery in progress · interface remains available" : next.message());
        render();
    }
    private VBox contextItem(String title, String value) {
        Label text = label(value, "context-value"); text.setWrapText(false); text.setMaxWidth(Double.MAX_VALUE); text.setMinHeight(Region.USE_PREF_SIZE);
        VBox box = new VBox(3, label(title, "eyebrow"), text); box.setMinHeight(Region.USE_PREF_SIZE); box.setAccessibleText(title+": "+value); return box;
    }
    private void navigate(String name) {
        destination = name;
        navigation.getToggles().forEach(t -> t.setSelected(name.equals(t.getUserData())));
        render();
    }
    private void render() {
        if (state == null) return;
        Node node = switch (destination) {
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
        ScrollPane scroll = new ScrollPane(node); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("workspace-scroll"); content.getChildren().setAll(scroll);
    }
    private VBox research() {
        VBox page = page("RESEARCH WORKSPACE", "Follow the evidence.", "Start with a question. Keep every answer tied to an exact build.");
        HBox heading = new HBox(16, label("What are you looking for?", "section-heading"), spacer(), button("Browse build archive →", () -> navigate("Archive"), "primary"));
        heading.setAlignment(Pos.CENTER_LEFT);
        GridPane cards = new GridPane(); cards.setHgap(12); cards.setVgap(10);
        java.util.ArrayList<Button> topicButtons=new java.util.ArrayList<>();
        for (int i=0;i<topics.length;i++) {
            String[] t = topics[i];
            Label topicTitle=label(t[0]+" / "+t[1],"topic-title");topicTitle.setWrapText(true);
            Label topicDescription=label(t[2],"secondary");topicDescription.setWrapText(true);
            VBox words=new VBox(4,topicTitle,topicDescription);words.setAlignment(Pos.CENTER_LEFT);
            Button card=new Button();card.setGraphic(words);card.setMaxWidth(Double.MAX_VALUE);card.getStyleClass().add("topic-card");
            words.prefWidthProperty().bind(card.widthProperty().subtract(28));
            words.maxWidthProperty().bind(card.widthProperty().subtract(28));
            double topicScale=Integer.parseInt(selectedScale.replace("%",""))/100.0;
            card.setPrefHeight(68*topicScale);card.setMinHeight(Region.USE_PREF_SIZE);card.setMaxHeight(Region.USE_PREF_SIZE);
            card.setAccessibleText(t[1]+". "+t[2]+". "+t[3]);
            card.setOnAction(e->{researchTopic=t[1];searchText="";showSearch();});
            card.setMinWidth(0);topicButtons.add(card);cards.add(card,i%3,i/3);GridPane.setHgrow(card,Priority.ALWAYS);
        }
        for(int i=0;i<3;i++){ColumnConstraints c=new ColumnConstraints();c.setPercentWidth(100d/3);cards.getColumnConstraints().add(c);}
        cards.widthProperty().addListener((observable,oldWidth,newWidth)->{
            double scaleFactor=Integer.parseInt(selectedScale.replace("%",""))/100.0;
            int cols=newWidth.doubleValue()<1020*scaleFactor?2:3;
            if(cards.getColumnConstraints().size()!=cols){
                cards.getColumnConstraints().clear();
                for(int i=0;i<cols;i++){ColumnConstraints c=new ColumnConstraints();c.setPercentWidth(100d/cols);cards.getColumnConstraints().add(c);}
                for(int i=0;i<topicButtons.size();i++){GridPane.setColumnIndex(topicButtons.get(i),i%cols);GridPane.setRowIndex(topicButtons.get(i),i/cols);}
            }
        });
        VBox launcher=panel(heading,cards);launcher.getStyleClass().add("research-launcher");page.getChildren().add(launcher);
        VBox left=panel(label("YOUR NEXT STEP", "eyebrow"),label(state.selected()==null?"Choose the evidence first.":"A precise starting point.","section-heading"),
                wrap(state.selected()==null?"Select a published build or a clearly labelled fixture. Research results will only appear when its required tables are supported and indexed.":"Your selected build has discovery metadata. Its tables and files have not been acquired or decoded; no research findings are being inferred from metadata."),
                button(state.selected()==null?"Select a build →":"Inspect selected source →",()->{if(state.selected()==null)navigate("Archive");else buildDetails(state.selected());},"primary"));
        left.getChildren().add(new Separator());
        left.getChildren().addAll(label("A disciplined research workflow","section-heading"),
                step("01","Find","Search names, stable IDs or supported tables within a selected scope."),
                step("02","Inspect","Follow raw fields, related records and their source provenance."),
                step("03","Investigate","Preserve questions and exact evidence before drawing conclusions."));
        VBox right=panel(label("FIELD NOTES","eyebrow"),label("Evidence, with context.","section-heading"),
                wrap("A new client record does not establish release timing, obtainability or server behavior."),
                note("SOURCE DISCIPLINE","Blizzard content and community interpretation are different sources. A future inspector will identify schema and naming providers separately."),
                label("AVAILABLE IN THIS FIRST SLICE","eyebrow"),wrap("Published build metadata, pinned source identity and an asynchronous discovery workflow."),
                button("View available capabilities",this::boundaries,"quiet-button"));
        HBox body=new HBox(16,left,right);HBox.setHgrow(left,Priority.ALWAYS);left.setMaxWidth(Double.MAX_VALUE);right.setPrefWidth(350);right.setMinWidth(280);
        page.getChildren().add(body);return page;
    }
    private VBox archive() {
        VBox page=page("CDN SOURCES / BUILD ARCHIVE","Choose a build. Keep its identity.","Discovery lists published pointers. It does not guarantee that historical content remains available.");
        ComboBox<String> product=new ComboBox<>();product.setId("discovery-product");product.setPrefWidth(240);product.setPromptText("Refresh products first");product.setAccessibleText("Discovered raw official product identifier");
        ComboBox<String> region=new ComboBox<>(FXCollections.observableArrayList("us","eu","kr","tw"));region.setId("discovery-region");region.setValue(selectedRegion);region.valueProperty().addListener((o,a,b)->selectedRegion=b);region.setAccessibleText("Discovery region");
        Button refresh=button("Check official metadata",()->{leaveSavedWorkspace();model.refresh(product.getValue(),region.getValue());},"primary");refresh.setId("check-build-metadata");
        Label pointerStatus=wrap("");
        Runnable selectionChanged=()->{
            selectedProduct=product.getValue();
            boolean supported=productModel!=null?productModel.canRequestCurrent(selectedProduct):state.fixtureMode()&&selectedProduct!=null;
            refresh.setDisable(state.busy()||!supported);
            PublishedProduct selected=publishedProduct(selectedProduct);
            pointerStatus.setText(selected==null?(productModel==null?"Fixture IDs only; official product discovery is unavailable in this test configuration.":"Choose a raw product ID. No game files are downloaded."):pointerSummary(selected)+" · Content and decoder support unverified.");
        };
        updateProductItems(product);product.valueProperty().addListener((o,a,b)->selectionChanged.run());selectionChanged.run();
        product.setCellFactory(list->new ListCell<>(){protected void updateItem(String value,boolean empty){super.updateItem(value,empty);PublishedProduct p=publishedProduct(value);setText(empty?null:value+(p==null?"":" · "+pointerSummary(p)));setTooltip(empty?null:new Tooltip(getText()));}});
        Label matchingCount=label(productCountText(),"secondary");
        TextField filter=new TextField(productFilter);filter.setId("product-filter");filter.setPromptText("Filter discovered product IDs…");filter.setAccessibleText("Filter discovered raw product identifiers");filter.setPrefWidth(280);
        filter.textProperty().addListener((o,a,b)->{productFilter=b;updateProductItems(product);matchingCount.setText(productCountText());selectionChanged.run();});
        Button refreshProducts=button("Refresh products",()->productModel.refresh(),"quiet-button");refreshProducts.setId("refresh-products");refreshProducts.setDisable(productModel==null||productState.busy());
        Button pointers=button("Inspect published pointers",()->{PublishedProduct p=publishedProduct(product.getValue());if(p!=null)productDetails(p);},"quiet-button");pointers.disableProperty().bind(product.valueProperty().isNull().or(new javafx.beans.property.SimpleBooleanProperty(productModel==null)));
        FlowPane catalogActions=new FlowPane(10,8,refreshProducts,filter,pointers);
        VBox sources=panel(label("OFFICIAL PRODUCT SUMMARY / BLIZZARD CDN","eyebrow"),catalogActions);sources.setSpacing(8);sources.getStyleClass().add("discovery-sources");
        if(productState!=null){
            if(productState.busy())sources.getChildren().add(busyPanel("Checking published products…","This does not fetch game content or change your selected exact-build workspace.",()->productModel.stopWaiting()));
            else if(!productState.error().isBlank())sources.getChildren().add(notice("Product discovery unavailable",productState.error()+" "+productState.message()));
            sources.getChildren().add(matchingCount);
        }
        FlowPane actions=new FlowPane(10,8,field("PRODUCT ID",product),field("REGION",region),refresh,button("Open fictional fixtures",()->{leaveSavedWorkspace();model.showFixtures();},"quiet-button"));actions.setAlignment(Pos.BOTTOM_LEFT);
        sources.getChildren().addAll(actions,pointerStatus);
        if(workspaceModel!=null&&(!workspaceState.workspaces().isEmpty()||workspaceState.busy()||!workspaceState.error().isBlank()))page.getChildren().add(savedWorkspacesPanel());
        page.getChildren().add(sources);
        if(state.busy())page.getChildren().add(busyPanel("Checking published metadata…","You can navigate while this request runs. Stopping observation prevents late results from entering your workspace.",()->model.stopWaiting()));
        if(!state.error().isBlank())page.getChildren().add(notice("Discovery unavailable",state.error()));
        if(state.fixtureMode())page.getChildren().add(label("FICTIONAL FIXTURES · These builds and configuration IDs are test examples, not Blizzard findings.","fixture-inline"));
        TableView<BuildSnapshot> table=new TableView<>(FXCollections.observableArrayList(state.builds()));
        table.setId("build-table");
        table.setAccessibleText("Published build metadata. Select a build and press Enter to open its workspace.");
        table.getColumns().add(column("Edition / product",b->b.editionLabel()+" / "+b.productId(),190));
        table.getColumns().add(column("Exact version",BuildSnapshot::versionLabel,180));
        table.getColumns().add(column("Channel / region",b->b.channel()+" / "+b.region(),160));
        table.getColumns().add(column("Source",b->b.fixture()?"Fictional fixture":"Official discovery",160));
        table.getColumns().add(column("Coverage",b->"Metadata only",155));
        if(state.selected()!=null)table.getSelectionModel().select(state.selected());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);table.setPrefHeight(255);
        table.setPlaceholder(wrap(state.busy()?"Loading metadata…":"No build metadata loaded. Choose an official source or open fictional fixtures."));
        table.setOnKeyPressed(e->{if(e.getCode()==KeyCode.ENTER){openSelection(table);e.consume();}});
        table.setRowFactory(tv->{TableRow<BuildSnapshot> row=new TableRow<>();row.setOnMouseClicked(e->{if(e.getClickCount()==2&&!row.isEmpty()){openBuild(row.getItem());}});return row;});
        Button open=button("Open metadata workspace →",()->openSelection(table),"primary");open.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        Button inspect=button("Inspect identity",()->{BuildSnapshot b=table.getSelectionModel().getSelectedItem();if(b!=null)buildDetails(b);},"quiet-button");inspect.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        page.getChildren().add(panel(label("PUBLISHED / FIXTURE BUILDS","eyebrow"),table,new HBox(10,open,inspect)));
        page.getChildren().add(note("ARCHIVE ACQUISITION · NOT IMPLEMENTED YET","This slice discovers metadata. Downloading historical content, pinning selected scopes and reference-safe cleanup will follow. Identical objects will be shared by build manifests; no duplicate per-build cache is planned. Storage byte totals are unknown until acquisition is implemented."));return page;
    }
    private void onProductState(ProductPresentationModel.State next){
        productState=next;status.setText(next.error().isBlank()?next.message():next.error());
        if(destination.equals("Archive")){
            Node focused=stage.getScene().getFocusOwner();String focusId=focused==null?null:focused.getId();
            render();if(focusId!=null)Platform.runLater(()->{Node restored=root.lookup("#"+focusId);if(restored!=null)restored.requestFocus();});
        }else if(destination.equals("Activity"))render();
    }
    private String productCountText(){return productState==null||productState.catalogue()==null?"No product catalog loaded · Refresh products explicitly to begin.":productModel.matching(productFilter).size()+" matching raw IDs / "+productState.catalogue().products().size()+" published · captured "+productState.catalogue().evidence().capturedAt();}
    private PublishedProduct publishedProduct(String id){return productModel==null||id==null?null:productModel.matching("").stream().filter(p->p.productId().equals(id)).findFirst().orElse(null);}
    private void updateProductItems(ComboBox<String> picker){
        List<String> ids=productModel!=null?productModel.matching(productFilter).stream().map(PublishedProduct::productId).toList():state.fixtureMode()?state.builds().stream().map(BuildSnapshot::productId).distinct().filter(id->id.contains(productFilter)).toList():List.of();
        String prior=selectedProduct;
        picker.getItems().setAll(ids);
        if(prior!=null&&ids.contains(prior))picker.setValue(prior);
        else if(productModel==null&&!ids.isEmpty())picker.setValue(ids.getFirst());
        else{picker.setValue(null);selectedProduct=null;}
        picker.setPlaceholder(wrap(productState==null||productState.catalogue()==null?"No product catalog loaded. Refresh products first.":"No matching raw product IDs. Clear the filter."));
    }
    private String pointerSummary(PublishedProduct p){
        var labels=new java.util.ArrayList<String>();
        if(p.hasCurrentVersions())labels.add("current versions");if(p.hasCdnMetadata())labels.add("CDN metadata");if(p.hasAdvertisedPreload())labels.add("preload advertised");
        if(p.pointers().stream().anyMatch(pointer->pointer.kind()==ProductPointer.Kind.UNKNOWN))labels.add("unrecognized flags");
        return String.join(" / ",labels);
    }
    private void productDetails(PublishedProduct p){
        VBox body=new VBox(12,label(p.productId(),"section-heading"),wrap("Raw published product ID. Edition mapping and decoding capabilities are not established by the product name."));
        for(var pointer:p.pointers())body.getChildren().add(identity(pointer.kind().name(),"Flag: "+(pointer.flag().isEmpty()?"(empty: current versions)":pointer.flag())+" · Publication sequence: "+pointer.sequence()));
        body.getChildren().add(wrap("Preload is advertised metadata only; this interface does not request preload builds. A CDN pointer is not evidence of a downloadable complete build. Unknown flags are preserved without interpretation."));
        if(productState!=null&&productState.catalogue()!=null){var evidence=productState.catalogue().evidence();body.getChildren().addAll(identity("SUMMARY SOURCE URL",evidence.sourceUrl()),identity("CAPTURED / SHA-256",evidence.capturedAt()+" / "+evidence.sha256()));}
        dialog("Published pointers · "+p.productId(),body);
    }
    private void onWorkspaceState(WorkspacePresentationModel.State next){
        workspaceState=next;
        if(activeWorkspace!=null){String id=activeWorkspace.id();activeWorkspace=next.workspaces().stream().filter(w->w.id().equals(id)).findFirst().orElse(null);}
        updateSavedWorkspaceBar();
        boolean showFeedback=!next.error().isBlank()||next.message().startsWith("Saved metadata workspace")||next.message().startsWith("Workspace label updated")||next.message().startsWith("Saved workspace membership removed");
        workspaceFeedback.getChildren().clear();workspaceFeedback.setManaged(showFeedback);workspaceFeedback.setVisible(showFeedback);
        if(showFeedback){Label message=wrap(next.error().isBlank()?next.message():next.error());HBox.setHgrow(message,Priority.ALWAYS);Button dismiss=button("Dismiss",()->{workspaceFeedback.setVisible(false);workspaceFeedback.setManaged(false);},"quiet-button");HBox row=new HBox(12,message,dismiss);row.setAlignment(Pos.CENTER_LEFT);workspaceFeedback.getChildren().add(row);workspaceFeedback.getStyleClass().setAll("workspace-feedback");}
        status.setText(next.error().isBlank()?next.message():next.error());
        if(destination.equals("Archive")){
            Node focused=stage.getScene().getFocusOwner();String focusId=focused==null?null:focused.getId();
            render();if(focusId!=null)Platform.runLater(()->{Node restored=root.lookup("#"+focusId);if(restored!=null)restored.requestFocus();});
        }
    }
    private void updateSavedWorkspaceBar(){
        if(workspaceModel==null||workspaceState==null)return;
        ComboBox<Workspace> picker=new ComboBox<>(FXCollections.observableArrayList(workspaceState.workspaces()));
        picker.setId("saved-workspace-picker");picker.setPromptText("Open saved metadata workspace…");picker.setPrefWidth(360);
        picker.setAccessibleText("Saved metadata workspaces. Reopen exact build offline without refreshing discovery.");
        picker.setConverter(new javafx.util.StringConverter<>(){
            public String toString(Workspace w){return w==null?"":w.name()+" · "+w.build().productId()+" / "+w.build().channel()+" / "+w.build().region()+" · "+w.build().versionLabel()+" / "+w.locale();}
            public Workspace fromString(String text){return null;}
        });
        picker.setValue(activeWorkspace);
        picker.setOnAction(e->{if(picker.getValue()!=null&&(activeWorkspace==null||!activeWorkspace.id().equals(picker.getValue().id())))openWorkspace(picker.getValue());});
        Button save=button("Save metadata…",this::saveWorkspaceDialog,"quiet-button");save.setAccessibleText("Save current exact-build metadata with a local label and locale");save.setId("save-workspace");save.setDisable(state==null||state.selected()==null||workspaceState.busy());
        Button reload=button("Refresh saved list",()->workspaceModel.load(),"quiet-button");reload.setDisable(workspaceState.busy());
        Label operation=label(workspaceState.busy()?workspaceState.message():workspaceState.error().isBlank()?"Offline metadata · "+workspaceState.workspaces().size()+" saved":workspaceState.error(),"secondary");operation.setWrapText(false);operation.setMaxWidth(380);
        savedWorkspaceBar.getChildren().setAll(picker,save,reload,operation);
    }
    private VBox savedWorkspacesPanel(){
        VBox p=panel(label("SAVED METADATA WORKSPACES","eyebrow"),label("Reopen the source. Stay offline.","section-heading"));
        if(!workspaceState.error().isBlank())p.getChildren().add(notice("Saved workspaces unavailable",workspaceState.error()));
        if(workspaceState.workspaces().isEmpty()){
            p.getChildren().add(wrap(workspaceState.busy()?"Loading app-owned metadata…":"No saved workspaces. Select a build below, then save its exact identity with a label and locale. Saving metadata does not retain game content."));return p;
        }
        TableView<Workspace> table=new TableView<>(FXCollections.observableArrayList(workspaceState.workspaces()));table.setId("saved-workspace-table");table.setAccessibleText("Saved exact-build metadata workspaces");
        table.getColumns().add(workspaceColumn("Workspace",Workspace::name,200));
        table.getColumns().add(workspaceColumn("Product / exact build",w->w.build().productId()+" / "+w.build().channel()+" / "+w.build().region()+" / "+w.build().versionLabel(),290));
        table.getColumns().add(workspaceColumn("Locale",Workspace::locale,85));
        table.getColumns().add(workspaceColumn("Source / coverage",w->(w.build().fixture()?"Fictional fixture":"Captured official metadata")+" · metadata only",260));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);table.setPrefHeight(170);
        String selectedId=selectedSavedWorkspaceId!=null?selectedSavedWorkspaceId:activeWorkspace==null?null:activeWorkspace.id();
        if(selectedId!=null)workspaceState.workspaces().stream().filter(w->w.id().equals(selectedId)).findFirst().ifPresent(w->table.getSelectionModel().select(w));
        table.getSelectionModel().selectedItemProperty().addListener((o,old,w)->{if(w!=null)selectedSavedWorkspaceId=w.id();});
        Runnable open=()->{Workspace w=table.getSelectionModel().getSelectedItem();if(w!=null)openWorkspace(w);};
        table.setOnKeyPressed(e->{if(e.getCode()==KeyCode.ENTER){open.run();e.consume();}});
        table.setRowFactory(tv->{TableRow<Workspace> row=new TableRow<>();row.setOnMouseClicked(e->{if(e.getClickCount()==2&&!row.isEmpty())openWorkspace(row.getItem());});return row;});
        Button openButton=button("Reopen offline →",open,"primary");openButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        Button inspect=button("Inspect source",()->{Workspace w=table.getSelectionModel().getSelectedItem();if(w!=null)buildDetails(w.build(),w.locale());},"quiet-button");inspect.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        Button rename=button("Rename…",()->{Workspace w=table.getSelectionModel().getSelectedItem();if(w!=null)renameWorkspaceDialog(w);},"quiet-button");rename.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull().or(new javafx.beans.property.SimpleBooleanProperty(workspaceState.busy())));
        Button remove=button("Remove saved metadata…",()->{Workspace w=table.getSelectionModel().getSelectedItem();if(w!=null)removeWorkspaceDialog(w);},"quiet-button");remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull().or(new javafx.beans.property.SimpleBooleanProperty(workspaceState.busy())));
        FlowPane actions=new FlowPane(8,8,openButton,inspect,rename,remove);
        p.getChildren().addAll(table,actions,wrap("Each entry pins its original build/configuration identity and locale. Reopening does not contact the CDN or substitute a newer build. Game files and decoded records are not retained by this metadata save."));return p;
    }
    private void rememberWorkspaceView(){if(activeWorkspace!=null)workspaceViews.put(activeWorkspace.id(),new WorkspaceView(destination,researchTopic,searchText,privateNote));}
    private void leaveSavedWorkspace(){rememberWorkspaceView();activeWorkspace=null;currentLocale="enUS";researchTopic="All research areas";searchText="";privateNote="";}
    private void openWorkspace(Workspace w){
        rememberWorkspaceView();activeWorkspace=w;currentLocale=w.locale();
        WorkspaceView view=workspaceViews.get(w.id());
        destination=view==null?"Research":view.destination();researchTopic=view==null?"All research areas":view.topic();searchText=view==null?"":view.query();privateNote=view==null?"":view.note();
        navigation.getToggles().forEach(t->t.setSelected(destination.equals(t.getUserData())));
        model.restore(w.build());
        Platform.runLater(()->{Node picker=root.lookup("#saved-workspace-picker");if(picker!=null)picker.requestFocus();});
    }
    private void saveWorkspaceDialog(){
        if(state.selected()==null||workspaceModel==null||workspaceState.busy())return;
        BuildSnapshot captured=state.selected();
        TextField name=new TextField(activeWorkspace==null?captured.versionLabel()+" research":activeWorkspace.name());name.setId("workspace-name");name.setAccessibleText("Workspace label, 1 to 120 printable characters");
        TextField locale=new TextField(currentLocale);locale.setId("workspace-locale");locale.setAccessibleText("Workspace locale, such as enUS or deDE");
        Label validation=wrap("");validation.getStyleClass().add("validation-error");validation.setVisible(false);validation.setManaged(false);
        VBox body=new VBox(12,wrap("Save this exact source identity in app-owned storage. No game content is downloaded and no latest pointer is resolved when reopening."),identity("PINNED SOURCE",captured.productId()+" / "+captured.region()+" / "+captured.versionLabel()),field("LOCAL LABEL",name),field("LOCALE VIEW",locale),validation);
        Dialog<Void> dialog=editorDialog("Save metadata workspace",body);ButtonType save=new ButtonType("Save metadata",ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(save,ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION,e->{
            try{String label=Workspace.validateName(name.getText());String selectedLocale=locale.getText().strip();if(!selectedLocale.matches("[a-z]{2}[A-Z]{2}"))throw new IllegalArgumentException("Choose a locale such as enUS, enGB or deDE.");workspaceModel.save(captured,selectedLocale,label);}
            catch(IllegalArgumentException invalid){e.consume();validation.setText(invalid.getMessage());validation.setVisible(true);validation.setManaged(true);if(!locale.getText().strip().matches("[a-z]{2}[A-Z]{2}"))locale.requestFocus();else name.requestFocus();}
        });dialog.setOnShown(e->name.requestFocus());dialog.showAndWait();
    }
    private void renameWorkspaceDialog(Workspace w){
        TextInputDialog dialog=new TextInputDialog(w.name());dialog.initOwner(stage);dialog.setTitle("Rename saved workspace");dialog.setHeaderText("Change the label, preserve the source.");dialog.setContentText("Local label:");
        dialog.getDialogPane().setStyle(root.getStyle());dialog.getDialogPane().getStylesheets().add(getClass().getResource("atlas.css").toExternalForm());
        Label validation=wrap("");dialog.getDialogPane().setExpandableContent(validation);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION,e->{try{workspaceModel.rename(w.id(),Workspace.validateName(dialog.getEditor().getText()));}catch(IllegalArgumentException failure){e.consume();validation.setText(failure.getMessage());dialog.getDialogPane().setExpanded(true);}});dialog.showAndWait();
    }
    private void removeWorkspaceDialog(Workspace w){
        Alert confirm=new Alert(Alert.AlertType.CONFIRMATION);confirm.initOwner(stage);confirm.setTitle("Remove saved metadata workspace?");confirm.setHeaderText("Remove “"+w.name()+"” from saved workspaces?");
        confirm.setContentText(w.build().productId()+" / "+w.build().versionLabel()+" / "+w.locale()+"\n\nOnly this saved metadata membership is removed. No game payload is deleted; other exact-build and locale views remain. The current source can remain open as an unsaved view.");
        confirm.getDialogPane().setStyle(root.getStyle());confirm.getDialogPane().getStylesheets().add(getClass().getResource("atlas.css").toExternalForm());
        confirm.showAndWait().filter(b->b==ButtonType.OK).ifPresent(b->workspaceModel.remove(w.id()));
    }
    private Dialog<Void> editorDialog(String title,Node body){Dialog<Void> dialog=new Dialog<>();dialog.initOwner(stage);dialog.setTitle(title);dialog.getDialogPane().setHeaderText(title);ScrollPane scroll=new ScrollPane(body);scroll.setFitToWidth(true);scroll.setPrefViewportHeight(410);dialog.getDialogPane().setContent(scroll);dialog.getDialogPane().setStyle(root.getStyle());dialog.getDialogPane().getStylesheets().add(getClass().getResource("atlas.css").toExternalForm());dialog.getDialogPane().setPrefWidth(620);dialog.setResizable(true);return dialog;}
    private static TableColumn<Workspace,String> workspaceColumn(String title,Function<Workspace,String> value,double width){TableColumn<Workspace,String> c=new TableColumn<>(title);c.setCellValueFactory(cell->new ReadOnlyStringWrapper(value.apply(cell.getValue())));c.setPrefWidth(width);return c;}
    private void openSelection(TableView<BuildSnapshot> table){BuildSnapshot b=table.getSelectionModel().getSelectedItem();if(b!=null)openBuild(b);}
    private void openBuild(BuildSnapshot b){leaveSavedWorkspace();model.select(b);buildDetails(b);Platform.runLater(()->{Node table=root.lookup("#build-table");if(table!=null)table.requestFocus();});}
    private void buildDetails(BuildSnapshot b){buildDetails(b,state.selected()!=null&&state.selected().snapshotId().equals(b.snapshotId())?currentLocale:null);}
    private void buildDetails(BuildSnapshot b,String locale){
        VBox info=new VBox(12,notice(b.fixture()?"Fictional source identity":"Official metadata identity",b.fixture()?"Test fixture only; not a live Blizzard build.":"Captured published metadata. Content availability remains unchecked."),
                identity("EDITION / PRODUCT",b.editionLabel()+" / "+b.productId()),identity("EXACT BUILD",b.versionLabel()),identity("CHANNEL / REGION",b.channel()+" / "+b.region()),
                identity("BUILD CONFIGURATION",b.buildConfig()),identity("CDN CONFIGURATION",b.cdnConfig()),identity("SNAPSHOT ID",b.snapshotId()),
                identity("OBSERVED",DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault()).format(b.observedAt())),
                wrap("Coverage: metadata only. Files, decoded records, previews and complete offline archive availability are not established by this source."));
        if(locale!=null)info.getChildren().add(1,identity("LOCALE VIEW",locale));
        info.getChildren().add(label("CAPTURED SOURCE EVIDENCE","eyebrow"));
        if(b.evidence().documents().isEmpty())info.getChildren().add(wrap("No source documents recorded for this fixture or legacy metadata. Configuration identity is retained; no document provenance is invented."));
        else for(var document:b.evidence().documents())info.getChildren().add(new VBox(6,identity("SOURCE URL",document.sourceUrl()),identity("SHA-256 / CAPTURED",document.sha256()+" / "+document.capturedAt())));
        if(!b.evidence().upstreamBuildId().isBlank())info.getChildren().add(identity("UPSTREAM BUILD ID",b.evidence().upstreamBuildId()));
        if(!b.evidence().productConfig().isBlank())info.getChildren().add(identity("PRODUCT CONFIGURATION",b.evidence().productConfig()));
        dialog("Build source · "+b.versionLabel(),info);
    }
    private VBox comparison(){return unavailable("COMPARE BUILDS","Put changes in context.","A comparison needs two exact builds from the same edition, compatible schemas and verified input coverage.","Comparison engine not implemented","No added, changed or removed records can be asserted yet. Missing or encrypted inputs will be shown as not compared, rather than removed.","Choose build metadata",()->navigate("Archive"));}
    private VBox database(){
        VBox page=page("DATABASE WORKSPACE","Records, before interpretation.","Browse supported tables with raw values, readable fields and source-aware relationships.");
        TextField filter=new TextField();filter.setPromptText("Filter supported tables…");filter.setAccessibleText("Filter supported tables");filter.setDisable(true);
        VBox list=panel(label("TABLE CATALOG","eyebrow"),filter,wrap("No decoded table catalog. Exact-build DB2 decoding is not implemented in this slice."));list.setPrefWidth(230);
        VBox table=panel(label("INDEX COVERAGE","eyebrow"),label("Not indexed. Not searched.","section-heading"),wrap("An empty table here does not mean this build contains no records. Download, exact-schema decoding and indexing must be supported first."),button("Inspect source scope",()->{if(state.selected()!=null)buildDetails(state.selected());else navigate("Archive");},"primary"));
        SplitPane split=new SplitPane(list,table);split.setDividerPositions(.27);split.setPrefHeight(340);page.getChildren().add(split);return page;
    }
    private VBox assets(){return unavailable("ASSET WORKSPACE","Look behind the references.","Models, textures, audio, maps and localized text belong to an exact source build.","Previews are staged capabilities","This first slice has no file acquisition or asset decoders. No copyrighted game assets, simulated playback or fabricated model results are bundled. Unknown names, encryption and unsupported formats will remain distinct states.","Inspect build source",()->{if(state.selected()!=null)buildDetails(state.selected());else navigate("Archive");});}
    private VBox notebook(){
        VBox page=page("INVESTIGATION NOTEBOOK","Keep the question with the evidence.","Future investigations will preserve exact source links, notes, tags and comparison scope.");
        TextArea note=new TextArea(privateNote);note.setPromptText("Draft a question for your next investigation…");note.setAccessibleText("Session draft research note");note.setPrefRowCount(6);note.textProperty().addListener((o,a,b)->privateNote=b);
        page.getChildren().add(panel(label("SESSION DRAFT · NOT SAVED TO DISK","eyebrow"),note,wrap("This draft stays only while the application is running. Persistent investigations and evidence attachment are not implemented yet.")));
        page.getChildren().add(notice("No evidence attached","Discovery metadata does not produce decoded findings. Save/reopen/export will become available with the investigation use cases."));return page;
    }
    private VBox activity(){
        VBox page=page("BACKGROUND ACTIVITY","Keep the work visible.","Network work runs outside the JavaFX application thread.");
        if(productState!=null&&productState.busy())page.getChildren().add(busyPanel("Official product summary","Reading published metadata pointers. Transfer bytes are not reported by this operation.",()->productModel.stopWaiting()));
        if(state.busy())page.getChildren().add(busyPanel("Official metadata discovery","Waiting for the application discovery service. Progress bytes are unknown for this operation.",()->model.stopWaiting()));
        else if(productState==null||!productState.busy())page.getChildren().add(panel(label("NO ACTIVE UI REQUEST","eyebrow"),label("A quiet workspace.","section-heading"),wrap("There are no acquisition, decoding or export jobs in this first slice. Refresh official metadata in Archive to exercise asynchronous discovery."),button("Open sources →",()->navigate("Archive"),"primary")));
        page.getChildren().add(note("HONEST CANCELLATION","The current discovery port cannot cancel its network request. “Stop waiting” only detaches this view and ignores its eventual response. Acquisition jobs will expose application-owned cancellation separately."));return page;
    }
    private VBox settings(){
        VBox page=page("WORKSPACE PREFERENCES","A careful local workspace.","No game installations, Battle.net folders or game-generated caches are read.");
        ComboBox<String> scale=new ComboBox<>(FXCollections.observableArrayList("100%","125%","150%","200%"));scale.setValue(selectedScale);scale.setAccessibleText("Application text scale");
        scale.setOnAction(e->{selectedScale=scale.getValue();String value=scale.getValue().replace("%","");root.setStyle("-fx-font-size: "+(14*Integer.parseInt(value)/100.0)+"px;");});
        page.getChildren().add(panel(label("APPEARANCE","eyebrow"),label("Atlas · ivory & teal","section-heading"),wrap("Standard JavaFX controls preserve keyboard focus and native accessibility semantics. Text scaling applies to this session."),field("TEXT SCALE",scale)));
        page.getChildren().add(panel(label("STORAGE POLICY","eyebrow"),wrap("App-owned content-addressed storage. Build manifests reference shared verified objects; archived and cached content are retention states over the same data. Physical usage and reclaimable bytes are not yet measured."),notice("Storage management not implemented","No storage location chooser, quota change or deletion is performed by this shell.")));
        return page;
    }
    private void showSearch(){destination="Search";navigation.getToggles().forEach(t->t.setSelected(false));render();}
    private VBox search(){
        VBox page=page("FIND / INSPECT / INVESTIGATE","What are you looking for?",researchTopic+" · results require supported, indexed content in your selected build.");
        TextField query=new TextField(searchText);query.setPromptText("Search names, record IDs, tables or paths…");query.setAccessibleText("Research search query");
        Label feedback=wrap("Not searched: no decoded records are indexed. This is distinct from a search with no matching results.");
        query.textProperty().addListener((o,a,b)->{searchText=b;feedback.setText(b.isBlank()?"Not searched: no decoded records are indexed.":"Query retained: “"+b+"”. Not searched: exact-build decoding and indexing are not available yet.");});
        page.getChildren().add(panel(label("QUERY / "+researchTopic.toUpperCase(),"eyebrow"),query,feedback));
        page.getChildren().add(notice(state.selected()==null?"No workspace selected":"Coverage: metadata only",state.selected()==null?"Choose an exact build before running a scoped investigation.":"Selected: "+state.selected().versionLabel()+" / "+state.selected().region()+". Metadata contains no searchable item, spell or asset records."));
        page.getChildren().add(button("Choose build scope →",()->navigate("Archive"),"primary"));Platform.runLater(query::requestFocus);return page;
    }
    private VBox unavailable(String eyebrow,String title,String sub,String heading,String explanation,String action,Runnable run){VBox page=page(eyebrow,title,sub);page.getChildren().add(panel(label("CAPABILITY NOT IMPLEMENTED","eyebrow"),label(heading,"section-heading"),wrap(explanation),button(action,run,"primary")));return page;}
    private void boundaries(){dialog("Available capabilities",new VBox(14,wrap("Available: Atlas navigation, explicit fixture metadata, official asynchronous discovery and exact source inspection."),wrap("Not implemented: persistent archives, downloads, DB2 decoding, indexed research, comparison, evidence persistence or previews. Disabled or explanatory states do not represent completed mining capabilities."),wrap("No local game installation or cache import is used.")));}
    private void dialog(String title,Node node){
        Dialog<Void> dialog=new Dialog<>();dialog.initOwner(stage);dialog.setTitle(title);dialog.getDialogPane().setHeaderText(title);ScrollPane scroll=new ScrollPane(node);scroll.setFitToWidth(true);scroll.setPrefViewportHeight(510);scroll.setStyle("-fx-background-color: transparent;");dialog.getDialogPane().setContent(scroll);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setStyle(root.getStyle());dialog.getDialogPane().getStylesheets().add(Objects.requireNonNull(getClass().getResource("atlas.css")).toExternalForm());dialog.getDialogPane().setPrefWidth(690);dialog.setResizable(true);dialog.showAndWait();
    }
    private VBox page(String eyebrow,String title,String subtitle){VBox page=new VBox(18);page.getStyleClass().add("workspace");Label sub=wrap(subtitle);sub.getStyleClass().add("subtitle");page.getChildren().addAll(new VBox(8,label(eyebrow,"eyebrow"),label(title,"page-heading"),sub));return page;}
    private VBox panel(Node... nodes){VBox p=new VBox(14,nodes);p.getStyleClass().add("panel");return p;}
    private VBox note(String title,String text){VBox box=new VBox(7,label(title,"eyebrow"),wrap(text));box.getStyleClass().add("note");return box;}
    private VBox notice(String title,String text){VBox box=new VBox(5,label(title,"notice-heading"),wrap(text));box.getStyleClass().add("notice");return box;}
    private VBox busyPanel(String title,String text,Runnable cancel){ProgressIndicator indicator=new ProgressIndicator();indicator.setMaxSize(26,26);HBox row=new HBox(12,indicator,label(title,"section-heading"),spacer(),button("Stop waiting",cancel,"quiet-button"));row.setAlignment(Pos.CENTER_LEFT);return panel(row,wrap(text));}
    private HBox step(String n,String title,String text){Label number=label(n,"step-number");VBox words=new VBox(4,label(title,"topic-title"),wrap(text));HBox row=new HBox(16,number,words);return row;}
    private VBox field(String title,Control control){return new VBox(6,label(title,"eyebrow"),control);}
    private VBox identity(String title,String value){TextArea text=new TextArea(value);text.setEditable(false);text.setWrapText(true);text.setPrefRowCount(1);text.setAccessibleText(title);text.getStyleClass().add("identity-value");return new VBox(4,label(title,"eyebrow"),text);}
    private static Label label(String text,String style){Label l=new Label(text);l.getStyleClass().add(style);if(style.equals("section-heading")||style.equals("page-heading")){l.setWrapText(true);l.setMinWidth(0);}return l;}
    private static Label wrap(String text){Label l=new Label(text);l.setWrapText(true);l.getStyleClass().add("body-copy");l.setMaxWidth(Double.MAX_VALUE);return l;}
    private static Region spacer(){Region r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    private static Button button(String title,Runnable action,String style){Button b=new Button(title);b.getStyleClass().add(style);b.setOnAction(e->action.run());return b;}
    private static TableColumn<BuildSnapshot,String> column(String title,Function<BuildSnapshot,String> value,double width){TableColumn<BuildSnapshot,String> c=new TableColumn<>(title);c.setCellValueFactory(cell->new ReadOnlyStringWrapper(value.apply(cell.getValue())));c.setPrefWidth(width);return c;}
    @Override public void stop(){if(model!=null)model.close();if(workspaceModel!=null)workspaceModel.close();if(productModel!=null)productModel.close();}
}
