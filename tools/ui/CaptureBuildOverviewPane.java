import io.vibeminer.desktop.BuildOverviewPane;
import io.vibeminer.desktop.BuildOverviewPane.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javafx.animation.PauseTransition;
import javafx.application.*;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.*;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Actual C pane and controls over authored display snapshots; no application/download service. */
public final class CaptureBuildOverviewPane {
  private static final long GIB = 1024L * 1024 * 1024;
  private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  private static final Identity IDENTITY =
      new Identity(
          "authored-context",
          "World of Warcraft · Retail",
          "wow",
          "Published",
          "us",
          "12.1.5.70448",
          "enUS");
  private static final Scope SCOPE =
      new Scope("authored-all-declared-v1", "All declared languages and platforms");
  private static Path output;
  private static int width, height, scale, assertions;
  private static String preview;
  private static volatile boolean complete;
  private static Stage stage;
  private static ScrollPane scroll;
  private static BuildOverviewPane pane;
  private static ViewData displayed;
  private static final List<ModeRequest> modeRequests = new ArrayList<>();
  private static final List<Identity> sourceRequests = new ArrayList<>();
  private static int backs, metadataRetries, retentionRetries;

  public static void main(String[] args) throws Exception {
    if (args.length != 12
        || !args[0].equals("--isolated-display")
        || !args[1].equals(System.getenv("DISPLAY"))
        || !args[2].equals("--output")
        || !args[4].equals("--width")
        || !args[6].equals("--height")
        || !args[8].equals("--scale")
        || !args[10].equals("--state"))
      throw new IllegalArgumentException(
          "Usage: CaptureBuildOverviewPane --isolated-display :N --output DIR --width 1440 --height"
              + " 1100 --scale 100 --state partial");
    output = Path.of(args[3]);
    width = Integer.parseInt(args[5]);
    height = Integer.parseInt(args[7]);
    scale = Integer.parseInt(args[9]);
    preview = args[11];
    if (!((width == 1440 && height == 1100) || (width == 1280 && height == 800))
        || !(scale == 100 || scale == 200)
        || !List.of("preparing", "partial", "downloading", "paused", "full", "corrupt")
            .contains(preview))
      throw new IllegalArgumentException("Unsupported C review state, viewport or text scale");
    Files.createDirectories(output);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, failure) -> {
          failure.printStackTrace();
          System.exit(1);
        });
    Thread timeout =
        new Thread(
            () -> {
              try {
                Thread.sleep(30000);
              } catch (InterruptedException stopped) {
                return;
              }
              if (!complete) {
                System.err.println("C overview capture timed out");
                System.exit(2);
              }
            });
    timeout.setDaemon(true);
    timeout.start();
    Application.launch(ReviewWindow.class);
    if (!complete) throw new IllegalStateException("C overview capture did not complete");
    System.out.println(
        "C overview standalone: "
            + assertions
            + " assertions; state="
            + preview
            + "; "
            + width
            + "x"
            + height
            + "; "
            + scale
            + "% application text; display callbacks only, no service/network/storage");
  }

  public static final class ReviewWindow extends Application {
    @Override
    public void start(Stage window) {
      stage = window;
      displayed = fixture("partial");
      pane =
          new BuildOverviewPane(
              displayed,
              new Callbacks(
                  modeRequests::add,
                  sourceRequests::add,
                  () -> backs++,
                  () -> metadataRetries++,
                  () -> retentionRetries++));
      scroll = new ScrollPane(pane);
      scroll.setFitToWidth(true);
      scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
      Label marker = new Label("VibeMiner · AUTHORED C OVERVIEW DATA · NO DOWNLOAD SERVICE");
      marker.setId("overview-fixture-marker");
      marker.setStyle(
          "-fx-padding: 9 24; -fx-font-size: 0.7857em; -fx-background-color: #fff3dc;"
              + " -fx-text-fill: #775a2a;");
      BorderPane root = new BorderPane(scroll);
      root.setTop(marker);
      root.setStyle("-fx-font-size: " + (14 * scale / 100) + "px;");
      Scene scene = new Scene(root, width, height);
      scene.getStylesheets().add(BuildOverviewPane.class.getResource("atlas.css").toExternalForm());
      stage.initStyle(StageStyle.UNDECORATED);
      stage.setX(0);
      stage.setY(0);
      stage.setScene(scene);
      stage.show();
      later(CaptureBuildOverviewPane::interactions);
    }
  }

  private static ViewData fixture(String name) {
    boolean unknown = List.of("preparing", "corrupt").contains(name);
    long retained =
        name.equals("full")
            ? 1184 * GIB / 10
            : List.of("downloading", "paused").contains(name) ? 186 * GIB / 10 : 42 * GIB / 10;
    long physical =
        name.equals("full")
            ? 90 * GIB
            : List.of("downloading", "paused").contains(name) ? 15 * GIB : 3 * GIB;
    long shared =
        name.equals("full")
            ? 12 * GIB
            : List.of("downloading", "paused").contains(name) ? 2 * GIB : GIB;
    Metrics metrics =
        new Metrics(
            SCOPE.revision(),
            NOW,
            unknown ? OptionalLong.empty() : OptionalLong.of(1284630),
            unknown ? OptionalLong.empty() : OptionalLong.of(1421907),
            unknown ? OptionalLong.empty() : OptionalLong.of(1184 * GIB / 10),
            OptionalLong.of(retained),
            !unknown,
            OptionalLong.of(physical),
            OptionalLong.of(shared),
            OptionalLong.of(286L * 1024 * 1024));
    MetadataState metadata =
        name.equals("preparing")
            ? MetadataState.PREPARING
            : name.equals("corrupt") ? MetadataState.NEEDS_ATTENTION : MetadataState.READY;
    Readiness status =
        unknown ? Readiness.NOT_READY : name.equals("full") ? Readiness.FULL : Readiness.PARTIAL;
    DownloadMode mode =
        List.of("downloading", "paused").contains(name)
            ? DownloadMode.DOWNLOAD_ALL
            : DownloadMode.ON_DEMAND;
    String activity =
        switch (name) {
          case "preparing" -> "Verifying metadata";
          case "downloading" -> "Downloading";
          case "paused" -> "Paused";
          case "corrupt" -> "Needs attention";
          default -> "";
        };
    String job =
        switch (name) {
          case "preparing" ->
              "Required metadata verification is still in progress. The complete content inventory"
                  + " and total cost are not known.";
          case "downloading" ->
              "Background retention is active for the exact all-declared-variants scope. Encoded"
                  + " coverage includes reused objects; it is not wire-transfer progress.";
          case "paused" ->
              "Background retention is paused. Verified objects remain retained and shared.";
          case "corrupt" ->
              "A required metadata object failed verification. Retained objects remain present;"
                  + " metadata readiness must be restored before continuing.";
          default -> "";
        };
    return new ViewData(
        IDENTITY,
        SCOPE,
        status,
        metadata,
        metrics,
        mode,
        activity,
        job,
        name.equals("corrupt")
            ? List.of("Required metadata needs verification. No complete-build claim can be made.")
            : List.of(),
        new Capabilities(
            !unknown,
            false,
            name.equals("corrupt"),
            name.equals("corrupt"),
            unknown
                ? "Download all is unavailable until the required metadata and scope are ready."
                : ""));
  }

  private static void show(ViewData value) {
    displayed = value;
    pane.update(value);
  }

  private static void interactions() {
    check(
        modeRequests.isEmpty() && sourceRequests.isEmpty() && backs == 0,
        "Construction dispatched actions");
    for (String state : List.of("downloading", "full", "corrupt", "partial")) show(fixture(state));
    check(modeRequests.isEmpty(), "Projection restoration changed policy");
    click("overview-on-demand");
    check(modeRequests.isEmpty(), "Same policy emitted a change");
    String retained = ((Label) node("overview-retained")).getText();
    click("overview-download-all");
    check(
        modeRequests.equals(
            List.of(
                new ModeRequest(
                    IDENTITY.contextId(), SCOPE.revision(), DownloadMode.DOWNLOAD_ALL))),
        "Mode request lost exact context or scope");
    check(
        ((ToggleButton) node("overview-on-demand")).isSelected(),
        "Unconfirmed policy request optimistically changed the control");
    check(
        ((Label) node("overview-mode")).getText().equals("On demand"),
        "Unconfirmed request pretended to start a job");
    check(
        ((Label) node("overview-retained")).getText().equals(retained),
        "Policy request reset retained progress");
    show(fixture("downloading"));
    click("overview-on-demand");
    check(
        modeRequests.size() == 2 && modeRequests.getLast().requested() == DownloadMode.ON_DEMAND,
        "Active bulk cannot request on-demand policy");
    check(
        displayed.metrics().retainedScopeEncodedBytes().orElseThrow() == 186 * GIB / 10,
        "Mode control mutated supplied stored-byte observation");
    show(fixture("full"));
    click("overview-download-all");
    check(
        ((Label) node("overview-status")).getText().equals("Fully downloaded"),
        "Mode request downgraded full readiness");
    show(fixture("corrupt"));
    int before = modeRequests.size();
    click("overview-download-all");
    check(
        modeRequests.size() == before && node("overview-download-all").isDisabled(),
        "Unavailable policy dispatched a request");
    click("overview-retry-metadata");
    click("overview-retry-retention");
    check(
        metadataRetries == 1 && retentionRetries == 1,
        "Supplied retry capabilities did not dispatch separately");
    ViewData partial = fixture("partial");
    show(
        new ViewData(
            partial.identity(),
            partial.scope(),
            partial.readiness(),
            partial.metadata(),
            partial.metrics(),
            partial.mode(),
            partial.activity(),
            partial.jobSummary(),
            partial.problems(),
            new Capabilities(true, true, false, false, "Policy update pending")));
    click("overview-download-all");
    check(modeRequests.size() == before, "Busy policy admitted another request");
    show(fixture("partial"));
    click("overview-source");
    click("overview-back");
    check(
        sourceRequests.equals(List.of(IDENTITY)) && backs == 1,
        "Source/back actions changed exact identity or failed");
    show(fixture(preview));
    scroll.setVvalue(0);
    later(CaptureBuildOverviewPane::verifyAndReach);
  }

  private static void verifyAndReach() {
    stage.getScene().getRoot().applyCss();
    stage.getScene().getRoot().layout();
    check(
        stage.getScene().getWidth() == width && stage.getScene().getHeight() == height,
        "Wrong declared capture viewport");
    check(
        ((Label) stage.getScene().lookup("#overview-fixture-marker"))
            .getText()
            .contains("AUTHORED"),
        "Missing authored fixture marker");
    FlowPaneOrder();
    Bounds scopeBounds =
        node("overview-scope").localToScene(node("overview-scope").getBoundsInLocal());
    Node facts = node("overview-facts");
    Bounds factArea = facts.localToScene(facts.getBoundsInLocal());
    Bounds bodyBounds =
        node("overview-body").localToScene(node("overview-body").getBoundsInLocal());
    for (Node card : facts.lookupAll(".overview-fact")) {
      Bounds cardBounds = card.localToScene(card.getBoundsInLocal());
      check(
          cardBounds.getMinY() >= scopeBounds.getMaxY() - 1,
          "Fact card paints over the content scope");
      check(
          cardBounds.getMaxY() <= bodyBounds.getMinY() + 1,
          "Fact card paints underneath the storage/readiness body");
      check(
          cardBounds.getMinY() >= factArea.getMinY() - 1
              && cardBounds.getMaxY() <= factArea.getMaxY() + 1,
          "Fact card escapes its allocated facts container");
      for (Node child : card.lookupAll(".label")) {
        Bounds labelBounds = child.localToScene(child.getBoundsInLocal());
        check(
            labelBounds.getMinY() >= cardBounds.getMinY() - 1
                && labelBounds.getMaxY() <= cardBounds.getMaxY() + 1,
            "Fact label paints beyond its own card");
      }
    }
    check(pane.lookupAll(".toggle-button").size() == 2, "Duplicate policy switches appeared");
    check(
        node("overview-identity").lookupAll(".toggle-button").isEmpty(),
        "Policy switch appeared in identity header");
    check(
        ancestor(node("overview-mode-control"), "overview-readiness"),
        "Policy switch is outside Build readiness");
    check(
        !((Label) node("overview-mode")).getText().contains("%"),
        "Mode summary duplicates the retained percentage");
    String expected =
        List.of("preparing", "corrupt").contains(preview)
            ? "Unknown"
            : preview.equals("full")
                ? "100%"
                : List.of("downloading", "paused").contains(preview) ? "15.71%" : "3.55%";
    check(
        ((Label) node("overview-retained")).getText().equals(expected),
        "Retained percentage does not match supplied complete scope");
    for (Node n : pane.lookupAll(".label"))
      if (n instanceof Label label && !label.getText().isBlank()) {
        check(!label.isTextTruncated(), "C text is truncated: " + label.getText());
        check(
            label.getHeight() >= label.prefHeight(label.getWidth()) - 1,
            "C text lacks preferred wrapped height: " + label.getText());
      }
    check(
        pane.lookupAll(".button").stream()
            .noneMatch(
                n ->
                    n instanceof Button b
                        && List.of(
                                "Explore game data", "Browse files", "Compare builds", "Findings")
                            .contains(b.getText())),
        "C contains excluded research launchers");
    boolean enabled = !node("overview-on-demand").isDisabled();
    if (enabled) node("overview-on-demand").requestFocus();
    else scroll.requestFocus();
    scroll.setVvalue(1);
    later(
        () -> {
          stage.getScene().getRoot().applyCss();
          stage.getScene().getRoot().layout();
          Bounds viewport =
              scroll
                  .lookup(".viewport")
                  .localToScene(scroll.lookup(".viewport").getBoundsInLocal());
          Node control = node("overview-mode-control");
          Bounds bounds = control.localToScene(control.getBoundsInLocal());
          check(
              bounds.getMinY() >= viewport.getMinY() - 1
                  && bounds.getMaxY() <= viewport.getMaxY() + 1,
              "Policy controls cannot be reached by scrolling");
          check(
              bounds.getMinX() >= viewport.getMinX() - 1
                  && bounds.getMaxX() <= viewport.getMaxX() + 1,
              "Policy controls are horizontally clipped");
          if (enabled)
            check(
                stage.getScene().getFocusOwner() == node("overview-on-demand"),
                "Policy choice did not retain keyboard focus");
          scroll.setVvalue(0);
          later(CaptureBuildOverviewPane::finish);
        });
  }

  private static void FlowPaneOrder() {
    var fields = ((javafx.scene.layout.FlowPane) node("overview-identity")).getChildren();
    int locale = fields.indexOf(node("overview-locale").getParent());
    check(
        fields.indexOf(node("overview-status").getParent()) == locale + 1,
        "Status does not follow Locale");
    check(
        fields.indexOf(node("overview-retained").getParent()) == locale + 2,
        "Retained does not follow Status");
    check(
        fields.indexOf(node("overview-mode").getParent()) == locale + 3,
        "Mode does not follow Retained");
    check(!node("overview-status").getStyleClass().contains("badge"), "Status is not plain text");
  }

  private static boolean ancestor(Node node, String id) {
    for (Node p = node.getParent(); p != null; p = p.getParent())
      if (id.equals(p.getId())) return true;
    return false;
  }

  private static void finish() {
    try {
      stage.getScene().getRoot().applyCss();
      stage.getScene().getRoot().layout();
      Bounds viewport =
          scroll.lookup(".viewport").localToScene(scroll.lookup(".viewport").getBoundsInLocal());
      Node header = node("overview-identity");
      Bounds bounds = header.localToScene(header.getBoundsInLocal());
      check(
          bounds.getMinY() >= viewport.getMinY() - 1 && bounds.getMinY() <= viewport.getMaxY(),
          "Capture did not return to identity header");
      writeScene(preview);
      complete = true;
      stage.close();
      Platform.exit();
    } catch (Exception failure) {
      throw new RuntimeException(failure);
    }
  }

  private static void writeScene(String state) throws Exception {
    WritableImage image = stage.getScene().snapshot(null);
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
            .resolve("overview-" + state + "-" + width + "x" + height + "-" + scale + "pct.png")
            .toFile());
  }

  private static Node node(String id) {
    Node value = pane.lookup("#" + id);
    if (value == null) throw new AssertionError("Missing #" + id);
    return value;
  }

  private static void click(String id) {
    ((ButtonBase) node(id)).fire();
  }

  private static void check(boolean condition, String message) {
    assertions++;
    if (!condition) {
      if (stage != null && Platform.isFxApplicationThread()) {
        try {
          writeScene("failure-" + preview);
        } catch (Exception diagnostic) {
          diagnostic.printStackTrace();
        }
      }
      throw new AssertionError(message);
    }
  }

  private static void later(Runnable action) {
    PauseTransition pause = new PauseTransition(Duration.millis(250));
    pause.setOnFinished(e -> action.run());
    pause.play();
  }
}
