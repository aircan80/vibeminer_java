# Guided discovery: integrated Atlas review

[CaptureGuidedDiscoveryIntegration](../../../tools/ui/CaptureGuidedDiscoveryIntegration.java) runs the actual Atlas shell, guided pane/presentation model, `DefaultBuildLibraryService`, pinned discovery mapper and schema-14 SQLite history/library repositories and reader. It qualifies the implemented discovery binding for [#99](https://github.com/aircan80/vibeminer/issues/99), separately from the [standalone layout matrix](../guided-discovery-native/README.md).

The **harness gateway** supplies authored versions/CDNs bytes at the application boundary. It has no HTTP transport. Production uses the separately implemented official paired metadata gateway; this review does not exercise that network adapter or establish real advertised builds. Official-role URLs in these authored `SourceDocument`s are synthetic source context, not evidence of retrieval. Host routes deliberately use `authored.invalid` and are never executed.

## What the journey checks

- A capture-only **AUTHORED REVIEW DATA · NO LIVE CDN** banner identifies the synthetic inputs; no fixture banner is added to production controls. The guided runtime exposes only Discover and supported Settings, with obsolete research destinations and workspace search/shortcut unavailable.
- Cold Atlas startup selects no product, invents no rows, performs no capture and creates no catalog file. Hidden legacy workspace/product/per-region discovery ports fail if called, covering the guided startup cleanup.
- One real UI product selection causes one paired gateway invocation, two separately persisted regional observations and one grouped row. A complete first visit establishes its baseline without marking everything New. Remembered publication is not Local or ready metadata.
- The source documents are actually parsed and stored: two regions share two physical raw-document blobs. Explicit Refresh adds a same-version/different-CDN-configuration group; its New marker appears while the old group retains its identity. Four retrieval events share three distinct document bodies.
- Local and Currently advertised controls query authoritative SQLite projections without another capture. Selection and Enter inspect without preparing a build. The selected inspector is captured after keyboard focus and scroll reachability checks for Open Build; the normal Retail product alias and every selected-inspector identity/help label are checked for actual JavaFX text truncation and sufficient wrapped height at both text scales. Open remains disabled because #100 metadata opening is not connected; no fake Home or readiness gateway is used and no workspace is created.
- Source details displays the actual persisted versions digest, selected CDN identity and raw Published channel. This is a real UI dialog over the selected application entry, not separately authored display strings.
- An authored capture failure leaves the prior saved rows visible. Switching to another exact product performs its own paired capture; a fresh reader/factory selects that product's persisted entry without substituting the previous product.
- No content store, game objects or source aliases are created. The harness never constructs a game-content CAS or acquisition client. The scratch SQLite database contains authored metadata only; it must not be included with published review images.

The assertions concern this two-screen milestone's discovery portion. They do not establish #100 metadata preparation, a sealed payload inventory, Download all, physical asset verification or a mining Home. Readiness remains **not assessed**. The SQL factory is reopened in the same process; this is not a two-JVM restart workflow. Real physical input, IME, assistive technology and cross-platform accessibility require separate review.

## Reproduce

Use a full JDK 25. Prepare the application classes and bundled dependencies:

```sh
./gradlew :desktop:classes :infrastructure:classes :bootstrap:installDist
mkdir -p /tmp/vibeminer-guided-integration-classes
javac -cp 'desktop/build/classes/java/main:desktop/build/resources/main:application/build/classes/java/main:domain/build/classes/java/main:infrastructure/build/classes/java/main:bootstrap/build/install/bootstrap/lib/*' \
  -d /tmp/vibeminer-guided-integration-classes tools/ui/CaptureGuidedDiscoveryIntegration.java
```

On an explicitly owned isolated display, for example `:191`:

```sh
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp '/tmp/vibeminer-guided-integration-classes:desktop/build/classes/java/main:desktop/build/resources/main:application/build/classes/java/main:domain/build/classes/java/main:infrastructure/build/classes/java/main:bootstrap/build/install/bootstrap/lib/*' \
  CaptureGuidedDiscoveryIntegration --isolated-display :191 \
  --output /tmp/vibeminer-guided-integration-review \
  --width 1440 --height 1100 --scale 100
```

Supported scene dimensions are **1440×1100** and **1280×800**; text scale is **100** or **200**. The 200% case uses Atlas's actual Settings text-scale control and returns to Discover; it is not OS DPI scaling. Window size is adjusted for decorations to obtain the requested scene dimensions. Only application/dialog scenes are captured, never the desktop. A missing display acknowledgement, failed assertion or 60-second watchdog fails the process.

Each invocation writes eight PNGs named `atlas-guided-STATE-WIDTHxHEIGHT-SCALEpct.png`, where STATE is `initial`, `grouped`, `selected`, `local-empty`, `variants`, `source-receipts`, `offline` or `product-changed`. Main-window screenshots use the declared scene dimensions; the source dialog uses its actual independently sized scene. An `authored-sql-*` scratch directory beside the images is private execution material; publish only reviewed PNGs. The success line reports the actual assertion and capture counts.

## Evidence status

Exact `be03a8e` passed a clean immutable full check: **1,707 Java tests**, Linux packaging, bundled-runtime verification and JavaFX launch. The integrated journey then passed **four native cases, 80 assertions each**, producing eight screenshots per case. Both scene sizes and both application text scales passed. An independent visual review accepted product placement, grouped builds, selection, offline state, exact source identities and readable wrapped explanations. The final layout prevents ordinary edition names and selected inspector text from being vertically truncated at 200%.

| Scene | Application text | Assertions | Result |
| --- | --- | --- | --- |
| 1440×1100 | 100% | 80 | PASS |
| 1440×1100 | 200% | 80 | PASS |
| 1280×800 | 100% | 80 | PASS |
| 1280×800 | 200% | 80 | PASS |

The [machine-readable results](results.json) bind the exact source revision and published image checksums. Representative images: [grouped builds](atlas-guided-grouped-1440x1100-100pct.png), [selected inspector](atlas-guided-selected-1440x1100-100pct.png), [offline history](atlas-guided-offline-1440x1100-100pct.png), [source receipts](atlas-guided-source-receipts-1440x1100-100pct.png), [200% product selection](atlas-guided-grouped-1280x800-200pct.png), and [200% inspector](atlas-guided-selected-1280x800-200pct.png). The selected images deliberately scroll to the action area; content above remains available through the outer page scroll.

Every capture visibly identifies authored review data. No scratch SQLite database is published. Open remains disabled because complete metadata opening is not connected in this checkpoint. These results establish the integrated discovery portion, not a mining Home or Download all. Source-dialog attribution/title sizing at 200% remains a minor polish limitation; receipts are readable by scrolling. Hardware input, assistive technology, OS DPI and other native platforms remain separate gates.
