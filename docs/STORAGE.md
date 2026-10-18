# Shared archive and storage plan

Formalized specification. Implementation and validation status are tracked in GitHub Issues.

## Released metadata persistence (#15)

Alpha.1 (`42b8be9`) includes saved offline metadata workspaces. `SqliteWorkspaceRepository` persists immutable build snapshots, captured source documents and named locale views in `catalog.sqlite`. Source-document bytes are shared in `source_blobs` by SHA-256; per-snapshot source rows preserve URL, capture time and ordering. Restoring a workspace reconstructs domain values and checks their identities and source digests. Renaming affects the workspace label; removing a workspace leaves snapshot/source history intact. This is offline metadata persistence, not retained game content or an implemented archive-cleanup policy.

The portable application-data decision is implemented by `AppDataDirectory`: Windows uses `%LOCALAPPDATA%/VibeMiner` (with a home-based fallback); macOS uses `~/Library/Application Support/VibeMiner`; Linux uses absolute `$XDG_DATA_HOME/vibeminer` or `~/.local/share/vibeminer`. Set `-Dvibeminer.dataDir=/absolute/app-owned/path` to choose another root. Relative overrides are rejected. This chooses a location; it does not migrate existing data or provide a portable installer mode. No game-directory discovery occurs.

## Current implementation — byte-store foundation (#17)

`FileObjectStore` accepts a caller-owned stream, expected whole-stream checksum (MD5 or SHA-256), exact length, byte limit and cancellation. It stages bytes under its own `work` directory, computes local SHA-256, verifies before atomic publication, and converges identical concurrent publications onto one physical object under `objects`. Existing conflicting/corrupt bytes are rejected and preserved rather than silently overwritten. Supplied whole-stream MD5 verification is not automatically BLTE EKey verification: the latter can cover only a header.

A process lock excludes a second store owner; coordinated publication and read leases protect ownership while operations/readers are active. Stored reads rehash before exposure. Absent objects, corrupt bytes, limits, I/O failure, closed/busy state and store-in-use have distinct outcomes. Startup removes abandoned regular staging files. File contents are forced before atomic move; this does not establish complete filesystem/SQLite crash recovery or power-loss durability.

Closed shared-acquisition slice #32 coordinates source requests over this byte-store foundation with independent consumer cancellation. This is not a retained-build cleanup policy.

## Delivered catalog and active publisher ownership

