# Research experience: raw evidence and semantic investigations

**Status: product/UX proposal under #116 for future discussion and phased research.** This document does not authorize runtime work, create a new backlog or expand milestone 5 (guided Discover/C overview) or milestone 6 (top navigation/Tables/optional FK labels). Higher-level entity screens remain future work. [Domain research](RESEARCH-AREAS.md) owns primary-source evidence and specific candidate dependency maps; a proposed relationship is not an admitted decoder or available dataset.

## Begin with the research question

VibeMiner should serve questions rather than expose a long flat list of parsers. Someone investigating an item asks what it is, which exact fields changed and what supports that conclusion. Someone examining a zone asks which maps, paths, creatures and story clues relate to it. A theorycrafter asks about a spell's underlying effect records, not merely its localized description. Each readable view must let the researcher return to exact raw evidence and understand what the client cannot establish.

The selected build/edition/configuration and requested locale remain the investigation context. Friendly labels and optional outside knowledge never replace those identities. Interpretation and completeness are visible per result, not a generic “supported game” promise. No actual research finding is populated from demo rows or external text masquerading as CDN evidence.

## Value beyond the technical foundation

Tables, Files and Assets are the foundation. A higher layer earns its place only when it reduces the work needed to answer a concrete question across those sources. Another catalogue with friendlier column headings is insufficient.

- **“What is this newly observed item, and what can I safely say about it?”** One readable finding brings its admitted name, item category, exact build and explicit coverage together; source links explain each fact. It does not invent a reward source or obtainability. Later comparison can add what actually changed once both exact builds are qualified.
- **“How does this flight route connect two places?”** An ordered route combines endpoint names and intermediate nodes, with the map/coordinate evidence that supports the display. The researcher should not manually join three tables to see direction and sequence; unavailable access/unlock facts stay unknown.
- **“Which dungeon does this encounter belong to, and what evidence connects it to this map?”** A connected encounter context follows reviewed journal/instance/map relationships and identifies what is client-described versus unobserved server mechanics.
- **“What story thread might this quest belong to, and what is missing?”** A bounded story view combines available line ordering, objectives and localized fragments while visibly separating prerequisite/server-data gaps. Type-dependent objective targets are resolved only by reviewed rules, never generic FK guesses.

These task outcomes are candidate product tests, not current capabilities. Each must keep an immediate raw-record/file escape without forcing users to understand storage formats before learning the useful answer. The taxonomy below is an information-architecture aid subordinate to those outcomes, not a feature checklist to implement all at once.

## Proposed area grouping

Use top-level areas sparingly. Inside each, use a contextual left catalogue or entity list, consistent with the approved Tables pattern. The following is a taxonomy for discussion, not a commitment to ship every tab:

| Area | First useful question | Candidate internal subjects | Evidence boundary |
| --- | --- | --- | --- |
| Build overview | What is ready, retained or still required? | Identity, source/proof, inventory, jobs, storage | Metadata, retained bytes, decoded coverage and upstream availability remain distinct |
| Tables | Which raw records/fields and declared relations exist? | Table catalogue, structure, pages, exact target-column navigation | Definitions do not create rows; only admitted layouts/sections/relations |
| Files & assets | Which bytes/models/textures/audio/map assets are present? | FileDataID/path metadata, source, encoded/decoded status, supported previews | Names may be community metadata; preview/decryption support is independent |
| Items & rewards | What is this item and which evidence explains its appearance/effects? | Items, appearances; later collectibles/reward associations | Obtainability/drop/release claims require evidence beyond a client row |
| World & travel | How do maps, zones and flight routes connect? | Maps/areas, taxi nodes/paths; later world placements | Geographic/model assets do not prove server placements or live travel access |
| Characters & encounters | Which creature/encounter records relate to this place? | Creature displays, dungeon/raid journal, encounter abilities | Journal/display data is not a complete NPC spawn/AI/drop database |
| Quests & story | Which localized story/objective clues connect? | Quest lines, objectives, POIs, text and related entities where supported | Client quest fragments are not a complete prerequisite/progression graph |
| Combat & crafting | Which spells/effects/recipes/reagents changed? | Spell/effect dependencies, recipe/reagent outputs | Static client values alone do not establish damage, server hotfixes or profitability |

