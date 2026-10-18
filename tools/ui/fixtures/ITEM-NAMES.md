# Authored Item names fixture

Groundwork only: no native names capture harness or display run is included here.

`ItemNamesFixtureBackend` composes the existing 61-row Item fixture with authored sparse name records, both real retained source readers, pinned schema resources, the actual Item/names application services, both SQLite backends and one CAS/catalog session. It imports production APIs, never test helpers or private resources.

Root/configuration/EN semantic authentication is an explicit synthetic proof-port fixture. The real readers authenticate the authored encoded SHA-256/EKey/chunks and required decoded windows. This proves neither official TACT authentication nor complete decoded table CKeys, live game contents or general table support. All network-capable operations fail; no game installation or normal user catalog is opened.

The only change to the existing Item fixture is three package-private, tool-only getters: `fixtureCatalog()`, `fixtureStore()`, `fixtureFiles()`. They let this fixture borrow the same active store/session instead of opening a second store owner. No production visibility changes are needed. Names workers retire before the borrowed Item fixture closes its store.

## Fixture API

- `new ItemNamesFixtureBackend(path, seed)`: seed authored data when true; reopen the same durable catalog with a fresh store when false.
- `itemService()`, `namesService()`, `workspaces()`, `rootService()`, `selectedService()`: adapters for a later Atlas native harness. Root/selected presentation remains retained-only synthetic proof-port behavior.
- `itemRequest(index)` and `namesRequest(preparedItem,index)`: explicit same-build enUS requests for the two exact build contexts. Both contexts share one physical Item source and one physical Sparse source, with separate memberships.
- `sparseAssociation(index)`, `builds()`: inspect exact source/consumer metadata.
- Counters `prepares`, `pages`, `lookups`, `projects`, `queryVerifications`, `activeReaders`, `transportCalls` (names port) and combined `outboundCalls()`; cancellable `holdPreparation`/`holdQuery` gates and entered flags. Preparation may project again to establish its source proof; queries must not enumerate Sparse records again.
- `removeSparseSource()` / `restoreSparseSource()` and `corruptItemSource()` / `restoreItemSource()`: explicit fixture-only reversible source states.
- `storedItemRows()`, `storedNameRows()`, `nameArtifacts()`: shared-storage assertions.

The Item anchor has 61 IDs, 1001–1061. Sparse physical names cover an authored subset plus a Sparse-only record. ID 1001 has a physical candidate, 1002 a valid empty candidate, 1003 a known unindexed Sparse copy, 1004 no physical name in the admitted scope, and 700001 only a Sparse physical record. The existing Item copy ID 999999 remains unindexed. No copy is assigned another row's name.

## Non-FX smoke

After building the production distribution, use the parent [tool compilation instructions](../README.md). The smoke needs no JavaFX display:

```sh
java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  io.vibeminer.infrastructure.item.ItemNamesFixtureSmoke
```

It creates temporary authored storage unless given a new directory argument. It verifies explicit preparation, 50 + 11 Item-ID pages, distinct name outcomes and the Sparse-only nonmatch, both partial proof chains, two-context artifact/row sharing, missing Sparse with independent Item queries still available, corrupt Item revoking paired readiness, fresh-store restart with explicit preparation and zero HTTP. It asserts no Sparse projection during queries, without claiming preparation skips projection. Native keyboard/scale/screenshot acceptance remains a separate later harness.
