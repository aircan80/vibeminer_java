package io.vibeminer.bootstrap;

import io.vibeminer.desktop.*;
import io.vibeminer.infrastructure.buildopening.BuildOpeningPolicy;
import io.vibeminer.infrastructure.inventory.SqliteBuildInventory;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javafx.animation.AnimationTimer;
import javafx.application.*;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.*;
import javax.imageio.ImageIO;

/** Real mining services with authored HTTP; launch seed and restart as separate JVMs. */
public final class BuildMiningReviewApp {
  private static Path output, directory;
  private static int width, height, scale, assertions;
  private static String phase;
  private static Stage stage;
  private static volatile boolean complete;
  private static BuildOpeningReviewData data;

  public static void main(String[] args) throws Exception {
    if (args.length != 14
        || !args[0].equals("--isolated-display")
        || !args[1].equals(System.getenv("DISPLAY"))
        || !args[2].equals("--output")
        || !args[4].equals("--data-dir")
        || !args[6].equals("--phase")
        || !args[8].equals("--width")
        || !args[10].equals("--height")
        || !args[12].equals("--scale"))
      throw new IllegalArgumentException(
          "Usage: BuildMiningReviewApp --isolated-display :N --output DIR --data-dir DIR --phase"
              + " seed|restart --width 1440 --height 1100 --scale 100");
    output = Path.of(args[3]);
    directory = Path.of(args[5]);
    phase = args[7];
    width = Integer.parseInt(args[9]);
    height = Integer.parseInt(args[11]);
    scale = Integer.parseInt(args[13]);
    if (!Set.of("seed", "restart").contains(phase)
        || !((width == 1440 && height == 1100) || (width == 1280 && height == 800))
        || !(scale == 100 || scale == 200))
      throw new IllegalArgumentException("Unsupported review phase or geometry");
    if (phase.equals("seed") && Files.exists(directory)) {
      try (var children = Files.list(directory)) {
        if (children.findAny().isPresent())
          throw new IllegalArgumentException("Seed data directory must be fresh");
      }
    }
    if (phase.equals("restart")
        && !Files.isRegularFile(directory.resolve("mining-review-complete.txt")))
      throw new IllegalArgumentException("Run the successful seed JVM before restart");
    Files.createDirectories(output);
    Files.createDirectories(directory);
    data = new BuildOpeningReviewData();
    if (phase.equals("restart")) data.offline();
    try (var metadata = MetadataRuntime.open(directory, data::newClient, gateway -> gateway);
        var content =
            new LazyBootstrapService(() -> ContentRuntime.open(directory, data::newClient));
        var opening = new LazyBuildOpeningService(content);
        var mining = new LazyBuildServices(content)) {
      AtlasApplication.configure(
          metadata.discovery(),
          metadata.workspaces(),
          () -> {
            throw new AssertionError("Independent product discovery not part of this journey");
          },
          false);
      AtlasApplication.configureBuildLibrary(metadata.library());
      AtlasApplication.configureBuildReadiness(
          metadata.readiness(), BuildOpeningPolicy.SCOPE_REVISION, SqliteBuildInventory.REVISION);
      AtlasApplication.configureBuildOpening(opening);
      AtlasApplication.configureBuildMining(
          mining, mining, mining, BuildMiningRuntime.overviewLimits());
      // The actual saved-intent startup restore is allowed to activate only persisted
      // authorization.
      metadata.resumeAuthorizedDownloads(content).toCompletableFuture().get(10, TimeUnit.SECONDS);
      Thread.setDefaultUncaughtExceptionHandler(
          (thread, failure) -> {
            failure.printStackTrace();
            System.exit(1);
          });
      var timeout =
          new Thread(
              () -> {
                try {
                  Thread.sleep(120000);
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
              "actual-mining-review");
      controller.setDaemon(true);
      controller.start();
      try {
        Application.launch(AtlasApplication.class);
      } finally {
        data.releaseGame();
      }
    }
    if (!complete) throw new AssertionError("Mining journey incomplete");
    System.out.println(
        "Actual mining "
            + phase
            + ": "
            + assertions
            + " assertions; authored HTTP attempts="
            + data.requests()
            + "; game attempts="
            + data.gameObjectRequests()
            + "; "
            + width
            + "x"
            + height
            + " @ "
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
          var marker =
              new Label(
                  "AUTHORED HTTP MINING REVIEW · "
                      + phase.toUpperCase(Locale.ROOT)
                      + " · NO LIVE CDN");
          marker.setId("mining-authored-fixture-marker");
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
            throw new AssertionError("Missing text scale control");
          });
      clickText("Discover builds");
    }
    fx(
        () -> {
          stage.setWidth(stage.getWidth() + width - stage.getScene().getWidth());
          stage.setHeight(stage.getHeight() + height - stage.getScene().getHeight());
          return null;
        });
    check(
        data.requests() == 0,
        "Startup/Settings do not request content or discovery for On demand state");
    chooseProduct("wow");
    until(() -> fx(() -> !list().getItems().isEmpty() && !node("guided-refresh").isDisabled()));
    int productRequests = data.requests();
    int offlineDiscoveryAttempts = data.forbiddenOfflineRequests();
    if (phase.equals("seed")) {
      check(
          productRequests == 2 && offlineDiscoveryAttempts == 0,
          "Deliberate online product selection retrieves exactly one versions/CDNs pair");
    } else {
      check(
          productRequests == 0 && offlineDiscoveryAttempts >= 1 && offlineDiscoveryAttempts <= 2,
          "Deliberate offline product refresh fails at its authored source while preserving local"
              + " rows");
    }
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
    check(data.requests() == productRequests, "Selecting exact saved row performs no acquisition");
    assertChooser(phase.equals("seed") ? "Not ready" : "Fully downloaded", phase.equals("restart"));
    shot(stage, phase + "-selected");
    click("guided-open-build");
    until(
        () ->
            fx(
                () ->
                    stage.getScene().lookup("#overview-refresh-coverage") != null
                        && !node("overview-refresh-coverage").isDisabled()
                        && !node("overview-download-all").isDisabled()));
    if (phase.equals("restart")) {
      assertFull();
      assertOnDemand();
      check(
          data.requests() == productRequests,
          "Offline metadata/Open/inventory/coverage add zero HTTP after honest failed discovery"
              + " pair");
      check(
          data.gameObjectRequests() == 0, "Restart verifies retained game object without network");
      shot(stage, "restart-offline-full");
      check(
          data.forbiddenOfflineRequests() == offlineDiscoveryAttempts,
          "Offline Full baseline contains no hidden HTTP attempts after deliberate discovery"
              + " failure");
      corruptAuthoredGameObject();
      int beforeChooser = data.requests(), beforeForbidden = data.forbiddenOfflineRequests();
      click("overview-back");
      assertChooser("Fully downloaded", true);
      check(
          data.requests() == beforeChooser && data.forbiddenOfflineRequests() == beforeForbidden,
          "Chooser reads dated historical Full without downloading or revalidating changed"
              + " payload");
      shot(stage, "restart-chooser-historical-full-after-corruption");
      openSelectedAndAwaitCoverage();
      assertPartial();
      check(
          fx(
              () ->
                  stage.getScene().getRoot().lookupAll(".overview-problem").stream()
                      .filter(Label.class::isInstance)
                      .map(Label.class::cast)
                      .anyMatch(label -> label.getText().contains("RETAINED_CORRUPT"))),
          "Current verification reports the changed authored game object as corrupt, separately"
              + " from metadata");
      check(
          data.requests() == beforeChooser && data.forbiddenOfflineRequests() == beforeForbidden,
          "Corruption assessment remains local and does not silently repair or redownload payload");
      reveal("overview-identity");
      shot(stage, "restart-current-partial-corrupt");
      click("overview-back");
      assertChooser("Partial", true);
      check(
          data.requests() == beforeChooser && data.forbiddenOfflineRequests() == beforeForbidden,
          "Chooser now shows the newly recorded dated Partial assessment with no HTTP");
      shot(stage, "restart-chooser-partial");
      captureChooserReadiness("Partial", "restart-chooser-partial-readiness");
    } else {
      assertPartial();
      assertOnDemand();
      check(
          data.gameObjectRequests() == 0,
          "Open and local inventory preparation do not download game payload");
      check(
          fx(() -> stage.getScene().getRoot().lookupAll(".overview-problem").isEmpty()),
          "Fresh unretained Partial build is normal, with no alarm");
      shot(stage, "seed-partial");
      data.holdGame();
      click("overview-download-all");
      check(
          data.awaitGame(Duration.ofSeconds(8)),
          "Actual scheduler starts held game payload after explicit Download all");
      awaitRefresh();
      click("overview-refresh-coverage");
      awaitRefresh();
      until(() -> fx(() -> ((ToggleButton) node("overview-download-all")).isSelected()));
      check(
          fx(() -> ((Label) node("overview-status")).getText().equals("Partial")),
          "Pending game transfer cannot become Full");
      check(
          fx(() -> !((Label) node("overview-retained")).getText().equals("100%")),
          "Held acquisition cannot fabricate retained percentage");
      check(data.gameObjectRequests() > 0, "Game source transfer is real authored transport work");
      reveal("overview-download-all");
      fx(
          () -> {
            node("overview-download-all").requestFocus();
            return null;
          });
      check(
          fx(() -> node("overview-download-all").isFocused()),
          "Active mode control accepts keyboard focus");
      check(
          fx(() -> visibleWithinOverview(node("overview-download-all"))),
          "Download mode remains reachable by keyboard/scroll at this scale");
      shot(stage, "seed-downloading");
      // Preserve the policy/focus view above, then expose the exact identity and live header.
      fx(
          () -> {
            node("overview-back").requestFocus();
            return null;
          });
      reveal("overview-identity");
      until(() -> fx(() -> ((Label) node("overview-mode")).getText().contains("1 active")));
      fx(
          () -> {
            var mode = (Label) node("overview-mode");
            var retained = (Label) node("overview-retained");
            check(
                mode.getText().contains("Download all") && mode.getText().contains("1 active"),
                "Held actual game transfer is visible as one active Download all job in the"
                    + " header");
            check(
                retained.getText().equals("0%"),
                "Held game transfer still has measured zero retained scope");
            check(
                !mode.isTextTruncated() && !retained.isTextTruncated(),
                "Active header mode and retained coverage are not truncated");
            check(
                visibleWithinOverview(mode) && visibleWithinOverview(retained),
                "Active mode and retained coverage are visible in the header viewport");
            return null;
          });
      fx(
          () -> {
            assertHeaderGeometry();
            return null;
          });
      shot(stage, "seed-downloading-header");
      data.releaseGame();
      untilFull();
      assertFull();
      reveal("overview-identity");
      shot(stage, "seed-full");
      int retainedRequests = data.requests();
      reveal("overview-mode-control");
      click("overview-on-demand");
      awaitRefresh();
      assertOnDemand();
      assertFull();
      check(
          data.requests() == retainedRequests,
          "Switching to On demand deletes nothing and starts no transfer");
      reveal("overview-identity");
      shot(stage, "seed-full-on-demand");
      int beforeChooser = data.requests(), beforeForbidden = data.forbiddenOfflineRequests();
      click("overview-back");
      assertChooser("Fully downloaded", true);
      check(
          data.requests() == beforeChooser && data.forbiddenOfflineRequests() == beforeForbidden,
          "Returning to chooser overlays historical readiness by SQL only");
      shot(stage, "seed-chooser-full");
      captureChooserReadiness("Fully downloaded", "seed-chooser-full-readiness");
      openSelectedAndAwaitCoverage();
      assertFull();
      assertOnDemand();
      check(
          data.requests() == beforeChooser && data.forbiddenOfflineRequests() == beforeForbidden,
          "Reopening retained Full after chooser adds no HTTP");
      Files.writeString(
          directory.resolve("mining-review-complete.txt"),
          "Successful actual UI Full then On demand; restart must preserve verified bytes.\n");
    }
    complete = true;
    fx(
        () -> {
          stage.close();
          Platform.exit();
          return null;
        });
  }

