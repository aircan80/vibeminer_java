# Complete selected-manifest inventory formats

Milestone 5 / [#101](https://github.com/aircan80/vibeminer/issues/101) research and pure-parser implementation proposal, 2026-10-18. No new parser is implemented by this document. It defines reusable enumeration needed for real background build retention, not an expansion of the selected Item lookup or an installation reader.

## Existing capabilities and missing work

`format-wow` has full in-memory `WowRootParser` and bounded streaming **selected-ID** `SelectedRootLookup`. The latter deliberately discards unrelated memberships and cannot provide full inventory. `protocol-tact` has bounded in-memory IN-v1 and full EN-v1 parsers, plus authenticated indexed EN-page parsing. It has **no DL parser**. The in-memory EN/root models retain whole collections, so they are not the large-inventory composition proposed here.

New pure readers consume caller-owned decoded streams and emit provisional bounded records. They do no HTTP, file/store/SQL publication, BLTE verification or application scheduling. A returned structural completion means complete supported grammar/count/EOF validation only. The caller still authenticates the original source and decoded stream before publishing a complete inventory. Sink exceptions, I/O failure, cancellation or late malformed input produce no completion; previously emitted rows remain provisional.

## Primary references

The supported ROOT subset follows the already reviewed [TACTSharp root reader](https://github.com/wowdev/TACTSharp/blob/67fd53ac852ca297e9bf95bb41d1a97da202eba4/TACTSharp/RootInstance.cs) and [CascLib WoW root reader](https://github.com/ladislav-zezula/CascLib/blob/38a34665624b8775bb875274b36191b21c38d97b/src/CascRootFile_WoW.cpp), with accepted VibeMiner version-2 source evidence described in [formats](FORMATS.md).

DL evidence is pinned TACT.Net **`57618c47dc0cfef1bfc0e7585bf335ced7218389`**, especially [DownloadHeader](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Download/DownloadHeader.cs), [DownloadFileEntry](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Download/DownloadFileEntry.cs), [DownloadFile](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Download/DownloadFile.cs), and [TagEntry](https://github.com/wowdev/TACT.Net/blob/57618c47dc0cfef1bfc0e7585bf335ced7218389/TACT.Net/SystemFiles/Tags/TagEntry.cs), inspected as original source files. The independent [CascLib download header/entry reader](https://github.com/ladislav-zezula/CascLib/blob/38a34665624b8775bb875274b36191b21c38d97b/src/CascOpenStorage.cpp) and [on-disk structures](https://github.com/ladislav-zezula/CascLib/blob/38a34665624b8775bb875274b36191b21c38d97b/src/CascStructs.h) provide the matching framing reference. Moving-master copies were consulted through web research; immutable pins control implementation review. TACT.Net is GPL-3.0 reference material: implement from documented format facts and independent fixtures, not copied implementation text. Existing notices and license review still apply to any adopted source.

## Supported ROOT grammar and full enumeration

Retain the currently explicit TSFM subset: little-endian header, version 1 with header size 20 or 24, or version 2 with header size 24; supported 24-byte headers require the existing zero extension. Unknown signature/version/extension is UNSUPPORTED, not a guessed legacy fallback. Header total/named counts are memberships, not unique FileDataIDs.

A version-1 block has count, content flags and locale flags. Version 2 has count, locale flags, raw first/second flag words and raw third byte (17 bytes). Preserve all raw components and the existing derived OR; only NoNames controls name-hash array framing. Unknown metadata bits do not cause filtering or invented semantics. Each block contains all unsigned32 FDID deltas, then 16-byte CKeys, then unsigned64 name hashes when named. Delta accumulation must reject overflow, preserve increasing IDs within a block and allow the same ID across variant blocks. Validate aggregate total/named counts and complete EOF.

**Proposed minimal API:** `FullRootInventory.read(InputStream, Limits, EntrySink, cancelled) -> Completion`. Emit block/row ordinals, FDID, CKey, locale/raw flags and optional name hash. Retain duplicate memberships as source facts; distinct-ID/CKey counts belong to a bounded persistent consumer, not this parser.

Columnar block framing requires either staging or a bounded block buffer before emitting complete entries. Prefer a simple checked `long[]` FDID buffer plus packed CKey bytes, approximately 24 bytes per entry, then emit while reading name hashes. Check `count * 24`, array-index range and `maxBlockWorkingBytes` **before allocation**. This is bounded block materialization, not constant-memory or whole-root materialization. If an actual block exceeds the admitted bound, return LIMIT; never skip it and report complete. No list of all entries or hex-string objects is retained by the reader. A later seekable/staged strategy is separate scope if actual geometry demands it.

## IN grammar and enumeration

Existing admitted IN-v1: `IN`, version 1, 16-byte CKey width, big-endian unsigned16 tag count and unsigned32 entry count. Tags precede entries: bounded NUL-terminated UTF-8 name, unsigned16 raw tag type, and `ceil(entryCount/8)` membership bytes. Entries contain bounded UTF-8 name, CKey and unsigned32 **decoded** size. Names are metadata, never filesystem destinations. Tags/platform/locale meanings are not inferred merely from text.

The existing bounded parser can remain the first adapter for a small admitted IN source. For larger manifests, a streaming equivalent can emit each bounded tag and entry with original ordinal, preserving the same grammar/EOF behavior. It needs byte/entry/tag/name/per-bitmap/aggregate-tag-work bounds; avoid retaining every tag bitmap and entry merely to enumerate all entries. Initial full scope includes all IN entries, so no tag filtering is required. IN entry count is not whole-game inventory.

## DL grammar and streaming reader

Propose DL versions **1, 2 and 3**, restricted to complete 16-byte EKeys. Unknown versions/key widths are UNSUPPORTED. Big-endian multibyte fields:

| Header | Framing |
| --- | --- |
| Common, 11 bytes | `DL`, version byte, EKey-width byte, checksum-present byte, unsigned32 entry count, unsigned16 tag count |
| v2, 12 bytes | Common header plus flag-byte width |
| v3, 16 bytes | v2 plus raw signed8 base-priority byte and three unknown bytes |

Each entry: EKey, unsigned40 **encoded** byte length, raw priority byte, optional unsigned32 checksum, and v2+ flag bytes. Preserve raw priority bits and explicit signed8 view; preserve base priority separately. TACT.Net subtracts base priority using a signed-byte representation, while CascLib retains raw priority. The inventory reader must not silently wrap that subtraction or turn it into foreground/background scheduler policy. Preserve the raw checksum-present byte while using its nonzero value to determine framing, as the references do. Checksum algorithm and flag semantics remain opaque; parsing them does not verify a payload checksum. Preserve the three unknown v3 bytes rather than requiring guessed zero values.

Entries precede tags. Tag framing is NUL-terminated name, unsigned16 raw type/value and a bitmap of `ceil(entryCount/8)` bytes, with MSB-first entry membership. A narrow reader must consume exact declared bitmap bytes rather than reproduce permissive truncation recovery from another client. Unused padding bits are not additional entries; do not invent protocol constraints on their value without evidence. Strict UTF-8/name bounds are VibeMiner's admitted interpretation. Preserve duplicate entry/tag ordinals; do not let a map silently overwrite repeated EKeys and change bitmap alignment.

**Proposed minimal API:** `DownloadInventoryReader.read(InputStream, Limits, Consumer, cancelled) -> Completion`, with consumer callbacks for immutable header, each `(ordinal, entry)` and each `(ordinal, tag)` including its bounded bitmap. Callbacks are provisional through tag parsing and final EOF. One entry and one tag bitmap are retained at a time; the consumer must batch/stage rather than accumulate the full file. Returned completion contains scalar counts/consumed bytes only.

Limits cover total decoded bytes, entries, tags, name bytes, per-bitmap bytes, aggregate bitmap/name work and flag bytes. Check header-derived minimum bytes with overflow-safe arithmetic before allocations. Keep priority/flags/checksum fields out of byte-total arithmetic except their declared framing widths. Reject trailing data for this supported subset. `DS` download-size files are a different grammar and are not accepted as DL.

## EN resolution and source identity

ROOT/IN give **CKeys**; DL gives **EKeys**. EN-v1 content pages provide CKey → decoded length + ordered EKey candidates. EN encoding pages provide EKey → encoded length + ESpec index, not the reverse CKey association itself. Bulk resolution needs persisted candidate memberships and a reverse join for DL, preserving conflicts and all candidates.

Reuse `IndexedEncodingParser` page/index validation instead of collecting all entries into `EncodingManifest`. A bounded full enumeration adapter must walk every declared page, verify index/page MD5 and first-key binding, reject cross-page duplicates/order violations, and consume supported ESpec/trailer framing. Retain at most bounded index metadata and one page/record batch. Source authentication and final decoded EN proof are separate from page-local structure. Current EN-v1 support uses full 16-byte keys and the already reviewed one-byte candidate count plus unsigned40 decoded size; do not substitute an older guessed count/size split.

## ROOT + IN + DL selected closure

Stage raw membership identity as exact manifest source plus family and original block/entry ordinal. Preserve each reference even when many rows share a CKey/EKey. Required configuration/root/encoding/install/download dependencies retain their own provenance and verified source roles. EN rows not referenced by the selected manifests are resolution metadata, **not an instruction to download every EN object**.

A real all-manifest inventory includes all supported ROOT variants and IN/DL entries for the exact pinned context. Initial all-variant enumeration makes no platform/locale/optional-tag exclusion. The future locale selector in #114 is outside this increment. Any later filtered scope needs validated tag/flag selection, explicit requested policy and auditable excluded memberships; unsupported selection cannot silently become “all.” A published required manifest that is missing, unreadable or unsupported blocks complete inventory. An absent optional manifest needs a proven NOT_PUBLISHED state, not a transport-failure shortcut.

Content retention follows the existing one canonical encoded representation per `(game namespace, CKey)` policy. A DL expectation for EKey B cannot be marked exact-B verified just because the same CKey retains A. Preserve **logical CKey coverage** separately from **exact advertised encoded expectation**. Reusing A needs the reviewed current candidate/proof policy. If full reverse EN membership leaves a DL EKey unmapped or ambiguously associated, keep it unresolved: never manufacture a CKey or silently insert it into the existing CKey-slot API. A direct-EKey retention role, if required, needs a separate narrow architecture decision before implementation.

Consequently “full retained” requires complete supported inventory EOF, authenticated mappings, declared scope and verified chosen physical sources, with unresolved expectations counted explicitly. It does not imply installability, readable encrypted payloads, complete decoded CKeys, server data, patch application or support for other root families. Required resolver metadata must be decoded sufficiently to enumerate; an opaque payload can still have complete encoded retention proof under the existing verifier. Other advertised families such as DS/patch/VFS require separate admission if needed for the chosen product/scope.

## TDD and adversarial acceptance

| Area | Meaningful deterministic cases |
| --- | --- |
| ROOT | v1/v2 named/NoNames blocks; repeated FDID across variants; unknown raw flag bits; ID delta overflow; header extension/version rejection; block-working-memory refusal before allocation; truncated IDs/CKeys/name hashes; total/named mismatch; sink late failure/cancellation; complete EOF |
| IN | zero/nonzero entries/tags; MSB bitmap boundaries; strict UTF-8 and unterminated names; declared counts/truncation; decoded-size unsigned boundary; bitmap/name/work limits; trailing bytes; callbacks never final on error |
| DL | all three header versions; checksum present/absent; zero/multiple flag bytes; unsigned40 size above32 bits; negative raw/base priorities without wrapping interpretation; nonzero unknown v3 bytes preserved; repeated EKeys and tag ordinal alignment; final tag truncation/invalid UTF-8; unsupported key widths/DS/version; count/size overflow; EOF/cancel/sink failure |
| EN/closure | candidates shared across ROOT+IN+DL; CKey/EKey size conflict; DL unmapped/multiple-CKey mapping; canonical A versus advertised B distinction; missing required manifest; not-published optional role; shared physical CAS object counted once; all EN pages not all payloads |
| Streaming behavior | fragmented and zero-bulk-read streams; bounded maximum read/allocation requests; cancellation during arrays/bitmap and final EOF; no stream ownership/close; provisional callbacks with no completion after late failure; authored million-entry generation without a whole manifest row collection |

Recommended first pure increment after review: streaming full ROOT enumeration with meaningful TDD, then bounded DL enumeration and IN streaming parity as needed. Reuse existing EN page primitives; bulk resolver/persistent closure belongs to a later application/infrastructure slice. No parser fixture depends on live data or private files, and no broad compatibility claim follows from synthetic tests alone.
