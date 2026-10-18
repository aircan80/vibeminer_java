#!/usr/bin/env python3
"""Generate VibeMiner platform icons from the selected SVG; developer-only ImageMagick."""
from pathlib import Path
import argparse
import shutil
import struct
import subprocess
import tempfile

SIZES = (16, 24, 32, 48, 64, 128, 256, 512, 1024)
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)
ICNS_TYPES = ((b'icp4',16),(b'icp5',32),(b'icp6',64),(b'ic07',128),
              (b'ic08',256),(b'ic09',512),(b'ic10',1024),
              (b'ic11',32),(b'ic12',64),(b'ic13',256),(b'ic14',512))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root = args.root.resolve()
    source = root / 'branding/vibeminer.svg'
    out = root / 'branding/icons'
    out.mkdir(parents=True, exist_ok=True)
    magick = shutil.which('magick')
    if not magick:
        parser.error('ImageMagick 7 (magick) is required only to regenerate artwork')
    with tempfile.TemporaryDirectory(prefix='vibeminer-branding-') as temporary:
        master = Path(temporary) / 'master.png'
        subprocess.run([magick, '-background', 'none', '-density', '1536', str(source),
                        '-resize', '4096x4096!', '-strip', 'PNG32:' + str(master)], check=True)
        for size in SIZES:
            subprocess.run([magick, str(master), '-filter', 'Lanczos', '-resize', f'{size}x{size}!',
                            '-strip', '-define', 'png:exclude-chunks=date,time',
                            'PNG32:' + str(out / f'vibeminer-{size}.png')], check=True)
    shutil.copyfile(out / 'vibeminer-512.png', out / 'vibeminer.png')
    images = [(size, (out / f'vibeminer-{size}.png').read_bytes()) for size in ICO_SIZES]
    header = struct.pack('<HHH', 0, 1, len(images))
    offset = len(header) + 16 * len(images)
    entries = []
    for size, data in images:
        encoded_size = 0 if size == 256 else size
        entries.append(struct.pack('<BBBBHHII', encoded_size, encoded_size, 0, 0, 1, 32, len(data), offset))
        offset += len(data)
    (out / 'vibeminer.ico').write_bytes(header + b''.join(entries) + b''.join(data for _, data in images))
    chunks = []
    for kind, size in ICNS_TYPES:
        data = (out / f'vibeminer-{size}.png').read_bytes()
        chunks.append(kind + struct.pack('>I', 8 + len(data)) + data)
    body = b''.join(chunks)
    (out / 'vibeminer.icns').write_bytes(b'icns' + struct.pack('>I', 8 + len(body)) + body)
    print('Generated nine PNG sizes, Linux PNG, seven-size ICO and eleven-chunk ICNS.')

if __name__ == '__main__':
    main()
