# VibeMiner consolidated implementation plan

Formal implementation plan, 2026-10-18. GitHub Issues owns scope and delivery status; this document records the product and architecture decisions. Application implementation is underway in small verified increments. See the linked issues for current completion and validation.

## Delivered baseline and current work

- Released: [v0.1.0-alpha.2](https://github.com/aircan80/vibeminer/releases/tag/v0.1.0-alpha.2) at `0bba50b`, with all four native package/runtime gates passed. Scope is Atlas official product/build discovery, saved offline metadata workspaces and explicit verified install-list download/inspection. This is not the complete research MVP.
- Closed foundations: #31–#39 provide bounded BLTE/config/IN parsing, structural WDC5 header inspection, official transport, shared acquisition, durable actual-source aliases/build memberships and the retained-list workflow. No item records are decoded by these slices.
- Post-release source: #40 adds bounded explicit WoW root v1/v2 membership parsing; #41–#43 add exact range transport, authenticated BLTE chunk plans and bounded EN page lookup. Clean snapshot `1272c62` passed 290 Java tests and a Linux bundled-runtime check.
- Current integration: #44 streams selected root IDs without building millions of membership objects. #45 will compose exact source proofs and inspect actual Item/ItemSparse headers. Schema candidates remain pinned metadata until actual bytes agree.
- Remaining: complete verified raw-file browsing/offline UI, selected-content retention and safe cleanup, exact-layout DB2 records/schema/item research, comparisons, findings and research exports. Preserve each issue's remaining acceptance; early packaged alphas do not close the final research-MVP release gate.

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

The source build selects Java 25 and JavaFX 25.0.2, with the committed Gradle wrapper. Alpha.1 package evidence establishes the four named runtime targets; additional decoder/native dependency choices and future compatibility changes still require validation. See BUILDING.md for current setup.

Still to prove during the narrow spikes: official Forever/product mapping; exact available baseline/target builds; broader resolver/BLTE coverage and exact DB2 variants; minimal useful table dependencies; schema/listfile licensing/pinning; native package runners and signing/distribution requirements. Establish search/latency/memory/storage budgets from small representative measured workloads before claiming performance targets. Do not silently compensate for missing format support with fabricated data or another edition's records.


## Delivery tracking

The public backlog contains stories #13–#26 and evidence spikes #27–#28, with build automation #29 and portable documentation #30. GitHub Issues is the source of truth.

- Increment 1: standalone discovery — #13–15 and #27.
- Increment 2: verified shared archives — #16–19.
- Increment 3: supported item research — #20–21 and #28.
- Research MVP — #22–26.

No speculative dates or velocity estimates are assigned. Milestones are outcome checkpoints, not mandatory serial phases. Package checks begin early; #26 remains the final supported-platform release gate. File comparisons can proceed after archive readiness independently of item decoding.
