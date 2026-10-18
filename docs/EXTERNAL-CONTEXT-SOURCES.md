# External context for future game research

Status: research proposal for [#116](https://github.com/aircan80/vibeminer/issues/116), checked 2026-10-18. This work is outside milestones 5 and 6. It adds no provider integration, downloads, game-install access or new screen commitment. See [research areas](RESEARCH-AREAS.md) and [experience plan](RESEARCH-EXPERIENCE-PLAN.md).

## Product direction

Higher-level views should answer game questions: “What is this quest about?”, “Which NPC starts it?”, “Where does this objective happen?”, or “How does this encounter relate to these abilities?” They should assemble relationships, localized descriptions and previews with paths back to the original records. Reproducing the Tables browser under an Items or Quests heading would add little value.

Keep exact-build client evidence and supplementary context independently inspectable. A current API description or community coordinate can make an old record understandable without establishing that description or coordinate was true in the selected historical build. Conflicting sources remain visible; missing information remains missing.

## What sources can provide

| Candidate | Verified useful surface | Important limit |
| --- | --- | --- |
| Blizzard WoW Game Data APIs | Quest ID lookup, indexes grouped by category, area and type; creature lookup/search and display media; journal expansions, instances, encounters and media; item lookup/search/media. | The documented selectors are identity, namespace and optional locale, rather than an arbitrary client build. An endpoint's existence does not establish complete fields, historical coverage, availability in every edition, or an entire quest prerequisite chain. |
| QuestieDB | Community-maintained quest, NPC, item and object datasets, corrections and translations for several WoW editions. A candidate source of relationship/location context. | Its data and corrections have their own release history. Expansion selection alone does not prove exact-build equivalence. Licensing/admission remains unresolved; no dataset is approved for embedding. |
| TrinityCore project documentation | An independently maintained model of server-side quest definitions, rewards, objectives and validation provenance. Useful for understanding gaps and planning questions. | Server reconstruction is not Blizzard's production database. This is a research reference, not an approved authoritative data provider. |

The official [WoW endpoint reference](https://community.developer.battle.net/documentation/world-of-warcraft/game-data-apis) was inspected through its [public JSON document](https://community.developer.battle.net/api/pages/content/documentation/world-of-warcraft/game-data-apis.json), which backs the JavaScript documentation site. No authenticated game-data requests were made. Representative routes are `/data/wow/quest/{questId}`, `/data/wow/quest/category/{questCategoryId}`, `/data/wow/creature/{creatureId}` and `/data/wow/journal-encounter/{journalEncounterId}`. Verify actual returned fields and edition support before promising an integration.

QuestieDB documents its scope and modes in its [README at inspected commit e9a67d1](https://github.com/Questie/QuestieDB/blob/e9a67d1a2753a0e64c4c96bf7864788fac7b765f/README.md), and explains inherited data, corrections and localization origins in [PROVENANCE.md](https://github.com/Questie/QuestieDB/blob/e9a67d1a2753a0e64c4c96bf7864788fac7b765f/PROVENANCE.md). Do not execute addon code to obtain context. A future adapter would require an approved, bounded data format and independently established permissions. The inspected recursive repository tree contained no LICENSE/COPYING file; public visibility and source attribution alone do not establish reuse permission.

## Why quests need more than client tables

At pinned WoWDBDefs revision `b9a63096cdd99b26967f17f9515377ae62cadfd7`, [QuestV2](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/QuestV2.dbd) contains identifiers and a few supporting fields rather than a complete quest narrative. [QuestObjective](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/QuestObjective.dbd) describes `ObjectID` as dependent on objective type; it must not become an unconditional NPC or Item link. [QuestPOIBlob](https://github.com/wowdev/WoWDBDefs/blob/b9a63096cdd99b26967f17f9515377ae62cadfd7/definitions/QuestPOIBlob.dbd) explicitly notes incomplete client availability for objective references. These are community format interpretations; each selected build still needs compatible schemas.

TrinityCore's [quest query handler at cd29c358](https://github.com/TrinityCore/TrinityCore/blob/cd29c358ac6647b9ffeeae3a3cfc9da5829868c9/src/server/game/Handlers/QuestHandler.cpp) models a server response to a quest query, while its [quest_template documentation](https://trinitycore.info/database/335/world/quest_template) describes narrative, objective and reward data with a `VerifiedBuild` convention distinguishing recorded and edited evidence. This supports a practical distinction: a client CDN archive alone is not a complete account of server-supplied quest behavior. It does not prove the behavior of any particular Blizzard realm.

Accordingly, client tables do not establish current quest availability, complete NPC spawn locations, a full prerequisite graph, server scripts, or a complete history of hotfixes. An external API observation also does not reconstruct every historical hotfix. Locale gaps can exist independently in the client and in supplementary translations; an unavailable localized description must not silently borrow a different locale or era. Links inferred from matching numbers require a validated edition/entity mapping.

## Temporal and locale identity

Blizzard explicitly warns that [API namespaces are not strict or semantic versioning](https://community.developer.battle.net/documentation/guides/game-data-apis). Therefore `static-us` is not a promise of the selected CDN build. Treat a response as “Blizzard API, observed at [time], namespace [value]”; label historical compatibility as unknown unless separately established.

The [official localization guide](https://community.developer.battle.net/documentation/world-of-warcraft/guides/localization) describes API locale tokens such as `en_US`. Omitting locale returns supported localized values together; specifying it flattens localized fields to the requested language. Map these tokens explicitly to build locale identifiers such as `enUS`; do not confuse API region, build locale and client edition. Preserve requested and actually supplied locale, including any explicitly identified fallback. Locale switching must fence stale results from the previous context.

For every external observation retain provider identity, resource key/URL without credentials, entity kind and ID, edition applicability, namespace, region, requested/actual locale, observed time, provider revision if supplied, response hash, attribution and permission/cache policy version. A content hash proves captured bytes, not their truth or historical applicability. Distinguish an exact client fact, an API observation, a community assertion and a VibeMiner interpretation.

## Access, caching and permissions

Blizzard's [OAuth guide](https://community.developer.battle.net/documentation/guides/using-oauth) describes client registration and client-credentials access for most API calls. Generic game research should not request a user's character/profile authorization. An optional API provider must have an explicit credential strategy; never embed a shared client secret in desktop packages or record tokens in URLs, logs, exports or provenance. Credentials remain outside ordinary cache and source control.

The [getting-started guide](https://community.developer.battle.net/documentation/guides/getting-started) documents client limits of 36,000 requests/hour and 100/second. These are ceilings to recheck before implementation, not target traffic. Bound concurrency, share identical requests, honor throttling and retry instructions, and let cancellation stop unnecessary enrichment. CDN bulk downloads and API quotas need separate scheduling policies.

The currently served [Blizzard Developer API Terms of Use](https://www.blizzard.com/en-us/legal/a2989b50-5f16-43b1-abec-2ae17cc09dd6/blizzard-developer-api-terms-of-use), dated October 1, 2019, require source identification, confidential API credentials, and a maximum 30-day TTL for API-obtained data; they also contain deletion requirements. These are material implementation constraints to verify against the accepted terms when admitting the provider. This document does not grant permission or determine legal compatibility. API observations must not inherit indefinite CDN archive retention or be bundled/relicensed under VibeMiner's GPL by default.

Community providers likewise require a specific grant covering the selected data, attribution, transformations, caching and exports. A project's code license is not automatically the license for all game data or media. WoWDBDefs' schema permissions do not grant rights to an unrelated quest database. Keep source-specific deletion and expiry possible even when a physical byte-store deduplicates content.

## Small future provider boundary

Keep build content acquisition and exact table decoding unchanged. A higher-level research use case can request optional context through a separate application port. Infrastructure adapters handle HTTP, credentials, bounded parsing and provider-specific rules; presentation never fetches or joins sources itself.

Return asynchronous, cancellable observations with provenance and applicability, plus explicit unavailable/unsupported/authentication/throttled/expired states. A partial client view should render immediately while optional context loads. Show transfer progress only when a trustworthy total exists; otherwise show the current stage. Foreground research remains responsive during background build retention.

Higher-level assemblers join admitted observations into a view; they never overwrite table records or manufacture a CDN retention proof. Derived results key their exact client inputs, provider observations, locale and assembler version. Source expiry invalidates dependent context. Turning a provider off must leave raw Tables, Files and Assets usable.

Before implementation, select one narrowly useful question, validate an edition-specific mapping and response fixture, settle provider permissions/credential/cache policy, and define loading, conflicting, expired and missing-context states. Test temporal mismatches, locale switches, cancellation and unavailable providers without live network in normal CI. There is no external-source implementation commitment in the current milestones.
