# Selected build preparation workflow

The owner selected **option 2, the dedicated preparation view**, from [design #122](https://github.com/aircan80/vibeminer/issues/122). See the [interactive reference](design/build-preparation/index.html?option=dedicated&state=indexUnknown). This specifies intended behavior; production lifecycle contracts and implementation still require review and qualification.

## Entry and work readiness

Open Build resolves the exact build and requested research locale. When that context needs genuine preparation, such as required metadata acquisition or local inventory/index construction, show the dedicated preparation view **before Build Home**. Initial saved-context reuse checks remain in Discover with truthful checking activity; revalidation alone must not cause a preparation-view flash for a valid prepared build. Enter Home only after actual preparation succeeds and the admitted inventory/target is usable. Complete metadata admission alone does not establish that local preparation is finished.

An already prepared, currently valid context bypasses the preparation view. Use a bounded asynchronous reuse/revalidation decision; do not flash a fake download or force a wizard for valid saved work. Saved state is not authority: missing, corrupt, incompatible or changed evidence must fail closed or require genuine preparation. Do not promise that deciding reuse requires no work or is always instantaneous.

Work-ready does not mean Fully downloaded. A prepared On demand build legitimately enters Home as Partial; this workflow does not require downloading payloads or authorize Download all.

## Job ownership and navigation

Preparation belongs to an application/composition-owned job, not the view instance. **Back to builds keeps preparation running**; it neither requests cancellation nor discards completed work. Returning to the same exact context attaches to the existing job and its current progress/result instead of starting duplicate work. A completed valid context opens Home directly.

Identify a job using the exact admitted build/reference/snapshot, locale and relevant metadata/inventory interpretation and scope; an equal display version is insufficient. Shared physical content remains owned once by the existing store. Navigation never substitutes another region, locale or build into a running request. Stale completion must not navigate a user who has returned to the list or selected another context; automatic Home entry is only for the currently attached requested context.

Use existing borrowed execution resources with explicit bounded admission; this decision authorizes neither unbounded concurrent builds nor a new executor/pool/store. The minimal implementation retains one active preparation job across Back. Another build request must report the busy/current-job state or require an explicit switch with cancellation and actual retirement before replacement. Returning to the list alone must not switch or cancel it. Freeze concrete ports and ownership before production changes.

Cancel is an explicit cooperative request, distinct from Back. Show Stopping until actual work and leases retire. Cancelling a derived future or closing a view cannot fabricate retirement. Preserve verified shared content, immutable receipts and committed valid artifacts. Retry is explicit and exact-context, respects budgets and revalidates reusable complete artifacts; unfinished STAGING is not row-resumable. Mode-activation retry and failed bulk-job recovery remain separate (#119).

No new durable cross-process job continuation is promised. After application restart, revalidate persisted complete artifacts through existing startup/authority rules; incomplete staging follows existing safe recovery/rebuild semantics. Never present a saved percentage as a resumable checkpoint.

## Honest activity

Show a meaningful current stage and measured elapsed duration. Existing metadata stages have optional transfer/requirement counters; local inventory currently has coarse stages without reference totals. Unknown totals remain indeterminate. A percentage may describe only a genuinely instrumented stage with a trustworthy matching numerator/denominator. Proposed encoding-index page/byte progress requires implementation and proof before use; no weighted overall percentage, invented ETA, guessed record count or SQLite-size download counter.

Transfer 100% still permits verification/indexing/finalization. Throttle/coalesce immutable progress off the JavaFX thread, preserve terminal delivery and fence stale context. The view performs no HTTP, SQL, parsing, indexing or proof validation.

## Dependencies and acceptance

Track preparation implementation as a child of #122, coordinated with selection reentrancy fix #123 and measured SQLite performance work #124. Performance changes must not weaken FULL durability, complete source/grammar verification or exact forward/reverse lookup equivalence. The UX remains useful even when totals are unknown or work takes minutes.

Qualify deterministic lifecycle tests for Back/reattach, one actual job, prepared bypass, busy/switch admission, cancellation/late commit, error/retry, restart revalidation and stale navigation. Real app-owned SQLite/CAS tests prove reuse and fail-closed corruption without game-install access. Native screenshots at1280×800/1440×1100 and100%/200% compare the selected reference, with keyboard/focus and progress announcements. Cover fresh, saved, offline reusable/missing, long unknown work, completed transfer with verification outstanding, stopping and failure. No implementation completion or release is claimed by this document.

## Application contract checkpoint

The initial application contract uses `BuildPreparation.Context`: the full registered build reference, research locale, metadata scope revision, inventory interpretation revision and configured policy commitment. Equal display labels or a shared physical object do not make contexts interchangeable. Its structural result check preserves the original consumer reference and both interpretation scopes; concrete adapters must still validate current source and policy authority.

`BuildPreparationService.prepare(context)` joins the same active job; another active context is typed BUSY. `retry(context)` is explicit and cannot replace an unretired producer. One active job and at most one completed hint are retained. A failed/cancelled job is not silently restarted. A terminal READY result becomes historical once its operation retires: a later Open or re-entry requires fresh authoritative checking before Home, even when the completed hint matches. The currently attached view may consume the just-completed result without starting preparation again.

A borrowed `Job` exposes its UUID, context, latest immutable progress and read-only completion stage. Cancelling a derived future does not cancel or retire its producer. Explicit `requestCancellation()` requests cooperative stopping. Runtime-owned service shutdown cancels its jobs without closing borrowed producer services. Back and view disposal only detach observation. Application orchestration composes asynchronous producer stages without blocking joins or a new executor.

`BuildPreparedContextLookup.find(context, cancellation)` asynchronously checks current complete artifacts. It performs no HTTP, rebuild, STAGING promotion, alias invention or new target issuance. Empty means the complete reusable context is absent; corruption, unsupported interpretation and exhausted limits remain typed failures. Its stage settles after actual work and lease cleanup. This is a port contract, not a claim that the concrete check-only adapter has been delivered.

Progress identifies a real stage and one unit: encoded bytes, decoded bytes, requirements, pages or rows. Optional completed/total counts share that unit; a denominator requires a numerator and cannot be smaller. Unknown remains indeterminate. Terminal READY/CANCELLED/FAILED observations carry no fabricated transfer counts or overall percentage; original producer evidence and failure causes are preserved. Elapsed presentation time is separate from source receipt timestamps. The three contract files are a first slice; the coordinator, concrete lookup, producer instrumentation and dedicated screen still require implementation and qualification.
