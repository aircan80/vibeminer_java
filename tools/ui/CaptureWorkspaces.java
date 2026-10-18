import io.vibeminer.application.DefaultWorkspaceService;
import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.Workspace;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real SQLite metadata save/restart review; all content is synthetic and network is forbidden. */
public final class CaptureWorkspaces {
    private static final AtomicInteger DISCOVERY_CALLS = new AtomicInteger();
    private static volatile boolean finished;
    private static String phase;

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("--isolated-display") && args[2].equals("--output")) {
            requireDisplay(args[1]);
            Path output = Path.of(args[3]).toAbsolutePath();
            Files.createDirectories(output);
            Path temporary = Files.createTempDirectory("vibeminer-workspace-review-");
            try {
                for (String step : List.of("save", "reopen")) {
                    var command = new ArrayList<String>();
                    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                    command.add("--enable-native-access=ALL-UNNAMED");
                    command.add("-cp");
                    command.add(System.getProperty("java.class.path"));
                    command.add(CaptureWorkspaces.class.getName());
                    command.addAll(List.of("--worker", step, args[1], output.toString(),
                            temporary.resolve("metadata.sqlite").toString()));
                    Process child = new ProcessBuilder(command).inheritIO().start();
                    if (!child.waitFor(60, TimeUnit.SECONDS)) {
                        child.destroyForcibly();
                        throw new IllegalStateException("Workspace review process timed out: " + step);
                    }
                    if (child.exitValue() != 0) throw new IllegalStateException("Workspace review failed: " + step);
                }
                System.out.println("SQLite UI restart contracts OK; two independent JVM launches; zero discovery calls");
            } finally {
                try (var paths = Files.walk(temporary)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
            return;
        }
        if (args.length != 5 || !args[0].equals("--worker")) {
            throw new IllegalArgumentException("Usage: CaptureWorkspaces --isolated-display :N --output DIRECTORY");
        }
        phase = args[1];
        requireDisplay(args[2]);
        CaptureAtlas.output = Path.of(args[3]);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {error.printStackTrace(); System.exit(1);});
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "workspace-review-storage");
            thread.setDaemon(true);
            return thread;
        });
        var repository = new SqliteWorkspaceRepository(Path.of(args[4]));
        var service = new DefaultWorkspaceService(repository, executor,
                Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC));
        AtlasApplication.configure(new DiscoveryService() {
            public List<BuildSnapshot> fixtureBuilds() {return FixtureDiscovery.builds();}
            public CompletionStage<List<BuildSnapshot>> refresh(String product, String region) {
                DISCOVERY_CALLS.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("Network forbidden in workspace review"));
            }
        }, service, phase.equals("save"));
        Thread deadline = new Thread(() -> {
            try {Thread.sleep(45_000);} catch (InterruptedException ignored) {return;}
            if (!finished) {System.err.println("Workspace UI review timed out: " + phase); System.exit(2);}
        });
        deadline.setDaemon(true);
        deadline.start();
        Thread controller = new Thread(() -> {
            for (int attempt = 0; attempt < 100; attempt++) {
                try {Platform.runLater(CaptureWorkspaces::begin); return;}
                catch (IllegalStateException starting) {
                    try {Thread.sleep(100);} catch (InterruptedException interrupted) {return;}
                }
            }
        });
        controller.setDaemon(true);
        controller.start();
        Application.launch(AtlasApplication.class);
        executor.shutdownNow();
        if (!finished || DISCOVERY_CALLS.get() != 0) throw new IllegalStateException("Offline workspace review did not finish safely");
        List<Workspace> saved = repository.list();
        if (saved.size() != (phase.equals("save") ? 2 : 1) || !phase.equals("save") && !saved.getFirst().locale().equals("enUS")) {
            throw new IllegalStateException("Unexpected persisted workspace membership after review");
        }
        System.out.println("Workspace phase " + phase + " OK; persisted metadata verified; zero discovery calls");
    }

    private static void requireDisplay(String expected) {
        if (expected.isBlank() || !expected.equals(System.getenv("DISPLAY"))) {
            throw new IllegalStateException("Explicit isolated display must match DISPLAY");
        }
    }

    private static void begin() {
        var shown = Window.getWindows().stream().filter(window -> window instanceof Stage && window.isShowing()).toList();
        if (shown.isEmpty()) {CaptureAtlas.later(CaptureWorkspaces::begin); return;}
        Stage stage = (Stage) shown.getFirst();
        CaptureAtlas.later(() -> {
            stage.setX(0); stage.setY(0); stage.setWidth(1280); stage.setHeight(800);
            await(() -> stage.getScene().getWidth() == 1280 && stage.getScene().getHeight() == 800
                            && picker(stage) != null && picker(stage).getItems().size() == (phase.equals("save") ? 0 : 2),
                    () -> {if (phase.equals("save")) saveFirst(stage); else reopen(stage);});
        });
    }

    private static void saveFirst(Stage stage) {
        CaptureAtlas.navigate(stage, "Archive");
        layout(stage);
        TableView<?> table = (TableView<?>) stage.getScene().lookup("#build-table");
        table.getSelectionModel().select(0);
        CaptureAtlas.later(() -> dialog(stage).hide());
        table.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        saveDialog(stage, "Collectible investigation", "enUS", true);
        await(() -> picker(stage).getItems().size() == 1, () -> {
            open(stage, "enUS");
            saveDialog(stage, "German research", "deDE", false);
            await(() -> picker(stage).getItems().size() == 2, () -> switchViews(stage));
        });
    }

    private static void saveDialog(Stage stage, String name, String locale, boolean capture) {
        CaptureAtlas.later(() -> {
            Stage editor = dialog(stage);
            layout(editor);
            ((TextField) editor.getScene().lookup("#workspace-name")).setText(name);
            ((TextField) editor.getScene().lookup("#workspace-locale")).setText(locale);
            CaptureAtlas.later(() -> {
                if (capture) CaptureAtlas.capture(editor, "workspace-save-dialog.png",
                        (int) editor.getScene().getWidth(), (int) editor.getScene().getHeight());
                CaptureAtlas.button(editor, "Save metadata").fire();
            });
        });
        ((Button) stage.getScene().lookup("#save-workspace")).fire();
    }

    private static void switchViews(Stage stage) {
        open(stage, "enUS");
        CaptureAtlas.button(stage, "Search workspace").fire();
        ((TextField) CaptureAtlas.accessible(stage, "Research search query")).setText("Moonlit Surveyor");
        CaptureAtlas.navigate(stage, "Notebook");
        ((TextArea) CaptureAtlas.accessible(stage, "Session draft research note")).setText("Investigate the exact source");
        open(stage, "deDE");
        CaptureAtlas.button(stage, "Search workspace").fire();
        check(((TextField) CaptureAtlas.accessible(stage, "Research search query")).getText().isEmpty(), "Query leaked between locale workspaces");
        CaptureAtlas.navigate(stage, "Notebook");
        check(((TextArea) CaptureAtlas.accessible(stage, "Session draft research note")).getText().isEmpty(), "Draft leaked between locale workspaces");
        open(stage, "enUS");
        check(((TextArea) CaptureAtlas.accessible(stage, "Session draft research note")).getText().equals("Investigate the exact source"), "Draft was not restored for original workspace");
        CaptureAtlas.button(stage, "Search workspace").fire();
        check(((TextField) CaptureAtlas.accessible(stage, "Research search query")).getText().equals("Moonlit Surveyor"), "Query was not restored for original workspace");
        CaptureAtlas.navigate(stage, "Archive");
        CaptureAtlas.button(stage, "Open fictional fixtures").fire();
        layout(stage);
        TableView<?> raw = (TableView<?>) stage.getScene().lookup("#build-table");
        raw.getSelectionModel().select(1);
        CaptureAtlas.later(() -> dialog(stage).hide());
        raw.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        CaptureAtlas.button(stage, "Search workspace").fire();
        check(((TextField) CaptureAtlas.accessible(stage, "Research search query")).getText().isEmpty(), "Saved query leaked into unrelated raw source");
        CaptureAtlas.navigate(stage, "Notebook");
        check(((TextArea) CaptureAtlas.accessible(stage, "Session draft research note")).getText().isEmpty(), "Saved draft leaked into unrelated raw source");
        open(stage, "enUS");
        CaptureAtlas.button(stage, "Search workspace").fire();
        check(((TextField) CaptureAtlas.accessible(stage, "Research search query")).getText().equals("Moonlit Surveyor"), "Saved query lost after raw source exploration");
        CaptureAtlas.navigate(stage, "Archive");
        CaptureAtlas.later(() -> {
            CaptureAtlas.capture(stage, "workspace-catalog-1280x800.png", 1280, 800);
            finish();
        });
    }

    private static void reopen(Stage stage) {
        open(stage, "deDE");
        check(picker(stage).getValue() instanceof Workspace value && value.locale().equals("deDE"), "Saved locale was not selected after restart");
        CaptureAtlas.later(() -> {
            CaptureAtlas.capture(stage, "workspace-reopened-1280x800.png", 1280, 800);
            CaptureAtlas.navigate(stage, "Archive");
            CaptureAtlas.later(() -> {
                CaptureAtlas.capture(stage, "workspace-reopened-catalog-1280x800.png", 1280, 800);
                CaptureAtlas.button(stage, "Settings").fire();
                CaptureAtlas.setCombo(CaptureAtlas.accessible(stage, "Application text scale"), "200%");
                CaptureAtlas.navigate(stage, "Archive");
                CaptureAtlas.later(() -> {
                    CaptureAtlas.capture(stage, "workspace-catalog-200pct-1280x800.png", 1280, 800);
                    CaptureAtlas.navigate(stage, "Research");
                    CaptureAtlas.later(() -> {
                        CaptureAtlas.capture(stage, "workspace-reopened-200pct-1280x800.png", 1280, 800);
                        reviewActions(stage);
                    });
                });
            });
        });
    }

    private static void reviewActions(Stage stage) {
        CaptureAtlas.button(stage, "Settings").fire();
        CaptureAtlas.setCombo(CaptureAtlas.accessible(stage, "Application text scale"), "100%");
        CaptureAtlas.navigate(stage, "Archive");
        selectSaved(stage, "enUS");
        CaptureAtlas.later(() -> {
            Stage inspector = dialog(stage);
            check(((TextArea) CaptureAtlas.accessible(inspector, "LOCALE VIEW")).getText().equals("enUS"),
                    "Source inspector used active locale instead of inspected saved locale");
            inspector.hide();
        });
        CaptureAtlas.button(stage, "Inspect source").fire();
        selectSaved(stage, "deDE");
        CaptureAtlas.later(() -> {
            Stage editor = dialog(stage);
            layout(editor);
            TextField name = (TextField) editor.getScene().getRoot().lookupAll(".text-field").stream()
                    .filter(node -> node instanceof TextField).findFirst().orElseThrow();
            name.setText("");
            CaptureAtlas.button(editor, "OK").fire();
            check(editor.isShowing(), "Invalid rename closed the editor");
            name.setText("German research reviewed");
            CaptureAtlas.button(editor, "OK").fire();
        });
        CaptureAtlas.button(stage, "Rename…").fire();
        await(() -> picker(stage).getItems().stream().anyMatch(value -> value instanceof Workspace w && w.name().equals("German research reviewed")), () -> {
            selectSaved(stage, "deDE");
            CaptureAtlas.later(() -> CaptureAtlas.button(dialog(stage), "Cancel").fire());
            CaptureAtlas.button(stage, "Remove saved metadata…").fire();
            check(picker(stage).getItems().size() == 2, "Cancelled removal changed saved memberships");
            CaptureAtlas.later(() -> CaptureAtlas.button(dialog(stage), "OK").fire());
            CaptureAtlas.button(stage, "Remove saved metadata…").fire();
            await(() -> picker(stage).getItems().size() == 1, () -> {
                check(((Workspace) picker(stage).getItems().getFirst()).locale().equals("enUS"), "Removal affected the other locale workspace");
                System.out.println("Workspace UI actions OK: source locale, invalid rename, cancel and confirmed membership removal");
                finish();
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static void selectSaved(Stage stage, String locale) {
        layout(stage);
        TableView<Workspace> table = (TableView<Workspace>) stage.getScene().lookup("#saved-workspace-table");
        table.getSelectionModel().select(table.getItems().stream().filter(value -> value.locale().equals(locale)).findFirst().orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static void open(Stage stage, String locale) {
        ComboBox<Workspace> box = (ComboBox<Workspace>) picker(stage);
        Workspace workspace = box.getItems().stream().filter(value -> value.locale().equals(locale)).findFirst().orElseThrow();
        box.setValue(workspace);
        layout(stage);
    }

    private static ComboBox<?> picker(Stage stage) {
        layout(stage);
        return (ComboBox<?>) stage.getScene().lookup("#saved-workspace-picker");
    }

    private static Stage dialog(Stage owner) {
        return (Stage) Window.getWindows().stream().filter(window -> window != owner && window.isShowing()).findFirst().orElseThrow();
    }

    private static void layout(Stage stage) {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private static void await(BooleanSupplier ready, Runnable next) {
        if (ready.getAsBoolean()) Platform.runLater(next);
        else CaptureAtlas.later(() -> await(ready, next));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void finish() {
        check(DISCOVERY_CALLS.get() == 0, "Workspace restore called discovery");
        finished = true;
        Platform.exit();
    }
}
