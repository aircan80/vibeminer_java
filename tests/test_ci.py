import importlib.util
import json
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


if __name__ == "__main__":
    unittest.main()
