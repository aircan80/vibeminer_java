import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.itemexport.*;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.item.ItemNamesFixtureBackend;
import io.vibeminer.infrastructure.itemexport.AtomicItemEvidenceWriter;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/**
 * Native one-row export over authored retained readers/CAS/SQLite and actual atomic JSON writer.
 * The chooser is injected; this does not certify an OS chooser or live Blizzard proof.
 */
public final class CaptureItemEvidenceExportWorkflow {
    private static ItemNamesFixtureBackend fixture;
    private static Stage stage;
    private static ExecutorService workers;
    private static Path output, store, destination;
    private static boolean restart;
    private static volatile boolean finished;
    private static int assertions;
    private static final AtomicInteger captures = new AtomicInteger(),
            saves = new AtomicInteger(),
            chooserCalls = new AtomicInteger();
    private static final AtomicBoolean holdCapture = new AtomicBoolean(),
            captureEntered = new AtomicBoolean(),
            holdBeforeWrite = new AtomicBoolean(),
            writeEntered = new AtomicBoolean(),
            holdAfterCommit = new AtomicBoolean(),
            committed = new AtomicBoolean();
    private static final AtomicBoolean loseReceipt = new AtomicBoolean();
    private static final AtomicReference<ItemEvidenceSnapshot> captured = new AtomicReference<>();
    private static final AtomicReference<ItemEvidenceReceipt> published = new AtomicReference<>();
    private static final AtomicReference<String> chosenPath = new AtomicReference<>();

