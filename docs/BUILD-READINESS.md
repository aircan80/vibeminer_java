# Dated discovery readiness

This contract completes the Discover chooser's readiness display without turning its build library into a physical-file verifier. Acceptance wording is maintained in [BUILD-BROWSER-ACCEPTANCE.md](BUILD-BROWSER-ACCEPTANCE.md).

## Meaning and identity

`application.buildreadiness` supplies a separate SQL-only overlay for at most 100 visible contexts. A context contains the exact registered regional build reference, locale, metadata admission policy revision and inventory scope revision. The reader returns exactly the requested contexts, in order, or a typed failure; it never returns a truncated page. Duplicate regional build/policy contexts are refused.

An admission header that is absent establishes **Not ready** for that exact local metadata context. A present header establishes only that an admission was recorded. It does not prove current metadata or payload bytes and cannot produce current Partial or Full. Without a stored assessment, the latter case reads **Local readiness not checked**. Read failures are unknown/error states, not evidence of absent metadata.

A stored observation describes a completed past overview assessment: its actual readiness, measurement time, overview revision, original discovery event/sequence/row/regional identity, and optional actual assessed target. The chooser labels it **Last checked: Partial** or **Last checked: Fully downloaded**, with date and locale. Not-ready observations remain explicitly dated too. An absent current admission takes precedence in the row; the inspector may still explain the retained historical observation. The inspector explains that local files may have changed. Neither age nor a stored Full grants current readiness, download authority, a target, or a decoded-content proof.

Fresh discovery events may reuse the dated observation only for the same exact regional group/snapshot, locale, policy scopes, admitted closure and configured retention-policy identity. The original observation provenance is not rewritten. Other regions, locales, snapshots, closures and policies do not inherit the label. A target's inventory scope and the metadata admission policy are separate identities.

## Reads and storage

The infrastructure reader opens only the app's existing SQL catalog, read-only. It does not initialize a content runtime, acquire the CAS process owner, migrate/create a missing database, read stored game bodies, contact a CDN, enumerate all retained builds, or run a physical assessment. A missing app catalog has no complete local admissions. Older catalogs without the observations table have no recorded observations. Body-free registered-reference checks and explicit SQL/time/page limits apply; source bodies are not a fallback.

Schema 21 stores one latest minimal dated summary per context and configured retention-policy identity. It stores no game body, copied source proof, archive/cache duplicate, or new authority capability. Summary framing and integrity checks are bounded. Header/admission and actual issued-target bindings are checked as historical identity facts, without claiming a fresh physical verification. Quotas and malformed/unavailable storage have explicit failures. The shared catalog owner installs the schema; list reads do not migrate it.

## Recording and lifecycle

`RecordingBuildOverviewService` borrows the existing overview service and executor. It exposes the existing `BuildOverviewService` API. It never starts preparation, issues a target, downloads, repairs, or changes download mode. Normal Open and C's explicit refresh continue to use their existing actual overview assessment.

Only a **successful terminal completion** of the borrowed overview operation can mint `CompletedAssessment`. Its constructor and factory are not public. The production delegate completes after its actual verification/cleanup and worker retirement; intermediate COMPLETE progress, failed cleanup, cancellation, and caller-constructed snapshot DTOs cannot invoke the recording port. This receipt permits only an internal historical SQL write, never mining/readiness authority.

The wrapper has at most four accepted overview/recording operations. Each slot remains occupied through the actual underlying operation and any queued/running recording task. The separate visible-page service has at most four actual SQL workers. Cancellation and close do not release either kind of slot before actual retirement, and they do not close borrowed delegates or executors. Failed cancellation requests cannot stand in for completion. Runtime shutdown cancels wrappers and separately retires their actual owners before closing shared storage.

SQL recording runs off the JavaFX thread on the borrowed executor. Terminal progress is withheld until the recording phase retires. A best-effort ordinary recording failure leaves the actual successful overview result intact and creates no synthetic saved observation; a previous dated record remains dated. Fatal recording errors retire the wrapper and fail its future rather than silently converting into successful recording. Cancellation after a commit cannot revoke an already committed, genuinely completed historical assessment; no global nonpublication guarantee is made.

Selecting or scrolling a row never starts a metadata/body hash, payload assessment, preparation, target issuance, or download. The overlay is separate from the unchanged body-free `BuildLibraryReader` contract. Current C readiness still comes exclusively from the authoritative overview backend's complete current proof checks, not this table or historical job-success counts.
