# VibeMiner design options

Open [index.html](index.html) in a desktop browser to compare three interactive desktop design mockups for the JavaFX application. All three run locally without installation, external fonts, third-party dependencies, a game installation or network access. Keep the shared research.js and research.css alongside the concept folders.

- **Forge:** compact graphite and amber workspace for technical investigation.
- **Atlas:** light research workbench for readable evidence and connected notes.
- **Observatory:** midnight visual studio for assets and discovery.

**Selected implementation direction: Atlas, rebuilt with JavaFX controls and CSS.** The other two concepts remain available for comparison.

Every direction includes its own navigation, working demo controls, fictional data, source disclosure and representative application states. These are UI prototypes, not working CDN clients. Original CSS/SVG artwork illustrates assets; it is not extracted Warcraft content. Exported files contain only demo data.

For HTTP preview, run from the repository root:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory mockups
```

Then open http://127.0.0.1:8765/. A server is optional; direct file opening also works.

See [WORKFLOWS.md](WORKFLOWS.md) for domain coverage and each option's README for its design rationale. The root product documents record the current name, research journeys, independent CDN archive and shared-storage requirements. The implementation stack is Java + JavaFX; HTML/CSS/JavaScript files are review prototypes only.

## Review and verification

A WoW mining reviewer and a separate UI reviewer checked the three options. Follow-up changes included table-specific records, coherent record inspectors, saved notes/evidence and watchlists, source-aware exports, and narrow-window inspector behavior.

Final local Node checks passed JavaScript syntax and rendering through a lightweight DOM harness for 31 screen views and 15 representative dialogs, including the shared research integration. Generated event handlers were checked for syntax. These checks do not establish browser layout, accessibility, or actual end-to-end behavior. Browser visual verification could not run in this environment: local sockets were denied and headless browsers failed to start. Open the mockups in your normal desktop browser to review their appearance and interactions.

The shared research entry points cover collectibles, classes/combat, crafting, story/world, art/sound and developer data. Research search offers fourteen fictional findings with related IDs, field changes, private notes, watch toggles and source-aware JSON exports. Each concept also includes a CDN-only historical build archive with deduplicated storage accounting.
