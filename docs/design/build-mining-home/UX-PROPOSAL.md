# Build mining home: usability proposal

Design discussion tracked by [issue #94](https://github.com/aircan80/vibeminer/issues/94), related to the [build-discovery review #93](https://github.com/aircan80/vibeminer/issues/93). Following review, the user selected **C · Build overview** as the direction for the next mockup revision. This is design agreement, not implemented capability or production acceptance. [The discovery mockup](../build-discovery/index.html) supplies the entrance to this page.

## Purpose

After selecting an edition, exact build and regional source, the user prepares its required metadata and enters that build's mining home through **Start mining**. Metadata preparation precedes the ready mining home; the revised discovery flow makes that transition explicit. The home answers three questions: **Which build am I studying? What is ready and available locally? Does anything need attention?** Research destinations belong in the persistent menu.

Do not ask the user to create or name a workspace. Return to the same build opens its existing mining state, downloaded references and findings. Locale remains an explicit research filter; it does not create a duplicate physical build archive. Exact underlying identities remain preserved even where friendly labels are primary.

## Recommended page hierarchy

1. **Persistent build header.** Keep the VibeMiner logo and overall layout, remove the breadcrumb, and use explicit labels: **Game / edition**, **Product ID**, **Channel / region**, **Exact build**, **Locale**, and **Status**. The latest user direction places one combined Status field immediately beside Locale, replacing the earlier separate metadata/content strip. Platform is not a required header field. Provide Change build and Source details. The disclosure reveals full configuration identities and observation receipts. The channel label must distinguish actual source classification such as Published from a friendly display alias such as Live; the alias does not establish an upstream channel identity. A clear Back to builds link returns to the known fixture in the prototype; production should preserve discovery filters and exact selection.
2. **One combined status, with a separate download mode.** **Not ready** means required metadata is absent or incomplete. **Partial** means required metadata is ready but some or no content is retained. **Full** means all content in the explicitly chosen scope is retained and verified. These are derived states, not manually chosen labels. Full is scoped to the selected content and does not imply every locale/platform, successful decoding, readable encryption or live upstream availability. The Status value is plain text, not a badge. Beside it, show this build's **On demand / Download all** mode and separate activity/progress text. Downloading, paused, waiting for connection and failures do not create additional readiness values.
3. **One local-content overview.** Show verified local payload referenced by this build, its shared subset, known missing encoded payload and separate metadata/derived overhead. Every metric states its scope and whether it is complete, partial or unknown. Do not repeat the same numbers in a statistic-card row and another storage panel. Do not make the home depend on expensive full scans or downloads.
4. **Metadata readiness.** Identify what is prepared, incomplete or needs attention. Keep the main summary readable, with component details available on request. Metadata readiness, current upstream availability and decoder support remain different facts.
5. **One download policy control.** Default to **On demand**. The per-build **Download all** choice queues background retention for the explicitly selected scope while normal research remains usable. Keep the mode control beside the header status, with activity details and relevant job controls in the body; do not repeat the selector as a large second call to action. Show a percentage only when the selected-scope denominator is established, and identify retained/reused coverage separately from actual network transfer progress.

Mode, readiness and activity are independent. A fully retained build can use On demand without losing content; a partially retained build can use Download all while paused or offline. Changing back to On demand stops or pauses only bulk retention, preserving existing shared/local objects and separately requested on-demand work. It never deletes content, releases another build's references or claims reclaimed space. Turning Download all on again continues from retained content rather than restarting its counters. The mockup's chosen scope is Windows / enUS; “all” does not silently add other platforms or languages. A first enablement should disclose scope, known additional bytes and unresolved costs before bulk acquisition; the browser prototype performs no transfer or persistence.

**Files you open take priority.** While Download all runs, work required for the current view takes precedence over bulk work. A shared object requested by both uses one promoted download, not duplicate transfers. The scheduler reserves interactive capacity and lets bulk work yield at bounded chunk/request boundaries; this is not a promise to interrupt a network operation instantly or make unavailable content available. [Story #96](https://github.com/aircan80/vibeminer/issues/96) tracks the policy. Any mock foreground-request button demonstrates scheduling intent only and is not a production scheduler acceptance test.

Do not add research-category cards, repeated menu buttons, recent-finding feeds, universal search or a second launchpad to this home. Those add repetition without answering its purpose. Contextual actions such as retrying a missing metadata component belong beside the state they address.

The first screen should have one visual center of gravity: a clear overview of this exact build. The persistent menu provides routes into research. Follow Atlas ivory surfaces, teal actions, serif headings and generous but efficient spacing. Show the raw product ID near its friendly label; keep longer protocol hashes in inspectable, copyable details.

## Research route language

| Route | User promise | Honest supporting detail |
| --- | --- | --- |
| Browse files | Explore the build's catalog and inspect a file | Names where known; numeric IDs remain first-class; downloads happen when required |
| Search supported data | Find records the current decoder can interpret | Name the available tables, locale, indexed scope and schema support; do not imply universal WoW search |
| Compare another build | Investigate added, removed or changed content | Select a compatible exact build; missing inputs are incomplete comparisons, never automatic removals |
| Saved findings | Return to evidence and notes | Preserve exact build, locale and source context |

These are destination names and capability promises, not additional home cards. The eventual broad mining experience includes collectibles, item/spell changes, quests and story, art and audio, and developer tables. Their existence as planned destinations is not proof that each decoder exists. Unsupported research areas explain requirements without implying live results.

## Statistics and sharing

Avoid an ambiguous “Build size” or “Downloaded 23%.” A file catalog is not the same as a complete content-size inventory, and encoded storage differs from decoded file lengths.

- **Catalog files:** distinct FileDataIDs in the stated completely enumerated scope, where that metric is supported. Root variant entries and unique declared CKeys are different optional metrics. Do not sum root locale/content variants and call that the file count. Current selected-ID lookup does not establish a complete catalog total. If only a subset is enumerated, say “Files found so far” and identify the subset.
- **Logical content:** decoded file sizes for a defined locale/platform/content scope where established. If some lengths are unknown, show the known subtotal plus the unknown count. Do not infer the total from archive lengths.
- **Available locally:** distinct verified physical payload objects reachable by this build, counted once within that set. This includes shared objects. Say “Includes data shared with other builds”; it is not the build's exclusive disk cost.
- **Additional download:** distinct missing required representations and known wire lengths for the chosen download scope. Unknown lengths remain explicit. It is not logical content minus local physical bytes.

Detailed storage can additionally show whole-store payload, derived/index data, staging reservations, shared payload and estimated additional disk use. Never add per-build local totals into a store total: their shared sets overlap. “Shared” requires an identified comparison set, and removing a build is not evidence all its bytes are reclaimable.

WoW-specific interpretation also matters: a root name hash does not supply a readable filename; the install manifest is not the whole-game inventory; a declared section key does not by itself establish that content is unreadable; retained encoded bytes do not establish decoded readiness. Supported record metrics state physical-row/section scope, with unindexed copies and absent versus empty names distinguished. A client snapshot does not prove live server behavior, obtainable rewards or release timing.

Show units, scope and measured time in the detail disclosure. Use “Not calculated” for unmeasured values and “Unknown” for unresolved facts; neither becomes zero. A percentage requires a known, meaningful denominator and representation.

## First visit and exceptional states

| State | Home behavior |
| --- | --- |
| First metadata preparation | Keep the selected build visible; show named stages, measured bytes where known and indeterminate progress otherwise. Do not invent a completion percentage. |
| Required metadata unavailable | Explain the missing component and what remains possible; do not label the whole catalog ready. Retry only the relevant operation. |
| Catalog ready, data not downloaded | This is a normal successful home. Browse metadata now; files fetch on demand through disclosed download policy. |
| Partial catalog | Mark coverage prominently, identify unresolved scope, and avoid definitive no-match or total-file claims. |
| Offline with retained catalog | Open normally, say Offline, and keep local research available. Uncached content explains that a connection is required. |
| Offline without required metadata | Preserve build identity and source details; say which preparation step needs a connection. |
| Decoder unsupported | Catalog browsing remains available if its own prerequisites are supported. Disable only the affected interpreted research route. |
| Encrypted or unavailable content | Keep the file identifiable and distinguish the condition from unsupported format, missing local content and failed verification. |
| Full download active | Home stays usable. Job controls expose scope, transferred/reused bytes, pause/resume capability where actually supported, cancellation and failures. No promise of complete retention until the scope verifies. |
| Returning visit | Restore meaningful research context without silently initiating background downloads. Previous observations retain their check times. |

“Metadata ready” must have a defined completeness contract. It does not mean all files are available upstream, all tables are decoded, filenames are known, encrypted content is readable, or all data is local.

## Interaction and accessibility

Keep edition/build/region visible when navigating to a research route. Region describes the selected source; locale describes the research content. Neither silently substitutes for the other. Search must explicitly state its target, such as Files or supported Item names, instead of a universal box that promises to search everything.

At 1280×800 retain the header, local-content overview and readiness without burying them below repeated navigation. At 200% text scale stack panels into a readable vertical flow; preserve full identity through an accessible details disclosure. Avoid fixed-height cards that clip status text. Keyboard order follows persistent navigation, header, overview, readiness actions and jobs; async progress must not steal focus. Badges include text, not color alone.

## Selected direction and superseded alternatives

- **C · Build overview — selected:** exact-build identity, one content/storage overview, metadata readiness and optional background-download controls. Research stays in the menu.
- **A · Researcher home — superseded:** research cards repeated the menu and displaced useful build information. Do not retain the earlier recommendation for A.
- **B · Catalog first — alternative destination:** file browsing remains useful as its own research destination, rather than the build home.

The revised discovery and selected C home must use the same explicitly fictional exact-build fixture across their transition, including raw product, edition/channel, version, region and requested locale. Selecting another regional source must not silently reopen the previous one. Show preparation, ready, partial and offline examples. A static or browser design is not production JavaFX or platform acceptance evidence.
