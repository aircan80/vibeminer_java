# Bounded Item physical-row projection

**Status: #81 physical-row backend, runtime and Atlas view are published in verified [alpha.4](releases/v0.1.0-alpha.4.md), exact `891a9c4`, with all four native targets passed.** This is the bounded #20 slice, not all table/search acceptance. Final Item UI acceptance exercised 85 assertions across two JVMs; [central evidence](PLAN.md#delivered-baseline-and-current-work) records the exact test/package/protocol/image limits. The [exact cohort](ITEM-COHORT.md) and contracts below define admitted physical rows, four candidate integer fields and copies-unindexed coverage. Full decoded-table CKeys, names and general search are not claimed.


The same admitted physical Item scope remains available in current verified [alpha.5](releases/v0.1.0-alpha.5.md); its later source and package qualification do not expand table/name coverage.

## User outcome and exact context

From an already retained supported Item encoded object, explicitly prepare the admitted zero-key section, page its physical records, look up an exact Item record ID and inspect four attributed integer candidates with source evidence. Preparation and subsequent queries operate offline, with zero HTTP. Missing required retained evidence is an actionable missing-local outcome, never permission to acquire or repair content automatically.

The initial admitted cohort is official product `wow`, US, build `12.1.5.70448`, explicit `enUS` context, Item FileDataID `841626`, declared CKey `1df6b6dbae36878cea020a62b15b9bd6`, WDC5 table hash `50238EC2`, layout `996192AA` and its validated zero-key section. Preserve exact build/configuration/root-variant and actual source-verification evidence; version labels or filenames alone cannot select a decoder.

**FileDataID identifies a file; Item record ID identifies a row.** `841626` identifies the Item table file, not every row inside it. Initial columns are Item record ID and the four candidate fields `ClassID`, `SubclassID`, `InventoryType`, `IconFileDataID`. The latter is a projected field, not a renaming of the row ID. Field labels and interpretation come from the pinned WoWDBDefs candidate described in [the cohort](ITEM-COHORT.md), including provider revision, definition SHA-256, license and attribution. They are not Blizzard-authored semantic guarantees. Preserve raw values alongside candidate signedness/width interpretation; no automatic icon download follows an icon ID.

## Admitted scope and proof

Index physical rows in the admitted section only. Copy declarations remain inspectable metadata but **copy rows are not indexed in this slice**. Display admitted physical-row counts and excluded copy/section scope explicitly. A copy ID or an ID outside this indexed scope cannot prove whole-table absence. A nonzero section-key declaration alone proves neither readability nor unreadability.

Keep these evidence dimensions distinct:

- Complete encoded-object authentication and local physical SHA-256 verification.
- Authenticated consumed BLTE chunks and the decoded section/metadata windows admitted to projection.
- Declared decoded CKey association from exact root/EN evidence.
- Complete decoded-length/CKey verification, only if actually established.

The first projection can be ready for its explicitly authenticated admitted section without claiming complete decoded-table verification. Verify the retained encoded object before reuse and authenticate every required admitted window. Late corruption, unsupported required storage, truncation or failed section proof must not publish a ready artifact. Do not label a partial section or unsupported source as a complete Item table.

## Responsibilities and shared derived storage

Use the existing layers rather than introducing a general-purpose table framework:

- **Domain:** immutable projection identity, row values, exact source/proof and interpretation/coverage descriptors. The artifact identity binds source content, decoder/version, selected section, schema provider/revision/file/selection evidence, locale and options.
- **Application:** explicit prepare, bounded page and exact-ID lookup use cases; retained-only policy, cancellation and immutable results. No HTTP, SQL, JavaFX or concrete parser types in their public contracts.
- **Pure format adapter:** compose bounded WDC5 metadata, fixed-section index and numeric primitives with exact candidate schema selection. Extract reusable logic from diagnostic composition into tested production contracts; application code must not invoke diagnostic entrypoints.
- **Infrastructure:** acquire CAS read leases, verify required bytes, stage shared projected rows and publish the artifact/index atomically only after declared-scope checks pass. SQLite stores one projection per artifact identity; build memberships reference it. Do not persist a second complete decoded table for paging. Queries use prepared values, bounded limits and stable ordering.
- **Desktop/bootstrap:** Atlas presents application results; bootstrap wires adapters and lifecycle. Background workers finish before storage closes.

