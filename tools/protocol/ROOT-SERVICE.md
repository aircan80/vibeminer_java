# Layered retained-root acceptance diagnostic (#50)

`RootLookupServiceProbe` exercises the actual application service, official protocol gateway,
content-addressed store and SQLite build memberships without JavaFX. It is a development tool,
not an application feature or a normal-CI live request. All self-test bytes are generated synthetic
fixtures committed beside the tool; no ignored files or game installations are inputs.

From a clean checkout, use a full JDK 25:

```sh
./gradlew :bootstrap:installDist
mkdir -p build/protocol-tools
javac -cp 'infrastructure/build/classes/java/main:protocol-tact/build/classes/java/main:format-wow/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main' \
  -d build/protocol-tools tools/protocol/RootLookupServiceProbe.java tools/protocol/RootLookupProbeFixture.java
java --enable-native-access=ALL-UNNAMED \
  -cp 'build/protocol-tools:infrastructure/build/classes/java/main:protocol-tact/build/classes/java/main:format-wow/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main:bootstrap/build/install/bootstrap/lib/*' \
  io.vibeminer.infrastructure.lookup.RootLookupServiceProbe --self-test
```

Windows uses `gradlew.bat`, shell-appropriate directory creation and `;` classpath separators.
Runtime dependencies, including SQLite JDBC, come from the declared Gradle distribution.

The deterministic self-test checks the same retention/reopen path with generated EN/BLTE/root
bytes, two locale/content variants, present-zero versus absent name hashes, scoped absent IDs,
two physical objects, rejection of a forged canonical-plan replay without transfer, wrong-CKey
rejection without root publication/membership, fresh service/store/catalog instances and a transport that throws if called
during offline reopen. It also checks the global wire/request refusal boundaries.

A separately authorized live run replaces `--self-test` with `--live`. The tool first fetches bounded
original official `wow` US versions/CDNs documents. The production gateway must validate that they
still attest build `70448`, version `12.1.5.70448`, build configuration
`88d5e8ef05ec5a8446cdec93d142d7c6` and CDN configuration
`f065eade5276a5ac05606e60a4c5a1f7`. A changed pointer fails rather than silently selecting latest.
Captured approved HTTPS routes drive the production transports. No fabricated source document,
community mapping, arbitrary historical config pair or private source fixture is used.

The tool prints the canonical plan and then explicitly selects only known root EKey
`1051943fc8cb91a3c88066dc946afc3e`, encoded length 50,651,441 and decoded length 67,292,495.
No alternate candidate or full encoding-manifest fallback is attempted. Retention verifies the
complete root through the service, then fresh offline instances resolve FileDataIDs 841626 and
1572924, requiring all twelve known memberships (one and eleven respectively). The two physical
objects must be the retained configuration and encoded root; partial EN proof buffers are not
published as complete objects. This does not acquire target table files or decode item records.

One supervisor bounds discovery, configuration and ranges together to **64 MiB transferred-body
reservations, 300 requests and five minutes**. Configuration requests reserve their full 1 MiB ceiling; discovery reserves 1 MiB plus
one oversize sentinel byte before dispatch. Successful requests settle actual lengths; failed transfers retain the
reservation. Ranges reserve exact lengths. These counters exclude HTTP headers/TLS overhead.
Each production request has a 15-second deadline; metadata body reading is also bounded to 15
seconds. Consumer cancellation is forwarded through the supervising future. Discovery and planning
consume their budgets before the explicit root selection.

Decoded work is bounded separately: planning allows at most 32 MiB of metadata decode; retaining
performs two complete root passes and fresh offline lookup performs a third. Before retention,
`3 * root decoded length + 32 MiB` must fit the aggregate **256 MiB** ceiling. The tool deliberately
omits a redundant online lookup/fourth root pass. Per-chunk buffers remain capped at 4 MiB by the
production gateway. Physical SHA-256, config MD5, BLTE EKey/chunk checks and complete decoded CKey
retain their distinct meanings.

All payloads and SQLite files live in a newly created tool-owned temporary directory. Services and
worker pools close before fresh offline instances open it. Finally, the tool recursively deletes
only that directory on success or failure. Retain textual identity/result/counter evidence only;
never commit downloaded content. A successful self-test does not establish live compatibility;
a successful live run does not complete historical-build or general file-acquisition acceptance.
