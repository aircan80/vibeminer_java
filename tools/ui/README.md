# Atlas screenshot review tool

`CaptureAtlas.java` launches the real JavaFX interface with synthetic discovery fixtures, opens Research and Archive, and writes baseline PNGs at 1440×1100 and 1280×800, plus selected-workspace and 200% text-scale images. An empty in-memory workspace service preserves the same saved-workspace bar as a fresh production launch; unused writes fail explicitly. The real SQLite persistence check is separate below. At 1280×800, the first fixture build row and primary metadata action must be visible above the footer in the initial Archive viewport.

It first verifies an empty official product picker and zero automatic discovery calls. An injected synthetic catalog then tests explicit product refresh, current-version capability guards for CDN-only/preload-only/unknown pointers, typed filtering, visible match counts and no-result recovery. It verifies the build refresh actually invokes its injected service rather than silently clicking a disabled control.

It also asserts Enter opens the selected source dialog, closing it restores table focus, multi-character queries survive navigation, text-scale preference survives navigation, and source controls survive asynchronous failure. The key event is programmatic; this is not physical keyboard or assistive-technology testing.

It makes no network requests and captures only its own JavaFX scene, never a desktop screenshot. These images demonstrate rendering, not mining functionality, accessibility certification or installer validation.

On Linux, use a full JDK 25, the project's Gradle build and an isolated Xvfb display. Do not point this tool at an existing user desktop. The explicit display argument must match `DISPLAY`. Output belongs in an ignored build directory.

```bash
set -e
./gradlew :bootstrap:installDist
mkdir -p build/ui-tool build/ui-review
javac -cp 'bootstrap/build/install/bootstrap/lib/*' -d build/ui-tool tools/ui/CaptureAtlas.java
Xvfb :191 -screen 0 1440x1100x24 -nolisten tcp -ac > build/ui-review/xvfb.log 2>&1 &
VIBEMINER_XVFB_PID=$!
trap 'kill "$VIBEMINER_XVFB_PID"' EXIT
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureAtlas --isolated-display :191 --output build/ui-review
```

Choose an unused display number and update both places if `:191` is occupied. `-ac` is appropriate only for a temporary isolated local review server; TCP listening is disabled. The JDK must include `java.desktop` for PNG encoding. The harness uses application fixture controls and waits for JavaFX layout pulses before capture; a changed navigation contract or wrong image dimensions fails visibly. The rendering sequence has an overall process deadline; do not treat missing PNGs or an abnormal exit as a pass.

Current platform target for this helper is Linux/X11. Cross-platform application packaging is checked separately by the Gradle distribution tasks and native CI. The helper uses the installed development distribution, not the trimmed packaged runtime.

## Persisted workspace review

`CaptureWorkspaces.java` tests metadata persistence through **two separate application JVM launches** using the real embedded SQLite adapter. It creates a temporary database, saves two synthetic locale workspaces through the interface, exits, then starts a second application against that database. It deletes the temporary database after the run and never opens the normal user catalog.

It asserts workspace and raw-source query/draft isolation, restoration when switching back, offline metadata reopen with zero discovery calls, inspected locale independence from the active locale, invalid/valid rename behavior, cancellation of removal, and removal of only the selected metadata membership. Session queries/drafts are checked across switches; they are not claimed to persist across process restarts. Screenshots include the save dialog, saved catalog, reopened workspace and 200% scaling. Programmatic controls and key events do not replace assistive-technology or physical keyboard review.

After building the installed development distribution and starting an isolated display as above:

```bash
set -e
javac -cp 'bootstrap/build/install/bootstrap/lib/*' -d build/ui-tool tools/ui/*.java tools/ui/fixtures/*.java
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureWorkspaces --isolated-display :191 --output build/ui-review/workspaces
```

Each child has a 45-second failure deadline; the supervisor also enforces a 60-second process limit. A successful run prints `SQLite UI restart contracts OK`; missing screenshots or a nonzero exit fail the review. The final removal is confined to the disposable test database. Finish all application/build changes and run `:bootstrap:installDist` before capturing; do not rebuild distribution jars while the review tool is compiling or running.


## Retained install-list workflow

`CaptureInstallWorkflow.java` uses two separate JVM launches with the real bootstrap service, configuration gateway, SQLite catalog, CAS, BLTE decoder and install-list parser. Its helper in `fixtures/InstallFixtureBackend.java` supplies deterministic synthetic bytes through the package-private transport seam; no production API is widened and no HTTP request is made. The temporary catalog and objects are separate from user data and deleted after the run.

Compile all helpers with the command above, then run on the same isolated display:

```bash
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureInstallWorkflow --isolated-display :191 --output build/ui-review/install-workflow
```

The first process verifies that checking configuration transfers no install content, shows encoded/decoded byte cost before a separate Download action, and handles unavailable content, cancellation and workspace changes without retaining a false result. It inspects actual decoded names, tags, filters and source evidence, including programmatic Enter/Escape focus transfer and focus retention while typing a filter. The second process reopens retained bytes without any transport calls, checks workspace isolation, then corrupts the retained object and verifies that failed local integrity checking clears the verified list and coverage without attempting repair over the network. Metadata-only and install-list-verified coverage remain distinct; game files are never acquired.

Thirteen scene-only PNGs include 1280×800 and 200% text scaling. Cost, entry and detail captures deliberately scroll the application body to the inspected section; they do not imply that every section fits simultaneously. Each child has a 90-second application deadline and a 120-second supervisor deadline. A successful run prints `Install UI workflow OK; real services/CAS/SQLite; two JVMs; offline transport forbidden`. Missing images or abnormal termination fail the review. These synthetic Linux rendering checks do not certify live CDN compatibility, other operating systems or assistive technology.
