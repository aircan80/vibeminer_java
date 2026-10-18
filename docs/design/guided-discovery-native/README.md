# Guided discovery: standalone JavaFX review

The standalone [capture harness](../../../tools/ui/CaptureGuidedDiscoveryPane.java) exercises the actual `GuidedDiscoveryPane` and its scoped stylesheet for [#99](https://github.com/aircan80/vibeminer/issues/99). It is presentation qualification, not the final application integration or an implementation of the application build library.

All projections are authored fixtures. Product/build/configuration combinations, locality and publication facts are synthetic; they are never shipped as production observations. The harness constructs no application services and performs no discovery, acquisition, database or game-install operations. Callbacks record presentation events only. Local and Currently advertised are independent supplied facts. Local includes explicit partial preparation in these examples, not merely a retained publication observation.

## Capture matrix

Supported fixture states: `ready`, `offline`, `loading`, `empty`, `longproducts`, `selectiondisabled`.

Supported viewports: **1440×1100** and **1280×800**, each at **100% / 200% application text scale**. The 200% case doubles the scene's base font size, matching the application's text-scale approach. It does not establish OS DPI scaling, native assistive-technology certification or full application chrome fidelity. The fixture banner is capture-only and is not part of the production pane.

Each invocation first checks real JavaFX interactions: projection restoration causes no callbacks; row selection and Enter inspect without opening; unsupported selection cannot open; the sole Open Build action preserves the exact group identity; deliberate product selection dispatches once; filters, explicit refresh, source details and paging dispatch separately. Final checks cover product-control bounds in the first viewport, exact capture dimensions, one-or-zero Open buttons, disabled/busy admission, scroll-reachable Open Build with keyboard focus (returning to the top before capture), complete accessible/tooltip product descriptions when visually truncated, and rendered row accessibility for Local/Currently advertised.

The ready view contains one US/EU/KR group and a distinct TW configuration with identical version text, plus a local canceled-preparation example. Grouping, persistent New semantics and membership verification are **inputs**, not work performed by this pane or proven by this harness. Keyboard events are programmatic JavaFX events; physical hardware input, IME and a screen reader require separate qualification. Snapshot comparison with the [guided browser reference](../build-discovery/index.html?concept=guided) remains a human review task.

## Reproduce

Use the full JDK from [BUILDING.md](../../../BUILDING.md). Build the desktop classes/resources and prepare the existing bundled dependency directory:

```sh
./gradlew :desktop:classes :bootstrap:installDist
mkdir -p /tmp/vibeminer-guided-native-classes
javac -cp 'desktop/build/classes/java/main:desktop/build/resources/main:bootstrap/build/install/bootstrap/lib/*' \
  -d /tmp/vibeminer-guided-native-classes tools/ui/CaptureGuidedDiscoveryPane.java
```

Choose an explicitly isolated display that you own. For a root-owned `:191` already running at least 1440×1100:

```sh
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp '/tmp/vibeminer-guided-native-classes:desktop/build/classes/java/main:desktop/build/resources/main:bootstrap/build/install/bootstrap/lib/*' \
  CaptureGuidedDiscoveryPane --isolated-display :191 \
  --output /tmp/vibeminer-guided-native-review \
  --width 1440 --height 1100 --scale 100 --state ready
```

Repeat with the supported viewport, scale and state combinations. A wrong/missing display acknowledgement, unsupported argument combination, failed assertion or 30-second deadline fails the process. Each successful run writes one scene-only PNG named `guided-STATE-WIDTHxHEIGHT-SCALEpct.png` and reports its actual assertion count. It never captures the desktop.

## Current evidence

Root executed all **24 combinations** (six states × two sizes × two text scales): **520 assertions passed**. Each capture checked stage dimensions, first-viewport product visibility, callback restoration guards, selection versus Open, enabled-action keyboard focus and scroll reachability. Long product selection retains its complete value in accessible text and a tooltip. Independent source/usability review accepted the component.

Representative screenshots:

- [Ready, 1440×1100, 100%](guided-ready-1440x1100-100pct.png)
- [Ready, 1280×800, 200%](guided-ready-1280x800-200pct.png)
- [Offline, 1280×800, 100%](guided-offline-1280x800-100pct.png)
- [Long products, 1280×800, 200%](guided-longproducts-1280x800-200pct.png)
- [Loading, 1440×1100, 100%](guided-loading-1440x1100-100pct.png)
- [Opening unavailable, 1280×800, 100%](guided-selectiondisabled-1280x800-100pct.png)

The root visually reviewed normal and 200% ready captures against the guided reference, plus representative loading/offline/long-product states. At 200% the page scrolls; Open remains keyboard reachable after scrolling. The long selected product description can ellipsize, with its full value available through tooltip/accessibility. This proves the standalone component only: full Atlas chrome, application build-library binding, real Open metadata and final integrated screenshot regression remain outstanding under #99/#100/#105.
