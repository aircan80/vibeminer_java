# Atlas — option 2

An editorial investigation workbench for VibeMiner. Warm ivory, teal ink, generous whitespace, serif headings, horizontal navigation, and persistent contextual evidence distinguish it from an IDE or dashboard.

Open `index.html` directly in a browser. Everything is self-contained; no network or dependencies. This is a browser-based desktop interface concept for JavaFX, not an implemented mining engine.

## Included workflows

Workspace overview, first-run setup, build library and source details, simulated old-build download queue, exact CDN snapshot provenance and independent build archive, field-level comparisons, DB2 table filtering and relational navigation, model/texture/audio/map/text previews, global search, persistent local notes, investigation creation, watchlist configuration, downloadable JSON/CSV exports, cancellable/retryable jobs, and cache/locale/definition/spoiler settings.

Use the footer state selector to inspect ready, loading, offline, error, empty, encrypted, and unknown-schema variants. All builds and data are explicitly fictional. Asset illustrations are original SVG; audio is a short synthesized tone.

## Interaction notes

- Press `/` for global search; Enter opens results. Escape closes dialogs.
- Notes persist in browser localStorage. Export buttons download actual sample files.
- Table filters, change filters, table selection, relation links, preview tabs, model rotation, audio playback, job cancellation/retry, modal creation/import controls, and settings feedback are interactive.
- Some creation operations deliberately use representative fictional data. A production app would wire them to the mining backend and durable storage.
- Responsive at narrow widths; controls use semantic buttons, labels, accessible names, and visible keyboard focus.


## Independent CDN archive

No reading game installations, Battle.net, or game caches; no imports. Builds come only from Blizzard CDN into VibeMiner-owned storage. Archive states distinguish downloaded, remotely available, downloading, and unavailable content. Download, complete demo job, cancel/retry, inspect exact source, reopen archived builds offline, pin retention, and cleanup confirmation are interactive.

Build manifests reference a shared content-addressed store. Identical bytes are stored once across versions and caches. UI distinguishes logical size, unique storage/downloads, shared content, and reclaimable space. Cleanup frees only unreferenced objects and retains shared bytes required by other builds.