Do not turn every subject into a new global area. Items/Spells/NPCs can be internal subject catalogues rather than permanently competing header buttons. Exact grouping should be tested using realistic tasks after the foundational navigation ships.

## Progressive raw → interpreted → connected evidence

Every semantic view follows a consistent progression:

1. **Identity and readiness:** exact entity/record ID, source table/FileDataID, build/locale, declared versus actual source representation, support/coverage and any missing dependencies.
2. **Readable interpretation:** a small set of validated fields with community-schema attribution, raw values available, uncertainty and empty/missing/copy states preserved.
3. **Reviewed relationships:** only admitted links with exact target columns and explicit semantic rules. Duplicate/partial target domains cannot manufacture one result; polymorphic links require reviewed discriminators.
4. **Source inspection:** direct “Inspect source record” and “Inspect file/proof” actions preserve the entity context and return trail. Raw tables/files remain first-class escape routes rather than a second disconnected application.
5. **Optional comparison/finding:** later exact compatible-build comparison and deliberate evidence export/notes, with both source sides and interpretation revisions retained. No automatic full-build diff or watchlist is introduced here.

Entity views consume the layered table/content providers; they do not download files, parse formats or run ad-hoc SQL from UI code. One shared content store and shared derived artifacts serve raw and semantic views. Cross-entity caches bind source/decoder/schema/locale/mapping identities, with exact consumer-context validation; they never merge by display name alone.

## Focused first candidate: one useful Item investigation

After milestone 6 and existing qualification, the best first semantic candidate is a compact Item inspector over the admitted physical Item/name cohort plus the independently admitted ItemClass relationship. It builds on the most mature evidence and answers “What is this ID/name, what kind of item is it, and where did these values come from?”

Initial surface: exact Item ID/name candidate, four currently admitted numeric candidates, reviewed class description, raw record links and a concise provenance/coverage panel. Preserve empty name, name-copy-unindexed and absent physical match distinctly. ItemClass.ClassID is the declared non-primary target; no default ItemClass.ID join. Existing label/body admission must precede use. Do not infer Subclass, quality, item level, spell effects, acquisition source or collectible status merely because mockups have room for them.

This candidate is not a generalized Item database, reward finder or a decision to duplicate the Tables grid. It can be a small focused inspector opened from a record, search result or later subject list. Test whether it answers a real collector/developer question better than the raw view before expanding it. The JSON evidence-export capability remains its own acceptance slice, not something every entity provider reinvents.

## World/travel candidate after Items

A possible second semantic vertical is an ordered flight-path investigation, not a full map editor: select a taxi path, inspect declared endpoints, follow node order and inspect raw sources. The domain evidence identifies explicit TaxiPath endpoint links and TaxiPathNode.PathID/NodeIndex ordering. Actual source/layout/locale admission is still required.

A map preview or route overlay adds independent coordinate-system, projection, map assets, transform and bounds validation. A line between nodes does not establish route availability, faction access, cost, unlock conditions or server behavior. Keep geography/route facts separate from any external route guide. A minimal node/path list may prove value before investing in full rendering.

## NPCs, dungeons and quests: explicit dependency gaps

Use precise labels such as “creature display” or “journal encounter” when that is the actual evidence. A JournalEncounter → JournalInstance → Map path can support a reviewed encounter context; it does not establish a complete dungeon entity, creature spawn locations, loot tables, encounter mechanics or current server availability. Additional records need independent mapping/source evidence.