  private static List<Label> chooserStatusLabels(String readiness) {
    String prefix = "Last checked: " + readiness + " · ";
    Label row =
        list().lookupAll(".guided-row-status").stream()
            .filter(Label.class::isInstance)
            .map(Label.class::cast)
            .filter(label -> label.getText().startsWith("Build status: " + prefix))
            .findFirst()
            .orElseThrow();
    Label inspector =
        node("guided-inspector").lookupAll(".guided-status").stream()
            .filter(Label.class::isInstance)
            .map(Label.class::cast)
            .filter(label -> label.getText().startsWith(prefix))
            .findFirst()
            .orElseThrow();
    return List.of(row, inspector);
  }

  private static boolean chooserStatusVisible(String readiness) {
    var scroll = (ScrollPane) node("guided-discovery-scroll");
    var viewport = scroll.lookup(".viewport");
    Bounds visible = viewport.localToScene(viewport.getBoundsInLocal());
    for (Label label : chooserStatusLabels(readiness)) {
      Bounds bounds = label.localToScene(label.getBoundsInLocal());
      if (bounds.getMinX() < visible.getMinX() - 1
          || bounds.getMaxX() > visible.getMaxX() + 1
          || bounds.getMinY() < visible.getMinY() - 1
          || bounds.getMaxY() > visible.getMaxY() + 1) return false;
    }
    return true;
  }

