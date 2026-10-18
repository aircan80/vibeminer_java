package io.vibeminer.bootstrap;

import io.vibeminer.application.buildopening.*;
import io.vibeminer.desktop.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.*;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.*;
import javax.imageio.ImageIO;

/**
 * Actual Atlas/application/adapters/SQLite/CAS with authored HTTP responses, never fake admission.
 */
public final class CaptureBuildOpeningIntegration {
  private static Path output;
  private static int width, height, scale, assertions;
  private static Stage stage;
  private static volatile boolean complete;
  private static BuildOpeningReviewData data;
  private static final AtomicReference<BuildMetadataAdmission> admitted = new AtomicReference<>();
  private static final List<BuildOpeningService.OpenOperation> operations =
      new CopyOnWriteArrayList<>();

  public static void main(String[] args) throws Exception {
    if (args.length != 10
        || !args[0].equals("--isolated-display")
        || !args[1].equals(System.getenv("DISPLAY"))
        || !args[2].equals("--output")
        || !args[4].equals("--width")
        || !args[6].equals("--height")
        || !args[8].equals("--scale"))
      throw new IllegalArgumentException(
          "Usage: CaptureBuildOpeningIntegration --isolated-display :N --output DIR --width 1440"
              + " --height 1100 --scale 100");
    output = Path.of(args[3]);
    width = Integer.parseInt(args[5]);
    height = Integer.parseInt(args[7]);
    scale = Integer.parseInt(args[9]);
    if (!((width == 1440 && height == 1100) || (width == 1280 && height == 800))
        || !(scale == 100 || scale == 200))
      throw new IllegalArgumentException("Unsupported review geometry/text scale");
    Files.createDirectories(output);
    var directory = Files.createTempDirectory(output, "authored-open-data-");
    data = new BuildOpeningReviewData();
    try (var metadata = MetadataRuntime.open(directory, data::newClient, gateway -> gateway);
        var content =
            new LazyBootstrapService(() -> ContentRuntime.open(directory, data::newClient));
        var opening = new LazyBuildOpeningService(content)) {
      AtlasApplication.configure(
          metadata.discovery(),
          metadata.workspaces(),
          () -> {
            throw new AssertionError(
                "Independent product discovery is outside this guided journey");
          },
          false);
      AtlasApplication.configureBuildLibrary(metadata.library());
      AtlasApplication.configureBuildOpening(
          new BuildOpeningService() {
            public OpenOperation open(
                io.vibeminer.application.buildlibrary.BuildLibraryReference reference,
                String locale) {
              var operation = opening.open(reference, locale);
              operations.add(operation);
              operation
                  .completion()
                  .whenComplete(
                      (result, failure) -> {
                        if (failure == null) admitted.set(result);
                      });
              return operation;
            }

            public void close() {
              throw new AssertionError("UI must not close borrowed opening service");
            }
          });
      Thread.setDefaultUncaughtExceptionHandler(
          (thread, failure) -> {
            failure.printStackTrace();
            System.exit(1);
          });
      var timeout =
          new Thread(
              () -> {
                try {
                  Thread.sleep(90000);
                } catch (InterruptedException stopped) {
                  return;
                }
                if (!complete) System.exit(2);
              });
      timeout.setDaemon(true);
      timeout.start();
      var controller =
          new Thread(
              () -> {
                try {
                  journey();
                } catch (Throwable failure) {
                  failure.printStackTrace();
                  System.exit(1);
                }
              },
              "actual-open-review");
      controller.setDaemon(true);
      controller.start();
      try {
        Application.launch(AtlasApplication.class);
      } finally {
        data.releaseEncoding();
      }
    }
    if (!complete) throw new AssertionError("Open journey did not complete");
    System.out.println(
        "Actual Atlas Open: "
            + assertions
            + " assertions; HTTP "
            + data.requests()
            + "; game objects "
            + data.gameObjectRequests()
            + "; "
            + width
            + "x"
            + height
            + "; "
            + scale
            + "% application text");
  }

