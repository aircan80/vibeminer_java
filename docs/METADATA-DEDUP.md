# Canonical root and install metadata

**Status: approved requirement; scoped implementation pending in [#80](https://github.com/aircan80/vibeminer/issues/80) under #17.** This contract extends the no-duplicate-content policy to retained root and install manifests. It complements [shared storage](STORAGE.md) and [selected-file acquisition](FILE-ACQUISITION.md). It introduces no deletion, automatic representation replacement or new game support.

## Observed implementation gap

Physical CAS deduplication is correct: identical SHA-256 bytes are stored once across build memberships and retention policies. Different valid BLTE encodings of the same decoded content can have different physical SHA values.

The current install source identity includes CKey, EKey, decoded length and encoded length. Its application service coalesces and reuses that complete tuple. Retained install inspection also requires the tuple and encoded length from the selected build plan. The root source identity likewise contains CKey, EKey and both lengths; root acquisition shares the full tuple. Immutable per-build roles prevent changing an attached manifest but do not prevent another build retaining an alternate encoding of the same CKey.

Consequently, equal decoded content with different encoding tuples can produce two retained physical objects. This is a control-flow and identity gap, not a claim that an actual duplicate has been measured. A source alias or matching filename is not sufficient evidence of decoded equality.

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
