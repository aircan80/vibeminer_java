# Atlas Item name-candidate native acceptance

The tenth native workflow for [#21](https://github.com/aircan80/vibeminer/issues/21) passed **63 assertions across two JVMs** against source `8ccbd42`, with 21 screenshots at 1280×800 and 100%/200% text scale. The first worker passed 46 assertions and the restart worker passed 17. Screenshots and interaction assertions exercise the actual JavaFX controls, not the HTML mockup.

This accepts a bounded **Item-anchored physical name-candidate view**. It does not establish live-data acceptance, full decoded table CKeys, name-text search, all Item/Sparse sections, resolved copy rows, complete item semantics, screen-reader conformance or cross-platform native acceptance.

## What the workflow establishes

- Item physical rows are explicitly prepared first. Missing Sparse offers navigation seeded with FileDataID `1572924`; that navigation starts no lookup, acquisition or names preparation. Selecting and verifying Sparse preserves the independent exact-context Item handle.
- Paired names preparation is a separate retained-only action. Cancellation exposes no staged ready page. Source/context changes suppress late results and require explicit preparation for the current pair.
- The fixture contains 61 physical Item anchors. Bounded numeric-ID browsing returns 50 rows followed by 11; missing names do not remove Item rows. The prepared name artifact stores 22 physical name candidates separately.
- Physical Unicode text, a valid empty name, an unindexed Sparse copy, a scoped Sparse nonmatch, an excluded Item copy and a Sparse-only record have distinct outcomes. Sparse-only content never fabricates an Item row.
- Exact positive Item record IDs are distinct from source-table FileDataIDs. Invalid IDs make no query. Draft editing preserves input focus and clears the current inspector; Enter looks up the ID, table Enter focuses full detail, and Escape returns to the table.
- At 100% the table shows Item record ID, Display candidate and Name status. At 200% it uses two columns with status inside the candidate cell. Full Unicode/empty text, four Item integer candidates and raw bits remain copyable in the inspector.
- Candidate provider attribution is visible beside current scope. Both source proofs and pinned interpretation evidence remain separately inspectable. Historical preparation receipts do not become fresh full-decoded attestations after a query.
- A fresh JVM reopens app-owned SQLite/CAS state with zero transport calls. Explicit preparation may repeat bounded source work to establish current proof; subsequent page/lookup calls do not reproject names. Shared artifact and stored-row counts remain unchanged.
- Missing Sparse clears paired readiness while independent Item browsing remains usable. A corrupt pair clears current names/results without guessing which physical source failed. No automatic repair or network fallback follows.

## Evidence boundary

[CaptureItemNamesWorkflow.java](../../../tools/ui/CaptureItemNamesWorkflow.java) uses the portable [authored fixture](../../../tools/ui/fixtures/ITEM-NAMES.md): actual retained Item/Sparse readers, pinned definition handling, derived indexes, application services, temporary SQLite and the filesystem content store. Root/configuration/EN association semantics are supplied by an explicitly synthetic proof port. The fixture is not official TACT authentication, actual Blizzard row content, a production catalog or a live request.

The supplied root/EN and acquisition receipts are authored test evidence. The fixture rejects transport-capable operations and checks zero outbound calls after both workers. Ordinary fixture success cannot upgrade either table to complete decoded-CKey verification. Missing/corrupt source tests invalidate current joined readiness without replacing original receipts.

The 21 frames include preparation/cancellation, first/next pages, Unicode and empty values, both copy scopes, no Item match, both source proofs, unsupported locale, 200% rows/inspector, restart, missing Sparse and corrupt-pair outcomes. Some captures deliberately scroll to the inspected controls or evidence. They do not imply all evidence fits in one viewport.

## Reproducing the native workflow

Build a coherent distribution first. Use a full JDK matching the project toolchain and the supported host's JavaFX libraries. The capture tool is developer validation, not a shipped application dependency.

```bash
./gradlew :bootstrap:installDist
mkdir -p /tmp/vibeminer-item-names-capture-classes
javac -cp 'bootstrap/build/install/bootstrap/lib/*' \
  -d /tmp/vibeminer-item-names-capture-classes \
  tools/ui/CaptureAtlas.java \
  tools/ui/CaptureItemNamesWorkflow.java \
  tools/ui/fixtures/ItemProjectionFixtureBackend.java \
  tools/ui/fixtures/ItemNamesFixtureData.java \
  tools/ui/fixtures/ItemNamesFixtureBackend.java
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp '/tmp/vibeminer-item-names-capture-classes:bootstrap/build/install/bootstrap/lib/*' \
  CaptureItemNamesWorkflow --isolated-display :191 \
  --output /tmp/vibeminer-item-names-captures
```

`:191` must be an already managed, isolated nonzero display whose dimensions support the capture. The supervisor starts two separate application JVMs and only removes its temporary fixture store after observing worker retirement. It does not create or take ownership of a display. Keep the compiled helpers and all application libraries from the same reviewed source; mixing stale libraries invalidates the evidence.

The accepted run used the provisioned JDK 25 toolchain and a Linux Xvfb display. JavaFX event/focus assertions are not physical-keyboard or assistive-technology testing. Other operating systems and accessibility tools require their own validation.