Current development source `492eab1` passed 1,169 Java cases, distribution assembly, all four native targets and eight Linux UI workflows; [central evidence](PLAN.md#delivered-baseline-and-current-work) separates this from published alpha.5. Later accepted `c0292c3` adds schema-12 shared name-artifact/context persistence, with 15 cases and 53 independent checks; it has no inherited native claim and does not enable names runtime/UI. The accepted `5d46283` repaired-reader correction preserves immutable historical capabilities while strict strong-reuse validation remains separate. #83/#17/#19 acceptance remains open; no GC or new release follows.


Current verified release is [alpha.5](releases/v0.1.0-alpha.5.md), exact `f3c4d46`, including explicit retained-metadata reuse and offline association inspection. Later read-only/repair-integration foundations are unreleased; no repair execution or GC is enabled. Historical source checkpoints below retain their original validation scope.

`BuildObjectCatalog`/`SqliteBuildObjectCatalog` share schema ownership with canonical selected-file and metadata repositories through `SqliteCatalog`. Actual source aliases, verification receipts and immutable exact-build memberships follow verified CAS publication. Filesystem promotion and SQLite commits remain separate resources.

Publisher ownership originally integrated at `6fac722` with additive conflict inventory/worker nonce and exclusive-store one-shot startup fencing. Current `891a9c4` additionally contains SQLite schema-10 metadata-association foundations and shared physical Item derived-index persistence. Preserve immutable physical bytes, original receipts, pins and legacy tokens; a schema association does not activate representation-aware runtime reuse.

Accepted post-release `ed6394b` adds associated-install readers that retain declaration-versus-actual receipt separation when reading schema-10 associations. Its service/reader acceptance does not enable runtime strong reuse or metadata repair. The independently clean 1,035-Java/24-Python check and passing local Linux native status are recorded in [the plan](PLAN.md#delivered-baseline-and-current-work); alpha.4 remains unchanged.

Accepted `fd240f7` adds SQLite schema-11 append-only repair audit under actual worker fencing. Its 19 new audit-only cases and independent 120-check validation establish persistence/ownership behavior, not execution, acquisition, byte publication or readiness. Execution-port contracts in `95c6f0a` are separate foundations; see [the repair contract](ORIGINAL-METADATA-REPAIR.md). #85 facade/lifecycle wiring is accepted in unreleased `7ffc822`, while its exact-source local Linux package/native/visual gate passed, with newer four-platform qualification pending; neither the association table nor audit schema enables that workflow by itself.

Unreleased `7ffc822` explicitly wires local retained-A reuse and associated install inspection without converting declared B into actual A or changing original receipts. Normal B acquisition remains guarded/refused. See [central checkpoint](PLAN.md#delivered-baseline-and-current-work) for scoped review/tests and passing exact-source local Linux acceptance (newer four-platform pending); no new repair or fallback follows.

The physical Item backend/runtime/Atlas journey is published in verified [alpha.4](releases/v0.1.0-alpha.4.md), exact `891a9c4`; see [central checkpoint and native validation](PLAN.md#delivered-baseline-and-current-work). Application projections reference shared artifacts rather than creating per-build decoded table copies. Names remain a pure parser foundation without delivered name-index/application/view. Final exact-source qualification of #85 explicit local reuse, #83 same-A metadata repair, quota/eviction, deletion/GC and full archive acceptance remain unfinished. Existing exact-descriptor reads retain their original proof even when canonical reconciliation blocks new acquisition. See [publisher ownership](PUBLISHER-OWNERSHIP.md) and [Item scope](ITEM-PROJECTION.md).

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

The backend adds immutable selected-scope definitions and reversible pins over the shared CAS. It does not delete bytes, enforce a quota or provide a storage-management screen. Explicit install/root download entrypoints record desired scope synchronously after canonical plan validation and before content acquisition. Planning and offline reads do not create pins. This backend does not establish completed archive-management acceptance.

`RetentionScope` binds one exact snapshot to `INSTALL_METADATA` (`BUILD_CONFIG` and `INSTALL_MANIFEST`), `ROOT_METADATA` (`BUILD_CONFIG` and `ROOT_MANIFEST`) or `LEGACY_RETAINED` requirements. A requirement records its role, optional exact `ResolvedSourceIdentity`, optional encoded transfer length and optional decoded logical length. Unknown values remain unknown, including the distinction between unknown and known zero. Scope identity hashes a canonical, role-sorted complete definition; pin state does not change that identity. Conflicting known source/size facts fail integrity checks rather than silently replacing an earlier definition.

Schema version 4 adds `retention_scopes` and `retention_scope_requirements` without rewriting snapshots, workspaces, source aliases, acquisition evidence or existing memberships. Migration creates a visible pinned compatibility scope for every preexisting build-role membership. These preserve existing retention intent; their encoded length is known from object metadata and decoded logical length remains unknown. Releasing one explicit scope does not release overlapping compatibility pins. Creating new scope definitions does not silently remove old pins.

The asynchronous `RetentionService` exposes three operations:

- `pin(scope)` persists an immutable definition and activates its pin. Desired roles may still be missing; this is not a completeness claim.
- `release(scopeId)` deactivates only that pin, leaving its definition, build history, memberships, aliases and physical bytes intact. Removing a workspace remains independent.
- Root download uses the narrower blocking `replaceUnfulfilledRoot(scope, cancellation)` repository operation: in one transaction, it validates the actual exact-build root membership, pins the explicitly selected root intent and releases differing unfulfilled `ROOT_METADATA` candidate pins for that build. Previous definitions remain as unpinned history. This operation requires a known root source and never applies to arbitrary scope kinds. Generic `pin` does not silently choose another candidate.
- `measure(scopeId, limits)` observes the selected scope and other active pins with bounded scope/requirement counts. Exceeding limits fails instead of reporting partial totals.

A failed candidate is an unfulfilled intention, not immutable proof that the build must use that representation forever. Explicitly selecting another valid candidate can replace it before a root is attached. The root workflow rejects a different candidate while any retain worker for that exact build is still active, including after facade cancellation until the worker retires; same-candidate consumers can share work. Once `ROOT_MANIFEST` is attached, its source remains immutable. No automatic fallback occurs. Same-source size contradictions remain integrity failures across history, while a released intent for another encoding remains honestly unsatisfied rather than corrupt merely because the build later retains a different encoding.

The service owns neither its executor nor catalog/store. Canceling/closing requests cooperative cancellation; callers stop workers before closing storage. SQLite operations observe cancellation at transaction boundaries, not through an immediate interruption guarantee.

Measurements identify an advisory `startedAt`/`completedAt` observation window. Definitions/pins come from one SQLite transaction; membership/alias observations and physical checks occur within the window rather than claiming an atomic disk snapshot. Physical presence is established through verified CAS read leases, with actual length checked and leases closed promptly. A missing physical object differs from corrupted bytes; corrupt metadata or contradictory sizes fail integrity checks.

Accounting uses these precise bases:

- Logical bytes are decoded metadata-object size facts per exact `(snapshot, role)`, with an unknown count. They are not the logical size of the game files listed by a root/install manifest. Repeated scopes do not create additional logical roles.
- Additional encoded bytes count an already proven missing physical SHA-256 object once, even when multiple source aliases map to it: restoring those exact bytes satisfies the existing mappings. When no physical mapping is known, deduplicate by exact source identity; never infer byte equality from length or a key guess. Unknown source/length counts remain explicit. An unknown source can bind only through its exact build-role membership, never a same-named role from another build. Known decoded/encoded size facts are rechecked against that resolved source as well as explicitly declared identities.
- A known source with an existing verified local alias can require zero transfer while its selected build-role attachment is still unresolved. Available bytes do not fabricate a ready membership.
- Present unique bytes count distinct verified physical SHA-256 objects once across aliases, roles and builds. Shared present bytes intersect the selected physical set with the physical set requested by other active pins.
- `losesProtectionBytes` counts selected present bytes not protected by another active pin; it is zero if the selected scope is already unpinned. This is a protection-change estimate, **not safely reclaimable space**. Active jobs, publication and deletion coordination are outside this backend.

Known sums use checked arithmetic; overflow fails instead of wrapping. Catalog-reachable payload totals exclude unregistered orphans, staging, SQLite files and filesystem allocation overhead, so they must not be labeled whole-store disk usage. Alias/history rows are evidence, not a future rule that protects every byte forever.

Ordinary synthetic checks cover overlapping scopes, distinct aliases sharing physical bytes, unknown facts, corruption, conflicts, cancellation, limits, compatibility migration, concurrent migration, workspace independence and restart. Deletion, GC/tombstone recovery, quota reservations, derived artifacts and storage-management UI integration remain separate work. No reclaimable-byte or crash-safe cleanup claim follows from releasing a pin.

## Canonical selected encoded files — persistence backend (#72)

The persistence backend adds schema version 5 with canonical content reservations, original encoded-source receipts and typed selected-file associations. This is not a selected-file UI, decoded-file capability, full archive or cleanup implementation. Application/gateway integration and their acceptance gates remain separately tracked in #71/#73.

A `ContentSlotKey` binds the explicitly supported `wow` namespace to an independently declared CKey. `SqliteSelectedFileCatalog.reserve` persists one candidate EKey and known encoded length before acquisition. Same-candidate requests receive the same reservation token; another candidate is busy while pending and requires replanning after a representation is retained. A CKey association is not decoded-CKey verification.

Publication has explicit durable boundaries:

1. `markPublishing` records the attempt before CAS acquisition.
2. The completing encoded verifier calls `recordVerifiedCompletion` from the CAS completion gate. The prospective physical SHA-256, length, typed encoded scope and actual transport receipt commit **before** CAS promotion. Failure to persist this proof prevents promotion.
3. `completePublication` verifies the actual CAS object under a read lease and commits its encoded alias, immutable canonical origin and retained slot. It does not attach a canceled consumer's build membership.
4. Each surviving consumer separately calls `attachAssociation`. One transaction attaches its typed selected-file record, immutable exact-build file membership and configuration/root/file dependency pin. The physical file and dependency objects are leased through that transaction. A conflict or cancellation rolls back these references together; an already recorded desired pin remains desired.

A pending attempt from another process session is observed as recovery and continues to exclude alternative encodings. The original candidate can be reserved/replayed; the recorded prospective SHA and proof reconcile promotion before a catalog commit. An uncertain attempt or one with a durable completion proof cannot be released as unpublished. Only a current owner whose source worker has conclusively retired without a completion/publication may release an empty attempt. Canceling a consumer's returned future alone is insufficient authority. These are process-crash ordering guarantees within the existing storage durability limits, not a new power-loss guarantee.

Original encoded-source evidence is normalized by namespace, EKey and verifier semantics. An exact same-EKey/length representation retained for another declared CKey can populate a new slot by rebinding only the slot; the encoded alias, scope and actual acquisition receipt remain unchanged. This avoids another representation or fabricated retrieval URL. Re-reserving an existing pending/publishing/recovery slot also adopts a subsequently committed same-EKey origin without changing its reservation token; any prospective proof must match the source, physical bytes and encoded scope before adoption. The source worker repeats this check after owning its shared acquisition flight, closing the gap between an earlier reservation and a prior flight retiring. Retained status records historical publication, not current physical availability: consumers still verify local bytes before reporting a successful offline inspection.

A typed association persists the exact snapshot, full root variant tuple (including every raw flag component and optional name-hash presence), configuration/root source receipts, chosen actual EKey, authenticated root/EN association attestation and canonical representation reference. The attestation preserves the complete bounded authenticated candidate list, optional sizes/ESpec references and declared decoded-length expectation. That expectation is not decoded verification. New attachment requires the actual EKey in the authenticated candidate facts. A known EN encoded size must match the complete receipt; an unknown EN size stays unknown when separately approved source geometry establishes the actual payload size. Legacy records lacking candidate facts remain readable metadata and cannot create a new ready association. Its full-SHA Base64url role is reconstructed and checked on read. No game payload or duplicated decoded root is placed in SQLite. Metadata records have explicit version/size bounds, strict UTF-8 decoding and immutable source checks. Archive receipts retain advertised and authenticated index geometry separately; older records with no authenticated geometry remain unknown rather than inventing size zero or agreement.

Synthetic checks cover reservation races, stale tokens, conclusive failure versus uncertain publication, verifier-gate ordering, cancellation before/after durable completion, promotion-before-catalog crash recovery, original cross-host receipt reuse, same-EKey sharing across declared CKeys, two-build typed associations, physical deduplication, schema4 migration, pin independence and rollback at all observed cancellation boundaries. Existing workspace, content-catalog and retention suites remain required. No deletion, GC, automatic encoding replacement or decoded readiness follows from this backend.

## Shared schema and startup recovery

Schema 18 installs the complete encoding lookup, durable retention ledger and declared inventory families in the existing catalog migration transaction. It adds references and derived facts, not another payload store. A failed later family migration rolls back earlier additions and the schema version together; prior observation bodies and existing records remain unchanged.

The existing canonical startup entry delegates to `SqliteContentStartupRecovery`. One unused, exclusive `FileObjectStore.StoreSession` capability admits one SQLite transaction before producers start. That transaction fences prior canonical owners/nonces, removes interrupted EN/inventory STAGING generations and recovers interrupted retention jobs. Published READY interpretations, original content proofs/receipts, attempt counts and reserved transfer/storage budgets remain intact. Paused intents stay paused: their interrupted workers become canceled failures, not queued downloads. Eligible authorized Download all work can be queued for the current mode generation without refunding uncertain prior reservations.

SQL recovery helpers require both the active recovery phase and a caller-owned transaction. They cannot reset live workers through an arbitrary UUID or a reused store capability. A failed transaction leaves the capability unusable; a fresh exclusive store lifetime is required to retry. Startup does not establish payload availability, repair missing bytes, fetch data or declare a build fully downloaded. Runtime execution and current physical-readiness checks remain separate responsibilities.

Verification includes a real schema-15 upgrade preserving all original table rows/bytes, forced late-DDL rollback, default runtime-entry recovery, published-generation preservation, paused/re-authorized/exhausted jobs, and all-family rollback after late SQL or typed corrupt-state failure. The complete infrastructure suite passed 735 cases for the schema wiring; focused independent migration/recovery checks also passed. These checks do not qualify the background downloader or GUI integration.

## Sealed inventory authority and bounded source leases

Retention targets bind the exact admitted metadata closure, completed inventory UUID, interpretation and locator reservation policy. The persisted target generation is a local issuance sequence, not an upstream build or inventory generation. A later observation of the same exact build and unchanged scope reuses the target; its first source provenance remains immutable, while each current consumer reference is validated separately. Reservation costs are bounded maxima, not measured traffic or a promise that a locator is available.

The inventory adapter keeps a bounded cache of verified source contexts so rotating between builds does not rehash complete metadata for every payload. Capacity is configurable from 1 to 16, with a compatibility default of 2; the approved runtime setting is 16. At most two cold contexts initialize concurrently. Each cached context holds source read leases, bounded provenance/configuration metadata and an EN lookup session, without decoded payload buffers or additional persistent content copies. The admitted ROOT/IN/EN/DL/DS profile uses up to seven source leases per context, or 112 at capacity 16. Idle contexts may be evicted; active consumers retain their leases until actual retirement. Shutdown closes every context and source even when an individual close fails, preserving the original failure and suppressed cleanup failures.

Mode, queue fill, job claim and payload association transactions revalidate the held authority inside their own SQL transaction. Queue drainage and historical job success do not establish Full readiness; current physical fulfillment of the complete declared scope is checked separately.

## Initial background activation limit

The initial runtime admits at most **16 simultaneous background build handles**, with at most two actual source jobs. Startup attempts to activate the first 16 previously authorized Download all intents in stable target-key order. A single bounded extra-row probe detects additional saved preferences and reports that they are deferred; startup does not silently treat this first page as every saved build or activate an unbounded queue.

Deferred preferences and their exact build scopes remain unchanged. Opening a deferred build retries its existing authorization. If all handles remain active, the build home reports an activation failure with a retry action; switching another build to On demand or allowing work to retire can free a handle. Saving a preference is distinct from successfully activating it. Failed startup activation also leaves the preference intact. There is no claim that more than 16 builds automatically resume at once.


## Per-publication staging capacity (#102)

The shared object store reserves each actual publication or original-object repair's known expected length before creating its staging file. The existing mutation coordinator serializes the app-owned work volume's usable-space observation, checked reservation arithmetic and release. Production leaves **512 MiB headroom**, matching metadata opening. Unknown or unavailable capacity fails with a typed I/O error; insufficient capacity or arithmetic overflow fails with a typed limit error. Neither outcome authorizes an unbounded write.

Capacity is checked again before atomic promotion. Full outstanding expected lengths remain reserved even after bytes have been written, although those writes also reduce observed usable space. This deliberately conservative policy may refuse work earlier than exact remaining-space accounting. It is an application admission guard, not an operating-system reservation; external writers can still cause later I/O failure. Cancellation does not release capacity until the actual operation closes and cleans its staging resources in its finalization path. Existing canonical/proof/source ownership remains separate. No retained content is deleted to make space.

This guard does not promise pre-network refusal: a source may inspect transport metadata before publication starts. It also does not complete whole-build capacity admission. Versioned bounds for additional unique payload, metadata/index/SQLite growth and temporary work, plus an exact-scope Download all preflight, remain open in #102. Cumulative retry/transfer budgets must not be relabeled as exact disk growth. Alpha.7 predates this guard and retains its documented capacity limitation.
