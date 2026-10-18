package io.vibeminer.desktop;

import io.vibeminer.application.bootstrap.InstallInspection;
import io.vibeminer.application.bootstrap.InstallPlan;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** Native install-list view. It accepts verified application projections and never opens listed paths. */
final class InstallFilesPane extends VBox {
    private final InstallPresentationModel model;
    private final InstallPresentationModel.State state;
    private final Consumer<String> filterChanged;
    private final Consumer<String> selectionChanged;
    private String selectedKey;

    InstallFilesPane(InstallPresentationModel model,String filter,String selectedKey,
                     Consumer<String> filterChanged,Consumer<String> selectionChanged) {
        super(10);
        this.model=model;this.state=model.state();this.selectedKey=selectedKey;
        this.filterChanged=filterChanged;this.selectionChanged=selectionChanged;
        getStyleClass().add("install-files-pane");
        Label title=label("Inspect the installation file list.","section-heading");title.setWrapText(true);
        getChildren().addAll(title,copy("List metadata only. Listed file contents and the full game asset catalogue are not downloaded."));
        if(state.build()==null){getChildren().add(notice("Choose an exact build first.","There is no install-list request or fictional result to display."));return;}
        if(state.stage()==InstallPresentationModel.Stage.FAILED||state.stage()==InstallPresentationModel.Stage.CANCELLED)
            getChildren().add(notice(state.stage()==InstallPresentationModel.Stage.CANCELLED?"Request cancelled":"Install list unavailable",state.message()));
        else if(state.stage()==InstallPresentationModel.Stage.UNCHECKED||state.stage()==InstallPresentationModel.Stage.PLANNED||state.stage()==InstallPresentationModel.Stage.RETAINED)getChildren().add(label(state.stage()==InstallPresentationModel.Stage.UNCHECKED?"NOT CHECKED":state.stage()==InstallPresentationModel.Stage.PLANNED?"PLAN READY":"VERIFIED RETAINED LIST","eyebrow"));
        else getChildren().add(copy(state.message()));
        if(state.busy()){
            ProgressIndicator progress=new ProgressIndicator();progress.setMaxSize(25,25);progress.setAccessibleText("Install-list operation in progress. Percentage is unknown.");
            Button cancel=button("Cancel request",model::cancel,"quiet-button");cancel.setId("install-cancel");
            FlowPane busy=new FlowPane(12,8,progress,label(busyTitle(),"install-operation-title"),cancel);busy.setAlignment(Pos.CENTER_LEFT);getChildren().add(busy);
        }else if(state.stage()==InstallPresentationModel.Stage.RETAINED){
            getChildren().add(new FlowPane(10,8,reopenButton(),checkButton()));
            getChildren().add(label("Retained list: "+bytes(state.plan().encodedBytes())+" encoded / "+bytes(state.plan().decodedBytes())+" decoded", "secondary"));
        }else if(state.plan()!=null)getChildren().add(planCard(state.plan()));
        else {
            FlowPane actions=new FlowPane(10,8,checkButton(),reopenButton());getChildren().add(actions);
            getChildren().add(label("Check reads build configuration only. Downloading the list is a separate explicit action.","secondary"));
        }
        if(state.inspection()!=null)getChildren().add(entryBrowser(state.inspection(),filter));
        InstallPlan evidencePlan=state.plan()!=null?state.plan():state.inspection()!=null?state.inspection().plan():null;
        if(evidencePlan!=null)getChildren().add(sourceDetails(evidencePlan,state.inspection()));
    }
    private String busyTitle(){return switch(state.stage()){
        case CHECKING->"Checking selected build…";
        case REOPENING->"Verifying retained list locally…";
        default->"Obtaining and checking list…";
    };}
    private Button checkButton(){Button b=button(state.plan()==null?"Check install list":"Check again",model::check,"quiet-button");b.setId("install-check");return b;}
    private Button reopenButton(){Button b=button("Reopen retained list",model::reopen,"quiet-button");b.setId("install-reopen");b.setAccessibleText("Reopen and verify retained install list for this exact build. Offline only, no network request.");return b;}
    private VBox planCard(InstallPlan plan){
        VBox card=new VBox(10);card.getStyleClass().add("install-cost-card");
        card.getChildren().add(label("INSTALL FILE LIST · EXACT REQUEST SCOPE","eyebrow"));
        FlowPane amounts=new FlowPane(24,8,
                new VBox(3,label(bytes(plan.encodedBytes()),"install-cost"),label("Encoded list object · download / reuse", "secondary")),
                new VBox(3,label(bytes(plan.decodedBytes()),"install-cost"),label("Decoded list · not listed file contents", "secondary")));
        Button download=button(state.stage()==InstallPresentationModel.Stage.FAILED?"Retry list download":"Download list",model::download,"primary");download.setId("install-download");download.setAccessibleText("Download the install-list object: "+bytes(plan.encodedBytes())+" encoded, "+bytes(plan.decodedBytes())+" decoded. Listed file contents are not downloaded; verified shared bytes may be reused.");
        FlowPane actions=new FlowPane(10,8,download,checkButton(),reopenButton());
        card.getChildren().addAll(amounts,actions,label("List object only · Verified shared bytes can be reused · Configuration check is separate", "secondary"));return card;
    }
    private VBox entryBrowser(InstallInspection inspection,String filter){
        VBox box=new VBox(10);box.getStyleClass().add("panel");
        box.getChildren().add(label("VERIFIED RETAINED INSTALL ENTRIES","eyebrow"));
        TextField search=new TextField(filter);search.setId("install-entry-filter");search.setPromptText("Filter install entries by name or tag…");search.setAccessibleText("Filter verified install entries by name or tag");
        Label count=label("","secondary");count.setId("install-entry-count");
        TableView<InstallInspection.Entry> table=new TableView<>();table.setId("install-entry-table");table.setAccessibleText("Verified install entries. Name, decoded size and tags. Enter inspects selected entry.");
        table.getColumns().add(column("Name",InstallInspection.Entry::name,440));
        table.getColumns().add(column("Decoded size",e->bytes(e.decodedBytes()),150));
        table.getColumns().add(column("Tags",e->String.join(", ",e.tags().stream().map(InstallInspection.Tag::name).toList()),260));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);table.setPrefHeight(245);
        table.setPlaceholder(copy("No matches in this verified install list. Clear the filter to see its entries."));
        VBox detail=new VBox(10);detail.setId("install-entry-detail");
        Runnable update=()->{
            List<InstallInspection.Entry> rows=model.matching(search.getText());
            table.setItems(FXCollections.observableArrayList(rows));
            count.setText(rows.size()+" of "+inspection.entries().size()+" verified entries");
            if(selectedKey!=null)rows.stream().filter(e->key(e).equals(selectedKey)).findFirst().ifPresent(e->table.getSelectionModel().select(e));
            showEntry(detail,table.getSelectionModel().getSelectedItem(),inspection);
        };
        table.getSelectionModel().selectedItemProperty().addListener((o,old,entry)->{
            if(entry!=null){selectedKey=key(entry);selectionChanged.accept(selectedKey);}showEntry(detail,entry,inspection);
        });
        table.setOnKeyPressed(e->{if(e.getCode()==KeyCode.ENTER){Node name=detail.lookup("#install-selected-name");if(name!=null)name.requestFocus();e.consume();}});
        detail.setOnKeyPressed(e->{if(e.getCode()==KeyCode.ESCAPE){table.requestFocus();e.consume();}});
        search.textProperty().addListener((o,a,b)->{filterChanged.accept(b);update.run();});
        update.run();box.getChildren().addAll(search,count,table,detail);return box;
    }
    private void showEntry(VBox detail,InstallInspection.Entry entry,InstallInspection inspection){
        detail.getChildren().clear();
        if(entry==null){detail.getChildren().add(copy("Select an entry to inspect its full name, tags and content identity."));return;}
        TextArea name=value(entry.name(),"Full install entry name. Display text only, never a local file path action.");name.setId("install-selected-name");
        detail.getChildren().addAll(label("SELECTED INSTALL ENTRY","eyebrow"),name,
                copy("Decoded size: "+bytes(entry.decodedBytes())+" · Tags: "+(entry.tags().isEmpty()?"None":String.join(", ",entry.tags().stream().map(t->t.name()+" (type "+t.type()+")").toList()))),
                copy("Source: "+inspection.plan().build().productId()+" / "+inspection.plan().build().region()+" / "+inspection.plan().build().versionLabel()+" · verified list only"));
        TitledPane identity=new TitledPane("Entry content identity",value(entry.contentKey(),"Entry content identity from the verified install list"));identity.setExpanded(false);detail.getChildren().add(identity);
    }
    private TitledPane sourceDetails(InstallPlan plan,InstallInspection inspection){
        VBox body=new VBox(10,
                field("SELECTED EXACT BUILD",plan.build().productId()+" / "+plan.build().region()+" / "+plan.build().versionLabel()),
                field("PINNED BUILD / CDN CONFIGURATION",plan.build().buildConfig()+" / "+plan.build().cdnConfig()),
                field("CONFIGURATION CAPTURE SOURCE",plan.configuration().sourceUrl()),
                field("CONFIGURATION CAPTURE / SHA-256",plan.configuration().capturedAt()+" / "+plan.configuration().sha256()),
                field("LIST CONTENT / ENCODING IDENTITY",plan.contentKey()+" / "+plan.encodingKey()),
                field("PLANNED ENCODED / DECODED LIST SIZE",bytes(plan.encodedBytes())+" / "+bytes(plan.decodedBytes())));
        if(inspection!=null)body.getChildren().addAll(
                field("RETAINED LIST CONTENT / ENCODING IDENTITY",inspection.plan().contentKey()+" / "+inspection.plan().encodingKey()),
                field("ACTUAL LIST VERIFICATION SOURCE",inspection.verification().sourceUrl()),
                field("VERIFIED AT / VERIFIER REVISION",inspection.verification().verifiedAt()+" / "+inspection.verification().verifierSemantics()),
                field("PHYSICAL OBJECT SHA-256 / LENGTH",inspection.object().digest().value()+" / "+bytes(inspection.object().length())),
                copy("Actual verification provenance can refer to an earlier source reused by another exact build. It is retained as captured, not replaced by the selected build's advertised host."));
        body.getChildren().add(copy("Names are display values from the installation file list. This is not a WoW root/FileDataID catalogue, complete game archive, decoded asset collection or game-install read."));
        TitledPane pane=new TitledPane("Source details and verification evidence",body);pane.setId("install-source-details");pane.setExpanded(false);return pane;
    }
    private static VBox field(String title,String text){return new VBox(4,label(title,"eyebrow"),value(text,title));}
    private static TextArea value(String text,String accessible){TextArea area=new TextArea(text);area.setEditable(false);area.setWrapText(true);area.setPrefRowCount(2);area.setAccessibleText(accessible);area.getStyleClass().add("identity-value");return area;}
    private static String key(InstallInspection.Entry e){return e.name()+"\u0000"+e.contentKey();}
    private static String bytes(long value){return String.format(Locale.ROOT,"%,d bytes",value);}
    private static Label label(String text,String style){Label l=new Label(text);l.getStyleClass().add(style);return l;}
    private static Label copy(String text){Label l=label(text,"body-copy");l.setWrapText(true);l.setMaxWidth(Double.MAX_VALUE);return l;}
    private static VBox notice(String title,String text){VBox v=new VBox(5,label(title,"notice-heading"),copy(text));v.getStyleClass().add("notice");return v;}
    private static Button button(String text,Runnable run,String style){Button b=new Button(text);b.getStyleClass().add(style);b.setOnAction(e->run.run());return b;}
    private static TableColumn<InstallInspection.Entry,String> column(String title,Function<InstallInspection.Entry,String> function,double width){TableColumn<InstallInspection.Entry,String> c=new TableColumn<>(title);c.setCellValueFactory(value->new ReadOnlyStringWrapper(function.apply(value.getValue())));c.setPrefWidth(width);return c;}
}