  private static boolean chooserTargetVisible(Node target) {
    var scroll = (ScrollPane) node("guided-discovery-scroll");
    var viewport = scroll.lookup(".viewport");
    Bounds visible = viewport.localToScene(viewport.getBoundsInLocal());
    Bounds bounds = target.localToScene(target.getBoundsInLocal());
    return bounds.getMinX() >= visible.getMinX() - 1
        && bounds.getMaxX() <= visible.getMaxX() + 1
        && bounds.getMinY() >= visible.getMinY() - 1
        && bounds.getMaxY() <= visible.getMaxY() + 1;
  }

  private static void scrollChooserTarget(Node target) {
    var root = stage.getScene().getRoot();
    var scroll = (ScrollPane) node("guided-discovery-scroll");
    for (int pass = 0; pass < 3; pass++) {
      root.applyCss();
      root.layout();
      var viewport = scroll.lookup(".viewport");
      Bounds visible = viewport.localToScene(viewport.getBoundsInLocal());
      Bounds bounds = target.localToScene(target.getBoundsInLocal());
      double extent = scroll.getContent().getLayoutBounds().getHeight() - visible.getHeight();
      if (extent > 0)
        scroll.setVvalue(
            Math.max(
                scroll.getVmin(),
                Math.min(
                    scroll.getVmax(),
                    scroll.getVvalue()
                        + (bounds.getMinY() - visible.getMinY() - 18)
                            / extent
                            * (scroll.getVmax() - scroll.getVmin()))));
    }
  }

