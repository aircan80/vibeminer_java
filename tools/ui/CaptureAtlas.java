import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.desktop.AtlasApplication;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.scene.control.*;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.WritableImage;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Fixture-only Atlas screenshot harness. Captures this application scene, never the desktop. */
public final class CaptureAtlas {
    static Path output;
    static volatile boolean complete;
    public static void main(String[] args) {
        if(args.length != 4 || !"--isolated-display".equals(args[0]) || !"--output".equals(args[2])) {
            throw new IllegalArgumentException("Usage: CaptureAtlas --isolated-display :N --output DIRECTORY");
        }
        if(args[1].isBlank() || !args[1].equals(System.getenv("DISPLAY"))) {
            throw new IllegalStateException("Explicit isolated display must match DISPLAY");
        }
        Thread.setDefaultUncaughtExceptionHandler((thread,failure)->{failure.printStackTrace();System.exit(1);});
        output=Path.of(args[3]);
        try {java.nio.file.Files.createDirectories(output);}catch(java.io.IOException failure){throw new RuntimeException(failure);}
        AtlasApplication.configure(new DiscoveryService() {
            public List<BuildSnapshot> fixtureBuilds(){return FixtureDiscovery.builds();}
            public CompletionStage<List<BuildSnapshot>> refresh(String p,String r){return CompletableFuture.failedFuture(new IllegalStateException("Offline screenshot fixture"));}
        },true);
        Thread deadline=new Thread(()->{
            try {Thread.sleep(30_000);}catch(InterruptedException stopped){return;}
            if(!complete){System.err.println("Atlas screenshot sequence timed out");System.exit(2);}
        });deadline.setDaemon(true);deadline.start();
        Thread controller=new Thread(()->{
            for(int i=0;i<100;i++) {
                try {Platform.runLater(()->begin());return;}
                catch(IllegalStateException startup){try{Thread.sleep(100);}catch(InterruptedException stopped){return;}}
            }
        });controller.setDaemon(true);controller.start();
        Application.launch(AtlasApplication.class);
        if(!complete) throw new IllegalStateException("Atlas screenshot sequence did not complete");
    }
    static void begin(){
        var windows=Window.getWindows().stream().filter(w->w instanceof Stage && w.isShowing()).toList();
        if(windows.isEmpty()){later(CaptureAtlas::begin);return;}
        Stage stage=(Stage)windows.getFirst();
        stage.setX(0);stage.setY(0);stage.setWidth(1440);stage.setHeight(1100);
        later(()->{capture(stage,"atlas-research-1440x1100.png",1440,1100);navigate(stage,"Archive");
            later(()->{capture(stage,"atlas-archive-1440x1100.png",1440,1100);stage.setWidth(1280);stage.setHeight(800);navigate(stage,"Research");
                later(()->{capture(stage,"atlas-research-1280x800.png",1280,800);navigate(stage,"Archive");
                    later(()->{capture(stage,"atlas-archive-1280x800.png",1280,800);reviewSelection(stage);});
                });
            });
        });
    }
    static void reviewSelection(Stage stage){
        TableView<?> table=(TableView<?>)stage.getScene().lookup("#build-table");
        if(table==null || table.getItems().size()!=2) throw new IllegalStateException("Expected fixture build table");
        table.getSelectionModel().select(0);table.requestFocus();
        later(()->{
            var dialogs=Window.getWindows().stream().filter(w->w!=stage && w.isShowing()).toList();
            if(dialogs.isEmpty()) throw new IllegalStateException("Enter did not open source dialog");
            dialogs.forEach(Window::hide);
        });
        table.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ENTER,false,false,false,false));
        later(()->{
            Node currentTable=stage.getScene().lookup("#build-table");
            if(stage.getScene().getFocusOwner()!=currentTable) throw new IllegalStateException("Source dialog did not restore table focus");
            capture(stage,"atlas-selected-1280x800.png",1280,800);
            button(stage,"Search workspace").fire();
            TextField query=(TextField)accessible(stage,"Research search query");
            for(char c:"Moonlit Surveyor".toCharArray()) query.setText(query.getText()+c);
            navigate(stage,"Notebook");button(stage,"Search workspace").fire();
            if(!"Moonlit Surveyor".equals(((TextField)accessible(stage,"Research search query")).getText())) throw new IllegalStateException("Query lost during navigation");
            button(stage,"Settings").fire();
            setCombo(accessible(stage,"Application text scale"),"200%");
            navigate(stage,"Research");button(stage,"Settings").fire();
            if(!"200%".equals(((ComboBox<?>)accessible(stage,"Application text scale")).getValue())) throw new IllegalStateException("Text-scale control lost session value");
            later(()->{
                capture(stage,"atlas-settings-200pct-1280x800.png",1280,800);
                navigate(stage,"Research");
                capture(stage,"atlas-research-200pct-1280x800.png",1280,800);
                navigate(stage,"Archive");
                setCombo(accessible(stage,"Official product identifier"),"wowt");
                setCombo(accessible(stage,"Discovery region"),"eu");
                button(stage,"Check official metadata").fire();
                later(()->{
                    if(!"wowt".equals(((ComboBox<?>)accessible(stage,"Official product identifier")).getValue()) || !"eu".equals(((ComboBox<?>)accessible(stage,"Discovery region")).getValue())) throw new IllegalStateException("Discovery controls reset during asynchronous render");
                    System.out.println("UI contracts OK: Enter dialog/focus, query retention, text-scale retention, asynchronous source selection");
                    complete=true;Platform.exit();
                });
            });
        });
    }
    @SuppressWarnings("unchecked")
    static void setCombo(Node node,String value){((ComboBox<String>)node).setValue(value);}
    static Node accessible(Stage stage,String name){stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();return stage.getScene().getRoot().lookupAll("*").stream().filter(n->name.equals(n.getAccessibleText())).findFirst().orElseThrow(()->new IllegalStateException("Missing control: "+name));}
    static Button button(Stage stage,String name){stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();return stage.getScene().getRoot().lookupAll(".button").stream().filter(n->n instanceof Button b && name.equals(b.getText())).map(n->(Button)n).findFirst().orElseThrow(()->new IllegalStateException("Missing button: "+name));}
    static void navigate(Stage stage,String name){
        stage.getScene().getRoot().lookupAll(".nav-button").stream().filter(n->n instanceof ToggleButton t && name.equals(t.getUserData())).map(n->(ToggleButton)n).findFirst().orElseThrow().fire();
    }
    static void later(Runnable action){PauseTransition p=new PauseTransition(Duration.millis(700));p.setOnFinished(e->Platform.runLater(action));p.play();}
    static void capture(Stage stage,String name,int width,int height){
        try {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            WritableImage image=stage.getScene().snapshot(null);
            if((int)image.getWidth()!=width || (int)image.getHeight()!=height) {
                throw new IllegalStateException("Expected "+width+"x"+height+" scene, got "+image.getWidth()+"x"+image.getHeight());
            }
            BufferedImage out=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
            var pixels=image.getPixelReader();for(int y=0;y<out.getHeight();y++)for(int x=0;x<out.getWidth();x++)out.setRGB(x,y,pixels.getArgb(x,y));
            ImageIO.write(out,"png",output.resolve(name).toFile());System.out.println("Captured "+name);
        }catch(Exception failure){failure.printStackTrace();Platform.exit();throw new RuntimeException(failure);}
    }
}
