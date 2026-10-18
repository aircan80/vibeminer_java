# VibeMiner consolidated implementation plan

Formal implementation plan, 2026-10-18. GitHub Issues owns scope and delivery status; this document records the product and architecture decisions. Application implementation is underway in small verified increments. See the linked issues for current completion and validation.

## Delivered baseline and current work

- Released: [v0.1.0-alpha.3](https://github.com/aircan80/vibeminer/releases/tag/v0.1.0-alpha.3) at `2382261`, with all four native gates and all 12 final assets independently verified. Scope adds explicit exact-FileDataID root metadata plan/retain/offline lookup to the Alpha.2 discovery/workspace/install-list baseline. Later source is not automatically included, and this is not the complete research MVP.
- Closed foundations: #31–#44 and #46–#49 provide bounded BLTE/config/IN parsing, structural WDC5 header inspection, official transport, shared acquisition, durable actual-source aliases/build memberships, streamed verified publication, selected root-ID streaming, bounded EN/range/chunk proof and authenticated archive-index lookup. #47 records modern-root compatibility evidence. Latest closed #48/#49 were checked with a clean 393-test run and Linux bundled-runtime check; the integrated backend snapshot `6093c4a` subsequently passed 407 cases and the same native gate. These are scoped protocol/storage foundations, not decoded item records or complete archives.
- Root-lookup backend #50 is committed in `5384671`: exact-root plans, full verified encoded-root retention and bounded selected-ID lookup/offline policy. Reported validation includes 14 integration tests and an independent 157-case tamper sweep. Shared-runtime integration (#54) is committed in `56b9274` with 73 bootstrap tests and independent lifecycle review, and separate Atlas native acceptance (#53) passed with the integrated `2382261` four-platform CI checkpoint. The delivered desktop slice covers root membership metadata; selected file payloads remain separate.
- Actual table evidence: #45 is closed for its bounded Item/ItemSparse header cohort; #51 records the archived Item header and #57 closes the actual Item section/storage-metadata evidence slice. These establish raw observed structures with explicitly scoped source proofs, not complete table decoded CKeys, readable rows or schema relations. See [Item header evidence](evidence/item-header-12.1.5.70448.json) and [protocol evidence](PROTOCOL-EVIDENCE.md).
- Latest four-platform checkpoint: [CI run 39108817700](https://github.com/aircan80/vibeminer/actions/runs/39108817700) passed all four native targets at exact source `b3a5e01`, including #73 gateway, #72 persistence and #74 candidate inspection. It predates the subsequent #71 corrective/default service work, persistence correction and shared-runtime integration; native validation of that later integration is pending.
- Latest committed local checkpoint: `7035fd3` passed a clean forced full check with 688 Java cases, zero failures/errors/skips, Linux development package 15 and actual bundled SQLite/CAS/UI launch. All five native UI harnesses passed: Atlas capture, workspaces, install workflow, file lookup and selected-file candidates. All eight offline protocol entrypoints passed: seven reported 638 assertions, and the layered-root fixture demonstrated zero HTTP and two physical objects. All seven candidate PNG comparisons were pixel-identical (AE 0) to design references at 100%/200% scaling. These later source checks do not change alpha.3's revision or assets.
- Delivered source foundations: #55/#58–#60 provide bounded WDC5 metadata, bit fields, supported numeric modes, fixed-section IDs and raw copies. Later source adds sparse metadata/coordinates and actual bounded section-index evidence (#61/#63/#65), exact-layout schema metadata (#64), typed HTTP availability (#66), supported sparse record locations/string-prefix bounds (#67), sequential exact-child range streaming (#69) and deterministic root-concurrency verification (#70). Implementation/check evidence and administrative issue closure are reconciled separately; these primitives do not establish readable item rows or full table support.
- Retention/accounting #56 is integrated in source with the reviewed A-to-B intent correction and cancellation/lifetime guards; the latest four-platform checkpoint covers the combined source. It concerns explicit metadata-scope intent and accounting, without a storage-management screen, deletion or GC. Broad older-build/archive acceptance remains separate.
- Complete encoded-stream verification #62 exists as a pure backend foundation. Encoded authentication does not establish decoded CKey, successful decompression, readable rows or complete table interpretation. Production selected-file acquisition is not yet delivered by that primitive.
- Diagnostic #68 completed one explicit bounded live Item/ItemSparse projection: record IDs `25` and `19019` were joined using candidate fields and display interpretation. Measured cost was 25 requests, 1,901,098 response-body bytes, 4,632,477 decoded-work bytes and 0.9865 seconds. Full table CKeys were not verified. This is one scoped diagnostic result, not complete-table decoding, a delivered item-research view or production selected-file acquisition; formal source/proof evidence is recorded separately.
- Selected-file backend and runtime: the approved [acquisition design](FILE-ACQUISITION.md) now has implemented gateway #73, canonical persistence #72, application orchestration #71 (`c0ca793`), stale-reservation handoff correction (`b4f03e8`), shared lazy runtime #75 (`2e92cbd`) and layered multi-build/offline-integrity journey #76 (`7035fd3`), with independent reviews. Runtime-enabled candidate inspection is present, and encoded-payload retention is available through the backend API. Final payload-retention GUI #77 remains in progress with 26 presentation tests, without shipping UI acceptance; exact-root-variant offline API #78 has new tests awaiting independent review; these changes are not an alpha.3 payload-download feature.
- Remaining: complete verified raw-file browsing/offline UI, selected-content retention and safe cleanup, exact-layout DB2 records/schema/item research, comparisons, findings and research exports. Preserve each issue's remaining acceptance; early packaged alphas and closed protocol subissues do not close broad research/archive stories.

## Immediate dependencies and acceptance boundaries

1. Preserve accepted install/root metadata and candidate-inspection workflows while completing #77/#78. Validate the combined post-`b3a5e01` application/persistence/runtime changes on all four native targets; the successful local `7035fd3` check does not substitute for pending cross-platform evidence.
2. Preserve #68’s exact diagnostic source/proof, row-boundary checks and candidate interpretation evidence when extending its single successful live projection. Full-table CKeys remain unverified; two joined records do not establish general row compatibility, complete table coverage or a production research feature.
3. Preserve the reviewed backend single-pass verified CAS publication, exact plan replay, canonical reservation policy and actual receipts when exposing payload controls. #77/#78 must establish exact-variant offline reconstruction, consent invalidation, cancellation/context isolation and real native UI acceptance. Do not infer a final payload GUI from backend journey success.
4. Complete encoded verification remains explicitly decoded-unverified. Readable item research (#20/#21) additionally requires complete supported table features, exact schema interpretation, validated joins and paged/offline presentation. Scope-qualified missing, unsupported and absent outcomes remain distinct.
5. Explicit metadata retention/accounting does not complete cleanup or historical archive stories. Keep deletion/GC, actually older-build availability, full dependency closure and general raw-file/offline UI under their separate acceptance criteria; no full-game archive claim follows from root/install or one selected encoded object.

## Agreed product and engineering contract

- VibeMiner is a standalone Windows/macOS/Linux Java/JavaFX application, with Atlas as its design direction and a bundled runtime.
- Read official Blizzard CDN data into app-owned storage. Never discover/read game installations, Battle.net directories or game-generated caches. No local hotfix ingestion, now or as an assumed future feature.
- Support separate product/edition workspaces and multiple exact builds with independent histories. Forever/Retail are required user-facing editions to investigate; official identifier/capability mappings must be verified before support is claimed.
- Maintain/download older available builds using app-observed or retained authoritative metadata/configuration references; arbitrary user-entered historical configuration pairs need separately validated provenance and are not an assumed first-release input. current pointers are not an exhaustive historical catalog. Disclose requested and retained scope, unavailable objects and incomplete archives. Retained selected content is not a full-game archive.
- Identical bytes have one physical owner shared across build memberships, archive and cache policies. Share derived results when source content, decoder/schema, locale and options match. Distinguish logical, physical, shared, missing unique and reclaimable bytes.
- Preserve research provenance and uncertainty: client content is not proof of obtainable rewards, release timing or server-only behavior. Missing input is not an asserted removal.
- Work in small end-to-end steps. Use TDD where practical, synthetic deterministic fixtures and independent review. Quality evidence must fit the change: units do not replace UI/integration/native-package validation.

## User value and release scope

The core research loop is: select an exact build → acquire supported content → search a readable record → inspect raw/related evidence → compare compatible builds → save/export a finding → revisit retained evidence offline.

The first research MVP covers supported item/table inspection, raw-file access/export, scoped item and file comparison, saved findings/notes, source-aware CSV/JSON including intentionally selected user research notes, older-build selected-content retention and baseline supported-platform packages. It does not imply every audience's entire domain is already decoded.

Keep all six audience journeys visible in product design: collectibles; classes/combat; crafting; story/world; art/sound; developer data. Add richer collectible/spell/recipe/quest relations and previews only after validated table/format capabilities. Manual watch rules and richer viewers follow the core MVP. Scheduled notifications, cross-edition semantic comparisons, general SQL and broad non-WoW decoding are later separately scoped work. Game-install ingestion and arbitrary decryption are excluded.

## Layers and responsibility boundaries

Use a modular monolith, initially five Gradle modules. An arrow denotes a compile dependency:

```text
desktop ---------> application -------> domain
infrastructure --> application/domain
bootstrap -------> desktop + infrastructure
```

| Layer | Owns | Must not own |
| --- | --- | --- |
| domain | Immutable identities, provenance/value types, capabilities/completeness, invariants | JavaFX, HTTP, JDBC, filesystem work or adapter dependencies |
| application | Use cases, business/retention policies, ports, immutable results, job/cancel/progress contracts | UI controls, concrete adapters, SQL or transport parsing |
| infrastructure | Port implementations for official transport, persistence, storage and protocol integration | UI state or unreviewed WoW interpretation policy |
| desktop | JavaFX views/presenters, navigation, UI-only observables, context generation, display | Direct HTTP/JDBC, mining file I/O, protocol decoding or business rules |
| bootstrap | Construction/wiring, configuration, lifecycle and package entry point | Research or storage business policy |

Do not create speculative empty modules for every feature. The pure `protocol-tact` and narrow `protocol-db2` modules now exist. Expand or extract specialized adapters when the table/search slices justify them. Parsers accept bounded inputs and return models; they do not fetch, persist, schedule or paint UI. Enforce dependency direction/cycle restrictions in normal CI, using Gradle boundaries and architecture tests.

Use explicit ports and constructor wiring. Avoid service locators, global mutable context, blanket dependency graphs, generic repository frameworks and a framework for hypothetical plugins. Concrete reference libraries do not become runtime dependencies without Java compatibility and license evidence.

## Contracts agreed before parallel implementation

1. Exact `BuildSnapshotId` pins verified product/configuration evidence, not just a displayed version. Locale is a content-view dimension. Edition labels, protocol CKey/EKey and local SHA-256 are distinct typed identities.
2. `JobHandle<T>` exposes one terminal outcome, cooperative cancellation and bounded/coalesced progress. Events bind to job/workspace generations; completion/cancellation races are explicit. UI subscriptions close with view lifecycle.
3. Paged queries have bounded limits, allowlisted filters/sorts, stable keys and snapshot-bound cursors. Incomplete coverage travels with results. Query/index changes cannot silently mix pages or builds.
4. The object store exposes atomic publish/retain/release/read-lease operations. The adapter owns transactions/locks; use cases own policy. Publish verified immutable bytes before committing ready references; recover interrupted work idempotently.
5. Cleanup coordinates deletion with new references, leases and active jobs; a zero-reference query followed by an uncoordinated delete is insufficient. Initial store ownership is single-process. Archives and caches do not duplicate bytes or decoded rows.
6. Expected unsupported, encrypted, unnamed, metadata-only, uncached and partial states are distinct from exceptional transport/integrity/storage failures. Missing comparison input stays indeterminate.

Detailed contracts: [architecture](ARCHITECTURE.md), [storage](STORAGE.md), [protocol](CDN.md), [UI](UI.md), and [format/search](FORMATS.md).

## Logical delivery sequence

Existing issues are umbrellas; refine their acceptance or open linked subissues before each implementation step. An early slice does not close an umbrella's remaining scope.

| Step | Working outcome | Issues | Exit evidence |
| --- | --- | --- | --- |
| 0 | Reproducible Atlas shell with fixture mode, five boundaries and bundled-runtime feasibility | #2, #3, early #11 | Full JDK/Gradle Wrapper verified; compile/unit/architecture checks; host launch; native CI package feasibility |
| 1 | Explicit official discovery and immutable build selection | #2, #3 | Parser/HTTP fixtures, bounded malformed responses, cancellation/stale results; separate opt-in protocol evidence |
| 2 | Multiple scoped workspaces reopen after restart, including offline metadata | #4, #3 | Exact identity persistence, locale/edition collision fixtures, no latest-pointer substitution or cross-context repaint |
| 3 | One raw object resolves, verifies, publishes and reopens offline | #5, initial #6 | Supported resolver chain; wrong hash/range, truncation, corrupt cache, shared request and cancellation tests; second open has zero content requests |
| 4 | Older selected-content archive reuses objects and survives safe cleanup | #6, #4 | Available/unavailable/partial scopes; two builds/one object; concurrent transfer sharing; accurate accounting; retention/GC/crash recovery |
| 5 | One exact-layout table supports paged search and evidence inspection | #7 | Bounded synthetic decoder fixtures, schema mismatch rejection, provenance, indexing/query limits and meaningful offline behavior |
| 6 | A real supported item investigation follows validated relations and exports evidence | #7, minimal #9 | Smallest reviewed table cohort; orphan/locale/layout fixtures; separately recorded official-build acceptance journey |
| 7 | File diffs, then compatible same-edition item field comparisons | #8 | Added/changed/removed plus indeterminate inputs; both snapshots/interpretations retained; no guessed cross-edition alignment |
| 8 | Findings/notes survive restart and scoped reports retain provenance | #9 | Save/reopen/navigation/export/cancel/error tests and usability review; no private working documents in exports (user-authored investigation notes are legitimate selected export content) |
| 9 | Supported release installs and completes the research loop | #11 | Windows/macOS/Linux runtime bundles, empty-cache/relocation/offline/upgrade validation; actual tested OS/architecture support and failed/unrun checks stated; dependency/license/native-runtime inventory included |
| Later | Manual watch rules, richer semantic research and staged text/texture/audio, then model/map capabilities | #9, #10 | Independent domain/format safety, accessibility and performance evidence per added capability |

Steps 0–2 are the first useful increment: launch → discover → select/persist → offline metadata reopen. Steps 3–8 grow that into the research MVP. Packaging is exercised from step 0 and continues through release; unsupported/unvalidated platforms must not be advertised as tested.

## Atlas work to resolve before relevant coding

- Use one coherent exact-build fixture across research, archive, records and notebooks. The prototype currently mixes independent fictional research and Retail contexts.
- Add a persistent context strip: edition/product, channel, region, build, locale and coverage. Context and demo/fixture disclosure must be visible before research actions, not only in a footer.
- Review the tall category panel so key build/scope controls stay discoverable in the first viewport. Preserve Atlas visual identity with standard accessible JavaFX controls.
- Calculate shared/archive byte figures from a small object graph fixture rather than hardcoded inconsistent totals. Show requested versus verified scope and unknown estimates explicitly.
- Specify file inspection, export preparation, relationship navigation, partial/offline failures, focus return, keyboard activation and status announcements before the corresponding slice.
- Browser overview rendering was observed during planning in a local screenshot; this is visual evidence only, not user testing or full interaction/accessibility validation. See [UI specification](UI.md) and [workflow acceptance](WORKFLOWS.md).

## Test and review gates

For deterministic behavior: write a failing test, implement the smallest change, then refactor with checks passing. Add regression tests for testable defects. Prioritize invariants and failure cases over coverage percentages or tests of getters.

- Domain/application units: identity, completeness, use-case policy, pagination, comparison and cancellation outcomes; no JavaFX startup, network or database required.
- Pure parser fixtures: valid/truncated/malformed data, checked arithmetic, string/row/copy/relationship/compression bounds and exact-layout matching.
- Adapter contracts/integration: local controlled HTTP, temporary SQLite/store, verification, coalescing, read leases, atomic publish/recovery, migrations and GC races.
- Presentation units and real JavaFX checks: stale responses, context switching, focus/keyboard, loading/error/empty behavior, paging and text scaling; actual screen-reader/platform checks remain separate.
- Release checks: bundled runtime, native packages/architecture, clean machine, offline retained scope, relocation, upgrade and no game-install reads.
- Normal CI is deterministic and has no live CDN dependency. Opt-in live verification records exact source/configuration and transfer evidence without committing game content.
- Before closure: acceptance met, relevant checks pass, independent technical/domain/UI review as applicable, limitations explicit and issue-linked commit/PR recorded.

## Validation-first decisions and risks

The source build selects Java 25 and JavaFX 25.0.2, with the committed Gradle wrapper. Alpha.2 package evidence establishes the four named runtime targets; additional decoder/native dependency choices and future compatibility changes still require validation. See BUILDING.md for current setup.

Still to prove during the narrow spikes: official Forever/product mapping; exact available baseline/target builds; broader resolver/BLTE coverage and exact DB2 variants; minimal useful table dependencies; schema/listfile licensing/pinning; native package runners and signing/distribution requirements. Establish search/latency/memory/storage budgets from small representative measured workloads before claiming performance targets. Do not silently compensate for missing format support with fabricated data or another edition's records.


## Delivery tracking

The public backlog contains stories #13–#26 and their linked implementation/evidence slices through #78, including build automation #29 and portable documentation #30. GitHub Issues is the source of truth. Closed foundation subissues do not close the broader user outcome automatically.

- Increment 1: standalone discovery — #13–15 and #27.
- Increment 2: verified shared archives — #16–19.
- Increment 3: supported item research — #20–21 and #28.
- Research MVP — #22–26.

No speculative dates or velocity estimates are assigned. Milestones are outcome checkpoints, not mandatory serial phases. Package checks begin early; #26 remains the final supported-platform release gate. File comparisons can proceed after archive readiness independently of item decoding.
