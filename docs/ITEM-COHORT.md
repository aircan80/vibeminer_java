# Exact Item research cohort and coverage

This specification records the evidence accepted in [#28](https://github.com/aircan80/vibeminer/issues/28) and defines the next bounded production scope under [#20](https://github.com/aircan80/vibeminer/issues/20) and [#21](https://github.com/aircan80/vibeminer/issues/21). A diagnostic projection is not an implemented searchable table or a claim that all sections are readable.

## Pinned source and interpretation

The observed cohort is official product code `wow`, US, exact build `12.1.5.70448`, BuildConfig `88d5e8ef05ec5a8446cdec93d142d7c6` and CDNConfig `f065eade5276a5ac05606e60a4c5a1f7`. Requested locale is explicitly enUS, root locale bit `0x2` within the selected ItemSparse membership mask `0x202`. Raw DB2 locale values are separate metadata. This does not establish a marketing-name binding for another product.

| Table | FileDataID | Declared CKey | Actual table/layout | Observed metadata |
| --- | --- | --- | --- | --- |
| Item | 841626 | `1df6b6dbae36878cea020a62b15b9bd6` | `50238EC2` / `996192AA` | WDC5; 50 sections; 15 storage descriptors: 12 PALLET, 1 IMMEDIATE, 2 SIGNED_IMMEDIATE; 49 nonzero section-key declarations |
| ItemSparse | 1572924 | `bb0ecbdc2fd4988ddd3c1c5e95c34165` | `919BE54E` / `1C17D17F` | WDC5; 49 sections; 67 raw NONE storage descriptors; 48 nonzero section-key declarations; noninline sparse IDs |

A key declaration alone proves neither readability nor unreadability. [Item metadata](evidence/item-section-metadata-12.1.5.70448.json) and [ItemSparse metadata](evidence/item-sparse-section-metadata-12.1.5.70448-enUS.json) retain actual geometry and authenticated range receipts. [#45](https://github.com/aircan80/vibeminer/issues/45), [#57](https://github.com/aircan80/vibeminer/issues/57) and [#61](https://github.com/aircan80/vibeminer/issues/61) record the completed diagnostic scopes.

Schema candidates are attributed to **WoWDBDefs contributors**, pinned revision `b9a63096cdd99b26967f17f9515377ae62cadfd7`: [Item definition](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/Item.dbd), [ItemSparse definition](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/ItemSparse.dbd), and [upstream license](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/LICENSE.md). Definition data is CC BY-SA 4.0, distinct from the designated code's BSD license. The [#64](https://github.com/aircan80/vibeminer/issues/64) bundled provider preserves unmodified resources, SHA-256 manifests and attribution. A compatible candidate is not Blizzard-authored semantics or validation of every field.

## Evidence and capability matrix

| Capability | Established evidence | Remaining boundary |
| --- | --- | --- |
| Exact source membership | Prior complete-root CKey and selected EN/archive mappings, explicitly attributed in subsequent diagnostics | Later diagnostics do not silently refresh prior proofs; production acquisition must replay its exact retained source context |
| Header/layout compatibility | Actual Item/ItemSparse prefixes match the pinned candidate table/layout cohort | Prefix authentication is not complete table CKey verification |
| Item zero-key section | Actual descriptors and the selected diagnostic's consumed complete section/chunks; 59,859 physical records and 153,925 copy declarations | General row enumeration, all copies and other sections have not been accepted as a production research dataset |
| Sparse zero-key index | [#65](https://github.com/aircan80/vibeminer/issues/65): 175,503 physical IDs/maps agree, absolute record coordinates are coherent and 122 direct physical-source copies are declared | Coordinates are validated for this exact section, not repaired or generalized to every sparse file |
| Two-record projection | [#68](https://github.com/aircan80/vibeminer/issues/68): IDs 25 and 19019 joined as physical records in both tables; four Item integer candidates and the fifth ordered Sparse localized-string candidate | No live copy was required; no general name search, trailing Sparse interpretation or arbitrary row/schema coverage |
| Synthetic error behavior | Independent fixtures cover copy/absence handling, bounded strings, malformed UTF-8, schema/source mismatch, corruption, budgets and cancellation; unresolved copy/orphan outcomes stay explicit | Synthetic copies/orphans/UTF-8 cases do not establish live keyed-section or general copy-chain coverage |
| Whole decoded source | Neither table's complete decoded CKey was established by #68 | Record complete decoded verification separately from selected-section/chunk authentication |
| Other builds/locales | No second-build comparison or other-locale projection accepted by this cohort | No latest-schema, same-ID cross-edition or locale substitution |
| Production research | Pure primitives, pinned provider and bounded diagnostic composition exist | Application decoding, shared derived projection, paging, search and UI remain follow-on acceptance |

The [two-ID evidence](evidence/item-row-cohort-12.1.5.70448-enUS.json) records actual proof origins, candidate attribution and 25 requests / 1,901,098 response bytes / 4,632,477 decoded-work bytes. It publishes IDs, hashes and coverage rather than actual names or projected integer contents. [The diagnostic contract](../tools/protocol/ITEM-ROW-COHORT.md) specifies reproducible bounded execution and synthetic checks; [recorded protocol evidence](PROTOCOL-EVIDENCE.md#exact-two-id-candidate-row-projection--2026-10-18-68) distinguishes consumed proof from unverified whole tables.

## Compatibility and incomplete outcomes

| Condition | Required behavior |
| --- | --- |
| Matching exact build/table/layout/provider, admitted section and explicit locale | Project only supported fields, retaining raw values and candidate attribution |
| Missing or mismatched layout/schema/build selector | MISSING, AMBIGUOUS or INCOMPATIBLE; retain inspectable identity/raw metadata without guessed named fields |
| Copy with a unique validated direct physical source | Preserve requested and physical-source IDs and copy provenance; otherwise scoped unsupported/orphan, never invented data |
| Missing name or unavailable locale/related record | Show stable ID plus precise unavailable/unindexed/unsupported reason; never borrow another locale/build |
| Unsupported/unreadable/unindexed section | Exclude from admitted row scope and expose coverage; no whole-table absence or removal inference |
| Same numeric IDs in another edition | No join or semantic comparison without independently validated mappings |
| Later baseline/target comparison | Independently verify both exact contexts, supported fields and locale; match stable record semantics and compatible interpretation revisions; changed layouts require an explicit validated mapping |
| Incomplete compared scopes | Report indeterminate coverage; added/removed requires complete relevant scope on both sides |

A second actual build is a later comparison acceptance gate, not an invented prerequisite for the first exact-cohort investigation. Client records do not prove obtainability, release dates, drops or server behavior.

## Smallest next production slice

After [#71](https://github.com/aircan80/vibeminer/issues/71), [#72](https://github.com/aircan80/vibeminer/issues/72) and [#73](https://github.com/aircan80/vibeminer/issues/73) provide retained selected encoded files, refine existing #20 to one **Item zero-key-section projection**. Avoid making general name search or complete ItemSparse interpretation part of that first slice.

1. Read the exact retained complete encoded object with its actual verification receipt and root/EN association. Recheck header/schema compatibility and bounded source/section geometry.
2. Authenticate and decode the supported section's required chunks. If the complete decoded stream is readable, verify exact decoded length and full CKey. If other chunks or sections remain opaque/unsupported, retain an explicitly partial decoded proof; never label the whole decoded source verified. Complete encoded proof, selected decoded-chunk proof, declared CKey association and complete decoded-CKey verification are distinct fields.
3. Produce bounded raw ID/field projections and the four observed, attributed Item integer candidates for the admitted section. Stage shared derived rows, keyed by source, decoder/schema/provider revision, locale/options and proof coverage. Publish only after the declared scope's checks pass; build membership references the shared artifact. Do not persist duplicate full decoded table copies merely for paging.
4. Expose bounded ID lookup/pages with allowlisted filters/sorts and stable ID tie-breakers. Cursors bind exact snapshot, artifact and query revision. Empty means no match in the stated indexed scope, not absence from the entire build. Show source/proof coverage and candidate attribution in the inspector.
5. Add the supported Sparse name-prefix projection and exact-context ID join under #21 after the Item slice works; disclose missing names/relations and preserve partial coverage.

Tests should exercise late source corruption with no ready artifact, section-proof mismatch, bounded allocations, cancellation/restart, raw signed values, duplicate/copy/orphan IDs, schema/layout/locale mismatch, stable paging, stale UI results, unchanged-source artifact reuse and retained offline zero HTTP. Whole-source corruption invalidates a claim of complete source verification; partial-section readiness must depend on its explicitly authenticated admitted scope, never an unsupported global claim. Independent UI keyboard/scale review follows the application/query contract. No new capability is considered delivered merely by writing this specification.
