# VibeMiner implementation plan

Status: proposed implementation sequence, 2026-10-17. Interactive design mockups exist; acquisition and mining are not implemented.

Current user decisions: the product is **VibeMiner**. The selected stack is **Java + JavaFX**, Gradle, embedded SQLite and bundled Java/JavaFX runtimes. Avalonia is excluded. VibeMiner never reads game installs, Battle.net folders, or game-generated caches. Older-build download and durable retention are core workflows. Identical data across versions of one game must be stored once, including archive/cache storage. The research-led journeys in [mockups/WORKFLOWS.md](mockups/WORKFLOWS.md) govern the UI.

## 1. Product contract

Build a standalone, easy-to-install desktop explorer for Blizzard-published data. A user chooses a product/game, channel, region, exact build and locale, then browses supported data without installing the game. Multiple build workspaces can be open together. The app fetches required metadata and content as needed, verifies it, and caches it locally.

The first valuable end-to-end journey is:

> Open an empty installation → choose a WoW product/build → browse published content → open a supported table → inspect an item → compare it with another available build → reopen the same content from cache.

The wider goal covers Blizzard products through adapters. Initial WoW decoding is a deliberate first slice, not a promise that every Blizzard title or file format works immediately.

Required boundaries:

- Never read or discover local game installations, Battle.net data, or game-generated caches; no local hotfix-cache import. No external local service or Vibecraft connection.
- No full-game download by default. Explain the metadata and archive ranges needed for each operation.
- No dependency on external developer tools in the shipped application.
- Default connections are to official Blizzard metadata/CDN endpoints. Optional community metadata is explicitly configured and attributed.
- Preserve exact product/build identities; never fill a missing build with another edition’s data.
- An unavailable build, encrypted payload, unsupported decoder, unnamed file and absent record are different states.
- The user requested a plan now; implementation begins as a later work item.

## 2. User experience

Atlas is the selected UI direction: [interactive reference](mockups/atlas/index.html). Build its light ivory/teal research workbench in JavaFX using split panes, virtualized tables, contextual inspectors and an investigation notebook. Preserve readable typography and asynchronous interaction patterns. The HTML prototype is a design reference, not an embedded browser requirement. Forge and Observatory remain alternatives for reference. Model/map preview support is a separate implementation capability.

Support separate game/edition workspaces, explicitly including WoW Forever and Retail, with multiple builds and independent histories. Resolve official product/channel identifiers during the protocol spike; display names do not establish product IDs or decoder compatibility. Another Blizzard game's adapter may expose discovery before semantic decoding is supported.

Within-edition comparisons are the default. Cross-edition comparisons are available only for explicitly validated identity mappings and compatible schemas; do not align records solely because their numeric IDs match. Shared byte storage does not imply shared game semantics.

Main areas:

| Area | Purpose |
| --- | --- |
| Discover | Published products/channels, regions, current builds and advertised preloads; last checked and refresh state |
| Build workspace | Pinned identity, availability, manifests, locale/content filters, files and supported tables |
| Record inspector | Raw values, interpreted values, references and compact source disclosure |
| Compare builds | File changes first, then table/record changes with comparable schemas |
| Downloads | Queued/running/failed jobs, required bytes when known, cancellation and retry |
| Cache | Encoded/decoded/index usage, limits, pinned workspaces and selective cleanup |
| History | Observed builds, previous discovery snapshots and saved comparisons |

Persistent top context: game/product → channel → region → exact build → locale. Switching context retains navigation per workspace and cancels or detaches obsolete UI requests. Data from another build must never paint into the current workspace after an asynchronous response arrives.

First launch discovers products without asking for a game folder. When offline, explain that cached workspaces are available; an empty offline installation has nothing to browse yet. Avoid a blank browser or a demand for a prebuilt database.

Table/file lists use filtering and pagination, with a visible loading/error state. Unknown filenames retain stable identifiers. Details open progressively; optional images and references do not block basic record inspection.

## 3. Acquisition and identity model

Start with official HTTPS version metadata, then follow the selected product’s advertised CDN configuration. Revalidate protocol details during the discovery spike; do not infer endpoints from display names.

