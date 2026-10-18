# VibeMiner licensing

The user selected **GPL-3.0-or-later** for original VibeMiner software in [issue #106](https://github.com/aircan80/vibeminer/issues/106). [LICENSE](../LICENSE) contains the complete GPL version 3 text; [NOTICE](../NOTICE) declares the “or later” choice and copyright attribution. This declaration does not relicense separately licensed third-party materials.

The license permits use, modification and commercial redistribution under its terms. Distributing covered source or binaries carries GPL notice and corresponding-source obligations. Keep the build scripts and exact source revision available alongside binary distributions; retaining a private repository does not make its source URLs publicly accessible. Recipients must actually have the necessary access to the corresponding source. Do not substitute a link to a moving branch for the source used by a particular binary.

## Independently licensed materials

Unmodified WoWDBDefs definitions/data retain **CC BY-SA 4.0**, with complete upstream license, attribution, immutable revision and modification notices bundled alongside those resources. Its designated upstream code uses BSD-3-Clause; no upstream parser code is copied into VibeMiner. Adapted definitions, if redistributed later, need treatment under their own license terms; the project GPL declaration does not erase those obligations.

OpenJDK, JavaFX, SQLite JDBC and other dependencies retain their licenses and upstream notices. Preserve bundled runtime legal directories and dependency JAR notices. The canonical Excavate artwork and independently authored project documentation are project materials; third-party notices continue to take precedence for separately identified resources. The GPL declaration covers no downloaded Blizzard game content.

## Packages and corresponding source

Each native app image includes the project LICENSE, NOTICE and a SOURCE.txt identifying the exact source commit and build instructions. Normal Git/CI builds obtain that identity from the supplied revision or actual Git checkout. Source-archive builds must supply their known revision rather than inventing provenance. Uncommitted local builds must be labelled as such; a base-commit URL alone does not describe their changed source.

This project remains private unless the owner separately changes repository visibility. GitHub source archives require repository access under the current configuration. When distributing to additional recipients, provide corresponding source they can access under GPL terms.

Sources: [GNU GPLv3 text via OSI](https://opensource.org/license/gpl-3.0), [upstream WoWDBDefs licensing](https://github.com/wowdev/WoWDBDefs/blob/master/LICENSE.md), and the exact pinned copies distributed in this repository. The standard GPL text was retrieved through GitHub's license API because the GNU text endpoint was unavailable during setup.
