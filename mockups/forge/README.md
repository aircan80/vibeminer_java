# Forge — option 1

A premium, compact investigation workspace: graphite, amber, monospace evidence, and persistent source/build context. Designed as a JavaFX desktop interface with a 940px minimum width. Open index.html directly; no server or dependencies required.

Complete navigation includes overview/build archive, baseline comparison with added/changed/removed filters and field differences, DB2 tables and unknown-schema state, six asset types (original model/texture/map placeholders, audio, text, encryption), global search, watchlists, a session notebook with editable notes and saved evidence, a CDN build archive with provenance, downloadable CSV/JSON exports, cancellable/retryable jobs, settings, and onboarding. Use the header state selector for offline, loading, error and empty experiences. Press / for search; Escape closes dialogs.

All builds, counts, records, previews, archive manifests and activity are explicitly fictional. Search and status filters operate on demo records; downloads contain actual demo CSV/JSON. There is no connection to Blizzard. Asset previews are original SVG placeholders. Controls demonstrate intent locally; settings and created watchlists are retained for the current page session only. Audio playback is simulated. The prototype tests information architecture and investigation flows, not real CDN integration.

Validation: Node syntax check passed. All 11 screen render functions executed against a minimal DOM harness, and 93 generated screen event handlers plus all six record inspector handlers passed JavaScript parsing. Browser visual verification was unavailable in this restricted environment.

CDN-only archive workflow: no game installation reads, game cache imports, or local game fallback. Build archive distinguishes retained, remotely available, downloading/cancelled, and unavailable historical builds. Download selected old content, finish simulated verification, cancel/retry, pin retained builds, inspect manifest/source, reopen offline, and confirm deletion of unpinned content. Storage belongs to VibeMiner.

Archive deduplication is explicit: 8.4 GB logical build data occupies 6.2 GB of shared physical objects. Build manifests reference a content-addressed store; identical data is never duplicated in the archive or caches. Download cards show additional unique bytes versus reused objects. Deletion confirms only unique unreferenced objects are reclaimed while other retained builds keep their shared objects.