Matching source/interpretation/proof inputs reuse the shared artifact. Changed schema, decoder, locale, options or admitted coverage cannot silently reuse an incompatible index. An interrupted prepare leaves no ready partial index; restart may prepare again under explicit user action. Ready local queries do not depend on the CDN or private working files.

## Atlas interaction and query boundary

Use the existing research/table destination, preserving the exact product/build/locale context strip. A persistent coverage label states that the view contains admitted zero-key physical rows. Before readiness, show distinct retained-but-unprepared, missing-local, incompatible-schema/layout, unsupported and corrupt states. The explicit **Prepare supported rows** action discloses bounded work and supports cancellation; phase/progress values come from actual work.

The first query interface supports only:

1. Bounded pages ordered by numeric Item record ID.
2. Exact unsigned Item record ID lookup.

Defer field filters, additional sort choices and free-text search until a concrete follow-on need justifies them. Cursors bind exact artifact, snapshot and query version; reject stale cursors instead of mixing results. The inspector exposes raw values, candidate attribution, physical record identity and full source/proof/coverage context. Empty results say no match in the indexed physical-row scope, without asserting absence from the entire build. When an excluded copy/section reason is known, show it explicitly.

Keyboard activation, focus return, page navigation, cancellation and 100%/200% scaling follow Atlas conventions. A cancelled request or older workspace/query completion cannot replace newer content. Missing/corrupt evidence clears any current ready claim rather than leaving misleading rows visible.

## TDD and acceptance sequence

1. **Compatibility and values:** exact build/table/layout/schema/locale/section selectors; four uniquely bound candidate fields; preserved raw signed/unsigned boundaries. Missing, ambiguous or incompatible definitions do not create guessed rows.
2. **Physical-row projection:** bounded synthetic records and required numeric modes; duplicate IDs, malformed geometry, overflow, truncation, unsupported mode, copy exclusion, cancellation and mismatched section proof. Late failure exposes no ready projection.
3. **Publication and queries:** atomic readiness, unchanged-input sharing, changed-input invalidation, bounded stable ID pages, exact-ID lookup, stale cursor rejection and restart/offline zero HTTP. Whole-source corruption cannot preserve an unsupported complete-source verification claim.
4. **Application/presentation:** preparation never invokes transport; invalid IDs make no query; cancellation/context changes suppress stale completion; missing/corrupt/unsupported states remain actionable. Complete the keyboard page/lookup/inspector route and compare native screenshots at reference sizes/scales.
5. **Independent acceptance:** review source/proof and schema interpretation separately from UI usability. A later explicitly authorized bounded real acceptance records identities, counts and coverage, without committing actual row values or names. Writing this design does not authorize a live request.

Normal tests use deterministic synthetic fixtures and temporary app-owned SQLite/CAS storage. No game installations, game caches, live CDN availability or ignored working documents are test dependencies.

## Exclusions and follow-on work

No copy-row enumeration or chain resolution; ItemSparse names or joins; name search; arbitrary field filters or SQL; related-record navigation; exports; thumbnails; cross-build comparison; automatic content acquisition/repair; full decoded-CKey upgrade; general table support; deletion/GC; full-game archives. Supported Sparse name-prefix projection and exact-context joins remain a later [#21](https://github.com/aircan80/vibeminer/issues/21) outcome. Existing selected-file acquisition, retained root/install workflows and the two-record diagnostic retain their own distinct acceptance boundaries.
