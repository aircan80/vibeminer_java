import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('native_icon', Path(__file__).parents[1] / 'tools/verify_native_icon.py')
icons = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(icons)


def png(label=b'AUTHORED'):
    return b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' + struct.pack('>II', 16, 16) + label


def ico(body=None):
    body = png() if body is None else body
    entry = struct.pack('<BBBBHHII', 16, 16, 0, 0, 1, 32, len(body), 22)
    return b'\0\0\1\0\1\0' + entry + body


def pe(source):
    metadata, body = icons.ico_images(source)[0]
    group = b'\0\0\1\0\1\0' + metadata + struct.pack('<H', 7)
    tree = {3: {7: {1033: body}}, 14: {1: {1033: group}}}
    resource = bytearray()
    pending = []

    def directory(children):
        offset = len(resource)
        resource.extend(bytes(16 + len(children) * 8))
        struct.pack_into('<H', resource, offset + 14, len(children))
        for i, (key, value) in enumerate(children.items()):
            if isinstance(value, dict):
                target = directory(value) | 0x80000000
            else:
                target = len(resource)
                resource.extend(bytes(16))
                pending.append((target, value))
            struct.pack_into('<II', resource, offset + 16 + i * 8, key, target)
        return offset
    directory(tree)
    for offset, payload in pending:
        struct.pack_into('<II', resource, offset, 4096 + len(resource), len(payload))
        resource.extend(payload)
    header = bytearray(512)
    header[:2] = b'MZ'
    struct.pack_into('<I', header, 60, 128)
    header[128:132] = b'PE\0\0'
    struct.pack_into('<H', header, 134, 1)
    struct.pack_into('<H', header, 148, 240)
    struct.pack_into('<H', header, 152, 0x20b)
    struct.pack_into('<II', header, 152 + 112 + 16, 4096, len(resource))
    struct.pack_into('<IIII', header, 392 + 8, len(resource), 4096, len(resource), 512)
    return bytes(header + resource)


class NativeIconTests(unittest.TestCase):
    def test_windows_transformed_resource_ids_preserve_exact_payload(self):
        source = ico()
        icons.verify_windows(source, pe(source))

    def test_windows_wrong_artwork_rejected(self):
        with self.assertRaises(ValueError):
            icons.verify_windows(ico(), pe(ico(png(b'WRONG'))))

    def test_truncated_or_invalid_pe_rejected(self):
        for body in (b'', b'MZ', pe(ico())[:-3]):
            with self.subTest(length=len(body)), self.assertRaises(ValueError):
                icons.verify_windows(ico(), body)

    def test_invalid_ico_rejected(self):
        for body in (b'', b'\0\0\1\0\0\0', ico()[:-2], ico(b'not a PNG'), ico(b'\x89PNG\r\n\x1a\n')):
            with self.subTest(body=body), self.assertRaises(ValueError):
                icons.ico_images(body)

    def test_selected_canonical_assets_validate(self):
        root = Path(__file__).parents[1] / 'branding/icons'
        for target, suffix in [('linux-x64', 'png'), ('macos-x64', 'icns'), ('windows-x64', 'ico')]:
            icons.verify(target, root / ('vibeminer.' + suffix))

    def test_missing_and_wrong_packaged_linux_icon_rejected(self):
        root = Path(__file__).parents[1] / 'branding/icons'
        with tempfile.TemporaryDirectory() as temp:
            image = Path(temp)
            with self.assertRaises(FileNotFoundError):
                icons.verify('linux-x64', root / 'vibeminer.png', image)
            (image / 'lib').mkdir()
            (image / 'lib/VibeMiner.png').write_bytes(b'wrong')
            with self.assertRaises(ValueError):
                icons.verify('linux-x64', root / 'vibeminer.png', image)
            (image / 'lib/VibeMiner.png').write_bytes((root / 'vibeminer.png').read_bytes())
            icons.verify('linux-x64', root / 'vibeminer.png', image)

    def test_macos_exact_resource_and_corruption(self):
        source = Path(__file__).parents[1] / 'branding/icons/vibeminer.icns'
        with tempfile.TemporaryDirectory() as temp:
            image = Path(temp)
            resource = image / 'Contents/Resources/VibeMiner.icns'
            resource.parent.mkdir(parents=True)
            resource.write_bytes(source.read_bytes())
            icons.verify('macos-arm64', source, image)
            resource.write_bytes(resource.read_bytes()[:-1])
            with self.assertRaises(ValueError):
                icons.verify('macos-arm64', source, image)

    def test_missing_source_and_unsupported_target_fail(self):
        with self.assertRaises(FileNotFoundError):
            icons.verify('linux-x64', '/does-not-exist')
        with self.assertRaises(ValueError):
            icons.verify('unknown', Path(__file__))
