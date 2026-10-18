# VibeMiner

**Explore Blizzard’s data. Discover what changed.**

VibeMiner is a standalone desktop application being built to explore and datamine Blizzard’s CDN data. The current alpha discovers and saves exact-build metadata; content research is being added in small tested increments.

It requires no installed Blizzard game, Battle.net client, external database or local service. It never discovers, reads, or imports a game installation, Battle.net directory, or game-generated cache. Its own archive, cache and search index live locally. First launch should work with an empty cache and an internet connection.

## Available now

[Alpha.6](https://github.com/aircan80/vibeminer/releases/tag/v0.1.0-alpha.6) is published and independently verified at exact `ee6598f0369266c236b6c0f051e7974458d33905`, annotated tag `d6ba492e67834cf13b67daa9c43efb88c3734b1d`. [Publication run 39127913086](https://github.com/aircan80/vibeminer/actions/runs/39127913086) passed all four native targets and eleven Linux UI workflows. All 12 final assets were freshly downloaded and independently checked for version, exact source, target, manifests, SHA-256, byte counts and exclusion of private archive paths. Intel `es2,sw` configuration is preserved.

This release adds paired physical Item names, explicit bounded literal name search and known-cost repair of the original canonical root/install representation. Discovery, saved metadata, retained-file inspection, local metadata reuse and admitted physical Item pages/lookup remain available. Packages bundle Java; they are unsigned and not notarized. See [verified release notes](docs/releases/v0.1.0-alpha.6.md) for exact scope and proof limits.

Copies/other sections, whole decoded-table CKeys, validated relations, comparisons, exports, GC and complete historical archives remain unfinished. #91 one-record JSON export has qualified source at `b3aecf3` with four-platform/twelve-workflow acceptance, while remaining unreleased; #92 metadata history remains planned. See [current status](docs/PLAN.md).

Current source export qualification passed all four targets/twelve native workflows at `b3aecf3`; #91 is still open and not included in alpha.6. Selected Excavate branding at `6bdba4f` passed all four native targets and twelve Linux UI workflows in [CI 39138548266](https://github.com/aircan80/vibeminer/actions/runs/39138548266). The next implementation scope is [guided Discover and C Build overview](docs/BUILD-BROWSER-IMPLEMENTATION.md) only, with real background retention; other research destinations remain planned.

## What it will do

- Discover published products, builds and preload metadata directly from Blizzard.
- Maintain separate exact-build contexts across supported WoW products/editions, with retained state and independent histories; friendly edition bindings require verified identities.
- Download and retain older available builds in VibeMiner’s own archive, keep several builds available, and reopen retained content offline.
- Store identical data once across versions of the same game, shared by their build manifests rather than copied into per-build folders.
- Browse file manifests, identifiers, metadata and supported decoded tables.
- Search WoW records such as items once their exact-build tables and schemas are supported.
- Compare two builds to find added, removed and changed files or records.
- Show where every result came from: product, build, locale, content identity and decoder/schema version.
- Download on demand, show progress, cancel work and manage cache size.
- Reopen cached content offline and clearly identify data that still needs downloading.

The first detailed decoder will target World of Warcraft. Discovering another Blizzard product does not automatically mean its formats are decoded. Unsupported or encrypted data stays visible with an honest status. Published build pointers are not a complete historical archive, and a preload is not proof that its contents are available or readable.

Comparisons start within one game/edition. Cross-edition comparisons require validated record identities and compatible schemas; matching numeric IDs alone are insufficient. Product identifiers and decoding capabilities must be verified during implementation rather than inferred from edition display names.

## Technical direction

**UI direction: Atlas.** Use its light ivory/teal research workbench, split views, readable comparisons, contextual inspectors and investigation notebook as the reference for the JavaFX implementation. Implement with JavaFX controls and CSS; the browser mockup is a design artifact. Forge and Observatory remain alternative design references. Asset preview support is staged separately, especially for models and maps.

The selected stack is Java with JavaFX, Gradle and embedded SQLite. Keep discovery, CDN acquisition, decoding, indexing and comparison in Java modules independent of the interface. Bundle a trimmed Java/JavaFX runtime using jlink and package Windows/macOS/Linux installers with jpackage; users do not install Java separately. Network, decoding, indexing and comparison work run outside the UI thread. No external Python, Node, Blender or database server is required by end users.

Blizzard is the default data source. Community filename lists or schema definitions, if introduced, are separate, identified metadata providers rather than silently treated as Blizzard-authored facts. Game-folder discovery, game-install reads and local game-cache imports are excluded.

All enhancements, defects and implementation work are tracked in [GitHub Issues](https://github.com/aircan80/vibeminer/issues). See [formal documentation](docs/README.md), [building and downloads](BUILDING.md), [milestones](https://github.com/aircan80/vibeminer/milestones) and [design references](mockups/index.html).

## Research-led workflows

VibeMiner starts with what people want to find, inspect, investigate and search:

- Collectibles: mounts, pets, transmog, toys, titles and achievement rewards.
- Classes and combat: spell/talent numbers, item effects, tuning and encounter ability changes.
- Crafting: recipes, reagents, profession gear and changed crafting requirements.
- Story and world: quests, localized dialogue, creatures, maps and unused development clues, with spoiler controls.
- Art and sound: models, textures, animations, music, voice lines and stable sound IDs.
- Developer data: database relationships, UI files and exports for addons, simulations and research.

Findings retain exact build and source context. Client data does not establish release timing, obtainable rewards, server-only behavior or market profitability.

## Shared storage across builds

The following is the target storage contract. The byte-store foundation exists; full retention, cleanup and storage-management workflows remain unfinished.

Builds retain manifests and references into one app-owned content-addressed object store. Archives and caches are retention policies over that store, not duplicate copies. Identical data is reused across versions; derived data is shared when its source content, decoder/schema, locale and output settings match. Concurrent requests for the same object share a download. Removing a build releases its references; cleanup removes only objects unused by all remaining builds and active work. Storage screens distinguish logical build size, physically shared bytes, additional download bytes and reclaimable bytes.

## Development workflow

Track enhancements, defects and maintenance in GitHub Issues before implementation. Deliver small logical steps with explicit acceptance criteria. Use test-driven development where practical, meaningful unit and regression tests, and integration/UI/packaging checks where needed. Record validation results and remaining work in the issue before closing it.

## License

Original VibeMiner software is **GPL-3.0-or-later**. See [LICENSE](LICENSE), [NOTICE](NOTICE) and [licensing details](docs/LICENSING.md). Separately licensed third-party materials, including CC BY-SA 4.0 WoWDBDefs definitions, retain their own terms and notices.
