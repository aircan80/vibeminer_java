#!/usr/bin/env python3
"""Verify selected launcher artwork in a jpackage image; no external image tools."""
import argparse
from pathlib import Path
import struct


def chunk(data, offset, length):
    if offset < 0 or length < 0 or offset + length > len(data):
        raise ValueError('Truncated native icon structure')
    return data[offset:offset + length]


def u16(data, offset):
    return struct.unpack('<H', chunk(data, offset, 2))[0]


def u32(data, offset):
    return struct.unpack('<I', chunk(data, offset, 4))[0]


def png_dimensions(data):
    if chunk(data, 0, 8) != b'\x89PNG\r\n\x1a\n' or chunk(data, 12, 4) != b'IHDR':
        raise ValueError('Invalid PNG header')
    width, height = struct.unpack('>II', chunk(data, 16, 8))
    if width <= 0 or height <= 0:
        raise ValueError('Invalid PNG dimensions')
    return width, height


def ico_images(data):
    if chunk(data, 0, 4) != b'\0\0\1\0' or not 1 <= u16(data, 4) <= 256:
        raise ValueError('Invalid ICO header')
    images = []
    for i in range(u16(data, 4)):
        entry = chunk(data, 6 + i * 16, 16)
        body = chunk(data, u32(entry, 12), u32(entry, 8))
        if png_dimensions(body) != (entry[0] or 256, entry[1] or 256):
            raise ValueError('ICO dimensions disagree with PNG payload')
        images.append((entry[:12], body))
    return images


def pe_icons(data):
    """Read resource-directory leaves from PE32/PE32+ without executing the launcher."""
    if chunk(data, 0, 2) != b'MZ':
        raise ValueError('Invalid PE launcher')
    pe = u32(data, 60)
    if chunk(data, pe, 4) != b'PE\0\0':
        raise ValueError('Invalid PE signature')
    optional = pe + 24
    magic = u16(data, optional)
    if magic not in (0x10b, 0x20b):
        raise ValueError('Unsupported PE optional header')
    directories = optional + (96 if magic == 0x10b else 112)
    resource_rva, resource_size = struct.unpack('<II', chunk(data, directories + 16, 8))
    sections = optional + u16(data, pe + 20)

    def rva_offset(rva, size):
        for i in range(u16(data, pe + 6)):
            section = sections + i * 40
            virtual_size, virtual, raw_size, raw = struct.unpack('<IIII', chunk(data, section + 8, 16))
            if virtual <= rva and rva + size <= virtual + raw_size:
                return raw + rva - virtual
        raise ValueError('Unmapped PE resource')

    base = rva_offset(resource_rva, resource_size)
    resources = chunk(data, base, resource_size)
    leaves = {}

    def visit(offset, path):
        if len(path) > 3:
            raise ValueError('Unexpected PE resource depth')
        count = u16(resources, offset + 12) + u16(resources, offset + 14)
        for i in range(count):
            name, target = struct.unpack('<II', chunk(resources, offset + 16 + 8 * i, 8))
            # Icon resources use numeric identifiers. Other resources may have names.
            if name & 0x80000000:
                continue
            current = path + (name,)
            if target & 0x80000000:
                visit(target & 0x7fffffff, current)
            else:
                rva, size = struct.unpack('<II', chunk(resources, target, 8))
                leaves[current] = chunk(data, rva_offset(rva, size), size)
    visit(0, ())
    return leaves


def verify_windows(source, launcher):
    expected = sorted(ico_images(source))
    resources = pe_icons(launcher)
    for path, group in resources.items():
        if len(path) != 3 or path[0] != 14:
            continue
        if chunk(group, 0, 4) != b'\0\0\1\0':
            continue
        actual = []
        for i in range(u16(group, 4)):
            entry = chunk(group, 6 + 14 * i, 14)
            icon_id = u16(entry, 12)
            body = resources.get((3, icon_id, path[2]))
            if body is None or len(body) != u32(entry, 8):
                raise ValueError('Missing or inconsistent PE icon payload')
            actual.append((entry[:12], body))
        if sorted(actual) == expected:
            return
    raise ValueError('Packaged Windows launcher does not contain selected ICO artwork')


def verify(target, source, image=None):
    source = Path(source)
    data = source.read_bytes()
    if target == 'linux-x64':
        if png_dimensions(data) != (512, 512):
            raise ValueError('Expected canonical 512-pixel PNG')
        relative = 'lib/VibeMiner.png'
    elif target.startswith('macos-'):
        if chunk(data, 0, 4) != b'icns' or struct.unpack('>I', chunk(data, 4, 4))[0] != len(data):
            raise ValueError('Invalid canonical ICNS')
        offset = 8
        count = 0
        while offset < len(data):
            size = struct.unpack('>I', chunk(data, offset + 4, 4))[0]
            if size <= 8:
                raise ValueError('Invalid ICNS image chunk')
            png_dimensions(chunk(data, offset + 8, size - 8))
            offset += size
            count += 1
        if offset != len(data) or count == 0:
            raise ValueError('Empty or malformed ICNS')
        relative = 'Contents/Resources/VibeMiner.icns'
    elif target == 'windows-x64':
        ico_images(data)
        if image is not None:
            verify_windows(data, (Path(image) / 'VibeMiner.exe').read_bytes())
        return
    else:
        raise ValueError('Unsupported native target')
    if image is not None and (Path(image) / relative).read_bytes() != data:
        raise ValueError('Packaged icon differs from selected artwork')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--image', type=Path)
    args = parser.parse_args()
    verify(args.target, args.source, args.image)
    print('Selected native icon verified')
