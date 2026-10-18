# Canonical root and install metadata

**Status: initial shared metadata/selected-file guard and actual worker ownership are integrated and active in source `6fac722` (#80/#84).** Source checkpoint `6fac722` enables the integrated canonical guard and actual-publisher ownership in the lazy content runtime. Its clean full check passed 855 Java cases with zero failures/errors/skips, plus Linux development package 17 and bundled self-storage/UI launch. [Four-platform CI 39113600719](https://github.com/aircan80/vibeminer/actions/runs/39113600719) subsequently completed successfully at that exact revision. All six native UI harnesses and all nine offline protocol entrypoints passed: seven suites reported 638 assertions, the supervisor reported 52, and the real layered-root fixture used 28 requests/two physical objects before fresh-instance zero-HTTP reopening. All 17 candidate/retention PNG comparisons matched references exactly (AE 0). These synthetic/native checks do not establish additional Item UI or repair capability.

Different declared encoding B is explicitly refused rather than strongly reusing actual A; representation-aware A-versus-B reuse (#85) and newly consented same-A metadata repair (#83) remain undelivered. Existing exact-descriptor legacy reads preserve their original proof despite a blocking canonical conflict. No deletion, GC, replacement or complete archive claim follows. See [publisher ownership/startup fencing](PUBLISHER-OWNERSHIP.md) for actual worker authority. Strong reuse below remains a target contract, not a delivered capability. Alpha.3 remains unchanged.


## Original gap and current guard boundary

Physical CAS deduplication is correct: identical SHA-256 bytes are stored once across build memberships and retention policies. Different valid BLTE encodings of the same decoded content can have different physical SHA values.

The current install source identity includes CKey, EKey, decoded length and encoded length. Its application service coalesces and reuses that complete tuple. Retained install inspection also requires the tuple and encoded length from the selected build plan. The root source identity likewise contains CKey, EKey and both lengths; root acquisition shares the full tuple. Immutable per-build roles prevent changing an attached manifest but do not prevent another build retaining an alternate encoding of the same CKey.

Without the integrated guard, equal decoded content with different encoding tuples could produce two retained physical objects. The active initial guard blocks new alternate-encoding publication across its supported producers; historical conflicting representations remain preserved and require explicit reconciliation. This is not a claim that historical duplicates were deleted or strong cross-representation reuse was implemented. A source alias or matching filename is not sufficient evidence of decoded equality.

## Uniform content-selection rule

For each explicitly reviewed game content namespace and declared CKey, choose one canonical retained encoded representation across selected files, roots, install manifests and cache/archive policies. Semantic namespace mappings are explicit; do not infer shared game identity from product prefixes, display names or matching numeric IDs. The initial selected-file registry supports verified raw product `wow`; other metadata products require a reviewed binding before semantic cross-product reuse. Physical SHA sharing remains global regardless of those mappings.

Preserve separate facts:

- **Declared build expectations:** exact snapshot and verified configuration, declared CKey, preferred/advertised EKey where present, and independently declared lengths.
- **Actual representation:** original EKey, encoded size, physical SHA and original verified source observations.
- **Verification capability:** complete encoded integrity, full decoded CKey/length verification and supported root/install structural validation are distinct attestations. Construction of an association does not upgrade capability.

A new build that declares encoding B cannot relabel retained encoding A as B. No B source alias, current EN membership, source URL or transfer observation is invented. Additional download cost can be zero because A is reused, while B's advertised encoded cost remains a separate fact. Expected decoded-length conflicts fail integrity rather than being silently normalized.

## Strong metadata reuse

Root and install workflows already verify the complete decoded CKey and length in addition to BLTE framing/chunks and their supported grammar. This is stronger than an encoded-only selected-file receipt.

Reuse of actual A for a new exact build is permitted only after:

1. Revalidate that build's retained configuration and its exact CKey declaration.
2. Find an existing canonical A with an original complete decoded-CKey attestation for the same reviewed namespace and CKey; revalidate physical bytes under a lease and replay the applicable bounded protocol/decoded verification before claiming current readability.
3. Check any independently declared decoded length against A's verified decoded length. Keep B's EKey/encoded length separate from A's actual EKey/encoded length.
4. Validate supported root/install interpretation for the requested use and attach a new immutable build-configuration-to-content association referencing the original actual source receipt.

For root, a verified configuration's root CKey declaration plus A's full decoded-CKey verification can establish content reuse without claiming that the new EN candidate set contains A. For install, the corresponding configuration CKey declaration supports the same reasoning, but its current plan/inspection DTO must first distinguish declared B from actual A. Do not bypass the current exact-tuple inspector by weakening an equality check in isolation.

An encoded-only A cannot establish this stronger metadata capability. It must be decoded and checked before metadata results become available. A later strong attestation can refer to the same encoded CAS object, without copying it. Conversely, reusing strong metadata bytes for selected-file encoded retention requires an explicit matching encoded-scope receipt; the existing stronger source identity is not overwritten.

## Safest interim guard

Before representation-aware metadata reuse is delivered, the narrow safe behavior is to detect a canonical same-CKey representation with a different EKey and return an explicit replan/unsupported-reuse outcome before acquiring the second payload. Do not silently store B, replace A, delete A or manufacture a B alias.

The initial guarded metadata workflow also reports missing or corrupt canonical A explicitly instead of fetching a repair. Its exclusive ownership and original evidence remain intact. A separate, newly consented known-cost repair of that same A is tracked in [#83](https://github.com/aircan80/vibeminer/issues/83); this restriction does not change the existing selected-file repair contract. Neither failure permits alternate encoding B.

The guard must operate across all supported content-producing paths, not merely check one service's in-memory map or existing per-build membership. Use the shared durable canonical slot and its prospective-publication recovery boundary. It must cover metadata and selected-file contenders for the same reviewed namespace/CKey. If a canonical slot is absent but verified historical metadata exists, adopt or reconcile that original receipt first; do not treat absence of the new slot row as permission to duplicate it.

Same actual EKey requests may share source acquisition. Different EKeys for one CKey are busy while the canonical winner is active; a cancelled subscriber does not release a source worker that is still publishing. A definitively failed unpublished attempt can release its reservation. An uncertain publication remains exclusive recovery state, including after process restart.

## Persistence, migration and recovery

Use a narrow metadata-content association alongside the shared canonical slot, rather than a generic proof graph. Record exact build configuration origin, declared CKey/EKey/length facts, selected canonical actual receipt and versioned full-CKey/structural attestation. Existing root/install membership roles remain immutable. The association explains why those actual bytes satisfy that build's metadata requirement; a source alias is not independently a retention root.

Persist the canonical reservation before acquisition. Record the verifier-issued prospective SHA/length/scope before CAS promotion; complete canonical publication separately from each surviving consumer's association and pin transaction. Preserve original source evidence on coalescing and restart. A crash after promotion and before the ready transaction must recover that exact representation rather than allowing a second encoding.

Migration must examine existing retained root/install references and their source proofs, preserve every compatibility pin and existing membership, and reconcile canonical slots before new alternate acquisition. Multiple legacy representations for one verified CKey are an explicit conflict requiring canonical choice; they are not permission to delete bytes or rewrite historical evidence. Existing referenced representations remain readable under their original proofs. Block further duplicate acquisition until reconciliation is complete. Safe reclamation is a separate GC work item.

No game bytes enter SQLite. Unknown verification/size facts remain unknown. Accounting must distinguish historical conflicting representations from newly selected canonical ownership and count physical SHA once. It must not claim reclaimed disk space merely because a canonical choice was recorded.

## Small implementation steps

1. **Inventory and guard:** add bounded shared canonical reconciliation for existing root/install references, preserve legacy conflicts, and reject alternate acquisition before bytes arrive. Exercise metadata-to-selected-file contention as well as metadata-to-metadata contention.
2. **Representation-aware contracts:** separate new-build declarations from actual retained representation in install/root plans and inspections. Define the narrow full-CKey attestation and exact configuration-to-content association. Keep original source identities and proof capabilities unchanged.
3. **Strong reuse and attachment:** locally replay A, verify new build CKey/length expectations and requested grammar, and attach the association/pin for each surviving consumer. Reuse the same CAS object across builds and capability receipts.
4. **Migration/recovery and usability:** prove legacy preservation, prospective-SHA recovery and honest zero-transfer reuse/conflict disclosure before enabling the behavior in the desktop. Deletion remains out of scope.

## Acceptance

Use synthetic content encoded into two deliberately different valid BLTE representations A/B. Tests must establish:

- Two exact builds declaring the same decoded CKey retain one canonical encoded representation; the second build's declared B and actual A remain distinguishable.
- Complete A CKey verification authorizes strong metadata reuse; weaker encoded-only receipts cannot authorize readable root/install results.
- Equal CKey with conflicting declared decoded length, wrong configuration proof, unreviewed namespace, corrupt/missing physical bytes or unsupported grammar never becomes a successful reuse.
- Same-EKey consumers share; competing EKeys cannot both publish. Cancellation and late source completion do not attach cancelled consumer memberships or release an uncertain reservation.
- Restart after reservation, prospective proof, CAS promotion and catalog completion retains exclusivity and original actual source observations. Offline reuse performs no HTTP.
- Metadata and selected-file operations contend on the same reviewed content slot. Added proof capability reuses physical bytes rather than creating a second encoded copy.
- Legacy duplicate representations remain visible and pinned; no automatic deletion or false reclaimed-byte claim occurs. Identical physical SHA aliases always count once.

These checks are separate from full-build archive, general cache eviction and decoded-table sharing acceptance. No live data is needed for the deterministic suite.

## Actual publisher ownership follow-up (#84)

The approved [publisher ownership and startup-fencing contract](PUBLISHER-OWNERSHIP.md) distinguishes durable reservation intent from an actual worker permit. SQL, selected-flight and runtime integration are active at the checkpoint above, with actual runnable ownership and explicit startup fencing; strong A-versus-B reuse and repair remain separate pending work. The separate same-representation repair restriction under #83 remains unchanged.

## Approved first guard architecture (#80)

The shared authority remains the existing `canonical_content_slots` header: namespace/CKey, actual EKey, encoded length, durable token and lifecycle state. Do not create a second independently authoritative metadata reservation table. Its bounded representation field gains one explicitly tagged metadata variant beside the existing encoded variant. Existing encoded receipt bytes and public selected-file APIs remain compatible; all producer transitions delegate to the same neutral reservation implementation in a transaction. Selected-file projection of a metadata-only receipt returns a typed unsupported-reuse outcome until representation-aware reuse exists. It never casts that receipt into `ContentRepresentation` or fabricates an encoded transport receipt.

A separate bounded legacy-conflict inventory is a blocking adjunct to that same authority, not a second lock or selected winner. Every lookup/reservation checks it transactionally. Historical header anchors/tokens remain unchanged, but a conflict returns an explicit conflict outcome rather than presenting an arbitrary anchor as canonical. No existing foreign-key parent table rebuild or opaque receipt rewrite is required by the first slice.

Concrete pure values are deliberately limited:

- A neutral `ContentClaim` contains the existing reviewed `ContentSlotKey`, actual EKey, encoded length, token, lifecycle state and optional typed proof. Process owner/session bookkeeping stays in infrastructure.
- A sealed two-variant proof contains either the existing encoded `ContentRepresentation` or a metadata proof. There is no extensible generic proof graph.
- A metadata proof preserves its original source alias/evidence, actual EKey, declared CKey and optional independently declared decoded length. It carries optional `DecodedContentAttestation(contentKey, actualDecodedBytes, verifierRevision)` and optional `MetadataStructureAttestation(ROOT|INSTALL, parserRevision)`.
- Historical source tuples populate declarations only. Their existence does not newly attest current physical presence, decoded validity or grammar. A new EOF verifier records decoded attestation before promotion; root structural attestation is added only after its actual bounded second parser pass. The proof may grow monotonically for the same immutable object and source facts.

A prospective metadata receipt is ledger evidence, not permission to publish a complete source alias or readable membership. Required protocol and structural capabilities must be satisfied before the corresponding final readiness/association transaction. Do not infer those capabilities from a verifier string in historical source metadata. Unknown length and absent attestation remain explicit, never zero or a generic ready flag.

The first domain TDD slice covers private-state invariants rather than acquisition: exact key/EKey/length/object agreement; historical versus decoded versus structural capability; optional facts and unsigned/checkable lengths; same-object monotonic attestation upgrade; unsupported proof projection; immutable source evidence; and no accidental promotion of declarations into verified capability. Storage tests then cover strict tagged-codec compatibility, same-header preservation, root/install and selected-file mutual exclusion, legacy conflict blocking, original pins and every prospective-SHA recovery boundary.

Implementation partitions are domain values, a narrow application canonical-content port, shared SQLite/codec delegation, existing install/root application policy, verifier publication observers, and composition injection. These are concrete responsibilities, not new frameworks. The initial runtime guard is enabled only through the integrated producer graph at the checkpoint above; future producers must participate before activation. A partially wired metadata-only guard cannot uphold cross-workflow exclusion. Representation-aware reuse remains a later increment of #80; the first safe guard can explicitly refuse alternate encoding B.
