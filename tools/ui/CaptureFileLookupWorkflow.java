import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.lookup.RootCandidate;
import io.vibeminer.application.lookup.RootVariant;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.Workspace;
import io.vibeminer.infrastructure.lookup.FileLookupFixtureBackend;
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
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Real JavaFX/use-case/codec/CAS/SQLite review with synthetic transport and two JVM launches. */
public final class CaptureFileLookupWorkflow {
  private static FileLookupFixtureBackend backend;
  private static boolean offline;
  private static volatile boolean finished;
  private static final AtomicInteger metadataCalls = new AtomicInteger();

  public static void main(String[] args) throws Exception {
    if (args.length == 4 && args[0].equals("--isolated-display") && args[2].equals("--output")) {
      requireDisplay(args[1]);
      Path output = Path.of(args[3]).toAbsolutePath();
      Files.createDirectories(output);
      Path root = Files.createTempDirectory("vibeminer-file-lookup-ui-");
      try {
        for (String phase : List.of("acquire", "offline")) {
          var command = new ArrayList<String>();
          command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
          command.addAll(
              List.of(
                  "--enable-native-access=ALL-UNNAMED",
                  "-cp",
                  System.getProperty("java.class.path"),
                  CaptureFileLookupWorkflow.class.getName(),
                  "--worker",
                  phase,
                  args[1],
                  output.toString(),
                  root.toString()));
          Process child = new ProcessBuilder(command).inheritIO().start();
          if (!child.waitFor(120, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new IllegalStateException("File lookup UI worker timed out: " + phase);
          }
          if (child.exitValue() != 0)
            throw new IllegalStateException("File lookup UI worker failed: " + phase);
        }
        System.out.println(
            "File lookup UI workflow OK; real services/CAS/SQLite; two JVMs; offline transport"
                + " forbidden");
      } finally {
        try (var paths = Files.walk(root)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            Files.deleteIfExists(path);
        }
      }
      return;
    }
    if (args.length != 5 || !args[0].equals("--worker")) {
      throw new IllegalArgumentException(
          "Usage: CaptureFileLookupWorkflow --isolated-display :N --output DIRECTORY");
    }
    requireDisplay(args[2]);
    offline = args[1].equals("offline");
    CaptureAtlas.output = Path.of(args[3]);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          error.printStackTrace();
          System.exit(1);
        });
    try (var resources = new FileLookupFixtureBackend(Path.of(args[4]), offline)) {
      backend = resources;
      AtlasApplication.configure(
          new DiscoveryService() {
            public List<BuildSnapshot> fixtureBuilds() {
              return backend.builds();
            }

            public CompletionStage<List<BuildSnapshot>> refresh(String product, String region) {
              metadataCalls.incrementAndGet();
              return CompletableFuture.failedFuture(
                  new AssertionError("Implicit discovery is forbidden"));
            }
          },
          backend.workspaces(),
          () -> {
            metadataCalls.incrementAndGet();
            return CompletableFuture.failedFuture(
                new AssertionError("Product refresh is outside this review scope"));
          },
          null,
          backend.service(),
          true);
      Thread deadline =
          new Thread(
              () -> {
                try {
                  Thread.sleep(90_000);
                } catch (InterruptedException interrupted) {
                  return;
                }
                if (!finished) {
                  System.err.println("File lookup UI review timed out");
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
                    Platform.runLater(CaptureFileLookupWorkflow::begin);
                    return;
                  } catch (IllegalStateException starting) {
                    try {
                      Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                      return;
                    }
                  }
                }
              });
      controller.setDaemon(true);
      controller.start();
      Application.launch(AtlasApplication.class);
      check(
          finished && metadataCalls.get() == 0,
          "File lookup review ended without completing its explicit scope");
      if (offline) check(backend.transportCalls() == 0, "Offline reopening invoked transport");
    }
  }

  private static void begin() {
    var windows =
        Window.getWindows().stream()
            .filter(window -> window instanceof Stage && window.isShowing())
            .toList();
    if (windows.isEmpty()) {
      CaptureAtlas.later(CaptureFileLookupWorkflow::begin);
      return;
    }
    Stage stage = (Stage) windows.getFirst();
    CaptureAtlas.later(
        () -> {
          stage.setX(0);
          stage.setY(0);
          stage.setWidth(1280);
          stage.setHeight(800);
          await(
              () -> picker(stage).getItems().size() == 2,
              () -> {
                check(backend.transportCalls() == 0, "Startup performed content acquisition");
                openBuild(stage, 0);
                CaptureAtlas.later(
                    () -> {
                      if (offline) reopenOffline(stage);
                      else startOnline(stage);
                    });
              });
        });
  }

  private static TextField input(Stage stage) {
    return (TextField) byId(stage, "file-id-input");
  }

  @SuppressWarnings("unchecked")
  private static ComboBox<RootCandidate> candidates(Stage stage) {
    return (ComboBox<RootCandidate>) byId(stage, "file-root-candidate");
  }

  private static void setId(Stage stage, String id) {
    input(stage).setText(id);
  }

  private static void startOnline(Stage stage) {
    setId(stage, "42");
    openBuild(stage, 1);
    setId(stage, "7");
    openBuild(stage, 0);
    check(
        input(stage).getText().equals("42"),
        "Saved workspace A did not restore its visible FileDataID");
    check(backend.transportCalls() == 0, "Typing or switching context acquired metadata");
    capture(stage, "file-lookup-unchecked-1280x800.png");
    input(stage).requestFocus();
    input(stage).fireEvent(new ActionEvent());
    await(
        () -> optional(stage, "file-root-candidate") != null,
        () -> {
          check(
              backend.plans.get() == 1
                  && backend.retentions.get() == 0
                  && backend.rootRequests() == 0,
              "Enter performed root acquisition instead of metadata planning");
          reviewPlan(
              stage,
              () -> {
                backend.holdRoot();
                click(stage, "file-download-metadata");
                await(
                    backend::wireStarted,
                    () -> {
                      openBuild(stage, 1);
                      await(
                          backend::wireCancelled,
                          () -> {
                            check(
                                input(stage).getText().equals("7"),
                                "Workspace B visible input was not restored");
                            check(
                                optional(stage, "file-variant-table") == null,
                                "Stale A result leaked into B");
                            backend.releaseRoot();
                            openBuild(stage, 0);
                            check(
                                input(stage).getText().equals("42"),
                                "A visible input lost after cancelled context switch");
                            click(stage, "file-check-requirements");
                            await(
                                () -> optional(stage, "file-root-candidate") != null,
                                () -> {
                                  chooseKnown(stage);
                                  click(stage, "file-download-metadata");
                                  await(
                                      () -> optional(stage, "file-variant-table") != null,
                                      () -> {
                                        check(
                                            backend.lastIds().equals(java.util.Set.of(42L)),
                                            "Actual service lookup ID differs from restored"
                                                + " visible42");
                                        inspect(stage, false);
                                      });
                                });
                          });
                    });
              });
        });
  }

  private static void chooseKnown(Stage stage) {
    candidates(stage)
        .setValue(
            candidates(stage).getItems().stream()
                .filter(c -> c.encodedBytes() > 0)
                .findFirst()
                .orElseThrow());
  }

  private static void reviewPlan(Stage stage, Runnable next) {
    check(
        candidates(stage).getValue() == null
            && ((Button) byId(stage, "file-download-metadata")).isDisabled(),
        "A root candidate was implicitly authorized");
    var unknown =
        candidates(stage).getItems().stream()
            .filter(c -> c.encodedBytes() < 1)
            .findFirst()
            .orElseThrow();
    candidates(stage).setValue(unknown);
    check(
        ((Button) byId(stage, "file-download-metadata")).isDisabled(),
        "Unknown root size authorized acquisition");
    reveal(stage, byId(stage, "file-root-cost"));
    CaptureAtlas.later(
        () -> {
          capture(stage, "file-lookup-unknown-cost-1280x800.png");
          chooseKnown(stage);
          reveal(stage, candidates(stage));
          CaptureAtlas.later(
              () -> {
                String cost = ((Label) byId(stage, "file-root-cost")).getText();
                check(
                    cost.contains(String.format(Locale.ROOT, "%,d bytes", backend.encodedBytes()))
                        && cost.contains(
                            String.format(Locale.ROOT, "%,d bytes", backend.decodedBytes())),
                    "Known root cost differs from actual fixture");
                assertVisible(stage, byId(stage, "file-root-cost"), "Known root cost hidden");
                assertVisible(
                    stage,
                    byId(stage, "file-download-metadata"),
                    "Consent action not visible with known cost");
                capture(stage, "file-lookup-known-cost-1280x800.png");
                scale(
                    stage,
                    () -> {
                      reveal(stage, byId(stage, "file-root-cost"));
                      CaptureAtlas.later(
                          () -> {
                            assertVisible(
                                stage,
                                byId(stage, "file-root-cost"),
                                "Scaled known cost is hidden");
                            assertVisible(
                                stage,
                                byId(stage, "file-download-metadata"),
                                "Scaled consent action is not visible with cost");
                            capture(stage, "file-lookup-known-cost-200pct-1280x800.png");
                            resetScale(stage);
                            CaptureAtlas.later(next);
                          });
                    });
              });
        });
  }

  private static void inspect(Stage stage, boolean restart) {
    check(table(stage).getItems().size() == 2, "All42 root variants were not retained");
    check(
        table(stage).getItems().stream().allMatch(v -> v.fileDataId() == 42),
        "Variants belong to a different visibleID");
    check(
        table(stage).getItems().stream().map(RootVariant::contentKey).distinct().count() == 2,
        "Distinct root memberships collapsed");
    table(stage).getSelectionModel().select(1);
    table(stage).requestFocus();
    table(stage)
        .fireEvent(
            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    TextArea raw = (TextArea) byId(stage, "file-variant-value");
    check(
        stage.getScene().getFocusOwner() == raw
            && raw.getText().contains("Filename: unavailable")
            && raw.getText().contains("Raw flags:"),
        "Enter failed full variant raw detail");
    raw.fireEvent(
        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
    check(
        stage.getScene().getFocusOwner() == table(stage),
        "Escape did not return variant table focus");
    reveal(stage, table(stage));
    CaptureAtlas.later(
        () -> {
          capture(
              stage,
              restart
                  ? "file-lookup-offline-variants-1280x800.png"
                  : "file-lookup-variants-1280x800.png");
          reveal(stage, raw);
          CaptureAtlas.later(
              () -> {
                capture(
                    stage,
                    restart
                        ? "file-lookup-offline-detail-1280x800.png"
                        : "file-lookup-detail-1280x800.png");
                var proof = (TitledPane) byId(stage, "file-source-details");
                proof.setAnimated(false);
                proof.setExpanded(true);
                var evidence =
                    (TextArea) CaptureAtlas.accessible(stage, "ACTUAL RETAINED ROOT VERIFICATION");
                check(
                    evidence.getText().startsWith("https://us.cdn.blizzard.com/tpr/wow/data/"),
                    "Actual root evidence source was fabricated");
                reveal(stage, evidence);
                CaptureAtlas.later(
                    () -> {
                      capture(
                          stage,
                          restart
                              ? "file-lookup-offline-source-1280x800.png"
                              : "file-lookup-source-1280x800.png");
                      proof.setExpanded(false);
                      scale(
                          stage,
                          () -> {
                            reveal(stage, table(stage));
                            CaptureAtlas.later(
                                () -> {
                                  capture(
                                      stage,
                                      restart
                                          ? "file-lookup-offline-variants-200pct-1280x800.png"
                                          : "file-lookup-variants-200pct-1280x800.png");
                                  resetScale(stage);
                                  absence(stage, restart);
                                });
                          });
                    });
              });
        });
  }

  private static void scale(Stage stage, Runnable next) {
    CaptureAtlas.button(stage, "Settings").fire();
    CaptureAtlas.setCombo(CaptureAtlas.accessible(stage, "Application text scale"), "200%");
    CaptureAtlas.navigate(stage, "Archive");
    CaptureAtlas.later(next);
  }

  private static void resetScale(Stage stage) {
    CaptureAtlas.button(stage, "Settings").fire();
    CaptureAtlas.setCombo(CaptureAtlas.accessible(stage, "Application text scale"), "100%");
    CaptureAtlas.navigate(stage, "Archive");
  }

  private static void absence(Stage stage, boolean restart) {
    int calls = backend.transportCalls();
    setId(stage, "999");
    click(stage, "file-reopen-evidence");
    await(
        () -> labels(stage).contains("No matching FileDataID"),
        () -> {
          check(
              optional(stage, "file-variant-table") == null
                  && backend.lastIds().equals(java.util.Set.of(999L)),
              "Scoped absence did not match visible selectedID");
          check(backend.transportCalls() == calls, "Retained absence performed HTTP");
          reveal(stage, byId(stage, "file-proof-summary"));
          CaptureAtlas.later(
              () -> {
                capture(
                    stage,
                    restart
                        ? "file-lookup-offline-absent-1280x800.png"
                        : "file-lookup-absent-1280x800.png");
                if (restart) otherBuildOffline(stage);
                else finish();
              });
        });
  }

  private static void reopenOffline(Stage stage) {
    check(backend.transportCalls() == 0, "Restart touched transport");
    setId(stage, "42");
    click(stage, "file-reopen-evidence");
    await(
        () -> optional(stage, "file-variant-table") != null,
        () -> {
          check(backend.transportCalls() == 0, "Restarted root verification performed HTTP");
          inspect(stage, true);
        });
  }

  private static void otherBuildOffline(Stage stage) {
    openBuild(stage, 1);
    setId(stage, "7");
    click(stage, "file-reopen-evidence");
    await(
        () -> labels(stage).contains("not retained for this exact source"),
        () -> {
          check(
              backend.lastIds().equals(java.util.Set.of(7L)),
              "B actual requestedID differs from visible7");
          check(optional(stage, "file-variant-table") == null, "B borrowed A root membership");
          openBuild(stage, 0);
          check(
              input(stage).getText().equals("999"),
              "A visible absenceID lost across offline switch");
          setId(stage, "42");
          click(stage, "file-reopen-evidence");
          await(() -> optional(stage, "file-variant-table") != null, () -> corrupt(stage));
        });
  }

  private static void corrupt(Stage stage) {
    check(
        table(stage).getItems().size() == 2 && labels(stage).contains("Root map verified"),
        "Corruption scenario did not begin with ready variants");
    try {
      backend.corruptRoot();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
    setId(stage, "42");
    click(stage, "file-reopen-evidence");
    await(
        () -> labels(stage).contains("failed verification") || labels(stage).contains("integrity"),
        () -> {
          check(
              optional(stage, "file-variant-table") == null
                  && !labels(stage).contains("Root map verified"),
              "Corrupt retained root left a ready result/coverage");
          check(backend.transportCalls() == 0, "Corrupt root attempted HTTP repair");
          scale(
              stage,
              () -> {
                reveal(stage, byId(stage, "file-proof-summary"));
                CaptureAtlas.later(
                    () -> {
                      capture(stage, "file-lookup-corrupt-offline-200pct-1280x800.png");
                      finish();
                    });
              });
        });
  }

  @SuppressWarnings("unchecked")
  private static void openBuild(Stage stage, int index) {
    ComboBox<Workspace> picker = (ComboBox<Workspace>) picker(stage);
    Workspace workspace =
        picker.getItems().stream()
            .filter(
                value ->
                    value.build().snapshotId().equals(backend.builds().get(index).snapshotId()))
            .findFirst()
            .orElseThrow();
    picker.setValue(workspace);
    CaptureAtlas.navigate(stage, "Archive");
    click(stage, "open-file-lookup");
    layout(stage);
  }

  private static ComboBox<?> picker(Stage stage) {
    return (ComboBox<?>) byId(stage, "saved-workspace-picker");
  }

  @SuppressWarnings("unchecked")
  private static TableView<RootVariant> table(Stage stage) {
    return (TableView<RootVariant>) byId(stage, "file-variant-table");
  }

  private static void click(Stage stage, String id) {
    ((Button) byId(stage, id)).fire();
  }

  private static Node byId(Stage stage, String id) {
    return CaptureAtlas.byId(stage, id);
  }

  private static Node optional(Stage stage, String id) {
    layout(stage);
    return stage.getScene().lookup("#" + id);
  }

  private static String labels(Stage stage) {
    layout(stage);
    return stage.getScene().getRoot().lookupAll(".label").stream()
        .filter(node -> node instanceof Label)
        .map(node -> ((Label) node).getText())
        .reduce("", (a, b) -> a + "\n" + b);
  }

  private static void layout(Stage stage) {
    stage.getScene().getRoot().applyCss();
    stage.getScene().getRoot().layout();
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

  private static void assertVisible(Stage stage, Node node, String message) {
    layout(stage);
    ScrollPane scroll = (ScrollPane) stage.getScene().getRoot().lookup(".workspace-scroll");
    Node viewport = scroll.lookup(".viewport");
    Bounds frame = viewport.localToScene(viewport.getBoundsInLocal());
    Bounds value = node.localToScene(node.getBoundsInLocal());
    check(
        value.getMinY() >= frame.getMinY() - 1 && value.getMaxY() <= frame.getMaxY() + 1, message);
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

  private static void requireDisplay(String value) {
    if (value.isBlank() || !value.equals(System.getenv("DISPLAY")))
      throw new IllegalStateException("Explicit isolated display must match DISPLAY");
  }

  private static void finish() {
    check(metadataCalls.get() == 0, "Unexpected metadata discovery");
    if (offline) check(backend.transportCalls() == 0, "Offline verification touched transport");
    finished = true;
    System.out.println("File lookup UI phase " + (offline ? "offline" : "acquire") + " OK");
    Platform.exit();
  }
}
