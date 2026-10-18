# One-row Item evidence export native workflow

The twelfth native workflow is `tools/ui/CaptureItemEvidenceExportWorkflow.java`.
Immutable packaged runtime **`87b090c`**, with no desktop overlay, passed
**177 assertions per font profile across two application JVMs, with zero HTTP
attempts**. Both Linux Noto and CI-like DejaVu runs passed 100%/200% text scaling.
Each produced **16 PNGs, ten authored JSON files and eleven expectation entries**;
the independent native JSON oracle passed both output sets. These are native
workflow results, not an inferred full Java-suite total for this revision.

The workflow exercises Item-only default and explicitly paired evidence, actual
local snapshot verification, four raw/candidate fields, physical Unicode and
valid empty name candidates, unindexed copies and scoped nonmatches. Destination
selection grants no publication consent. Final path-bound replacement consent
precedes actual atomic JSON publication over an authored existing target.
Missing-directory failure preserves the immutable preview for an explicit retry;
missing paired evidence clears its current preview without silently downgrading
scope or discarding independent Item readiness. Restart uses the real retained
catalog and explicitly prepared pair, with no new projection during export.

Native checks include modal Tab/Escape and return focus, no default Enter save,
actual 14px/28px label fonts, intrinsic header height, and all six final-consent
controls together inside the viewport with the unchanged one-pixel tolerance.
The full 2 MiB ceiling, exact path viewport, Copy/Choose, authorization and Save
remain readable together. An earlier strengthened Noto probe on `73c204a` found
Save clipped by 9px; the wrapping Copy/Choose row in `87b090c` passed the same
stronger assertion in both fonts. No font or tolerance was reduced.

Primary references use the Noto scenes. Representative views are
[scope review](export-review-100pct.png),
[copyable source proof](export-paired-proof-100pct.png),
[complete 200% consent](export-consent-200pct.png),
[missing paired evidence](export-paired-source-missing-200pct.png),
[uncertain publication](export-commit-uncertain-100pct.png) and
[restart receipt](export-restart-saved-100pct.png).

The fixture uses authored Item/Sparse bytes with real retained readers, pinned
schemas, CAS, SQLite indexes, `SqliteItemEvidenceSnapshotBackend`,
`DefaultItemEvidenceService` and `AtomicItemEvidenceWriter`. Root/config/EN
associations use an explicitly synthetic proof port. This is not live game data,
official TACT authentication, complete decoded CKey verification or a
self-authenticating Blizzard proof package. Only disposable fixture storage and
authored export destinations are opened.

## Reproduction

Build the installed development distribution, then compile all native tools and
fixtures together. Keep the distribution and helper classes unchanged during a
run. Start an isolated X display using the procedure in
[the portable tool instructions](../../../tools/ui/README.md).

```bash
./gradlew :bootstrap:installDist
mkdir -p build/ui-tool
javac -cp 'bootstrap/build/install/bootstrap/lib/*' -d build/ui-tool \
  tools/ui/*.java tools/ui/fixtures/*.java
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureItemEvidenceExportWorkflow --isolated-display :191 \
  --output build/ui-review/item-evidence-export
```

The supervisor starts two application JVMs against one disposable retained
catalog. Each worker has a 155-second application deadline and a 180-second
supervisor limit. Cleanup requires observed child retirement; otherwise the
store is preserved. Successful authored JSONs and their independently selected
expected ID/name states are copied to `output/exports` before temporary storage
is removed. Java checks actual byte bounds and receipt length/SHA256; independent
JSON/schema parsing is a separate explicit step.

## Independent JSON validation

Use the Python environment described in [the export oracle instructions](../../../tools/exports/README.md).
The CI environment is `build/export-validator/bin/python`; the Java harness has
no Python dependency. Run the independent native oracle against the output
folder, not its `exports` child:

```bash
build/export-validator/bin/python tools/exports/native_oracle.py \
  build/ui-review/item-evidence-export
```

The oracle imports the strict UTF-8/duplicate-key parser and local Draft 2020-12
schema validator. It requires exactly ten authored JSON files and eleven expected
entries across the two JVMs. Its fixed case/name and four integer field
expectations are independent of both the exported JSON and harness manifest.
All four fields intentionally read the same authored byte; their candidate
signs/widths differ. Checks cover exact ID, physical position, raw hex/width,
physical interpretation, candidate sign/width/value, source FileDataIDs, exact
Unicode/valid empty names, excluded copies and scoped nonmatches. Schema validity
and these fixture checks do not authenticate the commitments.

## Boundaries

Destination selection is an injected chooser port using authored temporary
paths. This checks the application review/consent/save flow and actual local
atomic writer, not an operating-system chooser or every filesystem provider.
Pre-capture and pre-write gates are explicit scheduling instrumentation around
the real service/backend/writer. Their cancellation cases do not establish
interruption of an already running SQLite/source scan. A post-commit gate delays
delivery after actual atomic publication so a cancellation/context-change race can still end saved.
A deliberately lost receipt after actual commit exercises an uncertain UI
outcome; it does not certify native provider failure, crash recovery or durability.

Scene captures and programmatic keyboard events establish the observed Linux
font/scale behavior only. Physical keyboards, assistive technology, other
platforms, live sources and arbitrary provider atomicity require separate checks.
