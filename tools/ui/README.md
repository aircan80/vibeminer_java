# Atlas screenshot review tool

`CaptureAtlas.java` launches the real JavaFX interface with synthetic discovery fixtures, opens Research and Archive, and writes baseline PNGs at 1440×1100 and 1280×800, plus selected-workspace and 200% text-scale images. It also asserts Enter opens the selected source dialog, closing it restores table focus, multi-character queries survive navigation, text-scale preference survives navigation, and source controls survive asynchronous failure. The key event is programmatic; this is not physical keyboard or assistive-technology testing.

It makes no network requests and captures only its own JavaFX scene, never a desktop screenshot. These images demonstrate rendering, not mining functionality, accessibility certification or installer validation.

On Linux, use a full JDK 25, the project's Gradle build and an isolated Xvfb display. Do not point this tool at an existing user desktop. The explicit display argument must match `DISPLAY`. Output belongs in an ignored build directory.

```bash
set -e
./gradlew :bootstrap:installDist
mkdir -p build/ui-tool build/ui-review
javac -cp 'bootstrap/build/install/bootstrap/lib/*' -d build/ui-tool tools/ui/CaptureAtlas.java
Xvfb :191 -screen 0 1440x1100x24 -nolisten tcp -ac > build/ui-review/xvfb.log 2>&1 &
VIBEMINER_XVFB_PID=$!
trap 'kill "$VIBEMINER_XVFB_PID"' EXIT
DISPLAY=:191 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' \
  CaptureAtlas --isolated-display :191 --output build/ui-review
```

Choose an unused display number and update both places if `:191` is occupied. `-ac` is appropriate only for a temporary isolated local review server; TCP listening is disabled. The JDK must include `java.desktop` for PNG encoding. The harness uses application fixture controls and waits for JavaFX layout pulses before capture; a changed navigation contract or wrong image dimensions fails visibly. The rendering sequence has an overall process deadline; do not treat missing PNGs or an abnormal exit as a pass.

Current platform target for this helper is Linux/X11. Cross-platform application packaging is checked separately by the Gradle distribution tasks and native CI. The helper uses the installed development distribution, not the trimmed packaged runtime.
