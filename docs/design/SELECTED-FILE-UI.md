# Selected-file Atlas interface

Design contract for the staged workflow in [Selected encoded-file acquisition](../FILE-ACQUISITION.md). This document specifies presentation, not delivered capability. Existing lookup remains metadata-only until the selected-file service and its acceptance gates are implemented. No filenames, previews, readable records or decoded verification are fabricated.

## Placement and context

Extend **Archive → Find file**, below the selected verified root variant, with **Retain encoded file**. Keep the current FileDataID lookup and all returned variants intact. Selecting a row inspects it; it never downloads or auto-selects an encoding. Workspace locale is a view preference, not an implicit variant selector.

The persistent context shows edition/product, channel, region, exact build and locale. The local panel repeats FileDataID and selected locale/content flags with the declared content identity available in full. Raw flag components, optional name hash, root/configuration identities and source verification expand under **Source and verification evidence**. Names remain “Filename unavailable” unless an identified provider supplies one. A name hash is not a filename.

Keep Atlas ivory/teal surfaces, serif section headings, 14 px body, >=12 px secondary text, 8 px spacing rhythm and 16–20 px panel padding. Avoid a second oversized page hero. Use one active stage panel and progressively disclosed evidence, rather than a stack of every prior form.

## Stages and explicit actions

| State | Visible presentation and allowed action |
| --- | --- |
| No verified selection | “Select a verified file variant first.” No acquisition action. |
| Selected variant | Explain “Retain one encoded representation. It will not yet be decoded or previewed.” **Check representations** replays retained configuration/root and performs bounded candidate metadata work; disclose its separate request/traffic/time limits before starting. |
| Checking representations | Indeterminate progress unless real progress events exist; **Cancel request**. Show selected exact scope. |
| Candidate choice | Show every authenticated representation in a table: actual full encoding identifier, known encoded bytes or Unknown, existing-retention status, unresolved encoding specification when supplied. No default selection. Distinct representations must not be merged by display name. |
| Locate authorization | **Locate selected representation** starts only the separately authorized bounded metadata operation. Show its metadata limits separately from payload size. Changing selection invalidates locator/plan consent. |
| Loose location absent | Only a conclusive typed loose HTTP 404 permits **Search archives** with new explicit search budget consent. Timeout, access denial, cancellation, other HTTP statuses or exhausted budgets never trigger archive search or another candidate automatically. |
| Search incomplete | “Search stopped before all permitted locations were checked.” Show actual reason/limits; not “File absent”. Any continuation requires a new explicit supported plan. |
| Acquisition plan | Immutable summary: exact variant, chosen actual encoding, route (direct object or archive section), complete encoded length, maximum payload transfer and bounded additional storage estimate. Separate metadata already requested, payload cost, physical reuse and unknown decoded size. **Retain encoded file** enabled only for a current valid known-size plan. |
| Acquiring/verifying | Honest stage/progress from backend, **Cancel request**; disable duplicate consent. “This cancels your request; shared requests may continue.” Do not claim worker retirement or empty canonical slot merely because UI future cancelled. |
| Retained | **Encoded file retained** with “Encoded integrity verified; decoded content not verified.” Display actual encoding and physical stored length. **Inspect retained bytes offline** verifies local physical bytes without HTTP. No Open asset/Decode/Preview control in this slice. |
| Offline verified | Same encoded-only boundary with actual local verification and original acquisition evidence. Other build association evidence is separate. |
| Missing/corrupt local | Clear current verified-local claim. Distinguish “Retained bytes missing” from “Retained bytes failed verification”. Explicit replan/retry only when service allows; never fetch implicitly. |

Unknown encoded size cannot authorize acquisition. Known object size is not measured additional download or disk growth. Display unknown values as **Unknown**, not zero. Reuse estimates are qualified; completed receipts show actual stored/shared outcome. A retained selected file and dependencies do not imply a complete offline build. Expiry or replan immediately removes the previous Retain consent; no automatic plan refresh.

## Canonical representation states

One canonical encoded representation is retained per verified game content-key domain and declared content key. Keep this policy understandable without requiring users to manage storage slots:

- **Existing representation supported here:** show actual retained encoding, original acquisition origin and new build association. Offer backend-approved reuse only; never relabel its source as a fresh fetch.
- **Another representation is being retained:** show busy/shared-work state. Do not offer a competing encoding or pretend cancellation of one consumer clears it.
- **Different representation already retained:** “This content already has a retained representation that this build does not advertise.” Show retained actual encoding and current candidates in evidence. Block duplicate acquisition; replacement/deletion/alternative retention are outside this workflow. **Back to variants** remains available.
- **Recovery required:** “An earlier retention operation needs recovery before a different representation can be selected.” Only expose a recovery action if backed by the service; otherwise show bounded diagnostics and an honest blocked state.

