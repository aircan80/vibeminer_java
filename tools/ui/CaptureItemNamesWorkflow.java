import io.vibeminer.application.DiscoveryService;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.item.ItemNamesFixtureBackend;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Native authored Item/Sparse acceptance. Real readers, schema, CAS, SQLite and services;
 * explicitly synthetic root/EN proof port. No live data, official TACT proof or user catalog.
 */
public final class CaptureItemNamesWorkflow {
    private static ItemNamesFixtureBackend fixture;
    private static Stage stage;
    private static boolean restart;
    private static volatile boolean finished;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (args.length == 4
                && args[0].equals("--isolated-display")
                && args[2].equals("--output")) {
            requireDisplay(args[1]);
            Path output = Path.of(args[3]).toAbsolutePath();
            Files.createDirectories(output);
            Path temp = Files.createTempDirectory("vibeminer-names-ui-");
            Process active = null;
            int total = 0;
            try {
                for (String phase : List.of("prepare", "restart")) {
                    active =
                            new ProcessBuilder(
                                            Path.of(System.getProperty("java.home"), "bin", "java")
                                                    .toString(),
                                            "--enable-native-access=ALL-UNNAMED",
                                            "-cp",
                                            System.getProperty("java.class.path"),
                                            CaptureItemNamesWorkflow.class.getName(),
                                            "--worker",
                                            phase,
                                            args[1],
                                            output.toString(),
                                            temp.toString())
                                    .inheritIO()
                                    .start();
                    require(
                            active.waitFor(180, TimeUnit.SECONDS),
                            "Names worker deadline: " + phase);
                    require(active.exitValue() == 0, "Names worker failed: " + phase);
                    total +=
                            Integer.parseInt(
                                    Files.readString(temp.resolve(phase + ".assertions")).strip());
                }
                System.out.println(
                        "Item names UI OK; "
                                + total
                                + " assertions; two JVMs; 61 authored Item anchors; real paired"
                                + " readers/schema/CAS/SQLite; synthetic root/EN proof port; zero"
                                + " HTTP");
            } finally {
                if (retire(active, TimeUnit.SECONDS.toNanos(10))) {
                    try (var paths = Files.walk(temp)) {
                        for (Path p : paths.sorted(Comparator.reverseOrder()).toList())
                            Files.deleteIfExists(p);
                    }
                } else
                    System.err.println(
                            "Worker retirement unconfirmed; preserving fixture store " + temp);
            }
            return;
        }
        require(
                args.length == 5 && args[0].equals("--worker"),
                "Usage: --isolated-display :N --output DIRECTORY");
        requireDisplay(args[2]);
        restart = args[1].equals("restart");
        CaptureAtlas.output = Path.of(args[3]);
        Path temp = Path.of(args[4]);
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, error) -> {
                    error.printStackTrace();
                    System.exit(1);
                });
        try (var resources = new ItemNamesFixtureBackend(temp.resolve("data"), !restart)) {
            fixture = resources;
            AtlasApplication.configure(
                    new DiscoveryService() {
                        public List<BuildSnapshot> fixtureBuilds() {
                            return fixture.builds();
                        }

                        public CompletionStage<List<BuildSnapshot>> refresh(String p, String r) {
                            throw new AssertionError("No discovery in names workflow");
                        }
                    },
                    fixture.workspaces(),
                    () -> {
                        throw new AssertionError("No products request");
                    },
                    null,
                    fixture.rootService(),
                    fixture.selectedService(),
                    fixture.itemService(),
                    null,
                    null,
                    fixture.namesService(),
                    true);
            Thread deadline =
                    new Thread(
                            () -> {
                                try {
                                    Thread.sleep(150000);
                                } catch (InterruptedException e) {
                                    return;
                                }
                                if (!finished) {
                                    System.err.println("Names UI deadline");
                                    System.exit(2);
                                }
                            });
            deadline.setDaemon(true);
            deadline.start();
            Thread controller =
                    new Thread(
                            () -> {
                                try {
                                    for (int i = 0; i < 100; i++) {
                                        try {
                                            stage =
                                                    fx(
                                                            () ->
                                                                    Window.getWindows().stream()
                                                                            .filter(
                                                                                    w ->
                                                                                            w
                                                                                                            instanceof
                                                                                                            Stage
                                                                                                    && w
                                                                                                            .isShowing())
                                                                            .map(w -> (Stage) w)
                                                                            .findFirst()
                                                                            .orElse(null));
                                            if (stage != null) break;
                                        } catch (IllegalStateException notStarted) {
                                        }
                                        Thread.sleep(100);
                                    }
                                    require(stage != null, "Native stage missing");
                                    fx(
                                            () -> {
                                                stage.setX(0);
                                                stage.setY(0);
                                                stage.setWidth(1280);
                                                stage.setHeight(800);
                                                return null;
                                            });
                                    until(() -> picker().getItems().size() == 3);
                                    check(
                                            fixture.prepares.get()
                                                            + fixture.pages.get()
                                                            + fixture.lookups.get()
                                                    == 0,
                                            "Startup performed names work");
                                    openWorkspace(0, "enUS");
                                    navigate("Database");
                                    check(
                                            optional("item-row-table") == null,
                                            "Database auto-browsed Item");
                                    inspectFile("841626");
                                    click("database-item-mode");
                                    click("item-prepare");
                                    waitNode("item-record-id");
                                    click("database-names-mode");
                                    check(
                                            optional("names-prepare") == null,
                                            "Names prepared without selected Sparse");
                                    int before =
                                            fixture.prepares.get()
                                                    + fixture.pages.get()
                                                    + fixture.lookups.get();
                                    shot(
                                            restart
                                                    ? "names-restart-missing-selection-1280x800.png"
                                                    : "names-missing-sparse-1280x800.png",
                                            "names-status");
                                    click("names-inspect-sparse");
                                    check(
                                            fx(
                                                    () ->
                                                            ((TextField) node("file-id-input"))
                                                                    .getText()
                                                                    .equals("1572924")),
                                            "Missing Sparse navigation did not seed correct"
                                                    + " FileDataID");
                                    check(
                                            fixture.prepares.get()
                                                            + fixture.pages.get()
                                                            + fixture.lookups.get()
                                                    == before,
                                            "Missing Sparse navigation performed names work");
                                    inspectFile("1572924");
                                    click("database-item-mode");
                                    check(
                                            optional("item-record-id") != null,
                                            "Selecting Sparse erased independent Item handle");
                                    click("database-names-mode");
                                    waitNode("names-prepare");
                                    check(
                                            optional("names-row-table") == null,
                                            "Source selection auto-browsed names");
                                    shot(
                                            restart
                                                    ? "names-restart-unprepared-1280x800.png"
                                                    : "names-unprepared-1280x800.png",
                                            "names-prepare");
                                    if (restart) restartJourney();
                                    else firstJourney();
                                    until(() -> fixture.activeReaders.get() == 0);
                                    check(fixture.outboundCalls() == 0, "Names UI used transport");
                                    Files.writeString(
                                            temp.resolve(args[1] + ".assertions"),
                                            Integer.toString(assertions));
                                    finished = true;
                                    System.out.println(
                                            "Names UI "
                                                    + args[1]
                                                    + " phase OK: "
                                                    + assertions
                                                    + " assertions");
                                    Platform.exit();
                                } catch (Throwable e) {
                                    e.printStackTrace();
                                    System.exit(1);
                                }
                            });
            controller.setDaemon(true);
            controller.start();
            Application.launch(AtlasApplication.class);
            require(finished, "Names UI unfinished");
        }
    }

    private static void firstJourney() throws Exception {
        fixture.holdPreparation.set(true);
        fixture.preparationEntered.set(false);
        click("names-prepare");
        until(() -> fixture.preparationEntered.get());
        check(optional("names-row-table") == null, "Staged names became ready");
        shot("names-preparing-1280x800.png", "names-cancel");
        click("names-cancel");
        fixture.holdPreparation.set(false);
        until(() -> fixture.activeReaders.get() == 0);
        check(optional("names-row-table") == null, "Cancelled preparation published UI rows");
        shot("names-cancelled-1280x800.png", "names-status");
        prepareNames();
        browse();
        check(
                rows().size() == 50 && rows().getFirst().item().recordId() == 1001,
                "First bounded names page wrong");
        check(
                rows().get(1).name().candidate().orElseThrow().value().isEmpty(),
                "Empty physical name lost");
        check(
                rows().get(2).name().outcome() == ItemNameMatch.Outcome.COPY_UNINDEXED,
                "Sparse copy expanded");
        check(
                rows().get(3).name().outcome() == ItemNameMatch.Outcome.NO_PHYSICAL_MATCH,
                "Missing Sparse removed Item anchor");
        assertColumns(3);
        shot("names-page-one-1280x800.png", "names-row-table");
        click("names-next-page");
        waitNode("names-row-table");
        check(
                rows().size() == 11 && rows().getFirst().item().recordId() == 1051,
                "Next names page is not remaining11 anchors");
        check(
                fx(() -> ((Button) node("names-next-page")).isDisabled()),
                "Exhausted names page offers Next");
        shot("names-page-two-1280x800.png", "names-row-table");
        exact("1001");
        check(rows().size() == 1, "Exact named row unavailable");
        check(
                fx(
                        () ->
                                ((TextArea) node("names-display-value"))
                                        .getText()
                                        .equals("Authored sword α")),
                "Unicode candidate changed");
        fx(
                () -> {
                    table().requestFocus();
                    table().getSelectionModel().selectFirst();
                    key(table(), KeyCode.ENTER);
                    return null;
                });
        pause();
        check(
                fx(() -> stage.getScene().getFocusOwner() == node("names-row-value")),
                "Enter did not focus names inspector");
        fx(
                () -> {
                    key(node("names-row-value"), KeyCode.ESCAPE);
                    return null;
                });
        check(
                fx(() -> stage.getScene().getFocusOwner() == table()),
                "Escape did not restore names table");
        check(
                fx(
                        () -> {
                            String raw = ((TextArea) node("names-row-value")).getText();
                            return raw.contains("CLASS_ID")
                                    && raw.contains("SUBCLASS_ID")
                                    && raw.contains("INVENTORY_TYPE")
                                    && raw.contains("ICON_FILE_DATA_ID")
                                    && raw.contains("Raw bits");
                        }),
                "Four Item candidate fields/raw bits are not copyable in paired inspector");
        shot("names-unicode-inspector-1280x800.png", "names-display-value");
        proof();
        exact("1002");
        check(labels().contains("Empty name candidate"), "Valid empty name called missing");
        check(
                fx(() -> ((TextArea) node("names-display-value")).getText().isEmpty()),
                "Empty candidate not copied exactly");
        shot("names-empty-candidate-1280x800.png", "names-status");
        exact("1003");
        check(
                rows().size() == 1 && labels().contains("copy"),
                "Sparse copy lost physical Item anchor");
        shot("names-copy-unindexed-1280x800.png", "names-status");
        exact("1004");
        check(
                rows().size() == 1 && labels().contains("No physical name"),
                "Sparse no-match lost physical Item anchor");
        shot("names-no-name-1280x800.png", "names-status");
        exact("700001");
        check(
                optional("names-row-table") == null && labels().contains("No physical Item match"),
                "Sparse-only record invented Item");
        shot("names-no-item-1280x800.png", "names-status");
        exact("999999");
        check(
                optional("names-row-table") == null && labels().contains("Item copy"),
                "Item copy fabricated joined row");
        shot("names-item-copy-unindexed-1280x800.png", "names-status");
        int queries = fixture.lookups.get();
        setId("0");
        click("names-lookup");
        check(
                fixture.lookups.get() == queries && labels().contains("4294967295"),
                "Invalid positive ID queried");
        browse();
        fx(
                () -> {
                    table().getSelectionModel().selectFirst();
                    return null;
                });
        fx(
                () -> {
                    var input = (TextField) node("names-record-id");
                    input.requestFocus();
                    input.setText("");
                    for (char c : "1001".toCharArray()) input.appendText(String.valueOf(c));
                    return null;
                });
        pause();
        check(
                fx(
                        () ->
                                stage.getScene().getFocusOwner() == node("names-record-id")
                                        && ((TextField) node("names-record-id"))
                                                .getText()
                                                .equals("1001")),
                "Draft typing lost field/focus");
        check(optional("names-row-value") == null, "Draft ID kept stale current inspector");
        fx(
                () -> {
                    ((TextField) node("names-record-id")).fireEvent(new javafx.event.ActionEvent());
                    return null;
                });
        until(() -> optional("names-cancel") == null);
        check(rows().size() == 1, "Return exact lookup failed");
        scale("200%");
        browse();
        assertColumns(2);
        shot("names-page-200pct-1280x800.png", "names-row-table");
        exact("1001");
        shot("names-inspector-200pct-1280x800.png", "names-display-value");
        fixture.holdQuery.set(true);
        fixture.queryEntered.set(false);
        click("names-browse");
        until(() -> fixture.queryEntered.get());
        openWorkspace(1, "enUS");
        fixture.holdQuery.set(false);
        until(() -> fixture.activeReaders.get() == 0);
        navigate("Database");
        click("database-names-mode");
        check(
                optional("names-row-table") == null && optional("names-record-id") == null,
                "Old context names query leaked");
        openWorkspace(0, "deDE");
        navigate("Database");
        click("database-names-mode");
        check(optional("names-prepare") == null, "Unsupported locale offered paired preparation");
        shot("names-unsupported-locale-200pct-1280x800.png", "names-status");
        openWorkspace(0, "enUS");
        inspectFile("841626");
        click("database-item-mode");
        click("item-prepare");
        waitNode("item-record-id");
        inspectFile("1572924");
        click("database-names-mode");
        prepareNames();
        browse();
        check(
                fixture.nameArtifacts() == 1 && fixture.storedItemRows() == 61,
                "Retry/context duplicated artifacts");
    }

    private static void restartJourney() throws Exception {
        check(
                fixture.projects.get() == 0 && fixture.nameArtifacts() == 1,
                "Restart lost shared name artifact or prepared implicitly");
        long storedNames = fixture.storedNameRows();
        prepareNames();
        int preparedProjects = fixture.projects.get();
        browse();
        check(rows().size() == 50, "Restart paired page unavailable");
        exact("1001");
        check(fixture.projects.get() == preparedProjects, "Restart page/lookup reprojected names");
        check(
                fixture.nameArtifacts() == 1
                        && fixture.storedNameRows() == storedNames
                        && fixture.storedItemRows() == 61,
                "Explicit readiness preparation duplicated durable rows/artifacts");
        browse();
        shot("names-restart-offline-1280x800.png", "names-row-table");
        fixture.removeSparseSource();
        setId("1001");
        click("names-lookup");
        until(
                () ->
                        optional("names-row-table") == null
                                && labels().toLowerCase(Locale.ROOT).contains("missing"));
        scale("200%");
        shot("names-missing-sparse-200pct-1280x800.png", "names-status");
        click("database-item-mode");
        check(
                optional("item-record-id") != null,
                "Generic Sparse failure revoked independent Item handle");
        click("item-browse");
        waitNode("item-row-table");
        fixture.restoreSparseSource();
        click("database-names-mode");
        prepareNames();
        browse();
        fixture.corruptItemSource();
        setId("1001");
        click("names-lookup");
        until(
                () ->
                        optional("names-row-table") == null
                                && labels().toLowerCase(Locale.ROOT).contains("verification"));
        shot("names-corrupt-pair-200pct-1280x800.png", "names-status");
        check(optional("names-display-value") == null, "Corrupt pair kept current candidate");
        fixture.restoreItemSource();
        check(fixture.outboundCalls() == 0, "Source failures performed transport");
    }

    private static void prepareNames() throws Exception {
        click("names-prepare");
        waitNode("names-record-id");
        check(optional("names-row-table") == null, "Prepare auto-browsed names");
    }

    private static void browse() throws Exception {
        click("names-browse");
        waitNode("names-row-table");
    }

    private static void setId(String value) throws Exception {
        fx(
                () -> {
                    var f = (TextField) node("names-record-id");
                    f.requestFocus();
                    f.setText(value);
                    return null;
                });
    }

    private static void exact(String value) throws Exception {
        setId(value);
        click("names-lookup");
        until(() -> optional("names-cancel") == null);
        pause();
    }

    private static void navigate(String destination) throws Exception {
        fx(
                () -> {
                    CaptureAtlas.navigate(stage, destination);
                    return null;
                });
        pause();
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
        until(() -> labels().contains("Complete encoded bytes verified"));
        navigate("Database");
    }

    private static void proof() throws Exception {
        fx(
                () -> {
                    ((TitledPane) node("names-source-proof")).setExpanded(true);
                    ((TitledPane) node("names-item-proof")).setExpanded(true);
                    return null;
                });
        pause();
        check(
                fx(
                        () ->
                                textAreas().contains("ItemSparse.dbd")
                                        && textAreas().contains("CC BY-SA")
                                        && textAreas()
                                                .contains(
                                                        "b9a63096cdd99b26967f17f9515377ae62cadfd7")),
                "Paired provider/license/revision not copyable");
        check(
                fx(
                        () ->
                                textAreas().contains("Original acquisition URL")
                                        && textAreas().contains("Declared association")
                                        && textAreas().contains("Not established")),
                "Paired source/proof distinctions missing");
        shot("names-two-source-proof-1280x800.png", "names-sparse-proof-value");
    }

    private static void assertColumns(int count) throws Exception {
        check(fx(() -> table().getColumns().size() == count), "Wrong adaptive names column count");
        check(
                fx(() -> table().getColumns().stream().noneMatch(TableColumn::isSortable)),
                "Names unsupported sorting enabled");
        check(
                fx(
                        () -> {
                            layout();
                            for (var column : table().getColumns()) {
                                var header =
                                        table().lookupAll(".column-header .label").stream()
                                                .filter(
                                                        n ->
                                                                n instanceof Label l
                                                                        && l.getText()
                                                                                .equals(
                                                                                        column
                                                                                                .getText()))
                                                .findFirst()
                                                .orElse(null);
                                if (header == null
                                        || !(header.lookup(".text")
                                                instanceof javafx.scene.text.Text text)
                                        || !text.getText().equals(column.getText())) return false;
                            }
                            return true;
                        }),
                "Rendered names headers are clipped or unavailable");
        check(
                labels().contains("WoWDBDefs") || fx(() -> textAreas().contains("WoWDBDefs")),
                "Candidate provider attribution missing");
    }

    private static void openWorkspace(int buildIndex, String locale) throws Exception {
        fx(
                () -> {
                    var w =
                            picker().getItems().stream()
                                    .filter(
                                            v ->
                                                    v.build()
                                                                    .snapshotId()
                                                                    .equals(
                                                                            fixture.builds()
                                                                                    .get(buildIndex)
                                                                                    .snapshotId())
                                                            && v.locale().equals(locale))
                                    .findFirst()
                                    .orElseThrow();
                    picker().setValue(w);
                    return null;
                });
        pause();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Workspace> picker() {
        return (ComboBox<Workspace>) node("saved-workspace-picker");
    }

    @SuppressWarnings("unchecked")
    private static TableView<NamedItemRow> table() {
        return (TableView<NamedItemRow>) node("names-row-table");
    }

    private static TableView<?> tableOptional() {
        Node n = optional("names-row-table");
        return n instanceof TableView<?> t ? t : null;
    }

    private static List<NamedItemRow> rows() throws Exception {
        return fx(() -> List.copyOf(table().getItems()));
    }

    private static void click(String id) throws Exception {
        fx(
                () -> {
                    var b = (Button) node(id);
                    b.requestFocus();
                    b.fire();
                    return null;
                });
        pause();
    }

    private static void key(Node n, KeyCode code) {
        n.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
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
                    reveal(node(target));
                    CaptureAtlas.capture(stage, name, 1280, 800);
                    return null;
                });
    }

    private static void reveal(Node target) {
        layout();
        var scroll = (ScrollPane) stage.getScene().getRoot().lookup(".workspace-scroll");
        Bounds content = scroll.getContent().localToScene(scroll.getContent().getBoundsInLocal()),
                bounds = target.localToScene(target.getBoundsInLocal());
        double range =
                scroll.getContent().getLayoutBounds().getHeight()
                        - scroll.getViewportBounds().getHeight();
        scroll.setVvalue(
                range <= 0
                        ? 0
                        : Math.max(0, Math.min(1, (bounds.getMinY() - content.getMinY()) / range)));
        layout();
    }

    private static Node node(String id) {
        return CaptureAtlas.byId(stage, id);
    }

    private static Node optional(String id) {
        if (!Platform.isFxApplicationThread())
            try {
                return fx(() -> optional(id));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        layout();
        return stage.getScene().lookup("#" + id);
    }

    private static void layout() {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private static String labels() {
        if (!Platform.isFxApplicationThread())
            try {
                return fx(CaptureItemNamesWorkflow::labels);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        layout();
        return stage.getScene().getRoot().lookupAll(".label").stream()
                .filter(n -> n instanceof Label)
                .map(n -> ((Label) n).getText())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static String textAreas() {
        return stage.getScene().getRoot().lookupAll(".text-area").stream()
                .map(n -> ((TextArea) n).getText())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static <T> T fx(Callable<T> call) throws Exception {
        var f = new CompletableFuture<T>();
        Platform.runLater(
                () -> {
                    try {
                        f.complete(call.call());
                    } catch (Throwable t) {
                        f.completeExceptionally(t);
                    }
                });
        return f.get(10, TimeUnit.SECONDS);
    }

    private static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!fx(condition::getAsBoolean)) {
            require(System.nanoTime() < end, "Timed out waiting for Item UI state");
            Thread.sleep(50);
        }
    }

    private static void waitNode(String id) throws Exception {
        until(() -> optional(id) != null);
        pause();
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(350);
    }

    private static void check(boolean ok, String message) {
        assertions++;
        require(ok, message);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static void requireDisplay(String value) {
        require(
                value.matches(":[1-9][0-9]*") && value.equals(System.getenv("DISPLAY")),
                "Explicit nonzero isolated DISPLAY required");
    }

    /**
     * Cleanup permission requires observed process retirement, including interrupted supervisors.
     */
    private static boolean retire(Process process, long timeoutNanos) {
        if (process == null || !process.isAlive()) return true;
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + timeoutNanos;
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
