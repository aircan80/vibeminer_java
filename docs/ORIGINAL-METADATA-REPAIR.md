# Explicit repair of original canonical metadata

**Status: repair backend/runtime/controls have accepted local qualification at `c17ec25`; newer four-platform qualification remains pending.** The clean full source check passed 1,333 Java cases and verified Linux development package 24. Final exact-source DejaVu repair acceptance passed 71 assertions across two JVMs with zero HTTP. Earlier focused font correction/overlay evidence remains distinct; [visual references](design/metadata-repair/README.md) record layouts.

[CI 39125032855](https://github.com/aircan80/vibeminer/actions/runs/39125032855), attempt 2 at `2be2962`, passed all four targets and ten Linux workflows after Intel's transient Maven DNS recovery. Newer `62513a2` eleven-workflow qualification is pending; no alpha.6 tag/publication is claimed. Portable three-real-adapter regressions `619a9b4` are independently accepted and included in the clean 1,333-case source check. Same-issued-plan sharing remains bounded to 32 lifetime consumers/four actual jobs, with distinct issuance/cross-build BUSY; ROOT actual-halt recovery and native post-STRUCTURE UNCERTAIN/local-verification evidence do not turn symmetric INSTALL uncertainty into native coverage (it remains unit-only). Published alpha.5 is unchanged.


ROOT crash acceptance covers the actual halt-after-STRUCTURE/CAS boundary, restart RECOVERY and ordinary metadata-acquisition local-proof replay to RETAINED with zero HTTP; it is not INSTALL process-crash coverage, every crash cut or automatic startup finalization. Read-only local verification/Atlas reopen can prove present readability without finalizing the canonical state or rewriting historical UNCERTAIN audit. The scripted native fixture restores INSTALL bytes after cancellation only to support restart, not as evidence that a cancelled repair succeeded. Portable sharing tests preserve other-build references, pins and requirements through one/all-consumer cancellation, with one actual audit/source/physical object.

This contract extends the [canonical metadata guard](METADATA-DEDUP.md), [actual publisher ownership](PUBLISHER-OWNERSHIP.md) and [shared storage](STORAGE.md). It covers app-owned install and root metadata only. VibeMiner does not access a game installation. It introduces no deletion, GC, encoding replacement, automatic retry or additional product namespace binding.

## Initial scope: repair exactly A

A is the original canonical encoded representation for the reviewed namespace and declared CKey. Repair restores its exact physical SHA-256 and encoded length. The original actual EKey, decoded CKey/length, supported source semantics, original source evidence, canonical reservation identity, aliases, memberships and pins remain intact.

The first repair workflow requires a currently verified exact-build plan requesting that same A:

- The build's verified configuration, metadata kind, namespace/CKey, requested EKey and lengths agree with the canonical original proof.
- Root selection contains A as an actual known-size candidate. An unknown EN size stays unknown; HEAD cannot manufacture a known EN candidate size or bypass the existing selection contract.
- The new HEAD observation reports that same resource key and exact original encoded length. A different advertised encoding B is not a repair target.
- Root/install protocol and grammar support and the existing operation budgets still apply.

If the exact source is no longer available, the retained scope remains missing or partial. No latest-build substitution, alternate EKey, archive search or mirror fallback is implied. Representation-aware declared-B/actual-A reuse is a separate [#85](https://github.com/aircan80/vibeminer/issues/85) contract; its zero-payload reuse consent never authorizes an A repair.

## Fresh planning and separate positive-cost consent

Current missing/corrupt outcomes remain explicit and perform no automatic repair. Offline reopening, lookup, an error callback, an ordinary old plan and a zero-cost reuse plan cannot authorize traffic.

The intended UI can retain its existing explicit Check → review cost → Download sequence. A fresh repair-bearing plan must exist before the separate download action is treated as repair consent. A small state/message distinction must explain that the action repairs original retained metadata. No new navigation or asynchronous framework is required by this contract, and no such presentation behavior is currently delivered.

The application and planning issuer must divide responsibilities as follows:

1. Replay the exact build configuration and current supported root/install selection. Read canonical history without creating a publisher or changing existing references.
2. The application establishes local missing/corrupt or uncertain status without discarding the original proof before calling the issuer. The issuer has no object-store dependency and does not detect readiness. Planning reads are bounded and confined to app-owned storage. Before execution, the actual worker must reread authoritative history and check local readiness under its permit; healthy A requires local replay rather than a repair GET.
3. Make a bounded, explicit metadata HEAD observation for exact A through its supported official source context. Authenticate the actual route, status, complete identity-encoded length and unambiguous response framing. No redirect or arbitrary URL supplied by a DTO is trusted.
4. Issue a fresh opaque issuance UUID and retain a bounded canonical session containing the complete plan tuple, original reservation/proof, source observation, costs and expiry.
5. Before acquisition, replay that issued session and current canonical authority. Unknown, altered, expired, differently scoped or replanned identity/cost invalidates consent before payload transfer.

The implemented issuer borrows the same root session authority and replays the retained raw install configuration. It permits one HEAD with zero observed body bytes and a fifteen-second deadline. It retains at most sixteen opaque five-minute sessions, checked against both wall and monotonic time. Its validation methods perform no HTTP. This planning-only slice creates no publisher permit, repair attempt, CAS publication or canonical mutation.

A successful HEAD is evidence of availability at one observed instant. It is neither payload integrity nor a promise that the later GET succeeds. HEAD requests have their own disclosed metadata bounds; they are not hidden payload transfers. A later 404 remains unavailable and does not trigger another source automatically.

`MetadataRepairPlan` preserves the original `MetadataContentProof`, the exact `MetadataContentRequest`, the HEAD `MetadataRepairSourceObservation`, positive `MetadataRepairCost`, issuance/reservation UUIDs and issue/expiry times. The current value contract bounds issuance lifetime and HEAD age at issuance to ten minutes. Start-time checks must also replay the issued session; `isUsableAt` alone authenticates nothing.

The known encoded transfer and maximum additional physical staging estimate each equal original A's encoded length. Request, duration and decoded-work ceilings remain explicit. The current contract provides for two complete decoded passes and prevents repair from widening existing root budgets. Old `InstallPlan` and `RootPlan` constructors produce empty repair consent. Repeated installation configuration documents may otherwise compare equal, so a fresh issuance identity is essential: a hidden map must never silently upgrade an old ordinary plan.

The DTO constructors check immutable shape and consistency only. In particular, install configuration URL/time equality does not authenticate its bytes, and a syntactically valid HTTPS resource value does not authenticate an official endpoint. Cryptographic configuration replay, supported source validation and exact issuer-session replay remain mandatory gateway responsibilities.

## Historical proof and new repair facts

Original acquisition evidence is historical. A repair must not restamp its URL/time, manufacture a B alias, or describe a new transfer as if it occurred during the original acquisition.

`MetadataRepairObservation` therefore preserves the original proof and separately records a new repair-attempt identity, actual verified transfer receipt, fresh decoded attestation, optional fresh structural attestation, bounded payload request/body counts and observation digest. Its physical object and source tuple must equal original A; its actual transfer URL/time are truthful new facts. The HEAD observation is separate from both receipts.

Historical optional capabilities remain optional. Their verifier strings or source declarations do not establish current decoded/grammar verification. Fresh capabilities may be recorded, and any later canonical capability addition must be monotonic for the same original object/source after actual full-CKey and supported-parser EOF verification. Existing original attestations cannot be replaced. An encoded-only selected-file receipt cannot be converted into a metadata proof by this repair contract.

A future durable repair audit must append bounded attempt facts beside the existing canonical authority. It must not create a second canonical owner, rewrite original source evidence or place game bytes in SQLite. Planning alone creates no new repair attempt or pin. Exact attempt transitions and persistence APIs require their own reviewed implementation partition.

## Exact-object physical restoration

The implemented primitive is:

```java
ObjectStore.repairVerified(
    InputStream source,
    StoredObject expectedOriginal,
    long maximumBytes,
    CompletionVerification completion,
    CancellationToken cancellation)
```

It stages the caller-owned input through real EOF, checks exact original length and full SHA-256, then invokes the completing verifier before force/promotion. Incorrect physical bytes cannot reach that verifier gate or replace the target. The metadata gateway must also complete the required encoded, full decoded-CKey/length and staged grammar checks; CAS does not interpret those protocols.

An absent target is restored at its original SHA path. An already-correct target is idempotent. A corrupt regular app-owned target can be atomically replaced only by the verified same original object. A nonregular target is refused. The current conservative implementation refuses repair promotion while **any** CAS read lease is active. A repair observer must close local read leases before promotion rather than holding one across the callback.

Callback failure, cancellation, invalid bytes or failed atomic replacement must preserve the old target and remove the temporary stage. Unsupported adapters fail closed and never fall back to ordinary publication. Ordinary `publish`/`publishVerified` continue rejecting existing corruption. Repair retains one expected-SHA object, not a second canonical representation or permanent corrupt copy. This explicit integrity restoration is not general eviction or GC.

The primitive's tests cover exact identity, missing/corrupt targets, valid-target idempotence, gate/cancellation failure, lease exclusion and atomic-move failure. Cross-platform metadata repair acceptance still requires the complete workflow and relevant native evidence; a standalone CAS test is insufficient.

## Worker-fenced completing verification and recovery

The same [exclusive-store session and worker permit](PUBLISHER-OWNERSHIP.md) govern repair. Passive reservation and user consent do not grant actual publisher ownership. The actual source runnable must acquire a permit, reread the canonical token/header/proof under that permit, and replay the exact repair consent before deciding that payload is still needed. If A became healthy, local replay can succeed without inventing a repair transfer observation.

Every repair-proof, capability, audit-completion and cleanup mutation must validate the exact reservation, worker nonce and store session. A second independent producer remains BUSY even for the same A; it cannot release the first producer. Same-A consumers may share an already admitted approved repair flight where their scope/cost checks permit. There is no promise of a single GET across independent coordinators.

Before physical promotion, the completing verifier must durably record the repair attempt's prospective **original** SHA/length and actual source observations. Root's inline gate verifies the full decoded CKey/length; its fresh root structural fact is added only after the actual bounded second parser pass. Install can obtain its fresh grammar fact before promotion because it is already verified in the bounded prepublication pass. Neither gate may derive capability from a historical verifier string or HEAD response.

The proposed repair flow must preserve the original canonical alias while recording the new attempt separately. The existing ordinary metadata observer cannot be reused by silently substituting either a newly timestamped canonical alias or an old alias pretending to describe fresh HTTP. Final repair acknowledgement and any new consumer membership require the exact original physical object and fresh required protocol/grammar success.

A crash after prospective proof, physical promotion or grammar verification must leave original-A exclusivity and audit evidence recoverable. A pending repair row is not a readable membership. The canonical header's historical retained status does not bypass present physical/protocol verification. Startup must preserve original references and classify incomplete attempt facts conservatively. If original bytes are present and pass bounded local replay, finalization can require zero HTTP; if still missing/corrupt, another fresh repair consent is required.

Failure before promotion never releases an existing canonical A merely because the latest attempt was conclusively unpublished. After possible promotion, unknown failure remains uncertain. Actual source `finally`, after transport/streams/leases are closed, retires only its own permit. Cancelled futures do not retire active workers. A cancelled consumer receives no newly completed association through its cancelled operation; independently committed historical references remain intact.

## Next implementation and acceptance boundaries

Implementation proceeds in reviewed logical steps:

1. **Port and source planning (delivered):** bounded official HEAD issuance and exact canonical replay, without repair execution or an audit observer. Keep ordinary acquisition and local reuse behavior unchanged.
2. **Completing verifier:** reacquire only consented A through the exact-object primitive; emit truthful new attempt facts and verify full physical SHA, encoded integrity, decoded CKey/length and supported grammar. Reuse the shared bounded install verifier and existing root mechanics rather than duplicate parsers.
3. **Persistence and worker policy:** append repair-attempt facts under the existing authority, persist prospective evidence before promotion, finalize under a physical lease, and prove crash/stale-nonce/cancellation recovery without changing original aliases, pins or memberships.
4. **Application and presentation:** consume only newly issued positive-cost repair plans, preserve per-consumer cancellation and current plan validation, and wire the smallest explicit repair state/copy. Runtime enablement follows joint acceptance.

Use deterministic synthetic fixtures and real temporary CAS/SQLite for the ownership/storage boundary. Tests must prove old/zero-cost/forged/expired consent causes zero payload; known A is the only requested representation; unknown EN facts remain unknown; HEAD200 followed by GET404 has no fallback; wrong physical SHA/EKey/CKey/length/grammar cannot complete; original evidence remains exact while repair evidence is fresh; same-A sharing and held ignored-cancellation workers cannot admit or release B; stale permits cannot mutate replacements; every prospective/promotion/finalization crash point preserves exclusivity; cancelled consumers do not attach; and fresh instances reopen successfully with zero HTTP after completed repair.

Native tests must cover atomic replacement and reader behavior on the supported platforms. Substantive UI changes require matching Atlas screenshot review under the [quality gates](TESTING.md). No live CDN execution is authorized by this document.

## Delivered audit-only persistence: schema11

The durable audit backend is implemented and independently reviewed. `MetadataRepairAuditRepository` provides `begin`, `recordProspective`, `recordStructure`, `complete`, `fail` and historical `find` operations. It is separate from repair execution, canonical publication, consumer attachment and runtime enablement.

The additive schema11 migration creates empty attempt and event tables. Each attempt preserves the issued consent and exact original proof; subsequent events append fresh verification facts without replacing earlier evidence. Admission is limited to 128 attempts per canonical reservation, with at most four events per attempt and 256 KiB per encoded record. There is no automatic audit pruning or migration-created verification capability. Versioned records use strict bounded decoding, checksums and indexed-fact comparisons.

Every audit write validates the existing canonical token, current store session and actual worker nonce. An attempt also remains bound to the worker that began it: a replacement worker cannot finalize its predecessor's attempt. Original header/proof and conflict checks remain mandatory. Consent expiry is rechecked and the admission timestamp sampled **inside the admitted SQLite write transaction**, so waiting for a writer cannot backdate an expired attempt. Application policy must still authenticate issuance and configuration, and recheck whether payload repair is needed; the repository does not authenticate a caller-created DTO or authorize HTTP.

`recordProspective` must commit before the CAS promotion gate returns. Its receipt identifies the exact issued resource URL and original physical SHA/length, alongside the fresh full decoded-CKey attestation. ROOT prospective evidence contains no grammar fact: only the actual second parser pass can append `STRUCTURE`. INSTALL prospective evidence already contains its grammar; its repeated completing callback validates the same facts without adding a ROOT-only event. Transfer identity, observations and decoded facts cannot change between stages.

`complete` verifies the original physical object under a read lease held through the audit commit and requires the prior complete verification sequence. It appends audit completion only. It does **not** change canonical status or capabilities, create or restamp an alias, attach a build membership, pin content, or retire a worker. Historical `find` likewise reports evidence rather than current readability. A failure after durable prospective evidence remains uncertain, even if physical promotion was conclusively refused; completed audit success cannot be downgraded by later cleanup, and cleanup still requires the owning permit.

Canonical capability addition or publication remains a separate, explicitly permitted step. Any later addition may fill missing capabilities only after fresh full-CKey and grammar verification; existing capabilities and original acquisition evidence remain unchanged. A crash between audit completion and canonical finalization therefore leaves conservative canonical state rather than invented readiness. Consumer association follows its own transaction after source-worker retirement and does not reuse that worker's permit.

Synthetic real-CAS/SQLite tests cover gate durability before promotion, ROOT/INSTALL ordering, exact original versus fresh receipts, missing/corrupt physical objects, expiry while waiting for SQLite, cancellation at final commit, stale/session fencing, blocked-worker cancellation, bounded admission and migration preservation. This accepted backend slice does not deliver the executing repair workflow, automatic recovery, runtime wiring or repair UI.

## Historical presentation-model checkpoint and preserved UI contract

The repair branch belongs in the existing Root or Install missing/corrupt context, as one compact
**Repair original retained metadata** card. Keep it separate from #85's explicitly zero-payload
local-reuse branch. The original source selection, exact snapshot and workspace locale remain
visible. Selecting a build, changing locale or offering a different original source clears pending
consent and suppresses older asynchronous results. No context change initiates a check, download,
retry, metadata query or offline opening.

The state sequence is explicit:

1. **Check required:** explain that **Check original source availability** permits one HEAD request,
   zero response-body bytes and up to fifteen seconds. It does not authorize payload transfer.
2. **Checking:** expose cancellation of this request. Availability is not integrity, current
   readability or a guarantee that a later GET succeeds.
3. **Review fresh plan:** show original A and its exact build/locale beside positive maximum
   download, temporary physical staging and local decoded-verification work. Root verification's
   two passes remain included in the issued local-work ceiling. Full original SHA, original
   receipt, actual newly pinned resource URL, HEAD observation time and expiry are copyable in
   evidence details. Only **Download original and verify** grants the separate issued consent.
4. **Repairing:** show actual stage/progress only when the service supplies it. Cancellation does
   not imply retirement of a shared publisher, absence of publication or cleared ownership.
5. **Completed:** distinguish a fresh completed repair audit from an execution that found A
   healthy and replayed it locally with zero payload. Offer a later explicit offline-open action;
   completion alone does not run a root lookup, install query or Item preparation.

At 100% and 200%, context, cost and the consent action stay adjacent and wrap vertically. Detailed
identifiers expand below them rather than pushing consent into another panel. Keyboard focus
progresses from explicit Check to plan review, explicit Download and result/offline opening.
Enter elsewhere must not authorize either network operation. At the original isolated-model checkpoint, native controls and runtime wiring were separate gates. The accepted later local controls and current pending cross-platform status are recorded above; model tests alone never establish shipped UI.

`MetadataRepairPresentationModel` borrows `MetadataRepairService` and accepts an explicit ordinary
`RootSelectionPlan`, or an ordinary `InstallPlan` plus its local budgets. It never closes the shared
service. It preserves typed canonical and repair outcomes, clears invalid consent/results and
provides a snapshot/kind/locale-scoped readiness-revocation callback for explicit local
`ContentClaimException.MISSING_LOCAL` or `INTEGRITY_FAILURE` outcomes. A HEAD/transport
`MetadataRepairException.INTEGRITY` does not identify a corrupt local component; unknown
exceptions receive a generic storage failure without parsing their text or inventing a missing
source. Malformed returned plan/result facts likewise invalidate repair consent without diagnosing
local physical corruption. After an observer or readiness callback, the model rechecks context
before starting service work or publishing another state; a future returned after cancellation or
closure is immediately detached. Root and Install readiness are separate, and neither callback authorizes automatic Item
preparation or repair of another component.

An executing `MetadataAcquisitionFailure` keeps its outer publication certainty in presentation
state. `CONCLUSIVELY_UNPUBLISHED` means this attempt promoted no replacement, not that no GET or
payload transfer occurred. `UNCERTAIN` means bytes may be present or restored while current
readiness remains unknown; the next supported action is explicit local verification before a fresh
repair plan. Both outcomes clear consent and successful-result claims. Nested transport,
verification or cancellation causes do not become a confirmed local-component failure, a
zero-payload success, a rollback claim, or automatic retry/opening.

Execution consumes `MetadataRepairResult(association, completedAudit)`. A present audit must be
completed and belong to the exact issued plan; the UI keeps its fresh transfer URL/time/counts
separate from unchanged original acquisition history and the earlier HEAD observation. An empty
audit means the executing service actually performed the verified healthy-race local replay;
only that explicit success can say **No payload download was needed**. A planning
`REPLAN_REQUIRED` outcome cannot establish health: it also covers an ordinary B plan that cannot
repair actual A. Such an association remains explicitly unsupported until an exact supported
original-A context is available. It may expose its original receipt and the separate local-reuse
branch, but never download preferred B as a repair fallback.

Expired, altered, unavailable, cancelled, over-budget and unsupported plans require another
explicit supported action. HEAD200 followed by GET404 leaves the repair unavailable, with no
automatic retry, mirror, archive search or encoding substitution. At the historical phase-one checkpoint only the pure presentation model and synthetic fixtures existed. Later service/runtime/native controls have the scoped local acceptance recorded above; the newer all-platform release gate remains separate.
