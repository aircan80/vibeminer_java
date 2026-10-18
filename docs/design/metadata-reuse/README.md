# Atlas retained metadata reuse references

These 17 scene-only PNGs record the accepted ROOT and INSTALL metadata-reuse interface against immutable source `7ffc822`. The final freshly compiled `CaptureMetadataReuseWorkflow` ran against its packaged distribution without a desktop overlay and passed **62 assertions across two application JVMs**: 44 in the first process and 18 after restart. Independent visual and source reviews accepted the scoped Linux fixture workflow at 100% and 200% text scaling.

The fixture uses independently authored install, root, encoding and configuration bytes through existing adapter transport seams. Actual initial acquisition produces the original metadata proofs; the workflow then uses the real reuse service, configuration/metadata gateway, supported grammar validation, canonical claims, CAS, SQLite associations and associated-install reader. There are no proof-port stubs in this composition. Synthetic transfer responses are not live HTTP or evidence of official CDN availability. No real game content, game installation or normal user catalog is used.

The selected exact build B has a CKey-only ROOT declaration, so its absent encoding and size facts stay unknown. Its INSTALL declaration prefers encoding B while the actual retained representation remains encoding A. Both proposals require explicit local verification despite zero additional payload transfer. Apply establishes the scoped association; opening the installation list or looking up FileDataID 42 remains a separate explicit action. Original URL, physical digest, acquisition time and verifier evidence remain distinct from the current declaration and association time.

| Reference | Inspected behavior |
| --- | --- |
| `reuse-root-unchecked-1280x800.png` | No automatic reuse check or root query when opening the workspace. |
| `reuse-root-checking-1280x800.png` | Explicit local check, cancellation and an accessible next action. |
| `reuse-root-plan-1280x800.png`, `reuse-root-plan-200pct-1280x800.png` | Unknown declaration facts, actual retained representation and zero-payload consent beside Verify at both scales. The proposal is not current readability. |
| `reuse-install-plan-1280x800.png`, `reuse-install-plan-200pct-1280x800.png` | Different declared B and actual A encodings, with the same explicit local-verification gate. |
| `reuse-root-associated-1280x800.png`, `reuse-install-associated-1280x800.png` | Verified association followed by a separate explicit offline-open action. |
| `reuse-root-original-proof-1280x800.png`, `reuse-install-original-proof-1280x800.png` | Copyable original source URL, hash, time and scoped verification evidence. |
| `reuse-root-offline-variants-1280x800.png`, `reuse-install-offline-entries-1280x800.png` | Two authored root variants and two authored installation-list entries from real retained readers. Installation-list presence does not acquire its listed files. |
| `reuse-expired-plan-1280x800.png` | Expired proposal cannot authorize reuse; a fresh check is explicit. Existing valid retained evidence is a separate scope. |
| `reuse-restart-root-1280x800.png`, `reuse-restart-install-1280x800.png` | A fresh JVM reopens persistent associations without seeding or transferring content. |
| `reuse-install-corrupt-200pct-1280x800.png` | Actual encoded install bytes are corrupted: current entries clear while independent ROOT coverage remains valid. Historical proof is not presented as current verification. |
| `reuse-root-missing-200pct-1280x800.png` | Actual ROOT bytes are removed: current variants and ROOT coverage clear, with no automatic repair. |

The assertions also check stable original acquisition facts, exact-build and locale consent isolation, no preferred-B acquisition alias, unchanged physical-object and source-alias counts, and pinned ROOT/INSTALL retention scopes. The held-check case proves cancellation and context isolation; its wrapper may prevent the downstream call. It does **not** establish delivery of an uncancellable late callback, which is covered separately by presentation-model tests. This fixture does not create Item readiness and therefore makes no native claim about dependent Item readiness invalidation.

All application-phase transport attempts are forbidden and the successful run attempted none. Initial authored responses do not use HTTP. Metadata full-decoded and grammar proofs apply to the admitted ROOT/INSTALL objects; they do not establish readable game assets, item names, icons or a complete build archive.

Captures deliberately scroll the application body to the inspected cost, action, result or proof. Programmatic focus checks supplement the images; this evidence does not certify physical keyboard input, screen readers, other operating systems or live CDN behavior. Each application child has a 120-second deadline and the supervisor allows 150 seconds, confirms worker termination before temporary-store cleanup, and preserves storage if termination cannot be confirmed.

To reproduce after `:bootstrap:installDist` and compilation of all `tools/ui` Java sources:

```bash
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureMetadataReuseWorkflow --isolated-display :191 --output build/ui-review/metadata-reuse
```

Use an explicitly isolated display, never the normal user desktop. The fixture helpers are `MetadataReuseFixtureBackend.java` and `MetadataReuseInstallSeed.java`; the former also reuses the existing authored synthetic root generator. No production API visibility is widened for these tools.
