# Project documentation

These versioned documents are the formal project specifications. A clean checkout contains the material needed to continue development; GitHub Issues records current delivery status.

[Alpha.6](https://github.com/aircan80/vibeminer/releases/tag/v0.1.0-alpha.6) remains the latest published release: exact `ee6598f0369266c236b6c0f051e7974458d33905`, annotated tag `d6ba492e67834cf13b67daa9c43efb88c3734b1d`. [Publication run 39127913086](https://github.com/aircan80/vibeminer/actions/runs/39127913086) passed all four native targets and eleven Linux UI workflows; all 12 final assets were independently verified. Intel `es2,sw`, unsigned/not-notarized package limits and the [release scope](releases/v0.1.0-alpha.6.md) remain unchanged.

The latest completed cross-platform development checkpoint is exact `5904239d364f7fde58fa75fe13fb60ccd24fd3b6`: [CI 39150531602](https://github.com/aircan80/vibeminer/actions/runs/39150531602) passed all four native targets and thirteen Linux UI workflows, including the new integrated guided discovery matrix. The twelve earlier workflows remain legacy regressions. This is development qualification, not a new release; #91 JSON export remains qualified source outside alpha.6.

Earlier exact `be03a8e` passed a clean immutable full check with **1,707 Java cases**, Linux packaging, bundled-runtime verification and JavaFX launch. Guided Discover is now connected to the real application service and SQLite history/library through Atlas. Its authored-source native journey passed four size/scale cases with **320 assertions** and independent visual review: [integrated discovery evidence](design/guided-discovery-integration/README.md). It performs no HTTP or game-content acquisition in that controlled harness. Open Build is capability-disabled until the complete metadata gateway is connected; C overview, authoritative payload inventory and background Download all are unfinished. The subsequent `5904239` CI run also passed the guided journey at both sizes and text scales. Later opening, overview and inventory work is not covered by that checkpoint. #92 history backend/runtime is accepted, while full issue acceptance remains open.

Current implementation is [milestone 5](https://github.com/aircan80/vibeminer/milestone/5): guided Discover → exact Open Build → only C overview, backed by real metadata/catalog/storage and On demand/Download all. Open requires the complete declared metadata proof closure; unknown or unfetched required metadata is Not ready. Only derived payload inventory/indexing may still be Preparing after C opens. Full requires the complete declared ROOT/IN/applicable DL content/dependency closure, not a convenient supported subset. [Milestone 6](https://github.com/aircan80/vibeminer/milestone/6) selects top navigation/Tables/optional FK labels only after milestone 5. Locale #114, asynchronous request #115, semantic research #116 and entity comparison #117 remain future work and do not expand milestone 5.

- [Verified alpha.6 release notes](releases/v0.1.0-alpha.6.md)
- [Verified alpha.5 release notes](releases/v0.1.0-alpha.5.md)
- [Verified alpha.4 release notes](releases/v0.1.0-alpha.4.md)
- [Project license and third-party materials](LICENSING.md)
- [Implementation plan and milestones](PLAN.md)
- [Guided discovery and C overview implementation](BUILD-BROWSER-IMPLEMENTATION.md)
- [Build browser delivery stories](BUILD-BROWSER-STORIES.md)
- [Build browser acceptance](BUILD-BROWSER-ACCEPTANCE.md)
- [Complete declared metadata required to open a build](BUILD-OPENING.md)
- [Declared manifest inventory formats and admission boundaries](BUILD-INVENTORY-FORMATS.md)
- [Selected Tables and top-navigation delivery stories](TABLES-STORIES.md)
- [Optional table relationship labels and proof limits](TABLE-RELATION-LABELS.md)
- [Future raw evidence and semantic research proposal](RESEARCH-EXPERIENCE-PLAN.md)
- [Future game-oriented research areas and source evidence](RESEARCH-AREAS.md)
- [Qualified-source single Item JSON evidence export](ITEM-EVIDENCE-EXPORT.md)
- [Exact discovery observation history and remaining integration](DISCOVERY-HISTORY.md)
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
