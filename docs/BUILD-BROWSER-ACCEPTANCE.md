# Guided build browser acceptance

This is the test and qualification plan for guided **Discover builds → Open
build → Build overview**. It defines future acceptance, not delivered behavior.
The selected overview is the C design; the earlier alternative homes are excluded.
Follow the [discovery review](design/build-discovery/REVIEW.md),
[overview proposal](design/build-mining-home/UX-PROPOSAL.md) and
[implementation plan](BUILD-BROWSER-IMPLEMENTATION.md) and
[delivery stories](BUILD-BROWSER-STORIES.md).

The intended user chooses an exact WoW product/build and opens its metadata
without downloading the game. An optional per-build background policy retains a
declared scope; foreground research remains responsive. VibeMiner uses official
publication/CDN sources and its own store, independently of game installations.
This change does not authorize live requests, periodic monitoring or release.

## Current capability boundary

Existing production discovery requests one product/region pair. Durable discovery
history and exact selected-build evidence are foundations for aggregation, not
proof of complete historical or global publication coverage. Official product
names are display aliases with reviewed evidence; raw IDs remain authoritative.

Current root lookup answers selected FileDataIDs, not a complete file browser.
Selected EN pages, complete-root verification for that lookup, retained IN lists
and bounded selected-file acquisition do not establish a complete downloadable
game inventory. IN is not the whole-game inventory. Applicable download-manifest
references, platform/locale selection semantics, complete enumeration and durable
bulk scheduling require their own accepted slices. Root flags remain raw unless
their interpretation has been validated; the illustrated Windows/enUS scope is
not already supported merely because the mockup labels it.

Consequently, no current metadata count, sample Item/Sparse cohort, archive size
or successful selected-file fetch may authorize **Download all**, a whole-build
percentage, a complete catalog total or **Fully downloaded**. The initial browser
must remain honest while those prerequisites are incomplete. Game data, Files,
Assets, Compare and Findings destinations are disabled future capabilities in
this scope. Existing narrow research tools do not make those broad destinations
implemented. No repeated research launcher cards or alternative home is added.

## Behavior and test matrix

Write a failing test at the lowest boundary that owns each new behavior, then
implement it in a small coherent slice. Add real composition tests where
independently sound adapters could disagree; do not substitute a presenter fake
for the corresponding metadata, scheduler or storage acceptance.

