# Format and search implementation plan

Formalized specification. Implementation and validation status are tracked in GitHub Issues.

## Decoder spike and supported subset

1. Agree immutable input/output contracts with protocol/storage. Pure format code takes bounded readable input, expected content identities, exact BuildRef and cancellation. The application/storage adapter owns leases and handle lifecycle; the format parser does not open arbitrary paths or discover game installs. TACT owns configs, encoding/root/index resolution and BLTE; DB2 adapter owns record interpretation. Both use the same bounded binary primitives. Query adapter owns page cursors; parsers expose records only.
2. Implement a small Java BLTE reader: valid magic, zero-header single-block and table-based 0x0f blocks; N raw and Z zlib only initially. Use JDK zlib with exact output limits. Verify block hashes where present, validate all header/count/offset arithmetic, and confirm decoded CKey according to protocol rules before publishing. E encryption and F/unknown modes return typed unsupported capabilities, never zero-filled records. No decryption/key-service promise.
3. Structural DB2 probe reports magic, table/layout identifiers, section/field counts and feature flags without claiming semantic support. Choose one observed WoW build/table only after protocol evidence establishes the actual magic/features. Proposed current-era target is WDC5, not an assumption that every current/Classic/Forever file is WDC5. WDBC may provide a small synthetic parsing exercise but does not satisfy item exploration acceptance.
4. WDC5 subset starts with bounded section parsing, fixed records, explicit or external IDs, string decoding and only storage modes required by that one table. Add signed/unsigned bit fields, common/pallet/pallet-array storage, sparse offset maps, copy records, relationships and multiple sections as separate tested capabilities when required. A whole table remains unavailable for complete indexing if any encountered section/mode is unsupported or encrypted; do not silently skip it. Partial metadata can remain browsable.
5. Expand to minimum useful item table bundle only after mining expert validates exact-schema joins. Item + ItemSparse are candidate dependencies, joined only after validating selected-build item IDs and locale/name sources. A name-only table proves decoding, not complete item acceptance. Every additional required DB2 mode becomes a decoder slice before claiming item support. No local hotfix/cache imports; findings describe CDN client data.

First fixture decision gate: begin with synthetic BLTE N/Z fixtures and a synthetic WDC5 structural/fixed-record fixture, with no external parser dependency assumed. Before defining the first real semantic slice, record chosen build/table magic, complete required feature list, exact schema revision, useful expected rows and all missing dependencies. Either the narrow Java decoder passes synthetic fixtures for every encountered feature and later explicit real integration, or mark that table unsupported and deliver raw metadata only. Do not redefine success as a legacy fixture working when the selected real table is incompatible.

## Reuse decision

Java candidate https://github.com/brecky/warcraft4j documents CDN and dataparser modules, but current WDC5 coverage, bounded parsing, dependency footprint, licensing and maintenance are unverified. Do not adopt it based on README scope. Source/license inspection and synthetic differential checks are a concrete spike task; public browser access to its license/subtree failed during this planning pass.

Prefer small Java implementations for the narrow supported subset, consulting pinned TACTSharp and DBCD source and retaining required notices for any translated code. Neither C# library becomes an end-user dependency. JNI/CascLib remains a fallback requiring an explicit packaging/maintenance decision, not a hidden change to the selected architecture. No Python/Node subprocesses.

## Exact schemas and provenance

SchemaResolver accepts product/game namespace, edition identity, exact build, table hash/name, layout hash, structural signature and pinned provider snapshot. It returns MATCH, MISSING, AMBIGUOUS or INCOMPATIBLE plus evidence. Match applicable build and layout selectors; reject conflicting candidates and validate physical field count/types. Never fall back to latest or same-looking filename. Legacy layouts without hashes require validated exact-build rules.

Ship a reviewed subset of WoWDBDefs with upstream revision, schema file SHA-256, upstream URL, license/notice and parsed definition version. Do not record a fictitious pinned commit now: implementation must resolve and record the actual immutable revision. Community names and foreign-key annotations remain distinct from Blizzard bytes. Preserve unknown/unverified '?' names and confidence. Filename metadata likewise retains its separate provider/revision. No network schema refresh changes an existing result silently.

Decoded identity: all source verified content hashes + decoder identifier/version + schema snapshot/file/selected-layout digest + locale + options. Schema selection evidence is part of interpretation identity even if two builds resolve identical byte content. Build membership points to a shared artifact; it does not copy decoded payloads. TableSnapshot includes completeness and unsupported reasons. The application resolves dependencies and schedules storage/indexing through ports; the format module remains pure.

## APIs and query model

