# WoW research home proposal

Design proposal, 2026-10-18. This describes the home reached after **choose edition/build/region → prepare metadata → Start mining**. It does not authorize implementation or promise unsupported table/asset coverage. The active context is the chosen exact build, not a separately created workspace. Reopening that identity returns to its existing research state and shared stored content.

## First visit: a useful place to work

Lead with the selected friendly edition and exact version/build, region, requested research locale, and metadata observation time. Source details expose product code, configuration identities and provenance. A small “Change build” action returns to selection; opening this home never silently follows a newer pointer.

Put a research entry field first: **Find an Item ID or search display candidates**. Explain its actual scope alongside results: exact prepared Item/ItemSparse pair, literal case-sensitive text when available, physical rows only and selected locale. An ID route remains valuable when names are missing. If the pair is not ready, show the precise required sources and an explicit prepare/download action; do not show an empty search as though it queried a complete game database.

Offer these working paths:

| Path | Useful research question | Honest capability boundary |
| --- | --- | --- |
| Items and names | “What is this Item ID?” / “Which admitted names contain this exact text?” | Existing narrow physical Item candidates and paired Display candidates; no reward/source/obtainability inference |
| Files and identifiers | “Which exact object belongs to this FileDataID?” / “Can I retain it?” | Root/EN membership and supported selected-file lookup; paths only when a separately attributed naming source provides them |
| Tables and raw evidence | “Which schema/layout and fields support this finding?” | Show supported tables and pinned definition coverage; do not imply a general DB2 browser exists |
| Recent inspected findings | “Continue the record I was investigating” | Revalidate retained source/readiness; preview a prior selection without claiming it is still ready |
| Export one inspected finding | “Share this exact record and its limitations” | One-record JSON is the separate #91 feature; expose only when delivered, not as a claim that bulk exports exist |
| Compare another build | “What changed?” | A later capability requiring a compatible second exact build and independently adequate coverage; incomplete absence is indeterminate |

Useful future research groups are **collectibles and appearances** (mounts, pets, toys, equipment looks), **spells and class changes**, **quests/NPCs/story text**, **crafting and reagents**, and **world/art/audio**. Each needs its own validated tables, relations or preview format. They can appear as clearly proposed capability cards with a short explanation, not fake totals or enabled universal searches. A client record does not establish release timing, drop location, availability in game, or server-only mechanics.

## Statistics that help researchers

Every statistic needs a scope, source and measurement state. Use **Not measured**, **Requires metadata**, **Unsupported**, or **Partially indexed** rather than inventing zero. Stable retained facts can be cached as attributed observations; do not rerun large root/index scans whenever the home repaints.

| Candidate statistic | What can establish it | Necessary qualifier |
| --- | --- | --- |
| Root membership entries | Complete structurally parsed, authenticated root scope | Entries are variants/memberships, not distinct files, names or physical objects |
| Distinct FileDataIDs | Explicit full-scope deduplication/enumeration | A header count alone cannot establish it; locale/content variants remain separate facts |
| Name hashes present | Root named-entry metadata | A hash is not a readable filename and does not identify a content type |
| Readable filenames | A supported attributed naming source or install-manifest entry | Naming/listfile coverage is separate from file presence; unknown path does not mean missing content |
| Retained objects / physical bytes | Current verified app-owned CAS/catalog observations | Count unique physical bytes once across builds; missing/corrupt references are not verified stored coverage |
| Selected scope retained | Declared dependency/representation closure and current physical verification | “Selected encoded files retained” is not whole-game or decoded-table completeness |
| Item rows ready / names ready | Completed admitted projection artifact and exact context | Physical records, admitted sections and explicit locale; show excluded sections and unindexed copies |
| Names unavailable | Joined physical/copy/nonmatch outcomes within that scope | Empty physical string, copy unindexed and no physical match stay distinct; none means whole-game absence |
| Opaque/unsupported inputs | Actual verifier/decoder results | Separate encoded retention, missing decryption capability, unsupported codec/schema and damaged bytes |