Store discovery documents with URL, timestamp and SHA-256. Pin the selected product, region, version/build identifier, build configuration, CDN configuration, product configuration where present, locale and current/preload channel. Resolve and record root/encoding identities from verified configuration content before file access.

Older-build download and durable archiving are core workflows. Discovery is a list of currently published pointers and locally observed history. It does not promise a complete historic build catalogue. A manually supplied known configuration pair can be a later advanced feature, with availability checked before browsing.

Content resolution pipeline:

1. Read and snapshot official product/build/CDN metadata.
2. Fetch the pinned build/CDN configurations and validate their protocol-defined content identities.
3. Retrieve the encoding, root and necessary archive indexes for the product adapter.
4. Map a content key/file identifier to an encoding key and CDN location.
5. Download a loose object or validated archive range; verify the expected object identity.
6. Decode supported BLTE compression/encryption modes; retain explicit failures and verified raw bytes.
7. Parse manifests or supported file formats, then index only the requested content.

Range requests require bounded offset/length arithmetic, validated Content-Range and correct handling when a server returns the whole object instead. If an index or manifest must be fetched in full, disclose that cost rather than promising every click downloads only a tiny file.

Use connection/read deadlines, retry limits, bounded concurrency, transfer limits and cooperative cancellation. Retry hosts come from the pinned Blizzard CDN metadata. Do not silently use community mirrors. Availability is determined by actual responses and verification, not just a published build name.

## 4. Cache and local index

The local cache is app-owned storage, not a required external connection. Proposed layout, outside the source repository:

```text
cache/
  objects/          verified encoded content, addressed by identity
  decoded/          derived content, keyed by source and decoder version
  metadata/         captured discovery/configuration manifests
  work/             partial downloads and checkpoints
index.sqlite        jobs, workspaces, provenance and indexed record subsets
```

Separate protocol content identifiers from a locally computed SHA-256. Include product namespace when identities are not demonstrably globally interchangeable. Reuse verified identical objects across builds while keeping build membership and provenance separate.

Download to a temporary file and atomically promote only after verification. Record expected length, identity, completion and last verification. Corrupt cache entries are quarantined or removed and fetched again. Coordinate concurrent requests for the same object into one transfer.

Expose a configurable cache quota, per-category usage, pinned builds and least-recently-used eviction. Never evict data under an active reader; rebuild disposable indexes when source objects remain. Cleanup must not accidentally remove settings, in-progress jobs or the only pinned offline copy.

Resume at verified object boundaries first. Partial byte-range resume follows only after identity and range validation are proven. Offline browsing makes clear which records are fully cached and which references need a network fetch.

### Durable build archive and deduplication

A saved build stores exact metadata and object membership, never a separate copy of its data. Archives, caches and offline workspaces share one content-addressed object store per game namespace. Identical bytes have one retained physical object; protocol CKey/EKey identities map to verified objects rather than per-build paths. Stage transfers once and atomically promote verified objects. Coalesce concurrent requests and reuse existing verified content before downloading. A build advertised upstream is not proof every historical object remains available. Show checked availability, requested scope, missing bytes and incomplete archival status explicitly.

Retained builds pin their required objects against cache eviction. Metadata-only, selected-content and fully retained scopes must be distinct; do not call a partial dataset a complete offline build. Download only missing unique objects. Shared encoded or derived artifacts must not be copied into separate archive/cache directories. Different derived representations are keyed by source content, decoder/schema revision, locale and output options and reused across builds when those inputs match.

Track logical build size separately from unique physical storage, shared bytes, additional download and reclaimable bytes. Removing a build releases references, not shared files. Cleanup considers remaining build memberships, pinned scopes and active readers/jobs before deleting unreferenced objects. Any future user-requested portable export is explicit output, never a hidden internal duplicate.

## 5. Decoding and search

Keep three levels distinct:

1. **Published metadata:** product/build/configuration information.
2. **Raw content exploration:** manifests, file identifiers, content hashes, bytes and export.
3. **Interpreted game data:** supported tables, fields, relationships and search.

