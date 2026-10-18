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