Declared content identity does not prove decoded equivalence. Byte sharing does not merge editions, records or source provenance.

## Evidence and failure clarity

Separate three groups: **Selected build and variant**, **Current association evidence**, **Original encoded acquisition and local verification**. Show actual configuration/root/EN proof references, declared content key, actual encoding, verifier scope, physical SHA-256, source observations and archive child range when applicable. An archive section receipt verifies the complete selected encoded object, not the complete remote archive. Retain full copyable values; primary copy uses plain “content identity” and “encoding identifier” with protocol labels in details.

Failures explain stage, cause and next safe action inline; footer is supplementary. Distinguish unsupported interpretation, malformed metadata, budget exhaustion, unknown cost, stale plan, transport failure, verification failure, canonical conflict, recovery, missing local bytes and cancellation. No generic “not found” for incomplete input. Selecting another build clears acquisition consent and prevents stale async results from painting into its workspace.

## Keyboard, scaling and layout

At 1280×800, stage title, exact selected scope, cost and primary explicit action should fit in the main viewport after scrolling the selection into view. Actions wrap; technical details expand below. Reuse virtualized variant/candidate tables with actual-row height capped to a small visible set; do not reserve large blank tables. Full identifiers remain selectable/copyable even when table cells ellipsize.

At 200% text scale, keep typography scaled without shrinking it to fit. Context wraps, stage actions stack, details flow below tables, and outer scrolling reaches every control. No fixed-height dialog or horizontal app clipping. Cost/consent must remain adjacent; never force the user to infer scope from an offscreen badge.

Tab reaches all actions and evidence; visible focus differs from selection. Enter inspects a table selection, not silently acquires it. Locate, archive search and Retain have no default-dialog Enter binding; typing an ID cannot authorize those operations. Escape returns detail focus to its table; it does not silently cancel a transfer. Selection/filter survive detail navigation. Busy/status/error changes are exposed to accessibility APIs without stealing focus. Button accessible names include the scope/cost of the consent they grant. Normal text contrast is at least 4.5:1; change/error/conflict states use words as well as color.

## Smallest delivery slice and acceptance

First deliver only backend-driven **Check representations → explicit candidate table → selected raw detail**, with immutable exact context, bounded metadata disclosure, cancellation and typed failures. It must not add a disabled-looking but unsupported Download affordance or sample candidates. Next wire separate location consent and its strict404/archive-search boundary. Add acquisition consent/retention/offline reopening only after canonical persistence/recovery and complete encoded verification gates pass.

Native screenshot/interaction acceptance at 100% and 200% covers: exact selected variant; all candidate choices including unknown-size; metadata consent; direct/archive location and incomplete search; known payload cost before consent; active cancel; encoded-only retained result; inherited proof origin; offline missing/corrupt; canonical busy/conflict/recovery; stale-workspace isolation. Capture long identifiers, multiple candidates and focused controls. Verify cost and action geometry against the viewport/footer, not merely control existence. Deterministic backend fixtures supply actual values; screenshots never substitute illustrative constants for service data. Independent usability review exercises keyboard selection, no accidental acquisition, scope comprehension and recovery; platform checks cover Windows/macOS/Linux text/scaling behavior.

## Candidate-metadata acceptance (#74)

The first slice now binds verified root-variant selection to the real selected-file service API. Selection alone performs no source requests. It presents every returned candidate, unknown sizes, explicit metadata wire/request/deadline limits, cancellation, current association evidence and unsupported-product states. Locator, payload-retention and offline-payload controls remain subsequent presentation work.

The deterministic native harness [`CaptureSelectedFileCandidates`](../../tools/ui/CaptureSelectedFileCandidates.java) uses real synthetic root/CAS/SQLite setup and a fake selected-file service, with conspicuous fixture disclosure. It checks keyboard inspection/focus, cancellation, stale context, unsupported products, and row visibility at 100%/200%. The original 200% estimate clipped the second row by eight pixels; a failing native geometry check now passes after increasing the scaled header allowance without reducing text size. CI runs this alongside the existing four UI harnesses.

Reviewed fixture references: [metadata consent](selected-file-candidates/selected-file-unchecked-1280x800.png), [candidate choices](selected-file-candidates/selected-file-candidates-1280x800.png), [unknown size](selected-file-candidates/selected-file-unknown-detail-1280x800.png), [source evidence](selected-file-candidates/selected-file-source-1280x800.png), [200% candidates](selected-file-candidates/selected-file-candidates-200pct-1280x800.png), [cancellation](selected-file-candidates/selected-file-cancelled-200pct-1280x800.png), and [unsupported product](selected-file-candidates/selected-file-unsupported-200pct-1280x800.png). These establish fixture presentation behavior, not live CDN acquisition, screen-reader certification or platform-specific font equivalence.
