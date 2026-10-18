# ItemSparse section/storage declarations (#61)

`SparseSectionMetadataProbe` reauthenticates only the previously recorded explicit enUS ItemSparse prefix, then applies `Wdc5MetadataInspector` to the complete available section/field/storage descriptor prefix. It performs no root, EN, configuration, index or HEAD refresh. No extra chunk or alternate source is attempted.

Prior membership/identity inputs come from [the recorded ItemSparse header evidence](../../docs/evidence/item-sparse-header-12.1.5.70448-enUS.json): `wow`/US build **12.1.5.70448**, FileDataID **1572924**, enUS bit `0x2` in shared enUS/enGB root mask `0x202`, CKey `bb0ecbdc2fd4988ddd3c1c5e95c34165`, loose EKey `cab35eb8601260c12a5917c1bdacfda5`. Whole encoded/declared decoded sizes are **7,988,612 / 62,715,198** bytes. These are attributed prior inputs, not a new complete root/EN proof.

The live plan is exactly two strict ranges on the known official loose resource:

| Range | Start | Count |
| --- | ---: | ---: |
| Complete BLTE header | 0 | 90,732 |
| First encoded chunk | 90,732 | 1,950 |

Total response bodies: **92,682 bytes**. Decoded work: **5,252 bytes**. HTTP/TLS overhead is excluded. Defaults cap wire-body reservations at **128 KiB**, requests at **16**, elapsed time at **one minute**, and encoded/decoded buffers and aggregate decoded work at **4 MiB**. The entire known scope is checked before the first request. Failures retain conservative reservations and stop without additional acquisition.

Compile using the [protocol tools instructions](ARCHIVED-ITEM.md), then run the offline generated-fixture test:

```sh
java -cp 'build/protocol-tools:infrastructure/build/classes/java/main:protocol-tact/build/classes/java/main:protocol-db2/build/classes/java/main:format-wow/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main' \
  SparseSectionMetadataProbe --self-test
```

Windows uses `;` classpath separators. No ignored evidence files are inputs. Separately authorized live evidence uses `--live`; there are no overrides or implicit live operation. Routine CI remains synthetic.

The complete BLTE header must authenticate against the prior EKey, and its first descriptor must agree with the exact authorized encoded/decoded sizes and offsets before fetching chunk 0. The complete consumed chunk MD5 and supported decode are checked before metadata inspection. WDC5 table `919BE54E`, layout `1C17D17F` and schema version `5` must match before successful metadata output. A truncated descriptor prefix refuses instead of requesting another chunk.

Output preserves every section declaration, raw encryption-key identifier, field metadata and storage kind/union parameters. Raw DB2 locale remains separate from root locale selection. Section keys and storage values are declarations: they do not establish section readability, key availability or schema semantics. The output explicitly leaves full table CKey, section bodies, rows, names and schema interpretation unverified. No payload is saved or published to storage.

Synthetic regressions cover exact two-range scope/costs, source and header/chunk integrity, wrong identities, incomplete descriptor scope, authenticated descriptor mismatch, all preflight budgets, deadline/cancellation after the first response, and refusal without refresh/fallback or extra chunks. The pure metadata parser supplies declared geometry checks; it does not validate the contents of sections or auxiliary values.
