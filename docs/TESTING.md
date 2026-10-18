# Quality and release plan

Formalized test strategy. GitHub Issues records actual implementation and validation status.

## Recorded delivery evidence and limits

[v0.1.0-alpha.1](https://github.com/aircan80/vibeminer/releases/tag/v0.1.0-alpha.1) at `42b8be9` has verified bundled-runtime packages for Windows x64, macOS ARM64, macOS x64 and Linux x64. Its tested product scope is Atlas metadata discovery and saved offline metadata workspaces. Package verification does not certify the unfinished research MVP, signing/notarization, all accessibility behaviors or all future dependencies.

Post-release official product catalogue `971862b` and compact fix `5f1dfb0` are committed; final geometry verification passed. Delivered #31–#35 and #38 backend slices have deterministic fixture coverage for bounded BLTE/IN parsing, structural DB2 inspection, transport and shared acquisition. Separate [protocol evidence](PROTOCOL-EVIDENCE.md) records one small real BLTE/IN path; it does not establish arbitrary real-file decoding or item support.

The #36 receipt/catalog refactor passed 40 focused tests and independent review. A clean committed checkout of `90dd952` passed all 188 Java tests plus Linux bundled SQLite/JavaFX launch checks. The #37 backend passed 19 scoped application/gateway checks and six lifecycle checks. Separate live evidence covers a tiny exact install scope; a real-service/SQLite/CAS two-JVM harness uses synthetic transport for deterministic UI checks. Final UI refinements passed two-JVM recapture; a full source check and Linux bundled-runtime SQLite/CAS/JavaFX package check also passed. New all-platform packages remain to validate. Full archive/DB2 acceptance remains outstanding.

For each subsequent validation claim, record exact commands, source revision and runner/architecture. Preserve failed/unrun checks explicitly. Ordinary CI remains deterministic without live CDN or private working documents; mockup data remains fictional. The acceptance matrices below include requirements still to implement.

## Delivery discipline

For each small issue, write the behavioral acceptance example and failure cases before implementation. Use TDD for pure domain rules, parsers, acquisition decisions, retention and comparison: failing test, minimal change, refactor. For UI/tooling or protocol uncertainty, timebox a spike, capture evidence and then add regression tests around the selected contract. Engineers own tests; an independent reviewer evaluates behavior and boundaries. QA covers interactions and release evidence, not a replacement implementation test team.

Require relevant units, boundary contracts, integration and user-flow evidence before closing an issue. Record actual commands/results, platform, fixture/version and unrun checks. A passing unit suite cannot substitute for an untested package or UI flow. Do not set a blanket coverage percentage: inspect critical decisions and boundary/error paths. Mutation testing is optional targeted validation for identity, parser bounds and retention once meaningful tests exist; it is not a day-one gate.

## Test layers and ownership

| Layer | Proves | Proposed execution |
| --- | --- | --- |
| Pure unit | Exact identity, limits, transitions, query/comparison semantics | JUnit behavioral tests; ordinary checks on each change |
| Parser/property | Malformed/truncated structures reject safely, bounded allocation | Small synthetic binaries and deterministic seeded generation; adversarial corpus |
| Port contract | Real and fake adapters implement the same semantics | Shared contract suites for content store, repository, transport, clock and task control |
| Integration | HTTP + verified store + SQLite + decoder interactions | Local HTTP fixture server, temporary app-owned directories and real embedded SQLite; no internet |
| Concurrency/recovery | Coalescing, cancellation, shutdown/crash and cleanup preserve invariants | Barriers/latches, injected faults and bounded subprocess recovery tests |
| Architecture | Allowed module dependencies and composition only | Compile boundaries plus dependency/package rules in normal verification |
| JavaFX | Selection, keyboard, stale replies, loading/errors, useful workflows | View-model tests plus small real JavaFX harness; native display runs for UI behavior |
| Package | Bundled application works without developer tooling | Native installers/runtime images on clean supported OS runners |
| Human review | Atlas usability, accessibility, interpretation and visual quality | Scripted journeys reviewed by UI designer, usability expert and WoW expert |

Do not use arbitrary sleeps, real CDN responses, latest-build pointers, wall-clock timing or screenshots alone as assertions. Inject time where relevant. GUI tests synchronize on observable state. Every asynchronous test has a bounded deadline and surfaces worker failures. Keep fixtures out of private working directories when implemented; builds/tests must work from a clean clone.

## Architecture enforcement alignment

The implemented layers are domain; application depending on domain; desktop depending on application/domain; infrastructure implementing application/domain ports; and bootstrap composing concrete adapters. Pure protocol-tact and protocol-db2 modules inherit explicit bans on networking, persistence, JavaFX and application dependencies. Executable architecture tests enforce these restrictions. New format adapters must preserve them. Contract coverage expands with real use cases: cancellation/outcome lifecycles, source receipts, bounded queries and snapshot-bound cursors. The complete future job/paging contracts below are requirements, not claims that every interface already exists.

## First acceptance gate: foundation and runnable vertical shell

Before acquisition implementation grows, demonstrate:

1. A clean clone builds with the committed Gradle wrapper and pinned Java/JavaFX configuration; reproducible synthetic tests run without internet after dependency provisioning.
2. Core/domain/application modules compile and test without JavaFX initialization or concrete HTTP/JDBC/filesystem adapters; architecture violation tests demonstrate that forbidden edges fail.
3. An Atlas shell launches with an empty app-owned data directory, offers discovery without a game-folder prompt, loads a synthetic product/build list asynchronously and distinguishes empty/offline/error states.
4. Selection preserves product/channel/region/exact build/configuration identity and locale through close/reopen. A slower previous selection cannot replace a newer workspace response.
5. Installation/runtime-image smoke checks on Windows, macOS and Linux prove launch without a preinstalled Java, Node, Python, game client or external database. Early packages may be unsigned development artifacts; label their verification limits.
6. Dependency versions, licenses and native libraries have an initial inventory; no private working directories material, game data or cache enters source artifacts/packages.

These are foundation gates, not a claim of a useful production miner. Live official protocol research is a separately invoked bounded spike with selected URLs/builds/hashes recorded; never part of ordinary CI.

## Milestone acceptance coverage

### M0 — Foundation and protocol uncertainty

- Units: immutable exact identity/value validation and task outcome types, unknown/unavailable/unsupported state distinctions.
- Contracts: injected discovery, persistence, cancellation and scheduling ports; no network implicit in constructors.
- UI/integration: synthetic discovery, one workspace persisted/restored, rapid switch with out-of-order replies; no game-install access.
- Quality exit: first gate above plus explicit protocol evidence for a pinned official build; unresolved format/toolchain risks documented rather than treated as implemented.

### M1 — Discovery and multiple persistent workspaces

- Fixtures: missing/extra columns, BOM/line endings/UTF-8, malformed size/identifier, duplicate rows, current/preload/unavailable products and unknown edition capability.
- Units: discovery is observed history rather than a complete old-build catalogue; same version label in different editions does not collide; locale and configuration identity remain pinned.
- Integration: migrations/reopen, offline with cached metadata versus empty offline installation, timeout/retry budgets, cancellation and last-known data after failed refresh.
- UI: two build workspaces retain independent state; no stale data/title/provenance after switching; keyboard focus and useful error recovery.
- Exit: fresh standalone discovery and reopen journey passes deterministically, with separate explicit live probe evidence.

### M2 — Verified content and durable shared archives

- Transport contracts: validated 206 Content-Range, full 200 response behavior, truncated/oversized responses, invalid ranges/overflow, 404/429/5xx, deadlines and redirects/host policy. No silent mirror substitution.
- Parser units: supported configuration/root/encoding/index/BLTE variants, unsupported/encrypted distinctions, all lengths/counts/offsets bounded before allocation/read; decompression output limits prevent expansion bombs.
- Content units: expected protocol digest versus local SHA-256, verified promotion, corruption quarantine, reuse identity, derived-artifact key inputs and quota/reference rules.
- Integration: overlapping builds share one encoded object and matching derived artifact; logical/physical/additional/reclaimable accounting agrees with object membership. Older missing content stays partial, never complete.
- Race tests: simultaneous requests share a transfer; cancelling one consumer preserves other consumers; cancelling last consumer follows documented policy; in-use readers and jobs block deletion; pin/GC race cannot delete newly retained data.
- Recovery: disk-full/permission/short-write, crash before/after atomic promotion and DB commit, interrupted metadata transaction, orphan temporary file, corrupt index and abrupt shutdown. Restart reconciles safe state without exposing unverified bytes or deleting pinned content.
- Exit: repeat open makes zero content downloads, offline retained content opens, a corrupt object is detected, and deleting one build preserves shared objects referenced by another build or active task.

### M3 — Exact-build table/search/item slice

- Synthetic DB2 variants: string sections, locale, copy rows, compressed fields, relationship sections and layout mismatch for each explicitly supported format.
- Units: strict schema/layout/build compatibility, unknown fields retain raw values, stable record identity, absent/encrypted/unsupported/missing-reference distinctions, cross-table relation provenance.
- Integration: bounded page/query/sort/filter/cancel, SQLite transactions/migrations, prepared inputs, deterministic export quoting/UTF-8/newlines, path/destination checks and safe replacement behavior. CSV spreadsheet-formula policy is explicit.
- UI: search one supported collectible/item research question, inspect raw/readable fields and evidence, follow a relation or see its missing status; query A cannot overwrite query B.
- Exit: reproducible item result and CSV/JSON with exact sources; no implied obtainability or release timing. WoW expert reviews interpretation independently.

### M4 — Compare and investigate

- Units: added/removed/changed stable identities, locale/schema compatibility, both-side provenance and incomplete input; identical IDs across editions do not imply comparability.
- Integration: file/record comparisons repeat from pinned inputs and interpretation versions, saved report reopens offline, comparisons with missing scope disclose uncertainty instead of false removals.
- UI/human: inspect a concrete collectible, spell-tuning or crafting change; save evidence and export. Verify screen-reader labels, keyboard-only route, readable diff beyond color, resizable/high-DPI layout and spoiler controls where relevant.
- Exit: two supported available builds yield explainable differences; investigation retains exact source references and explicit completeness.

### M5 — Adapters, previews and release hardening

- Adapter contract ensures discovery capability is distinct from decoding/preview capability; edition identity is never inferred from a friendly label.
- Asset tests bound dimensions/duration/allocations, reject malformed payloads, preserve unsupported/encrypted states and clean up native/media resources. Introduce decoder-specific fuzz/property tests as each viewer ships.
- Native package matrix: Windows x64, macOS ARM64 and x64, Linux target architectures/distributions agreed during M0. Test each artifact on its actual supported OS/architecture; emulation alone is insufficient evidence.
- Empty-cache first launch, no installed Java, relocation/spaces/non-ASCII paths, fresh user profile, restrictive filesystem, offline restore, uninstall/reinstall preserving intentional user data, graceful shutdown and upgrade/migration rollback or backup policy.
- Validate signing/notarization and installer trust behavior when release credentials/process are selected; do not call an unsigned local launch a signed-distribution check.
- Exit: public release scope names tested OS/architectures, supported decoders, capabilities and known limitations; all applicable package and research journeys pass.

## Security, dependency and artifact controls

Treat CDN bytes, schemas, filenames, export names, remote metadata and optional community sources as untrusted. Bound download lengths, archive arithmetic, decompression, allocations, parser recursion, table counts, indexing and query result sizes. Reject traversal/absolute output paths; test symlink and rename races where writes could escape the app-owned directory. Do not scan user/game directories. Prefer explicit injected app-owned roots and test denied access to forbidden roots at integration seams.

Pin dependencies/plugins, verify provenance/checksums as tooling permits, inventory licenses and redistributed natives/runtime assets, and run vulnerability checks with documented triage rather than treating tool silence as proof. Review release archives for private material, secrets, private working directories, synthetic-only labeling leaks and accidental downloaded game payloads. Diagnostics minimize usernames/absolute paths and never include credentials. Choose project and schema redistribution licenses before shipping.

## Tooling baseline and remaining validation

- Current build selects Java 25 and JavaFX 25.0.2 with the committed Gradle wrapper; alpha.1 establishes the four named packaged runtime targets. Keep vendor/runtime/dependency licenses inventoried and revalidate driver, codecs and native architectures as dependencies change.
- Evaluate JUnit/property-test, architecture-rule, local HTTP and JavaFX UI harness candidates in a tiny branch/spike. Headless TestFX/Monocle is a candidate, not assumed compatibility; native JavaFX runs remain needed.
- Retain the working four-platform package matrix and graphics fallback checks. Document Linux baseline/windowing support, distinguish image launch from installer automation, and retest affected platforms when graphics/runtime dependencies change.
- Define empirical UI responsiveness, query/decode/memory and unique-storage budgets using representative bounded fixtures; measure before declaring numeric gates. No invented throughput target.
- Define SQLite and object-store recovery ordering with architect/storage owner before crash tests become normative.
- Select dependency/license/vulnerability scanning tools and release signing availability. A skipped check must stay visible.

Independent review should focus on boundary violations, fake completeness, hidden duplicate storage and destructive cleanup as strongly as happy-path screenshots.


## Subsequent partial-resolution foundations

Committed snapshot `1272c62` passed 290 Java tests from a clean checkout and an actual Linux bundled-runtime SQLite/CAS/JavaFX package launch. This includes independently reviewed exact-range HTTP transport (#41), authenticated BLTE chunk plans (#42) and bounded EN page lookup (#43). These checks establish their scoped contracts, not a complete live file resolver. Streaming root lookup (#44) then passed its 13 new tests and independent fragmented-stream comparisons before integration. The separate opt-in item-header cohort (#45) must compose and verify these pieces against exact data before broader support claims.

Alpha.2 at `0bba50b` passed all four native gates and publication in [run 39100990190](https://github.com/aircan80/vibeminer/actions/runs/39100990190). Its user-facing scope is install-list metadata; the subsequent partial-resolution foundations are not included in that prerelease.


Clean committed snapshot `b899a73` passed 316 Java tests and an actual Linux bundled-runtime SQLite/CAS/JavaFX package check. This includes the opaque-root-flag correction (#47) and completion-gated streaming publication (#46). The subsequent opt-in diagnostic verified one complete root CKey, then stopped at a direct-file 404 before any table headers; archive lookup remains unfinished. Index-footer/size observations and selected-index parsers need their own composition evidence before claiming archived-file availability.
