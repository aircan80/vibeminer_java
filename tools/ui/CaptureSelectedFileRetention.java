import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.lookup.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.lookup.FileLookupFixtureBackend;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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
 * Native UI policy fixtures, not selected-file protocol verification. Root/CAS/SQLite are real and
 * synthetic; selected-service outcomes and persisted receipts are deliberately fake projections.
 * Two JVMs prove UI restart invocation by exact root variant without candidate/locator requests.
 */
public final class CaptureSelectedFileRetention {
  private static FileLookupFixtureBackend backend;
  private static FakeSelected selected;
  private static RootLookupResult root;
  private static VerifiedSourceAlias configuration;
  private static boolean offline;
  private static volatile boolean finished;
  private static int transportBaseline;
  private static Stage stage;

  public static void main(String[] args) throws Exception {
    if (args.length == 4 && args[0].equals("--isolated-display") && args[2].equals("--output")) {
      requireDisplay(args[1]);
      Path output = Path.of(args[3]).toAbsolutePath();
      Files.createDirectories(output);
      Path temporary = Files.createTempDirectory("vibeminer-retention-ui-");
      try {
        for (String phase : List.of("acquire", "offline")) {
          Process child =
              new ProcessBuilder(
                      Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                      "--enable-native-access=ALL-UNNAMED",
                      "-cp",
                      System.getProperty("java.class.path"),
                      CaptureSelectedFileRetention.class.getName(),
                      "--worker",
                      phase,
                      args[1],
                      output.toString(),
                      temporary.toString())
                  .inheritIO()
                  .start();
          if (!child.waitFor(150, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new IllegalStateException("Retention UI timed out: " + phase);
          }
          check(child.exitValue() == 0, "Retention UI child failed: " + phase);
        }
        System.out.println(
            "Selected retention UI OK; two JVMs; real synthetic root/CAS/SQLite; FAKE"
                + " selected-service receipts; zero live HTTP; not production selected-file"
                + " verification");
      } finally {
        try (var paths = Files.walk(temporary)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            Files.deleteIfExists(path);
        }
      }
      return;
    }
    check(
        args.length == 5 && args[0].equals("--worker"),
        "Usage: --isolated-display :N --output DIRECTORY");
    requireDisplay(args[2]);
    offline = args[1].equals("offline");
    CaptureAtlas.output = Path.of(args[3]);
    Path temp = Path.of(args[4]);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          error.printStackTrace();
          System.exit(1);
        });
    try (var resources = new FileLookupFixtureBackend(temp.resolve("root-fixture"), offline)) {
      backend = resources;
      if (!offline) {
        for (var build : backend.builds()) {
          var plan =
              backend
                  .service()
                  .planRoot(build, LookupBudgets.defaults())
                  .toCompletableFuture()
                  .get(20, TimeUnit.SECONDS);
          backend
              .service()
              .retainRoot(
                  new RootSelectionPlan(
                      plan,
                      plan.candidates().stream()
                          .filter(c -> c.encodedBytes() > 0)
                          .findFirst()
                          .orElseThrow()))
              .toCompletableFuture()
              .get(20, TimeUnit.SECONDS);
          if (configuration == null) configuration = plan.configuration();
        }
        saveConfiguration(temp.resolve("configuration.properties"), configuration);
      } else configuration = loadConfiguration(temp.resolve("configuration.properties"));
      root =
          backend
              .service()
              .lookupOffline(backend.builds().getFirst(), Set.of(42L), LookupBudgets.defaults())
              .toCompletableFuture()
              .get(20, TimeUnit.SECONDS);
      selected = new FakeSelected(temp.resolve("FAKE-selected-receipts"));
      transportBaseline = backend.transportCalls();
      AtlasApplication.configure(
          new DiscoveryService() {
            public List<BuildSnapshot> fixtureBuilds() {
              return backend.builds();
            }

            public CompletionStage<List<BuildSnapshot>> refresh(String p, String r) {
              throw new AssertionError("No discovery in retained fixture");
            }
          },
          backend.workspaces(),
          () -> {
            throw new AssertionError("No product refresh");
          },
          null,
          backend.service(),
          selected,
          true);
      Thread deadline =
          new Thread(
              () -> {
                try {
                  Thread.sleep(120_000);
                } catch (InterruptedException ignored) {
                  return;
                }
                if (!finished) {
                  System.err.println("Retention UI worker deadline");
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
                                      .filter(w -> w instanceof Stage && w.isShowing())
                                      .map(w -> (Stage) w)
                                      .findFirst()
                                      .orElse(null));
                      if (stage != null) break;
                    } catch (IllegalStateException startup) {
                    }
                    Thread.sleep(100);
                  }
                  check(stage != null, "No JavaFX stage");
                  fx(
                      () -> {
                        stage.setX(0);
                        stage.setY(0);
                        stage.setWidth(1280);
                        stage.setHeight(800);
                        return null;
                      });
                  until(() -> picker().getItems().size() == 2);
                  openBuild(0);
                  lookup();
                  chooseVariant(0);
                  if (offline) offlineJourney();
                  else acquisitionJourney();
                  check(
                      backend.transportCalls() == transportBaseline,
                      "UI attempted root network acquisition");
                  if (offline)
                    check(backend.transportCalls() == 0, "Restart touched root transport");
                  finished = true;
                  Platform.exit();
                } catch (Throwable error) {
                  error.printStackTrace();
                  System.exit(1);
                }
              });
      controller.setDaemon(true);
      controller.start();
      Application.launch(AtlasApplication.class);
      check(finished, "UI worker incomplete");
    }
  }

  private static void acquisitionJourney() throws Exception {
    check(
        selected.candidates == 0 && selected.probes == 0,
        "Root selection started selected metadata");
    click("selected-retention-offline");
    until(() -> labels().contains("No retained membership"));
    check(selected.candidates == 0, "Offline exact-variant lookup requested EN candidates");
    pause();
    check(
        fx(() -> stage.getScene().getFocusOwner() == node("selected-retention-offline")),
        "Offline no-membership lost retry focus");
    shot("retention-no-membership-1280x800.png", "selected-retention-offline");
    candidates();
    chooseCandidate(1);
    check(selected.probes == 0, "Candidate choice located automatically");
    selected.mode = "404";
    click("selected-retention-locate");
    selected.completeProbe();
    waitNode("selected-retention-search");
    check(selected.searches == 0, "Typed404 started archive search without approval");
    shot("retention-archive-consent-1280x800.png", "selected-retention-search");
    selected.incomplete = true;
    click("selected-retention-search");
    selected.completeSearch();
    until(() -> labels().contains("Search incomplete"));
    check(optional("selected-retention-retain") == null, "Incomplete search offered retention");
    shot("retention-incomplete-search-1280x800.png", "selected-retention-locate");
    selected.incomplete = false;
    click("selected-retention-locate");
    selected.completeProbe();
    waitNode("selected-retention-search");
    click("selected-retention-search");
    selected.completeSearch();
    waitNode("selected-retention-retain");
    assertConsent();
    consentShot("retention-archive-cost-1280x800.png");
    scale("200%");
    assertConsent();
    consentShot("retention-archive-cost-200pct-1280x800.png");
    click("selected-retention-retain");
    check(selected.retains == 1, "Explicit Retain not called");
    check(
        !labels().contains("Complete encoded bytes verified ·"),
        "Unfinished request appeared retained");
    selected.completeRetain();
    waitReceipt();
    shot("retention-encoded-receipt-200pct-1280x800.png", "selected-retention-offline");
    fx(
        () -> {
          var pane = titled("Encoded receipt and current association evidence");
          pane.setExpanded(true);
          return null;
        });
    pause();
    shotTitled(
        "retention-original-source-200pct-1280x800.png",
        "Encoded receipt and current association evidence");
    fx(
        () -> {
          Node original =
              CaptureAtlas.accessible(
                  stage, "Original encoded acquisition receipt, not current reused traffic");
          reveal(original);
          return null;
        });
    pause();
    fx(
        () -> {
          CaptureAtlas.capture(stage, "retention-original-receipt-200pct-1280x800.png", 1280, 800);
          return null;
        });
    scale("100%");
    fx(
        () -> {
          titled("Encoded receipt and current association evidence").setExpanded(true);
          return null;
        });
    pause();
    shotTitled(
        "retention-original-source-1280x800.png",
        "Encoded receipt and current association evidence");
    scale("200%");
    chooseCandidate(0);
    selected.mode = "conflict";
    click("selected-retention-locate");
    selected.completeProbe();
    until(() -> labels().contains("duplicate retention remains blocked"));
    check(
        optional("selected-retention-retain") == null
            && optional("selected-retention-search") == null,
        "Conflict offered alternate acquisition");
    shot("retention-canonical-conflict-200pct-1280x800.png", "selected-retention-status");
    chooseCandidate(1);
    selected.mode = "reuse";
    click("selected-retention-locate");
    selected.completeProbe();
    waitNode("selected-retention-retain");
    check(
        labels().contains("Planned encoded transfer: 0 bytes"),
        "Reuse displayed original traffic as current cost");
    consentShot("retention-reuse-cost-200pct-1280x800.png");
    click("selected-retention-retain");
    selected.completeRetain();
    waitReceipt();
    check(
        fx(() -> textAreas().contains("Original acquisition traffic: 90 bytes")),
        "Original receipt traffic missing");
    click("selected-retention-offline");
    waitReceipt();
    check(selected.offlineCalls >= 2, "Explicit offline did not reach variant API");
    chooseVariant(1);
    candidates();
    chooseCandidate(0);
    selected.mode = "direct";
    click("selected-retention-locate");
    selected.completeProbe();
    waitNode("selected-retention-retain");
    scale("100%");
    assertConsent();
    consentShot("retention-direct-cost-1280x800.png");
    click("selected-retention-retain");
    selected.completeRetain();
    waitReceipt();
    chooseCandidate(1);
    selected.mode = "hold";
    click("selected-retention-locate");
    var late = selected.probe;
    var latePlan = selected.plan(false);
    click("selected-retention-cancel");
    late.complete(new FileSelectionProbe(Optional.of(latePlan), Optional.empty()));
    pause();
    check(
        optional("selected-retention-retain") == null, "Cancelled late locator repainted consent");
    shot("retention-cancelled-1280x800.png", "selected-retention-locate");
    click("selected-retention-locate");
    late = selected.probe;
    latePlan = selected.plan(false);
    fx(
        () -> {
          var id = (TextField) node("file-id-input");
          id.requestFocus();
          id.setText("7");
          return null;
        });
    late.complete(new FileSelectionProbe(Optional.of(latePlan), Optional.empty()));
    pause();
    check(
        optional("selected-retention-retain") == null,
        "Changed ID accepted stale acquisition plan");
    check(
        fx(() -> stage.getScene().getFocusOwner() == node("file-id-input")),
        "Late result stole typing focus");
  }

  private static void offlineJourney() throws Exception {
    check(
        selected.candidates == 0 && selected.probes == 0,
        "Restart planned EN metadata before offline click");
    check(optional("selected-file-candidates") == null, "Restart fabricated candidate session");
    click("selected-retention-offline");
    waitReceipt();
    check(
        selected.candidates + selected.probes + selected.searches + selected.retains == 0,
        "Offline variant inspection invoked source operations");
    shot("retention-restart-offline-1280x800.png", "selected-retention-offline");
    scale("200%");
    shot("retention-restart-offline-200pct-1280x800.png", "selected-retention-offline");
    Path payload = selected.payload(selected.lastVariant);
    byte[] original = Files.readAllBytes(payload);
    byte[] bad = original.clone();
    bad[0] ^= 1;
    Files.write(payload, bad);
    click("selected-retention-offline");
    until(() -> labels().contains("Bytes failed verification"));
    pause();
    check(
        fx(() -> stage.getScene().getFocusOwner() == node("selected-retention-offline")),
        "Corrupt offline retry lost focus");
    check(
        !labels().contains("Selected encoded file verified"),
        "Corrupt reopen kept verified header");
    shot("retention-corrupt-offline-200pct-1280x800.png", "selected-retention-offline");
    Files.delete(payload);
    click("selected-retention-offline");
    until(() -> labels().contains("Retained bytes are missing"));
    shot("retention-missing-offline-200pct-1280x800.png", "selected-retention-offline");
    Files.write(payload, original);
    Files.delete(selected.record(selected.lastVariant));
    click("selected-retention-offline");
    until(() -> labels().contains("No retained membership"));
    check(
        selected.candidates + selected.probes + selected.searches + selected.retains == 0,
        "Offline failure silently repaired over source operations");
  }

  /**
   * Fake outcomes only; small persisted fixture files simulate restart/missing/corrupt projection.
   */
  private static final class FakeSelected implements SelectedFileService {
    final Path directory;
    int candidates, probes, searches, retains, offlineCalls;
    String mode = "direct";
    boolean incomplete;
    CandidatePlan candidatePlan;
    CandidateChoice choice;
    SelectedFilePlan consent;
    FileVariantAssociation lastVariant;
    CompletableFuture<FileSelectionProbe> probe;
    CompletableFuture<FileSelectionSearch> searching;
    CompletableFuture<SelectedFileAssociation> retaining;

    FakeSelected(Path directory) throws IOException {
      this.directory = directory;
      Files.createDirectories(directory);
    }

    public CompletionStage<CandidatePlan> planCandidates(
        BuildSnapshot b, FileVariantAssociation v, SelectedFileBudgets budgets) {
      candidates++;
      candidatePlan =
          new CandidatePlan(
              "FAKE-candidates-" + candidates,
              v,
              configuration,
              root.root(),
              proof(),
              List.of(
                  new EncodedCandidate("1".repeat(32), OptionalLong.of(90), OptionalLong.of(0)),
                  new EncodedCandidate("2".repeat(32), OptionalLong.empty(), OptionalLong.of(1))),
              budgets);
      return CompletableFuture.completedFuture(candidatePlan);
    }

    public CompletionStage<FileSelectionProbe> probeLoose(CandidateChoice choice) {
      probes++;
      this.choice = choice;
      probe = noncancellable();
      return probe;
    }

    void completeProbe() throws Exception {
      if (mode.equals("404"))
        probe.complete(
            new FileSelectionProbe(
                Optional.empty(), Optional.of(new Loose404Permit("FAKE-404-" + probes, choice))));
      else if (mode.equals("conflict"))
        probe.completeExceptionally(
            new SelectedFileException(
                SelectedFileException.Code.REPLAN_REQUIRED, "FAKE canonical conflict"));
      else if (mode.equals("reuse")) {
        var prior = load(choice.plan().variant());
        probe.complete(
            new FileSelectionProbe(
                Optional.of(
                    new SelectedFilePlan(
                        choice,
                        Optional.empty(),
                        Optional.of(prior.representation()),
                        new AcquisitionCost(0, 0))),
                Optional.empty()));
      } else if (!mode.equals("hold"))
        probe.complete(new FileSelectionProbe(Optional.of(plan(false)), Optional.empty()));
    }

    public CompletionStage<FileSelectionSearch> searchArchives(
        Loose404Permit permit, ArchiveSearchBudgets budgets) {
      searches++;
      check(permit.choice().equals(choice), "Changed typed404 choice");
      searching = noncancellable();
      return searching;
    }

    void completeSearch() {
      searching.complete(
          incomplete
              ? new FileSelectionSearch(Optional.empty(), 1, 4, false)
              : new FileSelectionSearch(Optional.of(plan(true)), 2, 4, false));
    }

    SelectedFilePlan plan(boolean archive) {
      var cost = new AcquisitionCost(90, 90);
      String key = archive ? "a".repeat(32) : choice.candidate().encodingKey();
      var locator =
          new FileLocator(
              archive ? FileLocator.Kind.ARCHIVE : FileLocator.Kind.LOOSE,
              "https://original.example/" + key,
              key,
              archive ? 4096 : 90,
              archive ? 128 : 0,
              90,
              archive ? Optional.of(mapping()) : Optional.empty());
      return new SelectedFilePlan(
          choice,
          Optional.of(new LocatedFilePlan("FAKE-location-" + probes, choice, locator, 90, cost)),
          Optional.empty(),
          cost);
    }

    public CompletionStage<SelectedFileAssociation> retain(SelectedFilePlan plan) {
      retains++;
      consent = plan;
      retaining = noncancellable();
      return retaining;
    }

    void completeRetain() throws Exception {
      if (consent.reuse().isPresent()) {
        retaining.complete(load(consent.choice().plan().variant()));
        return;
      }
      var v = consent.choice().plan().variant();
      var loc = consent.acquisition().orElseThrow().locator();
      byte[] bytes = new byte[90];
      for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
      Files.write(payload(v), bytes);
      Properties p = new Properties();
      p.setProperty("encoding", consent.choice().candidate().encodingKey());
      p.setProperty("archive", Boolean.toString(loc.kind() == FileLocator.Kind.ARCHIVE));
      p.setProperty("sha", sha(bytes));
      try (var out = Files.newOutputStream(record(v))) {
        p.store(out, "FAKE selected-service receipt projection; not protocol verification");
      }
      retaining.complete(load(v));
    }

    public CompletionStage<SelectedFileAssociation> inspectOffline(
        FileVariantAssociation variant, SelectedFileBudgets budgets) {
      offlineCalls++;
      lastVariant = variant;
      try {
        return CompletableFuture.completedFuture(load(variant));
      } catch (Exception error) {
        return CompletableFuture.failedFuture(error);
      }
    }

    public CompletionStage<SelectedFileAssociation> inspectOffline(
        SelectedFileDescriptor d, SelectedFileBudgets budgets) {
      return inspectOffline(d.association(), budgets);
    }

    SelectedFileAssociation load(FileVariantAssociation v) throws Exception {
      if (!Files.exists(record(v)))
        throw new SelectedFileException(
            SelectedFileException.Code.NOT_FOUND, "FAKE absent membership");
      if (!Files.exists(payload(v)))
        throw new SelectedFileException(
            SelectedFileException.Code.MISSING_LOCAL, "FAKE absent bytes");
      Properties p = new Properties();
      try (var in = Files.newInputStream(record(v))) {
        p.load(in);
      }
      byte[] bytes = Files.readAllBytes(payload(v));
      if (!sha(bytes).equals(p.getProperty("sha")))
        throw new SelectedFileException(
            SelectedFileException.Code.INTEGRITY_FAILURE, "FAKE hash check");
      String encoding = p.getProperty("encoding");
      boolean archive = Boolean.parseBoolean(p.getProperty("archive"));
      String key = archive ? "a".repeat(32) : encoding, url = "https://original.example/" + key;
      Instant at = Instant.parse("2020-01-01T00:00:00Z");
      var encoded =
          new VerifiedSourceAlias(
              ContentRepresentation.sourceIdentity(encoding),
              new StoredObject(new ObjectDigest(p.getProperty("sha")), 90),
              new SourceVerificationEvidence(ContentRepresentation.VERIFIER_SEMANTICS, url, at));
      var receipt =
          new EncodedSourceReceipt(
              archive ? EncodedSourceReceipt.Kind.ARCHIVE : EncodedSourceReceipt.Kind.LOOSE,
              url,
              key,
              archive ? 4096 : 90,
              archive ? 128 : 0,
              90,
              at,
              1,
              90,
              "b".repeat(64),
              archive ? Optional.of(mapping()) : Optional.empty());
      var representation =
          new ContentRepresentation(
              v.slot(), encoded, EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS, receipt);
      return new SelectedFileAssociation(
          new SelectedFileDescriptor(v, encoding, ContentRepresentation.VERIFIER_SEMANTICS),
          configuration,
          root.root(),
          proof(),
          representation);
    }

    Path record(FileVariantAssociation v) {
      return directory.resolve(
          v.build().snapshotId()
              + "-"
              + v.fileDataId()
              + "-"
              + v.contentKey()
              + "-"
              + v.rawFlagsFirst()
              + "-"
              + v.rawFlagsSecond()
              + ".properties");
    }

    Path payload(FileVariantAssociation v) {
      return Path.of(record(v) + ".FAKE-bytes");
    }

    public void close() {}
  }

  private static FileAssociationProof proof() {
    return new FileAssociationProof(
        root.root().source(),
        new ResolvedSourceIdentity("FAKE-fixture", "encoding-metadata", "FAKE-v1"),
        "FAKE-v1",
        "c".repeat(64));
  }

  private static ArchiveMappingProof mapping() {
    return new ArchiveMappingProof(
        "a".repeat(32),
        "d".repeat(64),
        "e".repeat(64),
        "f".repeat(64),
        "FAKE-index-v1",
        OptionalLong.of(100),
        100);
  }

  private static <T> CompletableFuture<T> noncancellable() {
    return new CompletableFuture<>() {
      public boolean cancel(boolean interrupt) {
        return false;
      }
    };
  }

  private static void candidates() throws Exception {
    click("selected-file-check");
    waitNode("selected-file-candidates");
  }

  private static void chooseCandidate(int index) throws Exception {
    int beforeProbes = selected.probes, beforeRetains = selected.retains;
    fx(
        () -> {
          TableView<?> table = (TableView<?>) node("selected-file-candidates");
          table.requestFocus();
          table.getSelectionModel().select(index);
          check(table == node("selected-file-candidates"), "Candidate choice replaced table");
          table.fireEvent(
              new KeyEvent(
                  KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
          Node detail = node("selected-file-candidate-value");
          check(stage.getScene().getFocusOwner() == detail, "Enter did not inspect candidate");
          detail.fireEvent(
              new KeyEvent(
                  KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
          check(
              stage.getScene().getFocusOwner() == table, "Escape did not restore candidate table");
          return null;
        });
    pause();
    check(
        selected.probes == beforeProbes && selected.retains == beforeRetains,
        "Keyboard inspection bypassed staged consent");
  }

  private static void chooseVariant(int index) throws Exception {
    fx(
        () -> {
          ((TableView<?>) node("file-variant-table")).getSelectionModel().select(index);
          return null;
        });
    pause();
    waitNode("selected-retention-offline");
  }

  private static void lookup() throws Exception {
    fx(
        () -> {
          ((TextField) node("file-id-input")).setText("42");
          return null;
        });
    click("file-reopen-evidence");
    waitNode("file-variant-table");
  }

  private static void openBuild(int index) throws Exception {
    fx(
        () -> {
          var w =
              picker().getItems().stream()
                  .filter(
                      v -> v.build().snapshotId().equals(backend.builds().get(index).snapshotId()))
                  .findFirst()
                  .orElseThrow();
          picker().setValue(w);
          CaptureAtlas.navigate(stage, "Archive");
          ((Button) node("open-file-lookup")).fire();
          return null;
        });
    pause();
  }

  @SuppressWarnings("unchecked")
  private static ComboBox<Workspace> picker() {
    return (ComboBox<Workspace>) node("saved-workspace-picker");
  }

  private static void waitReceipt() throws Exception {
    until(() -> labels().contains("Complete encoded bytes verified ·"));
  }

  private static void waitNode(String id) throws Exception {
    until(() -> optional(id) != null);
    pause();
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

  private static void scale(String scale) throws Exception {
    fx(
        () -> {
          CaptureAtlas.button(stage, "Settings").fire();
          CaptureAtlas.setCombo(CaptureAtlas.accessible(stage, "Application text scale"), scale);
          CaptureAtlas.navigate(stage, "Archive");
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

  private static void shotTitled(String name, String title) throws Exception {
    fx(
        () -> {
          reveal(titled(title));
          return null;
        });
    pause();
    fx(
        () -> {
          CaptureAtlas.capture(stage, name, 1280, 800);
          return null;
        });
  }

  private static void consentShot(String name) throws Exception {
    assertConsent();
    pause();
    fx(
        () -> {
          CaptureAtlas.capture(stage, name, 1280, 800);
          return null;
        });
  }

  private static void assertConsent() throws Exception {
    fx(
        () -> {
          var cost =
              stage.getScene().getRoot().lookupAll(".label").stream()
                  .filter(
                      n ->
                          n instanceof Label l
                              && l.getText().startsWith("Planned encoded transfer:"))
                  .findFirst()
                  .orElseThrow();
          reveal(cost);
          visible(cost);
          visible(node("selected-retention-retain"));
          return null;
        });
  }

  private static void visible(Node target) {
    layout();
    var scroll = (ScrollPane) stage.getScene().getRoot().lookup(".workspace-scroll");
    Node port = scroll.lookup(".viewport");
    Bounds frame = port.localToScene(port.getLayoutBounds()),
        bounds = target.localToScene(target.getLayoutBounds());
    check(
        bounds.getMinY() >= frame.getMinY() - 1 && bounds.getMaxY() <= frame.getMaxY() + 1,
        "Consent cost/action outside viewport: " + bounds + " / " + frame);
  }

  private static void reveal(Node target) {
    layout();
    var scroll = (ScrollPane) stage.getScene().getRoot().lookup(".workspace-scroll");
    Bounds content = scroll.getContent().localToScene(scroll.getContent().getBoundsInLocal()),
        bounds = target.localToScene(target.getBoundsInLocal());
    double range =
        scroll.getContent().getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
    scroll.setVvalue(
        range <= 0 ? 0 : Math.max(0, Math.min(1, (bounds.getMinY() - content.getMinY()) / range)));
    layout();
  }

  private static TitledPane titled(String text) {
    return stage.getScene().getRoot().lookupAll(".titled-pane").stream()
        .filter(n -> n instanceof TitledPane t && t.getText().equals(text))
        .map(n -> (TitledPane) n)
        .findFirst()
        .orElseThrow();
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
        return fx(CaptureSelectedFileRetention::labels);
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
    var result = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            result.complete(call.call());
          } catch (Throwable error) {
            result.completeExceptionally(error);
          }
        });
    return result.get(10, TimeUnit.SECONDS);
  }

  private static void until(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!fx(condition::getAsBoolean)) {
      check(System.nanoTime() < deadline, "Timed out waiting for UI state");
      Thread.sleep(50);
    }
  }

  private static void pause() throws InterruptedException {
    Thread.sleep(350);
  }

  private static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw new IllegalStateException(message);
  }

  private static void requireDisplay(String value) {
    check(
        value.matches(":[1-9][0-9]*") && value.equals(System.getenv("DISPLAY")),
        "Explicit nonzero isolated DISPLAY required");
  }

  private static void saveConfiguration(Path path, VerifiedSourceAlias a) throws Exception {
    Properties p = new Properties();
    p.setProperty("namespace", a.source().namespace());
    p.setProperty("key", a.source().immutableSourceKey());
    p.setProperty("verifier", a.source().verificationSemantics());
    p.setProperty("sha", a.object().digest().value());
    p.setProperty("length", Long.toString(a.object().length()));
    p.setProperty("url", a.evidence().sourceUrl());
    p.setProperty("at", a.evidence().verifiedAt().toString());
    try (var out = Files.newOutputStream(path)) {
      p.store(out, "Synthetic root configuration receipt");
    }
  }

  private static VerifiedSourceAlias loadConfiguration(Path path) throws Exception {
    Properties p = new Properties();
    try (var in = Files.newInputStream(path)) {
      p.load(in);
    }
    return new VerifiedSourceAlias(
        new ResolvedSourceIdentity(
            p.getProperty("namespace"), p.getProperty("key"), p.getProperty("verifier")),
        new StoredObject(
            new ObjectDigest(p.getProperty("sha")), Long.parseLong(p.getProperty("length"))),
        new SourceVerificationEvidence(
            p.getProperty("verifier"), p.getProperty("url"), Instant.parse(p.getProperty("at"))));
  }
}
