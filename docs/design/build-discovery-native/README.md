# Guided Discover visual parity (#121)

## Status: independently accepted local preview; not yet released

The approved reference is [guided concept B, ready state](../build-discovery/index.html?concept=B&state=ready). The reference's illustrative records are design inputs, not substitutes for actual services. C remains the only implemented build overview.

The reviewed application and capture sources are committed at `beac99ea192d64a5c1d60e69b144dffedec22fe9`. Local qualification used an isolated checkout of parent `f3b3b60` with those exact reviewed changes applied; its package correctly identifies the checkout as modified. The local preview is running that qualified package. Clean checks passed **2,188 Java tests** with zero failures/errors/skips and **40 Python tests**. Linux packaging, bundled-runtime verification/self-test and JavaFX smoke passed. These are local results, not final four-platform qualification or a published fix release.

Independent visual review accepted the final native mining matrix at 1440×1100 and 1280×800, each at 100% and 200% application text: **1,324 assertions**. Each 100% case passed 217 seed plus 106 restart assertions; each 200% case passed 225 seed plus 114 restart assertions. Fresh-JVM offline restarts made zero HTTP attempts. The standalone guided matrix passed **290 assertions**: 73 at each 100% size and 72 at each 200% size, with no configured HTTP/CAS and no legacy discovery calls. The actual metadata-opening matrix passed **128 assertions**: 32 at each size/scale, 13 authored HTTP requests per case and no game payload objects. Together these three workflows passed **1,742 assertions** across the four geometries.

The third reviewed 100% capture was an accepted intermediate checkpoint. A previously supplied 200% capture was stale and rejected; the capture harness now waits for three actual JavaFX pulses and verifies the intended visible target within the snapshot task. Earlier visual iterations failed search width, product label/facts, filter wrapping and badge overflow. Harness iterations also failed an obsolete navigation selector and a simultaneous row/inspector visibility assumption that contradicted the deliberate stacked layout. These failures were corrected before the final results; failed captures did not qualify the current source. Final corrected captures, including badge sizing, supersede that rejected evidence. This local acceptance does not close #121 or claim a qualified prerelease.

## Reported differences and review criteria

| Reported difference | Required final evidence |
| --- | --- |
| Palette and typography | Compare the actual paper/panel surfaces, borders, text and accent hierarchy with the browser reference at matching viewport sizes. Review native font metrics rather than claim pixel-identical browser text. |
| Discover outside the main bar | Show Discover in the main navigation bar, with the selected state and keyboard focus visible. |
| Missing introductory hierarchy | Show A SIMPLE STARTING POINT, the discovery question and supporting explanation in the approved order. |
| Product selector and panel | Show the product-first panel, readable friendly name and exact raw product ID; preserve real asynchronous refresh behavior. |
| Missing filters and different tiles | Show the actual full-catalog literal search, exact regional filter and unified list controls, with reference-aligned build tiles and independent New/local/advertisement/readiness facts. |
| Inspector | Show exact selected identity and source detail, plus the single Open Build action; preserve selection, wrapping and first-viewport action reachability. |

## Deliberate native adaptations

At 200% application text, the inspector stacks below the build list rather than compressing both into unusably narrow columns. This is an explicit readability adaptation, to be checked for scrolling, keyboard reachability and preserved context. It does not authorize omitting inspector data or introducing another Open action.

JavaFX and browser font metrics differ. Review readable hierarchy, weight, wrapping and baseline spacing at the same viewport and application text scale; do not describe ordinary native text rasterization as exact browser pixel parity. Badge geometry must accommodate its native font without clipping.

Search and region filtering operate on the real saved catalog, including matches beyond the current visible page. Literal query and exact regional identity must remain truthful. Coalesced asynchronous search must fence stale results and retain responsive input; UI rendering performs no SQL, downloads or parsing. Selection alone does not hash or fetch content. Dated readiness remains a historical observation, distinct from current Open/coverage verification.

## Evidence and remaining gates

The review compares the browser reference and actual JavaFX application at 1280×800 and 1440×1100, at 100% and 200% application text. Selected final captures are linked below and their bytes are recorded in [SHA256SUMS](SHA256SUMS). Cross-platform CI and final release-asset verification remain separate gates. Compare side-by-side or by overlays and independently review every mismatch above, loading/empty/error/offline states, keyboard/focus, long identities, badge wrapping and inspector stacking.

The production discovery/history/Open/C composition must remain connected throughout qualification. Authored service fixtures may qualify controlled interaction and visual behavior when labelled; they do not prove live CDN compatibility or real build availability. Final native/package/CI qualification and publication require their own actual results. No release claim is inferred from earlier workflow screenshots.

## Selected final captures

- [atlas-guided-selected-1440x1100-100pct](atlas-guided-selected-1440x1100-100pct.png)
- [atlas-guided-variants-1440x1100-100pct](atlas-guided-variants-1440x1100-100pct.png)
- [atlas-mining-seed-selected-1280x800-200pct](atlas-mining-seed-selected-1280x800-200pct.png)
- [atlas-mining-restart-chooser-partial-readiness-row-1280x800-200pct](atlas-mining-restart-chooser-partial-readiness-row-1280x800-200pct.png)
- [atlas-mining-restart-chooser-partial-readiness-inspector-1280x800-200pct](atlas-mining-restart-chooser-partial-readiness-inspector-1280x800-200pct.png)
- [atlas-mining-restart-chooser-partial-readiness-action-1280x800-200pct](atlas-mining-restart-chooser-partial-readiness-action-1280x800-200pct.png)
