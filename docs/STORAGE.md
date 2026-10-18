# Shared archive and storage plan

Formalized specification. Implementation and validation status are tracked in GitHub Issues.

## Current implementation — metadata persistence (#15)

`SqliteWorkspaceRepository` persists immutable build snapshots, captured source documents and named locale views in `catalog.sqlite`. Source-document bytes are shared in `source_blobs` by SHA-256; per-snapshot source rows preserve URL, capture time and ordering. Restoring a workspace reconstructs domain values and checks their identities and source digests. Renaming affects the workspace label; removing a workspace leaves snapshot/source history intact. This is offline metadata persistence, not retained game content or an implemented archive-cleanup policy.

The portable application-data decision is implemented by `AppDataDirectory`: Windows uses `%LOCALAPPDATA%/VibeMiner` (with a home-based fallback); macOS uses `~/Library/Application Support/VibeMiner`; Linux uses absolute `$XDG_DATA_HOME/vibeminer` or `~/.local/share/vibeminer`. Set `-Dvibeminer.dataDir=/absolute/app-owned/path` to choose another root. Relative overrides are rejected. This chooses a location; it does not migrate existing data or provide a portable installer mode. No game-directory discovery occurs.

## Current implementation — byte-store foundation (#17)

`FileObjectStore` accepts a caller-owned stream, expected whole-stream checksum (MD5 or SHA-256), exact length, byte limit and cancellation. It stages bytes under its own `work` directory, computes local SHA-256, verifies before atomic publication, and converges identical concurrent publications onto one physical object under `objects`. Existing conflicting/corrupt bytes are rejected and preserved rather than silently overwritten. Supplied whole-stream MD5 verification is not automatically BLTE EKey verification: the latter can cover only a header.

A process lock excludes a second store owner; coordinated publication and read leases protect ownership while operations/readers are active. Stored reads rehash before exposure. Absent objects, corrupt bytes, limits, I/O failure, closed/busy state and store-in-use have distinct outcomes. Startup removes abandoned regular staging files. File contents are forced before atomic move; this does not establish complete filesystem/SQLite crash recovery or power-loss durability.

The metadata source-blob table and filesystem store are currently separate foundations; no cross-store alias/membership catalog is implemented. No network fetching, transfer coalescing, build-scope retention, quotas, eviction, GC, derived-artifact sharing or full archive workflow is delivered by #17 alone. The specification below describes those later invariants. Synthetic tests exist for workspace restart/integrity and byte-store publication, cancellation, corruption, concurrency and read ownership; real game payload/complete retention support is not established by them.

## Invariants

One app-owned immutable object store owns retained bytes. Cache and archive are retention policies and references, never separate directories holding the same payload. Do not inspect game installations. Content equality and game identity are separate: physical bytes may be shared while build membership, edition semantics, provenance and search scopes remain distinct.

Use local SHA-256 plus length as physical blob identity; represent Blizzard CKey/EKey/configuration identities as typed source identifiers and verified mappings, not interchangeable SHA-256 strings. Different encodings of one decoded content may genuinely have different bytes; byte deduplication does not require retaining every encoding. A protocol alias can be published only after its applicable verification succeeds. Never deduplicate on filename, numeric FileDataID, version label or a truncated identifier. Document collision handling: unequal lengths or mismatching bytes under an existing identity are integrity failures, not overwrite permission.

All raw and derived byte payloads go through the same immutable blob repository. Typed references describe raw/configuration/decoded/index purposes. If a transformation yields identical bytes, its artifact descriptor references the same physical blob. Build-scoped membership must not duplicate shared decoded rows: keep shared artifact records keyed by artifact identity, and scope them through membership. SQLite query structures may legitimately contain distinct indexed representations; count their actual bytes separately and do not claim those are duplicated raw files.

## Contract proposals

- `ObjectRepository`: find verified blob, acquire read lease, stage bounded stream, promote verified blob, report existence/integrity. No networking or JavaFX types.
- `SourceObjectMapping`: source namespace + identity type + full digest + relevant verification specification maps to physical blob; includes expected/observed lengths and verification evidence.
- `BuildCatalog`: immutable exact-build snapshots, discovered history, scope definitions, content membership and provenance. Mutable labels/selection are workspace settings, not build identity.
- `RetentionService`: retain/release selected scopes, estimate disk effects, prepare/execute cleanup with current-reference recheck. Retained means protect existing required objects and record desired absent members; it does not imply complete.
- `ArtifactRepository`: key = canonical transform kind/version + ordered source blob identities + exact schema revision + locale + normalized options + relevant dependency versions. Outputs and records attach to this key; many builds reference it. Change any interpretation input and receive a different artifact identity.
- `OperationLease`: protects live readers, acquisition promotion, indexing and exports. UI cancellation releases only that subscriber; remaining consumers retain their lease and transfer.

