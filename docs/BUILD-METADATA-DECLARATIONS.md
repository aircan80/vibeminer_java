# Build metadata declaration policy

Research/proposed adapter contract for milestone 5 / [#100](https://github.com/aircan80/vibeminer/issues/100), 2026-10-18. This classifies declared metadata for the admitted WoW ROOT-mode path. It does not implement adapters, infer every future build property, or expand support to TVFS/other games. Required metadata must be completely verified and retained before the build home opens; only payload inventory/index work may remain preparing afterward.

## Existing parser and adapter boundaries

`TactConfigParser` verifies MD5 over exact original config bytes, strictly parses bounded UTF-8 assignments and preserves unknown keys/tokens. It does **not** make every hash-looking property an object reference. `TactConfig` currently exposes typed EN and IN bootstrap helpers only: CKey, EKey, decoded size and encoded size. Its direct-IN helper correctly refuses a valid CKey-only declaration until EN resolution is available. Existing root acquisition separately admits one root CKey and resolves it through authenticated EN pages.

The small install adapter caps encoded/decoded input at 4 MiB/16 MiB. It is not an EN acquisition adapter. The selected-root adapter reads authenticated EN windows; those windows are not complete retained EN source proof. A new complete EN acquisition/verification composition is necessary, using the config's direct bootstrap pair and bounded streaming decoding/parsing, not a bypass of install limits or a whole decoded array.

## Primary evidence

- [TACTSharp Build.cs](https://github.com/wowdev/TACTSharp/blob/67fd53ac852ca297e9bf95bb41d1a97da202eba4/TACTSharp/Build.cs): EN direct EKey/sizes; ROOT CKey lookup through EN; ROOT versus TVFS resolver selection; IN through EN.
- [TACT.Net ConfigDataFactory](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/Configs/ConfigDataFactory.cs) and [KeyValueConfig](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/Configs/KeyValueConfig.cs): named build/CDN property families and preserved token collections.
- [DL header](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Download/DownloadHeader.cs), [DL entry](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Download/DownloadFileEntry.cs), [DS header](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Download/DownloadSizeHeader.cs), [DS entry](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Download/DownloadSizeFileEntry.cs): DL and DS are separate encoded-size/tag metadata formats.
- [PatchFile](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Patch/PatchFile.cs) and [PatchHeader](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Patch/PatchHeader.cs): a separate incremental patch path, not direct full-object acquisition.
- [CascLib config reader](https://github.com/ladislav-zezula/CascLib/blob/38a34665624b8775bb875274b36191b21c38d97b/src/CascFiles.cpp): CKey/EKey versus patch EKey declarations. Web research checked its current implementation; immutable pins remain the review targets.

These are format/implementation references, not license permission to copy implementation text. TACT.Net's GPL-3.0 reference status and other notices remain separate from an independently implemented documented grammar.

## Concrete recorded declaration evidence

The previously authenticated `wow` build configuration `88d5e8ef05ec5a8446cdec93d142d7c6` for `12.1.5.70448` has raw SHA-256 `f80e4cb45b64a3e00f94db5b6eafc45dfdd03b0734282c1369bcff9443062ba1`. Its original acquisition is recorded in [protocol evidence](PROTOCOL-EVIDENCE.md). No new CDN request was made for this review.

| Property | Recorded declaration | Meaning / limit |
| --- | --- | --- |
| root | One CKey | Actual encoded source/length must be resolved through EN or admitted verified metadata reuse |
| encoding / encoding-size | CKey+EKey; 200,255,766 decoded / 188,062,372 encoded bytes | Direct EN bootstrap; full-source verification still required |
| install / install-size | CKey+EKey; 24,078 / 23,106 bytes | IN source, not all game files |
| download / download-size | CKey+EKey; 74,562,380 / 63,424,635 bytes | DL source; **download-size is a pair of sizes, not the DS object** |
| size / size-size | CKey+EKey; 48,627,732 / 40,971,067 bytes | Separate DS file-size/tag source; not a physical whole-game byte total |
| patch / patch-size | Single hash; 1,771,869 bytes | Patch/update declaration; do not invent a decoded CKey for the single hash |
| patch-index / patch-index-size | Two hashes; 1,679,874 / 1,497,396 bytes | Advertised patch-family metadata; pair semantics beyond the reviewed role remain unimplemented |
| vfs-root / vfs-root-size | Two hashes; 55,528 / 34,991 bytes | Alternative VFS resolver declarations |
| vfs-1 through vfs-868 | Numbered key and size pairs | Present **alongside valid ROOT**, not automatic requirements to fetch 868 VFS objects |

The source also has build-name/uid/product, client-version, feature-placeholder and build-partial-priority values. These are not automatically additional manifest downloads. In particular, a product display label or `Retail` substring cannot select parser support.

## Proposed admitted classification: WoW ROOT profile v1

This is an explicit application capability policy, not a claim that the protocol has a universal required-field list.

| Family | Classification for this profile | Behavior |
| --- | --- | --- |
| Captured versions/CDNs, pinned build/CDN configuration | Required provenance/configuration | Retain exact verified documents, routes and original identities; no latest substitution |
| encoding + encoding-size | Required bootstrap/resolver | Require complete CKey/EKey pair and both lengths; acquire/reuse one actual representation, verify encoded source, full decoded CKey/length and EN-v1 grammar |
| root | Required selected resolver | Initially admit the existing one nonzero CKey form; resolve through EN. Actual supported TSFM grammar required; other forms need explicit admission |
| install | Required IN source in this profile | Pair+size direct acquisition or valid CKey-only EN resolution; complete decoded IN-v1 validation |
| download | Required **when declared** | Complete supported DL source retained/verified. Missing field may be NOT_PUBLISHED only when the verified profile permits absence and no orphan companion exists |
| size / size-size | Required DS **when declared** | Retain/verify the complete DS source and admitted grammar. Preserve short EKey prefixes; do not claim they are complete identities or unique payload mappings |
| patch, patch-index and companion fields | Advertised update/delta family, outside direct full-object closure | Preserve identities as declared, without automatic patch application or patch archive downloads; not NOT_PUBLISHED |
| vfs-root / numbered vfs declarations | Advertised alternative resolver while valid ROOT is selected | Preserve references; no VFS acquisition merely because properties exist. ROOT absent/zero with VFS needed is UNSUPPORTED_ROOT_FAMILY until TVFS admission |
| Recognized build/order/client properties | Informational/opaque metadata | Preserve original tokens and provenance; never treat priority hints as mandatory content or a scheduler override |
| Other ordinary properties | Unknown informational metadata | Preserve visibly in source details without automatic network, hash interpretation or blanket rejection |
| Unclassified potential reference/family | Unsupported declaration requiring policy review | Do not guess source identity or silently declare complete required closure |

The last category is a conservative profile-admission guard, **not protocol inference**. Unknown properties with explicit manifest/root/index-family structure, an unexplained matching size companion, or standalone complete hash-shaped reference tokens need a reviewed classification. Report the exact offending key/tokens and policy revision. That does not assert that every such value is a required object; it prevents unknown reference-like declarations from silently disappearing. Harmless unknown textual/boolean values remain accepted. Future protocol families cannot be discovered reliably by suffix heuristics alone; broad support requires deliberately reviewed profile revisions.

A present but malformed/empty/orphaned declaration is not “not published.” A fetch failure, 404, unsupported grammar, unknown key or exhausted budget is not absence. Required EN/ROOT/IN missing or unsupported blocks this profile. Declared DL or DS missing/unsupported also blocks it; permitted absence must come from the authenticated declaration policy. A new root family is not an invitation to fall back to the old selected Item fixture or a current pointer.

The VFS alternative and patch-family exclusions require explicit acceptance as resolver/acquisition-mode policy before adapters are committed. DS is not excluded merely because EN/DL also contain lengths: primary implementation evidence identifies it as file-size/tag metadata, not an update-only artifact. The home describes the admitted required-manifest closure, not an installable client.

## Minimal admitted DS reader

The pinned [DownloadSizeFile reader/writer](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Download/DownloadSizeFile.cs) reads tags before entries and writes its source CKey/EKey plus lengths back to `size`/`size-size`. Its description and data model cover encoded file sizes and download-condition tags; they do not establish that DS is update-only. No live DS body was acquired in this research.

Propose a pure streaming DS-v1 subset: 15-byte header (`DS`, version, key width, big-endian unsigned32 entry count, unsigned16 tag count, unsigned40 declared total). Admit width 9 (the reference default) and width 16 explicitly; other widths are UNSUPPORTED until reviewed. Tags precede entries and use the existing bounded UTF-8 name/type/MSB-first bitmap framing. Each entry has the declared-width key bytes and unsigned32 encoded length. Preserve original ordinals, duplicate prefixes, tag values and bitmap padding without invented filtering. Validate complete framing/EOF and checked aggregate size accounting; check the observed sum against the declared total as an admitted-v1 consistency rule grounded in the pinned writer, preserving both values in completion. Deterministic fixtures must exercise mismatch and duplicate-prefix accounting; actual source compatibility remains unclaimed until separately verified.

A nine-byte row value is an EKey **prefix**, not a full 16-byte identity. It cannot be padded with zeroes and treated as an authenticated full EKey, merged into CKey storage slots, or used as proof of uniqueness. The DS manifest source itself still has a full CKey/EKey declaration and full verification requirements. Prefix resolution/collision diagnostics can follow separately without blocking honest retention of its metadata.

Like DL enumeration, the reader needs only one entry and one bounded tag bitmap at a time, provisional callbacks and final structural completion. Bound decoded bytes, entries, tags, name bytes, bitmap bytes/work and cumulative unsigned size arithmetic before allocation. Existing complete-source acquisition must stream its 49 MB-class decoded data; do not materialize it under the install adapter's limits. Missing/unsupported/invalid DS remains a required-role failure when published, not an informational warning.

## Acquisition order, proof and costs

1. Revalidate captured source context and pinned raw-MD5 configurations. Classify declarations with a versioned policy before issuing manifest transfers.
2. Acquire/reuse EN from its directly declared EKey/lengths. Stream encoded verification into the one CAS object; authenticate decoded stream, validate full EN grammar and establish full CKey proof without a persistent decoded duplicate.
3. Resolve ROOT CKey and any CKey-only IN/DL/DS declarations using authenticated EN candidate membership and lengths. Keep all candidates and conflicts; choose according to existing canonical representation policy, not first-row convenience.
4. Acquire/reuse required ROOT/IN/declared DL/DS sources. Require actual complete-source proofs and admitted grammars before home readiness. Payload membership persistence may finish afterward; a header/range proof alone does not satisfy retained metadata completeness.
5. Keep declared source expectation, actual retained representation and original receipt separate. Existing admitted strong metadata reuse can reuse proven identical decoded content under its reviewed policy; it must not manufacture current EN membership or rewrite declared EKeys.

DL entry EKeys require a reverse join over EN **content-candidate memberships** to associate CKeys; the EN encoding table provides sizes/specification but does not itself supply that reverse CKey mapping. Unmapped/ambiguous DL entries remain explicit inventory states, not invented CKey slots. This later closure work does not change which metadata source bytes must already be retained.

The recorded direct EN+IN+DL+DS encoded declarations total **292,481,180 bytes**, before ROOT, configuration, index/range overhead and retries. A separately resolved recorded ROOT representation adds 50,651,441 bytes, giving **343,132,621 bytes** for those five encoded sources; their declared decoded lengths total **390,762,451 bytes**. These are this exact observation's metadata-source quantities, **not whole-game size, universal defaults or fresh availability checks**. ROOT's size is not known from this build-config root property alone. Shared retained sources may reduce additional wire bytes; actual response-body accounting includes bounded locator/index overhead.

Use explicit per-source encoded/decoded/chunk/parser limits plus aggregate metadata wire, decoded-work, request and deadline budgets across the preparation job. Select ceilings that admit the observed approximately 200 MB decoded EN and 75 MB decoded DL without pretending install's 16 MiB cap applies. Unknown lengths block an unbounded transfer until independently resolved or a reviewed capped acquisition policy exists. Exceeding a limit is typed BUDGET_EXCEEDED; it never weakens the home gate or silently skips a source.

An archive index is required only when needed to authenticate a locator for a required source. The entire CDN archive-index set is not intrinsically mandatory Open metadata. Persist original locator proof and actual range accounting; do not retain duplicate permanent partial-range and complete-object copies. CDN config index-size advertisements remain advisory where the existing authenticated footer evidence differs.

## Adapter acceptance before implementation

- Authored configuration matrix: current recorded-shaped profile; harmless unknown boolean/text; unknown reference-like declaration; direct and CKey-only IN/DL/DS; malformed lengths/keys/orphan sizes; absent permitted DL versus failed DL; valid ROOT plus many VFS entries; absent ROOT requiring unsupported TVFS; declared DS required and patch classified explicitly.
- Exact configuration/provenance/canonical role identities remain unchanged across restart and metadata reuse. Complete required metadata means actual full source/decoded verification plus supported grammar, never declaration-only readiness.
- Large authored EN/DL streams obey bounded memory/work and use no install limit bypass or full decoded table copy. Missing/corrupt/unsupported/canceled roles leave progress/error outside home until all required roles pass.
- No payload acquisition, delta application, all-index sweep, locale selector, game installation or cache import is hidden in the declaration mapper. All-declared-variant payload inventory remains a separate #101/#102 composition.

This policy complements [build opening](BUILD-OPENING.md), [inventory formats](BUILD-INVENTORY-FORMATS.md) and [metadata reuse](METADATA-DEDUP.md). It is deliberately small enough for a declaration classifier plus existing source adapters/new EN-DL-DS adapters, rather than a new generic product-definition framework.
