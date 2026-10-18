# VibeMiner application artwork

**Excavate** is the selected application icon, chosen from the [nine design concepts](../docs/design/app-icons/index.html) in [issue #97](https://github.com/aircan80/vibeminer/issues/97). It combines a mining tool with data blocks, using the Atlas teal, mint and gold palette. This is original artwork without Blizzard or Java marks.

[`vibeminer.svg`](vibeminer.svg) is the canonical vector source. Its rounded teal tile has transparent outer margins. Generated assets in `icons/` include:

- `vibeminer-{16,24,32,48,64,128,256,512,1024}.png`: 8-bit RGBA JavaFX/display sizes.
- `vibeminer.png`: exact copy of the 512 pixel PNG, for Linux packaging.
- `vibeminer.ico`: PNG-backed Windows frames at 16, 24, 32, 48, 64, 128 and 256 pixels.
- `vibeminer.icns`: PNG-backed macOS regular and Retina entries through 1024 pixels.

To regenerate, install ImageMagick 7 on a **development machine** and run:

```sh
python3 tools/branding/generate.py
```

The script renders the vector at 4096 pixels, downsamples with Lanczos, strips metadata, and constructs ICO/ICNS containers with Python's standard library. ImageMagick is not an application runtime or normal build requirement: generated assets are versioned. Regeneration with a different ImageMagick/SVG-renderer version may change raster pixels; review resulting changes before committing. The initial exports used ImageMagick 7.1.2-32.

Artwork files and valid containers are separate from native integration evidence. JavaFX stage/header use, installed launchers, dock/taskbar behavior, pinned shortcuts and platform packaging must be checked against the actual generated application. SVG is the source for future refinements; do not edit generated PNG or container payloads independently.
