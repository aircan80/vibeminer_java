# Guided build discovery and C build overview — delivery stories

**Status: approved planning scope; implementation acceptance remains outstanding.** This breakdown implements only the latest [guided discovery](design/build-discovery/index.html?concept=guided) entrance and [C · Build overview](design/build-mining-home/index.html?concept=archive). [Architecture/delivery plan](BUILD-BROWSER-IMPLEMENTATION.md) defines technical contracts. GitHub Issues remains the work tracker; story labels below are document cross-references, not a separate backlog.

## Scope and product rules

The user journey is choose WoW product → automatically refresh publication metadata → select an exact grouped build → **Open Build** → C overview → use On demand or explicitly authorize Download all for the disclosed scope. Persist first-seen build identity and local content state. Returning to the same build reuses its state; do not ask for a second workspace name.

Implement no home A/B variants. Game Data, Files, Assets, Compare, findings/investigations and other research destinations remain clearly marked planned/disabled navigation; do not build those screens or wire synthetic production findings. Existing specialist features are regression obligations, not permission to expand this two-screen redesign. Future monitoring #95, icon selection #97, GC/deletion, broad comparison, export expansion and new games are excluded.

Download all is real production background retention, not a mocked queue/progress bar or a renamed install-manifest download. Initial honest bulk scope is **All declared manifest content**: complete declared ROOT/IN and applicable DL manifest dependencies/content closure for the admitted build grammar, without guessing locale/platform exclusion from opaque flags. A DL-only entry, unparsed required manifest or unresolved declaration cannot be silently dropped as unsupported to manufacture completion. Retain verified partial content while reporting the blocked closure. The UI preserves the requested research locale, but it is not an authenticated bulk-scope filter. A future narrower Windows/enUS scope requires reviewed versioned flag/tag semantics and explicit scope identity; no such filtering is assumed here. Full means complete verified encoded retention for that enumerated all-declared-variant scope, not a runnable installation, decoded/decrypted content or server data. All declared platform/locale variants are included in this initial closure; the mockup’s Windows/enUS fixture is illustrative, and a narrower production selector requires later validated scope semantics. Unsupported inventory/resolution must remain incomplete rather than reducing the denominator until success looks complete.

## Story matrix and acceptance

### BB1 — Remember successful observations and local first-seen state

As a researcher, I want discovered builds recorded locally, so I can return to exact observations and recognize newly seen identities.

Existing foundation #92/#18/#4. Implement before relying on persistent discovery badges.

- Successful discovery atomically stores exact event/snapshot/row source identity and verified document references; repeated bodies share bytes, repeated retrievals retain their own receipts. A→B→A creates three observations/two snapshot identities without rewriting first provenance.
- First-seen identity is written on discovery, not first Open Build. The first successful product visit establishes a persisted local baseline rather than marking every existing build New. Subsequently observed new exact identities are **New to VibeMiner** until acknowledged by Open Build; this never means newly released worldwide. Persist the baseline, firstSeen and acknowledgement across restart. Repeated refresh cannot renew firstSeen or re-add an acknowledged badge; New is independent of download mode/retention state.
- Failed/cancelled/partial capture cannot fabricate a complete publication event. Quota refusal reports observation not saved, preserves previous entries and does not evict evidence. Offline detail/selection performs zero HTTP.
- Tests: source/receipt dedup, row ambiguity, firstSeen/idempotent append, badge acknowledgement/restart, corruption/quota/concurrency and prior-workspace provenance preservation. No historical content availability claim.

### BB2 — Choose a WoW product and refresh automatically

As a researcher, I want an exact-ID/descriptive-name WoW picker at the top of discovery, so selecting a product directly reveals its build list.

Depends BB1 contracts; refines #93/#2/#3.

