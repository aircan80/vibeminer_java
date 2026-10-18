import io.vibeminer.application.*;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import io.vibeminer.infrastructure.lookup.MetadataReuseFixtureBackend;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.*;

/**
 * Native retained metadata reuse: authored acquisition bytes, actual proofs/services/CAS/SQLite.
 */
public final class CaptureMetadataReuseWorkflow {
  private static MetadataReuseFixtureBackend fixture;
  private static Stage stage;
  private static int assertions;
  private static volatile boolean finished;
  private static boolean restart;

  public static void main(String[] args) throws Exception {
    if (args.length == 4 && args[0].equals("--isolated-display") && args[2].equals("--output")) {
      requireDisplay(args[1]);
      Path output = Path.of(args[3]).toAbsolutePath();
      Files.createDirectories(output);
      Path temp = Files.createTempDirectory("vibeminer-reuse-ui-");
      Process active = null;
      int total = 0;
      try {
        for (String phase : List.of("prepare", "restart")) {
          active =
              new ProcessBuilder(
                      Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                      "--enable-native-access=ALL-UNNAMED",
                      "-cp",
                      System.getProperty("java.class.path"),
                      CaptureMetadataReuseWorkflow.class.getName(),
                      "--worker",
                      phase,
                      args[1],
                      output.toString(),
                      temp.toString())
                  .inheritIO()
                  .start();
          if (!active.waitFor(150, TimeUnit.SECONDS))
            throw new IllegalStateException("Metadata reuse worker deadline: " + phase);
          require(active.exitValue() == 0, "Metadata reuse worker failed: " + phase);
          total += Integer.parseInt(Files.readString(temp.resolve(phase + ".assertions")).strip());
        }
        System.out.println(
            "Metadata reuse UI OK; "
                + total
                + " assertions; two JVMs; actual acquisition proofs/services/CAS/SQLite; authored"
                + " bytes; zero HTTP");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw e;
      } finally {
        if (retire(active)) {
          try (var paths = Files.walk(temp)) {
            for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
          }
        } else
          System.err.println("Worker termination unconfirmed; preserving fixture store: " + temp);
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
        (t, e) -> {
          e.printStackTrace();
          System.exit(1);
        });
    try (var backend = new MetadataReuseFixtureBackend(temp.resolve("data"), !restart)) {
      fixture = backend;
      AtlasApplication.configure(
          new DiscoveryService() {
            public List<BuildSnapshot> fixtureBuilds() {
              return fixture.builds();
            }

            public CompletionStage<List<BuildSnapshot>> refresh(String p, String r) {
              throw new AssertionError("No discovery in local reuse workflow");
            }
          },
          fixture.workspaces(),
          () -> {
            throw new AssertionError("No productrefresh");
          },
          fixture.install(),
          fixture.root(),
          null,
          null,
          fixture.reuse(),
          true);
      Thread deadline =
          new Thread(
              () -> {
                try {
                  Thread.sleep(120000);
                } catch (InterruptedException e) {
                  return;
                }
                if (!finished) {
                  System.err.println("Metadata reuse UI deadline");
                  System.exit(2);
                }
              });
      deadline.setDaemon(true);
      deadline.start();
      Thread controller =
          new Thread(
              () -> {
                try {
                  until(
                      () -> {
                        try {
                          stage =
                              fx(
                                  () ->
                                      Window.getWindows().stream()
                                          .filter(w -> w instanceof Stage && w.isShowing())
                                          .map(w -> (Stage) w)
                                          .findFirst()
                                          .orElse(null));
                          return stage != null;
                        } catch (Exception e) {
                          return false;
                        }
                      });
                  fx(
                      () -> {
                        stage.setX(0);
                        stage.setY(0);
                        stage.setWidth(1280);
                        stage.setHeight(800);
                        return null;
                      });
                  until(() -> pickerCount() == 3);
                  check(
                      fixture.checks.get()
                              + fixture.applies.get()
                              + fixture.rootReads.get()
                              + fixture.installReads.get()
                          == 0,
                      "Startup performed metadata operations");
                  workspace(0, "enUS");
                  if (restart) restartJourney();
                  else firstJourney();
                  check(
                      fixture.forbiddenTransfers.get() == 0,
                      "A local-only path attempted transport");
                  Files.writeString(
                      temp.resolve(args[1] + ".assertions"), Integer.toString(assertions));
                  finished = true;
                  System.out.println(
                      "Metadata reuse " + args[1] + " phase OK: " + assertions + " assertions");
                  Platform.exit();
                } catch (Throwable e) {
                  e.printStackTrace();
                  System.exit(1);
                }
              });
      controller.setDaemon(true);
      controller.start();
      Application.launch(AtlasApplication.class);
      require(finished, "Metadata reuse UI did not finish");
    }
  }

  private static void firstJourney() throws Exception {
    long objects = fixture.objects(), aliases = fixture.count("source_aliases");
    rootView();
    check(
        fixture.checks.get() == 0 && fixture.rootReads.get() == 0,
        "Opening root view did local reuse/query implicitly");
    shot("reuse-root-unchecked-1280x800.png", "root-reuse-check");
    fixture.holdCheck.set(true);
    fixture.checkEntered.set(false);
    click("root-reuse-check");
    until(() -> fixture.checkEntered.get());
    waitNode("root-reuse-cancel");
    check(
        fx(() -> stage.getScene().getFocusOwner() == node("root-reuse-cancel")),
        "Check completion lost cancel focus");
    shot("reuse-root-checking-1280x800.png", "root-reuse-cancel");
    click("root-reuse-cancel");
    fixture.holdCheck.set(false);
    waitNode("root-reuse-check");
    check(optional("root-reuse-apply") == null, "Cancelled local check exposed consent");
    check(fixture.applies.get() == 0, "Cancel applied association");
    // Cancelling an in-flight check on context/locale transition clears its local consent.
    // This wrapper may stop the downstream call; uncancellable late completion is unit-tested.
    fixture.holdCheck.set(true);
    fixture.checkEntered.set(false);
    click("root-reuse-check");
    until(() -> fixture.checkEntered.get());
    workspace(1, "enUS");
    fixture.holdCheck.set(false);
    pause();
    rootView();
    check(optional("root-reuse-apply") == null, "Old build check painted a new-context proposal");
    workspace(0, "deDE");
    rootView();
    check(optional("root-reuse-apply") == null, "Locale switch retained prior consent");
    workspace(0, "enUS");
    rootView();
    checkRootPlan();
    check(
        fixture.rootReads.get() == 0 && fixture.applies.get() == 0,
        "Proposal opened/read/applied metadata implicitly");
    assertPlan("root-reuse", true);
    shot("reuse-root-plan-1280x800.png", "root-reuse-consent");
    scale("200%", true);
    assertPlan("root-reuse", true);
    shot("reuse-root-plan-200pct-1280x800.png", "root-reuse-consent");
    scale("100%", true);
    click("root-reuse-apply");
    waitNode("root-reuse-open");
    check(fixture.rootReads.get() == 0, "Apply automatically queried root");
    check(
        fx(() -> stage.getScene().getFocusOwner() == node("root-reuse-open")),
        "Apply did not hand focus to explicit open");
    assertReceipt(Kind.ROOT);
    shot("reuse-root-associated-1280x800.png", "root-reuse-open");
    proof(Kind.ROOT, "reuse-root-original-proof-1280x800.png");
    click("root-reuse-open");
    waitNode("file-variant-table");
    check(
        fixture.rootReads.get() == 1 && tableSize("file-variant-table") == 2,
        "Explicit root open did not return both actual variants");
    shot("reuse-root-offline-variants-1280x800.png", "file-variant-table");
    installView();
    check(fixture.installReads.get() == 0, "Install navigation opened metadata automatically");
    click("install-reuse-check");
    waitNode("install-reuse-apply");
    assertPlan("install-reuse", false);
    shot("reuse-install-plan-1280x800.png", "install-reuse-consent");
    scale("200%", false);
    assertPlan("install-reuse", false);
    shot("reuse-install-plan-200pct-1280x800.png", "install-reuse-consent");
    scale("100%", false);
    click("install-reuse-apply");
    waitNode("install-reuse-open");
    check(fixture.installReads.get() == 0, "Install apply automatically opened entries");
    assertReceipt(Kind.INSTALL);
    shot("reuse-install-associated-1280x800.png", "install-reuse-open");
    proof(Kind.INSTALL, "reuse-install-original-proof-1280x800.png");
    click("install-reuse-open");
    waitNode("install-entry-table");
    check(
        tableSize("install-entry-table") == 2,
        "Associated install reopen did not inspect original A entries");
    shot("reuse-install-offline-entries-1280x800.png", "install-entry-table");
    check(fixture.objects() == objects, "Reuse duplicated physical objects");
    check(fixture.count("source_aliases") == aliases, "Reuse invented an additional source alias");
    check(!fixture.hasPreferredInstallAlias(), "Reuse invented preferred B acquisition evidence");
    check(
        fixture.count("retention_scopes") == 2 && fixture.pinnedScopes() == 2,
        "ROOT/INSTALL retention memberships/pins missing");
    // Expiration invalidates consent without changing original association or performing a read.
    rootView();
    checkRootPlan();
    fixture.monotonic.addAndGet(TimeUnit.HOURS.toNanos(1));
    click("root-reuse-apply");
    until(() -> optional("root-reuse-apply") == null && optional("root-reuse-check") != null);
    check(optional("root-reuse-open") == null, "Expired proposal remained current");
    shot("reuse-expired-plan-1280x800.png", "root-reuse-status");
  }

  private static void restartJourney() throws Exception {
    long objects = fixture.objects(), aliases = fixture.count("source_aliases");
    check(fixture.seedTransfers.get() == 0, "Restart seeded/acquired content");
    rootView();
    checkRootPlan();
    click("root-reuse-apply");
    waitNode("root-reuse-open");
    check(fixture.rootReads.get() == 0, "Restart reuse autoqueried root");
    click("root-reuse-open");
    waitNode("file-variant-table");
    check(tableSize("file-variant-table") == 2, "Restart actual root variants unavailable");
    assertReceipt(Kind.ROOT);
    shot("reuse-restart-root-1280x800.png", "file-variant-table");
    installView();
    click("install-reuse-check");
    waitNode("install-reuse-apply");
    click("install-reuse-apply");
    waitNode("install-reuse-open");
    click("install-reuse-open");
    waitNode("install-entry-table");
    check(tableSize("install-entry-table") == 2, "Restart associated install unavailable");
    assertReceipt(Kind.INSTALL);
    shot("reuse-restart-install-1280x800.png", "install-entry-table");
    check(
        fixture.objects() == objects && fixture.count("source_aliases") == aliases,
        "Restart reuse duplicated objects/aliases");
    // Actual CAS byte corruption must cross the gateway boundary as a typed integrity outcome.
    Path installPath = fixture.actualPath(Kind.INSTALL);
    byte[] installBytes = Files.readAllBytes(installPath), bad = installBytes.clone();
    bad[bad.length - 1] ^= 1;
    Files.write(installPath, bad);
    click("install-reuse-check");
    waitNode("install-reuse-apply");
    click("install-reuse-apply");
    until(() -> optional("install-reuse-apply") == null && optional("install-reuse-open") == null);
    pause();
    check(optional("install-entry-table") == null, "Corrupt INSTALL reuse left current entries");
    check(
        labels().toLowerCase(Locale.ROOT).contains("verif"),
        "Corrupt INSTALL failure lost actionable verification copy");
    check(
        labels().contains("Root map verified"),
        "INSTALL failure revoked independent ROOT coverage");
    scale("200%", false);
    shot("reuse-install-corrupt-200pct-1280x800.png", "install-reuse-status");
    Files.write(installPath, installBytes);
    rootView();
    checkRootPlan();
    Path rootPath = fixture.actualPath(Kind.ROOT);
    byte[] rootBytes = Files.readAllBytes(rootPath);
    Files.delete(rootPath);
    click("root-reuse-apply");
    until(() -> optional("root-reuse-apply") == null && optional("root-reuse-open") == null);
    pause();
    check(optional("file-variant-table") == null, "Missing ROOT reuse left current variants");
    check(!Files.exists(rootPath), "Missing ROOT was repaired implicitly");
    check(
        !labels().contains("Root map verified")
            && labels().toLowerCase(Locale.ROOT).contains("missing"),
        "Missing ROOT retained current coverage or lost typed missing outcome");
    shot("reuse-root-missing-200pct-1280x800.png", "root-reuse-status");
    Files.write(rootPath, rootBytes);
    check(
        fixture.forbiddenTransfers.get() == 0, "Missing/corrupt local replay performed transport");
  }

  private static void assertPlan(String prefix, boolean unknown) throws Exception {
    String text = labels();
    check(
        text.contains("Local verification required") && text.contains("0 bytes"),
        "Local proposal lost zero-payload/nonready statement");
    check(
        text.contains("Actual retained representation") && text.contains("Declared encoding:"),
        "Declared and actual representations are not separate");
    if (unknown)
      check(
          text.contains("Not declared") && text.contains("Unknown"),
          "Unknown ROOT declarations were guessed");
    fx(
        () -> {
          reveal(node(prefix + "-consent"));
          return null;
        });
    pause();
    check(
        fx(() -> inside(node(prefix + "-apply")) && inside(node(prefix + "-consent"))),
        "Zero-payload consent and explicit verify/use action not visible together");
    check(optional(prefix + "-open") == null, "Unverified proposal offered current offline open");
  }

  private static void proof(Kind kind, String image) throws Exception {
    var association = fixture.association(kind);
    fx(
        () -> {
          stage.getScene().getRoot().lookupAll(".titled-pane").stream()
              .filter(
                  n ->
                      n instanceof TitledPane t
                          && t.getText()
                              .equals("Declaration, original receipt and verification scope"))
              .map(n -> (TitledPane) n)
              .findFirst()
              .orElseThrow()
              .setExpanded(true);
          return null;
        });
    pause();
    var area =
        fx(
            () ->
                stage.getScene().getRoot().lookupAll(".text-area").stream()
                    .filter(
                        n ->
                            n instanceof TextArea t
                                && t.getText().contains("Original actual source:"))
                    .map(n -> (TextArea) n)
                    .findFirst()
                    .orElseThrow());
    check(
        fx(
            () ->
                area.getText()
                        .contains(
                            association
                                .actualRepresentation()
                                .originalAlias()
                                .evidence()
                                .sourceUrl())
                    && area.getText()
                        .contains(association.actualRepresentation().object().digest().value())
                    && area.getText()
                        .contains(
                            association
                                .actualRepresentation()
                                .originalAlias()
                                .evidence()
                                .verifiedAt()
                                .toString())),
        "Original receipt URL/hash/time not copyable separately from declaration");
    fx(
        () -> {
          reveal(area);
          return null;
        });
    pause();
    fx(
        () -> {
          reveal(area);
          CaptureAtlas.capture(stage, image, 1280, 800);
          return null;
        });
  }

  private static void assertReceipt(Kind kind) throws Exception {
    var a = fixture.association(kind);
    check(
        a.actualRepresentation()
            .originalAlias()
            .evidence()
            .verifiedAt()
            .equals(io.vibeminer.infrastructure.bootstrap.MetadataReuseInstallSeed.NOW),
        "Reuse refreshed original acquisition timestamp");
    if (kind == Kind.INSTALL)
      check(
          !a.declaration()
              .preferredEncodingKey()
              .orElseThrow()
              .equals(a.actualRepresentation().actualEncodingKey()),
          "INSTALL B declaration changed to actual A");
    else
      check(
          a.declaration().preferredEncodingKey().isEmpty()
              && a.declaration().declaredEncodedBytes().isEmpty(),
          "ROOT unknown declaration was fabricated");
  }

  private static void checkRootPlan() throws Exception {
    click("root-reuse-check");
    waitNode("root-reuse-apply");
  }

  private static void rootView() throws Exception {
    fx(
        () -> {
          CaptureAtlas.navigate(stage, "Archive");
          ((Button) node("open-file-lookup")).fire();
          ((TextField) node("file-id-input")).setText("42");
          return null;
        });
    pause();
  }

  private static void installView() throws Exception {
    fx(
        () -> {
          ((Button) node("open-install-files")).fire();
          return null;
        });
    pause();
  }

  private static void scale(String value, boolean root) throws Exception {
    fx(
        () -> {
          CaptureAtlas.button(stage, "Settings").fire();
          CaptureAtlas.setCombo(CaptureAtlas.accessible(stage, "Application text scale"), value);
          return null;
        });
    if (root) rootView();
    else installView();
    pause();
  }

  private static void workspace(int build, String locale) throws Exception {
    fx(
        () -> {
          var w =
              picker().getItems().stream()
                  .filter(
                      v ->
                          v.build().snapshotId().equals(fixture.builds().get(build).snapshotId())
                              && v.locale().equals(locale))
                  .findFirst()
                  .orElseThrow();
          picker().setValue(w);
          return null;
        });
    pause();
  }

  private static int pickerCount() {
    try {
      return fx(() -> picker().getItems().size());
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
  }

  @SuppressWarnings("unchecked")
  private static ComboBox<Workspace> picker() {
    return (ComboBox<Workspace>) node("saved-workspace-picker");
  }

  private static int tableSize(String id) throws Exception {
    return fx(() -> ((TableView<?>) node(id)).getItems().size());
  }

  private static void click(String id) throws Exception {
    fx(
        () -> {
          Button b = (Button) node(id);
          b.requestFocus();
          b.fire();
          return null;
        });
    pause();
  }

  private static void shot(String name, String id) throws Exception {
    fx(
        () -> {
          reveal(node(id));
          return null;
        });
    pause();
    fx(
        () -> {
          reveal(node(id));
          CaptureAtlas.capture(stage, name, 1280, 800);
          return null;
        });
  }

  private static boolean inside(Node n) {
    Bounds b = n.localToScene(n.getBoundsInLocal());
    Node viewport = stage.getScene().getRoot().lookup(".workspace-scroll .viewport");
    Bounds v = viewport.localToScene(viewport.getBoundsInLocal());
    return b.getMinY() >= v.getMinY() - 1 && b.getMaxY() <= v.getMaxY() + 1;
  }

  private static void reveal(Node target) {
    layout();
    ScrollPane scroll = (ScrollPane) stage.getScene().getRoot().lookup(".workspace-scroll");
    Bounds c = scroll.getContent().localToScene(scroll.getContent().getBoundsInLocal()),
        b = target.localToScene(target.getBoundsInLocal());
    double range =
        scroll.getContent().getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
    scroll.setVvalue(
        range <= 0 ? 0 : Math.max(0, Math.min(1, (b.getMinY() - c.getMinY()) / range)));
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
        return fx(CaptureMetadataReuseWorkflow::labels);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    layout();
    return stage.getScene().getRoot().lookupAll(".label").stream()
        .filter(n -> n instanceof Label)
        .map(n -> ((Label) n).getText())
        .reduce("", (a, b) -> a + "\n" + b);
  }

  private static void waitNode(String id) throws Exception {
    until(() -> optional(id) != null);
    pause();
  }

  private static void until(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) throw new AssertionError("UI condition timed out");
      Thread.sleep(50);
    }
  }

  private static void pause() throws Exception {
    Thread.sleep(180);
    fx(
        () -> {
          layout();
          return null;
        });
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    if (Platform.isFxApplicationThread()) return action.call();
    var f = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            f.complete(action.call());
          } catch (Throwable e) {
            f.completeExceptionally(e);
          }
        });
    return f.get(10, TimeUnit.SECONDS);
  }

  private static void check(boolean condition, String message) {
    assertions++;
    require(condition, message);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void requireDisplay(String display) {
    require(
        display.matches(":[1-9][0-9]*") && display.equals(System.getenv("DISPLAY")),
        "Use matching explicitly isolated DISPLAY :N, never the user desktop");
  }

  private static boolean retire(Process process) {
    if (process == null || !process.isAlive()) return true;
    boolean interrupted = Thread.interrupted();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try {
      process.destroyForcibly();
      while (process.isAlive()) {
        long left = end - System.nanoTime();
        if (left <= 0) return false;
        try {
          if (process.waitFor(left, TimeUnit.NANOSECONDS)) return !process.isAlive();
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