  private static void captureChooserReadiness(String readiness, String state) throws Exception {
    if (scale == 200) {
      // At large text the approved readable layout stacks the inspector below the catalog.
      // Verify actual scrolling to each status independently instead of requiring two distant
      // sections to fit one viewport. Neither an offscreen lookup nor a tooltip is evidence here.
      for (int index = 0; index < 2; index++) {
        int selected = index;
        fx(
            () -> {
              scrollChooserTarget(chooserStatusLabels(readiness).get(selected));
              return null;
            });
        until(() -> fx(() -> chooserTargetVisible(chooserStatusLabels(readiness).get(selected))));
        fx(
            () -> {
              var label = chooserStatusLabels(readiness).get(selected);
              check(
                  label.getText().contains("UTC") && label.getText().endsWith(" · enUS"),
                  "Visible stacked chooser assessment includes its actual dated locale");
              check(!label.isTextTruncated(), "Stacked dated readiness is completely readable");
              check(chooserTargetVisible(label), "Stacked readiness is inside the real viewport");
              return null;
            });
        shotChooserTarget(
            state + (index == 0 ? "-row" : "-inspector"),
            () -> chooserStatusLabels(readiness).get(selected),
            () -> {
              var label = chooserStatusLabels(readiness).get(selected);
              check(!label.isTextTruncated(), "Snapshot readiness is fully readable");
              check(
                  label.getText().contains("UTC") && label.getText().endsWith(" · enUS"),
                  "Snapshot contains the intended dated readiness");
              if (selected == 0) assertCardBadges();
            });
      }
      fx(
          () -> {
            scrollChooserTarget(node("guided-open-build"));
            return null;
          });
      until(() -> fx(() -> chooserTargetVisible(node("guided-open-build"))));
      fx(
          () -> {
            check(
                chooserTargetVisible(node("guided-open-build")),
                "Stacked primary Open action is reachable");
            check(
                !node("guided-open-build").isDisabled(), "Stacked retained build remains openable");
            return null;
          });
      shotChooserTarget(
          state + "-action",
          () -> node("guided-open-build"),
          () ->
              check(
                  !node("guided-open-build").isDisabled(),
                  "Snapshot contains the enabled primary Open action"));
      return;
    }
    fx(
        () -> {
          list().scrollTo(list().getSelectionModel().getSelectedIndex());
          var root = stage.getScene().getRoot();
          root.applyCss();
          root.layout();
          var scroll = (ScrollPane) node("guided-discovery-scroll");
          for (int pass = 0; pass < 3; pass++) {
            root.layout();
            Bounds viewport =
                scroll
                    .lookup(".viewport")
                    .localToScene(scroll.lookup(".viewport").getBoundsInLocal());
            double top =
                chooserStatusLabels(readiness).stream()
                    .mapToDouble(label -> label.localToScene(label.getBoundsInLocal()).getMinY())
                    .min()
                    .orElseThrow();
            double extent =
                scroll.getContent().getLayoutBounds().getHeight() - viewport.getHeight();
            if (extent > 0)
              scroll.setVvalue(
                  Math.max(
                      scroll.getVmin(),
                      Math.min(
                          scroll.getVmax(),
                          scroll.getVvalue()
                              + (top - viewport.getMinY() - 18)
                                  / extent
                                  * (scroll.getVmax() - scroll.getVmin()))));
          }
          return null;
        });
    until(() -> fx(() -> chooserStatusVisible(readiness)));
    fx(
        () -> {
          for (Label label : chooserStatusLabels(readiness)) {
            check(
                label.getText().contains("UTC") && label.getText().endsWith(" · enUS"),
                "Visible chooser assessment includes its actual dated locale");
            check(
                !label.isTextTruncated(), "Visible row and inspector readiness are fully readable");
          }
          check(
              chooserStatusVisible(readiness),
              "Both dated row and inspector readiness are inside the actual viewport");
          return null;
        });
    shot(
        stage,
        state,
        () -> {
          check(
              chooserStatusVisible(readiness),
              "Snapshot contains both intended dated row and inspector assessments");
          assertCardBadges();
          for (var label : chooserStatusLabels(readiness))
            check(!label.isTextTruncated(), "Snapshot dated readiness is fully readable");
        });
  }

