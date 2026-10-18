# Selected encoded-file acquisition

**Status: approved design; production implementation is pending in #71–#73.** This specification covers one exact root variant and one complete encoded BLTE representation. It does not claim decoded CKey verification, readable rows, filenames, decryption or whole-archive integrity. [Encoded retention](ENCODED-RETENTION.md) defines the verification boundary; #16 remains the parent delivery issue.

## Explicit stages and consent

A dedicated application `SelectedFileService` owns policy. Existing install-list and root services retain their separate responsibilities. A narrow infrastructure gateway implements official-source lookup, binary protocols and streams; application DTOs contain plain identifiers, costs and scoped receipts, not HTTP or parser objects.

1. **Plan candidates.** Reopen the exact retained configuration and complete verified root. Replay the requested FileDataID and full selected variant tuple, including CKey, locale/content flags, raw flag components and optional name hash. Reject invented variants. Resolve that CKey through authenticated bounded EN index/page lookups and preserve every candidate EKey, known encoded size and unresolved ESpec reference. EN fragments remain session-only.
2. **Choose a representation.** Consult the canonical content slot described below before resolving a new payload route. Reuse its actual EKey only when the current authenticated candidate set supports it. A changed choice invalidates previous consent.
3. **Locate the selected candidate.** Perform a separately authorized metadata operation. Only an exact typed loose HTTP404 permits an explicitly approved archive search. Timeout,403,410,416, cancellation, integrity failure, conflicting size and budget exhaustion never silently switch routes or candidates. Archive lookup authenticates suffix footer, complete TOC and selected padded page; no guessed remote archive-group URL is used. HEAD size establishes range geometry, not archive integrity. Search request, wire, time and concurrency budgets remain independent of payload cost. Incomplete search is not absence.
4. **Approve the acquisition plan.** An immutable bounded session plan binds exact build/root/variant, CKey, actual selected EKey, verifier scope, locator, known encoded length, source associations, budgets and costs. An archive mapping length must agree with a known EN size. Unknown payload size cannot authorize acquisition. Additional physical storage is a bounded estimate, not a promise inferred from an unverified alias.
5. **Retain encoded bytes.** Replay the entire canonical plan before transfer or cached reuse. Pin the desired dependency scope, acquire the canonical content slot, verify the complete encoded stream in one publication pass, then atomically attach the durable association and exact-build membership. Same-source consumers share acquisition; cancelling one detaches it. Only the final consumer may stop shared work.
6. **Inspect retained bytes.** Reopen with zero HTTP, verify local physical SHA under a lease, and report complete encoded-integrity scope separately from decoded-unverified state. Missing bytes, corruption and unsupported interpretation remain distinct. A failed reopen clears any current verified-local claim.

No stage implicitly downloads the root again, acquires a different variant or expands a metadata budget. Bounded plans have exact tuple equality, explicit lifetime/count limits and no caller-provided proof boolean. UI numbers and provenance come from those plans rather than illustrative constants.

## One canonical encoded representation per declared content key

Physical SHA-256 deduplication always applies across builds, cache and retention memberships. In addition, the first production workflow permits only one retained encoded representation for each `(verified game namespace, declared CKey)` slot. The initial `wow` content namespace explicitly supports the verified raw product `wow` only. No `wow` prefix rule or guessed mapping includes `wowf`, Classic or other games. A future reviewed namespace mapping requires an explicit compatibility decision. The namespace is not automatically a separate build/locale, and unrelated games are never merged merely because identifiers happen to match.

A CKey here is an independently authenticated root/EN declaration. Encoded verification does **not** prove that decoding the bytes produces that CKey. The slot therefore records a declared association and its origins, not a decoded-content guarantee.

- An empty slot can reserve one selected EKey under an immutable canonical plan. Same-EKey requests share that reservation and its actual source receipt. A concurrent different-EKey request for the same CKey returns busy until the winner settles.
- After EKey A is retained, a request for EKey B must replan. It cannot silently retain B alongside A, replace A, delete A or pretend A was verified as B.
- Initial cross-build reuse requires the new build's independently authenticated EN candidate set to contain the retained **actual EKey A**. Its complete encoded proof and physical receipt supply the known actual length; compare the EN length when supplied. An unknown EN length stays unknown and does not contradict a separately verified actual length. Persist the original acquisition receipt and the new build's separate root/EN association evidence. Do not manufacture a new URL/capture or current EN membership.
- If the new build advertises only B, the workflow reports an existing canonical representation requiring a future association/replacement decision. It blocks duplicate B acquisition. CKey equality alone does not establish decoded equivalence, and no fallback is invented.
- A conclusively failed attempt with no completed publication may relinquish its pending reservation so an explicit new candidate can be planned. Historical intent definitions remain unchanged. Cancellation of a returned future alone does not establish worker retirement or absence of publication.
- An interrupted or uncertain publication remains a recovery state, not an empty slot. Replay/recover the reserved EKey and reconcile its receipt before allowing a different candidate. A crash between CAS promotion and catalog commit must not allow a second encoding to evade the slot policy as an untracked cache object.

Automatic replacement, deletion and alternate retained encodings are outside this slice. The same physical encoded object may later receive a separate decoded-CKey attestation without copying it or overwriting its earlier encoded-only proof.

For fresh acquisition, an unknown EN size may become a known payload cost through an explicit strict loose HEAD or authenticated archive mapping. This does not fill in the missing EN declaration. Acquisition still requires a concrete known-size plan, followed by complete encoded verification; retained reuse requires verified physical bytes. A known conflicting EN size is an integrity failure in either path.

