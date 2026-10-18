# Actual mining and offline restart

Exact source `76a7e837142c66bcc3d344e69c23e513fa44ac8a` passed a clean full check with **2,083 Java tests**, zero failures/errors/skips, Linux packaging, bundled-runtime self-test and JavaFX smoke.

The actual application journey passed four size/text-scale cases (1440×1100 and 1280×800, each at 100% and 200% application text): **652 assertions and 32 captured images**. Each case uses two fresh JVMs sharing only an app-owned authored catalog/content directory. Independent visual review accepted the corresponding workflow and header views. Selected captures and their hashes are recorded in [results.json](results.json).

The seed process selects a product/build, opens all seven required metadata roles, prepares actual sealed inventory, displays ordinary Partial, enables Download all with a held actual HTTP response, verifies active downloading and retained percentage, reaches Fully downloaded, and switches to On demand without losing retained content. The restart process performs an honest failed discovery refresh while offline, then opens the retained build with no additional metadata/content HTTP attempts and re-establishes Fully downloaded. Historical discovery availability and current content readiness remain separate.

The vector contains one authored content member (68 encoded bytes). These checks establish service/UI/storage behavior; they make no claim about live CDN formats, retail-size throughput, all archive routes, or all products. Separate integration checks cover interrupted Download all followed by automatic resume before another Open, preserving exact generation/policy and preventing completed metadata redownload.

Reproduce with JDK 25, `./gradlew :bootstrap:installDist`, an explicitly isolated display and:

```sh
DISPLAY=:191 tools/ui/run-build-mining-review.sh :191 /fresh/output 1440 1100 100
```

Use a fresh output directory for each case. The runner starts no display and uses authored transport responses. Its catalog/content and compiled tools are excluded from CI screenshot artifacts. [CI 39159259610](https://github.com/aircan80/vibeminer/actions/runs/39159259610) passed all four native targets and fifteen UI workflows at subsequent exact `072b46e278f72df17b17500f7fdd6e44ba1a9764`, including all four mining pairs with the same 652 assertions. Its results apply to that exact source.

This is development evidence. Dated chooser readiness observations and final milestone acceptance remain unfinished; alpha.6 remains the published release.
