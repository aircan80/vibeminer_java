# Canonical root and install metadata

**Status: publisher fencing and explicit local retained-metadata reuse are published in verified [alpha.5](releases/v0.1.0-alpha.5.md), exact `f3c4d46`.** See [central validation](PLAN.md#delivered-baseline-and-current-work) for 1,113 Java cases, four native targets, eight Linux UI workflows and final-asset proof. Explicit local reuse preserves declared B separately from actual A; ordinary B acquisition still refuses. No source fallback, repair execution, replacement or GC is enabled. Exact-descriptor legacy reads preserve original proofs despite conflicts. Later read-only and repair-integration source is outside this release.


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

The approved [publisher ownership and startup-fencing contract](PUBLISHER-OWNERSHIP.md) distinguishes durable reservation intent from an actual worker permit. SQL, selected-flight and runtime integration are active at the checkpoint above, with actual runnable ownership and explicit startup fencing; explicit local A-versus-B reuse now has accepted unreleased runtime/UI wiring, while final exact-revision validation and repair remain separate pending work. The separate same-representation repair restriction under #83 remains unchanged.

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

## Declaration versus actual representation contracts (#85)

The accepted source checkpoint `5f7e470` adds three pure application values: `MetadataDeclaration`, `MetadataReusePlan` and `MetadataBuildAssociation`. This is a **DTO-only checkpoint**. It enables no reuse, attachment, repair, network or runtime behavior. The integrated guard continues to refuse a new declaration B when retained canonical A has a different encoding until the remaining #85 policy, gateway, transactional-association and integration work is accepted.

`MetadataDeclaration` preserves one exact build's verified-configuration alias identity, requested ROOT/INSTALL interpretation, reviewed `wow` content slot and optional preferred encoding key/encoded length/decoded length. Construction checks identity and shape; executing code must still authenticate the configuration and its declarations. Unknown root encoding/length facts stay absent, rather than becoming a fabricated EN candidate or zero length. A declared encoded length is meaningful only with its declared encoding key.

`MetadataReusePlan` keeps those new-build declarations separate from the original `MetadataContentProof` for actual A, including A's physical SHA/length, EKey and unchanged original alias/source evidence. Its only state is `LOCAL_REPLAY_REQUIRED`. Zero additional payload-transfer bytes is a cost proposal, not current physical availability or verified readability; exact configuration, whole local physical SHA, complete decoded CKey/length and supported grammar still require bounded replay at execution. Previous explicit full decoded and matching structural attestations are required to construct the proposal. Historical declarations, encoded-only receipts and decoded-only proofs cannot satisfy this contract.

Independently declared decoded length must agree with A's full decoded attestation. B's advertised encoded size may differ from A's actual size and remains visible separately. If the declared encoding key equals A's key, its declared encoded length must agree with A too. No value manufactures a B source alias, B transfer observation or current EN membership for A. The existing strict `MetadataContentRequest` and original proof contracts remain unchanged; the separate same-A repair contracts in #83 do not reinterpret B as A.

`MetadataBuildAssociation` preserves the exact declaration/configuration origin, canonical reservation token, original actual A proof and separate association timestamp. The constructor authenticates no replay and commits no membership or pin. Future execution must revalidate the canonical authority and configuration, hold the required physical verification lease, and attach the immutable association plus dependencies/pins atomically for each surviving consumer. Original source evidence is never refreshed merely because another build reuses it.

Ten deterministic contract cases and independent review passed alongside sixteen existing metadata-acquisition cases. These checks cover declaration/actual separation, CKey-only root declarations, unknown optional facts, capability/kind/content/size/configuration/namespace rejection, unchanged original receipt and explicit replay-required cost state. They establish the value invariants only. Real two-build A/B gateway replay, zero-B-transfer physical-object counts, atomic association persistence, cancellation/worker fencing, fresh-instance offline use and applicable runtime/UI acceptance remain outstanding under #85.

## Local verifier checkpoint (#85)

Source checkpoint `00ce85b` adds the local-only `MetadataReuseGateway` / `OfficialMetadataReuseGateway` verifier and extracts the existing bounded install verifier without changing original acquisition behavior. Exact configuration bytes are verified against their physical SHA and raw configuration MD5, captured build/source identity and every known declaration. Actual A is replayed under read leases using its own encoding tuple and complete decoded-CKey/length and supported ROOT/INSTALL grammar checks. Both the exact configuration and actual representation remain protected through replay. Missing/corrupt bytes fail without acquisition; unsupported historical verification revisions are not silently upgraded. The returned actual proof preserves the original alias, URL, object and verification timestamp.

The local declaration reader derives `MetadataDeclaration` directly from retained configuration bytes under the same checks. Install's declared preferred EKey B and both lengths remain explicit. A supported root configuration declaring only CKey produces absent EKey/size facts; no EN lookup, current encoding-membership assertion, source-alias registration or payload read is needed to derive it. This separates deriving authenticated declarations from validating a caller-supplied value.

The adapter's root reader has a forbidden transport, and install replay has no transport dependency. Eleven initial local fixture cases and twenty-seven existing regressions passed at the verifier checkpoint; declaration-reader cases additionally cover complete install facts without A present, CKey-only root unknowns, foreign configuration identity/raw MD5, changed captured build and cancellation. These are local verifier checks, not delivered cross-build reuse. Application reuse planning/execution, durable association and pin transactions, service/runtime wiring and UI behavior remain separate acceptance steps. The integrated acquisition guard still refuses alternate B; no fallback, repair, claim mutation, new association or zero-transfer reuse has been enabled by this verifier slice.

## Associated-install reader checkpoint (#85)

Accepted source `ed6394b` adds a dedicated associated-install inspection/reopen path. It reads the persisted exact-build association and locally replays original canonical A using A’s actual receipt/encoding tuple, while preserving the new build’s declared B facts separately. It does not rewrite original source proofs or weaken the ordinary exact-tuple inspector. The accepted `MetadataReuseService` and real-adapter journeys in `74b574c`/`1885b89` remain separate backend foundations.

A clean `ed6394b` check independently passed 1,035 Java cases with zero failures/errors/skips and 24 Python checks; local Linux native validation passed with isolated development package 213 (53,116,421 bytes), bundled SQLite/CAS self-test and JavaFX smoke. No newer four-platform result is claimed. At this historical reader checkpoint runtime integration was not enabled. Later `7ffc822` wiring is described above; ordinary B acquisition still refuses and no metadata repair workflow is delivered. These later sources are not included in published alpha.4 (`891a9c4`); see [central status](PLAN.md#delivered-baseline-and-current-work).
