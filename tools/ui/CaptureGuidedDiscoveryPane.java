import io.vibeminer.desktop.GuidedDiscoveryPane;
import io.vibeminer.desktop.GuidedDiscoveryPane.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Standalone presentation qualification with authored projections; never connects to a service. */
public final class CaptureGuidedDiscoveryPane {
  private static Path output;
  private static int width, height, scale, assertions;
  private static String preview;
  private static volatile boolean complete;
  private static GuidedDiscoveryPane pane;
  private static ScrollPane scroll;
  private static Stage stage;
  private static final List<String> calls = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    if (args.length != 12
        || !args[0].equals("--isolated-display")
        || !args[1].equals(System.getenv("DISPLAY"))
        || !args[2].equals("--output")
        || !args[4].equals("--width")
        || !args[6].equals("--height")
        || !args[8].equals("--scale")
        || !args[10].equals("--state")) {
      throw new IllegalArgumentException(
          "Usage: CaptureGuidedDiscoveryPane --isolated-display :N --output DIR --width 1440"
              + " --height 1100 --scale 100 --state ready");
    }
    output = Path.of(args[3]);
    width = Integer.parseInt(args[5]);
    height = Integer.parseInt(args[7]);
    scale = Integer.parseInt(args[9]);
    preview = args[11];
    if (!((width == 1440 && height == 1100) || (width == 1280 && height == 800))
        || !(scale == 100 || scale == 200)
        || !List.of("ready", "offline", "loading", "empty", "longproducts", "selectiondisabled")
            .contains(preview)) {
      throw new IllegalArgumentException("Use reviewed viewport, scale and fixture state");
    }
    Files.createDirectories(output);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, failure) -> {
          failure.printStackTrace();
          System.exit(1);
        });
    Thread deadline =
        new Thread(
            () -> {
              try {
                Thread.sleep(30000);
              } catch (InterruptedException stopped) {
                return;
              }
              if (!complete) {
                System.err.println("Guided discovery capture timed out");
                System.exit(2);
              }
            });
    deadline.setDaemon(true);
    deadline.start();
    Application.launch(ReviewWindow.class);
    if (!complete) throw new IllegalStateException("Guided discovery capture did not complete");
    System.out.println(
        "Guided discovery standalone: "
            + assertions
            + " assertions; state="
            + preview
            + "; "
            + width
            + "x"
            + height
            + "; "
            + scale
            + "%; no service/HTTP configured");
  }

  public static final class ReviewWindow extends Application {
    @Override
    public void start(Stage window) {
      stage = window;
      pane =
          new GuidedDiscoveryPane(
              fixture("ready"),
              new Callbacks(
                  id -> calls.add("product:" + id),
                  filter -> calls.add("filter:" + filter),
                  id -> {
                    calls.add("select:" + id);
                    pane.update(selected(id));
                  },
                  id -> calls.add("open:" + id),
                  () -> calls.add("refresh"),
                  () -> calls.add("more"),
                  id -> calls.add("source:" + id)));
      scroll = new ScrollPane(pane);
      scroll.setFitToWidth(true);
      scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
      Label fixture = new Label("VibeMiner · GUIDED DISCOVERY · authored native review fixtures");
      fixture.setStyle("-fx-padding: 10 24; -fx-font-size: 0.7857em; -fx-text-fill: #657870;");
      BorderPane root = new BorderPane(scroll);
      root.setTop(fixture);
      root.setStyle("-fx-font-size: " + (14 * scale / 100) + "px;");
      Scene scene = new Scene(root, width, height);
      scene
          .getStylesheets()
          .add(GuidedDiscoveryPane.class.getResource("atlas.css").toExternalForm());
      stage.initStyle(StageStyle.UNDECORATED);
      stage.setScene(scene);
      stage.setX(0);
      stage.setY(0);
      stage.show();
      later(CaptureGuidedDiscoveryPane::interactions);
    }
  }

  private static ViewData selected(String id) {
    ViewData original = fixture("ready");
    return new ViewData(
        original.products(),
        original.selectedProductId(),
        original.rows(),
        id,
        original.filter(),
        false,
        true,
        false,
        original.message(),
        "");
  }

  private static ViewData fixture(String state) {
    List<ProductOption> products =
        List.of(
            new ProductOption("wow", "World of Warcraft · Retail"),
            new ProductOption("wowt", "World of Warcraft · PTR"));
    String selectedProduct = "wow";
    List<BuildRow> builds =
        List.of(
            new BuildRow(
                "same-refs-us-eu-kr",
                "12.1.5.70448",
                List.of("us", "eu", "kr"),
                "Partial",
                true,
                !state.equals("offline"),
                false,
                List.of(
                    new Detail("PRODUCT / SOURCE CHANNEL", "wow / Published"),
                    new Detail(
                        "REGIONAL OBSERVATIONS",
                        "US · EU · KR — matching identity-bearing references"),
                    new Detail("PREFERRED DOWNLOAD SOURCE", "US"),
                    new Detail("BUILD CONFIGURATION", "a1".repeat(16)),
                    new Detail("CDN CONFIGURATION", "d4".repeat(16)),
                    new Detail(
                        "LOCAL STATE",
                        "Explicit build-preparation context; metadata retained. Fixture only.")),
                true,
                "Required metadata is reused or prepared before the build opens."),
            new BuildRow(
                "conflicting-tw",
                "12.1.5.70448",
                List.of("tw"),
                "Not ready",
                false,
                true,
                true,
                List.of(
                    new Detail("BUILD CONFIGURATION", "b2".repeat(16)),
                    new Detail(
                        "DISTINCT IDENTITY",
                        "Different declared configuration; never merged by version text.")),
                false,
                "Required metadata grammar is unsupported in this authored example."),
            new BuildRow(
                "local-older",
                "12.1.0.70000",
                List.of("us"),
                "Not ready",
                true,
                false,
                false,
                List.of(
                    new Detail(
                        "LOCAL STATE", "Partial canceled preparation; Local does not mean ready.")),
                true,
                "Retry preparing the exact saved build."));
    String selected = "same-refs-us-eu-kr";
    if (state.equals("empty")) {
      builds = List.of();
      selected = null;
    }
    if (state.equals("selectiondisabled")) selected = "conflicting-tw";
    if (state.equals("offline")) builds = List.of(builds.getFirst(), builds.getLast());
    if (state.equals("longproducts")) {
      selectedProduct = "wow_review_unresolved_source_with_long_exact_identifier";
      products =
          List.of(
              new ProductOption(
                  selectedProduct, "Warcraft source — descriptive edition mapping unconfirmed"));
      builds = List.of();
      selected = null;
    }
    return new ViewData(
        products,
        selectedProduct,
        builds,
        selected,
        Filter.ALL,
        state.equals("loading"),
        !state.equals("loading"),
        false,
        state.equals("offline")
            ? "Offline · saved local contexts only. Advertisement has not been refreshed."
            : state.equals("loading")
                ? "Checking official metadata. Existing local observations remain visible."
                : "Authored projections only. Counts, identities and source combinations are"
                    + " synthetic.",
        state.equals("selectiondisabled")
            ? "The selected build needs supported metadata before opening."
            : "");
  }

  @SuppressWarnings("unchecked")
  private static void interactions() {
    check(calls.isEmpty(), "Construction caused callbacks");
    pane.update(selected("conflicting-tw"));
    pane.update(fixture("ready"));
    check(calls.isEmpty(), "Programmatic projection restoration caused callbacks");
    ListView<BuildRow> list = (ListView<BuildRow>) node("guided-builds");
    list.getSelectionModel().select(1);
    check(
        calls.equals(List.of("select:conflicting-tw")),
        "Selection did more than inspect exact row");
    list.fireEvent(
        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    check(calls.stream().noneMatch(c -> c.startsWith("open:")), "Enter on row opened a build");
    Button disabled = (Button) node("guided-open-build");
    check(disabled.isDisabled(), "Unsupported metadata enabled Open Build");
    disabled.fire();
    check(calls.stream().noneMatch(c -> c.startsWith("open:")), "Disabled action dispatched Open");
    list.getSelectionModel().select(0);
    ((Button) node("guided-open-build")).fire();
    check(
        calls.stream()
            .filter(c -> c.startsWith("open:"))
            .toList()
            .equals(List.of("open:same-refs-us-eu-kr")),
        "Sole Open action lost exact group identity");
    ComboBox<ProductOption> product = (ComboBox<ProductOption>) node("guided-product");
    product.getSelectionModel().select(1);
    check(
        calls.stream()
            .filter(c -> c.startsWith("product:"))
            .toList()
            .equals(List.of("product:wowt")),
        "Product selection did not dispatch once");
    pane.update(fixture("ready"));
    ((ToggleButton) node("guided-filter-local")).fire();
    check(calls.contains("filter:LOCAL"), "Local filter did not dispatch");
    ((Button) node("guided-refresh")).fire();
    ((Button) node("guided-source-details")).fire();
    check(
        calls.contains("refresh") && calls.contains("source:same-refs-us-eu-kr"),
        "Explicit refresh/source actions missing");
    var base = fixture("ready");
    pane.update(
        new ViewData(
            base.products(),
            base.selectedProductId(),
            base.rows(),
            base.selectedBuildId(),
            base.filter(),
            false,
            true,
            true,
            "",
            ""));
    ((Button) node("guided-more")).fire();
    check(calls.stream().filter("more"::equals).count() == 1, "Paging callback missing");
    pane.update(fixture(preview));
    scroll.setVvalue(0);
    later(CaptureGuidedDiscoveryPane::verifyAndCapture);
  }

  private static void verifyAndCapture() {
    stage.getScene().getRoot().applyCss();
    stage.getScene().getRoot().layout();
    Node product = node("guided-product");
    Bounds viewport =
        scroll.lookup(".viewport").localToScene(scroll.lookup(".viewport").getBoundsInLocal());
    Bounds productBounds = product.localToScene(product.getBoundsInLocal());
    check(
        productBounds.getMinY() >= viewport.getMinY() - 1
            && productBounds.getMaxY() <= viewport.getMaxY() + 1,
        "Product control is not in first viewport");
    check(
        productBounds.getMinX() >= viewport.getMinX() - 1
            && productBounds.getMaxX() <= viewport.getMaxX() + 1,
        "Product control is horizontally clipped");
    Label heading = (Label) pane.getChildren().getFirst();
    Bounds title = heading.localToScene(heading.getBoundsInLocal());
    check(productBounds.getMinY() > title.getMaxY(), "Product is not below heading");
    check(
        stage.getScene().getWidth() == width && stage.getScene().getHeight() == height,
        "Capture viewport differs from declared dimensions");
    if (!List.of("empty", "longproducts").contains(preview)) {
      var cells =
          pane.lookupAll(".list-cell").stream()
              .filter(
                  n ->
                      n instanceof ListCell<?> cell
                          && cell.getItem() instanceof BuildRow row
                          && row.groupId().equals("same-refs-us-eu-kr"))
              .toList();
      check(!cells.isEmpty(), "First grouped row was not rendered");
      String accessible = cells.getFirst().getAccessibleText();
      check(accessible.contains("Local"), "Local badge missing from accessible name");
      if (!preview.equals("offline"))
        check(
            accessible.contains("Currently advertised"),
            "Publication badge missing from accessible name");
    }
    long openButtons =
        pane.lookupAll(".button").stream()
            .filter(n -> n instanceof Button b && b.getText().equals("Open Build"))
            .count();
    check(
        openButtons == (List.of("empty", "longproducts").contains(preview) ? 0 : 1),
        "Unexpected duplicate Open actions");
    if (preview.equals("loading") || preview.equals("selectiondisabled"))
      check(node("guided-open-build").isDisabled(), "Busy/unsupported selection admits Open");
    if (preview.equals("longproducts")) {
      ComboBox<?> choice = (ComboBox<?>) product;
      String full = choice.getValue().toString();
      check(full.contains("wow_review_unresolved"), "Long raw product identity was replaced");
      check(
          choice.getAccessibleText().contains(full),
          "Full product identity/description missing from accessible name");
      check(
          choice.getTooltip() != null && choice.getTooltip().getText().equals(full),
          "Truncated product display has no complete tooltip");
    }
    if (openButtons == 1) {
      for (Node n : node("guided-inspector").lookupAll(".label")) {
        if (n instanceof Label label && !label.getText().isBlank()) {
          check(!label.isTextTruncated(), "Inspector text is truncated: " + label.getText());
          check(
              label.getHeight() >= label.prefHeight(label.getWidth()) - 1,
              "Inspector lacks preferred wrapped text height: " + label.getText());
        }
      }
      Node open = node("guided-open-build");
      Node focusTarget = open.isDisabled() ? node("guided-source-details") : open;
      focusTarget.requestFocus();
      scroll.setVvalue(1);
      later(
          () -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            Bounds visible =
                scroll
                    .lookup(".viewport")
                    .localToScene(scroll.lookup(".viewport").getBoundsInLocal());
            Bounds action = open.localToScene(open.getBoundsInLocal());
            check(
                stage.getScene().getFocusOwner() == focusTarget,
                "Inspector action cannot receive keyboard focus");
            check(
                action.getMinY() >= visible.getMinY() - 1
                    && action.getMaxY() <= visible.getMaxY() + 1,
                "Open Build cannot be reached vertically by scrolling");
            check(
                action.getMinX() >= visible.getMinX() - 1
                    && action.getMaxX() <= visible.getMaxX() + 1,
                "Open Build is horizontally clipped after scrolling");
            scroll.setVvalue(0);
            product.requestFocus();
            later(CaptureGuidedDiscoveryPane::finishCapture);
          });
    } else finishCapture();
  }

  private static void finishCapture() {
    stage.getScene().getRoot().applyCss();
    stage.getScene().getRoot().layout();
    Bounds viewport =
        scroll.lookup(".viewport").localToScene(scroll.lookup(".viewport").getBoundsInLocal());
    Node product = node("guided-product");
    Bounds productBounds = product.localToScene(product.getBoundsInLocal());
    check(
        productBounds.getMinY() >= viewport.getMinY() - 1
            && productBounds.getMaxY() <= viewport.getMaxY() + 1,
        "Capture did not return to the product-first viewport");
    capture();
    complete = true;
    stage.close();
    Platform.exit();
  }

  private static void capture() {
    try {
      WritableImage fx = stage.getScene().snapshot(null);
      BufferedImage image =
          new BufferedImage((int) fx.getWidth(), (int) fx.getHeight(), BufferedImage.TYPE_INT_ARGB);
      int[] pixels = new int[image.getWidth() * image.getHeight()];
      fx.getPixelReader()
          .getPixels(
              0,
              0,
              image.getWidth(),
              image.getHeight(),
              PixelFormat.getIntArgbInstance(),
              pixels,
              0,
              image.getWidth());
      image.setRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
      ImageIO.write(
          image,
          "png",
          output
              .resolve("guided-" + preview + "-" + width + "x" + height + "-" + scale + "pct.png")
              .toFile());
    } catch (Exception failure) {
      throw new RuntimeException(failure);
    }
  }

  private static Node node(String id) {
    Node result = pane.lookup("#" + id);
    if (result == null) throw new AssertionError("Missing #" + id);
    return result;
  }

  private static void check(boolean value, String message) {
    assertions++;
    if (!value) throw new AssertionError(message);
  }

  private static void later(Runnable action) {
    PauseTransition pause = new PauseTransition(Duration.millis(250));
    pause.setOnFinished(e -> action.run());
    pause.play();
  }
}
