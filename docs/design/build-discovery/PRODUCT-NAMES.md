# Warcraft product names for discovery designs

Research checked **2026-10-18**. Design evidence only; no production registry or protocol identity changes. Friendly names can lead the interface, with the exact discovered product code in secondary text/details.

## Evidence and confidence

These are primary sources for the maintainers’ **own** mappings: wowlib documents its client-flavor defaults; BlizzTrack publishes its own named product catalogue and per-product pages. They are community implementations, **not Blizzard-authenticated marketing metadata**. Their names support useful presentation aliases, not claims about availability, current launcher status, encryption, schema compatibility, or historical identity.

[Wowlib’s flavor documentation](https://skarndev.github.io/wowlib/python/core/) explicitly associates Retail, Classic progression, Classic Era and Anniversary with `wow`, `wow_classic`, `wow_classic_era` and `wow_anniversary`. It treats PTR/beta/one-off codes separately. This is strong implementation evidence for broad flavor aliases; it does not establish every test branch’s current expansion.

## Proposed names

“High” below means direct support in the named implementation’s documentation/catalogue, not official Blizzard certification. “Current label” means date-sensitive catalogue interpretation.

| Observed code / catalogue entry | Proposed display | Evidence / confidence |
| --- | --- | --- |
| `wow` | **World of Warcraft · Retail** | [wowlib](https://skarndev.github.io/wowlib/python/core/), high broad-flavor confidence |
| `wow_classic` | **WoW Classic · Progression** | [wowlib](https://skarndev.github.io/wowlib/python/core/), high; [BlizzTrack catalogue](https://blizztrack.com/en) currently names Mists of Pandaria Classic |
| `wow_classic_era` | **WoW Classic Era** | [wowlib](https://skarndev.github.io/wowlib/python/core/), high broad-flavor confidence |
| `wow_anniversary` | **WoW Classic · Anniversary** | [wowlib](https://skarndev.github.io/wowlib/python/core/), high; [BlizzTrack entry](https://blizztrack.com/view/wow_anniversary) currently names Burning Crusade Anniversary |
| `wowt` | **World of Warcraft · PTR** | [BlizzTrack entry](https://blizztrack.com/view/wowt), high current-label confidence |
| `wowxptr` | **World of Warcraft · PTR · Alternate source** | [BlizzTrack entry](https://blizztrack.com/view/wowxptr) also says PTR; “Alternate source” is our disambiguating presentation, not an upstream branch name |
| `wow_beta` | **World of Warcraft · Beta** | [BlizzTrack entry](https://www.blizztrack.com/view/wow_beta) currently names Midnight Beta; prefer broad Beta across future expansions |
| `wow_classic_era_ptr` | **WoW Classic Era · PTR** | [BlizzTrack catalogue](https://blizztrack.com/en) identifies this code as PTR; Era grouping is a presentation inference from the exact source family |
| `wow_classic_ptr` | **WoW Classic · PTR** | [BlizzTrack entry](https://blizztrack.com/view/wow_classic_ptr) currently says Wrath of the Lich King Classic PTR; avoid freezing that expansion into a permanent alias |
| `wow_classic_beta` | **WoW: Forever · Test source** | [BlizzTrack entry](https://blizztrack.com/view/wow_classic_beta) currently says WoW: Forever. Forever is source-attributed; “Test source” is our cautious grouping, not a literal channel field |
| `wow_cn_beta` | **WoW: Forever · Beta** | [BlizzTrack catalogue](https://blizztrack.com/en), current label; do not infer that all returned rows are China-only |
| `wowf` | **WoW: Forever · Stress Test** | [BlizzTrack entry](https://blizztrack.com/view/wowf?type=versions), current label; retain source/date attribution |
| `wow_classic_titan` | **WoW Classic · Titan Reforged** | [BlizzTrack catalogue](https://blizztrack.com/en), current label; not proof that VibeMiner can acquire/decode it |

These names immediately support readable game/source cards. A compact source note can say “Name from wowlib / BlizzTrack; checked 18 Oct 2026.” Show exact raw code for disambiguation when multiple sources share a friendly name.

## Conflicts and evolving codes

BlizzTrack currently labels `wowf` Stress Test, while the Warcraft Wiki interpretation supplied during design review described Forever PTR. Its Classic PTR expansion naming also differs from that secondary interpretation. We did not use that secondary source as independent verification. Prefer the broad stable Classic PTR alias, and mark the Forever suffix as a current BlizzTrack label rather than a timeless protocol fact.

The catalogue also prints entries such as `wow_anniversary (wow_classic_anniversary)` and `wowt (wow_ptr)`. Those are display/identifier pairs on that site, not authorization to rewrite the exact code returned by Blizzard discovery or to merge codes. Never derive a replacement endpoint or CAS namespace from a friendly alias. A preserved historical observation keeps its original source identity even if a presentation label later changes.

## Unknown sources and build-list boundaries

Unknown IDs remain selectable under **Other Warcraft sources**, with **Warcraft source · `<exact code>`** as the fallback. Internal/vendor/event sources belong in an advanced group, not silently labelled live retail. Do not identify game families solely from a substring unless that grouping is explicitly marked tentative.

A label registry should keep exact lookup key, display family, optional display channel, evidence URL/date and confidence. It changes presentation only. The existing `/versions` channel value `Published` remains a source fact; a friendly PTR/Beta label is separate. A summary `bgdl` pointer does not establish that a specific preload build has been fetched, is downloadable, or is decodable.

A build list may show the published version/build identifier, observed time, regions and exact configuration details. Availability remains **Not checked** until separately evidenced. An “All regions” presentation must preserve per-region snapshot/configuration/CDN-route facts and partial failures; matching version text alone cannot merge identities. This naming research establishes neither historical ordering nor an exhaustive catalogue of builds.