  private static void journey() throws Exception {
    until(
        () -> {
          try {
            return fx(
                () -> {
                  var found =
                      Window.getWindows().stream()
                          .filter(w -> w instanceof Stage && w.isShowing())
                          .findFirst();
                  if (found.isEmpty()) return false;
                  stage = (Stage) found.orElseThrow();
                  return true;
                });
          } catch (IllegalStateException startup) {
            if (!startup.getMessage().contains("Toolkit not initialized")) throw startup;
            return false;
          }
        });
    fx(
        () -> {
          var marker = new Label("AUTHORED HTTP REVIEW DATA · NO LIVE CDN");
          marker.setId("open-authored-fixture-marker");
          marker.setStyle(
              "-fx-padding: 7 24; -fx-background-color: #fff3dc; -fx-text-fill: #775a2a;");
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
              if (n instanceof ComboBox<?> combo
                  && "Application text scale".equals(combo.getAccessibleText())) {
                setCombo(combo, "200%");
                return null;
              }
            throw new AssertionError("Missing scale control");
          });
      clickText("Discover builds");
    }
    fx(
        () -> {
          stage.setWidth(stage.getWidth() + width - stage.getScene().getWidth());
          stage.setHeight(stage.getHeight() + height - stage.getScene().getHeight());
          return null;
        });
    check(data.requests() == 0, "Startup/settings must not request discovery/content HTTP");
    check(operations.isEmpty(), "Startup must not Open");
    chooseProduct("wow");
    until(() -> fx(() -> !list().getItems().isEmpty() && !node("guided-refresh").isDisabled()));
    int discoveredRequests = data.requests();
    check(discoveredRequests == 2, "One paired versions/CDNs product refresh");
    click("guided-filter-currently_advertised");
    until(() -> fx(() -> !list().getItems().isEmpty()));
    fx(
        () -> {
          list().getSelectionModel().selectFirst();
          return null;
        });
    until(
        () ->
            fx(
                () ->
                    stage.getScene().lookup("#guided-open-build") != null
                        && !node("guided-open-build").isDisabled()));
    String selected = fx(() -> list().getSelectionModel().getSelectedItem().groupId());
    check(
        data.requests() == discoveredRequests && operations.isEmpty(),
        "Filter/selection inspects only");
    shot(stage, "selected");
    data.holdEncoding();
    click("guided-open-build");
    check(
        data.awaitEncoding(Duration.ofSeconds(5)),
        "Actual EN acquisition held for progress review");
    until(
        () ->
            fx(
                () ->
                    node("guided-opening-cancel").isVisible()
                        && !((Label) node("guided-opening-progress")).getText().isBlank()));
    until(() -> fx(() -> visibleInViewport(node("guided-opening-cancel"))));
    fx(
        () -> {
          check(
              node("guided-open-build").isDisabled(),
              "Sole Open disabled during actual preparation");
          check(node("guided-opening-cancel").isFocused(), "Active Cancel receives keyboard focus");
          check(
              node("guided-opening-progress").getParent().getParent() == node("guided-inspector"),
              "Progress stays in selected inspector");
          check(
              !((Label) node("guided-opening-progress")).isTextTruncated(),
              "Metadata stage/counters not truncated");
          return null;
        });
    check(admitted.get() == null, "Held metadata cannot admit C");
    check(data.gameObjectRequests() == 0, "Open fetches no game payload");
    shot(stage, "preparing");
    click("guided-opening-cancel");
    check(
        data.awaitEncodingCancellation(Duration.ofSeconds(5)),
        "Actual pending EN HTTP response observes transport cancellation before body release");
    data.releaseEncoding();
    until(() -> fx(() -> !node("guided-open-build").isDisabled()));
    check(
        fx(() -> stage.getScene().lookup("#build-overview") == null),
        "Canceled Open remains selected discovery");
    shot(stage, "canceled");
    // The actual source worker may be settling after cooperative cancellation. Retry only through
    // the UI.
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
    do {
      click("guided-open-build");
      until(
          () ->
              fx(
                  () -> {
                    Node overview = stage.getScene().lookup("#build-overview");
                    Node open = stage.getScene().lookup("#guided-open-build");
                    return overview != null || open != null && !open.isDisabled();
                  }));
      if (fx(() -> stage.getScene().lookup("#build-overview") != null)) break;
      if (System.nanoTime() > end) {
        var latest = operations.getLast();
        Throwable failure = null;
        try {
          latest.completion().toCompletableFuture().getNow(null);
        } catch (RuntimeException observed) {
          failure = observed;
        }
        throw new AssertionError(
            "Actual canceled worker did not retire for retry; latest progress=" + latest.progress(),
            failure);
      }
      Thread.sleep(50);
    } while (true);
    assertOverview();
    shot(stage, "admitted");
    int retainedRequests = data.requests();
    click("overview-back");
    check(
        fx(() -> list().getSelectionModel().getSelectedItem().groupId().equals(selected)),
        "Back preserves selected exact group");
    check(
        fx(() -> ((ToggleButton) node("guided-filter-currently_advertised")).isSelected()),
        "Back preserves filter");
    check(data.requests() == retainedRequests, "Back does not refresh");
    shot(stage, "back");
    data.offline();
    click("guided-open-build");
    until(() -> fx(() -> stage.getScene().lookup("#build-overview") != null));
    assertOverview();
    check(
        data.requests() == retainedRequests,
        "Offline reopening verifies retained metadata with zero HTTP");
    check(data.gameObjectRequests() == 0, "Entire mining entry journey avoids game payload");
    shot(stage, "offline-reopened");
    complete = true;
    fx(
        () -> {
          stage.close();
          Platform.exit();
          return null;
        });
  }

  private static void assertOverview() throws Exception {
    var admission = admitted.get();
    check(admission != null, "Actual gateway admission required");
    check(
        admission.required().size() == 7 && admission.verified().size() == 7,
        "Actual seven-role verified metadata closure");
    check(
        admission.reference().key().productId().equals("wow") && admission.locale().equals("enUS"),
        "Exact source/research locale preserved");
    fx(
        () -> {
          check(
              ((Label) node("overview-status")).getText().equals("Partial"),
              "Complete metadata enters Partial content state");
          check(
              ((Label) node("overview-retained")).getText().equals("Unknown"),
              "Metadata admission invents no content percentage");
          check(
              node("overview-on-demand").isDisabled() && node("overview-download-all").isDisabled(),
              "Bulk policy unavailable without inventory/retention facade");
          check(
              stage.getScene().lookup("#overview-job") == null,
              "No invented inventory/background job");
          return null;
        });
  }

  private static boolean visibleInViewport(Node target) {
    var scroll = (ScrollPane) node("guided-discovery-scroll");
    Node viewport = scroll.lookup(".viewport");
    if (viewport == null) return false;
    Bounds t = target.localToScene(target.getBoundsInLocal()),
        v = viewport.localToScene(viewport.getBoundsInLocal());
    return t.getMinY() >= v.getMinY() - 1 && t.getMaxY() <= v.getMaxY() + 1;
  }

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
                      "atlas-open-" + state + "-" + width + "x" + height + "-" + scale + "pct.png")
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
