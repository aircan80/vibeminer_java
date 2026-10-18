# Archived Item header diagnostic

`ArchivedItemHeaderProbe` implements the bounded issue #51 experiment. It never refetches the 50 MB root or claims a new root scan. Its explicit prior input is the recorded #45 `wow`/US `12.1.5.70448` proof: complete decoded root CKey `e84483ee756c69a7dc5fbff04af81316` and selected authenticated EN pages map Item FileDataID **841626**, CKey `1df6b6dbae36878cea020a62b15b9bd6`, to EKey `a928b6c9c7ce92d85b638400f81b2fc2`, encoded size **738344**, decoded size **2139665**. This prior identity is separate from the evidence acquired by this run.

Use a full JDK 25 and compile the generated-fixture tools from the repository root:

```sh
./gradlew :infrastructure:classes :protocol-db2:classes :format-wow:classes
mkdir -p build/protocol-tools
javac -cp 'infrastructure/build/classes/java/main:protocol-tact/build/classes/java/main:protocol-db2/build/classes/java/main:format-wow/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main' \
  -d build/protocol-tools tools/protocol/*.java
java -cp 'build/protocol-tools:infrastructure/build/classes/java/main:protocol-tact/build/classes/java/main:protocol-db2/build/classes/java/main:format-wow/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main' \
  ArchivedItemHeaderProbe --self-test
```

Windows uses `gradlew.bat` and classpath `;` separators. No ignored files or game installation are required. The self-test injects synthetic configuration, archive/index, footer/TOC/page and BLTE bytes; normal CI remains offline.

An authorized live experiment replaces `--self-test` with **`--live`**. There are no live budget overrides or fallbacks. Fixed limits are **16 MiB response-body bytes**, **5000 requests**, **10 minutes**, **4 MiB per encoded/decoded chunk buffer**, **2 MiB maximum index geometry**, **8 GiB maximum observed archive size**, and **64 MiB aggregate decoded work**. Configuration reserves its 1 MiB maximum before transfer and settles actual bytes; footer reserves 28 bytes; each exact range reserves its length. HEAD reserves zero body bytes and one request. Failed operations keep conservative reservations. Every request and decode checks cancellation and remaining work/time before execution.

The run verifies CDN configuration `f065eade5276a5ac05606e60a4c5a1f7`, then scans only its `archives` keys in their original order. Each index uses bounded suffix 28 → filename/footer/geometry authentication → authenticated TOC → at most one selected padded page. Observed authenticated geometry controls exact ranges. Advertised `archives-index-size` values stay labeled hints and conflicts; an unequal vector length remains unbound. The code never reorders keys or guesses a group-index URL. Index buffers are released as the loop advances; no complete index or archive is retained.

The first verified EKey mapping stops the scan. Its encoded size must agree with the prior target input. A bounded HEAD observation supplies the archive total; the child span must fit. Child requests translate offsets into strict exact archive ranges and authenticate the child's BLTE EKey/header and every consumed encoded chunk before WDC5 prefix inspection. Logs preserve actual archive/index URLs, coordinates, observation timestamps, range digests and verification scope.

Object and chunk costs are printed before acquisition. Any unsupported format, unavailable response, integrity error or exhausted budget stops the run with remaining scope unacquired. A no-match applies only to the checked index scope and does not establish record absence. No payload bytes are written, and no stored object or ready alias is published.

Observed signature/table/layout/schema/flags are header evidence only. The result explicitly leaves **complete table CKey, table body, schema semantics, records and item name search unverified/unsupported**. The archive HEAD and index mapping are not whole-archive checksum verification. Preserve those limitations in any issue or protocol-evidence summary.
