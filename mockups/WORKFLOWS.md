# VibeMiner mockup coverage

Review contract for the three desktop interface concepts for the JavaFX application. Mock data and all discoveries must be clearly labeled fictional. These prototypes demonstrate interaction and information architecture; they do not connect to Blizzard or decode files.

## Shared product rules

- Atlas is the selected JavaFX design direction. Keep the other concepts as references.
- Support separate game/edition workspaces (including Forever and Retail), multiple exact builds per edition, and independent histories. Verify official product identifiers and adapter capabilities. Compare within an edition by default; cross-edition record comparisons require validated identity mappings and compatible schemas.
- Independent Blizzard CDN client. Never read a game installation, Battle.net data, or local game caches; no DBCache or hotfix imports.
- Choose product/channel, region, locale, and build. Download selected content on demand into VibeMiner's own archive, with verified content and explicit download scope.
- Maintain older builds in that archive. Preserve exact build identity, build/CDN configuration references, content hashes, locale, and source provenance; a version label alone is insufficient.
- Distinguish build discovery from content availability. An older catalog entry does not guarantee that every required object remains available on the CDN. Show archived, partial, missing, and unavailable content explicitly.
- Pin retained builds so routine cleanup cannot remove their required content. Distinguish retained game objects from disposable derived artifacts by references and policy, without creating a second copy of game bytes; show consequences before deleting retained content.
- Store shared game content once across versions of the same game, including cached and archived content. A content-addressed object store owns the bytes; each build stores only membership and manifest metadata referencing those objects. Archive and cache are retention states, never duplicate byte stores.
- Deduplicate queued and concurrent downloads of the same object. Reuse and verify an existing object instead of fetching or copying it for another build. Scope identifiers by game and object identity; a filename or build number is not a content identity.
- Share derived artifacts when their source content, decoder version, schema revision, locale, and relevant transformation options match. Keep per-build search membership as references to shared artifacts rather than duplicate decoded payloads. Artifacts with different interpretation inputs are distinct results.
- Separate logical build size from physical bytes stored, additional bytes needed, shared/reused bytes, and bytes reclaimable now. Adding an overlapping build should visibly reuse objects; never sum logical sizes as actual disk usage.
- Delete objects only when no retained, pinned, active, or in-progress operation references them. Removing one build drops its membership and reclaims only unreferenced objects; shared objects remain available to other builds.
- Retain build/source provenance with findings and exports. File names may come from a community listfile; unidentified names do not imply missing files.
- An asset appearing in a build does not establish that it will become obtainable in game.

## Screens and review journeys

| Area | Required mocked workflow |
| --- | --- |
| Start / Sources | Select CDN product, region, locale, and build; inspect manifest/configuration identity; choose VibeMiner archive location and download scope; initialize catalog. |
| Overview / Builds | Browse current and older build history; inspect exact identity, archived coverage and CDN availability; queue older-build download with existing/shared versus additional bytes; pin/retain a build; restore its archived view using shared objects; select baseline and target. |
| Research home / Search | Start with user questions about mounts/pets/appearances, class tuning, crafting/reagents, assets/maps/audio, lore/dialogue, or developer IDs/tables. Search across selected archived builds and drill into evidence with explicit availability and source context. |
| Compare | Filter added/changed/removed records and files; open one result; inspect field-level before/after; follow a related record; bookmark or export. |
| Databases | Search/select a table; filter/sort rows; inspect readable and raw fields, record ID, schema revision, related records, and history. |
| Files | Search path or FileDataID; filter type/status; inspect size, CKey/EKey and naming source; preview, queue download, or export. |
| Assets | Switch model, texture, audio, text, and map examples; show relevant preview controls and metadata; explain unsupported/encrypted previews. |
| Watchlists | Create/edit a saved query, choose scope and trigger, inspect matching findings, enable/disable it. |
| Investigations | Save findings, add a note/tag, reopen related evidence, and prepare a report. |
| Exports | Select CSV/JSON/report or available asset format, choose scope/destination and provenance options, queue export, inspect completion. |
| Jobs | Inspect download/verify/index/compare/export stages, unique byte counts, reused objects and progress; deduplicate concurrent requests; pause/resume, retry failure, cancel, and view diagnostic details. A failed older-build object must leave the archive visibly partial. |
| Settings | Inspect one shared content-store location, retained builds, derived-artifact usage/limit, network concurrency, definition/listfile versions, appearance/accessibility, and notifications. Show physical storage and reference-safe reclaimable bytes separately from logical build sizes. |

## Required visible states

- Search results and no results; selection and detail view; loading/progress and completion.
- Offline with archived content available; metadata-only build; uncached content requiring network; queued/downloading/paused/verifying/complete; partial or failed download with retry; older CDN object unavailable.
- Retained/pinned versus unpinned build; archive restored without downloading again; incomplete archive comparison with the missing scope disclosed.
- An overlapping build reuses stored objects and downloads only missing content; two queued builds share one transfer for a common object; removing a build preserves objects referenced by another build or active job.
- Unknown filename with FileDataID intact; missing/incompatible table definition; encrypted asset/missing key; unsupported preview format.
- Empty watchlist/investigation or editable populated example; invalid or incomplete form feedback.
- Confirmation for deleting retained archive content or cancelling work, with a meaningful explanation of scope and actual reclaimable bytes. Preview cleanup must preserve retained build content; objects shared with other retained builds or active operations are protected.

## Suggested coherent fictional dataset

Use `DEMO-104 → DEMO-105`, WoW PTR / `wowt`, US, `enUS`. Illustrative totals: 1,284 file changes (216 added, 1,031 changed, 37 removed), 18 changed tables, 42 unresolved names, 7 encrypted assets.

- Mount: **Moonlit Surveyor**, illustrative record ID `900001`.
- Spell: **Emberglass Focus**, illustrative record ID `900002`; coefficient `0.72 → 0.80`.
- Recipe: **Surveyor’s Field Satchel**, illustrative record ID `900003`; reagent count `8 → 6`.

Consistency matters more than these exact names or totals. If another dataset is used, label it fictional and keep its counts and record details coherent across screens.

## Domain references

- [TACTSharp](https://github.com/wowdev/TACTSharp): online storage, product/region/locale selection, file lookup by name/ID/content or encoding key, and CDN-structured data sources. Use only CDN access and VibeMiner-owned archive storage. Its encrypted-product support is incomplete; prototypes must not promise arbitrary decryption.
- [DBCD](https://github.com/wowdev/DBCD): DBC/DB2 decoding using WoWDBDefs; missing definitions require explicit handling. Its other source capabilities do not expand this product's CDN-only scope.

## Review standard

Every concept must make the core journey clickable: select a current or older CDN build → choose download scope → inspect download/verification → retain or restore the archive → compare → inspect a finding → save/watch/export → inspect the resulting job. Major navigation destinations need distinct populated screens. Demonstrative controls should either change meaningful visible state or explain their prototype limitation. Different visual concepts should share functional coverage, while differing in density, layout, and investigation style.
