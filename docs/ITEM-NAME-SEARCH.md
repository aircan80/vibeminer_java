# Literal search within prepared Item names

**Status: approved contract; implementation is not started by this document.** Tracked in [#89](https://github.com/aircan80/vibeminer/issues/89), a separate child of #21/#16. Preserve the current [names preparation, paging and exact-ID scope](ITEM-NAMES.md); this feature adds an explicit query rather than expanding previous acceptance retroactively. [Item physical coverage](ITEM-PROJECTION.md) and [central delivery status](PLAN.md) remain authoritative boundaries.

## Acceptance

- Explicit Search/Enter only; typing, selecting a pair and clearing do not perform search, preparation, acquisition or repair. Label the mode “Display candidates · exact text, case-sensitive.” Clear returns to Browse. Preserve exact-ID lookup.
- Query is 1–128 Unicode scalar values and at most 512 strict UTF-8 bytes. Reject empty, NUL and malformed UTF-16; preserve whitespace, case and normalization verbatim. Matching is contiguous scalar-sequence literal substring; %, _, quotes and backslash are literal. No regex, casefold, normalization or SQL LIKE behavior.
- Search only physical Display candidates joined to physical Item anchors in the admitted prepared exact pair. Copy/unavailable/empty candidates and Sparse-only IDs never supply matches. No match means no match in admitted coverage, not whole-build absence or gameplay availability. Preserve attribution and both source/proof scopes.
- Reuse the verified Item-anchored join in batches of at most 128. Decode/checksum/strict-UTF8/context-validate every examined tuple before Java matching, including nonmatches; no SQL predicate over unvalidated candidate BLOBs that could hide corruption. No new text table, persisted query payload or FTS framework.
- Admit at most 200,000 physical Item anchors, 200,000 physical names and 64 MiB projected name UTF-8 per action, checking trusted coverage and actual examined/decoded sums. Preserve existing per-record bounds. Fail LIMIT_EXCEEDED without partial results or false no-match if limits prevent completion. Buffers stay bounded.
- Unsigned numeric Item-ID ordering only; scan after cursor ID and stop at size+1 verified matches or verified EOF. Next cursor is after the last visible match. No count-all pass. Bind cursor to both artifacts/roles/snapshot/locale, exact query/mode/page size and opaque fingerprint of both current SQL artifact generations; do not use store/process epoch as artifact identity.
- Application validates requests/results and authorization after completion. Hold independent Item/Sparse source leases through exact final transaction. Preserve source readiness/coverage checks; missing/corrupt source revokes the affected ready pair. Cancellation retains job capacity until actual retirement, interrupts bounded row work and SQLite aggregates/scan with safe connection-scoped cleanup.
- Disclose bounded linear-scan work: page size is not total CPU/proof cost. Existing source replay budgets and integrity aggregate scans (#88) are additional. Measure realistic authored 59,859-Item/175,503-Sparse fixtures and cancellation before UI acceptance; do not promise invented latency or weaken checks.
- Native authored-data Search→continue→inspect→clear→exact-ID flow passes offline, with keyboard/100%/200%, stale pair/query suppression and regression of current names/repair workflows. No actual game names or live acquisition enters tests.

## TDD and independent evidence

Strict query/cursor tests; scalar-array predicate oracle including NFC/NFD, non-BMP, combining/ZWJ, punctuation and spaces; real SQLite corruption of nonmatching tuples; copy/empty/missing and Sparse-only exclusions; lookahead/cursor/generation tests; actual bounds and interrupted aggregates; source leases/restart zero HTTP. Independent integrity/storage and domain/UI reviews precede acceptance.

Excluded: new decoders/sources/locales, implicit projection/repair/download, linguistic/case-insensitive matching, generic filters/sorts/SQL, copies, full-table or decoded-CKey upgrades, comparison/export and GC. #88 remains a separate profiling follow-up, not permission to bypass validation. No current parent story is closed by this child’s creation.

## Minimal layered implementation boundary

Add a strict domain literal-query value and search cursor, plus one explicit asynchronous `search(pair, query, cursor, size)` operation and blocking backend equivalent. Query identity is versioned and length-framed; compare actual context as well as digests. Query text is not a shared derivation key and is not persisted as a result ledger. Return only matching physical `NamedItemRow` values with the same pair/proof/coverage contracts. Existing page and exact-ID cursors remain unchanged.

Infrastructure performs the verified scan in the existing exact pair transaction. Current names are versioned checksummed BLOBs without an independently trusted text column: predicate filtering before decoding could hide a corrupted formerly matching row. Validate each examined tuple first, then use Java `String.contains` on strictly valid strings; for well-formed strings this matches contiguous scalar sequences. Test against an independent code-point-array oracle. Matching a combining mark or scalar inside an emoji sequence is allowed: this is not grapheme, word or linguistic matching.

The opaque generation fingerprint binds both SQL artifact generations in the same transaction. Do not expose SQL objects across ports or bind immutable artifacts to process/store epochs. Respect existing four actual-job and sixteen prepared-pair admission limits; no new generic query framework or authorization ledger is required.

A result page holds at most the existing 500 visible matches and one lookahead. Scan buffers hold at most 128 joined rows; existing Item-record/candidate-string limits remain enforced. Check row and UTF-8 accounting with overflow-safe sums. The next action may re-examine the lookahead gap; correctness and independent per-action bounds take priority over speculative caching.

Connection-scoped SQLite cancellation must only check the token in its progress callback, run no SQL/reentrant mutation, and clear the handler in `finally`. Translate interrupted SQL into cancellation only when the token is actually cancelled. Unknown storage failures remain failures. The scan budget does not replace source replay budgets or impose a small combined wall-clock/decode ceiling.

No source changes, live requests or automatic acquisition are authorized by writing this contract. Implementation proceeds in separately owned, test-first steps under #89 with independent review.
