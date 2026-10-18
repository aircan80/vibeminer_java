# Bounded Item names and exact-context join

**Status: scoped paired-name child #90 is closed after exact `2be2962` all-four/all-ten native qualification; broad #21 still retains relations and separate official-run acceptance.** CI 39125032855 attempt 2 succeeded after Intel's transient Maven DNS recovery. Earlier failed INSTALL geometry and local overlay results remain distinct historical evidence, not outstanding failure of that completed gate.

Later literal search #89 has accepted local `c17ec25` qualification: 1,333 clean Java cases, verified Linux development package 24, and 109 assertions (92+17) per Noto/DejaVu run across two JVMs with zero HTTP and 21 PNGs per font. The newer `62513a2` all-four/all-eleven gate passed and scoped #89 is closed after independent criterion review. See [search contract](ITEM-NAME-SEARCH.md), [paired-name references](design/item-names/README.md), [search references](design/item-name-search/README.md) and [central checkpoint](PLAN.md#delivered-baseline-and-current-work). Alpha.5 remains the published baseline; no full-table decoded proof, copies, gameplay availability or alpha.6 publication is claimed.


This scoped workflow is included in published [alpha.6](releases/v0.1.0-alpha.6.md), exact `ee6598f`; four-target/eleven-workflow publication and independent verification of all 12 final assets passed. Earlier alpha.5 exclusions below describe that historical release, not the new alpha.6 capability.

## Research outcome and source selection

From two already retained sources, explicitly prepare the admitted ItemSparse physical-name projection and join its names onto the existing bounded Item record-ID pages and exact-ID lookup. The Item index remains the row anchor. No download, repair or preparation follows automatically from opening the screen or querying an ID.

The UI must require an explicit **source pair**: the prepared Item source and the retained ItemSparse source from the same exact build snapshot and requested locale. Show both FileDataIDs, declared CKeys, actual retained EKeys and independent readiness/coverage. A build or locale label alone does not select a source. Missing retained evidence is a local missing outcome, not permission to acquire another source. Source selection and preparation remain cancellable, and an older pair's completion cannot replace the current pair's results.

## Exact admitted cohort and schema

Use the existing [cohort evidence](ITEM-COHORT.md), not a current/latest schema assumption. The initial context is official product `wow`, US, build `12.1.5.70448`, explicit `enUS`. ItemSparse FileDataID is `1572924`, declared CKey `bb0ecbdc2fd4988ddd3c1c5e95c34165`; the previously observed actual EKey is `cab35eb8601260c12a5917c1bdacfda5`. A retained descriptor must independently authenticate the actual representation used. Do not replace its original receipt with another encoding's evidence.

The selected root membership mask `0x202` includes requested enUS bit `0x2` and enGB bit `0x200`. Preserve the exact selected variant and explicit requested locale. US region and raw DB2 locale value `1` do not establish enUS. No other-locale fallback is admitted.

The observed ItemSparse header is WDC5 schema version `5`, table hash `919BE54E`, layout `1C17D17F`, flags `5`, 67 fields and 49 sections. All 67 observed storage descriptors are `NONE`; common and pallet data sizes are zero. The admitted first section declares a zero key, 175,503 physical records and 122 copy declarations; 48 other sections remain excluded. A section-key declaration alone proves neither readability nor unreadability.

The bundled candidate is [WoWDBDefs ItemSparse.dbd](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/ItemSparse.dbd), immutable revision `b9a63096cdd99b26967f17f9515377ae62cadfd7`, SHA-256 `8664c3b5862dfe4c3a4322941b48b032c57b90412c070deb2028f3e448cf674a`. Preserve WoWDBDefs contributor attribution, CC BY-SA 4.0 licensing and the bundled notice/license. These are attributed community field candidates, not Blizzard-authored semantic guarantees.

After the noninline record ID, the selected definition orders five inline localized strings:

1. `Description_lang`
2. `Display3_lang`
3. `Display2_lang`
4. `Display1_lang`
5. `Display_lang`

Project only the fifth string as the initial name candidate. Locate it by safely reading all five bounded UTF-8 NUL-terminated strings within the validated physical record slice. Do not skip an assumed fixed byte count or interpret these sparse inline strings as ordinary string-table offsets. An empty fifth string is a valid empty candidate, distinct from a missing row. Trailing numeric fields remain uninspected. Header record size `328` does not supply each variable record's length; the validated sparse map's unsigned 16-bit size does.

## Bounded decoding and publication

The observed encoded source is 7,988,612 bytes; its declared whole decoded length is 62,715,198 bytes. The admitted record area is `[5252, 60166704)`, followed by the ID/copy/map suffix `[60166704, 62624722)`. These are recorded cohort observations, not hardcoded completion counts.

