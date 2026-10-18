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

## Exact FileDataID lookup workflow

`CaptureFileLookupWorkflow.java` runs two separate JVMs with the real file-lookup application service, official-source gateway, SQLite catalog, CAS, indexed EN parser, BLTE decoder and streaming root parser. `fixtures/FileLookupFixtureBackend.java` generates independent synthetic EN/root/configuration bytes and injects them through the existing package-private transport seam. It never uses a game installation or sends HTTP, and its temporary catalog/content directory is removed after both processes exit.

Compile all helpers as above, then run:

```bash
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureFileLookupWorkflow --isolated-display :191 --output build/ui-review/file-lookup
```

The first process verifies visible FileDataID restoration across saved A42 → B7 → A42, and verifies that the real service receives the same ID. Programmatic input Enter checks requirements without authorizing root acquisition. All returned candidates remain explicit: no default is selected and an unknown transfer size disables Download. Known encoded/decoded cost and the separate Download action must fit together at both 100% and 200% text scale. Switching exact workspaces cancels an in-flight root request and suppresses its stale result.

After explicit retention, both synthetic root variants for ID42 remain visible, raw fields and unavailable filenames remain honest, Enter focuses full detail and Escape returns to the table. Source evidence shows the actual retained root separately from configuration proof. ID999 produces only scoped absence after complete verification. The second JVM reopens actual retained bytes with zero transport attempts, verifies exact-workspace membership isolation, then corrupts the physical root while a verified result exists and confirms that local verification failure removes ready coverage and variants without network repair.

Fifteen scene-only PNGs include known/unknown cost, variants, raw detail, source proof, absence, and offline corruption. Screenshots deliberately scroll to the relevant inspected section. Each child has a 90-second application deadline and a 120-second supervisor deadline. Success prints `File lookup UI workflow OK; real services/CAS/SQLite; two JVMs; offline transport forbidden`. These checks use programmatic actions and key events; they do not certify physical keyboard, screen-reader, other-platform or live-CDN compatibility. Metadata lookup never acquires the selected game's file contents, filenames or item records.

## Selected encoded-file presentation

`CaptureSelectedFileRetention.java` runs two application JVMs and checks the Atlas staged-retention interface. It reuses the real synthetic root fixture, CAS and SQLite workspace storage. **The selected-file service is a fake:** candidate, location, search and receipt projections are deterministic fixtures. Small disposable sidecar files simulate its retained association and payload state across restart. Their hash checks exercise UI outcomes; they are not production BLTE verification or selected-file catalog persistence evidence. The separate backend journey in [the acquisition specification](../../docs/FILE-ACQUISITION.md) records those integration gates.

After `:bootstrap:installDist`, compile the helpers as above and use an isolated display:

```bash
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureSelectedFileRetention --isolated-display :191 --output build/ui-review/selected-retention
```

The first child checks explicit candidate selection and keyboard inspection, a separate locator action, a typed404 permit followed by separately approved archive search, incomplete-search wording, an initially unknown encoding size resolved to a known acquisition plan, cost/action geometry at 100% and 200%, direct and archive routes, explicit retention, zero-transfer reuse with unchanged original traffic evidence, blocked canonical conflict, cancellation and stale-ID suppression. It does not send HTTP or acquire real game content.

The second child starts with only a retained root variant and no candidate session. Its explicit offline action must call the exact-variant service API without candidate, locator, search or retention calls. Simulated missing membership, missing bytes and corrupt bytes remain distinct; verification failure clears the ready header and preserves a usable keyboard retry action. The harness removes its temporary directory after both children finish. It never opens the normal user catalog.

Screenshots deliberately scroll to the inspected scope, cost, action or evidence. The child deadline is 120 seconds and the supervisor allows 150 seconds per child. Success prints `Selected retention UI OK` and explicitly identifies the fake selected service. Programmatic keyboard/scene checks establish Linux fixture presentation behavior; they do not certify live sources, production selected-file integrity, other platforms, assistive technology, decoded content or filenames.

## Retained Item physical-row workflow

`CaptureItemProjectionWorkflow.java` and `fixtures/ItemProjectionFixtureBackend.java` run two application JVMs with authored 61-row WDC5/BLTE data, the actual retained Item reader, pinned schema, application service, CAS and SQLite projection. Root/EN authentication and root-membership presentation are explicit synthetic proof-port fixtures. They do not establish official TACT authentication, use actual game records or send HTTP. No game installation or normal user catalog is opened.

Compile all helpers as above, then run:

```bash
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureItemProjectionWorkflow --isolated-display :191 --output build/ui-review/item-projection
```

The first child checks explicit retained-only preparation/cancellation, bounded pages, exact positive Item record IDs, all five columns, Enter/Escape inspection, stable input/caret, scoped no-match versus excluded copies, four proof dimensions, pinned definition attribution, stale-query suppression and workspace/locale separation. The second child reopens the actual retained index without reprojection, corrupts/removes its physical encoded source and checks that rows and persistent readiness clear without repair. Missing/corrupt notices survive navigation and locale changes but do not transfer to another exact build. Fresh explicit retained verification clears the failure; explicit preparation is still required before Item physical scope is ready.

The final run against source `1cea99f` passed **85 assertions across two JVMs**, produced 19 scene-only PNGs and attempted zero HTTP. [Eighteen accepted references](../../docs/design/item-projection/README.md) omit the diagnostic after-pulse image. Both 100% and 200% text scaling are covered. Actual full decoded CKey verification, complete Item coverage, names/icons, live sources, other platforms and assistive technology are outside this evidence.

Each child has a 120-second application deadline; the supervisor allows 150 seconds per child. On timeout or interruption it forcibly stops the active child and waits up to ten seconds for confirmed termination before deleting temporary storage. Unconfirmed termination preserves the store. Its deterministic no-JavaFX supervisor regression is available separately:

```bash
java -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureItemProjectionWorkflow --supervisor-self-test
```

This checks confirmed retirement, interrupted waiting with restored interruption, and refusal to authorize cleanup while a worker remains alive. It requires no display. The native run uses programmatic keyboard events and scene checks; it does not certify physical keyboard or screen-reader operation.

## Literal Item display-candidate search workflow

`CaptureItemNameSearchWorkflow.java` is the eleventh native workflow. It uses the real retained Item/Sparse readers, pinned definitions, application services, CAS and SQLite indexes with the explicitly synthetic `LITERAL_SEARCH` fixture corpus. Compile **all tools and fixtures together** using the `javac` command above after `:bootstrap:installDist`; keep the distribution and compiled classes unchanged during capture.

```bash
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureItemNameSearchWorkflow --isolated-display :191 --output build/ui-review/item-name-search
```

Immutable source `c17ec25` passed **109 assertions (92 first process + 17 restart), two JVMs and zero HTTP attempts**, producing **21 PNGs per font profile**. Both Linux Noto and CI-like DejaVu runs covered 100%/200% text scaling, literal search and continuation, inspection, Clear without a query, explicit Browse/exact ID, input rejection, cost/action visibility and offline restart.

[Acceptance evidence and reproduction details](../../docs/design/item-name-search/README.md) define the clipboard and cancellation boundaries: paste checks the string exposed by the native clipboard API, which may already have undergone platform conversion; held cancellation/stale tests delay admission before the real search service and do not establish SQLite worker retirement. The same document records the synthetic proof scope and remaining platform/accessibility limits.
