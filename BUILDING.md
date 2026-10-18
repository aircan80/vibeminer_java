# Build and test VibeMiner

The application is being built incrementally. Current development packages are intended for testing the implemented shell/discovery capabilities; they are not a complete mining release. Test data must be labeled as fixtures. Check issue acceptance and release notes for verified capability coverage.

## From a clean checkout

Install a **full JDK 25**, including `javac`, `jlink` and `jpackage`. Set `JAVA_HOME` to that JDK and put its `bin` directory on PATH. The committed Gradle Wrapper downloads the pinned Gradle distribution; no separate Gradle installation is required. Gradle provisions Maven dependencies on first use. No game, Battle.net, externally installed database, Node or Python is needed to run the packaged application.

```sh
./gradlew check
./gradlew :bootstrap:run
```

On Windows use `gradlew.bat`. On Linux a graphical session is needed to launch JavaFX. Native CI uses `xvfb-run` for an isolated display; headless service tests are separate from real UI launch checks.

Build automation helper tests use Python 3.11+ on development/CI machines:

```sh
python3 -m unittest discover -s tests -v
```

Ordinary tests use synthetic fixtures and do not depend on live CDN availability. An explicit discovery action in the UI can contact the official source; launching the app must not probe game folders or silently start content downloads.

## Native packages

Each package is built on its matching OS/architecture. Cross-compiling a runtime image from another OS is not supported. Public application versions and artifact names retain their semantic version. For macOS only, the internal native bundle version increments the numeric major by one (`0.1.0` → `1.1.0`), because [JDK 25 requires a positive first CFBundleVersion component](https://github.com/openjdk/jdk25u/blob/master/src/jdk.jpackage/macosx/classes/jdk/jpackage/internal/CFBundleVersion.java). This does not indicate a 1.x feature release.

| Target | GitHub runner | Portable package |
| --- | --- | --- |
| `windows-x64` | Windows 2022 | ZIP |
| `macos-arm64` | macOS 15 Apple Silicon | ZIP containing `.app` |
| `macos-x64` | macOS 15 Intel | ZIP containing `.app` |
| `linux-x64` | Ubuntu 24.04 | tar.gz |

```sh
./gradlew check packageDistribution verifyDistribution \
  -PappVersion=0.1.0-dev.1 -PpackageVersion=0.1.0 \
  -PdistributionTarget=linux-x64
```

On headless Linux wrap that command with `xvfb-run -a`. `packageDistribution` writes only package files to `build/distributions/<target>/`. `verifyDistribution` extracts the generated archive to a separate location and checks its actual bundled launcher/runtime and JavaFX smoke launch. These checks establish launchability, not a complete research workflow or full accessibility certification.

CI collects the declared packages, verifies their file selection and writes SHA-256 checksums plus a build manifest containing target, version and exact commit. A complete Gradle bootstrap is required; partial wrappers/build files fail rather than silently skipping the app. Before a bootstrap exists, repository checks run but no application package or release is produced.

## Download from GitHub

On pushes to `main` and pull requests, [Build and package](https://github.com/aircan80/vibeminer/actions/workflows/build.yml) runs checks and native packaging. Open a successful run and download the artifact matching your target. Linux also runs the real-JavaFX fixture review harness and uploads separate `UI-review-*` screenshot artifacts; those are review evidence, not application packages. Workflow artifacts are retained for **14 days**; access requires repository read permission. They contain the portable package, checksum file and commit-bound manifest.

For longer-lived test builds, use [GitHub Releases](https://github.com/aircan80/vibeminer/releases). This repository is private; the builds remain available only to authorized readers.

## Publish a prerelease or release

Two explicitly triggered paths are supported:

1. In Actions → Build and package → Run workflow, choose `main`, select `prerelease` or `release`, and enter a new version without `v` (for example `0.1.0-rc.1`). Leave publication as `none` for downloadable workflow artifacts only.
2. Push a version tag pointing to a commit already on `main`: `v0.1.0-rc.1` produces a prerelease; `v0.1.0` produces a stable release. Tag events are explicit publication requests.

All four native targets must pass before publication. Versions must use package-compatible numeric components, optionally followed by a prerelease suffix; stable publication rejects prerelease versions. Existing releases and manually reused tags are rejected rather than overwritten. Release assets are rechecked against their expected version, exact commit, target set and checksums. Publication starts as a draft so failed asset uploads do not expose an apparently complete release. A failed publication may leave a draft for inspection.

Packages are initially **unsigned and not notarized**. OS trust prompts and signing/notarization readiness are separate from passing build tests. A native test package is not proof every mining feature is implemented or every platform workflow is validated. Release notes must state actual coverage and known limitations.

## UI visual review

Use [Atlas](mockups/atlas/index.html), the [UI specification](docs/UI.md) and matching-size screenshots. Capture only the application or an explicitly isolated virtual display. Review visual fidelity alongside keyboard/focus, scaling, empty/loading/error states and exact workspace context. See the portable capture tool under `tools/ui/` when present.
