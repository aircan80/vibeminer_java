# Official metadata discovery evidence

Observed 2026-10-18. Bounded HTTPS requests to official metadata endpoints succeeded, and captured body lengths/hashes were independently checked. These are observed advertised build pointers, not proof of available or decoded game payloads. No game installation or game-cache input was used.

Source routes: `https://us.version.battle.net/v2/summary`; `https://us.version.battle.net/wow/versions`; `https://us.version.battle.net/wow/cdns`; and equivalent `wowf` routes. The versioned `/v2/products/<product>/versions` and `/cdns` routes were also exercised by the Java adapter.

| Captured document | Bytes | SHA-256 |
| --- | ---: | --- |
| v2 summary | 26472 | `aa3d472bfb76742074c77b69827d7202c0704ed379c0d6fe4de14057e09e7d10` |
| wow versions | 1220 | `88824e3367bc0c446e0c2877990fc6170169b3cd56b9a7754034bb095adad407` |
| wow CDN metadata | 1338 | `b7ef75108cc94acfe46d7ef2101488c2c33155b15a291e1d2b010b021018b540` |
| wowf versions | 630 | `85271248e22aa53ae4f7cc524fe6a5ff973de47beaf81aefed7ce82c2a0f7a83` |
| wowf CDN metadata | 1338 | `9c1daa685a2576ba1ceac5cf8769b727566ad98c469cc0ffe196d0a85e759978` |

The US `wow` row advertises version `12.1.5.70448`, BuildId `70448`, BuildConfig `88d5e8ef05ec5a8446cdec93d142d7c6`, CDNConfig `f065eade5276a5ac05606e60a4c5a1f7`, KeyRing `3ca57fe7319a297346440e4d2a03a0cd`, ProductConfig `53020d32e1a25648c8e1eafd5771935f`.

The US `wowf` row advertises version `1.60.1.70245`, BuildId `70245`, BuildConfig `e8c4ef4e67bd1d94d820254984258fa8`, the same CDNConfig, an empty KeyRing, and ProductConfig `2ced73c17301c297ca062bc7c3583420`. Empty optional KeyRing must not shift later columns. Shared CDNConfig does not mean shared edition, build or record identities. Neither versions response alone verifies readable game payloads or DB2 support.

The fresh summary has an unflagged `wowf` entry, which differs from an older cached observation. This is a concrete reason not to use cached absence as proof of unsupported/unpublished content. The official Forever-to-product-code mapping still needs reviewed binding evidence.

## Minimum exact-selection evidence

Before marking a discovered build selectable as a verified snapshot, preserve its official product code, region, channel, exact advertised version/build identifier, build configuration and CDN configuration references, any provided product configuration, official source location, retrieval/observation time, and captured-document digest. Preserve original field names and unknown fields for forward-compatible parsing; required-field validation must be schema-aware.

A region-wide metadata host is not itself the selected content region; pin the selected row. Locale is an explicit content-view dimension, not necessarily an advertised versions column. A display label is not a primary key. The same version string with different configuration references is a different pinned snapshot. Discovery can establish metadata availability without establishing BLTE/DB2 support or CDN payload availability.

After config acquisition, record verified root/encoding identities and supported variant capability separately. Before actual acquisition, availability is unknown/advertised, not verified readable. Preload and current channels remain separate even if hashes happen to match.

## Deterministic synthetic test cases

The following identifiers are intentionally synthetic and must never populate a live product list as official data.

1. **Flag aggregation:** summary fixture includes `fixture_game` three times with distinct blank/CDN/background flags. One product exposes all three observations; blank is not discarded, order does not change meaning, metadata sequence is not displayed as build number.
2. **CDN-only product:** `fixture_candidate` has only a CDN row. Display raw code and metadata-only capability; do not fabricate versions, imply decode support or redirect to another product.
3. **Unmapped edition:** required display label Forever lacks reviewed binding evidence. UI shows unresolved mapping while generic discovered product codes remain inspectable. No guessed branch binding.
4. **Same display version:** two complete version rows share `0.0.0.fixture` but differ in build/CDN configuration references. Exact identities remain different; persistent workspace never follows a later pointer silently.
5. **Region/channel separation:** identical version strings under two region rows or current/preload sources never collapse. Missing selected-region row produces an explicit missing result rather than another region's build.
6. **Unknown fields:** additional header column is parsed by name and retained. Missing required config field produces invalid/incomplete metadata state. Trailing empty fields are preserved; malformed width cannot shift values into another field.
7. **Conflicting duplicate rows:** same selection key with conflicting configs is ambiguous and requires explicit handling; first/last response order cannot silently choose a build.
8. **Network indeterminate:** transport DNS/timeout/tool failure does not become product-not-found. Cached observed identity can reopen with timestamp and stale/offline label.
9. **Identity versus semantics:** two verified edition snapshots may refer to one shared physical object, but same numeric record IDs do not enable cross-edition semantic joins or comparisons.
10. **Current versus history:** retain previous observed snapshots when current pointer changes. Historical content remains unchecked until acquired; missing objects mark requested scope partial without deleting retained verified evidence.