  private static void shotChooserTarget(String state, Supplier<Node> target, Runnable verification)
      throws Exception {
    // Resolve again after the list's layout/virtualization, then wait for actual rendered frames.
    fx(
        () -> {
          scrollChooserTarget(target.get());
          return null;
        });
    shot(
        stage,
        state,
        () -> {
          check(
              chooserTargetVisible(target.get()),
              "Snapshot target is inside the actual chooser viewport: " + state);
          verification.run();
        });
  }

  private static void assertCardBadges() {
    var selected = list().getSelectionModel().getSelectedItem();
    check(selected != null, "Actual selected build supplies card membership badges");
    var cell =
        list().lookupAll(".list-cell").stream()
            .filter(node -> node instanceof ListCell<?> value && selected.equals(value.getItem()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Selected build cell is not materialized"));
    var card = cell.lookup(".guided-build-card");
    check(card != null, "Actual selected build card is materialized");
    Bounds bounds = card.localToScene(card.getBoundsInLocal());
    var expected =
        Map.of(
            "guided-current",
            selected.currentlyAdvertised() ? 1 : 0,
            "guided-local",
            selected.local() ? 1 : 0,
            "guided-new",
            selected.newToVibeMiner() ? 1 : 0,
            "guided-region",
            selected.regions().size());
    for (var entry : expected.entrySet()) {
      var badges = card.lookupAll("." + entry.getKey());
      check(
          badges.size() == entry.getValue(),
          "Rendered badges match actual membership: " + entry.getKey());
      for (var badge : badges) {
        var label = (Label) badge;
        Bounds actual = label.localToScene(label.getBoundsInLocal());
        check(!label.isTextTruncated(), "Card badge is not truncated: " + label.getText());
        check(
            actual.getMinX() >= bounds.getMinX() - 1 && actual.getMaxX() <= bounds.getMaxX() + 1,
            "Card badge stays within tile horizontal bounds: " + label.getText());
      }
    }
  }

  private static void openSelectedAndAwaitCoverage() throws Exception {
    until(
        () ->
            fx(
                () ->
                    stage.getScene().lookup("#guided-open-build") != null
                        && !node("guided-open-build").isDisabled()));
    click("guided-open-build");
    awaitRefresh();
  }

  private static void assertChooser(String readiness, boolean dated) throws Exception {
    String prefix = dated ? "Last checked: " + readiness + " · " : readiness;
    until(
        () ->
            fx(
                () ->
                    !list().getItems().isEmpty()
                        && list().getItems().getFirst().status().startsWith(prefix)));
    fx(
        () -> {
          var selected = list().getSelectionModel().getSelectedItem();
          check(
              selected != null && selected.status().startsWith(prefix),
              "Exact selected row receives the supplied readiness overlay");
          if (dated) {
            check(
                selected.status().contains("UTC") && selected.status().endsWith(" · enUS"),
                "Historical readiness includes assessment time and exact research locale");
            check(
                selected.details().stream()
                    .anyMatch(
                        detail ->
                            detail.label().equals("READINESS NOTE")
                                && detail.value().contains("Files may have changed")),
                "Historical readiness explicitly distinguishes a saved assessment from current"
                    + " files");
          } else
            check(
                selected.status().equals("Not ready"),
                "Absent complete metadata admission is Not ready, not estimated Partial");
          stage.getScene().getRoot().applyCss();
          stage.getScene().getRoot().layout();
          var statusLabels =
              new HashSet<Node>(stage.getScene().getRoot().lookupAll(".guided-status"));
          statusLabels.addAll(stage.getScene().getRoot().lookupAll(".guided-row-status"));
          check(!statusLabels.isEmpty(), "Chooser renders its actual supplied status labels");
          for (Node status : statusLabels)
            if (status instanceof Label label) {
              check(
                  !label.isTextTruncated(),
                  "Dated chooser readiness text is readable without ellipsis");
              check(
                  label.getHeight() + 1 >= label.prefHeight(label.getWidth()),
                  "Dated chooser readiness reserves its complete wrapped height");
              Bounds text = label.localToScene(label.getBoundsInLocal());
              Bounds container =
                  label.getParent().localToScene(label.getParent().getBoundsInLocal());
              check(
                  text.getMinY() >= container.getMinY() - 1
                      && text.getMaxY() <= container.getMaxY() + 1,
                  "Dated readiness remains contained within its actual row or inspector");
            }
          assertCardBadges();
          return null;
        });
  }

  private static void corruptAuthoredGameObject() throws Exception {
    String digest = data.gameObjectDigest();
    Path object =
        directory
            .toAbsolutePath()
            .normalize()
            .resolve("content/objects")
            .resolve(digest.substring(0, 2))
            .resolve(digest.substring(2));
    check(
        Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS),
        "Only the known authored game CAS file is selected for corruption review");
    check(
        object.toRealPath().startsWith(directory.toRealPath()),
        "Corruption remains inside this review's app-owned directory");
    byte[] bytes = Files.readAllBytes(object);
    check(
        bytes.length > 0 && bytes.length < 1024,
        "Authored game payload has the expected bounded review size");
    check(
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            .equals(digest),
        "Before mutation the exact authored game object has its expected SHA-256");
    bytes[bytes.length - 1] ^= 1;
    Files.write(
        object,
        bytes,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
        LinkOption.NOFOLLOW_LINKS);
  }

  private static void assertHeaderGeometry() {
    var header = (javafx.scene.layout.FlowPane) node("overview-identity");
    var labels = new ArrayList<Node>();
    for (Node fieldNode : header.getChildren()) {
      var field = (VBox) fieldNode;
      Bounds parent = field.localToScene(field.getBoundsInLocal());
      for (Node child : field.getChildren()) {
        Bounds label = child.localToScene(child.getBoundsInLocal());
        check(
            label.getMinX() >= parent.getMinX() - 1
                && label.getMaxX() <= parent.getMaxX() + 1
                && label.getMinY() >= parent.getMinY() - 1
                && label.getMaxY() <= parent.getMaxY() + 1,
            "Header label remains within its own identity field");
        for (Node previous : labels) {
          Bounds other = previous.localToScene(previous.getBoundsInLocal());
          double overlapX =
              Math.min(label.getMaxX(), other.getMaxX())
                  - Math.max(label.getMinX(), other.getMinX());
          double overlapY =
              Math.min(label.getMaxY(), other.getMaxY())
                  - Math.max(label.getMinY(), other.getMinY());
          check(
              overlapX <= 1 || overlapY <= 1,
              "Header labels do not paint over another identity field");
        }
      }
      labels.addAll(field.getChildren());
    }
  }

  private static void awaitRefresh() throws Exception {
    until(
        () ->
            fx(
                () ->
                    stage.getScene().lookup("#overview-refresh-coverage") != null
                        && !node("overview-refresh-coverage").isDisabled()));
  }

  private static void untilFull() throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (!fx(
        () ->
            stage.getScene().lookup("#overview-status") instanceof Label status
                && status.getText().equals("Fully downloaded"))) {
      if (System.nanoTime() > end)
        throw new AssertionError("Actual acquisition/verification failed to reach Full");
      Thread.sleep(40);
    }
  }

