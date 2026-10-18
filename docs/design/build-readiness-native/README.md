# Dated build readiness and actual mining

Exact source `8baaadbfb78732b39f2f2c37809718e76d61309d` passed a clean full check with **2,144 Java tests** (zero failures, errors or skips), **40 Python helper tests**, Linux distribution verification, bundled-runtime self-test and JavaFX smoke.

Four actual JavaFX cases passed at 1440×1100 and 1280×800, each at 100% and 200% application text: **956 assertions and 56 captures**. Each case runs seed and restart in separate JVMs against actual application services, SQLite and app-owned shared storage. Seed passes 184 assertions; restart passes 55. Selected images and SHA-256 hashes are in [results.json](results.json).

The journey covers product/build selection, complete seven-role authored metadata admission, Partial, background Download all, active progress, Fully downloaded, On demand without deletion, and dated chooser observations. Restart reopens retained content offline with zero metadata/content HTTP attempts. The harness then corrupts only its known authored payload: the chooser preserves its explicitly historical Full observation, while Open establishes current Partial with a corruption problem and no implicit repair. Returning to Discover records that newer dated Partial observation. Selection itself neither hashes content nor downloads it.

The vector has one authored 68-byte encoded payload. It establishes controlled behavior, not live-CDN compatibility, retail-size throughput, arbitrary formats, decryptability or a runnable installation. Separate actual integration tests cover foreground priority, safe cancellation, persisted background resume, injected publication I/O failures and seven process-death boundaries. Injected ENOSPC is not a physical full-volume test; process termination is not a power-loss proof.

Reproduce from this source with JDK 25, `./gradlew :bootstrap:installDist`, a fresh output directory and an explicitly isolated display:

```sh
DISPLAY=:191 tools/ui/run-build-mining-review.sh :191 /fresh/output 1440 1100 100
```

The runner starts no display. CI screenshot artifacts exclude authored catalogs/content and compiled tools. This page records local exact-source evidence; cross-platform release qualification is recorded separately in release notes after it completes.

[CI 39162118853](https://github.com/aircan80/vibeminer/actions/runs/39162118853) subsequently passed at this exact source: all four native targets and fifteen Linux UI workflows. Its mining matrix independently passed all 956 assertions, with zero metadata/content HTTP attempts in each fresh-JVM restart. This development checkpoint does not by itself establish final release-asset verification.
