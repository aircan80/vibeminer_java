# Scoped enUS ItemSparse header diagnostic

`SparseItemHeaderProbe` continues issue #45 after the complete root and Item header experiments. It never scans or downloads the root again. The eleven prior FileDataID **1572924** memberships are committed as immutable metadata inputs from the verified complete root CKey `e84483ee756c69a7dc5fbff04af81316` for `wow`/US build **12.1.5.70448**, build configuration `88d5e8ef05ec5a8446cdec93d142d7c6` and CDN configuration `f065eade5276a5ac05606e60a4c5a1f7`.

Selection is explicit **enUS locale bit `0x2`**, independent of US region. The matching root mask `0x202` shares content with enGB and selects CKey **`bb0ecbdc2fd4988ddd3c1c5e95c34165`**. All other locale/content variants remain visible metadata. Multiple matching memberships may share one CKey without duplicate downloads; multiple distinct matching CKeys are refused rather than silently choosing one.

Use the full-JDK compile commands in [the archived Item diagnostic](ARCHIVED-ITEM.md), which compile `tools/protocol/*.java`, then select this entry point:

```sh
java -cp 'build/protocol-tools:infrastructure/build/classes/java/main:protocol-tact/build/classes/java/main:protocol-db2/build/classes/java/main:format-wow/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main' \
  SparseItemHeaderProbe --self-test
```

Windows classpath separators are `;`. Tests generate all payloads in memory and make no network requests. They verify locale selection, shared identities, EN proof/source corruption, strict fallback policy, global budget accounting across reused stages, archive translation and prefix-only scope.

An authorized live run uses only **`--live`**, with fixed limits and no budget overrides:

| Boundary | Limit |
| --- | ---: |
| Global response-body reservations | 24 MiB |
| Global request count | 5064 |
| Overall time | 10 minutes |
| EN stage response bodies / requests | 4 MiB / 64 |
| Encoded or decoded chunk buffer | 4 MiB |
| Retained EN index cache | 2 MiB |
| Maximum archive index geometry | 2 MiB |
| Maximum observed archive size | 8 GiB |
| Aggregate EN + table decoded work | 64 MiB |

A single supervising source counts actual configuration/range/footer/HEAD calls across the independently bounded reused helpers. HEAD consumes one request and zero response-body bytes. Configuration conservatively reserves 1 MiB before transfer and settles actual bytes. Failed requests retain their reservation. Logs separately report completed response bodies and reserved-or-received bodies, so an initial loose 404 leaves its eight-byte conservative reservation visible. EN decoded work is carried into the shared total before table acquisition; selected table chunks are preflighted against the remaining total and per-buffer limits.

Network scope is deliberately narrow:

1. Verify the two exact configuration MD5 values and observed build name/UID/root identity.
2. Authenticate the EN BLTE header and small complete index chunks, then only the selected CKey/EKey pages. Preserve all encoding alternatives; explicitly choose the smallest declared encoded candidate. This is not complete EN CKey verification.
3. Send one exact **eight-byte loose probe** for that candidate. Success continues on the loose source. Only the initial probe's exact known HTTP 404 outcome permits the explicitly reviewed archive branch.
4. If necessary, scan pinned CDN archive keys in order using authenticated footer → TOC → selected page, stop at a verified mapping, HEAD the archive size, and translate the child ranges. Advertised size hints never control routing or exact range totals.
5. Authenticate the selected object's BLTE header and complete consumed chunks covering the 204-byte WDC5 prefix. Compare observed table/layout with candidates `919BE54E` / `1C17D17F`, while retaining actual schema, flags and unsupported outcomes.

The existing range API exposes a typed `UNAVAILABLE` reason without a numeric status field. This diagnostic therefore recognizes only that API's exact `Pinned range unavailable (HTTP 404)` message through `ExecutionException`/`CompletionException` wrappers, plus its matching typed reason. Unknown messages, 410/416, transport errors, integrity failures, and a 404 after the initial loose probe stop acquisition. This is a narrow diagnostic adapter, not a general HTTP status parser or production fallback policy.

No root bytes, full EN manifest, all eleven localized tables or game installation are read. No game payload is written. Prior root membership proof is labeled separately from this run's selected EN/index/archive/chunk evidence. Header success does not establish full table CKey, table body, schema semantics, rows, names, or section encryption status. Unknown encrypted-section state remains unknown after the prefix. Failures leave remaining scope unacquired; they do not establish record absence or silently expand budgets.
