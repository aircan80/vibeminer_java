# Project documentation

These versioned documents are the formal project specifications. A clean checkout contains the material needed to continue development; GitHub Issues records current delivery status.

Published [alpha.5](https://github.com/aircan80/vibeminer/releases/tag/v0.1.0-alpha.5) is exact `f3c4d46e42451bfc7941959b862143a462a3ddd6` (annotated tag `2cc1af4`). All four native packages and all 12 freshly downloaded assets were independently verified against manifests, SHA-256, targets and byte counts; [publication run 39119447852](https://github.com/aircan80/vibeminer/actions/runs/39119447852) passed. See [release notes](releases/v0.1.0-alpha.5.md) for scope. Explicit retained metadata reuse is released; names, repair execution and GC remain unfinished. Later source foundations do not alter the exact release contents.


Later accepted local `c17ec25` adds literal-name-search controls and repair qualification (1,333 Java cases, verified Linux package 24, Noto/DejaVu search and repair native checks). The `62513a2` four-platform/eleven-workflow gate passed and scoped #83/#89 are closed; broad #21 retains relations/official-run work and #91 export/#92 metadata history remain planning-only until the next alpha checkpoint. These features are not in alpha.5. See the [current checkpoint](PLAN.md).

- [Verified alpha.5 release notes](releases/v0.1.0-alpha.5.md)
- [Verified alpha.4 release notes](releases/v0.1.0-alpha.4.md)
- [Implementation plan and milestones](PLAN.md)
- [Planned single Item JSON evidence export](ITEM-EVIDENCE-EXPORT.md)
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
- [Approved bounded literal Item-name search](ITEM-NAME-SEARCH.md)
- [Literal name-search native visual references](design/item-name-search/README.md)
- [Bounded Item name projection and exact-context joins](ITEM-NAMES.md)
- [Atlas UI specification and screenshot acceptance](UI.md)
- [Research workflows and state coverage](WORKFLOWS.md)
- [Test strategy and quality gates](TESTING.md)
- [Building, downloading and publishing packages](../BUILDING.md)
- [Interactive Atlas design reference](../mockups/atlas/index.html)

Specifications describe intended behavior, not proof that every capability is implemented. Refer to the issue acceptance evidence and actual checks before claiming support. Informal scratch notes and private logs are not build inputs or formal specifications.