The application layer orchestrates these ports. SQLite/filesystem adapters implement them. Network/protocol modules never write arbitrary build folders; views cannot delete object paths directly.

## Manifest and archive model

Build snapshot identity includes product identifier, edition adapter namespace, channel, region, version label and pinned build/CDN/product configuration identities where present. Locale is a requested scope; represent it explicitly in requests and results rather than treating the display label as identity. Distinguish a repeated version label with different configs. Persist observation documents and timestamp separately from the immutable build identity.

Manifest entries reference source identities, expected length when known, selected representation and verification state. Manifest closure can be unresolved: metadata-only, selected scope planned, acquiring, verified complete for scope, partial missing, unavailable upstream, unsupported resolution, corrupt. Completeness is relative to a declared scope and resolved dependency closure; never say full build when only selected tables are present. Root/encoding/index dependencies required to resolve an offline scope are themselves retained. Unknown objects mean unknown totals, not zero.

Preserve known build snapshots after upstream pointers move. A previous build can still be requested if its exact configs remain known and data reachable. Removal of a workspace is separate from deleting retained coverage or history; user actions must explain which references change.

## Concurrency, crash consistency and cleanup

A single-writer app-store lock is the initial supported model, with concurrent jobs inside one process. Reject a second writer clearly rather than relying on undocumented cross-process coordination. Coalesce requests by resolved source identity before bytes arrive; converge duplicate aliases onto the same local blob after digest calculation. One transfer may have several independently cancellable subscribers. Bound temp space and account for in-flight reservations.

Stage to an app-owned file on the store volume; validate length, source digest and local digest, flush and atomically promote using no-replace semantics. Publish database aliases/membership only after promotion. There is no filesystem/SQLite cross-resource atomic transaction: explicitly journal states and recover. A crash before DB commit may leave a valid orphan, which reconciliation can adopt or later reclaim. A DB row with an absent file must become missing/corrupt, never verified-complete. Use SQLite transactions for references, job status and retention changes. Recovery is idempotent; partial files are not visible as complete objects. Start with object-boundary retry; partial-byte resume is later.

GC marks candidates without persistent references, retention roots or active leases, then rechecks under the shared store mutation coordinator before tombstone/rename and delete. New readers acquire leases under that coordinator. Promotion and scope pinning use it too. Recover tombstones after crash. Do not rely on a mutable reference count alone; derive/reconcile reachability from memberships and artifact dependency edges. Reserved pins must exist before queued work starts. Metadata, notes/settings and credentials are not generic cache candidates. Quota eviction affects unpinned data; if pins exceed quota, show that condition and block additional reservations rather than silently deleting archives.

## Storage arithmetic

Report byte units and measurement time. For resolved physical object sets B for a selected scope, R for other roots and A for active leases:

- Logical size: sum expected decoded/file sizes of manifest members, including repeated logical paths if appropriate; label the representation.
- Stored unique: sum sizes over the distinct present physical blobs; report whole-store physical payload and SQLite/temp overhead separately.
- Shared bytes: present blobs reachable from both B and another retained/workspace scope. Clearly choose and document comparison roots.
- Additional download: missing distinct required source representations with known transfer lengths. This is not logical size minus stored size; encodings/ranges differ. Show unknown counts separately.
- Reclaimable after action: present objects made unreachable after proposed root changes, excluding A and dependencies still needed elsewhere. Recheck at execution; active work can change the estimate.

Transport bytes downloaded may exceed final payload bytes due to archive/index overhead. Show that separately; never promise deduplication eliminates metadata/range overhead.

## TDD slices and validation

First storage slice: synthetic A={x,y}, B={y,z}; retain A then B, assert three physical payloads and one y; reopen both offline; remove A and reclaim x only. Include concurrent requests, cancel one subscriber and preserve the other. Then fake promotion/DB crashes at every boundary and verify recovery never reports missing bytes as complete. Property tests exercise arbitrary membership graphs and cleanup: no live referenced object is deleted.

Additional cases: duplicate aliases; corrupt existing blob; inconsistent length/digest; quota while pinned; active reader during cleanup; schema/locale/options invalidating derived reuse; identical transformed output sharing one blob; partial dependency closure; manifest totals unknown; disk full; cross-platform atomic move/file-handle behavior. Integration tests use temporary directories and SQLite; no live CDN dependency. This plan creates no tests yet.

## Risks and open decisions

Bulk manifests/indexes can dominate initial disk/download cost; measure before UI promises. A reproducible complete historical build requires retained resolvers as well as payloads. SQLite logical page freeing differs from filesystem reclaimable bytes; do not count a removed row as immediate disk reduction. Native file locking and directory durability differ across Windows/macOS/Linux and need packaging/runtime validation. Define explicit checksum revalidation policy and corruption behavior before claiming permanent integrity.
