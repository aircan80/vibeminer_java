# Actual build-opening desktop review

`tools/ui/CaptureBuildOpeningIntegration.java` runs the actual Atlas shell, metadata library,
lazy content initialization, build-opening application service, concrete metadata adapters,
SQLite and app-owned content storage. Its sole HTTP transport is
`tools/ui/fixtures/BuildOpeningReviewData.java`: deterministic authored responses, never live
Blizzard data. Admissions are returned by the actual gateway; the harness does not manufacture
readiness or supply a fake admission.

Native execution and independent visual review **passed** all four cases: **124 assertions**
and **24 scene PNGs**. [Machine-readable results](results.json) identify every image checksum.
Application base `e18c986` passed a clean full check with 1,892 Java cases; the captured Atlas
includes its subsequent visibility correction `5dda801`. The first native run caught a clipped
Cancel control, which was corrected using actual viewport bounds before this final matrix.
The retry observer also now waits through the transient JavaFX skin attachment state.
These results do not qualify background retention or a new release.

The journey checks startup has zero HTTP; one product selection performs the paired source
refresh; filtering/selection do not open content; a held encoding source exposes real progress
and keyboard-accessible Cancel in the inspector; cancellation stays in discovery; retry
completes the seven-role verified metadata closure; the admitted overview has unknown payload
coverage and unavailable bulk controls; Back preserves selection/filter; and offline reopening
uses retained verified metadata with zero additional HTTP. No game payload is requested.
The encoding hold is a synchronous pre-response fixture gate, not a simulation of every network
cancellation race. Viewports use application text scaling, not operating-system DPI certification.

Run from the repository root after compiling the current application and bootstrap classes.
Use a fresh output directory per matrix entry; temporary authored SQLite/CAS data stays there.
The display must already be an isolated review display; the harness refuses a mismatched
`DISPLAY`. It does not start or stop displays.

```sh
JAVA_HOME=/tmp/vibeminer-toolchain/jdk-25.0.4.1+1 ./gradlew :bootstrap:classes :bootstrap:installDist
mkdir -p /tmp/vibeminer-open-integration-classes
/tmp/vibeminer-toolchain/jdk-25.0.4.1+1/bin/javac \
  -cp 'desktop/build/classes/java/main:bootstrap/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main:infrastructure/build/classes/java/main:bootstrap/build/install/bootstrap/lib/*' \
  -d /tmp/vibeminer-open-integration-classes \
  tools/ui/fixtures/BuildOpeningReviewData.java tools/ui/CaptureBuildOpeningIntegration.java
DISPLAY=:191 /tmp/vibeminer-toolchain/jdk-25.0.4.1+1/bin/java \
  --enable-native-access=ALL-UNNAMED -Dprism.order=sw \
  -cp '/tmp/vibeminer-open-integration-classes:desktop/build/classes/java/main:bootstrap/build/classes/java/main:application/build/classes/java/main:domain/build/classes/java/main:infrastructure/build/classes/java/main:desktop/src/main/resources:bootstrap/build/install/bootstrap/lib/*' \
  io.vibeminer.bootstrap.CaptureBuildOpeningIntegration \
  --isolated-display :191 --output /tmp/vibeminer-open-review/1440-100 \
  --width 1440 --height 1100 --scale 100
```

Repeat for `1440 × 1100` and `1280 × 800`, each at `100` and `200` application text scale.
Each run writes six scene snapshots: `selected`, `preparing`, `canceled`, `admitted`, `back`,
and `offline-reopened`, with viewport and scale in filenames. Review exact identity and all
instructions at both scales, the active progress/Cancel geometry, honest unknown metrics,
plain header fields and the preserved discovery context. The final matrix passed and all 24 images received independent visual acceptance.

## Representative captures

- [Selected build](atlas-open-selected-1440x1100-100pct.png)
- [Actual metadata preparation and visible Cancel at 200%](atlas-open-preparing-1280x800-200pct.png)
- [Admitted C overview](atlas-open-admitted-1440x1100-100pct.png)
- [Admitted C overview at 200%](atlas-open-admitted-1280x800-200pct.png)
- [Canceled preparation preserves selection](atlas-open-canceled-1440x1100-100pct.png)
- [Offline reopened C at 200%](atlas-open-offline-reopened-1280x800-200pct.png)

The authored request count is 12 or 13 depending on whether cancellation wins before the held
EN response is consumed; no request reaches a live server. Every case independently verifies
zero game-payload acquisition and zero additional HTTP on the successful offline reopen.