Parser implementer chooses exact valid fixture grammar from verified header/schema evidence and writes behavioral tests. This document supplies semantics, not guessed binary or versions-file layouts.


## Remaining evidence gates

The official Forever-to-product-code binding, historical acquisition paths, resolver/BLTE modes and item-table schema cohort remain separately reviewed capabilities. A raw code such as `wowf` may be queried without inventing its marketing-name mapping. CI uses synthetic fixtures rather than current upstream values. Actual test/runner results are recorded in GitHub Issues, especially #14 and #27.

## First verified configuration and BLTE object — 2026-10-18

A separate bounded live probe fetched the exact US `wow` build configuration `88d5e8ef05ec5a8446cdec93d142d7c6` (87,836 bytes) and CDN configuration `f065eade5276a5ac05606e60a4c5a1f7` (59,333 bytes). Both full-byte MD5 values matched their pinned keys. The advertised HTTPS host served the reference route `https://us.cdn.blizzard.com/tpr/wow/config/aa/bb/<key>`; this validates that route for these objects, without resolving the distinct advertised ConfigPath convention generally.

The verified build config declared a small install manifest: decoded CKey `bfed494c95462d5e9455b75253946bd9`, EKey `d10563defeec2e7649ecaa7ae2d59a6a`, decoded size 24,078 and encoded size 23,106 bytes. The exact loose object at `https://us.cdn.blizzard.com/tpr/wow/data/d1/05/d10563defeec2e7649ecaa7ae2d59a6a` returned 23,106 bytes under a 64 KiB cap. The Java BLTE decoder verified its expected EKey, chunk hashes, exact decoded length and full decoded CKey; decoded prefix `494e0110` identifies the install-manifest family. Encoded local SHA-256: `08f7490350e63e80345b12d19b3db72d77169b4382b1a119f11562d734d8b268`.

The same config's encoding manifest was 188,062,372 encoded bytes, so it was not downloaded in this probe. No game payload bytes are versioned. This is evidence for one exact object and decoder path; it does not establish full root/FileDataID resolution, DB2 support, archive retention or a completed user-facing download workflow. Ordinary tests remain synthetic and offline.

A subsequent bounded IN v1 parser check consumed that same verified decoded object completely: 274 named metadata entries and 31 tags. This validates that exact install-manifest subset; names are not a complete game-root catalog or filesystem instructions.

## Retained install-scope service check — 2026-10-18

The layered application/gateway path was separately exercised against fresh official `wow`/`us` discovery for `12.1.5.70448`. Configuration inspection first returned the known 23,106-byte encoded / 24,078-byte decoded plan. A separate download verified and retained the exact install object, producing 274 entries and 31 tags with local SHA-256 `08f7490350e63e80345b12d19b3db72d77169b4382b1a119f11562d734d8b268`. Actual acquisition evidence retained the `us.cdn.blizzard.com` URL above.

The temporary store contained exactly two physical objects: configuration and encoded install manifest. After closing and rebuilding the store, catalog and service instances, the saved workspace reopened its verified list. Repeated configuration inspection/download actions reused durable aliases with **zero HTTP calls** under a transport that rejected every request. Temporary game bytes were removed afterward. This was a fresh-service restart check in one JVM; the separate versioned JavaFX harness proves two-process restart using synthetic content, real SQLite/CAS and no live network. Neither check establishes a complete game catalog or Item/DB2 support.


## Authenticated selected-page research — 2026-10-18

For the same exact build, the configuration declares encoding CKey `c36d0a7a0645d12c52932ec8c8f7fea2`, EKey `6e9260e140bd5f5b116d47229e937993`, encoded size 188,062,372 and decoded size 200,255,766. A bounded research probe used exact HTTP 206 ranges against the official data URL. It verified the 180-byte BLTE header against the EKey, then complete EN header/index chunks against their authenticated chunk hashes. The selected 4,096-byte content and encoding pages were verified against hashes in those indexes. The raw data sections were explicitly checked for N framing; this optimization cannot be assumed for compressed sections.

The proof transferred 1,436,495 bytes and resolved configuration-pinned root CKey `e84483ee756c69a7dc5fbff04af81316` to EKey `1051943fc8cb91a3c88066dc946afc3e`, encoded size 50,651,441 and decoded size 67,292,495. This authenticates selected membership pages, **not the complete encoding manifest or its decoded CKey**. The 16 MB encoding-specification section was not interpreted. A production implementation still requires bounded range transport, explicit partial-proof models and adversarial tests; this diagnostic is not an application capability.

