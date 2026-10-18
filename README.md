# VibeMiner

**Explore Blizzard’s data. Discover what changed.**

VibeMiner is a planned, standalone desktop application for exploring and datamining data published on Blizzard’s CDN. Pick a game, channel, region and build, then browse its available files and supported records. Download the data you actually explore and reuse it from a verified local cache.

It requires no installed Blizzard game, Battle.net client, Vibecraft installation, external database or local service. It never discovers, reads, or imports a game installation, Battle.net directory, or game-generated cache. Its own archive, cache and search index live locally. First launch should work with an empty cache and an internet connection.

## What it will do

- Discover published products, builds and preload metadata directly from Blizzard.
- Maintain separate game/edition workspaces, including WoW Forever and Retail, with multiple exact builds per edition and independent histories.
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

## Relationship to Vibecraft

VibeMiner is a separate application and project. Vibecraft remains the polished Warcraft companion and must retain its own easy first-run setup. Neither application requires the other. Acquisition and decoding libraries may eventually be shared through explicit versioned dependencies; neither app should read the other app’s live database or cache.

## Technical direction

**UI direction: Atlas.** Use its light ivory/teal research workbench, split views, readable comparisons, contextual inspectors and investigation notebook as the reference for the JavaFX implementation. Implement with JavaFX controls and CSS; the browser mockup is a design artifact. Forge and Observatory remain alternative design references. Asset preview support is staged separately, especially for models and maps.

The selected stack is Java with JavaFX, Gradle and embedded SQLite. Keep discovery, CDN acquisition, decoding, indexing and comparison in Java modules independent of the interface. Bundle a trimmed Java/JavaFX runtime using jlink and package Windows/macOS/Linux installers with jpackage; users do not install Java separately. Network, decoding, indexing and comparison work run outside the UI thread. No external Python, Node, Blender or database server is required by end users.

Blizzard is the default data source. Community filename lists or schema definitions, if introduced, are separate, identified metadata providers rather than silently treated as Blizzard-authored facts. Game-folder discovery, game-install reads and local game-cache imports are excluded.

**Status: interactive UI mockups.** The mining application is not implemented. Open [the three design options](mockups/index.html). See [PLAN.md](PLAN.md) for milestones, acceptance criteria, architecture and research references. Initial tracking is [Vibecraft issue #10](https://github.com/aircan80/vibecraft/issues/10); move future implementation tracking to VibeMiner’s own GitHub repository when one exists.

## Research-led workflows

VibeMiner starts with what people want to find, inspect, investigate and search:

- Collectibles: mounts, pets, transmog, toys, titles and achievement rewards.
- Classes and combat: spell/talent numbers, item effects, tuning and encounter ability changes.
- Crafting: recipes, reagents, profession gear and changed crafting requirements.
- Story and world: quests, localized dialogue, creatures, maps and unused development clues, with spoiler controls.
- Art and sound: models, textures, animations, music, voice lines and stable sound IDs.
- Developer data: database relationships, UI files and exports for addons, simulations and research.

Findings retain exact build and source context. Client data does not establish release timing, obtainable rewards, server-only behavior or market profitability. See [research journeys and archive requirements](mockups/WORKFLOWS.md).

## Shared storage across builds

Builds retain manifests and references into one app-owned content-addressed object store. Archives and caches are retention policies over that store, not duplicate copies. Identical data is reused across versions; derived data is shared when its source content, decoder/schema, locale and output settings match. Concurrent requests for the same object share a download. Removing a build releases its references; cleanup removes only objects unused by all remaining builds and active work. Storage screens distinguish logical build size, physically shared bytes, additional download bytes and reclaimable bytes.