    public static void main(String[] args) throws Exception {
        if (args.length == 4
                && args[0].equals("--isolated-display")
                && args[2].equals("--output")) {
            requireDisplay(args[1]);
            Path out = Path.of(args[3]).toAbsolutePath();
            Files.createDirectories(out);
            Path temp = Files.createTempDirectory("vibeminer-export-ui-");
            Process active = null;
            int count = 0;
            try {
                for (String phase : List.of("prepare", "restart")) {
                    active =
                            new ProcessBuilder(
                                            Path.of(System.getProperty("java.home"), "bin", "java")
                                                    .toString(),
                                            "--enable-native-access=ALL-UNNAMED",
                                            "-cp",
                                            System.getProperty("java.class.path"),
                                            CaptureItemEvidenceExportWorkflow.class.getName(),
                                            "--worker",
                                            phase,
                                            args[1],
                                            out.toString(),
                                            temp.toString())
                                    .inheritIO()
                                    .start();
                    require(
                            active.waitFor(180, TimeUnit.SECONDS),
                            "Export worker deadline: " + phase);
                    require(active.exitValue() == 0, "Export worker failed: " + phase);
                    count +=
                            Integer.parseInt(
                                    Files.readString(temp.resolve(phase + ".assertions")).strip());
                }
                System.out.println(
                        "Item evidence export UI OK; "
                                + count
                                + " assertions; two JVMs; actual retained"
                                + " readers/schema/CAS/SQLite/service/atomic writer; authored"
                                + " synthetic root/EN proof; injected chooser; zero HTTP");
            } finally {
                if (retire(active, TimeUnit.SECONDS.toNanos(10))) {
                    try (var paths = Files.walk(temp)) {
                        for (Path p : paths.sorted(Comparator.reverseOrder()).toList())
                            Files.deleteIfExists(p);
                    }
                } else System.err.println("Worker retirement unconfirmed; preserving " + temp);
            }
            return;
        }
        require(
                args.length == 5 && args[0].equals("--worker"),
                "Usage: --isolated-display :N --output DIRECTORY");
        requireDisplay(args[2]);
        restart = args[1].equals("restart");
        output = Path.of(args[3]);
        store = Path.of(args[4]);
        CaptureAtlas.output = output;
        Thread.setDefaultUncaughtExceptionHandler(
                (t, e) -> {
                    e.printStackTrace();
                    System.exit(1);
                });
        ExecutorService jobs = Executors.newSingleThreadExecutor();
        workers = jobs;
        try (var resources = new ItemNamesFixtureBackend(store.resolve("data"), !restart)) {
            fixture = resources;
            var backend = fixture.evidenceBackend();
            var writer = new AtomicItemEvidenceWriter(Clock.systemUTC());
            ItemEvidenceWriter recordingWriter =
                    new ItemEvidenceWriter() {
                        public void validateDestination(String path) {
                            writer.validateDestination(path);
                        }

                        public ItemEvidenceReceipt publish(
                                ItemEvidenceSnapshot snapshot,
                                String path,
                                CancellationToken token) {
                            saves.incrementAndGet();
                            writeEntered.set(true);
                            gate(holdBeforeWrite, token);
                            var receipt = writer.publish(snapshot, path, token);
                            published.set(receipt);
                            committed.set(true);
                            // Deliberate post-commit scheduling gate: actual bytes have been
                            // atomically
                            // published. Cancellation must not manufacture a rollback afterward.
                            while (holdAfterCommit.get()) {
                                try {
                                    Thread.sleep(10);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                            }
                            if (loseReceipt.getAndSet(false))
                                throw new ItemEvidenceException(
                                        ItemEvidenceException.Code.COMMIT_UNCERTAIN,
                                        "Native harness deliberately loses receipt after actual"
                                                + " atomic publication");
                            return receipt;
                        }
                    };
            try (var service =
                    new DefaultItemEvidenceService(
                            (selection, token) -> {
                                captures.incrementAndGet();
                                captureEntered.set(true);
                                gate(holdCapture, token);
                                var value = backend.capture(selection, token);
                                captured.set(value);
                                return value;
                            },
                            recordingWriter,
                            jobs)) {
                configure(service);
                Thread deadline =
                        new Thread(
                                () -> {
                                    try {
                                        Thread.sleep(155000);
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                    if (!finished) {
                                        System.err.println("Export UI deadline");
                                        System.exit(2);
                                    }
                                });
                deadline.setDaemon(true);
                deadline.start();
                new Thread(
                                () -> {
                                    try {
                                        for (int attempt = 0; attempt < 100; attempt++) {
                                            try {
                                                stage =
                                                        fx(
                                                                () ->
                                                                        (Stage)
                                                                                Window.getWindows()
                                                                                        .stream()
                                                                                        .filter(
                                                                                                w ->
                                                                                                        w
                                                                                                                        instanceof
                                                                                                                        Stage
                                                                                                                && w
                                                                                                                        .isShowing())
                                                                                        .findFirst()
                                                                                        .orElse(
                                                                                                null));
                                                if (stage != null) break;
                                            } catch (IllegalStateException notStarted) {
                                                if (!"Toolkit not initialized"
                                                        .equals(notStarted.getMessage()))
                                                    throw notStarted;
                                            }
                                            Thread.sleep(100);
                                        }
                                        require(
                                                stage != null,
                                                "Native export stage did not initialize within"
                                                        + " startup deadline");
                                        fx(
                                                () -> {
                                                    stage.setX(0);
                                                    stage.setY(0);
                                                    stage.setWidth(1280);
                                                    stage.setHeight(800);
                                                    return null;
                                                });
                                        until(() -> picker().getItems().size() == 3);
                                        prepareWorkspace();
                                        if (restart) restartJourney();
                                        else firstJourney();
                                        until(() -> fixture.activeReaders.get() == 0);
                                        check(
                                                fixture.outboundCalls() == 0,
                                                "Export workflow attempted transport");
                                        Files.writeString(
                                                store.resolve(args[1] + ".assertions"),
                                                Integer.toString(assertions));
                                        finished = true;
                                        fx(
                                                () -> {
                                                    Platform.exit();
                                                    return null;
                                                });
                                    } catch (Throwable error) {
                                        error.printStackTrace();
                                        try {
                                            fx(
                                                    () -> {
                                                        Stage w =
                                                                optional("export-context") == null
                                                                        ? stage
                                                                        : dialog();
                                                        if (w != null)
                                                            CaptureAtlas.capture(
                                                                    w,
                                                                    "export-failure-"
                                                                            + (restart
                                                                                    ? "restart"
                                                                                    : "prepare")
                                                                            + ".png",
                                                                    (int) w.getScene().getWidth(),
                                                                    (int) w.getScene().getHeight());
                                                        return null;
                                                    });
                                        } catch (Throwable diagnostic) {
                                            diagnostic.printStackTrace();
                                        }
                                        System.exit(1);
                                    }
                                },
                                "export-native-controller")
                        .start();
                Application.launch(AtlasApplication.class);
                require(finished, "Export worker ended before acceptance finished");
            } finally {
                jobs.shutdownNow();
                require(
                        jobs.awaitTermination(10, TimeUnit.SECONDS),
                        "Export workers must retire before fixture stores close");
            }
        } finally {
            jobs.shutdownNow();
            require(
                    jobs.awaitTermination(10, TimeUnit.SECONDS),
                    "Export executor retirement unconfirmed");
        }
    }

    private static void configure(ItemEvidenceService service) {
        AtlasApplication.configure(
                new DiscoveryService() {
                    public List<BuildSnapshot> fixtureBuilds() {
                        return fixture.builds();
                    }

                    public CompletionStage<List<BuildSnapshot>> refresh(String p, String r) {
                        throw new AssertionError("No discovery in export workflow");
                    }
                },
                fixture.workspaces(),
                () -> {
                    throw new AssertionError("No product request");
                },
                null,
                fixture.rootService(),
                fixture.selectedService(),
                fixture.itemService(),
                null,
                null,
                fixture.namesService(),
                service,
                true);
        AtlasApplication.configureItemEvidenceDestinationChooser(
                (window, suggested) -> {
                    chooserCalls.incrementAndGet();
                    return Optional.ofNullable(chosenPath.get());
                });
    }

    private static void gate(AtomicBoolean hold, CancellationToken token) {
        while (hold.get()) {
            token.throwIfCancellationRequested();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Interrupted native gate");
            }
        }
        token.throwIfCancellationRequested();
    }

    private static void prepareWorkspace() throws Exception {
        openWorkspace(0, "enUS");
        inspectFile("841626");
        click("database-item-mode");
        click("item-prepare");
        waitNode("item-record-id");
        inspectFile("1572924");
        click("database-names-mode");
        click("names-prepare");
        waitNode("names-record-id");
    }

    private static void assertContext(boolean paired, double font) throws Exception {
        fx(
                () -> {
                    var context = (Label) node("export-context");
                    String text = context.getText();
                    check(
                            text.contains("1001") && text.contains("enUS"),
                            "Persistent export context omits inspected ItemID/locale");
                    String lower = text.toLowerCase(Locale.ROOT);
                    check(
                            lower.contains("item")
                                    && (paired ? lower.contains("name") : lower.contains("only")),
                            "Persistent export context omits exact selected scope");
                    check(
                            Math.abs(context.getFont().getSize() - font) < 0.01,
                            "Export context font does not match selected scale");
                    var build = (Label) node("export-build-context");
                    check(
                            build.getText().contains(fixture.builds().getFirst().productId())
                                    && build.getText()
                                            .contains(fixture.builds().getFirst().versionLabel()),
                            "Persistent export context omits product/exact build");
                    check(
                            Math.abs(build.getFont().getSize() - font) < 0.01,
                            "Build context font does not match selected scale");
                    check(
                            build.getHeight() >= build.prefHeight(build.getWidth()) - 1,
                            "Persistent build context is clipped");
                    double preferred = context.prefHeight(context.getWidth());
                    System.out.println(
                            "Export header geometry height="
                                    + context.getHeight()
                                    + " preferred="
                                    + preferred
                                    + " width="
                                    + context.getWidth()
                                    + " text="
                                    + text.replace('\n', '|'));
                    check(
                            context.getHeight() >= preferred - 1,
                            "Essential export context clipped below intrinsic wrapped height");
                    return null;
                });
    }

    private static void firstJourney() throws Exception {
        int projects = fixture.projects.get();
        selectRow(1001);
        openExport();
        check(
                captures.get() == 0 && saves.get() == 0 && chooserCalls.get() == 0,
                "Opening export performed hidden work");
        check(
                fx(() -> ((RadioButton) node("export-scope-item")).isSelected()),
                "Item-only was not default");
        check(
                fx(() -> ((Button) node("export-save")).isDisabled()),
                "Save allowed unverified snapshot");
        assertContext(false, 14);
        shot("export-review-100pct.png", "export-context");
        fx(
                () -> {
                    node("export-scope-item").requestFocus();
                    key(node("export-scope-item"), KeyCode.TAB);
                    return null;
                });
        pause();
        check(
                fx(
                        () ->
                                dialog().getScene().getFocusOwner() != null
                                        && dialog().getScene().getFocusOwner()
                                                != node("export-scope-item")),
                "Tab did not advance modal focus");
        fx(
                () -> {
                    key(dialog().getScene().getRoot(), KeyCode.ESCAPE);
                    return null;
                });
        until(() -> optional("export-context") == null);
        check(captures.get() == 0 && saves.get() == 0, "Idle Escape performed export work");
        check(
                fx(() -> stage.getScene().getFocusOwner() == node("names-export-evidence")),
                "Closing review did not restore Export focus");
        openExport();
        verify();
        check(captured.get().selection().names().isEmpty(), "Default capture included names");
        shot("export-item-preview-100pct.png", "export-preview");
        saveCase("item-only.json", 1001, "NOT_INCLUDED", "", true);
        closeExport();

        String[] states = {"PHYSICAL_NAME", "PHYSICAL_NAME", "COPY_UNINDEXED", "NO_PHYSICAL_MATCH"};
        String[] names = {"Authored sword α", "", "", ""};
        for (int i = 0; i < 4; i++) {
            long id = 1001 + i;
            selectRow(id);
            openExport();
            click("export-scope-names");
            int before = captures.get();
            pause();
            check(captures.get() == before, "Changing scope captured without Verify");
            verify();
            check(captured.get().selection().names().isPresent(), "Explicit paired scope lost");
            if (i == 0) {
                expandProof();
                shot("export-paired-proof-100pct.png", "export-proof");
            }
            saveCase("paired-" + id + ".json", id, states[i], names[i], i == 0);
            closeExport();
        }
        check(fixture.projects.get() == projects, "Export reprojected prepared data");
        destinationFailure();
        cancelVerification();
        cancelBeforeWrite();
        uncertainAfterCommit();
        scaledJourney();
        missingPairedSource();
        commitWinsContextChange();
    }

    private static void restartJourney() throws Exception {
        check(Files.exists(store.resolve("paired-1001.json")), "Restart lost actual exported file");
        published.set(null);
        validateExport(
                store.resolve("paired-1001.json"), 1001, "PHYSICAL_NAME", "Authored sword α");
        int projects = fixture.projects.get();
        selectRow(1001);
        openExport();
        click("export-scope-names");
        verify();
        saveCase("restart-paired.json", 1001, "PHYSICAL_NAME", "Authored sword α", false);
        shot("export-restart-saved-100pct.png", "export-receipt");
        closeExport();
        check(fixture.projects.get() == projects, "Restart export reprojected sources");
    }

    private static void destinationFailure() throws Exception {
        selectRow(1001);
        openExport();
        verify();
        var snapshot = captured.get();
        choose(store.resolve("missing-parent").resolve("refused.json"));
        authorize();
        click("export-save");
        until(() -> status().contains("unavailable"));
        check(!Files.exists(destination), "Failed destination created output");
        check(captured.get() == snapshot, "Destination failure changed immutable snapshot");
        shot("export-destination-error-100pct.png", "export-status");
        saveCase("destination-retry.json", 1001, "NOT_INCLUDED", "", false);
        closeExport();
    }

    private static void cancelVerification() throws Exception {
        selectRow(1001);
        openExport();
        holdCapture.set(true);
        captureEntered.set(false);
        click("export-verify");
        until(captureEntered::get);
        click("export-cancel");
        holdCapture.set(false);
        until(() -> fixture.activeReaders.get() == 0);
        check(
                !Files.exists(store.resolve("cancel-capture.json")),
                "Capture cancellation wrote output");
        // The dialog may close on cancellation; a later reopen must still start Item-only.
        closeIfShowing();
    }

    private static void cancelBeforeWrite() throws Exception {
        selectRow(1001);
        openExport();
        verify();
        choose(store.resolve("cancel-before-write.json"));
        authorize();
        writeEntered.set(false);
        holdBeforeWrite.set(true);
        click("export-save");
        until(writeEntered::get);
        click("export-cancel");
        holdBeforeWrite.set(false);
        until(() -> !status().contains("still pending"));
        check(!Files.exists(destination), "Cancellation before writer admission published output");
        shot("export-cancelled-before-write-100pct.png", "export-status");
        closeExport();
    }

    private static void uncertainAfterCommit() throws Exception {
        selectRow(1001);
        openExport();
        verify();
        choose(store.resolve("lost-receipt.json"));
        authorize();
        published.set(null);
        loseReceipt.set(true);
        click("export-save");
        until(() -> status().contains("could not be confirmed"));
        check(Files.exists(destination), "Lost-receipt scenario did not actually publish");
        check(
                fx(() -> ((Button) node("export-save")).isDisabled()),
                "Unknown outcome allowed blind retry");
        validateExport(destination, 1001, "NOT_INCLUDED", "");
        shot("export-commit-uncertain-100pct.png", "export-status");
        closeExport();
    }

    private static void scaledJourney() throws Exception {
        scale("200%");
        selectRow(1001);
        openExport();
        click("export-scope-names");
        assertContext(true, 28);
        shot("export-review-200pct.png", "export-context");
        verify();
        shot("export-preview-200pct.png", "export-preview");
        choose(store.resolve("paired-200pct.json"));
        authorize();
        consentGeometry("export-consent-200pct.png");
        int before = saves.get();
        fx(
                () -> {
                    node("export-destination").requestFocus();
                    key(node("export-destination"), KeyCode.ENTER);
                    return null;
                });
        pause();
        check(saves.get() == before, "Enter unexpectedly committed authorized output");
        published.set(null);
        click("export-save");
        until(() -> status().startsWith("Saved JSON evidence"));
        validateExport(destination, 1001, "PHYSICAL_NAME", "Authored sword α");
        shot("export-saved-200pct.png", "export-receipt");
        closeExport();
        selectRow(1001);
        openExport();
        verify();
        choose(store.resolve("missing-200").resolve("error.json"));
        authorize();
        click("export-save");
        until(() -> status().contains("unavailable"));
        shot("export-destination-error-200pct.png", "export-status");
        closeExport();
        scale("100%");
    }

    private static void missingPairedSource() throws Exception {
        scale("200%");
        selectRow(1001);
        openExport();
        click("export-scope-names");
        fixture.removeSparseSource();
        int before = saves.get();
        click("export-verify");
        until(() -> status().contains("Evidence is no longer ready"));
        check(saves.get() == before, "Failed paired capture tried to save or downgrade scope");
        check(
                fx(() -> node("export-choose-destination").isDisabled()),
                "Failed paired source left destination/save preparation enabled");
        shot("export-paired-source-missing-200pct.png", "export-status");
        closeExport();
        click("database-item-mode");
        check(
                fx(() -> optional("item-record-id") != null),
                "Generic pair failure discarded independent Item readiness");
        fixture.restoreSparseSource();
        inspectFile("1572924");
        click("database-names-mode");
        waitNode("names-prepare");
        check(
                fx(() -> optional("names-row-table") == null),
                "Source restoration auto-prepared names");
        click("names-prepare");
        waitNode("names-record-id");
        scale("100%");
    }

    private static void commitWinsContextChange() throws Exception {
        selectRow(1001);
        openExport();
        click("export-scope-names");
        verify();
        choose(store.resolve("commit-wins.json"));
        authorize();
        committed.set(false);
        holdAfterCommit.set(true);
        published.set(null);
        click("export-save");
        until(committed::get);
        click("export-cancel");
        // Programmatic workspace change is a presenter/context test while a modal owns input;
        // this does not claim a user can operate disabled owner controls.
        openWorkspace(1, "enUS");
        holdAfterCommit.set(false);
        until(() -> status().startsWith("Saved JSON evidence"));
        check(
                status().contains("previous source context"),
                "Committed old context attached to new inspector");
        validateExport(destination, 1001, "PHYSICAL_NAME", "Authored sword α");
        shot("export-commit-wins-context-100pct.png", "export-receipt");
        closeExport();
    }

    private static void selectRow(long id) throws Exception {
        click("database-names-mode");
        fx(
                () -> {
                    ((TextField) node("names-record-id")).setText(Long.toString(id));
                    ((Button) node("names-lookup")).fire();
                    return null;
                });
        until(
                () -> {
                    var t = (TableView<?>) optional("names-row-table");
                    return t != null
                            && t.getItems().size() == 1
                            && ((NamedItemRow) t.getItems().getFirst()).item().recordId() == id;
                });
        fx(
                () -> {
                    var t = (TableView<?>) node("names-row-table");
                    t.getSelectionModel().selectFirst();
                    key(t, KeyCode.ENTER);
                    return null;
                });
        waitNode("names-row-value");
    }

    private static void openExport() throws Exception {
        Platform.runLater(
                () -> {
                    var button =
                            stage.getScene().getRoot().lookupAll(".button").stream()
                                    .filter(
                                            n ->
                                                    n instanceof Button b
                                                            && b.getText()
                                                                    .startsWith("Export evidence"))
                                    .map(n -> (Button) n)
                                    .findFirst()
                                    .orElseThrow();
                    button.requestFocus();
                    button.fire();
                });
        waitNode("export-context");
    }

    private static void verify() throws Exception {
        var previous = captured.get();
        click("export-verify");
        until(
                () ->
                        captured.get() != previous
                                && optional("export-choose-destination") != null
                                && !node("export-choose-destination").isDisabled());
    }

    private static void choose(Path path) throws Exception {
        destination = path.toAbsolutePath().normalize();
        chosenPath.set(destination.toString());
        click("export-choose-destination");
        check(
                fx(
                        () ->
                                ((Label) node("export-destination"))
                                        .getText()
                                        .equals(destination.toString())),
                "Displayed destination differs from chooser result");
        check(
                fx(() -> ((Button) node("export-save")).isDisabled()),
                "Choosing path implicitly authorized replacement");
        click("export-copy-destination");
        check(
                fx(() -> destination.toString().equals(Clipboard.getSystemClipboard().getString())),
                "Copy exact path changed selected destination");
    }

    private static void authorize() throws Exception {
        fx(
                () -> {
                    var c = (CheckBox) node("export-authorize-replacement");
                    if (!c.isSelected()) c.fire();
                    return null;
                });
        pause();
        check(
                fx(() -> !node("export-save").isDisabled()),
                "Explicit replacement consent did not enable Save");
    }

    private static void saveCase(
            String filename, long id, String state, String name, boolean screenshot)
            throws Exception {
        Path file = store.resolve(filename);
        Files.writeString(file, "authored existing destination sentinel");
        choose(file);
        authorize();
        if (screenshot)
            consentGeometry("export-" + filename.replace(".json", "") + "-consent-100pct.png");
        published.set(null);
        click("export-save");
        until(() -> status().startsWith("Saved JSON evidence"));
        validateExport(file, id, state, name);
        check(fx(() -> node("export-receipt") != null), "Confirmed publication omitted receipt");
    }

    private static void closeExport() throws Exception {
        if (fx(() -> optional("export-context") != null)) {
            click("export-cancel");
            until(() -> optional("export-context") == null);
        }
        workers.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private static void closeIfShowing() throws Exception {
        if (fx(() -> optional("export-context") != null)) closeExport();
    }

    private static String status() {
        if (!Platform.isFxApplicationThread())
            try {
                return fx(CaptureItemEvidenceExportWorkflow::status);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        Node n = optional("export-status");
        return n instanceof Label l ? l.getText() : "";
    }

    private static void expandProof() throws Exception {
        fx(
                () -> {
                    if (node("export-proof") instanceof TitledPane p) p.setExpanded(true);
                    return null;
                });
        pause();
    }

    private static void scale(String value) throws Exception {
        fx(
                () -> {
                    CaptureAtlas.button(stage, "Settings").fire();
                    CaptureAtlas.setCombo(
                            CaptureAtlas.accessible(stage, "Application text scale"), value);
                    CaptureAtlas.navigate(stage, "Database");
                    return null;
                });
        pause();
    }

    private static void shot(String name, String target) throws Exception {
        fx(
                () -> {
                    reveal(node(target));
                    return null;
                });
        pause();
        fx(
                () -> {
                    var d = dialog();
                    reveal(node(target));
                    CaptureAtlas.capture(
                            d, name, (int) d.getScene().getWidth(), (int) d.getScene().getHeight());
                    return null;
                });
    }

    private static void reveal(Node target) {
        var root = target.getScene().getRoot();
        root.applyCss();
        root.layout();
        ScrollPane scroll = null;
        for (Node parent = target.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof ScrollPane p) scroll = p;
        if (scroll == null) return;
        Bounds c = scroll.getContent().localToScene(scroll.getContent().getBoundsInLocal()),
                b = target.localToScene(target.getBoundsInLocal());
        double range =
                scroll.getContent().getLayoutBounds().getHeight()
                        - scroll.getViewportBounds().getHeight();
        scroll.setVvalue(
                range <= 0 ? 0 : Math.max(0, Math.min(1, (b.getMinY() - c.getMinY()) / range)));
        root.layout();
    }

    private static void consentGeometry(String name) throws Exception {
        fx(
                () -> {
                    reveal(node("export-limit"));
                    return null;
                });
        pause();
        fx(
                () -> {
                    var path = node("export-destination-scroll");
                    var consent = node("export-authorize-replacement");
                    var save = node("export-save");
                    var view =
                            path.getScene().getRoot().lookupAll(".scroll-pane").stream()
                                    .filter(
                                            n ->
                                                    n instanceof ScrollPane
                                                            && n.isVisible()
                                                            && n.getBoundsInLocal().getHeight()
                                                                    > 100)
                                    .map(n -> (ScrollPane) n)
                                    .max(
                                            Comparator.comparingDouble(
                                                    n -> n.getBoundsInLocal().getHeight()))
                                    .orElseThrow();
                    Node viewport = view.lookup(".viewport");
                    Bounds v = viewport.localToScene(viewport.getBoundsInLocal());
                    var d = dialog();
                    CaptureAtlas.capture(
                            d, name, (int) d.getScene().getWidth(), (int) d.getScene().getHeight());
                    check(
                            d.getWidth() <= 1280 && d.getHeight() <= 800,
                            "Export dialog exceeds1280x800 available owner dimensions");
                    for (Node n :
                            List.of(
                                    node("export-limit"),
                                    path,
                                    node("export-copy-destination"),
                                    node("export-choose-destination"),
                                    consent,
                                    save)) {
                        Bounds b = n.localToScene(n.getBoundsInLocal());
                        System.out.println(
                                "Export consent geometry "
                                        + n.getId()
                                        + "="
                                        + b
                                        + " viewport="
                                        + v);
                        check(
                                b.getMinY() >= v.getMinY() - 1 && b.getMaxY() <= v.getMaxY() + 1,
                                "Path/consent/Save outside dialog viewport: "
                                        + n.getId()
                                        + " "
                                        + b
                                        + " vs "
                                        + v);
                        check(
                                b.getMinX() >= v.getMinX() - 1 && b.getMaxX() <= v.getMaxX() + 1,
                                "Consent control overflows horizontally");
                    }
                    return null;
                });
    }

    /**
     * Preserve authored output plus independently chosen expectations for a separate Python strict
     * parser/schema/scalar oracle. The native Java workflow has no Python dependency.
     */
    private static void validateExport(Path file, long id, String nameState, String name)
            throws Exception {
        check(
                Files.size(file) > 0 && Files.size(file) <= ItemEvidenceReceipt.MAXIMUM_UTF8_BYTES,
                "Actual JSON exceeds output bound");
        var receipt = published.get();
        String actualSha =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(file)));
        Path priorReceipt = file.resolveSibling(file.getFileName() + ".receipt");
        if (receipt != null) {
            check(
                    receipt.encodedUtf8Bytes() == Files.size(file),
                    "Receipt bytes differ from actual target");
            check(receipt.sha256().equals(actualSha), "Receipt SHA differs from actual target");
            Files.writeString(
                    priorReceipt, receipt.encodedUtf8Bytes() + "\n" + receipt.sha256() + "\n");
        } else {
            var retained = Files.readAllLines(priorReceipt);
            check(
                    Long.parseLong(retained.get(0)) == Files.size(file),
                    "Original receipt length changed across restart");
            check(retained.get(1).equals(actualSha), "Original receipt SHA changed across restart");
        }
        Path exports = output.resolve("exports");
        Files.createDirectories(exports);
        Files.copy(file, exports.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(
                exports.resolve("expectations.tsv"),
                file.getFileName()
                        + "\t"
                        + id
                        + "\t"
                        + nameState
                        + "\t"
                        + Base64.getEncoder()
                                .encodeToString(
                                        name.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static void inspectFile(String id) throws Exception {
        navigate("Archive");
        click("open-file-lookup");
        fx(
                () -> {
                    ((TextField) node("file-id-input")).setText(id);
                    ((Button) node("file-reopen-evidence")).fire();
                    return null;
                });
        waitNode("file-variant-table");
        fx(
                () -> {
                    ((TableView<?>) node("file-variant-table")).getSelectionModel().selectFirst();
                    return null;
                });
        waitNode("selected-retention-offline");
        click("selected-retention-offline");
        until(() -> text(stage).contains("Complete encoded bytes verified"));
        navigate("Database");
    }

    private static void openWorkspace(int index, String locale) throws Exception {
        fx(
                () -> {
                    picker().setValue(
                                    picker().getItems().stream()
                                            .filter(
                                                    w ->
                                                            w.build()
                                                                            .snapshotId()
                                                                            .equals(
                                                                                    fixture.builds()
                                                                                            .get(
                                                                                                    index)
                                                                                            .snapshotId())
                                                                    && w.locale().equals(locale))
                                            .findFirst()
                                            .orElseThrow());
                    return null;
                });
        pause();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Workspace> picker() {
        return (ComboBox<Workspace>) node("saved-workspace-picker");
    }

    private static void navigate(String destination) throws Exception {
        fx(
                () -> {
                    CaptureAtlas.navigate(stage, destination);
                    return null;
                });
        pause();
    }

    private static void click(String id) throws Exception {
        fx(
                () -> {
                    var b = (ButtonBase) node(id);
                    b.requestFocus();
                    b.fire();
                    return null;
                });
        pause();
    }

    private static void openModal(String id) throws Exception {
        Platform.runLater(() -> ((ButtonBase) node(id)).fire());
        waitNode("export-context");
    }

    private static void waitNode(String id) throws Exception {
        until(() -> optional(id) != null);
        pause();
    }

    private static Node node(String id) {
        var value = optional(id);
        require(value != null, "Missing #" + id);
        return value;
    }

    private static Node optional(String id) {
        for (Window window : Window.getWindows())
            if (window.isShowing() && window.getScene() != null) {
                var root = window.getScene().getRoot();
                root.applyCss();
                root.layout();
                var found = root.lookup("#" + id);
                if (found != null) return found;
            }
        return null;
    }

    private static String text(Stage window) {
        window.getScene().getRoot().applyCss();
        window.getScene().getRoot().layout();
        return window.getScene().getRoot().lookupAll(".label").stream()
                .filter(n -> n instanceof Label)
                .map(n -> ((Label) n).getText())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static Stage dialog() {
        return (Stage) node("export-context").getScene().getWindow();
    }

    private static void key(Node n, KeyCode code) {
        n.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(250);
    }

    private static <T> T fx(Callable<T> task) throws Exception {
        var done = new CompletableFuture<T>();
        Platform.runLater(
                () -> {
                    try {
                        done.complete(task.call());
                    } catch (Throwable e) {
                        done.completeExceptionally(e);
                    }
                });
        return done.get(10, TimeUnit.SECONDS);
    }

    private static void until(BooleanSupplier test) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!fx(test::getAsBoolean)) {
            require(System.nanoTime() < deadline, "Timed out waiting for export UI");
            Thread.sleep(40);
        }
    }

    private static void check(boolean value, String message) {
        assertions++;
        require(value, message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static void requireDisplay(String value) {
        require(
                value.matches(":[1-9][0-9]*") && value.equals(System.getenv("DISPLAY")),
                "Explicit nonzero isolated DISPLAY required");
    }

    private static boolean retire(Process process, long timeout) {
        if (process == null || !process.isAlive()) return true;
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + timeout;
        try {
            process.destroyForcibly();
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return !process.isAlive();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            return true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