A separate root probe verified its 6,180-byte BLTE header against that EKey and its first 204,907-byte encoded chunk against the header's chunk digest. Decoding yielded 262,144 bytes with TSFM header size 24, version 2, total membership count 3,261,808 and named membership count 254,287. Only this prefix was acquired; the complete root CKey was not evaluated, and neither complete membership nor absent-file conclusions follow. Version 2 framing is checked against [pinned TACTSharp RootInstance](https://github.com/wowdev/TACTSharp/blob/67fd53ac852ca297e9bf95bb41d1a97da202eba4/TACTSharp/RootInstance.cs). Counts cover memberships across variants, not distinct file IDs. No game payload is committed.

## First composed item-cohort check — 2026-10-18

The versioned Java diagnostic in `tools/protocol/` passed 129 offline fixture assertions before a separately explicit live run. With unchanged defaults, it verified both pinned configurations, selected EN proof pages and root chunks through index 229 of 257. The streaming root parser then rejected an unenumerated content flag. Costs were 46,606,533 settled wire bytes, 243 requests and 61,729,430 decoded-work bytes. It stopped immediately, saved no game bytes and did not increase limits or substitute a source.

The complete root CKey was **not verified**, and no file memberships or Item/ItemSparse headers were reported. This identifies a parser compatibility restriction, not proof of upstream corruption. Follow-up #47 compares the pinned reference readers: their version-2 framing preserves opaque flag words and uses NoNames for name-hash array presence; an enum-name allowlist is not a framing requirement. Support changes require synthetic regressions, independent review and another bounded exact run before stronger claims.

## Complete root and archive-location investigation — 2026-10-18

After the independently reviewed opaque-flag correction, the same diagnostic verified **all 257 root chunks and complete decoded CKey `e84483ee756c69a7dc5fbff04af81316`**, consuming exactly 67,292,495 decoded bytes. The selected lookup preserved 12 memberships: one for FileDataID 841626 and eleven for FileDataID 1572924. This proves those exact memberships in the supported complete root; it does not decode the tables.

The 841626 target resolved through authenticated EN pages to CKey `1df6b6dbae36878cea020a62b15b9bd6`, EKey `a928b6c9c7ce92d85b638400f81b2fc2`, encoded size 738,344 and decoded size 2,139,665. Its direct official data URL returned 404 on the initial eight-byte request. The diagnostic stopped without a fallback: 52,243,315 completed-response bytes, plus an eight-byte failed-request reservation, 275 request reservations and 68,736,997 decoded-work bytes. No table headers were acquired and no game bytes were saved. A loose-object 404 does not establish file absence; archive-index resolution follows separately.

The exact CDN configuration lists 1,424 archives and the same number of index-size hints, totaling 126,552,712 advertised index bytes. The archive-group artifact is generated client-side in the pinned reference implementation; its hash is not proof of a downloadable server group index. Bounded footer/TOC/page lookup can avoid fetching all complete indexes.

A separate bounded probe of index `0017a402f556fbece46c38dc431a2c9b.index` authenticated its final 28 bytes against that filename MD5 and verified the internal footer checksum. Its count and widths derive 7,060 entries, 42 pages and **173,068 index bytes**, agreeing with the observed Content-Range total. The parallel configuration hint was **135,988**, a discrepancy with no proven cause. Preserve it explicitly; do not reorder keys by matching sizes or weaken identity verification. A dedicated bounded suffix observation can bootstrap footer authentication; subsequent ranges must use authenticated derived geometry. No complete index or game payload was retained by this probe.

## Archived Item header — 2026-10-18 (#51)

The committed bounded diagnostic (`5c89df5`) consumed the prior complete-root/EN identity above as an explicit input; it did not repeat the root scan. It found the Item EKey in the 277th checked archive index, archive `31236b924da7e06973fb5603c537fa7b`, at offset 45,153,828 with length 738,344. The authenticated index geometry was 4,148 bytes, conflicting with the advertised 12,388-byte hint. A bounded HEAD observed 55,261,444 archive bytes; this is not a whole-archive checksum.

The child's 1,428-byte BLTE header passed its EKey check. Only chunk zero was consumed and verified: 2,949 encoded / 7,152 decoded bytes. The actual WDC5 header reports table `50238EC2`, layout `996192AA`, schema version 5, 60,025 records, 15 fields, record size 11 and 50 sections. These table/layout hashes match the pinned Item candidate. Section readability, complete table CKey, schema semantics and rows remain unverified. ItemSparse is still a candidate awaiting its own actual header.

The experiment used 836 requests, 1,342,650 response-body bytes and 7,152 decoded-work bytes in about 8.14 seconds, with no payload writes. [Machine-readable evidence](evidence/item-header-12.1.5.70448.json) preserves the exact source URLs, timestamps, range digests, prior-proof separation and coverage limits. Both protocol diagnostics also pass generated-fixture offline self-tests (129 and 76 assertions); these are separate from live evidence.

