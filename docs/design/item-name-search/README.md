# Atlas literal Item-name search: native acceptance

The eleventh native workflow for [#89](https://github.com/aircan80/vibeminer/issues/89) passed **109 assertions per font profile** against immutable runtime source **`c17ec25`**. Each run used two application JVMs: 92 assertions in the first worker and 17 after restart, with zero outbound HTTP calls. Both Noto and CI-like DejaVu profiles passed at 1280×800 and 100%/200% text scale. Application libraries came from the clean distribution; no desktop class overlay was used for these final runs.

The [literal-search contract](../../ITEM-NAME-SEARCH.md), [paired names scope](../../ITEM-NAMES.md) and [physical Item scope](../../ITEM-PROJECTION.md) remain authoritative. This acceptance adds literal search over prepared, admitted physical name candidates. It does not establish whole-build coverage, live game names, resolved copies, full decoded-table CKeys, linguistic search, screen-reader conformance or native acceptance on every platform.

## Accepted behavior

- Search is explicit. Preparing or selecting sources, typing and clearing do not search or acquire content. Search scans attributed physical Display candidates joined to physical Item anchors; copy, empty/unavailable and Sparse-only names do not become matches.
- The additive authored literal-search corpus has 61 Item anchors and 60 physical Sparse names, including 58 nonempty joined `Authored` matches. The workflow displays a bounded first page of 50 matches and continues through the same accepted query. The original browse fixture remains unchanged.
- A Greek `α` search finds the authored Unicode candidate, which is fully copyable. Native inputs preserve LF, tab, spaces-only text, NFC/NFD sequences, supplementary scalars and literal punctuation. The service wrapper records the exact submitted query; domain/backend semantic oracles provide the broader matching evidence.
- Clear returns to Browse mode without a query or implicit page. Explicit Browse and exact Item-ID lookup remain independent. Editing a literal draft clears the current inspector, labels retained results as previous and disables search continuation under a different draft. Expanded details retain the exact executed query and generation separately from the draft.
- Enter searches; Shift+Enter inserts exactly one LF without searching. Tab inserts a literal tab and Ctrl+Tab moves to the Search action. Table Enter opens the full inspector and Escape returns to the table. These are JavaFX event/focus assertions, not physical-keyboard or general IME certification.
- Unsupported editor input produces a field-adjacent error and blocks literal Search/continuation until a valid edit or Clear. Exact-ID and Browse actions retain their separate purpose.
- At 200%, full table headers, Unicode inspector, current exact source and partial paired coverage remain readable. The entire local-scan disclosure and both Search/Clear buttons fit together inside the workspace viewport with the same one-pixel geometry tolerance under both fonts. The editor itself has a separate capture; a long workbench need not fit in one frame.
- Both original source proofs and pinned candidate attribution stay inspectable. Preparation timestamps remain historical; successful queries do not invent a new full-decoded attestation.
- A fresh JVM prepares the retained pair explicitly and searches offline. Missing Sparse revokes paired results/readiness while independent Item browsing remains available. No automatic repair or network fallback follows.

## Clipboard and cancellation boundaries

The native clipboard API's exposed string is the paste ingress boundary. In the tested Linux environment, supplying CRLF returned LF **before** VibeMiner received the clipboard string. The workflow checks that VibeMiner inserts and, only after explicit Search, submits the exact exposed string. It does not attribute that platform conversion to the application or claim to detect removed characters.

Unsupported characters still present in the clipboard string are rejected and latch Search. Independently, raw replacement tests pass CRLF, NUL, other C0 characters and DEL directly to the product editor and require pre-filter rejection, an unchanged draft and no search of the previous value. Controlled programmatic restoration is guarded. Arbitrary external calls to JavaFX's final `TextArea.setText` setter are not a supported product input API or an interception guarantee.

The native cancellation/context tests hold requests in a recording service wrapper **before admission to the real search service**, then exercise cancellation and stale-context suppression. They establish presenter/facade admission behavior. They do not establish cancellation of an already running SQLite scan or retirement of an ignored late backend callback; backend cancellation, source leases and integrity-scan behavior have separate tests and profiling evidence. Every completed search page in this native workflow comes from the actual application service and retained backend.

## Fixture and evidence scope

[CaptureItemNameSearchWorkflow.java](../../../tools/ui/CaptureItemNameSearchWorkflow.java) uses the portable [Item/Sparse fixture](../../../tools/ui/fixtures/ITEM-NAMES.md) with `Corpus.LITERAL_SEARCH`. It exercises real readers, pinned definition handling, application services, checksummed derived indexes, temporary SQLite and the filesystem content store. Root/configuration/EN association semantics are supplied by an explicitly synthetic proof port. The authored acquisition receipts are test evidence, not official TACT authentication or live Blizzard content.

Each font run produces 21 PNGs covering search/continuation, Unicode inspection, previous-query details, rejected ingress at both scales, scan/actions geometry, cancellation, restart, scoped no-match and missing paired evidence. Some frames deliberately scroll to inspected controls or proofs. The Noto frames are the primary visual references; the independent DejaVu repeat checks different font metrics and wrapping.

The native workflow exposed a real Shift+Enter shortcut gap; the product now inserts LF explicitly through the existing guarded replacement path. Earlier harness failures caused by platform clipboard conversion and an omitted explicit inspector selection were corrected as test setup/boundary issues, without relaxing actual input rejection or query-scope checks.

## Reproduce

Use a coherent distribution from the reviewed source and a full JDK matching the project toolchain. Capture helpers are developer tooling, not runtime dependencies of the shipped application.

```bash
./gradlew :bootstrap:installDist
mkdir -p /tmp/vibeminer-literal-capture-classes
javac -cp 'bootstrap/build/install/bootstrap/lib/*' \
  -d /tmp/vibeminer-literal-capture-classes \
  tools/ui/CaptureAtlas.java \
  tools/ui/CaptureItemNameSearchWorkflow.java \
  tools/ui/fixtures/ItemProjectionFixtureBackend.java \
  tools/ui/fixtures/ItemNamesFixtureData.java \
  tools/ui/fixtures/ItemNamesFixtureBackend.java
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp '/tmp/vibeminer-literal-capture-classes:bootstrap/build/install/bootstrap/lib/*' \
  CaptureItemNameSearchWorkflow --isolated-display :191 \
  --output /tmp/vibeminer-literal-captures
```

The display must already be managed and isolated, with a nonzero identifier. The supervisor starts two workers and removes its temporary store only after observing their retirement; it does not create or own the display. Separate font runs require independently configured font environments, displays and output directories. Keep compiled helpers and application libraries from the same reviewed source throughout each run.
