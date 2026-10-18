import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.bootstrap.InstallInspection;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.Workspace;
import io.vibeminer.infrastructure.bootstrap.InstallFixtureBackend;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real JavaFX/use-case/codec/CAS/SQLite review with synthetic transport and two JVM launches. */
public final class CaptureInstallWorkflow {
    private static InstallFixtureBackend backend;
    private static boolean offline;
    private static volatile boolean finished;
    private static final AtomicInteger metadataCalls = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("--isolated-display") && args[2].equals("--output")) {
            requireDisplay(args[1]);
            Path output = Path.of(args[3]).toAbsolutePath();
            Files.createDirectories(output);
            Path root = Files.createTempDirectory("vibeminer-install-ui-");
            try {
                for (String phase : List.of("acquire", "offline")) {
                    var command = new ArrayList<String>();
                    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                    command.addAll(List.of("--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                            CaptureInstallWorkflow.class.getName(), "--worker", phase, args[1], output.toString(), root.toString()));
                    Process child = new ProcessBuilder(command).inheritIO().start();
                    if (!child.waitFor(120, TimeUnit.SECONDS)) {
                        child.destroyForcibly();
                        throw new IllegalStateException("Install UI worker timed out: " + phase);
                    }
                    if (child.exitValue() != 0) throw new IllegalStateException("Install UI worker failed: " + phase);
                }
                System.out.println("Install UI workflow OK; real services/CAS/SQLite; two JVMs; offline transport forbidden");
            } finally {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
            return;
        }
        if (args.length != 5 || !args[0].equals("--worker")) {
            throw new IllegalArgumentException("Usage: CaptureInstallWorkflow --isolated-display :N --output DIRECTORY");
        }
        requireDisplay(args[2]);
        offline = args[1].equals("offline");
        CaptureAtlas.output = Path.of(args[3]);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {error.printStackTrace(); System.exit(1);});
        try (var resources = new InstallFixtureBackend(Path.of(args[4]), offline)) {
            backend = resources;
            AtlasApplication.configure(new DiscoveryService() {
                public List<BuildSnapshot> fixtureBuilds() {return backend.builds();}
                public CompletionStage<List<BuildSnapshot>> refresh(String product, String region) {
                    metadataCalls.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError("Implicit discovery is forbidden"));
                }
            }, backend.workspaces(), () -> {
                metadataCalls.incrementAndGet();
                return CompletableFuture.failedFuture(new AssertionError("Product refresh is outside this review scope"));
            }, backend.bootstrap(), true);
            Thread deadline = new Thread(() -> {
                try {Thread.sleep(90_000);} catch (InterruptedException interrupted) {return;}
                if (!finished) {System.err.println("Install UI review timed out"); System.exit(2);}
            });
            deadline.setDaemon(true); deadline.start();
            Thread controller = new Thread(() -> {
                for (int attempt = 0; attempt < 100; attempt++) {
                    try {Platform.runLater(CaptureInstallWorkflow::begin); return;}
                    catch (IllegalStateException starting) {
                        try {Thread.sleep(100);} catch (InterruptedException interrupted) {return;}
                    }
                }
            });
            controller.setDaemon(true); controller.start();
            Application.launch(AtlasApplication.class);
            check(finished && metadataCalls.get() == 0, "Install review ended without completing its explicit scope");
            if (offline) check(backend.transportCalls.get() == 0, "Offline reopening invoked transport");
        }
    }

    private static void begin() {
        var windows = Window.getWindows().stream().filter(window -> window instanceof Stage && window.isShowing()).toList();
        if (windows.isEmpty()) {CaptureAtlas.later(CaptureInstallWorkflow::begin); return;}
        Stage stage = (Stage) windows.getFirst();
        CaptureAtlas.later(() -> {
            stage.setX(0); stage.setY(0); stage.setWidth(1280); stage.setHeight(800);
            await(() -> picker(stage).getItems().size() == 2, () -> {
                check(backend.configurationCalls.get() == 0 && backend.contentCalls.get() == 0, "Startup performed content acquisition");
                openBuild(stage, 0);
                CaptureAtlas.later(() -> {
                    if (offline) reopenOffline(stage);
                    else startOnline(stage);
                });
            });
        });
    }

    private static void startOnline(Stage stage) {
        capture(stage, "install-unchecked-1280x800.png");
        assertVisible(stage, byId(stage, "install-check"), "Initial config check is outside the visible install workspace");
        check(labels(stage).contains("Metadata only"), "Unchecked scope is not metadata only");
        click(stage, "install-reopen");
        await(() -> labels(stage).contains("No verified retained list exists"), () -> {
            check(backend.configurationCalls.get() == 0 && backend.contentCalls.get() == 0, "Missing-local reopen started acquisition");
            check(optional(stage, "install-entry-table") == null, "Missing-local scope fabricated entries");
            click(stage, "install-check");
            await(() -> optional(stage, "install-download") != null, () -> planReady(stage));
        });
    }

    private static void planReady(Stage stage) {
        check(backend.configurationCalls.get() == 1 && backend.contentCalls.get() == 0, "Config check acquired content or failed to invoke its transport");
        Node card = stage.getScene().getRoot().lookup(".install-cost-card");
        reveal(stage, card);
        CaptureAtlas.later(() -> {
            String text = labels(stage);
            check(text.contains(String.format(Locale.ROOT, "%,d bytes", backend.encodedBytes()))
                    && text.contains(String.format(Locale.ROOT, "%,d bytes", backend.decodedBytes())), "Plan cost does not match real verified configuration");
            for (Node amount : card.lookupAll(".install-cost")) assertVisible(stage, amount, "Cost is hidden before download");
            assertVisible(stage, byId(stage, "install-download"), "Download action cannot be reviewed with its cost");
            capture(stage, "install-plan-cost-1280x800.png");
            backend.mode(InstallFixtureBackend.TransferMode.UNAVAILABLE);
            click(stage, "install-download");
            await(() -> labels(stage).contains("The CDN list is unavailable"), () -> {
                check(backend.contentCalls.get() == 1 && backend.references(0) == 1, "Failed download created manifest membership");
                capture(stage, "install-unavailable-1280x800.png");
                cancelDownload(stage);
            });
        });
    }

    private static void cancelDownload(Stage stage) {
        backend.mode(InstallFixtureBackend.TransferMode.HOLD);
        click(stage, "install-download");
        await(() -> backend.contentCalls.get() == 2 && optional(stage, "install-cancel") != null, () -> {
            click(stage, "install-cancel");
            await(backend::wireCancelled, () -> {
                check(backend.references(0) == 1 && optional(stage, "install-entry-table") == null, "Cancelled work became retained content");
                check(labels(stage).contains("Request cancelled"), "Cancellation lacks visible feedback");
                capture(stage, "install-cancelled-1280x800.png");
                contextSwitch(stage);
            });
        });
    }

    private static void contextSwitch(Stage stage) {
        backend.mode(InstallFixtureBackend.TransferMode.HOLD);
        click(stage, "install-download");
        await(() -> backend.contentCalls.get() == 3 && optional(stage, "install-cancel") != null, () -> {
            openBuild(stage, 1);
            await(backend::wireCancelled, () -> {
                check(backend.configurationCalls.get() == 1 && backend.contentCalls.get() == 3, "Switching exact builds performed implicit acquisition");
                check(optional(stage, "install-entry-table") == null && optional(stage, "install-download") == null, "Old build plan or entries leaked into the new exact context");
                check(labels(stage).contains("DEMO-INSTALL-B") && labels(stage).contains("Metadata only"), "New build context is mislabeled");
                openBuild(stage, 0);
                click(stage, "install-check");
                await(() -> optional(stage, "install-download") != null, () -> {
                    check(backend.configurationCalls.get() == 1, "Retained config was fetched again");
                    backend.mode(InstallFixtureBackend.TransferMode.SUCCESS);
                    click(stage, "install-download");
                    await(() -> optional(stage, "install-entry-table") != null, () -> inspectEntries(stage, false));
                });
            });
        });
    }

    private static void inspectEntries(Stage stage, boolean afterRestart) {
        check(table(stage).getItems().size() == 3, "Verified parser did not produce all synthetic entries");
        check(labels(stage).contains("Install list verified") && labels(stage).contains("Game files not acquired"), "Install-list retention overclaims game coverage");
        if (!afterRestart) {
            check(backend.contentCalls.get() == 4 && backend.configurationCalls.get() == 1, "Unexpected transfer scope");
            check(backend.references(0) == 2 && backend.references(1) == 0, "Install membership is not exact-build scoped");
        }
        TextField filter = (TextField) byId(stage, "install-entry-filter");
        filter.requestFocus();
        for (char letter : "Windows".toCharArray()) {
            filter.setText(filter.getText() + letter);
            check(stage.getScene().getFocusOwner() == filter, "Typing a multi-character filter lost focus");
        }
        check(table(stage).getItems().size() == 2 && ((Label) byId(stage, "install-entry-count")).getText().equals("2 of 3 verified entries"), "Tag filtering or visible count is incorrect");
        filter.setText("no-such-entry");
        check(table(stage).getItems().isEmpty() && ((Label) byId(stage, "install-entry-count")).getText().equals("0 of 3 verified entries"), "No-match state is not honest");
        filter.setText("");
        table(stage).getSelectionModel().select(0);
        TextArea fullName = (TextArea) byId(stage, "install-selected-name");
        check(fullName.getText().equals(InstallFixtureBackend.FIRST_NAME), "Full selected name is truncated or fabricated");
        table(stage).requestFocus();
        table(stage).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        check(stage.getScene().getFocusOwner() == fullName, "Enter did not focus the full entry name");
        fullName.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
        check(stage.getScene().getFocusOwner() == table(stage), "Escape did not return focus to the entry table");
        check(labels(stage).contains("Windows (type 1)") && labels(stage).contains("enUS (type 2)"), "Entry details lost verified tags");
        TitledPane source = (TitledPane) byId(stage, "install-source-details"); source.setAnimated(false); source.setExpanded(true);
        TextArea proof = (TextArea) CaptureAtlas.accessible(stage, "ACTUAL LIST VERIFICATION SOURCE");
        check(proof.getText().startsWith("https://us.cdn.blizzard.com/tpr/wow/data/"), "Actual verification source was replaced by a config or invented path");
        reveal(stage, proof);
        CaptureAtlas.later(() -> {
            capture(stage, afterRestart ? "install-offline-source-1280x800.png" : "install-retained-source-1280x800.png");
            source.setExpanded(false);
            captureEntrySections(stage, afterRestart);
        });
    }

    private static void captureEntrySections(Stage stage, boolean afterRestart) {
        reveal(stage, byId(stage, "install-entry-filter"));
        CaptureAtlas.later(() -> {
            capture(stage, afterRestart ? "install-offline-entries-1280x800.png" : "install-retained-entries-1280x800.png");
            reveal(stage, byId(stage, "install-entry-detail"));
            CaptureAtlas.later(() -> {
                capture(stage, afterRestart ? "install-offline-detail-1280x800.png" : "install-retained-detail-1280x800.png");
                scaleReview(stage, afterRestart);
            });
        });
    }

    private static void scaleReview(Stage stage, boolean afterRestart) {
        CaptureAtlas.button(stage, "Settings").fire();
        CaptureAtlas.setCombo(CaptureAtlas.accessible(stage, "Application text scale"), "200%");
        CaptureAtlas.navigate(stage, "Archive");
        reveal(stage, byId(stage, "install-entry-filter"));
        CaptureAtlas.later(() -> {
            capture(stage, afterRestart ? "install-offline-entries-200pct-1280x800.png" : "install-retained-entries-200pct-1280x800.png");
            CaptureAtlas.button(stage, "Settings").fire();
            CaptureAtlas.setCombo(CaptureAtlas.accessible(stage, "Application text scale"), "100%");
            CaptureAtlas.navigate(stage, "Archive");
            if (afterRestart) otherBuildOffline(stage); else finish();
        });
    }

    private static void reopenOffline(Stage stage) {
        check(labels(stage).contains("Metadata only"), "Offline startup claimed retention without explicit verification");
        click(stage, "install-reopen");
        await(() -> optional(stage, "install-entry-table") != null, () -> {
            check(backend.transportCalls.get() == 0, "Offline reopening touched transport");
            inspectEntries(stage, true);
        });
    }

    private static void otherBuildOffline(Stage stage) {
        openBuild(stage, 1);
        check(optional(stage, "install-entry-table") == null && labels(stage).contains("Metadata only"), "Retained entries leaked into another exact build");
        click(stage, "install-reopen");
        await(() -> labels(stage).contains("No verified retained list exists"), () -> {
            check(backend.transportCalls.get() == 0, "Missing offline build triggered implicit acquisition");
            openBuild(stage, 0);
            click(stage, "install-reopen");
            await(() -> optional(stage, "install-entry-table") != null, () -> corruptReopen(stage));
        });
    }

    private static void corruptReopen(Stage stage) {
        try {backend.corruptRetainedList();} catch (Exception failure) {throw new IllegalStateException(failure);}
        click(stage, "install-reopen");
        await(() -> labels(stage).contains("did not pass verification"), () -> {
            check(optional(stage, "install-entry-table") == null, "Failed local verification left a falsely verified table");
            check(labels(stage).contains("Metadata only") && !labels(stage).contains("Install list verified"), "Failed local verification retained a ready coverage label");
            check(backend.transportCalls.get() == 0, "Corrupt offline content triggered automatic repair HTTP");
            check(backend.references(0) == 2, "Corruption cleanup destroyed source membership evidence");
            capture(stage, "install-corrupt-offline-1280x800.png");
            finish();
        });
    }

    @SuppressWarnings("unchecked")
    private static void openBuild(Stage stage, int index) {
        ComboBox<Workspace> picker = (ComboBox<Workspace>) picker(stage);
        Workspace workspace = picker.getItems().stream().filter(value -> value.build().snapshotId().equals(backend.builds().get(index).snapshotId())).findFirst().orElseThrow();
        picker.setValue(workspace);
        click(stage, "open-install-files");
        layout(stage);
    }
    private static ComboBox<?> picker(Stage stage) {return (ComboBox<?>) byId(stage, "saved-workspace-picker");}
    @SuppressWarnings("unchecked")
    private static TableView<InstallInspection.Entry> table(Stage stage) {return (TableView<InstallInspection.Entry>) byId(stage, "install-entry-table");}
    private static void click(Stage stage, String id) {((Button) byId(stage, id)).fire();}
    private static Node byId(Stage stage, String id) {return CaptureAtlas.byId(stage, id);}
    private static Node optional(Stage stage, String id) {layout(stage); return stage.getScene().lookup("#" + id);}
    private static String labels(Stage stage) {
        layout(stage);
        return stage.getScene().getRoot().lookupAll(".label").stream().filter(node -> node instanceof Label).map(node -> ((Label) node).getText()).reduce("", (a, b) -> a + "\n" + b);
    }
    private static void layout(Stage stage) {stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();}
    private static void reveal(Stage stage, Node target) {
        layout(stage);
        ScrollPane scroll = (ScrollPane) stage.getScene().getRoot().lookup(".workspace-scroll");
        Bounds content = scroll.getContent().localToScene(scroll.getContent().getBoundsInLocal());
        Bounds node = target.localToScene(target.getBoundsInLocal());
        double range = scroll.getContent().getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
        scroll.setVvalue(range <= 0 ? 0 : Math.max(0, Math.min(1, (node.getMinY() - content.getMinY()) / range)));
        layout(stage);
    }
    private static void assertVisible(Stage stage, Node node, String message) {
        layout(stage);
        ScrollPane scroll = (ScrollPane) stage.getScene().getRoot().lookup(".workspace-scroll");
        Node viewport = scroll.lookup(".viewport");
        Bounds frame = viewport.localToScene(viewport.getBoundsInLocal());
        Bounds value = node.localToScene(node.getBoundsInLocal());
        check(value.getMinY() >= frame.getMinY() - 1 && value.getMaxY() <= frame.getMaxY() + 1, message);
    }
    private static void capture(Stage stage, String name) {CaptureAtlas.capture(stage, name, 1280, 800);}
    private static void await(BooleanSupplier ready, Runnable next) {if (ready.getAsBoolean()) Platform.runLater(next); else CaptureAtlas.later(() -> await(ready, next));}
    private static void check(boolean condition, String message) {if (!condition) throw new IllegalStateException(message);}
    private static void requireDisplay(String value) {if (value.isBlank() || !value.equals(System.getenv("DISPLAY"))) throw new IllegalStateException("Explicit isolated display must match DISPLAY");}
    private static void finish() {
        check(metadataCalls.get() == 0, "Unexpected metadata discovery");
        if (offline) check(backend.transportCalls.get() == 0, "Offline verification touched transport");
        finished = true;
        System.out.println("Install UI phase " + (offline ? "offline" : "acquire") + " OK");
        Platform.exit();
    }
}
