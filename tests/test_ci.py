import importlib.util
import json
import fnmatch
import os
import re
import subprocess
import textwrap
from pathlib import Path
import tempfile
import stat
import tarfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location("ci", Path(__file__).parents[1] / "tools/ci.py")
ci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci)
SHA = "a" * 40


class BuildAutomationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def bootstrap(self):
        for name in ci.BUILD_FILES:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture")
        (self.root / "settings.gradle.kts").write_text("")
        (self.root / "build.gradle.kts").write_text("")

    def plan(self, **overrides):
        args = dict(root=self.root, event="push", ref="refs/heads/main", sha=SHA,
                    run_number="12", publish="none", version="")
        args.update(overrides)
        return ci.plan(**args)

    def test_documents_do_not_claim_application_packages(self):
        result = self.plan()
        self.assertEqual(result["app_available"], "false")
        self.assertEqual(result["publish"], "none")

    def test_incomplete_bootstrap_is_a_failure_not_a_skipped_build(self):
        (self.root / "gradlew").touch()
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            self.plan()

    def test_complete_bootstrap_enables_native_builds(self):
        self.bootstrap()
        result = self.plan()
        self.assertEqual(result["app_available"], "true")
        self.assertEqual(result["version"], "0.0.0-dev.12")
        self.assertEqual(result["package_version"], "0.0.12")

    def test_release_without_application_fails(self):
        with self.assertRaisesRegex(ValueError, "application"):
            self.plan(event="workflow_dispatch", publish="prerelease", version="0.1.0-rc.1")

    def test_manual_release_requires_main_and_valid_version(self):
        self.bootstrap()
        for changes in [dict(ref="refs/heads/feature"), dict(version="1.0.0; echo bad"),
                        dict(publish="release", version="1.0.0-rc.1")]:
            args = dict(event="workflow_dispatch", publish="prerelease", version="1.0.0-rc.1")
            args.update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.plan(**args)

    def test_version_tags_select_prerelease_or_release(self):
        self.bootstrap()
        self.assertEqual(self.plan(ref="refs/tags/v1.2.3-rc.1")["publish"], "prerelease")
        self.assertEqual(self.plan(ref="refs/tags/v1.2.3")["publish"], "release")

    def test_pull_requests_never_publish(self):
        self.bootstrap()
        with self.assertRaises(ValueError):
            self.plan(event="pull_request", publish="release", version="1.0.0")

    def package(self, target="windows-x64", private=False):
        source = self.root / target
        source.mkdir(exist_ok=True)
        with zipfile.ZipFile(source / "app.zip", "w") as archive:
            archive.writestr(".agent/private.txt" if private else "VibeMiner/bin/launcher", "fixture")
        return source

    def test_collect_produces_commit_bound_checksums(self):
        source = self.package()
        output = self.root / "output"
        manifest = ci.collect(source, output, "windows-x64", "0.1.0", SHA)
        data = json.loads(manifest.read_text())
        self.assertEqual(data["commit"], SHA)
        self.assertEqual(data["target"], "windows-x64")
        payload = output / data["files"][0]["name"]
        self.assertEqual(ci.digest(payload), data["files"][0]["sha256"])
        self.assertTrue(manifest.with_suffix(".sha256").exists())

    def test_empty_duplicate_and_private_packages_fail(self):
        source = self.root / "source"
        source.mkdir()
        with self.assertRaises(ValueError):
            ci.collect(source, self.root / "out", "windows-x64", "0.1.0", SHA)
        source = self.package(private=True)
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            ci.collect(source, self.root / "out", "windows-x64", "0.1.0", SHA)

    def test_missing_platform_prevents_release(self):
        output = self.root / "output"
        ci.collect(self.package(), output, "windows-x64", "0.1.0", SHA)
        with self.assertRaisesRegex(ValueError, "platform"):
            ci.verify_release(output, "0.1.0", SHA)

    def test_release_rejects_changed_payload(self):
        output = self.root / "output"
        ci.collect(self.package(), output, "windows-x64", "0.1.0", SHA)
        data = json.loads(next(output.glob("*.build.json")).read_text())
        (output / data["files"][0]["name"]).write_text("tampered")
        with self.assertRaisesRegex(ValueError, "integrity"):
            ci.verify_release(output, "0.1.0", SHA, targets={"windows-x64"})

    def test_release_requires_a_portable_payload(self):
        output = self.root / "output"
        ci.collect(self.package(), output, "windows-x64", "0.1.0", SHA)
        manifest = next(output.glob("*.build.json"))
        data = json.loads(manifest.read_text())
        data["files"] = []
        manifest.write_text(json.dumps(data))
        manifest.with_suffix(".sha256").write_text("")
        with self.assertRaisesRegex(ValueError, "payload"):
            ci.verify_release(output, "0.1.0", SHA, targets={"windows-x64"})

    def test_validated_single_platform_asset_set_is_complete(self):
        output = self.root / "output"
        ci.collect(self.package(), output, "windows-x64", "0.1.0", SHA)
        files = ci.verify_release(output, "0.1.0", SHA, targets={"windows-x64"})
        self.assertEqual(len(files), 3)

    def test_duplicate_package_extensions_are_rejected(self):
        source = self.package()
        (source / "another.zip").write_bytes((source / "app.zip").read_bytes())
        with self.assertRaisesRegex(ValueError, "duplicate"):
            ci.collect(source, self.root / "output", "windows-x64", "0.1.0", SHA)

    def test_private_case_variants_and_windows_paths_are_rejected(self):
        for name in [".AGENT/notes.txt", "app/.AWS/key", "C:/outside", "../outside"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                ci.check_archive_name(name)

    def test_package_links_cannot_escape_or_disclose_private_files(self):
        source = self.root / "links"
        source.mkdir()
        for target in ["../../outside", "/outside", "C:/outside", "../.agent/private"]:
            with zipfile.ZipFile(source / "app.zip", "w") as archive:
                info = zipfile.ZipInfo("app/link")
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                archive.writestr(info, target)
            with self.subTest(target=target), self.assertRaises(ValueError):
                ci.collect(source, self.root / "output", "windows-x64", "0.1.0", SHA)

    def test_safe_relative_runtime_symlinks_are_allowed(self):
        ci.check_archive_name("../runtime/lib/example", "VibeMiner/lib")

    def linked_archive(self, kind, entries):
        source = self.root / "linked-archive"
        source.mkdir(exist_ok=True)
        if kind == "zip":
            with zipfile.ZipFile(source / "app.zip", "w") as archive:
                for name, target in entries:
                    info = zipfile.ZipInfo(name)
                    info.create_system = 3
                    info.external_attr = (stat.S_IFLNK | 0o777) << 16
                    archive.writestr(info, target)
        else:
            with tarfile.open(source / "app.tar.gz", "w:gz") as archive:
                for name, target in entries:
                    info = tarfile.TarInfo(name)
                    info.type = tarfile.SYMTYPE
                    info.linkname = target
                    archive.addfile(info)
        return source

    def test_linked_ancestors_are_rejected_in_either_archive_order(self):
        for kind in ["zip", "tar"]:
            for reverse in [False, True]:
                entries = [("app/link", ".."), ("app/link/escape", "../../outside")]
                if reverse:
                    entries.reverse()
                with self.subTest(kind=kind, reverse=reverse):
                    source = self.linked_archive(kind, entries)
                    target = "windows-x64" if kind == "zip" else "linux-x64"
                    with self.assertRaisesRegex(ValueError, "linked ancestor"):
                        ci.collect(source, self.root / f"output-{kind}-{reverse}", target, "0.1.0", SHA)
                    for path in source.iterdir():
                        path.unlink()

    def test_regular_file_under_case_aliased_linked_ancestor_is_rejected(self):
        source = self.linked_archive("zip", [("app/link", "..")])
        with zipfile.ZipFile(source / "app.zip", "a") as archive:
            archive.writestr("app/LINK/payload.txt", "fixture")
        with self.assertRaisesRegex(ValueError, "linked ancestor"):
            ci.collect(source, self.root / "output", "windows-x64", "0.1.0", SHA)

    def test_hardlink_cannot_be_parent_of_an_archive_entry(self):
        source = self.root / "hardlinks"
        source.mkdir()
        with tarfile.open(source / "app.tar.gz", "w:gz") as archive:
            entry = tarfile.TarInfo("app/link")
            entry.type = tarfile.LNKTYPE
            entry.linkname = "app/target"
            archive.addfile(entry)
            archive.addfile(tarfile.TarInfo("app/link/payload"))
        with self.assertRaisesRegex(ValueError, "linked ancestor"):
            ci.collect(source, self.root / "output", "linux-x64", "0.1.0", SHA)

    def test_link_target_cannot_traverse_another_link_before_parent_segments(self):
        # Lexically this target stays in the archive. Resolving app/base first
        # changes the parent depth, so ../.. would escape during extraction.
        for kind in ["zip", "tar"]:
            with self.subTest(kind=kind):
                source = self.linked_archive(kind, [
                    ("app/base", ".."), ("app/escape", "base/../../outside")])
                target = "windows-x64" if kind == "zip" else "linux-x64"
                with self.assertRaisesRegex(ValueError, "linked ancestor"):
                    ci.collect(source, self.root / ("output-" + kind), target, "0.1.0", SHA)
                for path in source.iterdir():
                    path.unlink()

    def test_safe_runtime_leaf_link_chain_collects(self):
        for kind in ["zip", "tar"]:
            with self.subTest(kind=kind):
                source = self.linked_archive(kind, [
                    ("app/lib/example", "example.1"),
                    ("app/lib/example.1", "../runtime/example.1")])
                target = "windows-x64" if kind == "zip" else "linux-x64"
                ci.collect(source, self.root / ("output-" + kind), target, "0.1.0", SHA)
                for path in source.iterdir():
                    path.unlink()

    def test_special_tar_files_are_rejected(self):
        source = self.root / "special"
        source.mkdir()
        with tarfile.open(source / "app.tar.gz", "w:gz") as archive:
            entry = tarfile.TarInfo("app/pipe")
            entry.type = tarfile.FIFOTYPE
            archive.addfile(entry)
        with self.assertRaisesRegex(ValueError, "special"):
            ci.collect(source, self.root / "output", "linux-x64", "0.1.0", SHA)

    def test_release_rejects_unexpected_files_or_wrong_commit(self):
        output = self.root / "output"
        ci.collect(self.package(), output, "windows-x64", "0.1.0", SHA)
        with self.assertRaisesRegex(ValueError, "identity"):
            ci.verify_release(output, "0.1.0", "b" * 40, targets={"windows-x64"})
        (output / "unexpected.txt").write_text("extra")
        with self.assertRaisesRegex(ValueError, "Unexpected"):
            ci.verify_release(output, "0.1.0", SHA, targets={"windows-x64"})


class NativeMiningWorkflowTests(unittest.TestCase):
    WORKFLOW = Path(__file__).parents[1] / ".github/workflows/build.yml"
    STEP = "Check actual build mining at both sizes and text scales"

    def mining_step(self):
        workflow = self.WORKFLOW.read_text()
        match = re.search(r"      - name: " + re.escape(self.STEP) +
                          r"\n        if: runner.os == 'Linux'\n        run: \|\n((?:          .*\n|\n)+)", workflow)
        self.assertIsNotNone(match, "Linux actual mining journey must be a native CI step")
        self.assertLess(workflow.index("check packageDistribution verifyDistribution"), match.start())
        self.assertIn("dependsOn '"+":bootstrap:installDist"+"'", (self.WORKFLOW.parents[2] / "build.gradle").read_text())
        return textwrap.dedent(match.group(1))

    def execute_step(self, fail=False):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        (root / "bin").mkdir()
        (root / "tools/ui").mkdir(parents=True)
        # Model process boundaries, not Java behavior: actual runner must receive the isolated
        # DISPLAY and every viewport/scale, and a failed native process must fail the CI step.
        scripts = {
            "bin/timeout": '#!/bin/bash\n[[ "$1" == --kill-after=10s && "$2" == 180s ]] || exit 31\nshift 2\nexec "$@"\n',
            "bin/xvfb-run": '#!/bin/bash\n[[ "$1" == -a ]] || exit 32\nshift\nexport DISPLAY=:471\nexec "$@"\n',
            "tools/ui/run-build-mining-review.sh": '#!/bin/bash\nexec python3 - "$@" <<\'PYPROBE\'\nimport json,os,sys\nwith open(os.environ["MINING_RECORD"],"a") as out: out.write(json.dumps({"args":sys.argv[1:],"display":os.environ.get("DISPLAY")})+"\\n")\nprint("Authored mining seed and restart proof")\nif os.environ.get("MINING_FAIL"): sys.exit(19)\nPYPROBE\n',
        }
        for name, content in scripts.items():
            path = root / name
            path.write_text(content)
            path.chmod(0o755)
        record = root / "calls.jsonl"
        env = dict(os.environ, PATH=str(root / "bin") + os.pathsep + os.environ["PATH"],
                   MINING_RECORD=str(record))
        if fail:
            env["MINING_FAIL"] = "1"
        result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", self.mining_step()],
                                cwd=root, env=env, capture_output=True, text=True, timeout=10)
        calls = [json.loads(line) for line in record.read_text().splitlines()] if record.exists() else []
        return root, result, calls

    def test_mining_ci_runs_all_four_isolated_seed_restart_cases_and_retains_proof_text(self):
        root, result, calls = self.execute_step()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(4, len(calls))
        self.assertEqual({("1440", "1100", "100"), ("1440", "1100", "200"),
                          ("1280", "800", "100"), ("1280", "800", "200")},
                         {tuple(call["args"][2:]) for call in calls})
        for call in calls:
            self.assertEqual(":471", call["display"])
            self.assertEqual(call["display"], call["args"][0])
            output = root / call["args"][1]
            self.assertIn("Authored mining seed and restart proof", (output / "results.txt").read_text())

    def test_mining_ci_native_failure_propagates_instead_of_being_hidden_by_tee(self):
        _, result, calls = self.execute_step(fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(calls))

    def test_ui_artifact_globs_exclude_mining_payloads_and_compiled_tools(self):
        workflow = self.WORKFLOW.read_text()
        artifact = workflow.split("name: UI-review-${{ matrix.target }}", 1)[1]
        block = artifact.split("          path: |\n", 1)[1].split("          if-no-files-found:", 1)[0]
        patterns = [line.strip() for line in block.splitlines() if line.strip()]
        def uploaded(path):
            included = False
            for pattern in patterns:
                if pattern.startswith("!"):
                    if fnmatch.fnmatchcase(path, pattern[1:]):
                        included = False
                elif fnmatch.fnmatchcase(path, pattern):
                    included = True
            return included
        prefix = "build/ui-review/build-mining/1440x1100-100pct/"
        for name in ["seed/atlas-mining.png", "restart/atlas-mining.png", "results.txt"]:
            self.assertTrue(uploaded(prefix + name), name)
        for name in ["authored-data/catalog.sqlite", "authored-data/mining-review-complete.txt",
                     "authored-data/content/objects/proof.txt", "tool-classes/Fixture.class",
                     "tool-classes/diagnostic.txt"]:
            self.assertFalse(uploaded(prefix + name), name)


if __name__ == "__main__":
    unittest.main()
