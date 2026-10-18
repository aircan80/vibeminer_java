import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('package_legal', Path(__file__).parents[1] / 'tools/package_legal.py')
legal = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(legal)
SHA = 'a' * 40


class PackageLegalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        (self.source / 'LICENSE').write_bytes(b'Authored test license\n')
        (self.source / 'NOTICE').write_bytes(b'Authored test notice\n')
        (self.source / 'docs').mkdir()
        (self.source / 'docs/LICENSING.md').write_text('Authored licensing instructions')

    def test_all_platforms_copy_verify_exact_source_and_preserve_upstream(self):
        for target in ('linux-x64', 'windows-x64', 'macos-x64', 'macos-arm64'):
            image = self.root / target
            upstream = image / 'runtime/legal/java.base/LICENSE'
            upstream.parent.mkdir(parents=True)
            upstream.write_bytes(b'upstream sentinel')
            legal.process(self.source, image, target, SHA, '0.1.0-test')
            legal.process(self.source, image, target, SHA, '0.1.0-test', verify=True)
            dest = legal.legal_directory(image, target)
            self.assertEqual((dest / 'LICENSE').read_bytes(), (self.source / 'LICENSE').read_bytes())
            text = (dest / 'SOURCE.txt').read_text()
            self.assertIn('/tree/' + SHA, text)
            self.assertIn('/archive/' + SHA + '.tar.gz', text)
            self.assertNotIn('/main', text)
            self.assertIn('authorized repository access', text)
            self.assertEqual(upstream.read_bytes(), b'upstream sentinel')

    def test_missing_or_modified_packaged_file_fails(self):
        image = self.root / 'image'
        with self.assertRaises(FileNotFoundError):
            legal.process(self.source, image, 'linux-x64', SHA, '1.0', verify=True)
        legal.process(self.source, image, 'linux-x64', SHA, '1.0')
        (legal.legal_directory(image, 'linux-x64') / 'NOTICE').write_text('altered')
        with self.assertRaises(ValueError):
            legal.process(self.source, image, 'linux-x64', SHA, '1.0', verify=True)

    def test_wrong_commit_version_or_dirty_claim_fails(self):
        image = self.root / 'image'
        legal.process(self.source, image, 'windows-x64', SHA, '1.0')
        for commit, version, dirty in [('b' * 40, '1.0', False), (SHA, '2.0', False), (SHA, '1.0', True)]:
            with self.assertRaises(ValueError):
                legal.process(self.source, image, 'windows-x64', commit, version, dirty, verify=True)

    def test_missing_empty_legal_or_unknown_revision_rejected(self):
        for commit in ('main', '', 'a' * 39):
            with self.assertRaises(ValueError):
                legal.expected(self.source, commit, '1.0', False)
        (self.source / 'NOTICE').write_bytes(b'')
        with self.assertRaises(ValueError):
            legal.expected(self.source, SHA, '1.0', False)
        (self.source / 'NOTICE').unlink()
        with self.assertRaises(FileNotFoundError):
            legal.expected(self.source, SHA, '1.0', False)

    def test_dirty_build_honestly_labels_base_reference(self):
        text = legal.expected(self.source, SHA, '1.0', True)['SOURCE.txt'].decode()
        self.assertIn('BASE reference', text)
        self.assertIn('modified corresponding source', text)
        self.assertNotIn('identifies the following exact', text)
