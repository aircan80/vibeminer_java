import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.item.ItemNamesFixtureBackend;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/**
 * Native literal search acceptance over authored paired Item/Sparse data; real readers and
 * services; explicitly synthetic root/EN proof port. No live data, official TACT proof or user
 * catalog.
 */
public final class CaptureItemNameSearchWorkflow {
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
            Path temp = Files.createTempDirectory("vibeminer-literal-names-ui-");
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
                                            CaptureItemNameSearchWorkflow.class.getName(),
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
                        "Item literal search UI OK; "
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
        try (var resources =
                new ItemNamesFixtureBackend(
                        temp.resolve("data"),
                        !restart,
                        ItemNamesFixtureBackend.Corpus.LITERAL_SEARCH)) {
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
                    searchingService(),
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
                                    System.err.println("Literal search UI deadline");
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
                                                    ? "literal-restart-missing-selection-1280x800.png"
                                                    : "literal-missing-sparse-1280x800.png",
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
                                                    ? "literal-restart-unprepared-1280x800.png"
                                                    : "literal-unprepared-1280x800.png",
                                            "names-prepare");
                                    if (restart) restartJourney();
                                    else firstJourney();
                                    until(() -> fixture.activeReaders.get() == 0);
                                    check(
                                            fixture.outboundCalls() == 0,
                                            "Literal search UI used transport");
                                    Files.writeString(
                                            temp.resolve(args[1] + ".assertions"),
                                            Integer.toString(assertions));
                                    finished = true;
                                    System.out.println(
                                            "Literal search UI "
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
            require(finished, "Literal search UI unfinished");
        }
    }

    private static final AtomicInteger searchCalls = new AtomicInteger(),
            backendSearchCalls = new AtomicInteger();
    private static final AtomicBoolean holdSearch = new AtomicBoolean(),
            searchEntered = new AtomicBoolean();
    private static volatile String lastQuery;

    private static ItemNamesService searchingService() {
        var actual = fixture.namesService();
        return new ItemNamesService() {
            public CompletionStage<PreparedItemNames> prepare(ItemNamesRequest r) {
                return actual.prepare(r);
            }

            public CompletionStage<ItemNamesPage> page(
                    PreparedItemNames p, Optional<ItemNamesCursor> c, int size) {
                return actual.page(p, c, size);
            }

            public CompletionStage<ItemNamesLookup> lookup(PreparedItemNames p, long id) {
                return actual.lookup(p, id);
            }

            public CompletionStage<ItemNamesSearchPage> search(
                    PreparedItemNames p,
                    ItemNameLiteralQuery q,
                    Optional<ItemNamesSearchCursor> c,
                    int size) {
                searchCalls.incrementAndGet();
                lastQuery = q.text();
                searchEntered.set(true);
                var result = new CompletableFuture<ItemNamesSearchPage>();
                Thread task =
                        new Thread(
                                () -> {
                                    try {
                                        while (holdSearch.get() && !result.isCancelled())
                                            Thread.sleep(10);
                                        if (result.isCancelled()) return;
                                        backendSearchCalls.incrementAndGet();
                                        var work =
                                                actual.search(p, q, c, size).toCompletableFuture();
                                        result.whenComplete(
                                                (v, e) -> {
                                                    if (result.isCancelled()) work.cancel(true);
                                                });
                                        work.whenComplete(
                                                (v, e) -> {
                                                    if (e == null) result.complete(v);
                                                    else result.completeExceptionally(e);
                                                });
                                    } catch (Throwable e) {
                                        result.completeExceptionally(e);
                                    }
                                });
                task.setDaemon(true);
                task.start();
                return result;
            }

            public void close() {
                throw new AssertionError("UI must not close borrowed names service");
            }
        };
    }

    private static void firstJourney() throws Exception {
        prepareNames();
        check(searchCalls.get() == 0, "Preparation started literal search");
        setSearch("Authored");
        check(searchCalls.get() == 0, "Typing started search");
        click("names-search");
        waitSearch();
        check(rows().size() == 50, "Expected50matching physical anchors");
        check(
                fx(() -> !((Button) node("names-next-page")).isDisabled()),
                "Search continuation unavailable");
        shot("literal-search-page-one-1280x800.png", "names-row-table");
        int prior = searchCalls.get();
        click("names-next-page");
        waitSearch();
        check(
                searchCalls.get() == prior + 1 && rows().size() > 0 && rows().size() < 50,
                "Search continuation did not query remaining matches");
        check(rows().getFirst().item().recordId() > 1050, "Continuation repeated first match page");
        shot("literal-search-continued-1280x800.png", "names-row-table");
        search("α");
        check(
                rows().size() == 1 && rows().getFirst().item().recordId() == 1001,
                "Greek literal positive mismatch");
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
                "Search row Enter did not focus inspector");
        check(
                fx(() -> ((TextArea) node("names-display-value")).getText().contains("α")),
                "Unicode candidate changed");
        fx(
                () -> {
                    key(node("names-row-value"), KeyCode.ESCAPE);
                    return null;
                });
        check(
                fx(() -> stage.getScene().getFocusOwner() == table()),
                "Search inspector Escape did not restore table");
        shot("literal-search-unicode-inspector-1280x800.png", "names-display-value");
        proof();
        int calls = searchCalls.get(), pages = fixture.pages.get(), lookups = fixture.lookups.get();
        click("names-search-clear");
        check(
                searchCalls.get() == calls
                        && fixture.pages.get() == pages
                        && fixture.lookups.get() == lookups,
                "Clear performed query");
        check(
                optional("names-row-table") == null && editorText().isEmpty(),
                "Clear retained current search result/draft");
        check(labels().contains("Browse"), "Clear did not return Browse mode");
        shot("literal-clear-no-query-1280x800.png", "names-search-input");
        browse();
        check(rows().size() == 50, "Explicit Browse after Clear unavailable");
        exact("1001");
        check(rows().size() == 1, "Exact Item ID no longer works independently");
        ingressCases();
        keyboardCases();
        search("Authored");
        setSearch("changed draft");
        check(
                fx(() -> ((Button) node("names-next-page")).isDisabled()),
                "Draft edit allowed executed-query continuation");
        check(
                optional("names-row-value") == null
                        && labels().toLowerCase(Locale.ROOT).contains("previous"),
                "Draft did not clear current inspector/label previous query");
        check(
                fx(() -> ((TextArea) node("names-executed-query")).getText().equals("Authored")),
                "Executed query not copyable independently of draft");
        fx(
                () -> {
                    ((TitledPane) node("names-search-proof")).setExpanded(true);
                    return null;
                });
        pause();
        shot("literal-draft-previous-query-1280x800.png", "names-executed-query");
        scale("200%");
        search("Authored");
        assertColumns(2);
        shot("literal-search-page-200pct-1280x800.png", "names-row-table");
        shot("literal-search-controls-200pct-1280x800.png", "names-search-input");
        shot("literal-search-cost-actions-200pct-1280x800.png", "names-search-cost");
        check(
                fx(
                        () ->
                                visibleInWorkspace(node("names-search-cost"))
                                        && visibleInWorkspace(node("names-search"))
                                        && visibleInWorkspace(node("names-search-clear"))),
                "200% scan disclosure and Search/Clear actions cannot be reviewed together");
        fx(
                () -> {
                    var area = (TextArea) node("names-search-input");
                    area.replaceText(0, area.getLength(), "A\r");
                    return null;
                });
        pause();
        check(
                fx(
                        () ->
                                node("names-search-error").isVisible()
                                        && ((Button) node("names-search")).isDisabled()),
                "200% rejected literal input lacks local visible error/latch");
        shot("literal-ingress-rejected-200pct-1280x800.png", "names-search-error");
        click("names-search-clear");
        check(
                fx(() -> !node("names-search-error").isVisible()),
                "Clear retained local literal error");
        search("α");
        check(
                rows().size() == 1 && rows().getFirst().item().recordId() == 1001,
                "200% Greek search row unavailable");
        fx(
                () -> {
                    System.out.println(
                            "200% Greek inspector setup rows="
                                    + table().getItems().size()
                                    + " selectedBefore="
                                    + table().getSelectionModel().getSelectedItem()
                                    + " detailBefore="
                                    + (optional("names-display-value") != null));
                    table().requestFocus();
                    table().getSelectionModel().selectFirst();
                    key(table(), KeyCode.ENTER);
                    return null;
                });
        pause();
        check(
                optional("names-display-value") != null
                        && fx(
                                () ->
                                        ((TextArea) node("names-display-value"))
                                                .getText()
                                                .contains("α")),
                "200% explicit selection failed full Unicode inspector");
        shot("literal-search-inspector-200pct-1280x800.png", "names-display-value");
        holdSearch.set(true);
        searchEntered.set(false);
        setSearch("Authored");
        click("names-search");
        until(() -> searchEntered.get());
        check(optional("names-row-table") == null, "Busy search exposed old rows as current");
        shot("literal-search-busy-200pct-1280x800.png", "names-cancel");
        click("names-cancel");
        holdSearch.set(false);
        pause();
        check(optional("names-row-table") == null, "Cancelled held search published rows");
        shot("literal-search-cancelled-200pct-1280x800.png", "names-status");
        holdSearch.set(true);
        searchEntered.set(false);
        setSearch("Authored");
        click("names-search");
        until(() -> searchEntered.get());
        openWorkspace(1, "enUS");
        holdSearch.set(false);
        pause();
        navigate("Database");
        click("database-names-mode");
        check(
                optional("names-row-table") == null && optional("names-search-input") == null,
                "Stale search painted into other source pair");
        openWorkspace(0, "enUS");
        inspectFile("841626");
        click("database-item-mode");
        click("item-prepare");
        waitNode("item-record-id");
        inspectFile("1572924");
        click("database-names-mode");
        prepareNames();
        search("α");
        check(fixture.outboundCalls() == 0, "Literal queries performed transport");
    }

    private static void restartJourney() throws Exception {
        check(searchCalls.get() == 0, "Restart searched implicitly");
        prepareNames();
        int projects = fixture.projects.get();
        search("Authored");
        check(rows().size() == 50, "Restart search first page unavailable");
        click("names-next-page");
        waitSearch();
        check(rows().size() > 0 && rows().size() < 50, "Restart search continuation unavailable");
        check(fixture.projects.get() == projects, "Search reprojected names");
        shot("literal-search-restart-offline-1280x800.png", "names-row-table");
        search("not-present-literal");
        check(
                optional("names-row-table") == null
                        && labels().contains("No matching Display candidate"),
                "Scoped literal no-match lost coverage");
        shot("literal-search-scoped-no-match-1280x800.png", "names-status");
        scale("200%");
        fixture.removeSparseSource();
        setSearch("Authored");
        click("names-search");
        until(
                () ->
                        optional("names-row-table") == null
                                && labels().toLowerCase(Locale.ROOT).contains("missing"));
        shot("literal-search-missing-pair-200pct-1280x800.png", "names-status");
        click("database-item-mode");
        check(
                optional("item-record-id") != null,
                "Generic search pair failure revoked independent Item");
        click("item-browse");
        waitNode("item-row-table");
        fixture.restoreSparseSource();
        click("database-names-mode");
        prepareNames();
        search("α");
        check(fixture.outboundCalls() == 0, "Restart/missing search used transport");
    }

    private static void ingressCases() throws Exception {
        check(
                fx(
                        () -> {
                            var local =
                                    new io.vibeminer.desktop.ItemNamesPresentationModel(
                                            fixture.namesService(), Runnable::run, state -> {});
                            local.setSearchInput("safe");
                            var editor =
                                    new io.vibeminer.desktop.ItemNamesPane.LiteralEditor(local);
                            boolean rejected = !local.setSearchInput("safe\r\n");
                            editor.restoreFromModel();
                            boolean okay =
                                    rejected
                                            && local.state().searchBlocked()
                                            && editor.getText().equals("safe");
                            local.close();
                            return okay;
                        }),
                "Controlled model restoration silently transformed unsupported raw input");
        for (String raw : List.of("A\r\nB", "A\0B", "A\u0001B", "A\u007fB")) {
            click("names-search-clear");
            setSearch("prior");
            int before = searchCalls.get();
            String received =
                    fx(
                            () -> {
                                var content = new ClipboardContent();
                                content.putString(raw);
                                Clipboard.getSystemClipboard().setContent(content);
                                String actual = Clipboard.getSystemClipboard().getString();
                                System.out.println(
                                        "Literal clipboard supplied="
                                                + scalars(raw)
                                                + " received="
                                                + scalars(actual));
                                ((io.vibeminer.desktop.ItemNamesPane.LiteralEditor)
                                                node("names-search-input"))
                                        .paste();
                                return actual;
                            });
            pause();
            check(received != null, "Native clipboard did not expose string data");
            check(searchCalls.get() == before, "Clipboard paste queried without explicit Search");
            if (!io.vibeminer.desktop.ItemNamesPresentationModel.acceptsEditorText(received)) {
                check(
                        editorText().equals("prior"),
                        "Rejected clipboard API text silently changed draft");
                check(
                        fx(() -> ((Button) node("names-search")).isDisabled()),
                        "Unsupported clipboard API text did not latch Search");
                pressSearchEnter();
                check(searchCalls.get() == before, "Rejected paste searched old/altered query");
            } else {
                String expected = "prior" + received;
                check(
                        editorText().equals(expected),
                        "App changed text exposed by native clipboard API");
                click("names-search");
                waitSearch();
                check(
                        Objects.equals(lastQuery, expected),
                        "Explicit Search did not preserve actual clipboard API text");
            }
            // Exact raw replacement bypasses platform clipboard conversions and must always reject.
            click("names-search-clear");
            setSearch("prior");
            before = searchCalls.get();
            fx(
                    () -> {
                        ((TextArea) node("names-search-input")).replaceText(0, 5, raw);
                        return null;
                    });
            check(
                    editorText().equals("prior")
                            && fx(() -> ((Button) node("names-search")).isDisabled()),
                    "Raw replacement failed prefilter rejection/latch");
            pressSearchEnter();
            check(searchCalls.get() == before, "Rejected raw replacement searched previous draft");
        }
        check(
                fx(
                        () ->
                                node("names-search-error").isVisible()
                                        && node("names-search-error").isManaged()),
                "Rejected input lacks field-adjacent visible error");
        shot("literal-ingress-rejected-1280x800.png", "names-search-input");
        setSearch("valid correction");
        check(
                fx(() -> !node("names-search-error").isVisible()),
                "Valid edit retained local literal error");
        click("names-search-clear");
        check(
                fx(() -> !node("names-search-error").isVisible()),
                "Clear retained local literal error");
        for (String text : List.of(" ", "\t", "a\nb", "é", "e\u0301", "😀", "%_\\'\"")) {
            click("names-search-clear");
            setSearch(text);
            check(editorText().equals(text), "Literal input changed before query");
            click("names-search");
            waitSearch();
            check(Objects.equals(lastQuery, text), "Submitted query normalized/casefolded/trimmed");
        }
    }

    private static void pressSearchEnter() throws Exception {
        fx(
                () -> {
                    key(node("names-search-input"), KeyCode.ENTER);
                    return null;
                });
        pause();
    }

    private static boolean visibleInWorkspace(Node control) {
        layout();
        var scroll = (ScrollPane) stage.getScene().getRoot().lookup(".workspace-scroll");
        Node viewport = scroll.lookup(".viewport");
        Bounds v = viewport.localToScene(viewport.getBoundsInLocal()),
                b = control.localToScene(control.getBoundsInLocal());
        System.out.println(
                "Literal200 geometry id=" + control.getId() + " bounds=" + b + " viewport=" + v);
        return control.isVisible()
                && b.getMinY() >= v.getMinY() - 1
                && b.getMaxY() <= v.getMaxY() + 1
                && b.getMinX() >= v.getMinX() - 1
                && b.getMaxX() <= v.getMaxX() + 1;
    }

    private static String scalars(String text) {
        return text == null
                ? "null"
                : text.codePoints()
                        .mapToObj(c -> String.format("U+%04X", c))
                        .collect(java.util.stream.Collectors.joining(" "));
    }

    private static void keyboardCases() throws Exception {
        click("names-search-clear");
        setSearch("Authored");
        int calls = searchCalls.get();
        fx(
                () -> {
                    var area = (TextArea) node("names-search-input");
                    area.requestFocus();
                    key(area, KeyCode.ENTER);
                    return null;
                });
        waitSearch();
        check(searchCalls.get() == calls + 1, "Enter did not explicitly Search");
        click("names-search-clear");
        setSearch("A");
        calls = searchCalls.get();
        fx(
                () -> {
                    var area = (TextArea) node("names-search-input");
                    area.requestFocus();
                    area.positionCaret(area.getLength());
                    System.out.println(
                            "ShiftEnter before="
                                    + scalars(area.getText())
                                    + " caret="
                                    + area.getCaretPosition()
                                    + " selection="
                                    + area.getSelection()
                                    + " focused="
                                    + (stage.getScene().getFocusOwner() == area));
                    area.fireEvent(
                            new KeyEvent(
                                    KeyEvent.KEY_PRESSED,
                                    "",
                                    "",
                                    KeyCode.ENTER,
                                    true,
                                    false,
                                    false,
                                    false));
                    System.out.println(
                            "ShiftEnter afterPressed="
                                    + scalars(area.getText())
                                    + " calls="
                                    + searchCalls.get());
                    area.fireEvent(
                            new KeyEvent(
                                    KeyEvent.KEY_TYPED,
                                    "\n",
                                    "",
                                    KeyCode.UNDEFINED,
                                    true,
                                    false,
                                    false,
                                    false));
                    area.fireEvent(
                            new KeyEvent(
                                    KeyEvent.KEY_RELEASED,
                                    "",
                                    "",
                                    KeyCode.ENTER,
                                    true,
                                    false,
                                    false,
                                    false));
                    System.out.println(
                            "ShiftEnter afterTypedReleased="
                                    + scalars(area.getText())
                                    + " caret="
                                    + area.getCaretPosition()
                                    + " focused="
                                    + (stage.getScene().getFocusOwner() == area)
                                    + " calls="
                                    + searchCalls.get());
                    return null;
                });
        pause();
        check(
                editorText().equals("A\n") && searchCalls.get() == calls,
                "Shift Enter failed literal LF/no-query");
        fx(
                () -> {
                    var area = (TextArea) node("names-search-input");
                    area.fireEvent(
                            new KeyEvent(
                                    KeyEvent.KEY_PRESSED,
                                    "",
                                    "",
                                    KeyCode.TAB,
                                    false,
                                    false,
                                    false,
                                    false));
                    return null;
                });
        pause();
        check(
                editorText().equals("A\n\t") && searchCalls.get() == calls,
                "Tab failed literal preservation/no-query");
        fx(
                () -> {
                    var area = (TextArea) node("names-search-input");
                    area.fireEvent(
                            new KeyEvent(
                                    KeyEvent.KEY_PRESSED,
                                    "",
                                    "",
                                    KeyCode.TAB,
                                    false,
                                    true,
                                    false,
                                    false));
                    return null;
                });
        pause();
        check(
                fx(() -> stage.getScene().getFocusOwner() == node("names-search")),
                "Ctrl Tab did not reach Search action");
    }

    private static void setSearch(String text) throws Exception {
        fx(
                () -> {
                    var area = (TextArea) node("names-search-input");
                    area.requestFocus();
                    area.replaceText(0, area.getLength(), text);
                    return null;
                });
        pause();
    }

    private static String editorText() throws Exception {
        return fx(() -> ((TextArea) node("names-search-input")).getText());
    }

    private static void search(String text) throws Exception {
        setSearch(text);
        click("names-search");
        waitSearch();
    }

    private static void waitSearch() throws Exception {
        until(() -> optional("names-cancel") == null);
        pause();
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
        shot("literal-two-source-proof-1280x800.png", "names-sparse-proof-value");
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
                return fx(CaptureItemNameSearchWorkflow::labels);
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
