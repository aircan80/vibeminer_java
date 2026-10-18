# Project documentation

These versioned documents are the formal project specifications. A clean checkout contains the material needed to continue development; GitHub Issues records current delivery status.

Released [alpha.3](https://github.com/aircan80/vibeminer/releases/tag/v0.1.0-alpha.3) remains `2382261`. Later `6fac722` passed 855 Java cases, Linux bundled-runtime smoke and [all four native targets](https://github.com/aircan80/vibeminer/actions/runs/39113600719). Its metadata/file publisher guard is active; strong A-versus-B reuse (#85), same-A metadata repair (#83), Item backend/index/UI, general research search and GC remain unfinished. Complete encoded verification remains distinct from decoded-content proof. All six native UI harnesses and nine offline protocol entrypoints passed, and all 17 candidate/retention PNG comparisons were exact (AE 0).


- [Implementation plan and milestones](PLAN.md)
- [Layered architecture and contracts](ARCHITECTURE.md)
- [CDN discovery and acquisition](CDN.md)
- [Recorded official metadata evidence](PROTOCOL-EVIDENCE.md)
- [Shared storage and historical archives](STORAGE.md)
- [Publisher ownership and startup fencing](PUBLISHER-OWNERSHIP.md)
- [Canonical root/install metadata and deduplication guard](METADATA-DEDUP.md)
- [Explicit original-metadata repair](ORIGINAL-METADATA-REPAIR.md)
- [Encoded-file retention proof boundary](ENCODED-RETENTION.md)
- [Approved selected encoded-file acquisition design](FILE-ACQUISITION.md)
- [WoW formats, schemas and research search](FORMATS.md)
- [Exact Item cohort, compatibility and proof coverage](ITEM-COHORT.md)
- [Approved offline physical Item-row projection](ITEM-PROJECTION.md)
- [Bounded Item name projection and exact-context joins](ITEM-NAMES.md)
- [Atlas UI specification and screenshot acceptance](UI.md)
- [Research workflows and state coverage](WORKFLOWS.md)
- [Test strategy and quality gates](TESTING.md)
- [Building, downloading and publishing packages](../BUILDING.md)
- [Interactive Atlas design reference](../mockups/atlas/index.html)

Specifications describe intended behavior, not proof that every capability is implemented. Refer to the issue acceptance evidence and actual checks before claiming support. Informal scratch notes and private logs are not build inputs or formal specifications.
