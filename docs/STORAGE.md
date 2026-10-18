# Shared archive and storage plan

Formalized specification. Implementation and validation status are tracked in GitHub Issues.

## Released metadata persistence (#15)

Alpha.1 (`42b8be9`) includes saved offline metadata workspaces. `SqliteWorkspaceRepository` persists immutable build snapshots, captured source documents and named locale views in `catalog.sqlite`. Source-document bytes are shared in `source_blobs` by SHA-256; per-snapshot source rows preserve URL, capture time and ordering. Restoring a workspace reconstructs domain values and checks their identities and source digests. Renaming affects the workspace label; removing a workspace leaves snapshot/source history intact. This is offline metadata persistence, not retained game content or an implemented archive-cleanup policy.

The portable application-data decision is implemented by `AppDataDirectory`: Windows uses `%LOCALAPPDATA%/VibeMiner` (with a home-based fallback); macOS uses `~/Library/Application Support/VibeMiner`; Linux uses absolute `$XDG_DATA_HOME/vibeminer` or `~/.local/share/vibeminer`. Set `-Dvibeminer.dataDir=/absolute/app-owned/path` to choose another root. Relative overrides are rejected. This chooses a location; it does not migrate existing data or provide a portable installer mode. No game-directory discovery occurs.

## Current implementation — byte-store foundation (#17)

`FileObjectStore` accepts a caller-owned stream, expected whole-stream checksum (MD5 or SHA-256), exact length, byte limit and cancellation. It stages bytes under its own `work` directory, computes local SHA-256, verifies before atomic publication, and converges identical concurrent publications onto one physical object under `objects`. Existing conflicting/corrupt bytes are rejected and preserved rather than silently overwritten. Supplied whole-stream MD5 verification is not automatically BLTE EKey verification: the latter can cover only a header.

A process lock excludes a second store owner; coordinated publication and read leases protect ownership while operations/readers are active. Stored reads rehash before exposure. Absent objects, corrupt bytes, limits, I/O failure, closed/busy state and store-in-use have distinct outcomes. Startup removes abandoned regular staging files. File contents are forced before atomic move; this does not establish complete filesystem/SQLite crash recovery or power-loss durability.

Closed shared-acquisition slice #32 coordinates source requests over this byte-store foundation with independent consumer cancellation. This is not a retained-build cleanup policy.

## In-progress durable content catalog (#36) and workflow (#37)

`BuildObjectCatalog` and `SqliteBuildObjectCatalog` now have source implementations over shared `SqliteCatalog`: immutable source aliases, physical object records, build-role membership and source verification evidence preserve actual URL, timestamp and verifier semantics. Workspace metadata and content catalog share schema/migration management. Verified lookups check local readiness; persisted references can still describe missing bytes. Publication precedes catalog attachment; SQLite cannot make the filesystem and catalog one atomic resource.

The #36 catalog/provenance refactor passed 40 focused tests and independent review, then a clean-checkout full check and Linux SQLite/JavaFX package launch. The #37 install-scope backend passed 19 focused checks; bounded real content and fresh-service offline reuse passed, and the two-JVM UI workflow and final captures passed. These changes are not in alpha.1 and do not deliver general scope pinning, quota/eviction, GC, complete crash reconciliation, derived-artifact sharing or older-build archive acceptance. The specification below preserves those remaining requirements. A small verified real install manifest is transport/parser evidence, not a full retained build.

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

Additional cases: duplicate aliases; corrupt existing blob; inconsistent length/digest; quota while pinned; active reader during cleanup; schema/locale/options invalidating derived reuse; identical transformed output sharing one blob; partial dependency closure; manifest totals unknown; disk full; cross-platform atomic move/file-handle behavior. Integration tests use temporary directories and SQLite; no live CDN dependency. Existing byte-store/catalog tests establish their scoped invariants; the complete retention/cleanup matrix above remains acceptance to deliver.

## Risks and open decisions

Bulk manifests/indexes can dominate initial disk/download cost; measure before UI promises. A reproducible complete historical build requires retained resolvers as well as payloads. SQLite logical page freeing differs from filesystem reclaimable bytes; do not count a removed row as immediate disk reduction. Native file locking and directory durability differ across Windows/macOS/Linux and need packaging/runtime validation. Define explicit checksum revalidation policy and corruption behavior before claiming permanent integrity.


## Completion-verified streaming publication (#46)

`ObjectStore.publishVerified` supports an exact-length bounded encoded stream when a whole-encoded checksum is not known upfront. The store computes physical SHA-256 into one staging file, observes actual EOF and exact length, then invokes the completion verifier once. Only successful verification and subsequent cancellation checks permit force/atomic promotion. The inline protocol verifier must have checked required BLTE chunks and complete decoded CKey/length. A header EKey is never substituted for a whole-encoded MD5. The existing checksum publication path shares the same storage implementation.

