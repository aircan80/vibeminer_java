# Full selected sparse suffix metadata (#65)

`SparseIndexMetadataProbe` validates the complete zero-key section-0 index suffix for the prior explicit enUS ItemSparse target. It uses the versioned [header](../../docs/evidence/item-sparse-header-12.1.5.70448-enUS.json) and [section declarations](../../docs/evidence/item-sparse-section-metadata-12.1.5.70448-enUS.json) as attributed inputs. No root, EN, configuration, archive index or HEAD refresh occurs.

The fixed target is FileDataID 1572924, CKey `bb0ecbdc2fd4988ddd3c1c5e95c34165`, loose EKey `cab35eb8601260c12a5917c1bdacfda5`, encoded/declared decoded lengths 7,988,612 / 62,715,198. Prior section 0 declares record bytes `[5252,60166704)` and suffix `[60166704,62624722)`, a **2,458,018-byte complete suffix**. Prior metadata is not silently treated as freshly verified.

Compile with the [protocol tools instructions](ARCHIVED-ITEM.md), then run:

```sh
java -cp 'build/protocol-tools:infrastructure/build/classes/java/main:protocol-tact/build/classes/java/main:protocol-db2/build/classes/java/main:format-wow/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main' \
  SparseIndexMetadataProbe --self-test
```

Windows uses `;` classpath separators. Fixtures are authored in memory, and normal CI remains offline. A separately authorized live run uses `--live`; there are no overrides, retries or larger fallback scopes.

Fixed ceilings are **4 MiB response bodies, 256 requests, 8 MiB aggregate decoded work, 4 MiB per encoded/decoded buffer, and 120 seconds**. The tool first acquires and authenticates only the known 90,732-byte BLTE header. It then selects and deduplicates chunk 0 plus every complete chunk whose decoded interval intersects the attributed suffix. Actual selected encoded/request/decode totals and each buffer size must fit the remaining ceilings before any chunk payload is acquired. A refusal does not increase limits.

Chunk 0 is acquired first and fully authenticated. Fresh WDC5 metadata must agree with the exact table/layout/schema/flags and prior zero-key section coordinates before other selected chunks are acquired. The tool retains only the suffix buffer and each current bounded decoded chunk; it does not accumulate selected chunk arrays. Complete boundary chunks can contain adjacent bytes, but those adjacent record bytes are not inspected. An unrelated chunk never becomes part of the plan. Chunk 0 is reused if it overlaps the suffix, rather than fetched again.

The exact complete suffix is passed to `SparseSectionIndexParser` (#63). **Every** raw record ID, map ID, offset/size entry and copy declaration is parsed and checked—not merely boundary samples. Immutable results preserve the original lists and order. Canonical little-endian SHA-256 digests reconstructed from every raw field are checked against the corresponding authenticated suffix byte regions. Text output includes full-stream digests, complete coherence counts, first/last entry samples, and every copy pair up to 1,024 pairs; larger copy lists use explicitly sampled display while all entries remain checked and hashed.

Coordinate analysis reports these distinct facts without repair or normalization:

- Record-ID/map-ID sequence and set agreement, ordering, duplicates and zero counts.
- Every raw map offset/size under the **absolute decoded-file-coordinate candidate**, including nonzero sizes and bounds inside the declared record span.
- Original physical map order, contiguous transitions, first/last boundaries, total sizes and exact span coverage. A composite candidate flag describes this packed/contiguous model; separate bounds/coverage facts remain visible if it does not fit.
- Unresolved copy target/source facts, including duplicate targets, targets already in physical IDs, sources outside selected physical IDs and self references. No aliases or records are resolved.

The pinned DBCD writer at revision `e732093f8864240fc5884bd1bba6b02f3dfc0d56` records `writer.BaseStream.Position` in sparse offsets. The reader follows a different size-accumulation path. The pure parser therefore continues to expose `RAW_OFFSET_ORIGIN_UNVERIFIED`; this diagnostic does not alter that production capability. Actual full-source coherence is evidence for this exact cohort, not a universal format rule or a correction applied to raw values.

Success proves only authenticated consumed chunks and complete selected suffix metadata. It does not prove full table CKey, record bodies, names, schema semantics or section readability. No payload is written or published as a ready object. Failure stops immediately with no extra acquisition.