  private static void assertPartial() throws Exception {
    check(
        fx(() -> ((Label) node("overview-status")).getText().equals("Partial")),
        "Actual complete metadata/inventory with absent game member is Partial");
    check(
        fx(() -> ((Label) node("overview-retained")).getText().equals("0%")),
        "Authored one-member initial inventory has measured zero retained coverage");
  }

  private static void assertFull() throws Exception {
    check(
        fx(() -> ((Label) node("overview-status")).getText().equals("Fully downloaded")),
        "Only actual verified scoped closure produces Full");
    check(
        fx(() -> ((Label) node("overview-retained")).getText().equals("100%")),
        "Full retains exactly100% encoded scope");
    check(
        fx(() -> stage.getScene().lookup("#overview-retry-preparation") == null),
        "Successful actual inventory has no retry-preparation error");
  }

  private static void assertOnDemand() throws Exception {
    check(
        fx(() -> ((ToggleButton) node("overview-on-demand")).isSelected()),
        "On demand is actual persisted/default preference");
  }

  private static void reveal(String id) throws Exception {
    fx(
        () -> {
          var scroll = (ScrollPane) node("build-overview-scroll");
          stage.getScene().getRoot().applyCss();
          stage.getScene().getRoot().layout();
          var target = node(id);
          target.requestFocus();
          var viewport = scroll.lookup(".viewport");
          Bounds t = target.localToScene(target.getBoundsInLocal()),
              v = viewport.localToScene(viewport.getBoundsInLocal());
          double extent = scroll.getContent().getBoundsInLocal().getHeight() - v.getHeight();
          if (extent > 0) {
            double delta = t.getMinY() - v.getMinY() - 16;
            scroll.setVvalue(Math.max(0, Math.min(1, scroll.getVvalue() + delta / extent)));
          }
          return null;
        });
    fx(() -> null);
  }