- Product control appears directly under the discovery question, with raw official code plus attributed descriptive alias. Reviewed Warcraft registry determines inclusion; unknown Warcraft labels use honest fallback, never guessed code/namespace rewriting.
- User product selection starts bounded asynchronous refresh automatically; secondary manual Refresh handles retry. One initial product selection may trigger the same disclosed flow; constructors and restored offline/history navigation do not secretly poll. Future scheduling #95 remains disabled.
- Product change/cancel/generation suppresses stale results without erasing retained local observations. Initial/loading/empty/offline and retry states are distinct. UI does not perform HTTP fan-out or parsing.
- Tests: one selection/one refresh, aliases never alter identity, unknown code fallback, no network on filtering/row selection, stale refresh and accessible first viewport.

### BB3 — Browse one list with preserved regional evidence

As a researcher, I want local observations and currently advertised builds together, so I can choose the exact source without separate Saved builds navigation.

Depends BB1/BB2; refines #93/#4.

- All/Local/Currently advertised filters use explicit facts: Local means the defined metadata/retained presence; advertisement is bound to successful checked scope/time. Neither implies current remote payload availability.
- Group regional rows only when complete identity-bearing references agree. Preserve raw product/channel/build/config/product-config facts and all region receipts/routes. Same version/different configurations stays separate. No forced region step for an equivalent group; a deterministic preferred source route is disclosed separately from build identity.
- Partial regional failures preserve prior evidence and unknown region state; absence from one refresh never means upstream unavailable. New badge uses BB1 local semantics.
- Tests: equal-config grouping, version collisions, regional route differences, local-only survival, partial failure/unknown publication and offline selection. No global history/availability inference.

### BB4 — Open one exact build through verified metadata admission

As a researcher, I want one Open Build action, so metadata prerequisites are handled without separate preparation and mining buttons.

Depends BB3 and agreed required-metadata closure; refines #93/#94/#16.

- Capture exact build/locale/scope/source-route and admission generation. Reuse verified ready metadata quickly; otherwise acquire only required metadata with actual stages/known bytes or indeterminate progress, then navigate automatically to the same C overview.
- Define required metadata explicitly, independently of game-content inventory/full retention; overview may open with full inventory still preparing and unknown totals; an empty install-list alone cannot masquerade as ready complete game catalog. Unsupported protocol/missing dependency fails admission with exact limitation.
- Failure preserves selection and retry; cancellation/context replacement never opens a different/stale build. Retry does not rewrite original first provenance or create another physical build archive.
- Offline ready path has zero HTTP; missing prerequisites explain connection needs. Tests cover cached reuse, corrupt/missing input, partial metadata, alternate route identity, cancellation and source ownership.

### BB5 — Show C overview, status and truthful local metrics

As a researcher, I want one exact-build overview, so I can understand readiness and storage without duplicate launchpads.

Depends BB4 and bounded storage projections; refines #94/#17.

- Header labels Game/edition, Product ID, Channel/region, Exact build, Locale, **Status** (plain text immediately after Locale). Raw source and full hashes remain inspectable/copyable. Logo/header have no breadcrumb. Back to builds restores filters/selection.
- Readiness is Not ready / Partial / Fully downloaded; activity Downloading/Paused/Needs attention is separate. Show a separate labelled Retained percentage (Unknown until denominator is proven). Mode/activity are independent: On demand / Download all control appears only in the Build readiness body, not the identity header or repeated elsewhere.
- One local overview distinguishes verified unique payload referenced by this build, shared subset with defined comparison set, missing known encoded bytes/unknown counts and separate metadata/derived overhead. Do not add overlapping per-build totals as physical store total or infer file counts from root variants.
- All metrics come from bounded current metadata/projections, with scope/time/unknown states; rendering does not trigger complete byte scans or network. Tests use a small shared-object graph with calculated totals and status transitions.

### BB6 — Establish a complete selected-scope content inventory

As a researcher choosing Download all, I want its real inventory and limits, so “all” has a verifiable meaning before work starts.

Depends BB4 metadata contracts; prerequisite for full-scope queue, not a cosmetic UI item. Refines #16/#17/#94.

