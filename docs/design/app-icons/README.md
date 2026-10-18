# VibeMiner application icon proposals

Design review for [issue #97](https://github.com/aircan80/vibeminer/issues/97). Open [the comparison page](index.html) locally; it needs no network, external assets or fonts. Its background toggle previews all nine icons on light and dark surfaces. Each card includes actual 16, 24, 32 and 48 pixel previews alongside a 128 pixel view.

These are original standalone SVG proposals with a `0 0 256 256` view box. Atlas deep teal, mint, ivory and restrained gold connect them to the application. No proposal uses Blizzard or Java branding. The user selected **02 · Excavate**. Its canonical production source and generated platform assets are in [branding/](../../../branding/README.md); native integration is verified separately.

| Option | Direction | Idea |
| --- | --- | --- |
| 01 | [Facet](01-facet.svg) | A crystalline V. Distinctive, compact, and closest to the VibeMiner name. |
| 02 | [Excavate](02-excavate.svg) | A bold mining tool cutting into a small bank of data blocks. |
| 03 | [Strata](03-strata.svg) | Layered archives with a bright exposed seam: builds over shared content. |
| 04 | [Reveal](04-reveal.svg) | A search lens around a raw data facet, with a strong exploration silhouette. |
| 05 | [Signal V](05-signal-v.svg) | An abstract V and M form a continuous signal through the data. |
| 06 | [Lattice](06-lattice.svg) | A connected content cube: shared objects, relationships, and discovery. |
| 07 | [Threshold](07-threshold.svg) | An opening into an unexplored build, with a mineral at its center. |
| 08 | [Core](08-core.svg) | A precise voxel with an illuminated inner core: inspect what is inside. |
| 09 | [Bearing](09-bearing.svg) | A survey compass for navigating unknown datasets and exact builds. |

Facet, Strata and Reveal are the strongest starting points: Facet has the most ownable silhouette, Strata communicates shared layered data, and Reveal makes exploration immediately understandable. The other directions test more literal mining, abstract identity, relationships, discovery and navigation.

Excavate now has dedicated Windows ICO, macOS ICNS and Linux PNG exports plus JavaFX stage-image sizes. Platform masks, transparency, taskbar/dock rendering, pinned shortcuts and bundled installer behavior require separate native verification. The rounded teal tile has transparent outer margins. Generated exports do not by themselves prove native integration or optical pixel hinting.

Validation: all nine standalone SVG files parse as XML, have the expected view box and contain no script, embedded image, external URL reference, font requirement or external resource. The gallery references only the nine local SVG files and local notes. Browser screenshots provide visual review; runtime and packaging changes are outside this proposal.
