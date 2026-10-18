# Project documentation

These versioned documents are the formal project specifications. A clean checkout contains the material needed to continue development; GitHub Issues records current delivery status.

Released baseline: [alpha.3](https://github.com/aircan80/vibeminer/releases/tag/v0.1.0-alpha.3) at `2382261` covers discovery, saved offline metadata, install-list inspection and exact-root FileDataID metadata lookup. Later source `b3a5e01` passed [four-platform CI](https://github.com/aircan80/vibeminer/actions/runs/39108817700). Subsequent backend/runtime source `7035fd3` passed 688 Java cases, Linux bundled-runtime checks, five native UI harnesses and eight offline protocol entrypoints; seven candidate image comparisons matched references exactly. Its newer four-platform validation is pending. Candidate inspection is runtime-enabled and selected encoded-payload retention has a backend API; final payload GUI (#77) and exact-variant offline API (#78) remain in progress. One bounded diagnostic joined two Item/ItemSparse records without full-table CKey verification. None of this establishes general item research, decoded-content verification or GC.


- [Implementation plan and milestones](PLAN.md)
- [Layered architecture and contracts](ARCHITECTURE.md)
- [CDN discovery and acquisition](CDN.md)
- [Recorded official metadata evidence](PROTOCOL-EVIDENCE.md)
- [Shared storage and historical archives](STORAGE.md)
- [Encoded-file retention proof boundary](ENCODED-RETENTION.md)
- [Approved selected encoded-file acquisition design](FILE-ACQUISITION.md)
- [WoW formats, schemas and research search](FORMATS.md)
- [Exact Item cohort, compatibility and proof coverage](ITEM-COHORT.md)
- [Atlas UI specification and screenshot acceptance](UI.md)
- [Research workflows and state coverage](WORKFLOWS.md)
- [Test strategy and quality gates](TESTING.md)
- [Building, downloading and publishing packages](../BUILDING.md)
- [Interactive Atlas design reference](../mockups/atlas/index.html)

Specifications describe intended behavior, not proof that every capability is implemented. Refer to the issue acceptance evidence and actual checks before claiming support. Informal scratch notes and private logs are not build inputs or formal specifications.