  private static boolean visibleWithinOverview(Node target) {
    var scroll = (ScrollPane) node("build-overview-scroll");
    var viewport = scroll.lookup(".viewport");
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
    shot(window, state, () -> {});
  }

  private static void awaitRenderedPulses() throws Exception {
    if (Platform.isFxApplicationThread())
      throw new IllegalStateException("Rendered-frame wait must stay outside the FX thread");
    var rendered = new CountDownLatch(1);
    var timer =
        fx(
            () -> {
              var animation =
                  new AnimationTimer() {
                    int pulses;

                    @Override
                    public void handle(long now) {
                      // A third pulse guarantees the two preceding layout/render cycles had time to
                      // finish.
                      if (++pulses >= 3) {
                        stop();
                        rendered.countDown();
                      } else Platform.requestNextPulse();
                    }
                  };
              animation.start();
              Platform.requestNextPulse();
              return animation;
            });
    try {
      if (!rendered.await(5, TimeUnit.SECONDS))
        throw new AssertionError("JavaFX did not render three capture pulses");
    } finally {
      fx(
          () -> {
            timer.stop();
            return null;
          });
    }
  }

  private static void shot(Window window, String state, Runnable verification) throws Exception {
    awaitRenderedPulses();
    fx(
        () -> {
          window.getScene().getRoot().applyCss();
          window.getScene().getRoot().layout();
          verification.run();
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
                      "atlas-mining-"
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
        throw new AssertionError("Timed out waiting for actual mining UI state");
      Thread.sleep(25);
    }
  }
}
