# Authored Item/name scale journey

This standalone command exercises the production retained readers, CAS, SQLite catalog,
Item/name projection backends, literal search, and source verification at 59,859 physical
Item rows and 175,503 physical Sparse rows. It requires no game installation, network,
private files, test classes, or display. It does not modify the accepted native UI fixtures.

All bytes and names are authored. An explicit synthetic root/config/EN association port
checks the original catalog association and rejects every transport-capable operation.
The exact supported cohort descriptor/schema constants are used to enter the existing
narrow decoder; those constants do **not** authenticate these authored payloads as Blizzard
content. BLTE header EKey, every encoded chunk MD5, physical SHA/length, admitted windows,
projection, provisional-row staging, and ready/query leases use production implementations.
No official TACT chain or complete decoded CKey verification is claimed.

## Run

Use JDK 25+, current compiled production modules, the infrastructure resources, SQLite JDBC
and SLF4J API. No JavaFX classes are needed. For example, from the repository root with
`JAVA_HOME`, `SQLITE_JAR`, and `SLF4J_JAR` pointing to installed local dependencies:

```bash
mkdir -p /tmp/vibeminer-scale-classes
"$JAVA_HOME/bin/javac" -cp "$SQLITE_JAR" -d /tmp/vibeminer-scale-classes \
  $(rg --files domain/src/main/java application/src/main/java protocol-tact/src/main/java \
    protocol-db2/src/main/java format-wow/src/main/java infrastructure/src/main/java tools/bench -g '*.java')
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Xmx512m \
  -cp "/tmp/vibeminer-scale-classes:infrastructure/src/main/resources:$SQLITE_JAR:$SLF4J_JAR" \
  io.vibeminer.infrastructure.item.ItemNamesScaleBenchmark /tmp/vibeminer-scale-new-run
```

Supply a NEW directory. The command refuses an existing catalog. Output and catalog/CAS
are retained for inspection. Repeat with new directories/processes; label cold/warm runs
explicitly. Optional `-Xlog:gc*` or JFR flags provide allocation/GC evidence; printed heap
usage is a point observation, **not** peak memory or allocation rate.

## Geometry and correctness

The authored Sparse record area is exactly 60,161,452 bytes (about 57.37 MiB). Variable rows
hold five strict UTF-8 CStrings, followed by deterministic zero padding ignored by the
narrow Display projection. The suffix has 175,503 IDs/maps/map IDs and ONE unindexed copy,
so its 2,457,050 bytes intentionally differ from the observed real cohort suffix containing
122 copies. Metadata is 4096 bytes; total decoded Sparse size is 62,622,598 bytes. Item uses
the admitted 11-byte authored physical recipe and one unindexed copy.

Generation streams decoded scratch files and compresses independent <=256 KiB decoded
BLTE Z chunks. It never constructs the 57 MiB record area as one heap array. Scratch decoded
and temporary chunk body files are removed after encoding; encoded seeding files are
removed after CAS publication. The retained CAS holds one physical object per source.
Generator chunk buffers, reader encoded materialization within explicit 16 MiB bounds,
bounded suffix/record buffers, and SQL rows are separate costs. Compression/padding is
synthetic and is not a prediction of real Blizzard compression or I/O cost.

The corpus has unique high-cardinality names, Greek, case variants, NFC versus NFD,
a supplementary scalar, literal percent/underscore/backslash/quotes, an empty physical
name, a Sparse copy-only Item ID, a missing Sparse physical Item ID, and Sparse-only rows.
Search results are checked against an independently generated Item-anchored literal oracle
in stable ID order; copies and Sparse-only rows cannot fabricate matches. First and next
pages and lookup use the actual query backend. Projected UTF-8 count must remain <=64 MiB.
The SQL row/artifact counts must show one shared artifact per source before and after
restart, with exactly 59,859 integer rows and 175,503 name rows.

A deterministic projection cancellation is raised after 1,024 actual provisional callbacks.
The command requires worker/lease retirement and zero READY name artifact, then performs
normal preparation. A second search cancellation is raised after 10,000 real token polls;
the counter does not identify a decoder or SQL stage. It must retire with zero active source
leases. Wall-time search cancellation is also requested after 5 ms in a separate worker; its
actual outcome is printed because completion can win the race. A bounded retirement timeout
is a correctness guard, not a UI latency promise. Restart explicitly prepares again: this
may reproject to obtain source proof. Subsequent page/lookup/search must not reproject.

## Measurements and limits

Each phase prints monotonic elapsed milliseconds, JVM/OS/heap settings, source byte/count
facts, artifact IDs, and heap usage observations. Timings are observations for this authored
corpus and machine, with no invented acceptance threshold. Search/page/lookup still include
physical verification and admitted-proof replay. Synthetic root replay does not represent
the production cost of independently bounded real root stages. SQL integrity aggregates
and search scans are additional work; page size alone does not bound CPU. Story 88 profiles
those inherited aggregates separately. The corpus is at production row counts but does
not simulate encrypted sections, slow disks, actual CDN access, or arbitrary layouts.
