# Option 3 — Observatory

Open `index.html` directly, or use the parent concept gallery. This browser-based desktop prototype uses original SVG art and local demo data; it never contacts Blizzard or reads game installations.

Midnight blue, cyan and lilac visual research studio: discovery overview, CDN build archive, comparisons, DB2 records/schema/relationships, asset previews, search, investigations, watch rules, JSON/CSV exports, jobs and settings. Shared research home/search enhancements are provided by the parent directory.

## Standalone archive contract

Blizzard CDN is the sole acquisition source. VibeMiner keeps its own archive; no installation browsing, game cache reading or hotfix imports exist. Build catalog separates retained snapshots, remotely available older builds and unavailable remote content. Download queues expose progress/cancel/retry and verified completion. Archived builds reopen offline; pinning protects retention. Cleanup asks for confirmation.

Build manifests reference one content-addressed object store. Identical bytes are stored once across versions, including cache usage. UI separates logical build size, shared objects, unique additional download and exclusive/reclaimable bytes. Removing manifests only reclaims objects no remaining build references; shared objects remain retained. Numbers are illustrative, not measured.

## Prototype behavior

All builds, records, hashes and results are fictional. Dialogs, filters, navigation, original preview tone, model rotation, export downloads, investigation notes, watch rules and preferences work within the page session. CDN downloads and archive verification are simulated via explicit progress controls; no real backend or persistence is claimed. Metadata alone cannot restore an older build whose CDN content is unavailable.

Validation: Node JavaScript compilation and VM smoke render checks cover all workspaces, alternate states and dialogs. Host browser rendering was unavailable.
