# Guided build discovery

Design reference for [issue #93](https://github.com/aircan80/vibeminer/issues/93). The selected implementation direction is **Guided discovery**. Earlier A/C discovery concepts remain comparison history; implement only the latest guided flow and [C build overview](../build-mining-home/index.html). See the [implementation plan](../../BUILD-BROWSER-IMPLEMENTATION.md), [stories](../../BUILD-BROWSER-STORIES.md) and [acceptance matrix](../../BUILD-BROWSER-ACCEPTANCE.md).

Open [the mockup](index.html) locally. It is self-contained and performs no network, persistence or real downloads.

1. Select a WoW product directly beneath “Which builds do you want to explore?” Options show exact ID and descriptive name. Selection automatically refreshes the illustrative build list; manual Refresh is secondary.
2. Browse a unified list using All / Local / Currently advertised. Local observations, publication and checked remote availability are separate facts. Unknown availability is not failure or absence.
3. Select an exact build. Matching identity-bearing references form one row with region badges; differing configurations remain separate. A preferred download source is optional and does not change equivalent build data.
4. Choose **Open Build**. Saved prepared metadata opens quickly; missing metadata shows acquisition/verification progress and then opens automatically. Failure allows retry. No separate Prepare metadata or Start mining actions appear.

One overall status is shown: **Not ready**, **Partial**, or **Fully downloaded**. Required metadata must be complete before Partial; Full additionally requires verified selected-scope retention. Operation activity is separate. The current Windows/enUS numbers and regional combinations are synthetic design fixtures, not evidence of real scope filtering or content availability.

## Preview controls and limits

The toolbar previews initial, ready, loading, partial-regional-error and offline states. `?concept=guided` selects the agreed layout. `?metadata=missing` exercises automatic preparation; `?metadata=error` fails the first attempt and allows retry. `?content=none|partial|downloading|paused|full|incomplete` changes the authored content state and is preserved on opening home.

Only the authored `wow` / 12.1.5.70448 / configuration a1 / preferred US fixture has a matching home. Other product choices demonstrate selection and honest missing fixtures; they never substitute the Retail example. The exact metadata/configuration strings remain synthetic. Source labels have [separate naming evidence](PRODUCT-NAMES.md).

JavaScript syntax and real Firefox interactions checked product refresh, saved fast-open, missing-metadata preparation, failure/retry and exact home navigation. [The guided screenshot](guided.png) shows the current 1440×1100 layout. Browser checks do not qualify production JavaFX behavior or cross-platform accessibility. See the [usability review](REVIEW.md).
