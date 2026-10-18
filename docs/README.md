# Project documentation

These versioned documents are the formal project specifications. A clean checkout contains the material needed to continue development; GitHub Issues records current delivery status.

Released baseline: [alpha.3](https://github.com/aircan80/vibeminer/releases/tag/v0.1.0-alpha.3) at `2382261` covers discovery, saved offline metadata, install-list inspection and exact-root FileDataID metadata lookup. Later source at `6843b10` passed [four-platform CI](https://github.com/aircan80/vibeminer/actions/runs/39106673392). Pure parsing/streaming foundations are ahead of the released UI; one bounded live diagnostic joined two Item/ItemSparse records without full-table CKey verification, and production selected-file acquisition (#71–#73) is in progress. Encoded-file verification does not establish decoded CKey or readable rows.

- [Implementation plan and milestones](PLAN.md)
- [Layered architecture and contracts](ARCHITECTURE.md)
- [CDN discovery and acquisition](CDN.md)
- [Recorded official metadata evidence](PROTOCOL-EVIDENCE.md)
- [Shared storage and historical archives](STORAGE.md)
- [Encoded-file retention proof boundary](ENCODED-RETENTION.md)
- [Approved selected encoded-file acquisition design](FILE-ACQUISITION.md)
- [WoW formats, schemas and research search](FORMATS.md)
- [Atlas UI specification and screenshot acceptance](UI.md)
- [Research workflows and state coverage](WORKFLOWS.md)
- [Test strategy and quality gates](TESTING.md)
- [Building, downloading and publishing packages](../BUILDING.md)
- [Interactive Atlas design reference](../mockups/atlas/index.html)

Specifications describe intended behavior, not proof that every capability is implemented. Refer to the issue acceptance evidence and actual checks before claiming support. Informal scratch notes and private logs are not build inputs or formal specifications.