| Requirement | Meaningful deterministic tests | Real composition and failure gate |
| --- | --- | --- |
| Product-first guided discovery | Exact WoW ID and reviewed description; unknown names remain raw and discoverable. Product selection starts the bounded publication refresh once; filtering/row selection performs no payload fetch. A late product A completion cannot repaint B. | Synthetic summary/versions/CDN documents through real bounded transport/parser/application mapping. Empty, malformed, unsupported pointers, duplicate/conflicting rows, partial regions, cancellation and retry retain their actual check scopes. No guessed product or host. |
| One local/advertised list | All/Local/Currently advertised operate on independent facts. Same display version with different complete references stays distinct. Exact grouping preserves every regional observation and duplicate-row provenance. | Persist observations, reopen offline, then refresh with one region unavailable or a local build no longer advertised. Neither case deletes local state, marks it remotely unavailable, nor treats failed refresh as definitive absence. Grouping retains exact route choices. |
| First-seen New | Derive first/last observation from committed exact reference facts, not version sorting, label changes, fetch time or a region badge. Repeated observations are idempotent; changed identity-bearing configuration is a new identity. | Restart, duplicate input and concurrent refresh cannot reset first-seen or emit duplicate notifications. Partial/error/canceled observations invent no discoveries. The first successful product visit establishes a baseline without marking all results New. Only subsequent new exact identities receive New to VibeMiner, persisting until Open acknowledges it. Repeated refresh cannot rebadge an acknowledged identity. New means newly observed here, not newly released or previously unavailable. |
| One explicit Open build action | Selecting a row performs no dependency fetch. Open binds product, complete references, route, locale and opening generation. Warm verified metadata returns the exact context; missing prerequisites perform only explained metadata work with visible stages. | Use actual synthetic configuration/root/IN/EN prerequisites required by the accepted slice. Hold each required opening dependency independently: no early navigation, game payload or hidden full scan. Full inventory may continue preparing after the overview opens; unknown totals stay explicit. Missing/corrupt/unsupported input stays on the chosen build with a typed retry. Cancellation and A→B replacement cannot open A after B. |
| Metadata-first responsiveness | A warm open is independent of a blocked network/bulk queue. Metadata opening is not queued behind a whole-build enumeration or payload transfer. UI remains usable while cold prerequisites verify. | Latch-controlled transports prove metadata-only work, bounded concurrency and actual retirement. Record cold/warm wall time, UI stalls, request/byte counts and peak memory on representative authored inputs; establish a measured performance budget before claiming a speed target. No arbitrary sleep or invented completion percentage. |
| Exact build overview | Persistent product, complete build, advertised regions, source route, locale and derived status agree with the selected context. Returning to Discover preserves filters/selection. No named-workspace prerequisite. | Fresh process and offline reopen preserve original evidence/check times. Context/locale changes fence stale completion and inspector state; friendly aliases never change identity or select another build. Source details expose copyable exact facts. |
| Background on/off | Default On demand. Download all requires explicit scope, supported complete inventory, known additional-byte subtotal plus unresolved costs, and consent. Turning off withdraws only bulk intent; already retained objects and independent consumers survive. | Actual scheduler plus real CAS/SQLite: pause/off while queued, acquiring, verifying and after promotion; resume/restart uses verified retained coverage, not reset counters. Actual worker/lease/nonce retirement, failure and crash recovery are observed. No deletion, unpin of another scope or promised reclaimed space. |
| Foreground priority | With every bulk slot/queue occupied, an interactive request receives reserved bounded admission and bypasses queued bulk work. Shared work is promoted rather than fetched twice. Bulk yields at safe request/range boundaries and resumes fairly. | Controlled early, late and closing-flight schedules; same and different representations; one consumer canceled while another remains; last cancellation with ignored I/O; failed transfer and retry. Assert actual transport count and held ownership, not merely that a task was submitted. No instantaneous-interruption or zero-latency promise. |
| Exact deduplication | Resolve canonical representation/proof before adoption. Same verified physical SHA is stored/counts once; exact source requests coalesce. Different CKeys may share physical bytes only with verified associations. Same CKey with another declared EKey does not permit arbitrary substitution. | Cross-build/region/locale fixtures with shared A, distinct B, unknown sizes, conflicting origin and unavailable physical bytes. Reopen and verify original receipt plus new association separately; missing/corrupt aliases cannot count as ready. Existing worker permits and conservative recovery remain enforced across producers. |
| Readiness and metrics | Derive Not ready/Partial/Fully downloaded separately from On demand/Download all and operation activity. Unknown, unresolved, missing and corrupt are not zero. No meaningful denominator means no percentage. | Complete and incomplete inventories, unknown lengths, missing objects, conflicting claims and shared physical mappings exercise the same service projections used by UI. Refresh measurement and restart cannot promote a partial set to complete or sum overlapping per-build totals into store usage. |
| Unsupported and failure routes | Only affected capability is disabled. Explain missing metadata, unsupported parser, retained encoded bytes, verification failure and unchecked remote availability separately. | Cover offline retained/missing, unsupported product/format/route, unavailable source and malformed bounded inputs. No fabricated successful catalog, substituted latest build, false absence, automatic repair or hidden content transfer. |

