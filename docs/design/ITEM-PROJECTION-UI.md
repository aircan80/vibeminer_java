# Atlas Item physical-row interface

**Status: design for [#81](https://github.com/aircan80/vibeminer/issues/81), not implemented or visually accepted.** The [Item projection contract](../ITEM-PROJECTION.md) defines the admitted source, interpretation, proof and query boundary. This document specifies its native Atlas presentation. It authorizes no live requests and contains no actual row values, names or screenshots of a claimed implementation.

## Outcome, placement and identity

Use **Database → Item**, within the selected exact-build workspace. Title **Item physical rows**. Keep the existing ivory paper, teal actions, dark ink, serif section title, readable body text and persistent exact product/channel/region/build/locale context. Research may link here with the label **Inspect Item physical rows**; opening that link does not prepare an index or acquire content.

A compact source line precedes the task: **Source table file · FileDataID 841626 · Item** for the admitted source, supplied from application identity. Visually separate it from the query field **Exact Item record ID** and the first table column **Item record ID**. `IconFileDataID` is one candidate row field, not the row ID or a request to load an icon. Do not use a generic ID label for these three roles.

The initial supported cohort is the precise `wow` / US / `12.1.5.70448` / `enUS` context and table/layout in the projection contract. A matching display version alone does not establish compatibility. The application must establish the exact retained file/root variant, configuration, table/layout, provider and admitted section. Other contexts show compatibility or missing-evidence reasons; never silently choose this cohort, a latest build, another locale or another schema.

Do not reproduce the prototype's broad table catalogue, name-search box, item names, icons, relation buttons or exports. A single Item selection or compact table selector is sufficient; unsupported tables are not represented as empty decoded datasets.

## Hierarchy and coverage

The main workspace has this order:

1. Compact title and source-file identity.
2. Persistent local coverage strip: **Admitted zero-key section · Physical rows only · Copies not indexed**. Show the actual admitted section identity, indexed physical-row count and excluded scope when returned. Before preparation, say **Not prepared** instead of displaying a ready coverage claim.
3. Current stage, preparation action or exact-ID/page controls.
4. Five-column virtualized row table, or a truthful stage/empty/error panel.
5. Selected-record inspector beside the table when space permits, below it or in an accessible drawer at larger text scales.

Keep **Candidate fields · WoWDBDefs contributors** visible near the table or query outcome. It applies to the four interpreted fields, not to a claim of Blizzard-authored semantics. Counts come from the prepared artifact's verified coverage descriptor. Do not bake diagnostic counts into the interface, count an entire table to populate a footer, or include copy declarations in the physical-row count.

**Other sections not indexed** is the default exclusion language. A section-key declaration alone does not justify “encrypted”, “unreadable” or “readable”. If a particular unsupported or unavailable section is established, render the precise application outcome and source. A partial scope never becomes a complete Item table badge.

## States and explicit actions

| State | Main presentation and action |
| --- | --- |
| No exact workspace | **Choose an exact build to inspect Item physical rows.** Link to existing source selection; no prepare/query request. |
| Unsupported or incompatible context | Identify the unsupported build/locale/table/layout/definition condition. Offer **Inspect source and compatibility**; no guessed rows, silent schema substitution or acquisition. |
| Missing retained dependencies | **Required retained evidence is missing.** Identify the missing scope and retain metadata/proof that remains valid. An optional **Inspect retained source** link opens the existing exact-source view; it does not start acquisition or repair. |
| Retained, unprepared | **Supported source retained · rows not prepared.** Primary **Prepare supported rows**. Explain **Offline work only. Authenticates the admitted section and builds a local physical-row index; no network request. Copies and other sections are excluded.** Show application-supplied work limits or explain that duration/work totals are unknown. |
| Preparing | Actual phase, exact source and admitted scope; **Cancel preparation**. Use indeterminate progress unless real totals support a bounded measure. Do not stream staged rows into the result table or expose a partially published index. |
| Cancelled/interrupted | **This request did not establish a ready projection.** No local ready-table claim follows cancellation. Shared work may continue when the application supports it; a late completed artifact is not ruled out by cancellation alone. **Prepare supported rows** remains an explicit retry when supported, and any reuse first establishes readiness through retained-only application policy. Reopening does not resume or prepare automatically. Cancellation does not imply unrelated retained encoded bytes were deleted. |
| Ready | **Admitted physical rows ready** plus scope and attribution. Bounded numeric-ID pages and exact-ID lookup become available. This is not complete decoded-table verification. |
| Reading a page/looking up an ID | Preserve the current input/focus and show a local-query status. Do not present old page rows as the new query's result. A retained prior page, if shown, is explicitly marked previous and noncurrent until the new result arrives. |
| Exact match | **Physical row in the indexed section**; show the actual Item record ID and four candidates. Select the exact scoped record for inspection. |
| No exact match | **No match in indexed physical rows. Copies and other sections are not covered.** This does not establish whole-table/build absence. |
| Known excluded copy/section | If reported, **Copy declaration is not indexed in this projection**, or the precise excluded section reason. Show requested ID and known exclusion metadata without fabricating a physical row, resolved copy chain or candidate values. |
| Empty admitted scope/page | Distinguish **No physical rows in the admitted prepared scope** from an exhausted page and an exact-ID nonmatch. Readiness and exclusions remain visible. Do not interpret an empty last page as whole-table absence. |
| Missing/corrupt evidence after readiness | Clear rows, selected interpreted values and current ready/proof claims that depended on it. Distinguish **Retained evidence missing** from **Evidence failed verification**. No automatic source repair or network fallback; preserve inspectable failure context. |
| Unsupported required storage/decoder or failed section proof | **Projection unavailable for this admitted scope**, with the actual reason. No partially ready artifact or guessed fields. Unsupported is distinct from missing bytes and verification failure. |
| Query cursor invalidated | **This page belongs to an older projection. Return to the first page.** Do not merge pages or silently reuse a cursor for changed source/schema/locale/options. |
| Busy/capacity/storage failure | Keep task capacity, bounded-work limits and storage errors distinct; **The app is busy. Try again shortly** is not a claim of disk exhaustion. Stage and safe retry remain inline. |

There is no transfer-cost card: preparation and queries are retained-only. Show actual bounded local work without borrowing the selected-file download UI's byte labels. A supported encoded file can remain retained while its projection is unavailable; the header must distinguish those scopes rather than claim all source bytes are corrupt or absent.

## Query and table boundary

The first interface offers only **Browse physical rows**, **Exact Item record ID**, **Look up ID**, **First page**, and **Next page** when the application returns a next cursor. Keep one active query mode and label it. **Browse physical rows** or **First page** returns from an exact lookup to bounded enumeration. Do not imply a Previous control unless its cursor/page-history behavior is supported; a bounded session cursor history may later provide it without arbitrary offsets.

Pages are ordered by numeric Item record ID. Do not enable header sorting, alternate sort selectors, field filters, arbitrary SQL or free-text search. Display the application's fixed bounded page limit and returned range/count where known; no unbounded “All rows” option. Artifact physical-row totals, page-row counts and exact-match counts are separate quantities.

Validate one unsigned decimal record ID using the application policy, including its supported range. Reject blank, negative, decimal, multiple-ID or overflow input before querying, preserve the entered text and associate the error with the field. Do not coerce IDs through signed 32-bit storage or silently strip significant digits. Typing makes no request. Invalid input must not make the existing page appear to answer that input: either keep it explicitly labeled as the unchanged prior page/query or clear the result area until the input is corrected. Return invokes only **Look up ID** when that action is visibly available; it never prepares an artifact or acquires content. An exact lookup does not change the source FileDataID.

The table has exactly these columns:

| Column | Meaning and presentation |
| --- | --- |
| Item record ID | Stable unsigned physical-row identity in the exact artifact. A plain number, not the table's FileDataID. |
| ClassID | Attributed integer candidate; preserve raw representation and candidate width/signedness in the inspector. |
| SubclassID | Attributed integer candidate; no inferred category name. |
| InventoryType | Attributed integer candidate; no invented equipment-slot description. |
| IconFileDataID | Attributed integer candidate; no automatic thumbnail, preview or file acquisition. |

No name or icon column is reserved as an empty-looking promise. Plain numeric cells remain copyable in full inspector fields. Selection keys include artifact identity, exact snapshot/query scope and Item record ID, not a reused row index. Page changes must not leave a detached record selected as though it belonged to the current page; either preserve a separately labeled pinned inspection or clear the inspector and announce the new page. This slice defaults to clearing selection on a different page/query.

## Record inspector and interpretation evidence

Selecting a physical row shows **Item record [ID] · Physical row**, with its exact source and admitted section. Use standard controls and progressive disclosure:

- **Candidate values:** four named values, each with raw value/bit representation and the actual candidate width/signedness interpretation supplied by the application. An unsupported or ambiguous definition does not produce named candidate cells. Do not infer enum labels, item usefulness, obtainability or server behavior.
- **Physical identity:** Item record ID, artifact identity, admitted section and physical record coordinates/index when provided. Source FileDataID remains a separate labeled field. No copy row is fabricated here.
- **Source and proof:** original encoded acquisition receipt, actual local physical SHA verification, authenticated consumed chunks/admitted windows and current exact root/EN association. Full hashes, URLs, identities and verifier revisions are selectable/copyable; timestamps say whether they are original acquisition, local verification or artifact preparation.
- **Candidate definition:** provider **WoWDBDefs contributors**, pinned revision, definition path and SHA-256, selected build/layout/field evidence, license **CC BY-SA 4.0**, attribution and stable upstream source/license links. The provider revision is `b9a63096cdd99b26967f17f9515377ae62cadfd7` for this cohort; display the actual application's pinned descriptor rather than an unrelated latest revision. Schema attribution is distinct from Blizzard source evidence and the application's code license.

The proof summary has four separately labeled dimensions:

| Dimension | Required boundary |
| --- | --- |
| Complete encoded object / local physical SHA | Actual complete-encoded authentication and local physical verification with their reported times; original receipt remains identifiable. Cached artifact readiness does not invent a fresh verification timestamp. |
| Consumed decoded chunks / admitted windows | Exactly which section/metadata windows were authenticated for this projection, with exclusions. |
| Declared decoded CKey association | Authenticated root/EN declaration; it is not proof that the full decoded stream matches the declared key. |
| Complete decoded length / CKey | **Not established** unless a separate complete verification result actually exists. Never infer it from ready physical rows. |

Expand technical evidence without making all hashes mandatory reading before a row can be inspected. The compact coverage and community-candidate attribution remain visible when expanders are closed. Icon IDs are text; no link silently joins another record or downloads another file.

## Async, reuse and restart

All preparation, page and lookup calls go through asynchronous application use cases; the JavaFX view performs no HTTP, storage, SQL, binary decoding or schema selection. Each request binds exact snapshot, locale, artifact identity, query revision and a desktop generation. Context/query/cursor changes cancel the relevant subscription and reject stale success, failure and progress even if cancellation loses a race.

A prepared artifact is reusable only when the complete source/decoder/schema/locale/options/coverage identity matches. Shared rows across build memberships do not imply compatible meanings across editions. After restart, the application may report an existing artifact and its verification/readiness state through retained-only operations; opening the screen must not start a new prepare, repair or CDN request. If the application cannot establish readiness, show unprepared/unavailable rather than trusting a prior UI flag. Ready local queries do not depend on the CDN.

Save only supported session presentation state, such as the draft exact ID, query mode or inspector expansion, within the correct workspace. Never carry rows or a stale ready handle to another source. Cursor expiry or reprepare returns explicitly to the first bounded page. A cancelled or interrupted prepare cannot expose rows from staging as a ready index.

## Native sizing, focus and accessibility

Use the existing Database destination with a compact workspace title, a paper table panel and an ink/teal inspector. At 1280×800 and 100%, a narrow Item selector or single-table tab avoids spending a third of the width on an empty table catalogue. A roughly 70/30 table/inspector split is appropriate only when five headers and useful numeric cells fit. Coverage and the current action should appear before the first page rows, without a second dashboard hero or tall decorative cards.

At 200%, use the full table width and move the inspector below it or into a labeled expandable drawer. Wrap long column headers, keep their accessible names complete, and retain all five fields in full detail. Outer scrolling reaches actions and evidence; any bounded table scrolling is deliberate, with no accidentally clipped last row or blank fixed-height viewport for small result sets. Do not shrink fonts to make the layout fit. Main application content must not clip horizontally. Preparation status/cancel, exact-ID validation and current scope remain reachable without relying on the footer.

Tab reaches prepare/cancel, query, page controls, table and inspector. Return in the ID field only looks up; Enter on a table row focuses its inspector; Escape returns to the table and restores the exact row when still present. Page navigation has descriptive accessible labels and announces page/query scope, result count and exclusions. Preserve ID input focus/caret through validation and background updates. Completion does not steal focus from another task; when its initiating control disappears, focus the relevant new action, results or inline error deliberately. Cancellation is a labeled action, not an implicit Escape shortcut.

Real progress is throttled for visual and screen-reader updates; unknown totals stay indeterminate. Ready, excluded, unsupported and error states use text rather than color alone. Use the established Atlas contrast-corrected secondary/caution tokens. Tooltip text supplements full accessible names and copyable evidence; it does not replace them.

## Implementation and acceptance gates

This design precedes application DTO agreement and production UI work. Proposed control IDs for coordination are `item-prepare`, `item-cancel`, `item-record-id`, `item-lookup`, `item-browse`, `item-first-page`, `item-next-page`, `item-row-table`, `item-row-detail`, `item-coverage`, `item-source-proof`, and `item-schema-attribution`; names may be finalized with the implementation/harness owner before coding.

Presentation tests must cover no automatic preparation/transport; invalid ID makes no query; preparation/cancellation without a local partial-ready claim or unsupported global-publication inference; stale workspace/query/progress suppression; numeric page ordering and cursor isolation; exact physical match versus known excluded copy versus scoped no-match; copy/other-section coverage; four distinct proof dimensions; schema attribution; and revocation of rows/readiness after missing/corrupt evidence. Format and publication tests separately establish real values, supported modes, source/window proof, atomic index readiness and shared artifact reuse.

Native acceptance uses deterministic synthetic data with application policy and temporary app-owned stores where feasible. Exercise prepare → cancel → retry → first page → next page → exact-ID lookup → Enter inspector → Escape table → restart retained-only query, plus missing/unsupported/corrupt/schema mismatch and late-response context changes. Assert visible current action/coverage, five complete accessible headers, page/lookup focus and copyable full proof. Compare actual JavaFX screenshots at 1280×800, 100% and 200%, against Atlas typography, spacing, palette and hierarchy. A static design or future HTML specimen is not native acceptance, and fake UI projections do not establish production decoder/index correctness.

No names, ItemSparse joins, thumbnails, field filtering, additional sorting, relationships, exports, cross-build comparison, full-table verification, implicit acquisition or cleanup is included. A later explicitly authorized real-data acceptance records identities/counts/proof, not committed actual row contents or names. This document itself starts no live activity.
