# Proposed complete encoded-file retention

**Status: production workflow proposed; pure stream verifier implemented in #62.** This decision refines selected-file acquisition under #16. It does not establish decoded files, readable records, item support or new decryption capabilities.

A selected file may be retained as a complete, integrity-verified encoded BLTE object even when its contents cannot be decoded. Existing requirements to verify decoded CKey before publishing decoded-ready data remain in force. Encoded retention is a separate capability with a separate proof scope and membership role.

## Verification boundary

The [pinned TACT.Net writer](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/BlockTable/BlockTableStreamWriter.cs) establishes two EKey scopes:

- **Zero-header BLTE:** EKey is MD5 of the complete encoded object, including its initial BLTE framing. Every byte through exact EOF must be consumed before verification succeeds.
- **Supported chunk-table BLTE:** EKey authenticates the complete header containing chunk lengths and hashes. Every encoded chunk, including its mode byte, must match its declared MD5. Checked length totals, exact object length and EOF are required; a verified header or selected chunk alone is insufficient.

Compute local physical SHA-256 independently. That digest identifies stored bytes; it does not replace protocol verification. Reject unsupported container framing, malformed lengths, truncation, extra bytes, failed checksums and exceeded limits before publication.

Encryption (`E`), frame (`F`) and unknown chunk modes may remain opaque in this encoded-only workflow: their bytes are checksummed without interpreting, decrypting or expanding them. No success claims about mode semantics, decoded size or decoded CKey follow. An unreadable encoded object can be retained offline while previews and record decoding remain unavailable. Unsupported outer encryption/framing cannot be relabeled as verified BLTE.

## Identity, receipt and storage

The encoded source identity binds game namespace, full EKey and versioned verification semantics, including byte scope and supported container variant. It does not include an assumed decoded CKey, locale, FileDataID or archive URL merely to distinguish build memberships. CKey/FileDataID/locale associations belong to independently verified root/EN selection evidence for the exact build. Conflicting size or association claims must remain visible and cannot silently change a source alias.

A completed receipt must identify:

- Exact encoded source identity, encoded length and physical SHA-256.
- Verifier revision, zero-header whole-object or chunk-table header-plus-all-chunks scope, and completion time.
- Actual official source location and transport evidence; for archived objects, authenticated mapping and child range, with no whole-archive checksum claim.
- Root/EN association evidence separately, including pinned build and requested/actual variant context.
- Decoded proof explicitly absent/unverified, plus independently known expectations and unavailable capabilities.

Use distinct typed states/roles, such as `ENCODED_INTEGRITY_VERIFIED` with `encoded-file`, versus `DECODED_CKEY_VERIFIED` with a decoded role. These names describe proposed contracts, not existing API guarantees. A generic `ready` label or caller-supplied string cannot convert partial bytes into verified content. Only the completing verifier may issue the receipt and authorize its source alias.

One physical CAS object owns the complete encoded bytes. Archive/cache/retention policies and multiple builds reference that object. Later decoded verification reads the same encoded object under a lease and adds a separate receipt; it must not copy the encoded bytes or overwrite the truthful earlier scope. Reuse any matching actual source alias rather than manufacturing a second identity from retrieval location. Derived decoded bytes, if materialized, are a distinct shared representation keyed by source and interpretation inputs.

Do not persist separate chunk copies alongside the completed object. Initial acquisition stages one sequential complete object and atomically publishes it only after verification. Partial diagnostic/header evidence does not create a complete-object alias or ready membership. Resume/chunk persistence would require separately designed deduplicated ownership, not hidden per-build copies.

## Proposed implementation sequence and APIs

Implement a **pure streaming encoded verifier first** in `protocol-tact`. Existing `BlteChunkPlanner` authenticates nonzero headers and selected-chunk recipes; its `AUTHENTICATED_CHUNK_ONLY` scope must stay unchanged. Existing `BlteDecoder` remains a bounded N/Z interpretation component. Neither should be silently widened to claim full encoded retention.