## Identity, membership and durable association

The encoded source identity binds game/protocol namespace, full EKey and versioned complete-encoded verification semantics. It excludes CKey, FileDataID, locale, build and retrieval URL. Associations carry those contexts separately. Different source aliases that resolve to identical SHA-256 still reference one physical object.

A selected-file membership uses a versioned canonical descriptor rather than FileDataID alone. A role such as `EF1_` plus unpadded URL-safe Base64 of its full SHA-256 is 47 characters, below the 64-character contract limit. Canonical serialization uses explicit optional markers and length-framed fields. It includes exact root source/verifier context, unsigned FileDataID, CKey, complete raw variant tuple, chosen actual EKey and encoded verifier semantics. Snapshot identity remains the outer membership scope. Role uniqueness does **not** authorize multiple retained EKeys for one CKey; the content-slot policy is checked first.

The desired `ENCODED_FILE` retention scope explicitly requires configuration, root and the selected file membership. Known encoded lengths and unknown decoded lengths remain distinct. This closure supports association inspection, not a complete build. Desired intent is not an immutable content fact; actual attached memberships remain immutable.

A narrow durable selected-file association record is necessary. Hash-only roles cannot reconstruct FileDataID/provenance after restart. Persist:

- Exact snapshot and role, canonical descriptor, root/config source references and selected raw variant.
- Declared CKey, actual EKey, consistent expected lengths and versioned authenticated root/EN association attestation, including original proof identities.
- Canonical content-slot reference and the complete encoded source/object receipt.
- Actual source observations: official URL and request scope; for archives, authenticated mapping and child range. Preserve reuse origins rather than relabeling the new consumer's unfetched route.

Proposed additive catalog storage comprises canonical content slots/reservations and selected-file associations referencing existing source/object tables. Reservation changes, pin changes and final association/membership attachment have explicit transactions and immutable conflict checks. Physical immutable bytes publish before ready catalog references. Persist the pending reservation before acquiring bytes. In the CAS completion gate, durably record the verifier-issued prospective physical SHA, exact length, scope and actual source receipt before promotion. After an uncertain crash, recovery retains exclusivity and checks that exact SHA before replaying the same EKey. Canonical publication and source alias commit separately from each surviving consumer's association/membership/pin transaction; a cancelled originating consumer must not gain a completed association just because another consumer survives. An explicit desired pin created before transfer may remain as requested missing/partial intent after cancellation, matching the existing root convention; cancellation is not an implicit release. This is a concrete schema extension, not a generic proof graph. No game bytes enter SQLite.

Measurements count physical SHA once and identify shared membership. They must not treat aliases or historical unpinned intent as permanent retention roots. No deletion/GC is introduced by this contract.

## Single-pass bounded streaming

An infrastructure sequential range input stream exposes one pinned logical object through strict bounded buffers. For an archived child, checked arithmetic translates logical offsets into parent archive coordinates. Exact parent Content-Range total and response length are validated. Logical EOF is the child length; bytes outside that child are never read or represented as its proof. Every actual transport observation is retained within a bounded receipt or deterministic digest of bounded observations.

Compose the range stream into the pure `EncodedBlteInputStream`, then into `ObjectStore.publishVerified`. Its completion gate accepts only the verifier-issued completion proof for the expected EKey/length/scope. Compare physical SHA with the resulting stored object. Header replay feeds the same staging pass; no preverification followed by a second acquisition, persistent chunk copies, pipes or background output callbacks are required.

The pure verifier authenticates zero-header whole-object MD5 or nonzero header plus all encoded-chunk MD5s, exact encoded count and actual object EOF. E/F/unknown modes remain opaque. No decoded length or CKey claim follows. Source and lease ownership is explicit; workers stop before CAS closes. HTTP execution is independent of workers waiting for range results.

## Delivery contracts and verification gates

- **Transport prerequisites:** typed HTTP status (#66) and bounded sequential child-range input (#69). Neither creates source aliases or claims file availability.
- **Application policy (#71):** separate planning, candidate choice, locator authorization, acquisition and offline inspection. Fake-port tests cover forged/stale tuple rejection before cached reuse, typed404 policy, every EN candidate, explicit known cost, canonical-content conflicts, same-source sharing and cancellation after workers retire.
- **Source gateway (#73):** retained root replay, bounded authenticated EN and archive lookup, explicit source receipts and single-pass complete encoded verification. Controlled HTTP fixtures cover ignored ranges, short/trailing bytes, wrong totals, unknown modes, checksum failure, budget exhaustion and no remote group assumption.
- **Persistence and retention (#72):** canonical slot/reservation recovery plus typed association and dependency closure. Test same-CKey/different-EKey races, failed and uncertain publication, restart without HTTP, original receipt reuse, immutable role conflicts, multiple-build physical dedup and no duplicate representation.
- **Presentation:** explicit stage cost/consent, actual candidate and locator, encoded-only scope, inherited proof origin, incomplete search, blocked canonical conflicts, cancellation and honest offline corruption. Native keyboard/scale/screenshot checks follow backend acceptance. The [selected-file UI design](design/SELECTED-FILE-UI.md) defines the proposed screen states.

Optional live checks are separate from deterministic CI. No payload is committed. Existing successful diagnostics and numeric field primitives do not imply this production workflow is delivered.
