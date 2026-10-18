# C · Build overview

Selected mining-home design for [issue #94](https://github.com/aircan80/vibeminer/issues/94). Implement **C only**; A/B remain marked earlier concepts and are excluded. The selected journey is [guided discovery](../build-discovery/index.html) → **Open Build** → C overview. There is no separately managed user workspace.

Open [the home mockup](index.html) locally. All build facts and numbers are synthetic; no actual networking, retention or research occurs. See the [implementation plan](../../BUILD-BROWSER-IMPLEMENTATION.md), [stories](../../BUILD-BROWSER-STORIES.md) and [acceptance matrix](../../BUILD-BROWSER-ACCEPTANCE.md).

## Approved layout

- Excavate logo and labelled header: Game / Edition, Product ID, Channel / Region, Exact Build, Locale, **Status**, **Retained**, **Download mode**.
- Status is plain text: Not ready when required metadata is incomplete; Partial when metadata is ready with some or no payload; Fully downloaded when all required scoped content is verified retained.
- Retained percentage has its own column after Status. Show Unknown without a complete denominator. Download mode shows only policy/activity.
- The On demand / Download all switch is inside **Build readiness**. Switching modes preserves existing bytes and independent requests. Background retention must keep interactive mining responsive; no permanent priority explanation or demo control appears on screen.
- One storage/sharing breakdown, metadata readiness and actual job/problems information. Research navigation belongs in the menu; duplicate launcher cards are removed. Game Data, Files, assets, comparisons and findings are not implemented by this increment.

Scope is essential: encoded retention is different from decoded/decrypted readability, wire transfer and installed-game size. Shared bytes are a subset of local physical storage, not an extra copy. The implementation plan requires complete declared manifest closure; the mockup’s Windows/enUS example does not prove platform/locale filtering. First-seen/New and durable mode behaviors are planned backend requirements rather than browser persistence.

## Preview and evidence

Default `?concept=archive` selects C. `?state=ready|partial|offline|downloading` and `?content=none|partial|downloading|paused|full|incomplete` illustrate coverage and jobs. `?mode=all|ondemand` controls initial policy. Offline transfers wait/are paused; unknown metadata prevents a false full percentage. The same exact authored build context is used by discovery and home.

Real Firefox interaction checks confirmed saved/missing/error Open Build paths and mode switching without resetting the retained 18.6 GiB fixture. Source review checked full118.4GiB/remaining0, shared subsets and paused/offline behavior. The removed priority demo was used for a local interaction check before its removal; actual scheduling remains [story #96](https://github.com/aircan80/vibeminer/issues/96), not exercised by a mockup.

[Current C screenshot](archive.png), [UX proposal](UX-PROPOSAL.md) and [WoW research guidance](WOW-RESEARCH-PROPOSAL.md) provide design evidence. Native JavaFX screenshots, keyboard/scaling tests and matching-platform packages remain separate implementation gates.
