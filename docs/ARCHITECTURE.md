# Layered Java architecture design

Formalized architecture for issue #12. These contracts are implementation requirements; capabilities are accepted only after their tests and evidence pass.

## Current implementation and release boundary

Published [v0.1.0-alpha.3](https://github.com/aircan80/vibeminer/releases/tag/v0.1.0-alpha.3) remains exact source `2382261`: Atlas discovery, saved offline metadata, install-list inspection and root FileDataID metadata lookup. Later source `47b2b43` adds selected encoded-file candidate/retention controls and exact-variant offline reopening; all four native targets passed [CI run 39111231067](https://github.com/aircan80/vibeminer/actions/runs/39111231067). That checkpoint also has 722 clean Java cases, Linux bundled-runtime checks, six native UI harnesses, nine offline protocol entrypoints and 17 exact image-reference comparisons. These source capabilities are not retroactively part of alpha.3.

Application services separately own install-list, root lookup and selected-file policy, bounded jobs, cancellation, canonical acquisition consent and offline behavior. Infrastructure gateways own source validation, HTTP, binary mechanics and persistence through application ports. Shared acquisition preserves independent consumer cancellation and actual verification receipts. The lazy bootstrap runtime composes shared storage/catalog/transport/workers; metadata-only startup does not acquire a content-store lock or issue content HTTP.

`BuildObjectCatalog`/`SqliteBuildObjectCatalog` share `SqliteCatalog` migration ownership. Immutable source aliases, actual URL/time/verifier receipts and exact-build memberships publish transactionally after verified immutable CAS bytes. Selected-file reservations and associations preserve canonical encoding decisions and source origins across restart. A completed encoded object is not proof of its decoded CKey. The bounded #79 production-service diagnostic established encoded Item retention and fresh-instance zero-HTTP reopen, with temporary cleanup, rather than general row research or full archives.

#80 shared canonical claim contracts and typed proof codec are delivered source, while root/install guard integration remains in progress; global cross-workflow canonical enforcement is not yet claimed complete. #81 now has a pure physical Item projection parser in `3f19b89`, with attributed four-field numeric candidates and explicit scope. The later `fad1f0c` local full check passed 743 cases; this newer parser source has not yet received its own four-platform checkpoint. Derived index/application preparation and the production Item table UI remain unfinished. Neither the two-record diagnostic nor this pure parser establishes general names, search, complete decoded-table CKeys, comparisons or GC.

The formal contracts below preserve remaining acceptance. Missing-local, corrupt, unsupported, incompatible, cancelled and unavailable outcomes remain distinct; raw provenance is retained rather than reconstructed as a hypothetical successful request.

## Architectural position

Use a modular monolith: one process, eight Gradle compilation boundaries and explicit dependency inversion. Organize bounded responsibilities inside those layers; avoid a service framework, event bus, speculative plugin loader, generic repository framework or one module per screen. Current modules are `domain`, `application`, `infrastructure`, `desktop`, `bootstrap`, `protocol-tact`, `protocol-db2` and `format-wow`. Add boundaries only when a concrete slice justifies them; no adapter split is required by this document. JPMS descriptors remain a separately validated packaging decision.

Current production compile dependencies (arrows mean compile dependencies; direct domain dependencies are shown even when also reachable transitively):

```text
bootstrap      -> desktop, infrastructure, application, domain
desktop        -> application, domain
application    -> domain
infrastructure -> application, domain, protocol-tact, format-wow
protocol-tact  -> domain
format-wow     -> protocol-db2
protocol-db2   -> JDK only
domain         -> JDK only
```

`format-wow` exposes `protocol-db2` with a Gradle `api` dependency; infrastructure therefore also sees its pure DB2 models transitively. No inverse dependency from `protocol-db2` to `format-wow` exists. Test-only dependencies used by bootstrap architecture/integration checks do not change the production graph.

- `domain`: immutable identities, provenance/proof values, capability/completeness and invariant checks. No application ports, HTTP, JDBC, JavaFX, filesystem work or adapter dependencies.
- `application`: explicit use cases, policy, plain immutable projections, cancellation/job contracts and outbound ports; domain/JDK only. No concrete protocol parsers, transport responses, SQL or UI controls in its API.
- `infrastructure`: implementations of application ports for official transport, protocol orchestration, SQLite, CAS, schema-resource loading and source evidence. Application owns policy; adapters own byte/persistence mechanics. Pure parsing belongs in the existing pure modules.
- `desktop`: Atlas JavaFX views/presenters, navigation, UI-only observables and generation-aware state. No direct HTTP/JDBC, mining filesystem work or concrete protocol calls; explicit export/path selection supplies inputs to use cases rather than performing mining I/O.
- `bootstrap`: construction, configuration, lazy shared runtime, owned worker/resource lifecycle and package entrypoint. The only layer composing UI and concrete adapters; no research or storage policy.
- `protocol-tact`: bounded configuration, BLTE, IN, root, EN and archive-index parsing/verification primitives. No HTTP, SQL, filesystem acquisition, application or JavaFX dependencies. Parsers consume explicit bounded inputs and return models/proofs rather than fetching hidden dependencies.
- `protocol-db2`: bounded WDC5 headers/section/storage metadata, fixed/sparse indices, bit and numeric primitives. JDK only; no application, HTTP, SQL, JavaFX or inverse WoW-format dependency.
- `format-wow`: pure WoW-specific interpretation contracts and bounded physical Item projection composed over `protocol-db2`. It does not fetch schemas/content, persist rows, schedule jobs or mutate UI. Infrastructure loads attributed pinned schema resources; exact candidate selection remains distinct from validated whole-table semantics.

Search/index publication and comparison remain future use-case/adaptor slices; their required behavior is specified below, not an instruction to add empty Gradle modules. Keep paging/storage separate from parsing and preserve build membership separately from shared derived artifact identity. Ordinary CI enforces allowed dependencies and parser purity; sharing a model never authorizes networking, persistence or UI access from a pure parser.

## Identity and provenance contracts

`ProductId` is an official verified source identifier, not a guessed mapping from Forever/Retail labels. Keep game family, edition, product/channel, region and display labels distinct. A pinned build snapshot contains official product, region, exact configuration identities, upstream version metadata, captured discovery evidence and applicable product configuration. `BuildSnapshotId` is a local stable ID for that immutable resolved snapshot; display build numbers alone are never keys. Locale is a separate `ContentContext` dimension, allowing multiple locale views over the same pinned build.

Retain source URL, capture time, local SHA-256, protocol identity and interpretation revisions. Repeated discovery can create observations pointing to the same snapshot. CDN locations and availability checks are time-stamped acquisition evidence, not mutable semantic build identity. Never change a saved build to the latest pointer when requested content is missing. A build configuration hash alone is not sufficient provenance for a reproducible acquisition.

Protocol `ContentKey`/`EncodingKey` and local `ObjectDigest(SHA-256)` are distinct typed values. An encoding is a physical representation; equal decoded content may have different encoded bytes. Do not conflate them. Physical identical verified bytes share one object; memberships, protocol aliases and provenance refer to it. Hashing content can deduplicate previously unknown aliases after download; metadata cannot guarantee avoidance of all repeated network bytes before identity is known. Never claim encoded and decoded representations are duplicate copies of the same bytes. Avoid persistently materializing decoded files when streaming directly to a derived artifact suffices.

Derived artifacts have keys over all source digests, decoder version, exact schema revision, locale and transformation options. Build membership points at shared artifacts; rows are stored once per artifact, not copied per build. Relationships derived from multiple tables include every dependency. Captions/field interpretations retain community versus Blizzard provenance.

## Application APIs and jobs

Use explicit use cases, initially `RefreshDiscovery`, `ListDiscoveredBuilds`, `OpenWorkspace` and `RestoreWorkspace`. Extend only with a user slice. Domain concepts are not generic CRUD repositories.

Long-running use cases return a framework-free `JobHandle<T>`: immutable JobId, completion (`CompletionStage<JobOutcome<T>>`), cancellation request and a bounded progress subscription. Define `JobOutcome` as succeeded, failed, cancelled, with one terminal result. A cancellation request is cooperative and idempotent; successful completion may win an already committed operation. Document that race rather than rewriting success as cancellation. Do not equate thread interruption with user cancellation.

Progress snapshots carry JobId, request/context generation, monotonically increasing sequence, stage, determinate totals only when known, transferred unique bytes, reused bytes, and optional safe diagnostics reference. Stage counts are distinct from bytes. Coalesce transient events so slow listeners cannot consume unbounded memory; completion remains reliable even if intermediate events are dropped. Subscription closes on terminal/disposal. Initial implementation can use a tiny callback subscription interface; introduce Flow only if actual consumers justify complexity.

Application job coordinator uses bounded task concurrency and a separate bounded transfer limit. Owned execution resources are injected; shutdown cancels work, closes subscriptions, waits a bounded interval and closes store connections. Parser loops and streaming operations check cancellation between bounded units. A subscriber cancelling its request does not abort a shared transfer still needed by another subscriber. Release leases after I/O termination, including exceptions.

JavaFX presenter marshals callbacks onto the FX thread; checks workspace/request generation before applying every result. Progress never mutates JavaFX state directly. Switching workspaces detaches a viewer, not an unrelated explicitly retained background archive job. Persistent transfer recovery is a later acquisition concern; do not imply a first-slice in-memory job survives a crash.

## Errors, availability and queries

Use typed application failures: invalid request, transport unavailable, source object missing, integrity failure, unsupported format/layout, encrypted without supported key, incompatible schema, storage/quota failure and cancelled outcome. Include retryability and safe contextual identifiers. Adapters translate low-level exceptions with diagnostic causes retained locally. UI maps codes into actionable text, not raw stack traces. No exception message parsing for control flow.

Expected capability/availability states are normal data, not thrown errors: metadata-only, selected-content retained, partial, uncached, offline-ready for declared scope, format unsupported, encrypted. File absence differs from unknown name and unreachable CDN. Completeness accompanies every query/comparison; missing inputs cannot produce an asserted removal.

`PageRequest` has bounded limit, allowlisted sort/filter and opaque cursor. `Page<T>` has optional next cursor, optional total, pinned build/artifact snapshot and coverage. Stable tie-breaker ID prevents unstable sorting. Bind cursors to query and snapshot version; reject expired cursors on index replacement rather than returning mixed-build pages. Prefer keyset paging for large results; support constrained offsets only where UI needs measured random access. Search/cancel uses request generation and database cancellation where supported; result limits remain enforced regardless of UI.

## Storage consistency boundary

Application owns retention policy; storage adapter owns transaction/locking implementation. Prefer purpose-built atomic port operations (`commitVerifiedObject`, `retainScope`, `releaseBuild`, `acquireReadLease`) to exposing JDBC transactions to all layers. Content store and catalog form one consistency subsystem through those operations even when files and SQLite cannot share an ACID transaction.

Publication protocol: stream to app-owned temp path; enforce bounds; verify protocol hash and compute SHA-256; atomically publish immutable object on same filesystem, tolerating another winner; transactionally record object aliases/membership. A crash after publish can leave an unreferenced object; recovery reconciles safe orphans. Never commit a ready reference before durable object publication. Document fsync and SQLite durability policy; atomic rename alone does not promise power-loss durability. Failing metadata commits do not make a partial build complete.

GC must claim/delete under coordination with reference creation and active leases. It cannot merely SELECT zero references and unlink later: a new reference could arrive meanwhile. Mark a deletion candidate/generation transactionally, exclude new leases/memberships or make them retry, then delete, finalize and recover interrupted states. Test the races. Pinned scope, active readers, shared jobs and export users protect objects. Leases are per active instance with explicit shutdown/recovery; crash leftovers cannot permanently pin everything.

The first release supports one app process owning a store, enforced by a lock and clear second-instance behavior. Multi-process shared-store access is deferred until needed. Path traversal, symlinks, malicious range lengths, decompression ratios and oversized records require explicit bounds. Download sources come only from validated configured source metadata; no implicit game-path adapters exist.

## Test and architecture enforcement

Define behavioral contracts before implementation and use failing synthetic tests first where practical. Unit tests target values/invariants, pure parsers, retention decisions, pagination and comparison semantics. Adapter contract suites run the same use-case expectations against fakes and actual temporary SQLite/object storage. HTTP adapter integration tests use a local controllable server; ordinary tests never depend on CDN availability. Live protocol evidence is a separate opt-in probe with pinned identities and recorded transfer sizes.

Enforce Gradle project dependency directions and ArchUnit package restrictions in ordinary CI: domain/application cannot import JavaFX, JDBC, HTTP clients or infrastructure; desktop cannot import adapter packages; protocol/format cannot import networking/persistence/UI. Test visibility rules on production classes, allow testing dependencies deliberately. Compile-only source checks are insufficient for accidental runtime wiring, so bootstrap integration creates a real app service graph.

Mandatory acquisition integration cases: wrong range, truncation, corruption, cancellation and subscriber races, concurrent same object, crash/recovery publication, GC versus new references, pinned/shared build removal, offline partial scope and index invalidation. UI evidence includes stale results and interactive paging, plus actual native accessibility/packaging checks on Windows/macOS/Linux. Do not chase arbitrary coverage percentages or test accessors merely to raise numbers.
