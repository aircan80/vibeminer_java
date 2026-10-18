# Build preparation concepts

Design-only proposals for [#122](https://github.com/aircan80/vibeminer/issues/122). Open [the interactive comparison](index.html). No production behavior is changed or approved by these mockups.

- [1 — Stay in the build list](index.html?option=inline&state=indexUnknown): selected-tile activity and inline inspector progress preserve the product/build context. The recommended starting point for review.
- [2 — Preparation view](index.html?option=dedicated&state=indexUnknown): a focused Discover view for long preparation, with a return to the build list.
- [3 — Progress drawer](index.html?option=drawer&state=indexUnknown): detailed progress can be closed while compact activity remains visible on the selected build.

The state selector covers metadata transfer with known and unknown totals, completed transfer with verification outstanding, local indexing, finalization, readiness, cooperative stopping, stopped work, failure/retry, saved-data revalidation and a proposed background-job lifecycle. The Next observed state control changes illustrative observations; elapsed times and counters are authored examples, not performance measurements. The 200% control checks enlarged text; it does not represent a native JavaFX qualification.

## Readiness and percentage semantics

Required metadata admission and useful local inventory preparation are separate. Today the app can enter C after required metadata is admitted while inventory preparation continues. These concepts propose keeping the preparation workflow visible in Discover until the inventory/index and exact target are usable. That entry decision needs review; it does not redefine metadata admission or require complete payload retention. A prepared build can remain Partial with On demand payloads.

Metadata opening has coarse stages and optional wire/requirement counters. A reliable response-body denominator permits a **metadata-download step percentage**. Download 100% still requires verification. The concrete inventory preparation backend currently provides coarse stages and no processed/total counters. Its default preview is therefore indeterminate.

The **64% mapping-index step** is explicitly proposed instrumentation: known authenticated decoded-source length or encoding page/byte totals could support it after new instrumentation is implemented. It is not an existing reported percentage, an up-front record count, a network-byte counter, a weighted overall percentage, or evidence that the final proof/SQL/ROOT/IN/DL/DS joins/seal/target work is done. Derived SQLite size is not download progress.

Cancel requests a cooperative stop; the worker must retire before the job is stopped. Whole verified shared objects and ready derived data may be reused after validation. Unfinished staging is not a resumable N% checkpoint. Retry keeps the exact build and never silently repairs/replaces corrupt bytes or authorizes Download all.

Current production has one build session and retires preparation when another build is selected. Keeping a preparation job alive while browsing a different build is a **separate lifecycle proposal** illustrated explicitly in the background state; it requires resource-budget, cancellation and ownership changes. Closing a drawer or returning to the same build list must not accidentally imply cancellation.

## Reference and limits

The visual baseline is [approved Atlas B build discovery](../build-discovery/index.html?concept=B&state=ready): product first, sage product panel, separate build tiles, flat pale inspector, teal single primary action and explicit activity separate from build status. Mock build identities, data, timing, failures and stage percentages are illustrative. No CDN request, game-install access, download, parse, index, save or actual job occurs. Review these options before implementation.