Authenticate the complete retained encoded object and replay its exact original root/EN association locally. Authenticate the required metadata, complete suffix and consumed record chunks before their decoded bytes contribute to a projection. Keep these evidence dimensions separate:

- Complete encoded verification and current local physical SHA-256/length verification.
- Exact consumed chunk identities, encoded/decoded geometry and admitted decoded windows.
- Declared CKey association from the selected root/EN evidence.
- Complete decoded CKey verification, only if independently established.

The 2,458,018-byte suffix (approximately 2.34MiB) can be an explicitly bounded buffer. The 60,161,452-byte record area (approximately 57.37MiB) must be streamed; do not enlarge Item's 4MiB section-buffer contract silently or persist a second decoded table. Decode required chunks in a bounded pass, retaining at most one bounded decoded chunk and a bounded record tail across chunk boundaries. Repeatedly inflating the same chunk once per row is not an acceptable enumeration strategy. Validate absolute sparse coordinates and ID/map-ID coherence; do not repair offsets by accumulating sizes.

Choose explicit limits before production implementation: encoded bytes, header/chunk count and sizes, metadata/suffix bytes, physical records, copy declarations, record-area bytes, per-record bytes, five-string prefix bytes, per-string UTF-8 bytes, aggregate projected text and staging batches. The physical record-size ceiling is 65,535 bytes. Do not truncate names silently to fit a limit. A malformed or over-limit late record must leave no ready partial name index.

Callbacks are provisional until the declared physical projection completes. Use bounded nonready staging and a short atomic ready transition, retaining the CAS read lease through publication. Cancellation discards unpublished staging; a completed atomic publication may win a cancellation race without delivering a ready result to the cancelled caller. Required opaque/unsupported chunks produce a scoped unsupported outcome, with no hidden key lookup or alternate-source fallback.

## Shared artifacts, joins and honest outcomes

Store one independent Sparse physical-name artifact, keyed by its physical source content, exact schema/selection/decoder/index/string-validation revisions, locale, options and admitted coverage. Build memberships reference shared rows. Do not duplicate the existing Item integer rows in every name artifact or hash snapshot observation timestamps into shared derivation identity. Changed interpretation inputs require a distinct artifact.

The contextual join requires separately validated memberships for both artifacts in the same exact snapshot and locale. Join unsigned Item record ID to unsigned Sparse physical record ID. Physical names do not expand copies. Preserve copy target/source declarations as inspectable metadata; do not resolve chains or manufacture a target's values from a source row in this slice.

The Item-based left join distinguishes physical name candidate, empty physical name candidate, known Sparse copy declaration unindexed and no physical Sparse match in the admitted scope. An Item row without an admitted name retains its ID and four integer candidates. Sparse-only rows do not silently become Item rows. Neither side's excluded sections or a scoped nonmatch establish whole-table absence. Do not join the same numeric ID across builds, editions or locales.

Queries must recheck both retained sources and required proof before trusting persisted artifacts. Item readiness does not prove Sparse readiness. Preserve each artifact's historical preparation evidence separately from any new verification observation. Inspectors expose both source/proof chains, candidate attribution and independent counts/coverage. Render names as plain text; they do not authorize markup execution, icon downloads or inferred gameplay availability.

## Delivery and TDD sequence

1. **Pure decoder:** add a narrow Sparse physical-name projector composing the existing schema selector, sparse suffix parser, validated locator and C-string prefix reader. Test exact field ordering/layout/flags, positive unique IDs and map IDs, absolute contiguous bounds, unindexed copies, empty/non-ASCII strings, invalid UTF-8, missing terminators, record/chunk boundaries, late failure and cancellation. Malformed uninspected trailing fields must not be labelled verified.
2. **Domain and application:** define separate string candidate/name coverage and exact source-pair contracts, then explicit retained-only prepare, bounded pages and exact-ID joined lookup. Test source/locale/snapshot substitutions, unavailable names and copies, invalid limits before I/O, stale handles/cursors, bounded jobs and cancellation. Do not force strings into integer candidate DTOs or introduce a general table framework.
3. **Source reader and storage:** replay complete encoded and consumed-window proof, stream records with bounded allocations/work, stage one shared name index and atomically publish. Test corruption, required opaque chunks, late rollback, restart with zero HTTP, shared artifacts across independently validated memberships, changed-interpretation separation, lease retirement and absence of duplicate decoded tables or duplicated Item rows.
4. **Atlas UI:** require the explicit retained source pair and preparation action. Show separate readiness, proof and physical/copy/excluded coverage; retain actionable missing/corrupt/unsupported states. Add joined name inspection without hiding an Item row whose name is unavailable. Verify keyboard navigation, cancellation, stale-pair suppression and 100%/200% scaling against native screenshots after #81 UI acceptance.
5. **Independent acceptance:** review format semantics, source proof, storage sharing and UI behavior independently. Normal tests use authored IDs/strings and temporary app-owned storage. Existing two-ID diagnostic evidence is corroboration, not general production acceptance. Any later live acceptance needs its own bounded authorization and records proof scope without committing game rows or names.