## Explicit enUS ItemSparse header — 2026-10-18 (#45)

The separate diagnostic (`da2f593`) preserved the eleven prior complete-root memberships and explicitly selected enUS bit `0x2` within shared enUS/enGB root mask `0x202`: CKey `bb0ecbdc2fd4988ddd3c1c5e95c34165`. It did not rescan root. Fresh authenticated EN pages resolved EKey `cab35eb8601260c12a5917c1bdacfda5`, encoded size 7,988,612 and decoded size 62,715,198. The loose official URL was available, so no archive fallback ran.

The 90,732-byte BLTE header passed EKey authentication and declares 3,780 chunks. Only chunk zero was consumed: 1,950 encoded / 5,252 decoded bytes, with chunk integrity verified. The actual WDC5 header matches table `919BE54E` / layout `1C17D17F`, schema version 5 (`WOWSTATIC_12_1_5_70077`), flags 5, 175,758 records, 67 fields, record size 328 and 49 sections. The raw DB2 locale value 1 is distinct from the root locale mask; no equivalence is inferred.

Cost was 14 requests, 1,676,362 response-body bytes, 1,441,562 decoded-work bytes and about 0.66 seconds. [Machine-readable evidence](evidence/item-sparse-header-12.1.5.70448-enUS.json) records exact source/range observations and prior-proof separation. The 139-assertion injected-source self-test and independent review passed separately. Both candidate table/layout pairs now match actual headers, but neither complete table CKey nor table bodies, schema semantics, section encryption/readability or rows are verified. No payload bytes were written.

## Layered retained-root service — 2026-10-18 (#50)

The production application/gateway/CAS/SQLite path independently retained the exact known root after fresh official discovery. It verified complete decoded CKey and structural EOF, then reopened fresh service/store/catalog instances with forbidden transport. All 12 expected memberships were returned after scanning 3,261,808 memberships; offline HTTP calls were zero. Exactly two physical objects remained: configuration and encoded root, SHA-256 `07ac99930560e33ee36c6def613ac9bf27dd045add11b2a003ba75f799d5b826`. Temporary payloads/catalog were deleted after the check. Cost was 52,178,330 conservative body bytes and 271 requests. [Structured evidence](evidence/layered-root-service-12.1.5.70448.json) preserves exact source receipt and all variants. This does not acquire referenced tables or prove older-build availability.

## Actual Item section/storage declarations — 2026-10-18 (#57)

After one environment-blocked attempt with zero response bytes, a separate three-request run freshly authenticated the known Item child header and first chunk from the prior archive mapping: 4,377 body bytes / 7,152 decoded-work bytes. The bounded metadata inspector accepted 50 section declarations and 15 field-storage descriptors. Forty-nine sections declare nonzero encryption-key identifiers; this does not establish readability. Observed storage kinds are twelve pallet fields, one immediate field and two signed-immediate fields. Metadata descriptors end at byte 2,624; declared auxiliary data ends at 5,924. The first section declares 59,859 physical records and 153,925 copy entries, so copies cannot be ignored when implementing record identity.

[Structured metadata evidence](evidence/item-section-metadata-12.1.5.70448.json) includes the raw declarations and fresh range receipts. The prior index mapping was not reacquired. Complete table CKey, auxiliary values, bodies, schema semantics, row decoding and section readability remain unverified. No game payload was written.

## Actual explicit-enUS ItemSparse declarations — 2026-10-18 (#61)

The known loose target was reauthenticated in exactly two ranges: its 90,732-byte BLTE header and 1,950-byte first chunk, totaling 92,682 response bytes and 5,252 decoded-work bytes. No root/EN refresh, HEAD, archive lookup or additional chunks ran. The WDC5 metadata inspector consumed 4,040 descriptor bytes: 49 sections, 67 fields and 67 storage descriptors, all raw mode 0 (NONE). Forty-eight sections declare distinct nonzero key identifiers, which do not establish readability. Raw DB2 locale 1 remains distinct from the prior root mask `0x202` and explicit enUS bit `0x2`.

The zero-key first section declares 175,503 records at offset 5,252, variable record data ending at 60,166,704, a 702,012-byte ID list, 175,503 offset-map entries and 122 copy entries, with declared section end 62,624,722. This motivates a bounded sparse-index parser; no records or names have been decoded. [Structured evidence](evidence/item-sparse-section-metadata-12.1.5.70448-enUS.json) preserves every declaration and exact range receipts. Full table CKey, bodies/schema semantics and section readability remain unverified. The independent 65-assertion offline suite and 361 tamper cases passed separately. No payload was written.