Raw exploration should still work when a semantic decoder is unavailable. WoW DB2/WDC decoding requires exact layout/build matching, string tables, locale handling, copy records, compression modes and relationship data as applicable. Implement and test supported variants explicitly.

Field names and relationships may come from a community schema project. Mark their provider, pinned revision and confidence separately from Blizzard bytes. Prefer a reviewed schema snapshot shipped as application metadata, with an explicit optional update source; default game-content requests remain Blizzard-only. Display stable IDs/raw fields when a compatible schema is absent. Never guess a table layout because the filename looks familiar.

A WoW item view will likely combine multiple tables. Plan and report those dependencies; downloading one table alone does not prove item coverage. A record newly present in client data is not proof of a live server drop, quest availability or release announcement.

Use a read-only query interface for indexed content with prepared queries and limits. An advanced SQL view, exports and additional asset viewers are later milestones. Bulk decoded datasets are not required for first launch.

## 6. Comparison model

Compare exact build snapshots from the same supported product context. Start with added/removed/changed content identifiers. A byte change does not automatically mean a gameplay change.

Record comparisons align stable primary keys and compatible schemas, then identify field changes. Account for locale, schema/layout changes, copy records and incomplete/encrypted inputs. If a reliable comparison cannot be made, display that limitation instead of classifying everything as added or removed.

Every result links to both source builds and the interpretation/schema version. Save comparison parameters and completeness so reopening a result is reproducible.

## 7. Architecture

Selected baseline: Java/JavaFX desktop, Gradle, Java mining modules, embedded SQLite for app-owned indexes, and custom runtime images/installers through jlink/jpackage on native CI runners. Validate Java acquisition and DB2 decoder coverage during the protocol spike; TACTSharp and DBCD are reference implementations rather than runtime dependencies. Confirm third-party library support and licenses during the initial spike. End users install no Python, Node, Blender or database server.

Logical modules:

| Module | Responsibility |
| --- | --- |
| discovery | Product registry, version pointers, regions, metadata snapshots |
| transport-cache | Official CDN access, verified objects, ranges, quota and cancellation |
| tact | Configuration, encoding/root/archive indexes and supported BLTE decoding |
| adapters | Product-specific manifests and formats; WoW first |
| catalog-index | Build membership, provenance, requested table subsets and search |
| compare | Reproducible file/record differences |
| desktop | Workspaces, inspectors, jobs, cache management and themes |

Keep HTTP, decoding, indexing and database work off the JavaFX Application Thread. Use bounded workers, immutable progress events, generation tokens for UI updates and cancellation propagated through each stage. Tables request limited pages; the GUI must not materialize an entire game dataset to show its first rows.

Vibecraft’s existing build-discovery and verified-fetch code is a reference candidate, not a dependency on its database or a wholesale copy of its app. Review before reuse: its discovery currently restricts channels to WoW, while VibeMiner needs product adapters. Extract a small shared versioned library only when two working callers justify it. Confirm code licensing before selecting the new project’s license.

## 8. Delivery milestones and acceptance

### M0 — Project and protocol spike

- Establish the project’s own repository, issue tracker, Gradle/Java builds and synthetic fixtures when implementation starts.
- Verify live official product summary and version/CDN endpoints; define resilient parsing and capability states.
- Demonstrate one pinned official WoW build/configuration identity without local game files.
- Review GUI mockups: discover, build workspace, downloads and cache.

Exit: documented protocol evidence and a runnable shell using synthetic fixtures plus an explicit live discovery action. No bulk content or interpreted data claim.

### M1 — Discovery and persistent workspaces

- Product/channel/region/build/locale selection, multiple saved workspaces and observed-build history.
- Async refresh, cancellation, cached discovery, honest preload/unavailable states.
- Cache size/location preferences and diagnostic exports without private credentials or paths.

Exit: a fresh installation selects a currently published build, closes, reopens and restores its verified metadata, without a game installation or Vibecraft.

### M2 — Verified on-demand file access

- Build/CDN configs, supported encoding/root/index access and one useful raw file browser.
- Fetch and decode one supported file by stable ID/key using only official CDN data.
- Shared verified cache, interrupted transfer handling, quota, progress and offline reopen.

