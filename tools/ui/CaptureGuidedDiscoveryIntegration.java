import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.desktop.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.discoveryhistory.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.*;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.*;
import javax.imageio.ImageIO;

/** Actual Atlas + application facade + SQLite; the sole metadata gateway returns authored bytes. */
public final class CaptureGuidedDiscoveryIntegration {
  private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  private static final AtomicInteger captures = new AtomicInteger(),
      legacyCalls = new AtomicInteger();
  private static final Map<String, Integer> productCaptures = new ConcurrentHashMap<>();
  private static final Map<String, BuildLibraryProductObservation> observed =
      new ConcurrentHashMap<>();
  private static volatile boolean offline, complete;
  private static Path output, database;
  private static int width, height, scale, assertions;
  private static Stage stage;
  private static DefaultBuildLibraryService service;
  private static ExecutorService workers;

  public static void main(String[] args) throws Exception {
    if (args.length != 10
        || !args[0].equals("--isolated-display")
        || !args[1].equals(System.getenv("DISPLAY"))
        || !args[2].equals("--output")
        || !args[4].equals("--width")
        || !args[6].equals("--height")
        || !args[8].equals("--scale"))
      throw new IllegalArgumentException(
          "Usage: CaptureGuidedDiscoveryIntegration --isolated-display :N --output DIR --width 1440"
              + " --height 1100 --scale 100");
    output = Path.of(args[3]);
    width = Integer.parseInt(args[5]);
    height = Integer.parseInt(args[7]);
    scale = Integer.parseInt(args[9]);
    if (!((width == 1440 && height == 1100) || (width == 1280 && height == 800))
        || !(scale == 100 || scale == 200))
      throw new IllegalArgumentException("Unsupported review viewport/text scale");
    Files.createDirectories(output);
    database = Files.createTempDirectory(output, "authored-sql-").resolve("catalog.sqlite");
    var catalog = new SqliteCatalog(database);
    var library = new SqliteBuildLibraryRepository(catalog);
    workers =
        new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),
            r -> {
              var t = new Thread(r, "authored-guided-library");
              t.setDaemon(true);
              return t;
            });
    service =
        new DefaultBuildLibraryService(
            CaptureGuidedDiscoveryIntegration::captureMetadata,
            new SqliteDiscoveryHistoryRepository(catalog),
            library,
            library,
            new SqliteBuildLibraryReader(catalog),
            workers,
            Clock.fixed(NOW, ZoneOffset.UTC));
    AtlasApplication.configure(
        new DiscoveryService() {
          public List<BuildSnapshot> fixtureBuilds() {
            throw legacy("Legacy fixture discovery invoked");
          }

          public CompletionStage<List<BuildSnapshot>> refresh(String product, String region) {
            throw legacy("Legacy per-region discovery invoked");
          }
        },
        new WorkspaceService() {
          public CompletionStage<List<Workspace>> list() {
            throw legacy("Hidden legacy workspace startup read invoked");
          }

          public CompletionStage<Workspace> save(BuildSnapshot b, String locale, String name) {
            throw legacy("Legacy workspace save invoked");
          }

          public CompletionStage<Workspace> rename(String id, String name) {
            throw legacy("Legacy workspace rename invoked");
          }

          public CompletionStage<Void> remove(String id) {
            throw legacy("Legacy workspace removal invoked");
          }
        },
        () -> {
          throw legacy("Independent product-summary retrieval invoked");
        },
        false);
    AtlasApplication.configureBuildLibrary(service);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, failure) -> {
          failure.printStackTrace();
          System.exit(1);
        });
    Thread timeout =
        new Thread(
            () -> {
              try {
                Thread.sleep(60000);
              } catch (InterruptedException stopped) {
                return;
              }
              if (!complete) {
                System.err.println("Integrated guided review timed out");
                System.exit(2);
              }
            });
    timeout.setDaemon(true);
    timeout.start();
    Thread controller =
        new Thread(
            () -> {
              try {
                journey();
              } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
              }
            },
            "guided-review-controller");
    controller.setDaemon(true);
    controller.start();
    try {
      Application.launch(AtlasApplication.class);
    } finally {
      service.close();
      workers.shutdownNow();
      workers.awaitTermination(5, TimeUnit.SECONDS);
    }
    if (!complete) throw new IllegalStateException("Integrated review did not complete");
    System.out.println(
        "Integrated Atlas guided discovery: "
            + assertions
            + " assertions; "
            + captures.get()
            + " authored paired captures; legacy calls="
            + legacyCalls.get()
            + "; "
            + width
            + "x"
            + height
            + "; "
            + scale
            + "% application text; no HTTP transport or CAS configured");
  }

  private static AssertionError legacy(String reason) {
    legacyCalls.incrementAndGet();
    return new AssertionError(reason);
  }

  private static BuildLibraryProductObservation captureMetadata(
      String product, CancellationToken token) {
    token.throwIfCancellationRequested();
    captures.incrementAndGet();
    if (offline) throw new IllegalStateException("Authored offline gateway failure");
    int generation = productCaptures.merge(product, 1, Integer::sum);
    String base = "https://us.version.battle.net/v2/products/" + product + "/";
    String head =
        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0|BuildId!DEC:4|ProductConfig!HEX:16\n";
    String suffix = "|12.1.5.70448|70448|" + "d".repeat(32) + "\n";
    String body =
        head
            + "us|"
            + "a".repeat(32)
            + "|"
            + "b".repeat(32)
            + suffix
            + "eu|"
            + "a".repeat(32)
            + "|"
            + "b".repeat(32)
            + suffix;
    if (generation > 1) body += "eu|" + "a".repeat(32) + "|" + "c".repeat(32) + suffix;
    var versions =
        new SourceDocument(base + "versions", NOW, body.getBytes(StandardCharsets.UTF_8));
    var cdns =
        new SourceDocument(
            base + "cdns",
            NOW,
            ("Name!STRING:0|Path!STRING:0|Hosts!STRING:0\n"
                    + "us|tpr/wow|authored.invalid\n"
                    + "eu|tpr/wow|authored.invalid\n")
                .getBytes(StandardCharsets.UTF_8));
    var mapper = new DiscoveryObservationMapper();
    var us = mapper.map(UUID.randomUUID(), product, "us", NOW, versions, cdns);
    var eu = mapper.map(UUID.randomUUID(), product, "eu", NOW, versions, cdns);
    var result =
        new BuildLibraryProductObservation(
            product,
            NOW,
            us.parserRevision(),
            us.versions(),
            us.cdns(),
            List.of(us, eu),
            List.of(),
            Optional.empty());
    observed.put(product, result);
    return result;
  }

  private static void journey() throws Exception {
    until(
        () -> {
          try {
            return fx(
                () -> {
                  var w =
                      Window.getWindows().stream()
                          .filter(v -> v instanceof Stage && v.isShowing())
                          .findFirst();
                  if (w.isEmpty()) return false;
                  stage = (Stage) w.orElseThrow();
                  return true;
                });
          } catch (IllegalStateException startup) {
            if (!startup.getMessage().contains("Toolkit not initialized")) throw startup;
            return false;
          }
        });
    fx(
        () -> {
          Label marker = new Label("AUTHORED REVIEW DATA · NO LIVE CDN");
          marker.setId("guided-authored-fixture-marker");
          marker.setStyle(
              "-fx-padding: 7 24; -fx-font-size: 0.7857em; -fx-background-color: #fff3dc;"
                  + " -fx-text-fill: #775a2a;");
          ((VBox) ((BorderPane) stage.getScene().getRoot()).getTop())
              .getChildren()
              .addFirst(marker);
          stage.setX(0);
          stage.setY(0);
          stage.setWidth(width);
          stage.setHeight(height);
          return null;
        });
    if (scale == 200) {
      clickText("Settings");
      fx(
          () -> {
            for (Node n : stage.getScene().getRoot().lookupAll(".combo-box"))
              if (n instanceof ComboBox<?> c
                  && "Application text scale".equals(c.getAccessibleText())) {
                setCombo(c, "200%");
                return null;
              }
            throw new AssertionError("Missing real application text-scale control");
          });
      clickText("Discover");
    }
    fx(
        () -> {
          stage.setWidth(stage.getWidth() + width - stage.getScene().getWidth());
          stage.setHeight(stage.getHeight() + height - stage.getScene().getHeight());
          return null;
        });
    until(
        () ->
            fx(
                () ->
                    stage.getScene().getWidth() == width
                        && stage.getScene().getHeight() == height));
    check(
        captures.get() == 0 && legacyCalls.get() == 0,
        "Startup called a source or hidden legacy flow");
    check(
        fx(
            () ->
                stage.getScene().getRoot().lookupAll(".nav-button").stream()
                    .filter(ToggleButton.class::isInstance)
                    .map(ToggleButton.class::cast)
                    .map(ToggleButton::getText)
                    .toList()
                    .equals(List.of("Discover"))),
        "Guided runtime exposes obsolete research destinations");
    check(
        fx(
            () ->
                stage.getScene().getRoot().lookupAll(".button").stream()
                    .noneMatch(
                        n -> n instanceof Button b && b.getText().equals("Search workspace"))),
        "Guided runtime exposes obsolete workspace search");
    fx(
        () -> {
          stage
              .getScene()
              .getOnKeyPressed()
              .handle(
                  new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, true, false, false));
          return null;
        });
    check(
        fx(() -> stage.getScene().lookup("#guided-product") != null),
        "Workspace search shortcut escaped the supported guided screen");
    check(
        fx(
            () ->
                ((Label) node("guided-authored-fixture-marker"))
                    .getText()
                    .contains("AUTHORED REVIEW DATA")),
        "Authored metadata lacks a visible capture-only marker");
    check(!Files.exists(database), "Startup opened the catalog before product selection");
    check(fx(() -> list().getItems().isEmpty()), "Cold library invented rows");
    check(
        fx(() -> ((ComboBox<?>) node("guided-product")).getValue() == null),
        "Cold library invented selected product");
    productInViewport();
    shot(stage, "initial");
    chooseProduct("wow");
    until(
        () ->
            fx(
                () ->
                    list().getItems().size() == 1
                        && !((Button) node("guided-refresh")).isDisabled()));
    check(captures.get() == 1, "One product selection did not use exactly one paired capture");
    var grouped = fx(() -> list().getItems().getFirst());
    check(
        grouped.regions().equals(List.of("eu", "us")),
        "Equivalent regional references were not grouped");
    check(
        !grouped.local() && !grouped.newToVibeMiner(),
        "First baseline became Local or New without preparation");
    check(
        grouped.status().equals("Readiness not assessed"),
        "Publication invented metadata readiness");
    check(
        count("discovery_history_events") == 2, "Regional history was not persisted independently");
    check(count("source_blobs") == 2, "Paired raw documents were duplicated by region");
    shot(stage, "grouped");
    fx(
        () -> {
          Node product = node("guided-product");
          var cell =
              product.lookupAll(".list-cell").stream()
                  .filter(
                      n ->
                          n instanceof ListCell<?> c
                              && c.getItem() instanceof GuidedDiscoveryPane.ProductOption)
                  .map(n -> (ListCell<?>) n)
                  .findFirst()
                  .orElseThrow(() -> new AssertionError("Selected product display is missing"));
          check(
              !cell.isTextTruncated(), "Normal product ID and Retail alias are visually truncated");
          return null;
        });
    fx(
        () -> {
          list().getSelectionModel().select(0);
          return null;
        });
    until(() -> fx(() -> stage.getScene().lookup("#guided-open-build") != null));
    check(
        fx(() -> ((Button) node("guided-open-build")).isDisabled()),
        "Missing #100 capability enabled Open");
    fx(
        () -> {
          list()
              .fireEvent(
                  new KeyEvent(
                      KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
          ((Button) node("guided-open-build")).fire();
          return null;
        });
    check(
        captures.get() == 1 && count("workspaces") == 0,
        "Selection/disabled Open acquired or opened a build");
    fx(
        () -> {
          node("guided-source-details").requestFocus();
          ((ScrollPane) node("guided-discovery-scroll")).setVvalue(1);
          return null;
        });
    until(
        () ->
            fx(
                () -> {
                  stage.getScene().getRoot().applyCss();
                  stage.getScene().getRoot().layout();
                  var scroll = (ScrollPane) node("guided-discovery-scroll");
                  Bounds viewport =
                      scroll
                          .lookup(".viewport")
                          .localToScene(scroll.lookup(".viewport").getBoundsInLocal());
                  Node open = node("guided-open-build");
                  Bounds bounds = open.localToScene(open.getBoundsInLocal());
                  return bounds.getMinY() >= viewport.getMinY() - 1
                      && bounds.getMaxY() <= viewport.getMaxY() + 1;
                }));
    fx(
        () -> {
          var scroll = (ScrollPane) node("guided-discovery-scroll");
          Bounds viewport =
              scroll
                  .lookup(".viewport")
                  .localToScene(scroll.lookup(".viewport").getBoundsInLocal());
          Node open = node("guided-open-build");
          Bounds bounds = open.localToScene(open.getBoundsInLocal());
          check(
              bounds.getMinX() >= viewport.getMinX() - 1
                  && bounds.getMaxX() <= viewport.getMaxX() + 1,
              "Selected Open Build is horizontally clipped");
          check(
              bounds.getMinY() >= viewport.getMinY() - 1
                  && bounds.getMaxY() <= viewport.getMaxY() + 1,
              "Selected Open Build is not reachable by scrolling");
          check(
              stage.getScene().getFocusOwner() == node("guided-source-details"),
              "Selected inspector action did not retain keyboard focus");
          return null;
        });
    fx(
        () -> {
          for (Node n : node("guided-inspector").lookupAll(".label")) {
            if (n instanceof Label label && !label.getText().isBlank()) {
              check(
                  label.isWrapText() && !label.isTextTruncated(),
                  "Selected inspector text is truncated: " + label.getText());
              check(
                  label.getHeight() >= label.prefHeight(label.getWidth()) - 1,
                  "Selected inspector text lacks its preferred wrapped height: " + label.getText());
            }
          }
          check(
              ((Label) node("guided-open-reason"))
                  .getText()
                  .equals("Build metadata preparation is not connected yet."),
              "Open capability explanation was replaced or omitted");
          return null;
        });
    shot(stage, "selected");
    fx(
        () -> {
          ((ScrollPane) node("guided-discovery-scroll")).setVvalue(0);
          return null;
        });
    click("guided-filter-local");
    until(
        () ->
            fx(
                () ->
                    list().getItems().isEmpty()
                        && !((Button) node("guided-refresh")).isDisabled()));
    check(captures.get() == 1, "Local filter refreshed metadata");
    shot(stage, "local-empty");
    click("guided-filter-all");
    until(() -> fx(() -> list().getItems().size() == 1));
    click("guided-refresh");
    until(
        () ->
            fx(
                () ->
                    list().getItems().size() == 2
                        && !((Button) node("guided-refresh")).isDisabled()));
    check(captures.get() == 2, "Explicit refresh did not use one paired capture");
    check(
        fx(
                () ->
                    list().getItems().stream()
                        .filter(GuidedDiscoveryPane.BuildRow::newToVibeMiner)
                        .count())
            == 1,
        "Newly observed conflicting group lost durable New marker");
    check(
        fx(
                () ->
                    list().getItems().stream()
                        .map(GuidedDiscoveryPane.BuildRow::groupId)
                        .distinct()
                        .count())
            == 2,
        "Same-version conflicting CDN references were merged");
    check(
        count("discovery_history_events") == 4 && count("source_blobs") == 3,
        "Repeated regional capture did not preserve events/shared raw documents");
    shot(stage, "variants");
    click("guided-filter-currently_advertised");
    until(
        () ->
            fx(
                () ->
                    list().getItems().size() == 2
                        && !((Button) node("guided-refresh")).isDisabled()));
    check(captures.get() == 2, "Currently advertised filter performed a capture");
    click("guided-filter-all");
    until(() -> fx(() -> !((Button) node("guided-refresh")).isDisabled()));
    fx(
        () -> {
          for (int i = 0; i < list().getItems().size(); i++)
            if (list().getItems().get(i).newToVibeMiner()) {
              list().getSelectionModel().select(i);
              return null;
            }
          throw new AssertionError("Missing New row");
        });
    until(() -> fx(() -> stage.getScene().lookup("#guided-source-details") != null));
    check(count("workspaces") == 0, "Row inspection created a preparation context");
    Platform.runLater(() -> ((Button) node("guided-source-details")).fire());
    until(() -> fx(() -> dialogWindow() != null));
    Window dialog = fx(CaptureGuidedDiscoveryIntegration::dialogWindow);
    String receipts =
        fx(
            () ->
                dialog.getScene().getRoot().lookupAll(".text-area").stream()
                    .filter(TextArea.class::isInstance)
                    .map(TextArea.class::cast)
                    .map(TextArea::getText)
                    .findFirst()
                    .orElseThrow());
    check(
        receipts.contains(observed.get("wow").versions().source().sha256()),
        "Source dialog omitted actual persisted versions digest");
    check(
        receipts.contains("Source channel: Published")
            && receipts.contains("CDN configuration: " + "c".repeat(32)),
        "Source dialog substituted channel or selected identity");
    shot(dialog, "source-receipts");
    fx(
        () -> {
          DialogPane pane = (DialogPane) dialog.getScene().getRoot().lookup(".dialog-pane");
          ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
          return null;
        });
    check(captures.get() == 2, "Inspecting source receipts refreshed metadata");
    offline = true;
    click("guided-refresh");
    until(
        () ->
            fx(
                () ->
                    !((Button) node("guided-refresh")).isDisabled()
                        && !((Label) node("guided-discovery").lookup(".guided-error"))
                            .getText()
                            .isBlank()));
    check(fx(() -> list().getItems().size()) == 2, "Failed check discarded saved rows");
    check(captures.get() == 3, "Offline authored attempt count differs");
    shot(stage, "offline");
    offline = false;
    chooseProduct("wowt");
    until(
        () ->
            fx(
                () ->
                    list().getItems().size() == 1
                        && !((Button) node("guided-refresh")).isDisabled()));
    check(captures.get() == 4, "Changed product did not capture its own exact source once");
    var finalRow = fx(() -> list().getItems().getFirst());
    var reopened =
        new SqliteBuildLibraryReader(new SqliteCatalog(database))
            .select(finalRow.groupId(), CancellationToken.none());
    check(
        reopened.row().group().key().productId().equals("wowt"),
        "Product change reused previous product identity");
    check(
        count("catalog_objects") == 0 && count("source_aliases") == 0,
        "Discovery wrote game-content CAS records");
    check(
        !Files.exists(database.getParent().resolve("content")),
        "Discovery created a content store");
    check(legacyCalls.get() == 0, "Guided flow invoked hidden legacy services");
    shot(stage, "product-changed");
    complete = true;
    fx(
        () -> {
          stage.close();
          Platform.exit();
          return null;
        });
  }

  @SuppressWarnings("unchecked")
  private static ListView<GuidedDiscoveryPane.BuildRow> list() {
    return (ListView<GuidedDiscoveryPane.BuildRow>) node("guided-builds");
  }

  private static Node node(String id) {
    Node n = stage.getScene().lookup("#" + id);
    if (n == null) throw new AssertionError("Missing #" + id);
    return n;
  }

  private static Window dialogWindow() {
    return Window.getWindows().stream()
        .filter(w -> w != stage && w.isShowing())
        .findFirst()
        .orElse(null);
  }

  private static void chooseProduct(String id) throws Exception {
    fx(
        () -> {
          ComboBox<?> c = (ComboBox<?>) node("guided-product");
          Object option =
              c.getItems().stream()
                  .filter(
                      v ->
                          v instanceof GuidedDiscoveryPane.ProductOption p
                              && p.productId().equals(id))
                  .findFirst()
                  .orElseThrow();
          setCombo(c, option);
          return null;
        });
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void setCombo(ComboBox<?> c, Object value) {
    ((ComboBox) c).setValue(value);
  }

  private static void click(String id) throws Exception {
    fx(
        () -> {
          ((ButtonBase) node(id)).fire();
          return null;
        });
  }

  private static void clickText(String text) throws Exception {
    fx(
        () -> {
          var n =
              stage.getScene().getRoot().lookupAll(".button").stream()
                  .filter(v -> v instanceof ButtonBase b && b.getText().equals(text))
                  .findFirst();
          if (n.isEmpty())
            n =
                stage.getScene().getRoot().lookupAll(".toggle-button").stream()
                    .filter(v -> v instanceof ButtonBase b && b.getText().equals(text))
                    .findFirst();
          ((ButtonBase) n.orElseThrow()).fire();
          return null;
        });
  }

  private static long count(String table) throws Exception {
    try (var c = new SqliteCatalog(database).connect();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  private static void productInViewport() throws Exception {
    fx(
        () -> {
          stage.getScene().getRoot().applyCss();
          stage.getScene().getRoot().layout();
          var scroll = (ScrollPane) node("guided-discovery-scroll");
          Bounds viewport =
              scroll
                  .lookup(".viewport")
                  .localToScene(scroll.lookup(".viewport").getBoundsInLocal());
          var p = node("guided-product");
          Bounds control = p.localToScene(p.getBoundsInLocal());
          check(
              control.getMinY() >= viewport.getMinY() - 1
                  && control.getMaxY() <= viewport.getMaxY() + 1,
              "Actual Atlas product control is not in first viewport");
          check(
              control.getMaxX() <= viewport.getMaxX() + 1,
              "Actual Atlas product control is horizontally clipped");
          return null;
        });
  }

  private static void shot(Window window, String state) throws Exception {
    fx(
        () -> {
          window.getScene().getRoot().applyCss();
          window.getScene().getRoot().layout();
          WritableImage image = window.getScene().snapshot(null);
          BufferedImage png =
              new BufferedImage(
                  (int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
          int[] pixels = new int[png.getWidth() * png.getHeight()];
          image
              .getPixelReader()
              .getPixels(
                  0,
                  0,
                  png.getWidth(),
                  png.getHeight(),
                  PixelFormat.getIntArgbInstance(),
                  pixels,
                  0,
                  png.getWidth());
          png.setRGB(0, 0, png.getWidth(), png.getHeight(), pixels, 0, png.getWidth());
          ImageIO.write(
              png,
              "png",
              output
                  .resolve(
                      "atlas-guided-"
                          + state
                          + "-"
                          + width
                          + "x"
                          + height
                          + "-"
                          + scale
                          + "pct.png")
                  .toFile());
          return null;
        });
  }

  private static void check(boolean condition, String message) {
    assertions++;
    if (!condition) throw new AssertionError(message);
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    var task = new FutureTask<T>(action);
    Platform.runLater(task);
    try {
      return task.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof Exception e) throw e;
      if (failure.getCause() instanceof Error e) throw e;
      throw failure;
    }
  }

  private interface Condition {
    boolean test() throws Exception;
  }

  private static void until(Condition condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (!condition.test()) {
      if (System.nanoTime() > end)
        throw new AssertionError("Timed out waiting for guided integration state");
      Thread.sleep(25);
    }
  }
}