The new verifier should accept a bounded stream, pinned EKey, exact encoded length, explicit header/chunk/input limits and cancellation. It consumes the complete supported object and returns an immutable completion proof, or a typed failure. It performs no HTTP, CAS writes, membership updates or decoding. Reuse framing rules where sound without weakening selected-chunk proof boundaries. Stream chunk hashing rather than requiring entire-object or decoded buffers.

Then compose existing `VerifiedSourceAcquirer`, `ObjectStore.publishVerified`, source-alias and catalog ports:

1. **Plan selected file:** validate exact root variant and EN candidate; reuse retained aliases; resolve an official loose or archived locator within explicit metadata budgets. Return immutable selection/proof references, chosen route, exact encoded cost when known, additional physical storage, unresolved scope and plan generation.
2. **Acquire planned file:** bind execution to that plan, verify complete encoded bytes, publish CAS atomically, then attach the encoded-only receipt and exact-build membership. Recovery must not expose a ready reference to missing bytes.
3. **Inspect retained file:** verify physical storage, expose encoded and decoded scopes separately, and lease bytes before offline access. Corruption and absent storage are distinct from unsupported decoding.

These are proposed use-case boundaries, not a requirement for new generic frameworks. Receipt typing and source semantics must be agreed before production wiring.

## Resolution policy and known cost

Resolve exact build/locale/content variant → authenticated EN candidate → explicit official locator. Keep encoding alternatives and choice evidence. A bounded loose404 may permit an explicitly approved archive search; integrity failures, cancellation, size conflicts and budget exhaustion must not silently switch routes, locale, edition or candidate.

Archive lookup uses authenticated footer, TOC and selected index page. Advertised index-size tokens remain hints when they conflict with authenticated geometry. Cache reusable mapping facts only with their identity, source and proof scope; an old text URL/offset is insufficient. An archive-group hash does not promise a remote group index. HEAD length and HTTP receipts establish range geometry, not archive content integrity.

Disclose metadata-search byte/request/time budgets separately from payload cost. Stop when the plan is incomplete or exceeds approved limits. No repeated root acquisition is needed when valid retained root/EN proof is available. Text-only diagnostic experiments do not imply reusable payload storage.

## Acceptance before implementation completion

- Prove zero-header full-object EKey and chunk-table header/all-chunk verification, exact EOF, malformed/truncated/extra bytes, size/count overflow, cancellation and bounded memory with synthetic streams.
- Verify opaque E/F/unknown modes without invoking interpretation; decoded proof remains absent. Unknown container framing stays unsupported.
- Reject exact-build/variant/source substitutions, stale plans and conflicting size evidence. Keep partial root/EN search distinct from absence.
- Exercise loose success, approved404-to-archive resolution, mapping conflicts, ignored ranges, corrupt footer/TOC/page/chunk and metadata-budget exhaustion through controlled HTTP fixtures.
- Prove same-source concurrent acquisition and multiple memberships share one physical object; cancellation, leases, pins, atomic publication and failure recovery remain safe.
- Prove an added decoded receipt reuses the encoded object's SHA-256 without another encoded copy. Offline reopen verifies storage and preserves the encoded-only label without CDN calls.
- Independently review backend behavior and native UI cost/consent/progress/source-scope presentation before claiming the production workflow. Live checks remain explicit and separate from deterministic CI.

No actual item/table decoding, full archive verification or general format support is implied by this proposal.

## Pure verifier checkpoint (#62)

`EncodedBlteInputStream` now implements the single-pass encoded verification foundation. It replays an authenticated bounded header unchanged, hashes body chunks with bounded reads, and exposes an immutable completion proof only after actual EOF. Its proof distinguishes zero-header whole-object EKey from table-header plus all encoded chunk hashes, and includes independent physical SHA-256. It does not close the caller-owned source or interpret modes.

Eight tests and 12,000 independent generated-stream oracle cases passed review. The zero-header chunk-size bound was added with a failing regression before acceptance; oversized bodies are rejected after eight framing bytes. The production locator, receipt/alias policy, CAS composition, build roles, retention scope and UI remain unimplemented by this pure increment.
