# Layered Java architecture design

Formalized architecture for issue #12. These contracts are implementation requirements; capabilities are accepted only after their tests and evidence pass.

## Current implementation — workspace, object-store and decoder slices

Source status for issues #15, #17 and #31; review and completion evidence remain in their issues. The discovery shell uses asynchronous `DiscoveryService` calls and immutable build snapshots. `DefaultWorkspaceService` delegates blocking persistence to an injected executor through `WorkspaceRepository`; `SqliteWorkspaceRepository` stores pinned metadata and locale workspaces. `WorkspacePresentationModel` keeps presentation state separate from persistence and rejects stale completions using a generation counter. Bootstrap wires the catalog under the app-owned data directory.

`ObjectStore` is an application port implemented by `FileObjectStore`; it verifies bounded supplied streams and publishes immutable SHA-256-addressed bytes, independently of the GUI and network. This foundation has read leases and exclusive store ownership, but does not yet implement build membership, retained scopes, download coalescing or cleanup policies. The desktop bootstrap currently wires the workspace catalog, not a complete acquisition/archive pipeline.

The extracted `protocol-tact` module now contains a pure bounded `BlteDecoder` for N/raw and Z/zlib, exercised by synthetic fixtures. It receives byte arrays, limits, expected decoded size, optional protocol hashes and cancellation; it neither acquires nor persists content. No DB2/search or real-payload compatibility claim follows from this decoder slice.

Raw metadata provenance includes captured document bytes, HTTPS source URL, timestamp and verified SHA-256. Workspace storage failures distinguish unavailable storage, corrupt metadata, missing workspace and limits. Object integrity/missing/limit/I/O/ownership failures and decoder malformed/integrity/unsupported/limit/cancelled outcomes stay separate. These implemented subsets do not imply the complete job, capability, paging or archive contracts below already exist.

## Architectural position

Use a modular monolith, a single process with explicit dependency inversion. Organize code by bounded responsibility inside layers rather than one package per technical noun. Begin with five Gradle modules; add boundaries when a real slice needs them. Avoid a service framework, event bus, plugin loader, generic repository framework or one module per screen. Gradle projects are compilation boundaries; defer JPMS descriptors until the JavaFX/runtime packaging spike proves the dependency graph supports them. A runnable bundled runtime is an early requirement, not a reason to design every future module upfront.

Dependencies (arrow means compile dependency):

```
bootstrap -> desktop -> application -> domain
bootstrap -> infrastructure -> application -> domain
infrastructure -> domain
```

- `domain`: immutable identities, value types, capability/completeness states, invariant checks. Only JDK; no HTTP, SQL, JavaFX, filesystem access or concurrency runtime. Does not know application ports.
- `application`: use cases, input/output DTOs, policy orchestration, job/cancellation contracts and outbound ports. Depends only on domain and JDK. Owns interfaces needed by use cases; never a static service locator. DTOs expose no JavaFX properties, JDBC results, HTTP responses or adapter exceptions.
- `infrastructure`: initially separate `discovery`, `transport`, `protocol`, `persistence` packages. Implements ports with JDK HTTP and SQLite. Protocol parsing is pure byte-to-model work, not an HTTP/SQL operation. Introduce format/storage submodules at the milestones below rather than letting this temporary assembly grow indefinitely.
- `desktop`: Atlas JavaFX views, presenters/viewmodels, UI-only observable state, navigation and rendering. Talks only to application inputs/outputs. No direct SQL/HTTP/filesystem mining. A file chooser may collect an explicit export/archive destination; actual file operations remain adapters.
- `bootstrap`: explicit construction/wiring, configuration, startup/shutdown, executable packaging. The sole place with visibility into UI and concrete adapters. No business policy.

First extraction when verified file acquisition begins:

```
infrastructure-discovery -> application, domain, protocol-tact
infrastructure-http      -> application, domain
infrastructure-storage   -> application, domain
protocol-tact            -> domain
bootstrap                -> all required adapters + desktop
```

`protocol-tact` owns configuration/index/root/encoding parsers and supported BLTE decoding. Parsers accept bounded byte input/streams and explicit resource limits, return format models, perform no source lookup, database access, job scheduling or hidden downloads. Product resolver orchestration stays in application, which obtains indexes/content through ports and invokes an explicit decoding port implemented by the protocol adapter.

When DB2/search becomes real, add `format-wow` (pure parser/schema matching), `infrastructure-query` (SQLite prepared queries/index persistence) and application use cases. Format code cannot call HTTP, SQL or JavaFX. Search/storage adapters do not infer WoW semantics. Add comparison as application policies/domain structures first; its own module only if size and stable reuse justify it. Avoid blanket `implementation` dependencies between every project.

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