Exit: opening an uncached supported file performs bounded downloads; reopening it makes no content download; corrupt cached bytes are detected. Unsupported/encrypted content remains explicit.

### M3 — First real WoW data explorer

- Decode a supported exact-build DB2 table; schema provenance and searchable paged rows.
- Add the minimum table dependencies for an item inspector with raw/source details.
- Query cancellation, field filtering and CSV/JSON export of requested results.

Exit: a user finds and inspects a real item from a selected official build, with a reproducible source chain and no fabricated relationships.

### M4 — Build comparison and discoveries

- File manifest differences, then compatible item/table comparisons.
- Added/removed/changed filters, field comparisons and saved reports.
- Manual checks for newly observed builds; optional scheduling comes later.

Exit: compare two available compatible builds and explain each result, missing input and schema mismatch honestly. New client records are identified without claiming live gameplay availability.

### M5 — Additional products and portable previews

- Add another Blizzard product through a documented adapter; generic discovery alone does not mark it decoded.
- Improve asset previews, query tools and cache management according to actual usage.
- Native Windows, macOS ARM/Intel and Linux packaging, relocation tests and empty-cache launch checks.

Exit: downloadable app packages with bundled runtime; no shipped game database/cache and no dependency on external tools or the companion app.

## 9. Validation and work tracking

Tests must cover protocol fixtures, malformed lengths/hashes, wrong Content-Range, unavailable content, cancellation, corrupt cache, offline behavior, same-object request coalescing, quota eviction, exact-build/schema matching and stale UI responses. Test on Windows/macOS/Linux; filesystem aliases and portable archive paths need explicit platform coverage.

Use small synthetic fixtures in Git. Keep downloaded Blizzard data, caches, databases, private archives, build outputs and task worktrees out of source control. Live smoke tests are explicit and record the selected build/configuration and fetched hashes; deterministic CI does not depend on the latest live build remaining unchanged.

Track every substantive feature/bug in GitHub issues, with implementation notes, checks and next steps. Initial handoff issue: [Vibecraft #10](https://github.com/aircan80/vibecraft/issues/10). When a VibeMiner repository exists, create its implementation issues and cross-link rather than pretending this plan is completed software. No separate GitHub repository or release is created by this planning task.

## 10. Research and unresolved questions

Primary implementation references, reviewed 2026-10-17:

- [TACTSharp](https://github.com/wowdev/TACTSharp): reference implementation for online WoW storage, content lookup and configuration-driven extraction. It is C# and serves as a protocol/reference implementation, not a direct Java dependency. Its stated scope does not establish other-game support.
- [TACTSharp Ribbit source](https://github.com/wowdev/TACTSharp/blob/main/TACTSharp/VersionServices/Ribbit.cs): current implementation reference for official HTTPS summary/product metadata endpoints. Pin a revision during the protocol spike.
- [CascLib](https://github.com/ladislav-zezula/CascLib): native C/C++ CASC implementation reference across Blizzard games. Evaluate native bindings and licensing only if needed; package dependencies explicitly.
- [WoWDBDefs](https://github.com/wowdev/WoWDBDefs): schema definitions, including build/layout-specific information and unverified columns. Community interpretation is separate provenance from client bytes.
- Official metadata probes: `https://us.version.battle.net/wow/versions` and `/wow/cdns` returned HTTP 200 during planning. The guessed `/v2/products` listing returned HTTP 400 and is not a product-list endpoint. The implementation reference uses `/v2/summary`; verify it and the versioned per-product routes in M0. No game payload was downloaded for this plan.

Resolve through the M0 spike: Selected library codec coverage for encountered BLTE modes, supported WoW root/DB2 variants, minimum useful metadata transfer sizes, community-schema distribution/update policy and code license. Publicly discoverable encrypted or missing files remain unavailable; the app does not promise access to unpublished data.

First implementation ticket: **Discover and cache official Blizzard product/build metadata in a fresh standalone Java/JavaFX app.** Do that before broad file decoding, item comparison or another game adapter.
