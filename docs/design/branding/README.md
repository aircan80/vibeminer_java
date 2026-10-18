# Selected Excavate application branding

Excavate (option 02) was selected in [issue #97](https://github.com/aircan80/vibeminer/issues/97). The [canonical artwork and regeneration instructions](../../../branding/README.md) are versioned alongside generated PNG, ICO and ICNS assets. The desktop header uses the same cached 32px image as the JavaFX window-icon set. Packaging explicitly supplies the host platform icon and verifies the generated and re-extracted app image.

Local Linux validation passed: 1,409 Java tests, 32 Python helper tests, native distribution creation and verification, bundled self-test and JavaFX smoke. The scene-only captures below assert nine window-icon sizes and shared header image identity with synthetic discovery and no HTTP. They show the existing production desktop with its new branding; the redesigned build-browser screens remain separate mockups and plans.

- [1280×800 scene](excavate-atlas-1280x800.png)
- [1440×1000 scene](excavate-atlas-1440x1000.png)

Reproduce after `./gradlew :bootstrap:installDist` on an isolated graphical display:

```sh
javac -cp 'bootstrap/build/install/bootstrap/lib/*' -d build/ui-tool tools/ui/CaptureBranding.java
DISPLAY=:99 java --enable-native-access=ALL-UNNAMED \
  -cp 'build/ui-tool:bootstrap/build/install/bootstrap/lib/*' CaptureBranding \
  --isolated-display :99 --output build/ui-review/branding
```

Use an isolated display you own. JavaFX window metadata and a Linux app image do not establish Windows/macOS dock/taskbar or pinned-shortcut behavior. Matching native CI package checks remain required. No new release is implied.