Caller ownership of streams, active leases, process exclusion, corruption handling and dedup remain in force. Failed verification, I/O, malformed length or cancellation leaves no new published object or ready membership. Catalog attachment remains a later transaction; this does not make filesystem and SQLite promotion one atomic resource.

The first production root-retention design uses one complete encoded CAS object, without persistent chunks or a decoded root duplicate. Selected EN proof pages remain bounded session memory initially. After protocol verification and publication, an offline read lease can decode/parse requested IDs in a second bounded pass. Structural parser failure blocks lookup results without relabeling correctly protocol-verified raw bytes as corrupt. This is the storage contract for the upcoming lookup slice, not a delivered raw-file/archive workflow.

## Explicit metadata retention and accounting backend (#56)

The backend adds immutable selected-scope definitions and reversible pins over the shared CAS. It does not delete bytes, enforce a quota or provide a storage-management screen. Existing install/root download entrypoints still require a separate integration step to record desired scope before acquisition; merely having this backend does not establish completed archive-management acceptance.

`RetentionScope` binds one exact snapshot to `INSTALL_METADATA` (`BUILD_CONFIG` and `INSTALL_MANIFEST`), `ROOT_METADATA` (`BUILD_CONFIG` and `ROOT_MANIFEST`) or `LEGACY_RETAINED` requirements. A requirement records its role, optional exact `ResolvedSourceIdentity`, optional encoded transfer length and optional decoded logical length. Unknown values remain unknown, including the distinction between unknown and known zero. Scope identity hashes a canonical, role-sorted complete definition; pin state does not change that identity. Conflicting known source/size facts fail integrity checks rather than silently replacing an earlier definition.

Schema version 4 adds `retention_scopes` and `retention_scope_requirements` without rewriting snapshots, workspaces, source aliases, acquisition evidence or existing memberships. Migration creates a visible pinned compatibility scope for every preexisting build-role membership. These preserve existing retention intent; their encoded length is known from object metadata and decoded logical length remains unknown. Releasing one explicit scope does not release overlapping compatibility pins. Creating new scope definitions does not silently remove old pins.

The asynchronous `RetentionService` exposes three operations:

- `pin(scope)` persists an immutable definition and activates its pin. Desired roles may still be missing; this is not a completeness claim.
- `release(scopeId)` deactivates only that pin, leaving its definition, build history, memberships, aliases and physical bytes intact. Removing a workspace remains independent.
- `measure(scopeId, limits)` observes the selected scope and other active pins with bounded scope/requirement counts. Exceeding limits fails instead of reporting partial totals.

The service owns neither its executor nor catalog/store. Canceling/closing requests cooperative cancellation; callers stop workers before closing storage. SQLite operations observe cancellation at transaction boundaries, not through an immediate interruption guarantee.

Measurements identify an advisory `startedAt`/`completedAt` observation window. Definitions/pins come from one SQLite transaction; membership/alias observations and physical checks occur within the window rather than claiming an atomic disk snapshot. Physical presence is established through verified CAS read leases, with actual length checked and leases closed promptly. A missing physical object differs from corrupted bytes; corrupt metadata or contradictory sizes fail integrity checks.

Accounting uses these precise bases:

- Logical bytes are decoded metadata-object size facts per exact `(snapshot, role)`, with an unknown count. They are not the logical size of the game files listed by a root/install manifest. Repeated scopes do not create additional logical roles.
- Additional encoded bytes count an already proven missing physical SHA-256 object once, even when multiple source aliases map to it: restoring those exact bytes satisfies the existing mappings. When no physical mapping is known, deduplicate by exact source identity; never infer byte equality from length or a key guess. Unknown source/length counts remain explicit. An unknown source can bind only through its exact build-role membership, never a same-named role from another build. Known decoded/encoded size facts are rechecked against that resolved source as well as explicitly declared identities.
- A known source with an existing verified local alias can require zero transfer while its selected build-role attachment is still unresolved. Available bytes do not fabricate a ready membership.
- Present unique bytes count distinct verified physical SHA-256 objects once across aliases, roles and builds. Shared present bytes intersect the selected physical set with the physical set requested by other active pins.
- `losesProtectionBytes` counts selected present bytes not protected by another active pin; it is zero if the selected scope is already unpinned. This is a protection-change estimate, **not safely reclaimable space**. Active jobs, publication and deletion coordination are outside this backend.

Known sums use checked arithmetic; overflow fails instead of wrapping. Catalog-reachable payload totals exclude unregistered orphans, staging, SQLite files and filesystem allocation overhead, so they must not be labeled whole-store disk usage. Alias/history rows are evidence, not a future rule that protects every byte forever.

Ordinary synthetic checks cover overlapping scopes, distinct aliases sharing physical bytes, unknown facts, corruption, conflicts, cancellation, limits, compatibility migration, concurrent migration, workspace independence and restart. Deletion, GC/tombstone recovery, quota reservations, derived artifacts and workflow/UI integration remain separate work. No reclaimable-byte or crash-safe cleanup claim follows from releasing a pin.