Successful discovery persists first-seen state. The first successful product
visit establishes a baseline without New badges; subsequent newly observed
identities receive New to VibeMiner until Open acknowledges them. The exact minimum metadata set for opening each supported build and
the complete bulk inventory are different contracts. Freeze both before their
implementation tests; a mock value or generic list of manifests resolves neither. Periodic monitoring remains separately opt-in and bounded under
[#95](https://github.com/aircan80/vibeminer/issues/95). Foreground scheduling is
tracked by [#96](https://github.com/aircan80/vibeminer/issues/96).

## Status and accounting oracle

Keep the overall status beside Locale as plain text. Metadata/component details,
current publication, checked availability, download mode and active work remain
separate facts. Retained percentage is separately labelled and Unknown until
its denominator is proven. The On demand / Download all selector appears once,
in the Build readiness body, never repeated in the identity header. The expected
state table is independent of UI labels or buttons.

| Verified facts for the explicitly declared scope | Overall status | Additional requirement |
| --- | --- | --- |
| Required opening metadata missing, invalid, unresolved or unsupported | Not ready | Local discovery documents or some payload bytes cannot override this. |
| Opening metadata ready; any required content absent/unverified, or inventory/coverage incomplete | Partial | A successful metadata open normally reaches this state, even with no payload retained. |
| Opening metadata ready, complete admitted manifest closure, every required representation locally verified | Fully downloaded | Unknown membership/length, conflict, missing/corrupt content or unverified prerequisites prevent this claim. No universal decoding/decryption promise. |

An active download can be Partial; a Fully downloaded scope can use On demand.
Paused/offline/error are activity or attention facts, not additional readiness
values. A stale measurement carries its observation time; current verification
failure invalidates readiness instead of preserving an earlier positive badge.

The initial bulk scope is **all declared content in the admitted manifest
closure**: ROOT plus IN plus applicable DL dependencies and entries, including
DL-only members. Unparsed or unresolved required content blocks Full; a Partial
overview may still open. Research
locale is preserved but does not silently filter bulk membership. An illustrated
Windows/enUS subset requires separately reviewed, versioned flag/tag semantics.
Initial scope includes all variants. Unsupported required inventory cannot be
excluded to improve completion.

An independent small set-based oracle should calculate expected distinct logical
membership, physical SHA sets, shared subset and missing representation costs.
Use overlap cases that make an incorrect sum visibly wrong. Keep encoded local
payload, logical decoded sizes, actual response bytes, metadata/derived overhead
and unknown costs separate. Do not infer missing cost by subtracting physical
bytes from decoded size, count aliases as retained bytes, or interpret a root
name hash as a filename. Percentage denominators must name scope and unit.

## Fixture and qualification ladder

Before each implementation slice, freeze explicit limits for publication events,
region fan-out, document/body/decoded bytes, enumerated memberships and variants,
index pages, actual active workers, queued intents, in-flight bytes, staging disk,
database growth, retries and elapsed work. A bounded transport alone does not
bound the full inventory, SQLite growth or multiplied regional work. The limits
belong to versioned contracts; this plan does not invent defaults before protocol
and memory measurements establish them.

For each frozen quota, test just below, exactly at and one beyond the limit with
independently counted work. Include duplicate-heavy input that must not consume
distinct-object quota twice, and unresolved input that must still consume its
bounded processing allowance. Admission refusal changes no prior consent,
observation, membership or readiness; it performs no eviction or implicit repair.
Backpressure and cancellation retain actual capacity until I/O and database
leases retire. Test saturated background and foreground admission separately,
including a worker that ignores cancellation and an executor that rejects work.

1. **Pure contracts and TDD:** exact identity/grouping, first-seen transitions,
   status derivation, set-based accounting and bounded queue/admission policy.
   Use independent expected sets and hand-authored identity changes; preserve
   original provenance and checked-versus-unknown distinctions.
2. **Protocol/transport fixtures:** authored official-shaped publication and
   captured metadata, complete and partial manifests, bounded BLTE/config/root/EN
   data, exact range/HEAD behavior, malformed bytes, unknown sizes and delayed
   cancellation. Scripted adapters test policy; local HTTP tests establish real
   header/body/deadline behavior. Normal CI never contacts the live CDN.
3. **Real composition:** actual application services, readers, schema/manifest
   validation, CAS, SQLite and startup/session fencing across at least two JVMs.
   Cross-build sharing, offline open, missing/corrupt data, restart and competing
   producer ownership must use real storage. Explicit proof-port stubs must be
   labelled and cannot qualify the protocol checks they replace.
4. **Native JavaFX journey:** production chrome and real fixture services at
   1280×800 and larger size, 100%/200% text scale, Noto and CI-like DejaVu.
   Verify product → results → Open → exact overview, loading/failure/retry,
   offline return, mode on/off, New and unknown statuses. Assert complete visible
   bounds for required labels/actions, then independently inspect screenshots.
   Check keyboard order/focus retention and no stale context after async work.
   Separate any injected chooser, scheduling gates or fake payload service from
   the real behavior being claimed. Future disabled destinations stay inert.
5. **Platform/package gate:** run appropriate unit/integration contracts and
   bundled launch/resource/native SQLite checks on Windows, macOS Intel/ARM and
   Linux. Native Linux screenshots do not establish other-platform layout,
   screen-reader or physical-keyboard support. Actual filesystem/provider
   results qualify that environment, not every filesystem or CDN route.

Third-party protocol research, reviewed product-name sources, parser fixtures
and earlier narrow real-CDN evidence explain particular interpretations. They
do not prove an unlimited current catalog, historical availability, readable
encryption, complete game download, live server behavior or future release
timing. Any separately authorized live probe has its own exact identity,
request/byte/time limits and evidence scope; it is never a normal CI dependency.

## Delivery gates

Each slice must be runnable and honest before the next: exact discovery/history
projection; metadata-first opening and context continuity; overview/status
accounting; complete scoped inventory; durable background retention; foreground
priority and shared acquisition; final native/package qualification. This order
allows useful metadata-first browsing before broad content support exists.
Download all and Fully downloaded remain unavailable until their complete-scope
proof gates pass. Visual prototypes, simulated counters and disabled menu labels
are design evidence only. No tests or implementation are delivered by this plan.
