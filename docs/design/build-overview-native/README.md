# C build overview: standalone JavaFX qualification

[CaptureBuildOverviewPane](../../../tools/ui/CaptureBuildOverviewPane.java) displays the actual isolated `BuildOverviewPane` and scoped stylesheet for [#104](https://github.com/aircan80/vibeminer/issues/104). It supplies authored immutable display projections, with a visible **AUTHORED C OVERVIEW DATA · NO DOWNLOAD SERVICE** marker. There is no Atlas integration, application facade, downloader, database, CAS, network client or game-install access in this harness.

The production pane contains no fixture values or automatic progress. Controlled transitions in this tool test presentation bindings; they do not implement metadata verification, scope sealing, retention, cancellation or foreground scheduling. Full in a fixture is not evidence that a real build was retained. The eventual application adapter must supply genuine current #100/#102/#103 facts and capabilities.

## Matrix

Run every row at **1440×1100** and **1280×800**, each with **100% and 200% application text scale**: 24 invocations. Text scaling doubles the scene base font; this is not OS DPI certification. All fixture scope labels explicitly cover **all declared languages and platforms**, independent of the research locale enUS.

| Fixture state | Supplied status | Retained header | Supplied policy/activity |
| --- | --- | --- | --- |
| `preparing` | Not ready | Unknown | On demand / verifying metadata; policy unavailable |
| `partial` | Partial | 3.55% | On demand |
| `downloading` | Partial | 15.71% | Download all / Downloading |
| `paused` | Partial | 15.71% | Download all / Paused |
| `full` | Fully downloaded | 100% | On demand; a policy request cannot downgrade supplied readiness |
| `corrupt` | Not ready | Unknown | On demand / Needs attention; separate retry capabilities |

The values are illustrative. The denominator is selected-scope encoded coverage; physical local/shared bytes are intentionally separate observations and never used to derive the retained percentage.

## Assertions and visual review

Before capturing the chosen state, the harness checks real JavaFX controls:

- Constructing/restoring projections dispatches no actions. Choosing the same mode emits no change.
- An enabled mode request contains the exact context and scope revision. The controls, mode summary, retained bytes and Full status do not optimistically change without a new supplied observation.
- Unavailable or busy mode controls cannot dispatch; source, back and separate retry actions remain distinct.
- Plain Status follows Locale, then Retained and Download mode. Only the Readiness body contains the two mode buttons; the mode header contains no percentage. Research launchers are absent.
- The retained header matches the independent authored expectation. Every rendered pane label is checked for truncation and sufficient wrapped height. Actual card/label bounds must stay inside the facts region, below the scope line and above the storage/readiness body; independent label-height checks alone do not establish non-overlap.
- The policy control can be scrolled fully into the viewport, with keyboard focus retained when available. The capture returns to the identity header afterward.

Keyboard focus and button activation use programmatic JavaFX calls. A physical keyboard/mouse, IME, screen reader, OS DPI scale and final integrated Atlas journey need separate qualification. Scene snapshots are for human comparison with the [selected C reference](../build-mining-home/index.html?concept=archive), not an automatic assertion of visual fidelity. The fixture banner is capture-only; the isolated pane relies on its eventual app shell for branding/navigation.

## Compile and run

Use a full JDK 25 and the bundled dependency directory from [BUILDING.md](../../../BUILDING.md). Compilation below uses a private class directory, keeping capture compilation separate from concurrent development builds:

```sh
./gradlew :bootstrap:installDist
mkdir -p /tmp/vibeminer-overview-native-classes
javac -cp 'bootstrap/build/install/bootstrap/lib/*' \
  -d /tmp/vibeminer-overview-native-classes \
  desktop/src/main/java/io/vibeminer/desktop/BuildOverviewPane.java \
  tools/ui/CaptureBuildOverviewPane.java
```

On an explicitly isolated display available in your environment, for example `:191`:

```sh
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp '/tmp/vibeminer-overview-native-classes:desktop/src/main/resources:bootstrap/build/install/bootstrap/lib/*' \
  CaptureBuildOverviewPane --isolated-display :191 \
  --output /tmp/vibeminer-overview-native-review \
  --width 1440 --height 1100 --scale 100 --state partial
```

Repeat for each matrix combination. The explicit display argument must match DISPLAY. Unsupported inputs, an assertion failure or the 30-second watchdog fail the process. Each successful invocation reports its actual assertion count and writes one scene-only PNG named `overview-STATE-WIDTHxHEIGHT-SCALEpct.png`. It never captures the desktop.

## Evidence status

Exact `d627a93` passed all **24 native matrix cases with 3,288 assertions**, following eight focused presentation-contract tests. Independent visual review accepted the corrected layout, including unknown/error states at 200%. An earlier visual review rejected overlapping unknown-value fact cards; the final harness checks parent/card containment as well as label dimensions, and the complete matrix was rerun successfully.

[Machine-readable results](results.json) record the exact source, each case and published image checksums. Representative captures: [partial](overview-partial-1440x1100-100pct.png), [downloading](overview-downloading-1440x1100-100pct.png), [full](overview-full-1440x1100-100pct.png), [paused](overview-paused-1280x800-100pct.png), [unknown values at 200%](overview-preparing-1280x800-200pct.png), and [needs attention at 200%](overview-corrupt-1280x800-200pct.png).

These are standalone component results with authored projections. Atlas branding/navigation, real asynchronous overview projections, download services and the complete Open Build journey remain separate integration gates. No real build download or readiness claim follows from a fixture state.
