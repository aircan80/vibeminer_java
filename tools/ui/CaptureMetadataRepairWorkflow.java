import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import io.vibeminer.infrastructure.lookup.MetadataRepairFixtureBackend;
import java.nio.file.*;
import java.time.Instant;
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
 * Native retained metadata repair: authored acquisition bytes, actual proofs/services/CAS/SQLite.
 */
public final class CaptureMetadataRepairWorkflow {
  private static MetadataRepairFixtureBackend fixture;
  private static Stage stage;
  private static int assertions;
  private static volatile boolean finished;
  private static boolean restart;
  private static boolean probeMode;
  private static int consentSample;
  private static volatile MetadataRepairPlan lastPlan;
  private static volatile MetadataRepairResult lastResult;
  private static final List<UUID> auditIds = new ArrayList<>();

  public static void main(String[] incoming) throws Exception {
    boolean probe = incoming.length == 5 && incoming[0].equals("--probe-install-consent");
    final String[] args = probe ? Arrays.copyOfRange(incoming, 1, incoming.length) : incoming;
    if (args.length == 4 && args[0].equals("--isolated-display") && args[2].equals("--output")) {
      requireDisplay(args[1]);
      Path output = Path.of(args[3]).toAbsolutePath();
      Files.createDirectories(output);
      Path temp = Files.createTempDirectory("vibeminer-repair-ui-");
      Process active = null;
      int total = 0;
      try {
        for (String phase : probe ? List.of("probe") : List.of("prepare", "restart")) {
          active =
              new ProcessBuilder(
                      Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                      "--enable-native-access=ALL-UNNAMED",
                      "-cp",
                      System.getProperty("java.class.path"),
                      CaptureMetadataRepairWorkflow.class.getName(),
                      "--worker",
                      phase,
                      args[1],
                      output.toString(),
                      temp.toString())
                  .inheritIO()
                  .start();
          if (!active.waitFor(150, TimeUnit.SECONDS))
            throw new IllegalStateException("Metadata repair worker deadline: " + phase);
          require(active.exitValue() == 0, "Metadata repair worker failed: " + phase);
          total += Integer.parseInt(Files.readString(temp.resolve(phase + ".assertions")).strip());
        }
        System.out.println(
            "Metadata repair UI OK; "
                + total
                + " assertions; "
                + (probe ? "single-JVM install consent probe" : "two JVMs")
                + "; actual acquisition proofs/services/CAS/SQLite; authored"
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
    probeMode = args[1].equals("probe");
    CaptureAtlas.output = Path.of(args[3]);
    Path temp = Path.of(args[4]);
    Thread.setDefaultUncaughtExceptionHandler(
        (t, e) -> {
          e.printStackTrace();
          System.exit(1);
        });
    try (var backend =
        new MetadataRepairFixtureBackend(temp.resolve("data"), !restart, Instant.now())) {
      fixture = backend;
      fixtureData = temp.resolve("data");
      AtlasApplication.configure(
          new DiscoveryService() {
            public List<BuildSnapshot> fixtureBuilds() {
              return List.of(fixture.build());
            }

            public CompletionStage<List<BuildSnapshot>> refresh(String p, String r) {
              throw new AssertionError("No discovery in repair workflow");
            }
          },
          fixture.workspaces(),
          () -> {
            throw new AssertionError("No productrefresh");
          },
          fixture.installs(),
          fixture.roots(),
          null,
          null,
          null,
          recordingService(),
          null,
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
                  System.err.println("Metadata repair UI deadline");
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
                  until(() -> pickerCount() == 2);
                  check(
                      fixture.head.calls.get()
                              + fixture.payloadRequests.get()
                              + fixture.rootReads.get()
                              + fixture.installReads.get()
                          == 0,
                      "Startup performed metadata operations");
                  workspace(0, "enUS");
                  if (probeMode) installConsentProbe();
                  else if (restart) restartJourney();
                  else firstJourney();
                  check(
                      !restart
                          || fixture.head.calls.get() == 0 && fixture.payloadRequests.get() == 0,
                      "Offline restart attempted HEAD or payload");
                  Files.writeString(
                      temp.resolve(args[1] + ".assertions"), Integer.toString(assertions));
                  finished = true;
                  System.out.println(
                      "Metadata repair " + args[1] + " phase OK: " + assertions + " assertions");
                  Platform.exit();
                } catch (Throwable e) {
                  e.printStackTrace();
                  System.exit(1);
                }
              });
      controller.setDaemon(true);
      controller.start();
      Application.launch(AtlasApplication.class);
      require(finished, "Metadata repair UI did not finish");
    }
  }

  private static MetadataRepairService recordingService() {
    var delegate = fixture.repairs();
    return new MetadataRepairService() {
      public CompletionStage<MetadataRepairPlan> prepareInstall(
          InstallPlan ordinary, LookupBudgets budgets) {
        lastPlan = null;
        lastResult = null;
        var stage = delegate.prepareInstall(ordinary, budgets);
        stage.whenComplete(
            (plan, error) -> {
              if (error == null) lastPlan = plan;
            });
        return stage;
      }

      public CompletionStage<MetadataRepairPlan> prepareRoot(RootSelectionPlan ordinary) {
        lastPlan = null;
        lastResult = null;
        var stage = delegate.prepareRoot(ordinary);
        stage.whenComplete(
            (plan, error) -> {
              if (error == null) lastPlan = plan;
            });
        return stage;
      }

      public CompletionStage<MetadataRepairResult> execute(MetadataRepairPlan plan) {
        lastResult = null;
        var stage = delegate.execute(plan);
        stage.whenComplete(
            (result, error) -> {
              if (error == null) lastResult = result;
            });
        return stage;
      }

      public void close() {}
    };
  }

  /**
   * Focused layout journey: real ROOT setup supplies the same persistent coverage as the full run.
   */
  private static void installConsentProbe() throws Exception {
    var source = fixture.ordinaryRoot();
    fixture.remove(Kind.ROOT);
    var rootPlan = fixture.repairs().prepareRoot(source).toCompletableFuture().join();
    fixture.repairs().execute(rootPlan).toCompletableFuture().join();
    rootView();
    click("file-reopen-evidence");
    waitNode("file-variant-table");
    check(labels().contains("Root map verified"), "Probe did not establish actual ROOT coverage");
    installView();
    click("install-check");
    waitNode("install-repair-check");
    fixture.corrupt(Kind.INSTALL);
    click("install-repair-check");
    waitNode("install-repair-download");
    consent("install");
    shot("probe-install-consent-100pct.png", "install-repair-consent");
    scale("200%", false);
    consent("install");
    shot("probe-install-consent-200pct.png", "install-repair-consent");
  }

  private static void firstJourney() throws Exception {
    rootView();
    click("file-check-requirements");
    waitNode("file-root-candidate");
    fx(
        () -> {
          ((ComboBox<?>) node("file-root-candidate")).getSelectionModel().selectFirst();
          return null;
        });
    waitNode("root-repair-check");
    fixture.remove(Kind.ROOT);
    int heads = fixture.head.calls.get(), gets = fixture.payloadRequests.get();
    click("root-repair-check");
    waitNode("root-repair-download");
    check(
        fixture.head.calls.get() == heads + 1 && fixture.payloadRequests.get() == gets,
        "Availability check did not keep HEAD separate from payload");
    check(
        fx(() -> stage.getScene().getFocusOwner() == node("root-repair-download")),
        "HEAD completion lost explicit download focus");
    check(
        optional("file-variant-table") == null && !labels().contains("Root map verified"),
        "Positive ROOT repair proposal retained current variants/coverage");
    check(
        lastPlan != null && lastPlan.cost().encodedTransferBytes() > 0,
        "Original repair proposal did not disclose positive cost");
    check(
        fixture.rootReads.get() == 0 && optional("file-variant-table") == null,
        "Proposal implicitly opened root metadata");
    consent("root");
    shot("repair-root-plan-1280x800.png", "root-repair-consent");
    scale("200%", true);
    consent("root");
    shot("repair-root-plan-200pct-1280x800.png", "root-repair-consent");
    scale("100%", true);
    evidence("root", "head-proof", "repair-root-head-1280x800.png");
    var originalRoot = fixture.original(Kind.ROOT);
    click("root-repair-download");
    waitNode("root-repair-open");
    until(() -> lastResult != null);
    completed("root", gets);
    check(
        fx(() -> stage.getScene().getFocusOwner() == node("root-repair-open")),
        "Repair completion lost explicit offline-open focus");
    check(fixture.rootReads.get() == 0, "Successful repair opened root implicitly");
    check(
        lastResult
            .association()
            .actualRepresentation()
            .originalAlias()
            .equals(originalRoot.originalAlias()),
        "Root repair replaced original acquisition history");
    evidence("root", "audit-proof", "repair-root-audit-1280x800.png");
    click("root-repair-open");
    waitNode("file-variant-table");
    check(
        tableSize("file-variant-table") == 2 && fixture.rootReads.get() == 1,
        "Explicit repaired root open did not return two authored variants");
    shot("repair-root-offline-variants-1280x800.png", "file-variant-table");

    // Tool callback fails after real CAS promotion and durable structural audit evidence.
    click("file-check-requirements");
    waitNode("file-root-candidate");
    fx(
        () -> {
          ((ComboBox<?>) node("file-root-candidate")).getSelectionModel().selectFirst();
          return null;
        });
    waitNode("root-repair-check");
    fixture.remove(Kind.ROOT);
    click("root-repair-check");
    waitNode("root-repair-download");
    long associationsBeforeFault = fixture.count("metadata_build_associations");
    fixture.failAfterStructure.set(true);
    click("root-repair-download");
    waitNode("root-repair-verify");
    check(
        fixture.postStructureFaults.get() == 1
            && Files.exists(fixture.path(Kind.ROOT))
            && lastResult == null
            && fixture.count("metadata_build_associations") == associationsBeforeFault,
        "Post-structure uncertainty invented a completed repair/new association");
    var uncertain = fixture.latestAudit().orElseThrow();
    check(
        uncertain.events().getLast().kind() == MetadataRepairAuditEvent.Kind.UNCERTAIN
            && uncertain.events().stream()
                .noneMatch(e -> e.kind() == MetadataRepairAuditEvent.Kind.COMPLETED),
        "Actual uncertain repair audit was marked completed");
    check(
        optional("root-repair-check") == null
            && optional("root-repair-download") == null
            && optional("root-repair-open") == null
            && optional("file-variant-table") == null,
        "Uncertain repair exposed automatic retry/open/current variants");
    check(
        fx(
            () -> {
              Node ordinary = stage.getScene().lookup("#file-download-metadata");
              return ordinary == null || ordinary instanceof Button button && button.isDisabled();
            }),
        "Uncertain repair left ordinary root acquisition enabled");
    check(
        fx(() -> stage.getScene().getFocusOwner() == node("root-repair-verify")),
        "Uncertain completion lost explicit local-verification focus");
    shot("repair-uncertain-verify-only-1280x800.png", "root-repair-verify");
    int uncertainHeads = fixture.head.calls.get(),
        uncertainPayload = fixture.payloadRequests.get(),
        reads = fixture.rootReads.get();
    click("root-repair-verify");
    waitNode("file-variant-table");
    check(
        fixture.rootReads.get() == reads + 1
            && tableSize("file-variant-table") == 2
            && fixture.head.calls.get() == uncertainHeads
            && fixture.payloadRequests.get() == uncertainPayload
            && lastResult == null,
        "Explicit local verification transferred payload or invented repair completion");
    check(
        fixture.latestAudit().orElseThrow().events().getLast().kind()
            == MetadataRepairAuditEvent.Kind.UNCERTAIN,
        "Separate local reading changed historical repair uncertainty");
    shot("repair-uncertain-explicit-local-verification-1280x800.png", "file-variant-table");
    click("file-check-requirements");
    waitNode("file-root-candidate");
    fx(
        () -> {
          ((ComboBox<?>) node("file-root-candidate")).getSelectionModel().selectFirst();
          return null;
        });
    waitNode("root-repair-check");
    check(
        fx(() -> !((Button) node("file-download-metadata")).isDisabled())
            && fixture.head.calls.get() == uncertainHeads
            && fixture.payloadRequests.get() == uncertainPayload,
        "Explicit ordinary source check did not restore its own action or issued repair IO"
            + " implicitly");
    click("file-reopen-evidence");
    waitNode("file-variant-table");

    installView();
    click("install-check");
    waitNode("install-repair-check");
    fixture.corrupt(Kind.INSTALL);
    heads = fixture.head.calls.get();
    gets = fixture.payloadRequests.get();
    click("install-repair-check");
    waitNode("install-repair-download");
    check(
        fixture.head.calls.get() == heads + 1 && fixture.payloadRequests.get() == gets,
        "Install check fetched payload instead of one HEAD");
    consent("install");
    shot("repair-install-plan-1280x800.png", "install-repair-consent");
    scale("200%", false);
    consent("install");
    shot("repair-install-plan-200pct-1280x800.png", "install-repair-consent");
    scale("100%", false);
    var originalInstall = fixture.original(Kind.INSTALL);
    click("install-repair-download");
    waitNode("install-repair-open");
    until(() -> lastResult != null);
    completed("install", gets);
    check(fixture.installReads.get() == 0, "Install repair opened entries implicitly");
    check(
        lastResult
            .association()
            .actualRepresentation()
            .originalAlias()
            .equals(originalInstall.originalAlias()),
        "Install repair replaced original receipt");
    evidence("install", "original-proof", "repair-install-original-proof-1280x800.png");
    evidence("install", "audit-proof", "repair-install-audit-1280x800.png");
    click("install-repair-open");
    waitNode("install-entry-table");
    check(
        tableSize("install-entry-table") == 2,
        "Explicit repaired install open lost authored entries");
    shot("repair-install-offline-entries-1280x800.png", "install-entry-table");

    fixture.remove(Kind.INSTALL);
    click("install-repair-check");
    waitNode("install-repair-download");
    check(
        optional("install-entry-table") == null && !labels().contains("Install list verified"),
        "Positive INSTALL repair proposal retained previous entries/current coverage");
    check(
        labels().contains("Root map verified"),
        "INSTALL repair proposal revoked independent ROOT readiness");
    check(
        fx(
            () ->
                stage.getScene().getRoot().lookupAll(".titled-pane").stream()
                    .anyMatch(
                        n ->
                            n instanceof TitledPane t
                                && t.getText().contains("Historical installation-list receipt"))),
        "Readiness revocation discarded historical installation-list receipt");
    evidence("install", "original-proof", "repair-install-revoked-history-1280x800.png");
    gets = fixture.payloadRequests.get();
    long attempts = fixture.count("metadata_repair_attempts");
    fixture.restore(Kind.INSTALL);
    click("install-repair-download");
    waitNode("install-repair-open");
    until(() -> lastResult != null);
    check(
        lastResult.completedAudit().isEmpty()
            && fixture.payloadRequests.get() == gets
            && fixture.count("metadata_repair_attempts") == attempts,
        "Restored-local race invented payload or audit");
    evidence("install", "audit-proof", "repair-install-verified-local-1280x800.png");

    fixture.remove(Kind.INSTALL);
    click("install-check");
    waitNode("install-repair-check");
    fixture.head.status = 404;
    heads = fixture.head.calls.get();
    gets = fixture.payloadRequests.get();
    click("install-repair-check");
    waitNode("install-repair-check");
    check(
        fixture.head.calls.get() == heads + 1
            && fixture.payloadRequests.get() == gets
            && optional("install-repair-download") == null,
        "HEAD404 authorized payload or fallback");
    shot("repair-head-unavailable-1280x800.png", "install-repair-status");
    fixture.head.status = 200;
    click("install-repair-check");
    waitNode("install-repair-download");
    fixture.payloadMode = MetadataRepairFixtureBackend.PayloadMode.WRONG_BYTES;
    gets = fixture.payloadRequests.get();
    click("install-repair-download");
    waitNode("install-repair-check");
    check(
        fixture.payloadRequests.get() == gets + 1
            && !Files.exists(fixture.path(Kind.INSTALL))
            && lastResult == null,
        "Wrong bytes published a repair or selected a fallback");
    shot("repair-wrong-bytes-1280x800.png", "install-repair-status");
    fixture.payloadMode = MetadataRepairFixtureBackend.PayloadMode.SUCCESS;
    click("install-repair-check");
    waitNode("install-repair-download");
    gets = fixture.payloadRequests.get();
    fixture.monotonic.addAndGet(TimeUnit.HOURS.toNanos(1));
    click("install-repair-download");
    until(() -> optional("install-repair-download") == null);
    check(
        fixture.payloadRequests.get() == gets && optional("install-repair-open") == null,
        "Expired consent started payload or claimed completion");
    shot("repair-expired-1280x800.png", "install-repair-status");
    // Re-offer the ordinary exact source after expiry; no automatic plan or payload follows.
    click("install-check");
    waitNode("install-repair-check");
    click("install-repair-check");
    waitNode("install-repair-download");
    fixture.payloadMode = MetadataRepairFixtureBackend.PayloadMode.HOLD;
    gets = fixture.payloadRequests.get();
    click("install-repair-download");
    until(() -> fixture.heldPayload != null);
    waitNode("install-repair-cancel");
    shot("repair-downloading-1280x800.png", "install-repair-cancel");
    click("install-repair-cancel");
    until(() -> fixture.heldPayload.isCancelled());
    waitNode("install-repair-check");
    check(
        fixture.payloadRequests.get() == gets + 1
            && lastResult == null
            && optional("install-repair-open") == null
            && !Files.exists(fixture.path(Kind.INSTALL)),
        "Cancel claimed repair completion or restored bytes");
    check(
        fx(() -> stage.getScene().getFocusOwner() == node("install-repair-check")),
        "Cancelled request lost retry-check focus");
    shot("repair-cancelled-1280x800.png", "install-repair-status");
    fixture.payloadMode = MetadataRepairFixtureBackend.PayloadMode.SUCCESS;
    // Fixture cleanup restores the authored source for the restart reader; this is not UI repair.
    fixture.restore(Kind.INSTALL);
  }

  private static void restartJourney() throws Exception {
    check(fixture.seedResponses.get() == 0, "Restart reacquired original metadata");
    List<String> ids = Files.readAllLines(fixtureData.resolve("completed-audits.txt"));
    check(ids.size() == 2, "Original process did not persist both completed audit IDs");
    for (String value : ids) {
      var audit = fixture.audit(UUID.fromString(value)).orElseThrow();
      check(
          audit.events().getLast().kind() == MetadataRepairAuditEvent.Kind.COMPLETED
              && audit.events().getLast().observation().isPresent(),
          "Fresh process lost completed repair audit");
    }
    rootView();
    click("file-reopen-evidence");
    waitNode("file-variant-table");
    check(tableSize("file-variant-table") == 2, "Restart explicit offline ROOT lookup failed");
    shot("repair-restart-root-1280x800.png", "file-variant-table");
    installView();
    click("install-reopen");
    waitNode("install-entry-table");
    check(
        tableSize("install-entry-table") == 2,
        "Restart explicit offline install inspection failed");
    shot("repair-restart-install-1280x800.png", "install-entry-table");
    check(
        fixture.head.calls.get() == 0
            && fixture.payloadRequests.get() == 0
            && fixture.ordinaryResponses.get() == 0,
        "Restart made HEAD/payload/ordinary requests");
  }

  private static Path fixtureData;

  private static void consent(String kind) throws Exception {
    String prefix = kind + "-repair-";
    int sample = ++consentSample;
    fx(
        () -> {
          reveal(node(prefix + "consent"));
          return null;
        });
    pause();
    String initial = consentGeometry(prefix, "initial");
    boolean initialFit =
        fx(() -> inside(node(prefix + "cost")) && inside(node(prefix + "download")));
    if (!initialFit)
      fx(
          () -> {
            CaptureAtlas.capture(
                stage, "repair-consent-failure-" + sample + "-" + kind + "-initial.png", 1280, 800);
            return null;
          });
    awaitPulses(2);
    fx(
        () -> {
          reveal(node(prefix + "consent"));
          return null;
        });
    awaitPulses(2);
    String settled = consentGeometry(prefix, "settled-after-four-pulses");
    boolean fit = fx(() -> inside(node(prefix + "cost")) && inside(node(prefix + "download")));
    Files.writeString(
        CaptureAtlas.output.resolve("repair-consent-" + sample + "-" + kind + "-geometry.txt"),
        initial + "\n" + settled);
    if (!fit)
      fx(
          () -> {
            CaptureAtlas.capture(
                stage, "repair-consent-failure-" + sample + "-" + kind + "-settled.png", 1280, 800);
            return null;
          });
    check(fit, "Positive cost and separate repair action do not fit together\n" + settled);
    check(
        fx(
            () ->
                ((Label) node(prefix + "cost"))
                    .getText()
                    .contains(Long.toString(lastPlan.cost().encodedTransferBytes()))),
        "Consent lost actual positive transfer bytes");
    check(optional(prefix + "open") == null, "Repair proposal exposed ready offline open");
  }

  private static String consentGeometry(String prefix, String phase) throws Exception {
    return fx(
        () -> {
          layout();
          var root = stage.getScene().getRoot();
          Node viewport = root.lookup(".workspace-scroll .viewport");
          var cost = (Label) node(prefix + "cost");
          var button = (Button) node(prefix + "download");
          var scroll = (ScrollPane) root.lookup(".workspace-scroll");
          Node context = root.lookup(".context-strip");
          return "phase="
              + phase
              + "\nscene="
              + stage.getScene().getWidth()
              + "x"
              + stage.getScene().getHeight()
              + " outputScale="
              + stage.getOutputScaleX()
              + ","
              + stage.getOutputScaleY()
              + "\nviewport="
              + viewport.localToScene(viewport.getBoundsInLocal())
              + "\ncontext="
              + context.localToScene(context.getBoundsInLocal())
              + "\ncost="
              + cost.localToScene(cost.getBoundsInLocal())
              + " prefHeight="
              + cost.prefHeight(cost.getWidth())
              + " font="
              + cost.getFont()
              + " wrap="
              + cost.isWrapText()
              + "\nbutton="
              + button.localToScene(button.getBoundsInLocal())
              + " prefHeight="
              + button.prefHeight(button.getWidth())
              + " font="
              + button.getFont()
              + "\nconsent="
              + node(prefix + "consent").localToScene(node(prefix + "consent").getBoundsInLocal())
              + "\nscrollV="
              + scroll.getVvalue()
              + " content="
              + scroll.getContent().getLayoutBounds()
              + "\nrootInlineStyle="
              + root.getStyle()
              + "\ncostText="
              + cost.getText()
              + "\n";
        });
  }

  private static void awaitPulses(int count) throws Exception {
    var done = new CompletableFuture<Void>();
    Platform.runLater(
        () ->
            new javafx.animation.AnimationTimer() {
              int frames;

              public void handle(long now) {
                if (++frames >= count) {
                  stop();
                  done.complete(null);
                }
              }
            }.start());
    done.get(5, TimeUnit.SECONDS);
  }

  private static void completed(String kind, int previousGets) throws Exception {
    var audit = lastResult.completedAudit().orElseThrow();
    var actual = audit.events().getLast().observation().orElseThrow();
    check(
        actual.payloadRequestCount() == fixture.payloadRequests.get() - previousGets
            && actual.payloadResponseBytes() == lastPlan.originalProof().object().length(),
        "Audit does not report actual payload request/response counts");
    check(
        actual.actualTransfer().object().equals(lastPlan.originalProof().object())
            && actual.originalProof().equals(lastPlan.originalProof()),
        "Audit changed original encoded physical identity");
    check(
        !actual
            .actualTransfer()
            .evidence()
            .verifiedAt()
            .equals(lastPlan.originalProof().originalAlias().evidence().verifiedAt()),
        "Fresh repair timestamp was replaced with original acquisition time");
    auditIds.add(audit.attemptId());
    Files.write(
        fixtureData.resolve("completed-audits.txt"),
        auditIds.stream().map(UUID::toString).toList());
  }

  private static void evidence(String kind, String part, String image) throws Exception {
    String prefix = kind + "-repair-";
    fx(
        () -> {
          ((TitledPane) node(prefix + "evidence")).setExpanded(true);
          return null;
        });
    pause();
    check(
        fx(
            () ->
                node(prefix + part) instanceof TextArea t
                    && !t.isEditable()
                    && !t.getText().isBlank()),
        "Evidence is not copyable separate readonly text");
    String text = fx(() -> ((TextArea) node(prefix + part)).getText());
    if (part.equals("head-proof"))
      check(
          lastPlan != null
              && text.contains(lastPlan.sourceObservation().resourceUrl())
              && text.contains(lastPlan.sourceObservation().observedAt().toString()),
          "HEAD proof lost actual observed route/time");
    if (part.equals("original-proof"))
      check(
          lastPlan != null
              && text.contains(lastPlan.originalProof().originalAlias().evidence().sourceUrl())
              && text.contains(
                  lastPlan.originalProof().originalAlias().evidence().verifiedAt().toString()),
          "Original proof replaced historical route/time");
    if (part.equals("audit-proof") && lastResult != null) {
      if (lastResult.completedAudit().isPresent()) {
        var actual =
            lastResult.completedAudit().get().events().getLast().observation().orElseThrow();
        check(
            text.contains(actual.actualTransfer().evidence().sourceUrl())
                && text.contains(actual.actualTransfer().evidence().verifiedAt().toString())
                && text.contains("Payload requests: " + actual.payloadRequestCount())
                && text.contains("Actual encoded bytes: " + actual.payloadResponseBytes()),
            "Completed audit text lost actual GET facts");
      } else
        check(
            text.contains("No completed download audit was issued"),
            "Healthy local race displayed invented fresh transfer audit");
    }
    shot(image, prefix + part);
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

  private static void workspace(int ignoredBuild, String locale) throws Exception {
    fx(
        () -> {
          var w =
              picker().getItems().stream()
                  .filter(
                      v ->
                          v.build().snapshotId().equals(fixture.build().snapshotId())
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
        return fx(CaptureMetadataRepairWorkflow::labels);
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
