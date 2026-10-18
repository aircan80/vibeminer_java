import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.item.ItemProjectionException;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.ItemPhysicalRow;
import io.vibeminer.infrastructure.item.ItemProjectionFixtureBackend;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
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

/**
 * Native Item projection acceptance: authored WDC5/BLTE, real reader/schema/derived SQLite/CAS and
 * application policy. Root/EN association authentication is an explicit proof-port fixture; there
 * is no live data, official TACT claim, imported test class or normal user catalog.
 */
public final class CaptureItemProjectionWorkflow {
  private static ItemProjectionFixtureBackend fixture;
  private static Stage stage;
  private static boolean restart;
  private static volatile boolean finished;
  private static int assertions;

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--supervisor-self-test")) {
      supervisorSelfTest();
      return;
    }
    if (args.length == 4 && args[0].equals("--isolated-display") && args[2].equals("--output")) {
      requireDisplay(args[1]);
      Path output = Path.of(args[3]).toAbsolutePath();
      Files.createDirectories(output);
      Path temp = Files.createTempDirectory("vibeminer-item-ui-");
      int total = 0;
      Process active = null;
      try {
        for (String phase : List.of("prepare", "restart")) {
          var process =
              active =
                  new ProcessBuilder(
                          Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                          "--enable-native-access=ALL-UNNAMED",
                          "-cp",
                          System.getProperty("java.class.path"),
                          CaptureItemProjectionWorkflow.class.getName(),
                          "--worker",
                          phase,
                          args[1],
                          output.toString(),
                          temp.toString())
                      .inheritIO()
                      .start();
          if (!process.waitFor(150, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Item UI worker deadline: " + phase);
          }
          require(process.exitValue() == 0, "Item UI worker failed: " + phase);
          total += Integer.parseInt(Files.readString(temp.resolve(phase + ".assertions")).strip());
        }
        System.out.println(
            "Item projection UI OK; "
                + total
                + " assertions; two JVMs; authored61rows; real reader/schema/service/CAS/SQLite;"
                + " synthetic root/EN proof port; zero HTTP");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw interrupted;
      } finally {
        if (retire(active, TimeUnit.SECONDS.toNanos(10))) {
          try (var paths = Files.walk(temp)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
              Files.deleteIfExists(path);
          }
        } else {
          System.err.println("Worker termination unconfirmed; preserving fixture store: " + temp);
        }
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
        (thread, error) -> {
          error.printStackTrace();
          System.exit(1);
        });
    try (var resources = new ItemProjectionFixtureBackend(temp.resolve("data"), !restart)) {
      fixture = resources;
      AtlasApplication.configure(
          new DiscoveryService() {
            public List<BuildSnapshot> fixtureBuilds() {
              return fixture.builds();
            }

            public CompletionStage<List<BuildSnapshot>> refresh(String p, String r) {
              throw new AssertionError("No discovery in offline Item workflow");
            }
          },
          fixture.workspaces(),
          () -> {
            throw new AssertionError("No product refresh");
          },
          null,
          fixture.rootService(),
          fixture.selectedService(),
          fixture.service(),
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
                  System.err.println("Item UI deadline");
                  System.exit(2);
                }
              });
      deadline.setDaemon(true);
      deadline.start();
      Thread controller =
          new Thread(
              () -> {
                try {
                  for (int attempt = 0; attempt < 100; attempt++) {
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
                  require(stage != null, "No native stage");
                  fx(
                      () -> {
                        stage.setX(0);
                        stage.setY(0);
                        stage.setWidth(1280);
                        stage.setHeight(800);
                        return null;
                      });
                  until(() -> picker().getItems().size() == 3);
                  check(
                      fixture.prepares.get() + fixture.pages.get() + fixture.lookups.get() == 0,
                      "Startup performed Item work");
                  openWorkspace(0, "enUS");
                  fx(
                      () -> {
                        CaptureAtlas.navigate(stage, "Database");
                        return null;
                      });
                  pause();
                  check(
                      optional("item-row-table") == null && fixture.prepares.get() == 0,
                      "Database navigation prepared rows automatically");
                  inspectItemSource();
                  if (restart) restartJourney();
                  else firstJourney();
                  check(
                      fixture.transportCalls.get() == 0,
                      "A transport-capable gateway operation occurred");
                  check(
                      fixture.activeReaders.get() == 0,
                      "Source read lease leaked after UI requests");
                  Files.writeString(
                      temp.resolve(args[1] + ".assertions"), Integer.toString(assertions));
                  finished = true;
                  System.out.println(
                      "Item UI " + args[1] + " phase OK: " + assertions + " assertions");
                  Platform.exit();
                } catch (Throwable error) {
                  error.printStackTrace();
                  System.exit(1);
                }
              });
      controller.setDaemon(true);
      controller.start();
      Application.launch(AtlasApplication.class);
      require(finished, "Item UI did not finish");
    }
  }

  /** Cleanup permission requires observed process retirement, including interrupted supervisors. */
  private static boolean retire(Process process, long timeoutNanos) {
    if (process == null || !process.isAlive()) return true;
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + timeoutNanos;
    try {
      process.destroyForcibly();
      while (process.isAlive()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return false;
        try {
          if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return !process.isAlive();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      return true;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static void supervisorSelfTest() {
    final class Stub extends Process {
      boolean alive = true, destroyed, interruptOnce, stubborn;
      int waits;

      public java.io.OutputStream getOutputStream() {
        return java.io.OutputStream.nullOutputStream();
      }

      public java.io.InputStream getInputStream() {
        return java.io.InputStream.nullInputStream();
      }

      public java.io.InputStream getErrorStream() {
        return java.io.InputStream.nullInputStream();
      }

      public int waitFor() {
        throw new AssertionError("Unbounded wait");
      }

      public boolean waitFor(long time, TimeUnit unit) throws InterruptedException {
        require(destroyed, "Wait occurred before forced termination");
        waits++;
        if (interruptOnce) {
          interruptOnce = false;
          throw new InterruptedException("fixture");
        }
        if (!stubborn) alive = false;
        return !alive;
      }

      public int exitValue() {
        if (alive) throw new IllegalThreadStateException();
        return 0;
      }

      public void destroy() {
        destroyed = true;
      }

      public Process destroyForcibly() {
        destroyed = true;
        return this;
      }

      public boolean isAlive() {
        return alive;
      }
    }
    var normal = new Stub();
    require(
        retire(normal, TimeUnit.SECONDS.toNanos(1)) && normal.waits == 1,
        "Cleanup allowed without confirmed exit");
    var interrupted = new Stub();
    interrupted.interruptOnce = true;
    require(
        retire(interrupted, TimeUnit.SECONDS.toNanos(1))
            && interrupted.waits == 2
            && Thread.currentThread().isInterrupted(),
        "Interrupted retirement lost wait/interrupt");
    Thread.interrupted();
    var stubborn = new Stub();
    stubborn.stubborn = true;
    require(
        !retire(stubborn, 0) && stubborn.alive && stubborn.destroyed,
        "Unconfirmed worker permitted fixture cleanup");
    System.out.println(
        "Item supervisor self-test OK: confirmed exit, interrupted wait, preserve live store");
  }

  private static void firstJourney() throws Exception {
    check(fixture.prepares.get() == 0, "Retained source inspection prepared projection");
    check(optional("item-prepare") != null, "Explicit prepare action missing");
    check(
        labels().contains("FileDataID 841626") && !labels().contains("59859"),
        "Source file identity/count is misleading");
    shot("item-unprepared-1280x800.png", "item-prepare");
    fixture.holdPreparation.set(true);
    click("item-prepare");
    until(() -> fixture.preparationEntered.get());
    check(
        optional("item-cancel") != null && optional("item-row-table") == null,
        "Preparing exposed ready rows or no cancellation");
    shot("item-preparing-1280x800.png", "item-cancel");
    click("item-cancel");
    fixture.holdPreparation.set(false);
    until(() -> fixture.activeReaders.get() == 0);
    pause();
    check(optional("item-row-table") == null, "Canceled preparation painted staged rows");
    check(
        fixture.readyArtifacts() == 0 && fixture.storedRows() == 0,
        "Canceled fixture prepare left ready/indexed staging");
    shot("item-cancelled-1280x800.png", "item-prepare");
    prepare();
    check(fixture.pages.get() == 0, "Prepare implicitly queried a page");
    browse();
    check(
        rows().size() == 50 && rows().getFirst().recordId() == 1001,
        "First bounded physical page differs");
    assertColumns();
    assertCoverage();
    assertReadyHeader();
    cellProbe("item-cell-probe-before.txt");
    shot("item-page-one-1280x800.png", "item-row-table");
    pause();
    pause();
    cellProbe("item-cell-probe-after.txt");
    fx(
        () -> {
          CaptureAtlas.capture(stage, "item-page-one-after-pulse-1280x800.png", 1280, 800);
          return null;
        });
    click("item-next-page");
    until(() -> tableOptional() != null && tableOptional().getItems().size() == 11);
    pause();
    check(
        rows().getFirst().recordId() == 1051 && rows().getLast().recordId() == 1061,
        "Next page not fixed numeric ID order");
    check(
        fx(() -> ((Button) node("item-next-page")).isDisabled()),
        "Next remained enabled after last physical page");
    shot("item-page-two-1280x800.png", "item-row-table");
    fx(
        () -> {
          var t = table();
          t.requestFocus();
          t.getSelectionModel().selectFirst();
          key(t, KeyCode.ENTER);
          return null;
        });
    pause();
    check(
        fx(() -> stage.getScene().getFocusOwner() == node("item-row-value")),
        "Enter did not inspect physical row");
    check(
        fx(() -> ((TextArea) node("item-row-value")).getText().contains("1051")),
        "Inspector contains another record");
    fx(
        () -> {
          key(node("item-row-value"), KeyCode.ESCAPE);
          return null;
        });
    check(
        fx(
            () ->
                stage.getScene().getFocusOwner() == table()
                    && table().getSelectionModel().getSelectedItem().recordId() == 1051),
        "Escape lost exact row/page selection");
    shot("item-row-inspector-1280x800.png", "item-row-value");
    int queries = fixture.lookups.get();
    TextField input =
        fx(
            () -> {
              var field = (TextField) node("item-record-id");
              field.requestFocus();
              field.setText("100");
              field.positionCaret(3);
              field.appendText("1");
              return field;
            });
    check(fixture.lookups.get() == queries, "Typing made an exact query");
    check(
        fx(
            () ->
                node("item-record-id") == input
                    && stage.getScene().getFocusOwner() == input
                    && input.getCaretPosition() == 4),
        "Typing replaced input or moved caret");
    check(
        optional("item-row-value") == null
            && fx(() -> table().getSelectionModel().getSelectedItem() == null),
        "Draft input kept current selected inspector");
    check(
        labels().contains("Previous browse page"), "Old browse rows masquerade as draft-ID result");
    fx(
        () -> {
          input.fireEvent(new ActionEvent());
          return null;
        });
    until(() -> tableOptional() != null && tableOptional().getItems().size() == 1);
    pause();
    check(
        fixture.lookups.get() == queries + 1 && rows().getFirst().recordId() == 1001,
        "ID Enter did not perform exact physical lookup only");
    fx(
        () -> {
          table().getSelectionModel().selectFirst();
          return null;
        });
    proof();
    exact("999999");
    check(
        labels().toLowerCase(Locale.ROOT).contains("cop")
            && labels().toLowerCase(Locale.ROOT).contains("not indexed"),
        "Known copy declaration became an absent/physical row");
    check(optional("item-row-table") == null, "Excluded copy manufactured a row");
    shot("item-copy-unindexed-1280x800.png", "item-status");
    exact("999998");
    check(
        labels().contains("No match") && labels().contains("physical"),
        "No-match lost physical scope");
    check(optional("item-row-table") == null, "Scoped no-match retained old rows");
    shot("item-scoped-no-match-1280x800.png", "item-status");
    int beforeInvalid = fixture.lookups.get();
    setId("0");
    click("item-lookup");
    pause();
    check(fixture.lookups.get() == beforeInvalid, "Zero Item record ID reached query");
    setId("4294967296");
    click("item-lookup");
    pause();
    check(
        fixture.lookups.get() == beforeInvalid && labels().contains("4294967295"),
        "Invalid unsigned ID reached query or lacks boundary feedback");
    browse();
    scale("200%");
    assertColumns();
    assertCoverage();
    shot("item-page-200pct-1280x800.png", "item-row-table");
    fx(
        () -> {
          table().getSelectionModel().selectFirst();
          return null;
        });
    shot("item-row-inspector-200pct-1280x800.png", "item-row-value");
    int preparesBeforeSwitch = fixture.prepares.get(),
        projectsBeforeSwitch = fixture.projects.get();
    fixture.holdQuery.set(true);
    fixture.queryEntered.set(false);
    click("item-next-page");
    until(() -> fixture.queryEntered.get());
    openWorkspace(1, "enUS");
    fx(
        () -> {
          CaptureAtlas.navigate(stage, "Database");
          return null;
        });
    until(() -> fixture.queryGates.get() == 0);
    fixture.holdQuery.set(false);
    pause();
    check(
        optional("item-row-table") == null && fixture.prepares.get() == preparesBeforeSwitch,
        "Stale page painted or context switch prepared implicitly");
    inspectItemSource();
    prepare();
    browse();
    check(
        fixture.projects.get() == projectsBeforeSwitch && fixture.storedRows() == 61,
        "Second exact snapshot duplicated physical projection");
    openWorkspace(0, "deDE");
    inspectItemSource();
    check(
        optional("item-prepare") == null && optional("item-row-table") == null,
        "Unsupported locale offered prepare or previous rows");
    shot("item-unsupported-locale-200pct-1280x800.png", "item-status");
    openWorkspace(0, "enUS");
    inspectItemSource();
    check(optional("item-row-table") == null, "A→otherlocale→A carried old ready rows");
    check(optional("item-record-id") == null, "Unprepared source carried previous query controls");
    fixture.nextPrepareFailure = ItemProjectionException.Code.INCOMPATIBLE;
    click("item-prepare");
    until(
        () ->
            labels().toLowerCase(Locale.ROOT).contains("incompat")
                || labels().toLowerCase(Locale.ROOT).contains("definition"));
    check(optional("item-row-table") == null, "Incompatible definition displayed old rows");
    shot("item-incompatible-200pct-1280x800.png", "item-status");
    prepare();
    browse();
    check(
        fixture.storedRows() == 61 && fixture.readyArtifacts() == 1,
        "Retry duplicated shared derived rows/artifacts");
  }

  private static void restartJourney() throws Exception {
    check(
        fixture.projects.get() == 0 && fixture.prepares.get() == 0,
        "Restart implicitly prepared rows");
    check(
        fixture.storedRows() == 61 && fixture.readyArtifacts() == 1,
        "Restart lost ready derived artifact");
    prepare();
    check(fixture.projects.get() == 0, "Explicit readiness preparation reprojected shared rows");
    browse();
    check(rows().size() == 50, "Restart bounded page unavailable");
    shot("item-restart-offline-1280x800.png", "item-row-table");
    exact("1001");
    check(rows().getFirst().recordId() == 1001, "Restart exact lookup wrong physical record");
    Path path = fixture.sourcePath();
    byte[] original = Files.readAllBytes(path), bad = original.clone();
    bad[bad.length - 1] ^= 1;
    Files.write(path, bad);
    scale("200%");
    setId("1002");
    click("item-lookup");
    until(() -> optional("item-row-table") == null && optional("item-prepare") != null);
    pause();
    check(!labels().contains("Admitted physical rows ready"), "Corrupt source kept ready proof");
    assertFailureHeader("Item evidence verification failed");
    shot("item-corrupt-offline-200pct-1280x800.png", "item-status");
    fx(
        () -> {
          CaptureAtlas.navigate(stage, "Research");
          return null;
        });
    assertFailureHeader("Item evidence verification failed");
    fx(
        () -> {
          CaptureAtlas.navigate(stage, "Database");
          return null;
        });
    assertFailureHeader("Item evidence verification failed");
    Files.delete(path);
    click("item-prepare");
    until(() -> labels().toLowerCase(Locale.ROOT).contains("missing"));
    check(optional("item-row-table") == null, "Missing physical source kept ready rows");
    assertFailureHeader("Item evidence missing");
    shot("item-missing-offline-200pct-1280x800.png", "item-status");
    check(!Files.exists(path), "Missing source was repaired implicitly");
    check(fixture.projects.get() == 0, "Failed queries automatically reprojected rows");
    check(fixture.transportCalls.get() == 0, "Missing/corrupt case tried transport");
    int prepares = fixture.prepares.get();
    openWorkspace(1, "enUS");
    check(!contextText().contains("Item evidence missing"), "Other build inherited source failure");
    openWorkspace(0, "deDE");
    assertFailureHeader("Item evidence missing");
    openWorkspace(0, "enUS");
    assertFailureHeader("Item evidence missing");
    check(fixture.prepares.get() == prepares, "Context switching prepared source implicitly");
    Files.write(path, original);
    inspectItemSource();
    check(
        !contextText().contains("Item evidence missing")
            && !contextText().contains("Item evidence verification failed"),
        "Fresh explicit retained verification did not clear source failure");
    check(
        !contextText().contains("Item physical rows"),
        "Encoded verification alone established Item projection readiness");
    prepare();
    browse();
    assertReadyHeader();
    shot("item-reverified-offline-200pct-1280x800.png", "item-row-table");
    check(
        fixture.projects.get() == 0 && fixture.transportCalls.get() == 0,
        "Fresh retained verification/reopen reprojected or transferred content");
  }

  private static String contextText() throws Exception {
    return fx(
        () -> {
          Node stripe = stage.getScene().getRoot().lookup(".context-strip");
          if (stripe == null) throw new AssertionError("Persistent context stripe missing");
          return stripe.lookupAll(".label").stream()
              .filter(n -> n instanceof Label)
              .map(n -> ((Label) n).getText())
              .collect(java.util.stream.Collectors.joining(" | "));
        });
  }

  private static void assertReadyHeader() throws Exception {
    String text = contextText();
    check(
        text.contains("Item physical rows")
            && text.contains("copies unindexed")
            && text.contains("Partial Item scope"),
        "Persistent header lost physical-only Item coverage: " + text);
  }

  private static void assertFailureHeader(String reason) throws Exception {
    String text = contextText();
    check(
        text.contains(reason)
            && text.contains("Item scope not verified")
            && !text.contains("Selected encoded file verified")
            && !text.contains("Item physical rows"),
        "Persistent header retained misleading verification: " + text);
  }

  private static void prepare() throws Exception {
    click("item-prepare");
    waitNode("item-record-id");
    check(optional("item-row-table") == null, "Prepare exposed an implicit page");
  }

  private static void browse() throws Exception {
    click("item-browse");
    waitNode("item-row-table");
  }

  private static void exact(String value) throws Exception {
    setId(value);
    click("item-lookup");
    until(() -> optional("item-cancel") == null);
    pause();
  }

  private static void setId(String value) throws Exception {
    fx(
        () -> {
          var field = (TextField) node("item-record-id");
          field.requestFocus();
          field.setText(value);
          return null;
        });
  }

  private static void assertColumns() throws Exception {
    check(
        fx(
            () ->
                table().getColumns().stream()
                    .map(TableColumn::getText)
                    .toList()
                    .equals(
                        List.of(
                            "Item record ID",
                            "ClassID",
                            "SubclassID",
                            "InventoryType",
                            "IconFileDataID"))),
        "Five physical/candidate headers differ");
    check(
        fx(() -> table().getColumns().stream().noneMatch(TableColumn::isSortable)),
        "Unsupported column sorting enabled");
    check(
        fx(
            () -> {
              layout();
              for (var column : table().getColumns()) {
                var header =
                    table().lookupAll(".column-header .label").stream()
                        .filter(n -> n instanceof Label l && l.getText().equals(column.getText()))
                        .findFirst()
                        .orElse(null);
                if (header == null
                    || !(header.lookup(".text") instanceof javafx.scene.text.Text text)
                    || !text.getText().equals(column.getText())) return false;
              }
              return true;
            }),
        "A rendered candidate header is ellipsized or unavailable");
  }

  private static void assertCoverage() throws Exception {
    String text = fx(() -> ((Label) node("item-coverage")).getText());
    check(
        text.contains("61")
            && text.contains("1")
            && text.toLowerCase(Locale.ROOT).contains("cop")
            && text.toLowerCase(Locale.ROOT).contains("physical"),
        "Coverage doesn't distinguish physical rows and excluded copy declarations");
    check(labels().contains("WoWDBDefs"), "Community candidate attribution missing");
  }

  private static void proof() throws Exception {
    fx(
        () -> {
          ((TitledPane) node("item-source-proof")).setExpanded(true);
          return null;
        });
    pause();
    for (String title :
        List.of(
            "1 · Complete encoded object",
            "2 · Consumed decoded chunks",
            "3 · Declared decoded content association",
            "4 · Complete decoded length"))
      check(labels().contains(title), "Missing proof dimension: " + title);
    check(labels().contains("Not established"), "Full decoded CKey implied by ready rows");
    check(
        fx(
            () ->
                textAreas().contains("CC BY-SA-4.0")
                    && textAreas().contains("b9a63096cdd99b26967f17f9515377ae62cadfd7")
                    && textAreas().contains("Definition SHA-256")),
        "Pinned definition/license/digest not copyable");
    shot("item-source-proof-1280x800.png", "item-source-proof");
    fx(
        () -> {
          reveal(
              CaptureAtlas.accessible(
                  stage, "Community candidate definition attribution and pinned interpretation"));
          return null;
        });
    pause();
    fx(
        () -> {
          CaptureAtlas.capture(stage, "item-schema-attribution-1280x800.png", 1280, 800);
          return null;
        });
  }

  private static void cellProbe(String name) throws Exception {
    check(
        fx(
            () -> {
              layout();
              var cells =
                  table().lookupAll(".table-row-cell").stream()
                      .filter(n -> n instanceof TableRow<?> r && !r.isEmpty() && r.getIndex() < 2)
                      .flatMap(n -> n.lookupAll(".table-cell").stream())
                      .filter(
                          n ->
                              n instanceof TableCell<?, ?> c
                                  && c.getTableColumn() != null
                                  && c.getTableColumn().getText().equals("ClassID"))
                      .toList();
              return cells.size() == 2
                  && cells.stream()
                      .allMatch(
                          n -> {
                            TableCell<?, ?> cell = (TableCell<?, ?>) n;
                            var texts =
                                cell.lookupAll(".text").stream()
                                    .filter(
                                        t ->
                                            t instanceof javafx.scene.text.Text v
                                                && v.isVisible()
                                                && !v.getText().isEmpty())
                                    .toList();
                            return texts.size() == 1
                                && ((javafx.scene.text.Text) texts.getFirst())
                                    .getText()
                                    .equals(cell.getText())
                                && cell.getGraphic() == null;
                          });
            }),
        "Visible ClassID cells contain duplicated text/graphics");
    String text =
        fx(
            () -> {
              layout();
              StringBuilder out = new StringBuilder();
              table().lookupAll(".table-row-cell").stream()
                  .filter(n -> n instanceof TableRow<?> r && !r.isEmpty() && r.getIndex() < 6)
                  .sorted(Comparator.comparingInt(n -> ((TableRow<?>) n).getIndex()))
                  .forEach(
                      row -> {
                        out.append("ROW ")
                            .append(((TableRow<?>) row).getIndex())
                            .append(" ")
                            .append(row.localToScene(row.getLayoutBounds()))
                            .append('\n');
                        row.lookupAll(".table-cell").stream()
                            .filter(
                                n -> n instanceof TableCell<?, ?> c && c.getTableColumn() != null)
                            .forEach(
                                n -> {
                                  TableCell<?, ?> cell = (TableCell<?, ?>) n;
                                  out.append(" CELL ")
                                      .append(cell.getTableColumn().getText())
                                      .append(" text=")
                                      .append(cell.getText())
                                      .append(" bounds=")
                                      .append(cell.localToScene(cell.getLayoutBounds()))
                                      .append(" graphic=")
                                      .append(cell.getGraphic())
                                      .append('\n');
                                  for (Node t : cell.lookupAll(".text"))
                                    if (t instanceof javafx.scene.text.Text value)
                                      out.append("  TEXT ")
                                          .append(value.getText())
                                          .append(" visible=")
                                          .append(value.isVisible())
                                          .append(" bounds=")
                                          .append(value.localToScene(value.getBoundsInLocal()))
                                          .append('\n');
                                });
                      });
              return out.toString();
            });
    Files.writeString(CaptureAtlas.output.resolve(name), text);
  }

  private static void inspectItemSource() throws Exception {
    fx(
        () -> {
          CaptureAtlas.navigate(stage, "Archive");
          ((Button) node("open-file-lookup")).fire();
          ((TextField) node("file-id-input")).setText("841626");
          ((Button) node("file-reopen-evidence")).fire();
          return null;
        });
    waitNode("file-variant-table");
    fx(
        () -> {
          ((TableView<?>) node("file-variant-table")).getSelectionModel().selectFirst();
          return null;
        });
    waitNode("selected-retention-offline");
    click("selected-retention-offline");
    until(() -> labels().contains("Complete encoded bytes verified"));
    fx(
        () -> {
          CaptureAtlas.navigate(stage, "Database");
          return null;
        });
    pause();
  }

  private static void openWorkspace(int buildIndex, String locale) throws Exception {
    fx(
        () -> {
          var w =
              picker().getItems().stream()
                  .filter(
                      v ->
                          v.build()
                                  .snapshotId()
                                  .equals(fixture.builds().get(buildIndex).snapshotId())
                              && v.locale().equals(locale))
                  .findFirst()
                  .orElseThrow();
          picker().setValue(w);
          return null;
        });
    pause();
  }

  @SuppressWarnings("unchecked")
  private static ComboBox<Workspace> picker() {
    return (ComboBox<Workspace>) node("saved-workspace-picker");
  }

  @SuppressWarnings("unchecked")
  private static TableView<ItemPhysicalRow> table() {
    return (TableView<ItemPhysicalRow>) node("item-row-table");
  }

  private static TableView<?> tableOptional() {
    Node n = optional("item-row-table");
    return n instanceof TableView<?> t ? t : null;
  }

  private static List<ItemPhysicalRow> rows() throws Exception {
    return fx(() -> List.copyOf(table().getItems()));
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

  private static void key(Node n, KeyCode code) {
    n.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
  }

  private static void scale(String value) throws Exception {
    fx(
        () -> {
          CaptureAtlas.button(stage, "Settings").fire();
          CaptureAtlas.setCombo(CaptureAtlas.accessible(stage, "Application text scale"), value);
          CaptureAtlas.navigate(stage, "Database");
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
        return fx(CaptureItemProjectionWorkflow::labels);
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
    var f = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            f.complete(call.call());
          } catch (Throwable t) {
            f.completeExceptionally(t);
          }
        });
    return f.get(10, TimeUnit.SECONDS);
  }

  private static void until(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!fx(condition::getAsBoolean)) {
      require(System.nanoTime() < end, "Timed out waiting for Item UI state");
      Thread.sleep(50);
    }
  }

  private static void waitNode(String id) throws Exception {
    until(() -> optional(id) != null);
    pause();
  }

  private static void pause() throws InterruptedException {
    Thread.sleep(350);
  }

  private static void check(boolean ok, String message) {
    assertions++;
    require(ok, message);
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new IllegalStateException(message);
  }

  private static void requireDisplay(String value) {
    require(
        value.matches(":[1-9][0-9]*") && value.equals(System.getenv("DISPLAY")),
        "Explicit nonzero isolated DISPLAY required");
  }
}