Quest exploration requires a stricter admission plan. Quest line ordering is not the prerequisite graph. QuestV2 metadata is not the full quest database. Objective object IDs may be Type-dependent, and some objective/POI targets are not present in client data. A generic integer FK renderer cannot safely create those links. Each supported objective kind needs a reviewed discriminator/target policy, missing-client-data outcome and localized text provenance.

A useful exploratory quest view could show one admitted line's ordered entries, a specific quest's observed objective records and available localized text, with raw source links and spoiler controls. It must label missing prerequisites, completion conditions, dialogue, rewards and server-only logic as unknown rather than fill gaps from an online database. No “complete quest chain,” route optimization or gameplay walk-through promise precedes sufficient evidence. Quests should therefore follow narrower validated entity work rather than launch with all subjects at once.

## Optional outside context

External context may help explain a finding, but it must remain an explicit optional source lane rather than a hidden interpretation authority. Possible categories are official public descriptions, separately reviewed community metadata or researcher-added links/notes. Provider availability, terms, licensing, API limits and privacy/network behavior require separate evaluation before integration.

Every outside result shows provider, direct link, capture/publication time when known, requested locale, exact-build relevance and mapping method/confidence. “Current website” data without a historical build match is clearly not exact-build evidence. Edition/locale mismatch, absent mapping and changed text are explicit. Do not overwrite original CDN values, refresh historic timestamps or synthesize a server fact from a nearby named entity.

Enable external fetching only through a deliberate user choice with disclosed source scope. The default offline investigation remains usable without it; do not scan game installs or game-generated caches. Cache external metadata separately by provider/capture/interpretation identity while preserving shared-byte storage policies; an outside tooltip cannot implicitly queue game content or authenticate Blizzard bytes. Do not select concrete external vendors solely because they are popular; research primary access/licensing evidence first.

## Phasing and evidence gates

- **Now:** background domain research and product task examples only. Milestones 5 and 6 stay unchanged; no new runtime issue burst.
- **After foundational tables/navigation:** choose one admitted Item inspector and prove question→interpretation→raw evidence→return flow, offline and keyboard accessible. Keep its exact cohort/exclusions visible.
- **Then:** evaluate one travel/path or journal context vertical using reviewed source relations; qualify data before advanced visualizations.
- **Later:** scoped combat/crafting and quest/story investigations, each with explicit extra tables, polymorphism, localized text and server-data limits. External context is independently opt-in and licensed.

Before a phase becomes implementation: nominate exact source/cohort and minimum dependency closure; define fields/joins/absence semantics and schema/decoder revisions; author deterministic malformed/copy/partial/locale fixtures; measure realistic query/render/cancellation work; separately authorize bounded official evidence where needed; and review actual native screenshots/accessibility. Unit success is not a live-data claim, a browser mockup is not JavaFX acceptance, and a published client record is not a release announcement.

## Assumptions and later discussion points

The proposed grouping assumes researchers alternate named entities with raw evidence rather than need every category constantly visible. It also assumes Item evidence offers more immediate value than a broad NPC/quest catalogue whose server gaps are large. Validate these with task-based review, not usage claims invented from the taxonomy.

Later decisions include which first Item task is most useful, whether World/Travel should be one area or internal map catalogue, which spoiler controls are necessary before story text, how to show build relevance for external sources, and which optional context sources have acceptable primary access/licensing evidence. None requires interrupting current implementation to ask the user now. Record findings, then discuss concrete reviewed options when the next semantic milestone is actually prioritized.

## Later: what changed at the entity level?

[Entity comparison #117](https://github.com/aircan80/vibeminer/issues/117), under comparison epic #8, will compare the supported high-level things: added/removed/changed item properties, quest objectives or rewards where admitted, NPC context, flight connections and encounter relationships. It follows usable entity views and reviewed cross-build identity. Locale changes, decoder/schema coverage and external-source updates must be separated from client game-data differences; missing or unreadable evidence is not deletion. Raw comparison #22 and supported Item comparison #23 remain narrower related work. This adds no implementation to the current milestones.