- BlteDecoder: verified encoded handle + limits + cancellation -> complete decoded artifact or typed failure (malformed, integrity mismatch, unsupported, cancelled).
- TableProbe: decoded source -> structure/features; SchemaResolver: exact context + probe -> explicit match result.
- TableDecoder: complete source + exact SchemaMatch -> closeable typed RecordCursor and provenance; reject duplicate IDs or invalid copy/relation targets instead of choosing an arbitrary row.
- RelationResolver: typed record reference + declared validated relation -> resolved/not-indexed/missing-target/unsupported, with dependency table and source evidence. Never infer every integer is a foreign key.
- CatalogQuery: immutable snapshot/membership, allowlisted fields/operators/sort, page limit and continuation -> immutable page + coverage + provenance. No arbitrary user SQL in MVP.
- CompareService: two exact table snapshots + validated identity/schema mapping -> paged diff or incompatibility; ExportService streams a pinned query snapshot/selection rather than reading a changing UI page.

Index typed scalar columns and canonical raw record payloads once per artifact. Membership retains build scope. Index IDs and approved relation fields with B-trees, optional text projection/FTS for selected name fields after benchmark. Choose typed table-per-schema or generic projection implementation with storage engineer before DDL; avoid broad untyped EAV scans. Read-only prepared queries, bounded results, deterministic null ordering and unique-ID tie-breaker; keyset pagination preferred over unbounded OFFSET. Filters apply selected build/locale and artifact status first. Never turn not-yet-indexed into zero results. Report scope, indexed/required table coverage and missing dependencies. Cancellation applies decoding, indexing and paging; publish indexes atomically only after complete validation.

Mining review: missing item names display Item #ID with actual locale/name-source reason. Unresolved relations retain target table/key and availability reason. Mount→spell→item and similar semantic edges require separate edition-specific review, not hardcoded universal joins. Forever product mapping remains unverified.

## Comparisons and export

File comparison aligns verified stable file identity in the same edition and locale/content context; a changed encoding alone is not changed decoded content. Record comparison aligns validated primary keys and compatible field mappings. Same-schema typed values compare losslessly; preserve integer widths, array order, raw floating values and explicit null/missing distinctions. Do not round floating values for equality. Different field labels alone are schema metadata changes. Across layouts, compare only with validated mapping; otherwise disclose incompatibility. No cross-edition ID-only matching.

Added/removed requires complete relevant snapshots on both sides. Missing file, unknown schema, encryption and unindexed scope produce unknown/incomplete, never removed. A byte change is not evidence of obtainable content or live mechanics. Canonical fingerprints may accelerate equality but verify interpretation identity and confirm candidate differences with typed values.

CSV/JSON exports stream the explicitly selected scope with cancellation and atomic final rename. Include both build identities for diffs, locale, CKey/source hash, decoder/schema provider revisions, query/filter/sort, coverage and timestamp. CSV uses a metadata sidecar; JSON uses an envelope with metadata and rows. Preserve 64-bit values losslessly (document tagged/string representation where consumers cannot represent them), escaping, nulls and array values. Provide spreadsheet-safe CSV text handling with disclosed transformation or explicit raw mode. Never export only the displayed page while labeling it all results.

## Synthetic TDD acceptance before implementation

- BLTE N/Z single/multiple chunks; bad magic, hash, count, overlap, truncation, output expansion, unsupported E/F, exact boundary and cancellation; incomplete outputs never published.
- DB2 golden synthetic rows exercise every declared feature, signedness, unsigned 64-bit, string offsets/terminators, duplicate/copy IDs, invalid relation, missing sections, compression variants, malformed arithmetic. Unknown variant yields capability status without rows.
- Schema exact/absent/ambiguous/wrong-layout/wrong-build; unchanged bytes with different schemas produce different derived identities. '?' confidence and pinned revision survive query/export.
- Paged search stable across ties/nulls, bound limits, prepared-value injection strings, Unicode names, empty versus not-indexed distinction, cancellation and snapshot consistency. Shared artifact queries never mix build memberships.
- Diff equal/changed/add/remove plus incomplete input, locale mismatch, layout mismatch, schema rename, ID collision across editions, float and integer precision. Incomplete input cannot prove removals.
- Export CSV escaping/newlines/formula-like text, JSON integer precision, metadata completeness, selection/all-results scope, cancellation leaves no misleading finished file.

Tests are synthetic and deterministic; no live CDN CI. A later explicitly authorized end-to-end integration records exact build/config hashes, supported table and coverage. Synthetic success proves parser behavior, not compatibility with an unseen real build.

## Blockers and handoff

No parser code exists and no current payload headers have been inspected; exact useful WDC feature set is unproven. Resolve through the later bounded protocol/format spike before committing item-decoder acceptance. Pin actual upstream commits/licenses before reuse/distribution. Mining expert must validate item name/base-record dependencies and joins for the chosen build. Architect/storage must settle artifact versus membership keys, index publication and reader leases. These block semantic implementation promises, not initial discovery/raw metadata work.
