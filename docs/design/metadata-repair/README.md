# Atlas original metadata repair references

These 21 scene-only PNGs record the scoped native interface for repairing an original retained ROOT or INSTALL metadata representation against immutable runtime revision `7b188d7`. Fresh compilation of the UI tools and a run without a desktop overlay passed **71 assertions across two application JVMs** (62 in the first process and nine after restart), with zero live HTTP. Independent source, fault-seam and visual reviews accepted this Linux fixture presentation. Cross-platform CI and release qualification remain separate gates.

## Real stack and authored responses

`CaptureMetadataRepairWorkflow` uses `MetadataRepairFixtureBackend` and `MetadataRepairHeadClient`. Initial independently authored root/encoding/configuration and installation-list bytes pass through actual acquisition adapters and produce the original proofs. Repair uses the actual application service, same-session ordinary-plan issuer, bounded HEAD transport, executor, read-only verifier, CAS, canonical worker policy, SQLite audit and build association. There are no proof-port stubs or fake repair results.

All source responses are scripted tool fixtures. Counts describe the actual requests and responses consumed by the adapters; they do not claim that HTTP reached a CDN. No live request, game installation, real game content or normal user catalog is used. The selected fixture is an ordinary original-A context, not an unsupported alternative-B repair.

Availability checking is a separate action: one pinned HEAD, no payload response body. A positive proposal is not verified readiness or download permission. The user separately approves the disclosed original encoding, positive payload and temporary-storage/work limits. Repair does not select another encoding, mirror, build or fallback source. Opening retained metadata remains explicit after completion.

## Evidence and bounded scope

Original acquisition URL/time/physical identity, HEAD observation and completed repair audit remain separate copyable evidence. Successful authored ROOT repair performs four range responses totaling 157 encoded bytes; INSTALL uses one payload response of 123 encoded bytes. The actual completed audit must match these request/response observations and preserve the original acquisition receipt. ROOT parsing includes both required passes. These scoped metadata proofs do not establish readable game assets, item names, icons or a complete build archive.

The fixture can restore its authored physical source after HEAD and before execution. The real local verification path then needs zero payload requests and returns no completed-download audit. It must not invent fresh transfer evidence from HEAD or the original receipt.

A deliberately injected test callback failure occurs **after actual CAS promotion and durable structural evidence**. The real executor/service must report publication `UNCERTAIN`; the audit has no `COMPLETED` event and no new consumer association or successful result is claimed. A previously valid historical association may remain. This is an actual storage/audit-policy exercise with an authored callback fault, not certification of arbitrary disk or filesystem failure. The repair panel offers verification only, without a new repair Check/Download/Open action. Ordinary ROOT payload acquisition is asserted disabled during uncertainty and restored only after an explicit new ordinary-source check and candidate choice, without an implicit repair HEAD or payload request. The symmetric INSTALL action guard has separate presentation-test coverage. No availability check, payload retry, offline open or current variants follow automatically. A separate explicit local read may establish present readability while the failed repair attempt remains historically uncertain; it does not manufacture a completed repair audit.

Other cases reject expired consent before payload, reject HEAD unavailability without fallback, reject wrong payload bytes without promotion, and cancel an actually pending payload response without claiming completion. The cancellation test establishes this controlled request's outcome; it does not certify physical keyboard cancellation, arbitrary network timing or ignored late callbacks.

A second JVM explicitly reopens ROOT and INSTALL metadata without HEAD, payload or ordinary-source requests, and replays the two durable completed audit IDs. After the cancellation case, the test fixture restores authored install bytes solely to permit the restart reader check; this is not a claimed successful UI repair. The real reader still verifies the restored physical bytes. The restart audit checks concern earlier actual successful repairs, not the canceled or uncertain attempt.

## Reference states

The 21 PNGs have the following scopes. Filenames end in `-1280x800.png`; enlarged plan captures also include `-200pct-`.

| Filename stem | Inspected behavior |
| --- | --- |
| `repair-root-plan`, `repair-install-plan` at both scales | Positive cost, exact original encoding and separate consented download action together. |
| `repair-root-head` | Copyable HEAD observation, distinct from original proof and completed repair evidence. |
| `repair-root-audit`, `repair-install-audit` | Actual completed request/byte counts and fresh verification evidence. |
| `repair-install-original-proof` | Original acquisition history remains unchanged after repair. |
| `repair-root-offline-variants`, `repair-install-offline-entries` | Explicit retained inspection, not automatic opening after completion. |
| `repair-install-revoked-history` | Prior current entries cleared; historical receipt remains inspectable. Independent ROOT coverage survives. |
| `repair-install-verified-local` | Restored-local race: zero payload, no completed-download audit. |
| `repair-head-unavailable`, `repair-wrong-bytes`, `repair-expired` | Distinct unsuccessful paths, with no fallback or successful repair claim. |
| `repair-downloading`, `repair-cancelled` | Controlled pending payload request and cancellation without current completion. |
| `repair-uncertain-verify-only` | Actual post-structure callback fault leaves repair verification-only and ordinary ROOT download disabled. |
| `repair-uncertain-explicit-local-verification` | Explicit local read establishes present readability without rewriting uncertain repair history. |
| `repair-restart-root`, `repair-restart-install` | Fresh-process retained reads and persisted historical audit checks without outbound requests. |

## Presentation and reproduction

Plan captures at 1280×800 and 200% text scaling keep positive cost and the separate download action together. Separate images inspect original proof, HEAD, completed audit, metadata results, local-restoration race, failed bytes, expiration, cancellation and uncertainty. Captures deliberately scroll the application body to the inspected section; they do not imply that every section fits simultaneously.

The backend expiry case advances the fixture’s monotonic time; it does not claim that this run waited through real wall-clock expiry.

The workflow asserts programmatic focus handoffs from availability check to consent, repair completion to explicit offline open, cancellation to retry, and uncertainty to local verification. It does not certify physical keyboard input, screen readers, other platforms or live-source availability. Initial ROOT proposal assertions establish absent current coverage; the prior-ready INSTALL case additionally verifies that old entries/current readiness clear while historical receipts and independent ROOT readiness survive. Prior-ready ROOT/dependent Item invalidation is covered separately by presentation tests rather than claimed from this native fixture.

After `:bootstrap:installDist`, compile the Java tools as described in [the UI-tool instructions](../../../tools/ui/README.md). Then run on an explicitly isolated display:

```bash
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureMetadataRepairWorkflow --isolated-display :191 --output build/ui-review/metadata-repair
```

The supervisor runs separate prepare/restart JVMs. Each child has a 120-second deadline; the supervisor allows 150 seconds per child and confirms process retirement before deleting its temporary store. Unconfirmed termination preserves storage. The normal user desktop is never captured.

The CI entry point is `CaptureMetadataRepairWorkflow` with the arguments above. The integrating workflow invokes this ninth entry point separately. This reference records the native run above, not an assertion that all cross-platform CI or release gates have completed.