- Enumerate the complete declared manifest closure for the admitted build grammar: ROOT memberships, IN entries and applicable DL dependencies/entries, including declarations not represented in another manifest, preserving variants and authoritative root/EN/config/index dependencies; resolve declared content to all relevant candidate encodings and exact locators under bounded budgets. Deduplicate logical identities separately from physical representation keys.
- ROOT/IN alone cannot establish full closure when applicable DL or another required declared manifest remains unparsed. Install manifests alone are not the game inventory. Archive container byte counts do not equal decoded content size. Unresolved variants/unsupported ESpec or codecs, missing keys, inaccessible indexes and unknown size remain explicit inventory/resolution states.
- Retain encrypted/opaque payloads under the exact complete-encoded verification rules where supported, without claiming decryption/decoded CKey. Unsupported encoded verification remains a blocker, never a silently skipped member.
- Freeze scope identity/definition revision and enumerated completeness. Full cannot become true until every required member/dependency is resolved and verified retained. Changes require an explicit new scope/replan rather than silently shrinking coverage.
- Tests: complete enumeration, variants/aliases/duplicate content, schema/format limits, encrypted supported versus unverifiable opaque, malformed indexes, unknown lengths, bounded work and no false completion from partial catalog.

### BB7 — Persist Download all intent and durable shared-CAS jobs

As a researcher, I want background retention to survive restart and reuse content, so I do not repeatedly download shared data.

Depends BB5/BB6 and canonical ownership; refines #94/#17/#18.

- Default On demand. First Download all admission discloses exact scope, known missing unique bytes, unknown costs and budgets. Persist mode, immutable scope revision, queued dependencies/members, actual progress and failures before presenting accepted intent.
- Acquire through the same canonical content policy, object verification, CAS and exact-build memberships used by on-demand work. Equal physical bytes have one owner; shared identities reuse one actual transfer/receipt. Respect current alternate-encoding refusal/association rules rather than retaining a second encoding to “finish.”
- Bounded worker/queue/byte/time/disk reservations, backpressure and retry policy are defined per operation. Durable resume reconciles actual CAS/catalog publication and active ownership; completed objects are reused. Do not promise partial-byte resume until verified; no permanent duplicate chunk/decoded source store.
- Offline startup restores mode/jobs but does not silently grant new consent or report missing bytes complete. Tests: restart at enqueue/verification/publish/attach boundaries, quota/disk/error, shared two-build objects and zero redundant completed-object fetch.

### BB8 — Keep foreground requests responsive during bulk work

As a researcher, I want interactive acquisition ahead of bulk retention, so the full queue cannot block an explicit request.

Depends BB7 scheduler contracts; existing #96 owns the core requirement.

- Reserve bounded interactive capacity; new foreground work bypasses queued bulk tasks. Same-source attachment promotes one shared flight without a second fetch; bulk yields at safe bounded request/range boundaries, without instantaneous preemption claims.
- On demand mode withdraws only this build’s bulk-consumer intent. Preserve retained bytes, active publication permits, other builds and independent interactive consumers. Cancellation does not release worker ownership until actual finally retirement.
- Resume fair bulk progress after interactive demand; explicit pause/retry semantics and stale context cannot mutate another build. No research screen is implemented to demonstrate this: scheduler/backend controlled foreground requests suffice.
- Tests: saturated queue, promotion, final/one-consumer cancel, priority/fairness, mode switch, worker budget and actual transfer/receipt preservation.

### BB9 — Restore overview, resume safely and declare completion honestly

As a researcher, I want progress/readiness preserved across return visits, so I know precisely what remains.

Depends BB5–BB8; refines #94/#17.

- Persisted mode/scope and queue projections restore after restart; explicit resume handles paused/failed work without resetting retained/reused counters. Returning to On demand deletes no data and claims no reclaimed space.
- Fully downloaded requires complete frozen inventory/dependency closure and current verified retained memberships for that scope. Missing/corrupt/unavailable member yields Partial/Needs attention; decoding/encryption availability remains separate.
- Known denominators permit accurate percentage; unresolved totals show measured subtotals/unknown count. Network wire, reused content and retained physical coverage are different quantities.
- Tests: fresh On demand, queued/downloading/paused/offline/partial/full, corruption after prior completion, same-byte two-build references and interrupted recovery. Independent interpretation/storage review precedes completion claims.