Free-text name search follows an explicit Unicode normalization/case and indexed-query design after the exact/paged join works. Full tooltips, other Sparse fields, copy expansion, related-table joins, additional locales/builds, comparison, icons and automatic acquisition remain outside this first name slice. This document authorizes no source changes or network work.

## Proposed retained-query proof replay

**Approved in principle; reader implementation and independent acceptance remain pending.** A name query must independently reverify both retained source chains. It may reuse the names artifact's trusted, persisted admitted decoded-window proof only when the complete current encoded object's SHA-256, length and actual EKey are identical, and the exact schema/decoder/index/proof revisions and authenticated structural recipe manifest match. Recheck the retained association, complete encoded verification, authenticated BLTE header and bounded metadata/suffix recipe. Compare the original required-window/chunk manifest against that authenticated structure; SQL row counts alone cannot establish readiness.

This permits a query to avoid enumerating the 57.37MiB Sparse record area again merely to read an existing index. It preserves the original admitted-window attestation and preparation timestamp. Current physical verification is a distinct observation; it does not mint a new decoded-window attestation, refresh historical proof or establish a complete decoded CKey. Missing, corrupt or inconsistent original proof prevents reuse and produces an actionable local failure. Existing Item source verification semantics remain unchanged. Both source leases must span the joined index read.

Local extraction and root replay have separate bounded costs. The existing offline selected-file verifier can replay the complete retained root for each selected source; a source pair can therefore require two independent root passes. Current root-work defaults permit up to 512MiB decoded work per pass, independently of Sparse extraction limits. A small Sparse window budget must not be presented as a combined operation ceiling while those separate stages run. Disclose and enforce each stage's bounds; no cross-source verification shortcut, unbounded cache or implicit network work follows from this policy.

## Atlas workflow and presentation contract

The existing Item physical-row workspace remains independently usable. Name candidates are a subordinate optional mode, requiring the current prepared Item handle and a separately verified retained ItemSparse descriptor (FileDataID 1572924) for the same exact snapshot and enUS locale. If Sparse is missing, an explicit action may open Find file with that ID seeded; navigation must not perform lookup, encoding selection, acquisition or preparation. Preparing names is a separate explicit local action. The pair references the existing Item artifact and does not duplicate its integer rows.

Keep receipt history, current source verification and prepared handles distinct. Selecting Sparse must not overwrite the same-context Item source or its prepared handle merely because Sparse becomes the most recent selected-file receipt. A later integration must keep independent exact-snapshot/source-role source records. Navigation within the same context preserves the workspace; a build, locale, selected source or interpretation change cancels stale work and revokes paired handles/cursors. Historical receipts never silently restore readiness after a failed verification.

Use bounded Browse and exact positive Item record ID (1–4294967295), with no unsupported text-name search, arbitrary sort or filters. At 100% scale the joined table has ID, Display candidate and status columns. At 200% scale prioritize ID and Display candidate, with status visible inline and in the inspector, never available only through a tooltip. The four integer candidates remain in the inspector. Names render as plain text, preserve Unicode and allow full-value copying. A physical empty Display string is labelled an empty candidate; it is distinct from an unindexed name-copy declaration and no physical name match in the paired scope. An Item copy or scoped Item nonmatch is not a physical join anchor. A missing Sparse match does not discard the physical Item row or its integer candidates.

Show independent Item and Sparse physical/copy/excluded counts, source identities, original receipts, current verification observations, artifact and definition revisions, attribution and proof dimensions. Neither a candidate nor a paired handle establishes a complete table, decoded file, build archive or gameplay availability. Page cursors bind both artifacts, both source roles, the exact snapshot/locale and fixed query/options; changing either side resets the first page. Preparation and returned page/lookup scopes must be validated before presentation.

Missing/corrupt paired evidence revokes joined readiness, but a generic pair failure does not identify which source failed and must not falsely mark the independent Item artifact corrupt. Confirmed required ROOT/configuration failure revokes both affected dependent handles in that exact context. An unrelated INSTALL failure does not. Fresh verification clears only matching source failures and never automatically prepares or queries names. Cancellation and late completions cannot repopulate a changed context. There is no implicit repair, fallback or network request.

Desktop delivery proceeds through a new pure presentation model and synthetic asynchronous tests first, then separately authorized source-registry integration and compact controls after backend acceptance, followed by real portable native keyboard/scaling acceptance at 100% and 200%. New controls must not ship before their actual service and source-proof paths are ready.
