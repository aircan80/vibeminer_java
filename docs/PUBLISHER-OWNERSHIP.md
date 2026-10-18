# Actual publisher ownership and startup fencing

**Status: publisher fencing and explicit local retained-metadata reuse are published in verified [alpha.5](releases/v0.1.0-alpha.5.md), exact `f3c4d46`.** See [central validation](PLAN.md#delivered-baseline-and-current-work) for 1,113 Java cases, four native targets, eight Linux UI workflows and final-asset proof. Explicit local reuse preserves declared B separately from actual A; ordinary B acquisition still refuses. No source fallback, repair execution, replacement or GC is enabled. Exact-descriptor legacy reads preserve original proofs despite conflicts. Later read-only and repair-integration source is outside this release.


## One durable claim is not one active worker

A canonical reservation UUID represents durable intent for one content slot/representation. Looking it up, reserving it or joining as a passive consumer does not establish ownership of the actual runnable that can publish bytes. Two independent helpers may share that UUID while only one should publish. Failure or cancellation of the other must not release the active publisher's exclusion.

The same canonical header carries an actual worker nonce. `claimWorker` atomically acquires a fresh nonce only when no worker owns that reservation and returns a `ContentWorkerPermit`. All publication, prospective-proof, capability-upgrade and cleanup mutations validate the exact reservation token, worker nonce and store session in SQLite. No service-local map is the cross-producer authority. Bare UUID APIs cannot mutate or clean up an actively owned claim.

A second independent actual publisher receives BUSY even for the same EKey; ordinary subscribers of an already owned shared flight do not become independent publishers. Another adapter's random UUID/session is never proof that the original worker has died and must not steal, replace or reassign its ownership.

## Retirement follows actual runnable termination

Only the owning actual source runnable retires its permit, in `finally`, after transport, streams and read leases have closed and later publication is impossible. Cancelling a returned future or detaching its originating subscriber is not worker retirement. A passive consumer cannot retire a publisher.

`finishWorker` atomically checks the permit, retires the nonce and preserves the correct outcome. A conclusively unpublished, unproven failed attempt may release its claim. A prospective proof, uncertain publication or retained object preserves RECOVERY/RETAINED and original representation exclusivity. Retained state never downgrades because of late cancellation. There is no release-before-retirement window.

A stale nonce cannot mutate or finish a later worker's claim. Queue rejection likewise grants no cleanup authority: either conservatively leave intent pending, or acquire a dedicated actual permit before conclusive cleanup. If another worker owns the claim, BUSY means no mutation. A token alone is insufficient.

## Bounded selected-flight fanout

One actual selected-file flight owns at most **32** admitted slot permits. Admission selects an accepting flight and atomically enrolls its slot authority relative to the flight's freeze. A joining thread may perform that short admission on behalf of the flight; the flight registry/runnable alone owns retirement.

If a different actual producer already owns a joining slot, that consumer gets BUSY without enrollment, proof mutation or release. The primary flight and already admitted consumers continue. Subscriber cancellation never retires any actual-flight permit.

Before CAS promotion, the completion gate freezes admission and records the actual prospective SHA/length/source receipt for **every** held slot. Failure to persist any required proof aborts promotion; uncertain states remain exclusive recovery rather than becoming empty slots. Do not record fabricated receipts or release unowned slots.

A post-gate late join waits for retirement and adopts the durable actual-EKey receipt locally, without another GET. It cannot retroactively claim prepromotion enrollment. Actual source `finally` retires all held permits, including when the original requester cancelled; cancelled consumers receive no completed build association.

## Exclusive-store startup authority

`FileObjectStoreSession` is opaque authority minted only after successful exclusive object-store opening. An arbitrary UUID or independently constructed catalog adapter is not equivalent authority. Every participating canonical/selected adapter uses the same exclusive-store lifetime session.

Startup reconciliation is an explicit **one-shot SQLite-only** operation under that session, before enabling any publisher or using CAS. It fences stale previous-session nonces into recovery while preserving retained objects, prospective receipts, pins, memberships and representation exclusivity. Old permits cannot mutate after fencing. Recovery never silently chooses an alternate EKey or deletes uncertain bytes.

Late, repeated, failed or closed-session startup attempts deny further activation. Same-runtime construction must not sweep live workers. A legacy-producer marker is recorded **before any HTTP or SQL producer work** so a later startup attempt cannot masquerade as a quiescent boundary. Random foreign-session adapters cannot steal live ownership; absent valid startup authority, return recovery-required/BUSY without changing the original worker's owner session or nonce.

Runtime composition must complete reconciliation successfully before exposing content-producing services. Failed initialization cannot leave a partly enabled producer graph. Metadata-only application startup can remain lazy; laziness does not authorize publishers to precede the explicit content-store startup boundary.

## Hard TDD and acceptance matrix

Use real temporary SQLite/CAS, controlled latches and independent coalescers where the ownership boundary requires them. No live CDN or timing sleeps are needed.

| Scenario | Required result |
| --- | --- |
| Helper A blocked in transfer; independent B uses the same slot/EKey/token | B is BUSY; B failure/cancellation cannot release A; alternate C stays blocked until A's actual `finally` |
| Metadata and selected-file producers contend, in both orders | One shared authority, no per-service ownership bypass, original source receipt preserved |
| A selected flight tries to admit a slot owned by another metadata worker | Only that joining consumer fails BUSY; no mutation of the foreign slot; primary flight completes |
| Source freezes after admission, before promotion | All at-most-32 held permits receive prospective proof before CAS; one failed proof aborts promotion |
| Late subscriber after the gate | Wait for retirement, reuse actual durable receipt locally, zero extra GET and no invented prepromotion proof |
| Originating requester cancels while another survives | One source request; no premature claim release; survivor completes; cancelled build gets no completed association |
| Cancellation future completes before source cleanup | Alternate publisher cannot enter until actual runnable termination |
| Queue rejection | No bare-token cleanup; any conclusive retirement owns its own nonce and has no source/prospective publication |
| Nonce 1 retires, nonce 2 starts, stale callback/finish arrives | Reject stale mutation without changing nonce 2, including prospective and retained states |
| New adapter/random session appears while a worker is live | No stealing, sweeping or owner-session reassignment; original permit remains valid |
| Exclusive store opens, one-shot startup fences prior-session worker | Old permit rejected; same representation/prospective evidence remains recoverable; no alternate acquisition |
| Startup after producer marker, CAS use, prior attempt or session close | Deny late/repeated/failed/closed activation; leave no partially enabled producer graph |
| Crash after prospective proof or CAS promotion | Preserve exclusive same-EKey recovery and actual receipts; never downgrade retained or uncertain publication to empty intent |

Tests must cover completed source, conclusive failure, corruption, prospective proof, retained proof, independent metadata/selected contenders and retirement of all fanout permits. Integration and runtime acceptance are required before enabling the guard; passing only core-value or helper tests is insufficient.

## Remaining repair and product boundaries

The separate [#83](https://github.com/aircan80/vibeminer/issues/83) follow-up governs explicitly consented, known-cost repair of missing/corrupt canonical representation A. Neither an integrity failure nor this ownership contract permits alternate B acquisition, automatic replacement or deletion. Strong decoded/structural metadata proof, encoded-only proof and declared associations remain separate capabilities. Full archive retention, GC and general duplicate-reclamation remain outside this contract.