### BB10 — Qualify only the two selected JavaFX screens

As a desktop user, I want the guided entrance and C overview to stay readable and usable on supported platforms.

Depends accepted preceding stories; refines #3/#26 and the existing release milestone rather than creating a parallel release programme.

- Actual JavaFX captures at 1280×800 and 100%/200%, keyboard/focus/screen-reader naming, first viewport product control, plain Status placement and responsive stacked layout match the latest two references with justified usability corrections.
- Authored deterministic two-process flow: discover/group/local New → Open Build metadata admission → C overview → authorize real scope queue → foreground promotion/mode withdrawal → restart/resume/retained completion. Include offline, regional failure, unknown-cost, encrypted encoded-only and corrupt/incomplete states. No production demo numbers or fabricated game findings.
- Windows x64/macOS ARM64/macOS Intel/Linux bundled-runtime validation is stated by exact revision and actual checks. Native screen workflows on Linux do not automatically certify identical accessibility on every OS. Ordinary tests stay offline; separate bounded official evidence proves protocol support before advertising real full-scope capability.
- Existing workflows remain regression-tested; disabled planned destinations remain disabled, not blank implemented screens. No A/B home, research navigation destinations, monitor #95, GC or icon replacement is delivered by this scope.

## Sequencing and definition of done

First freeze domain/application identity, observation, metadata admission, full-scope inventory and durable job contracts. Deliver BB1–BB4 as working verticals rather than building a purely visual shell; BB5 can consume controlled storage projections while BB6 is implemented, but Download all is not called complete until BB6–BB9 work end to end. Parallel work requires stable ports and distinct file ownership. Protocol/storage safety precedes bulk concurrency; required tests precede each implementation step. Record exact unit, integration, native and separate official evidence in linked issues before closure.

The project manager maps/refines these outcomes to existing #92/#93/#94/#96 and creates only needed small children. Product/architecture review settles semantic completeness, quota/refusal, first-seen acknowledgement and queue consent in the shared implementation plan. No additional feature work is authorized merely by a disabled navigation label or by a mockup’s illustrative download control.

## GitHub delivery crosswalk

The authoritative work tracker is [Guided discovery and build overview](https://github.com/aircan80/vibeminer/milestone/5). This milestone has no dates or release promise. Design references #93/#94 do not substitute for implementation acceptance.

| Reviewed outcome | GitHub delivery issue |
| --- | --- |
| BB1 durable successful observations | [#92](https://github.com/aircan80/vibeminer/issues/92), reused existing story |
| BB1–BB3 regional library, first-visit baseline and New acknowledgement | [#98](https://github.com/aircan80/vibeminer/issues/98) |
| BB2–BB3 latest guided Discover UI | [#99](https://github.com/aircan80/vibeminer/issues/99) |
| BB4 exact Open Build/minimum verified metadata | [#100](https://github.com/aircan80/vibeminer/issues/100) |
| BB6 complete declared ROOT/IN/applicable DL streaming inventory | [#101](https://github.com/aircan80/vibeminer/issues/101) |
| BB6 durable sealed scope, EN index/candidates/locators and limits | [#102](https://github.com/aircan80/vibeminer/issues/102) |
| BB7 durable Download all/shared-CAS execution | [#103](https://github.com/aircan80/vibeminer/issues/103) |
| BB8 foreground promotion and independent bulk withdrawal | [#96](https://github.com/aircan80/vibeminer/issues/96), reused existing story |
| BB5/BB9 only C overview, current readiness and storage accounting | [#104](https://github.com/aircan80/vibeminer/issues/104) |
| BB10 two-screen end-to-end/native qualification | [#105](https://github.com/aircan80/vibeminer/issues/105) |

Sequence the actual application/catalog layers before claiming the controls work. Unknown or unparsed required manifest content blocks Full, not useful Partial retention. First-visit baseline and subsequent New-to-VibeMiner state are local database facts; they never establish release chronology or availability. Monitoring #95, research destinations and deletion/GC remain separate backlog work.
