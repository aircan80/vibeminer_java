# Build discovery usability review

Design proposals for [issue #93](https://github.com/aircan80/vibeminer/issues/93). Review the [interactive concepts](index.html). These proposals are not implemented production behavior.

## Current difficulty

The [existing 1280×800 application screenshot](../javafx-product-builds-1280x800.png) shows the required product selector below the visible viewport. Empty workspace context and saved-workspace controls precede discovery; loaded build results can precede the controls that choose their source. The first required input is a raw product identifier, with filtering by that identifier rather than an understandable game name. Pointer protocol details and fictional-fixture controls compete with the primary task.

## Revised proposed hierarchy

For the dataminer audience, start with **choose a WoW product → automatically refresh its published builds → select a build → Open build → mining home**. Place the clearly labeled Product dropdown directly beneath **“Which builds do you want to explore?”** and its short introduction, within the discovery page’s main content. It is the first task control, before results; do not place it in the global header or a detached top-right control. Each option shows the exact product ID together with a descriptive name. The current scope is World of Warcraft products only; keep exact WoW IDs and descriptive names together. Keep the chosen exact product visible above a compact build list and contextual details. Reduce empty research-context chrome during initial browsing; local builds remain in the same build list rather than a separate saved-build destination.

The original edition-sidebar, guided-card and research-home concepts explored the navigation problem. They remain design history rather than a selected implementation. An intermediate dropdown proposal included other Blizzard products after Warcraft. The latest requested scope is WoW-only. A later global-dropdown placement separated the required choice from its task heading. The current proposal places Product immediately under the discovery question, follows that product-first workflow and leads to a separate [mining home](../build-mining-home/index.html); production implementation remains pending.

Keep exact IDs directly readable in the picker and selected-product heading, alongside plain descriptions of editions/channels. A descriptive alias requires reviewed evidence; neither a machine prefix nor a marketing label proves protocol identity. Mockup aliases must be explicitly illustrative until verified. Unmapped WoW products remain discoverable with their exact raw identifier and an honest unresolved-name label. Other Blizzard games are outside this discovery design. Unsupported decoders do not make a published product disappear. Grouping products must not rewrite or merge their source identities.

## Page hierarchy

Use one page-level heading followed by Product and the build results. Choosing a product automatically refreshes its published build list; retain a secondary manual Refresh builds action for retry or rechecking the same product. The results heading is subordinate; avoid a second equally prominent heading, repeated selected-product summaries or multiple competing discovery buttons. Remove the obsolete “Next: discover published metadata” hint and the redundant WoW/Retail/Live eyebrow above Published builds. Introductory guidance must refer to the actual Product control, not an obsolete edition/sidebar choice. Keep exact selected-product identity visible where useful without repeating the same technical explanation around every control. The selected-build inspector follows results with the build’s advertised-region badges, a separately disclosed preferred download/source route and one primary **Open build** action. Remove separate Prepare metadata, Start mining, Check availability and Explore actions; retain source/technical details as a secondary link. A required regional-choice step is unnecessary when advertised rows refer to the same complete build references. Merely selecting a product or row must not open a build or download its research dependencies.

## One build list

Remove Saved builds and Investigations from this discovery page’s navigation. Use one list with **All / Local / Currently advertised** filters. All combines local observations with the latest successfully checked advertisements; Local means the defined metadata or retained scope exists locally; Currently advertised means present in the checked official publication scope. Keep local-presence and publication badges separate.

A local-only entry not present in the last checked publication scope is not proof that Blizzard removed the build or that its content is unavailable. Preserve its exact identity and show last observation/check scope where relevant. A failed or partial refresh must not turn an unobserved region into a definitive absence. Avoid a separate saved-workspace workflow for reaching the same exact build.

## Regions and build identity

Defaulting to **All advertised regions** removes a prerequisite most users cannot yet answer. This means regions represented in the actual official metadata; it does not promise exhaustive global coverage. Region is distinct from research locale.

Show one build row with region badges when the official region rows share the same complete build references. A version label or build number alone is insufficient: product, channel, build configuration, CDN configuration, product configuration and any other identity-bearing references must agree. Different configurations remain separate rows, even with identical displayed versions.

Preserve every regional observation and its exact provenance. Grouping is a presentation decision, not deletion or rewriting of region-specific snapshots. Source/download routing is separate from build identity: disclose the preferred route (US in the current mock flow), preserve other advertised routes and evidence, and do not ask users to choose a “different build” when only routing differs. This supersedes the earlier proposal requiring an explicit Source region selection for every grouped result. A route policy must not silently substitute a genuinely different build or imply that a regional advertisement proves complete content availability.

The current application discovery port requests one product/region pair. Aggregate discovery needs an application-layer contract with coherent results and failure handling; JavaFX must not own HTTP fan-out or source interpretation.

## Build-to-home continuity

Discovery must hand the exact chosen product/build/configuration/locale context, advertised-region set and separately chosen source-route context to the mining home. Regional grouping must preserve all underlying observations; acquisition still uses an explicitly represented exact source route. Open build automatically handles the prerequisites for the selected context: use verified saved metadata immediately, or download and verify missing metadata with visible progress before opening mining home. Failure stays on the chosen build with a clear retry path; do not navigate on partial success. This replaces the earlier two-step Prepare metadata / Start mining proposal. State the preparation scope without implying a full game download, fully retained build or universal decoder support, and keep the same context visible at the destination.

Fence asynchronous completion by the captured product, build references, source route and opening generation. Changing context, starting a replacement operation or cancelling observation must prevent an old completion from opening another build or repainting the current selection. A saved-metadata fast path must still match the exact intended context; a saved label alone is insufficient. Missing metadata and a failure/retry path should be independently reviewable in the prototype. A disclosed US preferred route is compatible with a build advertised in several regions; do not present that routing preference as an independently different build or erase the other regions’ provenance.

The prototype uses authored fixtures. A destination with a single fixed build must only accept that matching fixture context; unsupported selections must remain explicit rather than being relabeled as the supported example.

## Status hierarchy

Use one combined overall status in the chooser and mining-home header:

- **Not ready**: required metadata is missing or incomplete.
- **Partial**: required metadata is complete, but none or only some of the selected content is retained.
- **Fully downloaded**: required metadata is complete and every object in the declared content scope is retained and verified.

Keep the selected scope explicit, including platform and locale. Fully downloaded does not establish that every format is decoded or supported. Downloading, Paused and Needs attention are separate activity/attention indicators, not additional overall status values. An active download can coexist with Partial; incomplete required metadata always makes the overall status Not ready, even if some content is already retained.

This supersedes the earlier proposal of separate Metadata and Build content headline statuses. Detailed metadata/content facts remain available in context without competing with the combined status. In mining home, place STATUS next to LOCALE in the identity header; this intentionally revises the earlier header-field layout.

A local discovery observation is not proof that required metadata is ready. Derive the combined status from actual prerequisite readiness, including missing/error previews and opening progress. Metadata completion may transition Not ready to Partial without changing retained content. Needs attention identifies missing or failed verification and must not manufacture Fully downloaded from byte counts alone.

## Information and state requirements

- Keep publication state and checked remote availability separate from the combined local status. A region badge means advertisement, not available game content. Missing/error required metadata gives Not ready even if a discovery observation is stored locally.
- Show observation/check timestamps. Current pointers are not a complete historical catalog; avoid claiming that the highest numeric build is latest.
- Selecting a row and inspecting technical details must not initiate downloads. Open build explicitly authorizes its explained metadata prerequisite work; distinguish this from retaining game content.
- Keep selection visible during loading. On failure preserve prior results with their original check time and offer retry; do not turn failed refresh into an empty catalog.
- Explain empty results for the selected scope and offer clearing filters. Keep unknown/unmapped and unsupported states distinct.
- Put exact configuration hashes and source documents in contextual details, retaining access without making them first-use prerequisites.
- Follow Product dropdown → filters → results → details/action keyboard order. At 200% scale stack detail panels, preserve focus and do not use color as the only status indicator.

A region matrix is a useful advanced view for rollout investigation, but is a heavier starting experience than the Build Library. Browser prototypes establish a reviewable direction; production acceptance still requires real JavaFX screenshots, keyboard/scaling checks, asynchronous-state tests and relevant integration evidence.
