# Encoding inventory preparation performance

Issue [#124](https://github.com/aircan80/vibeminer/issues/124) tracks slow first-time local inventory preparation. This document records the first measured optimization, not a complete live-build performance qualification.

## What preparation does

After metadata retention, the EN encoding manifest is strictly parsed and its authenticated pages are projected into local SQLite content, encoding and ordered candidate-reference tables. These derived tables support exact file lookups and reverse references. The observed slow worker was executing SQLite inserts into these tables; this observation does not establish that every preparation stage is network-free.

The writer already reused three prepared statements. Each inserted content, encoding or candidate row nevertheless executes a separate SQL insert, maintaining primary/secondary indexes, foreign keys and immutable-READY triggers. The original runtime committed after every **512 aggregate inserted rows**, including candidates. Every connection requests SQLite `synchronous=FULL` with the existing journal policy.

The selected small change increases the runtime batch to **4,096 aggregate inserted rows**. It changes no schema, indexes, parser, row/candidate/byte ceilings, source validation, journal policy or durability setting. `maxBatches` remains bounded. Cancellation is still checked between records and candidate writes, rather than only at a transaction boundary. Larger transactions can occupy the writer for longer; this is a measured tradeoff, not an assertion of zero latency.

READY publication still requires strict parser completion, final batch success, the caller's complete-source verification callback, completion-counter equality and the owning STAGING generation fence. Failed or canceled staging is retired without changing an earlier READY generation. This optimization does not accelerate a version of the app already running.

## Measurements, 2026-10-18

Environment: Linux x86-64, Intel Core i7-1365U, JDK 25.0.4.1, SQLite JDBC 3.53.4.0, app-owned Btrfs storage, JVM heap ceiling 256 MiB. This was a shared development host with other application/build work; filesystem/JIT/cache state was not controlled as a laboratory benchmark. A separate 100-page warmup preceded the measured runs; subsequent trials used fresh database files. Treat the numbers as evidence for this small choice, not a promised real-WoW preparation time or a worst-case latency bound.

Inputs were authored complete EN streams, generated a page at a time and retained as benchmark input. No official game payload, user catalog, game installation or live CDN request was used. The measured operation includes actual `FullEncodingInventory` parsing and `EncodingLookupStaging` construction, excluding fixture generation and post-build verification.

The pilot used 3,000 content pages and 3,000 encoding pages: 78,000 content rows, 120,000 encoding rows and 78,000 candidate rows, or **276,000 SQL inserts**. It used a nine-character artifact identifier and one candidate per content row.

| Aggregate rows per transaction | Trial 1 | Trial 2 | Estimated data-batch commits | Final database bytes | Sampled peak journal bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| 512 | 11.357 s | 10.331 s | 540 | 74,899,456 | 148,256 |
| 2,048 | 6.334 s | 5.908 s | 135 | 74,899,456 | 172,880 |
| 4,096 | 4.961 s | 5.499 s | 68 | 74,899,456 | 172,880 |

A larger follow-up used a 64-character artifact identifier and two candidates per content row, including shared encoding references. Its 12,000 pages per table produced 204,000 content rows, 480,000 encoding rows and 408,000 candidate rows: **1,092,000 SQL inserts**.

| Aggregate rows per transaction | Elapsed | Estimated data-batch commits | Final database bytes | Sampled peak journal bytes |
| --- | ---: | ---: | ---: | ---: |
| 512 | 34.845 s | 2,133 | 510,279,680 | 185,192 |
| 4,096 | 22.881 s | 267 | 510,279,680 | 210,456 |

Commit counts above are arithmetic estimates for inserted data batches. They exclude artifact creation and READY publication and are **not measured fsync counts**. Journal sizes were sampled every 5 ms and may miss a shorter peak. No claim is made about total write amplification or disk wear.

Both series retained FULL synchronization, all indexes, triggers and foreign keys. Verification checked exact table totals, first/last forward lookups, complete candidate-to-content/encoding joins for ordinal zero, and `foreign_key_check`. The checked-in tool additionally verifies ordered reverse origins and a bounded ambiguous-prefix refusal. The larger series supports selecting 4,096 without removing protections. Per-row SQL and index costs remain; this is not a complete solution to arbitrarily large inventories.

A separate cooperative-cancellation probe requested cancellation after a fixed number of cancellation checks during staging. Request-to-retired-return times were 0.230 s at 512 and 0.192 s at 4,096; all owned staging tables were empty afterward. These are two synthetic observations, including cleanup, not maximum or percentile cancellation guarantees. Depending on the existing check site, cancellation can surface as a cancellation exception or the parser's canceled-enumeration I/O failure.

## Reproduce the authored workload

Use a full JDK 25 and a new output directory for every run. The Bash helper builds the normal installed application distribution first; Gradle may fetch build dependencies on its first invocation. The benchmark itself uses no network client. Its compiled helper classes are temporary; output consists of authored EN input and a disposable application-schema database. Do not point it at application/user storage.

```sh
export JAVA_HOME=/path/to/jdk-25
./tools/performance/run-encoding-inventory-benchmark.sh /tmp/en-warmup 100 512 2
./tools/performance/run-encoding-inventory-benchmark.sh /tmp/en-512 12000 512 2
./tools/performance/run-encoding-inventory-benchmark.sh /tmp/en-4096 12000 4096 2
```

Arguments are output directory, pages per table, aggregate transaction rows and candidate fanout (1 or 2). Current reproduction uses a 64-character artifact identifier for both fanouts; the early pilot used nine characters as noted above. Run multiple sequential fresh-directory trials and alternate order when making a new performance decision. Do not run benchmark variants concurrently against the same files. After validating numeric arguments, the helper atomically creates the requested directory and refuses every existing output directory, including an empty one. It never opens an existing catalog.

Normal CI uses deterministic semantic regression tests with no wall-clock performance assertion. New tests cross multiple 4,096-row batches, verify exact forward/relational lookup data, reject late complete-source failure and cancel after a committed larger batch while preserving a prior READY generation. Existing tests continue to cover duplicate candidate ordering, unknown encoded sizes, unsigned 40-bit lengths, bounded queries, malformed/late EOF, fencing, immutability and retired-owner recovery. Native process-death qualification remains a separate project gate.

## Remaining investigations

A shorter reverse index appears to be a strict prefix of the covering reverse-origins index. Removing it could reduce write/storage cost, but requires a separate migration, exact/full and prefix query-plan evidence, prior-schema upgrades and query-budget regression checks. This change deliberately retains both indexes. JDBC multi-row writes, compact key representation and deferred index construction likewise require independent measured work; none justifies disabling source verification, foreign keys, READY protections or durable writes.
