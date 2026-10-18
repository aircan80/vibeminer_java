# Exact item cohort diagnostic

`ItemCohortProbe` is an explicit development diagnostic for issue #45. It composes the production transport, BLTE planner, indexed EN parser, streaming root lookup and WDC5 header inspector. It does not save game bytes, import game installations, publish objects/aliases, or enable item rows. Ordinary checks use generated synthetic fixtures. No live execution is part of normal CI.

Use a full JDK 25 and build the source modules first, from the repository root:

```sh
./gradlew :infrastructure:classes :protocol-db2:classes :format-wow:classes
mkdir -p build/protocol-tools
javac -cp 'infrastructure/build/classes/java/main:protocol-tact/build/classes/java/main:protocol-db2/build/classes/java/main:format-wow/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main' \
  -d build/protocol-tools tools/protocol/ItemCohortProbe.java tools/protocol/ItemCohortProbeSelfTest.java
java -cp 'build/protocol-tools:infrastructure/build/classes/java/main:protocol-tact/build/classes/java/main:protocol-db2/build/classes/java/main:format-wow/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main' \
  ItemCohortProbe --self-test
```

On Windows use `gradlew.bat`, create `build/protocol-tools` with the shell's directory command, and replace classpath `:` separators with `;`. No ignored developer files are inputs. The compiled diagnostic stays under ignored `build/`.

The offline self-test injects a bounded synthetic source. It checks complete root CKey gating, corrupted selected pages/index chunks, source identity substitution, all locale variants, shared header-result reuse, header-only scope, and refusal before request/time/decode budgets are exceeded. Successful tests do not establish real build compatibility.

Live requests require the explicit `--live` switch instead of `--self-test`. The sole live target is the previously captured official `wow`/US build **12.1.5.70448**, build configuration `88d5e8ef05ec5a8446cdec93d142d7c6`, CDN configuration `f065eade5276a5ac05606e60a4c5a1f7`, on the captured advertised `us.cdn.blizzard.com/tpr/wow` route. The diagnostic verifies configuration MD5 and exact build name/UID; it does not refresh latest pointers or guess other products. See [recorded source evidence](../../docs/PROTOCOL-EVIDENCE.md) and [candidate schemas](../../docs/FORMATS.md#pinned-item-cohort-and-header-acquisition-28-45).

Default limits are **96 MiB wire bytes, 768 requests, 10 minutes, 256 MiB aggregate decoded work, 4 MiB per decoded/encoded chunk buffer, and 2 MiB retained decoded-index cache**. Header metadata reuse is limited to 128 results. Per-request body/deadline limits remain enforced by production transport. The root retains only its current authenticated chunk plus the streaming parser's small work buffer/requested memberships; it does not accumulate all chunks. Whole EN/root/table lengths have separate parser limits, not the per-chunk buffer limit. Protocol decoding may briefly hold bounded encoded/decoded representations while verifying a chunk.

Overrides are explicit positive decimal options:

```text
--max-wire-bytes=N --max-requests=N --max-seconds=N
--max-decoded-bytes=N --max-chunk-bytes=N --max-cache-bytes=N
```

The per-chunk limit cannot exceed 64 MiB, retained decoded-index cache cannot exceed 16 MiB, and total time cannot exceed one hour. Configuration transfers conservatively reserve their full 1 MiB cap before starting, then settle actual bytes. Failed transfers retain their full reservation. Every range reserves its exact length. Every authenticated decode reserves declared decoded work first. Complete-root and header-chunk scope costs are checked before their payload transfers. Reports distinguish complete success costs from conservative reservations on failure.

The diagnostic prints object/transfer/chunk scope before acquisition. Complete small BLTE index chunks authenticate EN indexes; large raw-N page regions are used only after the selected page MD5 agrees with its authenticated index. This is **not complete EN CKey verification**. Root candidates are all reported, then the smallest declared encoded candidate is selected explicitly; its complete decoded CKey must pass before memberships appear. Every selected FileDataID locale/content variant and its EN candidates remains visible.

Table acquisition consumes only authenticated chunks covering the 204-byte prefix. Observed table/layout hashes are compared with the pinned candidate definitions, while flags, schema metadata and unsupported outcomes remain explicit. Prefix success is **not complete table CKey verification, body validation, schema semantics or record/name search support**. A failure stops further acquisition with remaining candidates unacquired; it never substitutes another build or reports absent records from partial data.

Do not run live diagnostics as part of routine builds or screenshot capture. Review the source and selected budgets before an explicitly authorized live evidence run. Keep only non-payload textual evidence; never commit game data or local diagnostic paths.
