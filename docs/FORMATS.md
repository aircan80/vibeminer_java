# Format and search implementation plan

Formalized specification. Implementation and validation status are tracked in GitHub Issues.

## Current implementation — bounded synthetic BLTE subset (#31)

`protocol-tact` contains `BlteDecoder` with synthetic tests for zero-header single-block and 0x0f chunk-table forms, N/raw and Z/zlib, including mixed chunks. Callers supply exact expected decoded size and encoded/decoded/chunk-count bounds. Table arithmetic and declared sizes are checked before output allocation; inflation enforces exact size and rejects trailing, truncated or dictionary-dependent streams. Cancellation is checked during copying, hashing and inflation. A failed decode returns no partial byte array.

Chunk MD5 checks cover the encoded chunk including its mode byte. Optional expected EKey checks cover the complete BLTE header when present, or the whole encoded object in zero-header form; optional CKey checks cover complete decoded bytes. These MD5 protocol identities are separate from local SHA-256 object identity. Because expected hashes are optional at this parser boundary, a successful call alone is not a claim that upstream source identity was verified; acquisition must provide the required verified expectations.

`BlteException.Reason` distinguishes `MALFORMED`, `INTEGRITY`, `UNSUPPORTED`, `LIMIT` and `CANCELLED`; bad caller arguments remain argument errors. Encryption and other modes are unsupported; there is no key lookup or decryption. The implementation is an in-memory pure parser, with no CDN resolution, storage publication or UI integration. Ordinary tests are synthetic. A separate [bounded live check](PROTOCOL-EVIDENCE.md#first-verified-configuration-and-blte-object--2026-10-18) verified one exact install-manifest BLTE object; broader payload compatibility, DB2 decoding, schema resolution, item search and semantic comparison remain unproven.

Primary contract references reviewed on 2026-10-18: [TACTSharp BLTE](https://github.com/wowdev/TACTSharp/blob/67fd53ac852ca297e9bf95bb41d1a97da202eba4/TACTSharp/BLTE.cs) and [TACT.Net encoded-key writer](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/BlockTable/BlockTableStreamWriter.cs). These inform an original narrow implementation; no external parser dependency is bundled.

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

The synthetic BLTE subset above exists; DB2 parser support and the exact useful WDC feature set remain unproven. One exact live BLTE object has passed verification; this does not establish general payload or DB2 compatibility. Resolve through the later bounded protocol/format spike before committing item-decoder acceptance. Pin actual upstream commits/licenses before reuse/distribution. Mining expert must validate item name/base-record dependencies and joins for the chosen build. Architect/storage must settle artifact versus membership keys, index publication and reader leases. These block semantic implementation promises, not initial discovery/raw metadata work.

## Current configuration and install-manifest subset (#33, #35)

`TactConfigParser` verifies full-byte configuration MD5 before strict UTF-8 interpretation and enforces byte, line, token and declared bootstrap limits. Unknown fields remain immutable metadata; duplicate assignments fail explicitly. Direct encoding/install bootstrap pairs retain CKey, EKey and decoded/encoded sizes separately. A CKey-only install entry requires encoding-table resolution and is unsupported by the initial direct-object workflow.

`InstallManifestParser` supports decoded IN version 1 with 16-byte content keys. It bounds counts and names, validates terminated UTF-8, reads big-endian unsigned sizes and preserves MSB-first tag membership in immutable results. Entry names are metadata, never filesystem paths or proof of complete game-root membership. Eight synthetic tests and independent review passed; the exact [verified live object](PROTOCOL-EVIDENCE.md#first-verified-configuration-and-blte-object--2026-10-18) parsed as 274 entries and 31 tags. These parsers do not resolve arbitrary game files.

## WDC5 header inspection foundation (#38)

`protocol-db2` initially inspects the mandatory 204-byte WDC5 prefix, with a separate bounded descriptor inspector described below. This length is derived from the actual sequential fields in the [pinned DBCD reader](https://github.com/wowdev/DBCD/blob/e732093f8864240fc5884bd1bba6b02f3dfc0d56/DBCD.IO/Readers/WDC5Reader.cs), whose `HeaderSize = 200` constant does not include all bytes consumed through the section count. Flag identities were independently checked against the [matching flag definitions](https://github.com/wowdev/DBCD/blob/e732093f8864240fc5884bd1bba6b02f3dfc0d56/DBCD.IO/DB2Flags.cs).

The header inspector bounds unsigned metadata, retains exact table/layout/schema identities and reports expected-identity mismatches and unsupported flags. Its result always identifies body validation, schema semantics and record decoding as unavailable or unverified. Strict UTF-8 and embedded-NUL rejection are deliberate limits of this supported subset, not claims about every upstream file. Eleven synthetic tests include every prefix truncation from zero through 203 bytes. That increment acquired no actual DB2 table; later #51 verifies one Item header only (see protocol evidence). Item/ItemSparse, sparse strings, noninline IDs, field storage, copy/relationship sections and locale semantics remain separate work.

## EN lookup foundation (#39)

The pure TACT module now parses a bounded EN variant with 16-byte keys. It verifies the 22-byte header, page/index bounds, first-key ordering, full padded-page MD5 values, supported sentinels and record boundaries. Content entries preserve every candidate EKey and the declared 40-bit decoded size; available encoded-size/spec records remain distinct, and absent metadata stays absent. Encoding specifications and the supported trailing file-spec text are preserved as bounded opaque metadata rather than interpreted as download instructions.

Twelve synthetic tests and independent review passed against the pinned [TACT.Net reader/writer](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Encoding/EncodingFile.cs) and [TACTSharp lookup](https://github.com/wowdev/TACTSharp/blob/67fd53ac852ca297e9bf95bb41d1a97da202eba4/TACTSharp/EncodingInstance.cs). No complete real encoding manifest has been acquired or validated; the separately recorded range proof covers selected authenticated pages only. The current in-memory lookup model can be expensive for large manifests; choose explicit byte/page/record bounds, measure memory and introduce paging/indexing before claiming production support for the observed 188 MB encoding object.


## Bounded WoW root foundation (#40)

The pure `format-wow` module parses explicit TSFM versions 1 and 2 with bounded complete inputs. Version 2 uses packed 17-byte block headers; original flag components remain separate from their lossy legacy OR. It preserves every FileDataID variant, CKey, locale/content flags and optional name hash without selecting a preferred platform or locale. A present zero name hash differs from an absent hash; neither supplies a filename.

Twelve synthetic tests and independent review passed, including variants, counts, truncation, unsigned delta overflow, cancellation and unsupported headers/flags. Header counts describe memberships, not distinct IDs. The pinned [TACTSharp reader](https://github.com/wowdev/TACTSharp/blob/67fd53ac852ca297e9bf95bb41d1a97da202eba4/TACTSharp/RootInstance.cs) supplies primary framing evidence. A separately authenticated live prefix confirms the observed version-2 header and first block grammar, but is incomplete and cannot pass the complete-root parser. The later bounded streaming parser and one complete exact live-root verification are recorded in protocol evidence. Item table acquisition and broader format compatibility remain unverified.

## Pinned item cohort and header acquisition (#28, #45)

The reviewed WoWDBDefs revision is `b9a63096cdd99b26967f17f9515377ae62cadfd7`. Its [manifest](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/manifest.json), [Item definition](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/Item.dbd) and [ItemSparse definition](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/ItemSparse.dbd) provide these community metadata candidates for exact version `12.1.5.70448`:

| Table | FileDataID | Table hash | Candidate layout hash |
| --- | ---: | --- | --- |
| Item | 841626 | `50238EC2` | `996192AA` |
| ItemSparse | 1572924 | `919BE54E` | `1C17D17F` |

The separately listed legacy `Item-sparse` (FileDataID 841677, table hash `6A7C6E76`) is not an interchangeable fallback. Independently checked SHA-256 values for the two definition files are `076ea4c7e80c891b399cc77f285c807071080c0fed95701e447f7c98024ae120` (Item) and `8664c3b5862dfe4c3a4322941b48b032c57b90412c070deb2028f3e448cf674a` (ItemSparse). No downloaded definition content is bundled by this documentation change.

The next bounded acquisition outcome is two exact table-header results with source identities, locale/content variants, requested transfer cost and verification scope. Resolve the selected FileDataIDs through authenticated root/EN evidence, verify each consumed BLTE chunk, and inspect the 204-byte prefix only if its observed magic is WDC5. A partial search nonmatch remains indeterminate. Header inspection does not establish full decoded CKey verification, schema semantics or row support. The candidate table/layout identities above must agree with actual bytes before enabling a matching decoder; the subsequent #51 experiment validates the Item table/layout header match only. the subsequent explicit-enUS #45 experiment also validates the ItemSparse table/layout header match. Complete table verification and rows remain unsupported.

The minimum subsequent item projection combines Item's noninline ID and supported class/subclass/inventory/icon identifiers with ItemSparse's noninline ID and localized base display name, joined only within the same exact edition/build. The pinned sparse definition requires sparse record handling; fixed record offsets cannot locate its name safely. Implement the observed ID maps, string and field-storage modes before claiming name/ID search. Missing names or paired rows retain their identities and explicit reasons; richer tooltips, effects and acquisition sources remain separate capabilities.

The pinned [license](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/LICENSE.md) assigns definitions/data **CC BY-SA 4.0** and designated code **BSD-3-Clause**. Preserve contributor attribution, source/revision, license references and modification notices when distributing schema artifacts; review applicable share-alike requirements before adapting/distributing definitions. Parser-reference licensing does not replace schema licensing. The [pinned README](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/README.md) also identifies unverified column markers: retain that uncertainty and the pinned interpretation revision in findings and exports.


## Streaming selected root lookup (#44)

`SelectedRootLookup` consumes a caller-owned decoded stream using a fixed 8 KiB buffer and bounded requested-ID/matching-membership collections. It retains all selected variants while skipping unrequested key/hash arrays, validates total/named counts and complete EOF, and leaves stream ownership with the caller. Thirteen new tests plus independent fragmented-stream differential checks passed. Structural success establishes neither source authenticity nor complete decoded CKey verification; a composition layer must establish those before publishing verified membership or absence.

## Root flag framing and opaque metadata (#47)

The pinned [TACTSharp root reader](https://github.com/wowdev/TACTSharp/blob/67fd53ac852ca297e9bf95bb41d1a97da202eba4/TACTSharp/RootInstance.cs) and independently pinned [CascLib WoW root reader](https://github.com/ladislav-zezula/CascLib/blob/38a34665624b8775bb875274b36191b21c38d97b/src/CascRootFile_WoW.cpp) preserve complete raw flag words. In the supported modern array layout, NoNames controls whether name hashes are present; an enum's list of named bits is not a framing allowlist. Unnamed bits must remain opaque metadata without invented behavior. Preserve original version-2 components as well as the derived lossy OR, and retain strict structural version, extension, arithmetic and count checks. This correction follows the stopped bounded live check; it does not itself establish complete live-root compatibility.

## WDC5 section and storage declarations (#55)

`Wdc5MetadataInspector` extends prefix inspection to bounded 40-byte section descriptors, signed 4-byte field metadata and 24-byte field-storage descriptors. It retains unsigned storage parameters, unknown mode values, declared section extents and encryption-key identifiers. It checks descriptor and declared auxiliary geometry, per-field size accounting for known modes, relationship-size framing and supported sparse offset-map/ID ordering against the pinned DBCD reference. The supplied total decoded length is a caller declaration; this parser does not authenticate it.

Twenty-four DB2 tests (13 new metadata tests and 11 existing header tests), independent reference review and 100,000 deterministic mutated prefixes passed. Unknown modes and unsupported parameters remain capability facts; a nonzero encryption-key identifier is a declaration, not proof that a section is readable or unreadable. Auxiliary values, encrypted ID lists, section disjointness, bodies, schema meaning and record decoding remain unvalidated. Actual section/storage evidence is a separate bounded diagnostic (#57), rather than inferred from synthetic tests or the 50-section header.

## Supported numeric primitives and fixed-section identities (#58–#60)

`RecordBitFields` reads explicit record slices in little-endian bit order with widths 1–64, preserving raw unsigned bits and separate signed interpretation without neighboring-record reads or implicit padding. `FieldValueDecoder` supports the observed immediate, signed-immediate and scalar pallet modes only. It validates duplicate offset/width declarations, zero supported union flags, exact per-field pallet boundaries and indices; unsupported modes remain explicit. Pallet uint32 values are raw physical values until an exact schema supplies interpretation.

`FixedSectionIndexParser` accepts an exact bounded fixed-width, noninline-ID, zero-key section span and preserves uint32 IDs and ordered raw copy target/source pairs. It retains no record bytes and does not repair zero IDs, choose duplicate targets, resolve forward/cross-section copies or infer row absence. Sparse, keyed and unsupported relationship variants remain separate work. The caller must authenticate supplied bytes; these pure readers do not establish source or schema validity.

Independent review passed the four bit-reader tests plus 100,000 oracle cases, seven numeric-mode tests plus 200,000 oracle cases, and eleven fixed-section tests plus 10,000 valid/100,000 malformed fixtures. These are decoding foundations, not an implemented item-table browser or complete supported cohort.

## Sparse section suffix metadata (#63)

`SparseSectionIndexParser` reads an explicitly supplied bounded zero-key sparse/noninline section suffix in the pinned physical order: ID list, raw copy pairs, six-byte offset/size entries and map-ID list. It preserves unsigned offsets, sizes, zero entries and differing ID lists without repairs or row selection. Exact suffix geometry, limits, cancellation and unsupported variants are checked; record data is not retained.

The result explicitly marks raw offset origin unverified. The pinned reader consumes sizes cumulatively, while independent writer evidence and actual source coherence need separate validation before converting raw offsets into row coordinates. Nine tests and independent 10,000 suffix fixtures/100,000 malformed inputs passed. This is index metadata, not sparse-string or item-name decoding.