An IN install manifest is a small installation-related manifest, **not the game’s complete file catalogue**. Its entry count or summed entry lengths cannot be presented as the full game size. Root entries and EN candidate counts are also different populations and must not be added together as “files.”

Prioritize a small status strip over a wall of speculative metrics: **retained unique objects and physical bytes**, **supported research coverage**, and **current work needing attention**. Put exact identities, raw counts and proof details one click deeper. Do not count a filename extension guessed from an untrusted path as a verified asset format.

## Locale, names, copies and encrypted data

Region chooses publication/CDN context; locale chooses research content. They are not interchangeable. Preserve requested locale, raw root locale mask and raw DB2 locale separately; do not infer equivalence from an undocumented numeric field or borrow names from another locale/build.

Physical Display values are attributed community-schema candidates. Keep exact Unicode and empty strings. Do not expand a declared copy into a physical row/name unless a separate validated interpretation supports that capability. Copies and excluded sections must remain visible in coverage, without dominating every research action.

A nonzero section key declaration is not proof that a section is unreadable. Likewise, complete verified encoded bytes do not prove successful decoding, complete decoded CKey verification, or readable rows. Present actionable states such as **Encoded file retained · name projection unavailable**, with the actual reason. Unknown flag bits remain raw metadata unless their behavior is validated.

No game installation, Battle.net directories, local hotfix cache or DBCache import is part of the flow. The home describes an exact CDN-derived client snapshot, not the live server state. If future independently sourced update streams are researched, their provenance and overlay semantics need a separate contract; do not imply present support with a “Hotfixes current” badge.

## On-demand acquisition and a background full-build proposal

The default research experience should fetch only the sources required by the requested action, with explicit scope/cost consent. Reuse verified retained sources, shared derived artifacts and in-flight transfers. Files stay in the one shared content store; build identity and research views reference them without duplicate per-build copies.

A secondary **Retain more of this build** action could propose a background acquisition plan. Define its scope first: selected file set, supported product/context, locale/variant policy, optional installation tags and dependency closure. “All selected encoded content” still does not mean installable game, all readable assets, all server data, or every possible build variant.

Before claiming a full-build total, a supported planner must resolve the selected membership set, actual chosen encodings and known lengths, deduplicate content/representations and account for required configuration/root/EN/index dependencies. Show unknown object counts explicitly. Missing upstream objects leave a persistent partial plan; they never trigger substitution of latest files or a different edition.

Show separate quantities:

- **Logical content bytes**, with encoded/decoded representation and declared versus verified status stated.
- **Unique physical bytes already retained**, and shared attribution without summing the same bytes across builds.
- **Known additional payload bytes**, with unresolved lengths/counts still unknown.
- **Metadata/index transfer and temporary storage**, which may be substantial even for a small selected payload.
- **Actually transferred bytes and completed unique objects**, measured during the job.

A provisional estimate is labelled as such; it must not become an invented “Total game size” number. Decoded file sizes do not directly predict wire bytes or disk usage. Archive range acquisition can avoid downloading whole archives, while its index discovery still has its own costs.

Background work remains visible and cancellable without blocking inspection of retained evidence. Offer pause/resume only at the granularity actually implemented; object-boundary retry is not partial-byte resume. Ordinary home navigation should not start, duplicate, or cancel an existing build job. Offline mode keeps retained work usable and explains which next action requires unavailable sources.

## Design acceptance questions

Can a first-time researcher identify the exact build and useful next action without understanding CKey/EKey? Can they distinguish “not searched yet” from “no match,” a name candidate from game truth, and encoded retention from readable coverage? Can they begin with on-demand files without agreeing to a whole-build download? Do duplicate visits reuse the same build state and physical objects? Do fictional mockup numbers remain labelled authored, with no unsupported size, hotfix or historical availability claims?

This proposal follows the source/proof boundaries in [storage](../../STORAGE.md), [formats](../../FORMATS.md), [Item coverage](../../ITEM-COHORT.md), [paired names](../../ITEM-NAMES.md), and [product naming](../build-discovery/PRODUCT-NAMES.md). Older workspace terminology in existing documents does not override the agreed build-first home flow here.
