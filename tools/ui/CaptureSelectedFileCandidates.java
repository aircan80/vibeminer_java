import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.lookup.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.lookup.FileLookupFixtureBackend;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Scene-only candidate-metadata acceptance. Real synthetic root/CAS/SQLite, fake selected service.
 */
public final class CaptureSelectedFileCandidates {
  private static FileLookupFixtureBackend backend;
  private static RootPlan prepared;
  private static RootLookupResult root;
  private static BuildSnapshot unsupported;
  private static FakeSelected selected;
  private static volatile boolean finished;
  private static int transportBaseline;
  private static final AtomicInteger discoveryCalls = new AtomicInteger();

  public static void main(String[] args) throws Exception {
    if (args.length != 4
        || !args[0].equals("--isolated-display")
        || !args[2].equals("--output")
        || !args[1].equals(System.getenv("DISPLAY"))
        || args[1].equals(":0"))
      throw new IllegalArgumentException(
          "Usage: DISPLAY=:N CaptureSelectedFileCandidates --isolated-display :N --output DIRECTORY"
              + " (isolated server only)");
    CaptureAtlas.output = Path.of(args[3]).toAbsolutePath();
    Files.createDirectories(CaptureAtlas.output);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          error.printStackTrace();
          System.exit(1);
        });
    Path temporary = Files.createTempDirectory("vibeminer-selected-ui-");
    try (var resources = new FileLookupFixtureBackend(temporary, false)) {
      backend = resources;
      // Explicit synthetic fixture setup, before app launch. No real network or normal user store.
      for (var build : backend.builds()) {
        RootPlan plan =
            backend
                .service()
                .planRoot(build, LookupBudgets.defaults())
                .toCompletableFuture()
                .get(20, TimeUnit.SECONDS);
        RootCandidate candidate =
            plan.candidates().stream().filter(c -> c.encodedBytes() > 0).findFirst().orElseThrow();
        backend
            .service()
            .retainRoot(new RootSelectionPlan(plan, candidate))
            .toCompletableFuture()
            .get(20, TimeUnit.SECONDS);
        if (prepared == null) prepared = plan;
      }
      root =
          backend
              .service()
              .lookupOffline(backend.builds().getFirst(), Set.of(42L), LookupBudgets.defaults())
              .toCompletableFuture()
              .get(20, TimeUnit.SECONDS);
      check(root.variants().size() == 2, "Real root fixture must provide two variants");
      var b = backend.builds().getFirst();
      unsupported =
          new BuildSnapshot(
              "wowt",
              "Synthetic unsupported product",
              "fixture",
              "us",
              "DEMO-UNSUPPORTED",
              b.buildConfig(),
              b.cdnConfig(),
              Instant.EPOCH,
              true,
              b.evidence());
      backend
          .workspaces()
          .save(unsupported, "enUS", "Synthetic unsupported product")
          .toCompletableFuture()
          .get(10, TimeUnit.SECONDS);
      selected = new FakeSelected();
      transportBaseline = backend.transportCalls();
      FileLookupService lookup =
          new FileLookupService() {
            public CompletionStage<RootPlan> planRoot(BuildSnapshot build, LookupBudgets budgets) {
              throw new AssertionError("UI must use retained lookup only in this harness");
            }

            public CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan selection) {
              throw new AssertionError("UI must not reacquire root");
            }

            public CompletionStage<RootLookupResult> lookupOffline(
                BuildSnapshot build, Set<Long> ids, LookupBudgets budgets) {
              if (build.productId().equals("wowt"))
                return CompletableFuture.completedFuture(
                    new RootLookupResult(
                        build, root.root(), ids, root.variants(), root.scannedRecords()));
              return backend.service().lookupOffline(build, ids, budgets);
            }

            public void close() {}
          };
      AtlasApplication.configure(
          new DiscoveryService() {
            public List<BuildSnapshot> fixtureBuilds() {
              return backend.builds();
            }

            public CompletionStage<List<BuildSnapshot>> refresh(String product, String region) {
              discoveryCalls.incrementAndGet();
              throw new AssertionError("No live discovery");
            }
          },
          backend.workspaces(),
          () -> {
            throw new AssertionError("No product refresh");
          },
          null,
          lookup,
          selected,
          true);
      Thread deadline =
          new Thread(
              () -> {
                try {
                  Thread.sleep(90_000);
                } catch (InterruptedException ignored) {
                  return;
                }
                if (!finished) {
                  System.err.println("Selected-file UI acceptance timed out");
                  System.exit(2);
                }
              });
      deadline.setDaemon(true);
      deadline.start();
      Thread controller =
          new Thread(
              () -> {
                for (int attempt = 0; attempt < 100; attempt++) {
                  try {
                    Platform.runLater(CaptureSelectedFileCandidates::begin);
                    return;
                  } catch (IllegalStateException starting) {
                    try {
                      Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                      return;
                    }
                  }
                }
              });
      controller.setDaemon(true);
      controller.start();
      Application.launch(AtlasApplication.class);
      check(finished, "Selected-file UI journey did not finish");
      check(
          discoveryCalls.get() == 0 && backend.transportCalls() == transportBaseline,
          "UI attempted unrelated transport");
      System.out.println(
          "Selected-file candidates UI OK; synthetic real root/CAS/SQLite; fake selected service;"
              + " no payload, locator or live HTTP");
    } finally {
      try (var paths = Files.walk(temporary)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  private static final class FakeSelected implements SelectedFileService {
    int calls;
    CompletableFuture<CandidatePlan> pending;
    FileVariantAssociation requested;
    SelectedFileBudgets budgets;

    public CompletionStage<CandidatePlan> planCandidates(
        BuildSnapshot build, FileVariantAssociation variant, SelectedFileBudgets budgets) {
      check(build.equals(variant.build()), "Service received incoherent exact build");
      calls++;
      requested = variant;
      this.budgets = budgets;
      pending =
          new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean interrupt) {
              return false;
            }
          };
      return pending;
    }

    CandidatePlan plan() {
      return new CandidatePlan(
          "synthetic-selected-session-" + calls,
          requested,
          prepared.configuration(),
          root.root(),
          new FileAssociationProof(
              root.root().source(),
              new ResolvedSourceIdentity("synthetic", "encoding-fragment", "synthetic-v1"),
              "synthetic-v1",
              "a".repeat(64)),
          List.of(
              new EncodedCandidate("1".repeat(32), OptionalLong.of(90), OptionalLong.of(0)),
              new EncodedCandidate("2".repeat(32), OptionalLong.empty(), OptionalLong.of(1))),
          budgets);
    }

    public CompletionStage<FileSelectionProbe> probeLoose(CandidateChoice choice) {
      throw new AssertionError("Candidate UI must not locate files");
    }

    public CompletionStage<FileSelectionSearch> searchArchives(
        Loose404Permit permit, ArchiveSearchBudgets budgets) {
      throw new AssertionError("Candidate UI must not search archives");
    }

    public CompletionStage<SelectedFileAssociation> retain(SelectedFilePlan plan) {
      throw new AssertionError("Candidate UI must not acquire payload");
    }

    public CompletionStage<SelectedFileAssociation> inspectOffline(
        SelectedFileDescriptor descriptor, SelectedFileBudgets budgets) {
      throw new AssertionError("Candidate UI must not claim retained payload");
    }

    public void close() {}
  }

  private static void begin() {
    var windows =
        Window.getWindows().stream().filter(w -> w instanceof Stage && w.isShowing()).toList();
    if (windows.isEmpty()) {
      CaptureAtlas.later(CaptureSelectedFileCandidates::begin);
      return;
    }
    Stage stage = (Stage) windows.getFirst();
    stage.setX(0);
    stage.setY(0);
    stage.setWidth(1280);
    stage.setHeight(800);
    await(
        () -> picker(stage).getItems().size() == 3,
        () -> {
          check(selected.calls == 0, "Startup planned candidates automatically");
          open(stage, backend.builds().getFirst());
          CaptureAtlas.later(
              () ->
                  lookup(
                      stage,
                      () -> {
                        check(
                            selected.calls == 0,
                            "Root lookup or selection planned candidate metadata automatically");
                        variants(stage).getSelectionModel().selectFirst();
                        check(selected.calls == 0, "Selecting root variant started network");
                        CaptureAtlas.later(() -> startCheck(stage));
                      }));
        });
  }

  private static void startCheck(Stage stage) {
    Node action = node(stage, "selected-file-check");
    reveal(stage, action);
    capture(stage, "selected-file-unchecked-1280x800.png");
    click(stage, "selected-file-check");
    check(selected.calls == 1, "Explicit Check did not invoke service exactly once");
    check(optional(stage, "selected-file-cancel") != null, "Missing in-flight cancel");
    check(
        selected.budgets.lookup().maximumMetadataWireBytes() == 8L << 20,
        "Wrong disclosed metadata ceiling");
    selected.pending.complete(selected.plan());
    await(
        () -> optional(stage, "selected-file-candidates") != null,
        () -> {
          TableView<EncodedCandidate> table = candidates(stage);
          check(table.getItems().size() == 2, "Not all candidates displayed");
          check(
              table.getSelectionModel().getSelectedItem() == null,
              "Candidate selected automatically");
          table.requestFocus();
          table.getSelectionModel().select(1);
          check(candidates(stage) == table, "Candidate selection replaced virtual table");
          check(stage.getScene().getFocusOwner() == table, "Candidate selection lost table focus");
          check(
              candidates(stage).getSelectionModel().getSelectedItem().encodedBytes().isEmpty(),
              "Unknown candidate disappeared");
          check(
              labels(stage).contains("Unknown encoded size cannot authorize acquisition"),
              "Unknown cost disclaimer missing");
          key(table, KeyCode.ENTER);
          CaptureAtlas.later(
              () -> {
                Node detail = node(stage, "selected-file-candidate-value");
                check(
                    stage.getScene().getFocusOwner() == detail,
                    "Enter did not focus full candidate detail");
                key(detail, KeyCode.ESCAPE);
                check(
                    stage.getScene().getFocusOwner() == table,
                    "Escape did not return candidate table");
                reveal(stage, table);
                assertCandidateRowsFit(stage);
                capture(stage, "selected-file-candidates-1280x800.png");
                reveal(stage, detail);
                capture(stage, "selected-file-unknown-detail-1280x800.png");
                var source =
                    stage.getScene().getRoot().lookupAll(".titled-pane").stream()
                        .filter(
                            n ->
                                n instanceof TitledPane t
                                    && t.getText().equals("Source and association evidence"))
                        .map(n -> (TitledPane) n)
                        .findFirst()
                        .orElseThrow();
                source.setExpanded(true);
                CaptureAtlas.later(
                    () -> {
                      reveal(stage, source);
                      capture(stage, "selected-file-source-1280x800.png");
                      check(
                          selected.calls == 1,
                          "Selection or source inspection started another request");
                      scale(stage, "200%");
                      CaptureAtlas.later(
                          () -> {
                            CaptureAtlas.navigate(stage, "Archive");
                            CaptureAtlas.later(
                                () -> {
                                  reveal(stage, node(stage, "selected-file-candidates"));
                                  CaptureAtlas.later(
                                      () -> {
                                        reveal(stage, node(stage, "selected-file-candidates"));
                                        assertCandidateRowsFit(stage);
                                        capture(
                                            stage, "selected-file-candidates-200pct-1280x800.png");
                                        check(
                                            selected.calls == 1,
                                            "Scaling planned metadata automatically");
                                        cancelAndStale(stage);
                                      });
                                });
                          });
                    });
              });
        });
  }

  private static void cancelAndStale(Stage stage) {
    click(stage, "selected-file-check");
    check(selected.calls == 2, "Recheck was not explicit");
    var old = selected.pending;
    var oldPlan = selected.plan();
    click(stage, "selected-file-cancel");
    old.complete(oldPlan);
    CaptureAtlas.later(
        () -> {
          check(
              optional(stage, "selected-file-candidates") == null,
              "Cancelled late result repainted candidates");
          check(
              labels(stage).toLowerCase(Locale.ROOT).contains("cancelled"),
              "Cancelled state missing");
          reveal(stage, node(stage, "selected-file-check"));
          capture(stage, "selected-file-cancelled-200pct-1280x800.png");
          click(stage, "selected-file-check");
          var stale = selected.pending;
          var stalePlan = selected.plan();
          TextField id = (TextField) node(stage, "file-id-input");
          id.requestFocus();
          id.setText("7");
          stale.complete(stalePlan);
          CaptureAtlas.later(
              () -> {
                check(
                    optional(stage, "selected-file-candidates") == null,
                    "Changed ID accepted old candidates");
                check(
                    stage.getScene().getFocusOwner() == id && id.getText().equals("7"),
                    "Typing lost ID focus/value");
                id.setText("42");
                lookup(
                    stage,
                    () -> {
                      variants(stage).getSelectionModel().selectFirst();
                      CaptureAtlas.later(
                          () -> {
                            click(stage, "selected-file-check");
                            var contextOld = selected.pending;
                            var contextPlan = selected.plan();
                            open(stage, backend.builds().get(1));
                            contextOld.complete(contextPlan);
                            CaptureAtlas.later(
                                () -> {
                                  check(
                                      optional(stage, "selected-file-candidates") == null,
                                      "Switched context painted previous candidates");
                                  int calls = selected.calls;
                                  open(stage, unsupported);
                                  CaptureAtlas.later(
                                      () ->
                                          lookup(
                                              stage,
                                              () -> {
                                                variants(stage).getSelectionModel().selectFirst();
                                                CaptureAtlas.later(
                                                    () -> {
                                                      check(
                                                          optional(stage, "selected-file-check")
                                                              == null,
                                                          "Unsupported product exposes"
                                                              + " selected-file action");
                                                      check(
                                                          selected.calls == calls,
                                                          "Unsupported product called selected-file"
                                                              + " port");
                                                      check(
                                                          labels(stage)
                                                              .contains(
                                                                  "not supported for this exact"
                                                                      + " product"),
                                                          "Unsupported product state missing");
                                                      Node unsupportedNotice =
                                                          stage
                                                              .getScene()
                                                              .getRoot()
                                                              .lookupAll(".label")
                                                              .stream()
                                                              .filter(
                                                                  n ->
                                                                      n instanceof Label label
                                                                          && label
                                                                              .getText()
                                                                              .contains(
                                                                                  "not supported"
                                                                                      + " for this"
                                                                                      + " exact"
                                                                                      + " product"))
                                                              .findFirst()
                                                              .orElseThrow();
                                                      reveal(stage, unsupportedNotice);
                                                      assertBodyVisible(
                                                          stage,
                                                          unsupportedNotice,
                                                          "Unsupported-product notice is outside"
                                                              + " the visible workspace");
                                                      capture(
                                                          stage,
                                                          "selected-file-unsupported-200pct-1280x800.png");
                                                      check(
                                                          backend.transportCalls()
                                                              == transportBaseline,
                                                          "UI root reopen used network");
                                                      finished = true;
                                                      Platform.exit();
                                                    });
                                              }));
                                });
                          });
                    });
              });
        });
  }

  private static void assertBodyVisible(Stage stage, Node target, String message) {
    layout(stage);
    ScrollPane scroll = (ScrollPane) stage.getScene().getRoot().lookup(".workspace-scroll");
    Node viewport = scroll.lookup(".viewport");
    Bounds frame = viewport.localToScene(viewport.getLayoutBounds());
    Bounds bounds = target.localToScene(target.getLayoutBounds());
    check(
        bounds.getMinY() >= frame.getMinY() - 1 && bounds.getMaxY() <= frame.getMaxY() + 1,
        message + ": " + bounds + " / " + frame);
  }

  private static void assertCandidateRowsFit(Stage stage) {
    layout(stage);
    TableView<EncodedCandidate> table = candidates(stage);
    check(table.getItems().size() == 2, "Geometry fixture must retain both candidates");
    Node flow = table.lookup(".virtual-flow");
    Node clipped = flow == null ? null : flow.lookup(".clipped-container");
    check(clipped != null, "Candidate table has no measurable row viewport");
    Bounds viewport = clipped.localToScene(clipped.getLayoutBounds());
    int rows = 0;
    for (Node node : table.lookupAll(".table-row-cell")) {
      if (node instanceof TableRow<?> row
          && !row.isEmpty()
          && row.getIndex() < table.getItems().size()) {
        Bounds bounds = row.localToScene(row.getLayoutBounds());
        check(
            bounds.getMinY() >= viewport.getMinY() - 1
                && bounds.getMaxY() <= viewport.getMaxY() + 1,
            "Candidate row "
                + row.getIndex()
                + " is clipped inside its table viewport: row="
                + bounds
                + ", viewport="
                + viewport);
        rows++;
      }
    }
    check(rows == 2, "Not all candidate rows were rendered inside the viewport");
    check(
        table.lookupAll(".scroll-bar").stream()
            .noneMatch(
                node ->
                    node instanceof ScrollBar bar
                        && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL
                        && bar.isVisible()),
        "Two candidate rows unnecessarily require an internal vertical scrollbar");
  }

  private static void lookup(Stage stage, Runnable next) {
    TextField id = (TextField) node(stage, "file-id-input");
    id.setText("42");
    click(stage, "file-reopen-evidence");
    await(() -> optional(stage, "file-variant-table") != null, next);
  }

  @SuppressWarnings("unchecked")
  private static ComboBox<Workspace> picker(Stage stage) {
    return (ComboBox<Workspace>) node(stage, "saved-workspace-picker");
  }

  private static void open(Stage stage, BuildSnapshot build) {
    Workspace w =
        picker(stage).getItems().stream()
            .filter(v -> v.build().snapshotId().equals(build.snapshotId()))
            .findFirst()
            .orElseThrow();
    picker(stage).setValue(w);
    CaptureAtlas.navigate(stage, "Archive");
    click(stage, "open-file-lookup");
  }

  @SuppressWarnings("unchecked")
  private static TableView<RootVariant> variants(Stage stage) {
    return (TableView<RootVariant>) node(stage, "file-variant-table");
  }

  @SuppressWarnings("unchecked")
  private static TableView<EncodedCandidate> candidates(Stage stage) {
    return (TableView<EncodedCandidate>) node(stage, "selected-file-candidates");
  }

  private static void scale(Stage stage, String value) {
    CaptureAtlas.button(stage, "Settings").fire();
    CaptureAtlas.setCombo(CaptureAtlas.accessible(stage, "Application text scale"), value);
  }

  private static void click(Stage stage, String id) {
    Button b = (Button) node(stage, id);
    b.requestFocus();
    b.fire();
  }

  private static void key(Node node, KeyCode code) {
    node.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
  }

  private static Node node(Stage stage, String id) {
    return CaptureAtlas.byId(stage, id);
  }

  private static Node optional(Stage stage, String id) {
    layout(stage);
    return stage.getScene().lookup("#" + id);
  }

  private static void layout(Stage stage) {
    stage.getScene().getRoot().applyCss();
    stage.getScene().getRoot().layout();
  }

  private static String labels(Stage stage) {
    layout(stage);
    return stage.getScene().getRoot().lookupAll(".label").stream()
        .filter(n -> n instanceof Label)
        .map(n -> ((Label) n).getText())
        .reduce("", (a, b) -> a + "\n" + b);
  }

  private static void reveal(Stage stage, Node target) {
    layout(stage);
    ScrollPane scroll = (ScrollPane) stage.getScene().getRoot().lookup(".workspace-scroll");
    Bounds content = scroll.getContent().localToScene(scroll.getContent().getBoundsInLocal());
    Bounds node = target.localToScene(target.getBoundsInLocal());
    double range =
        scroll.getContent().getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
    scroll.setVvalue(
        range <= 0 ? 0 : Math.max(0, Math.min(1, (node.getMinY() - content.getMinY()) / range)));
    layout(stage);
  }

  private static void capture(Stage stage, String name) {
    CaptureAtlas.capture(stage, name, 1280, 800);
  }

  private static void await(BooleanSupplier ready, Runnable next) {
    if (ready.getAsBoolean()) Platform.runLater(next);
    else CaptureAtlas.later(() -> await(ready, next));
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
