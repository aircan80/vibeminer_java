#!/usr/bin/env python3
"""Copy and verify project legal/source-access records in app-owned package paths."""
import argparse
from pathlib import Path
import re


def legal_directory(image, target):
    if target == 'linux-x64':
        return Path(image) / 'lib/app/legal'
    if target == 'windows-x64':
        return Path(image) / 'app/legal'
    if target in {'macos-x64', 'macos-arm64'}:
        return Path(image) / 'Contents/app/legal'
    raise ValueError('Unsupported native target')


def expected(source, commit, version, dirty):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Corresponding source commit must be an exact 40-character Git SHA')
    if not re.fullmatch(r'[0-9A-Za-z.+-]+', version):
        raise ValueError('Invalid application version')
    source = Path(source)
    files = {name: (source / name).read_bytes() for name in ('LICENSE', 'NOTICE')}
    files['LICENSING.md'] = (source / 'docs/LICENSING.md').read_bytes()
    if any(not data for data in files.values()):
        raise ValueError('Project legal files must not be empty')
    status = ('Uncommitted working-tree changes were present. This revision is a BASE reference, '
              'not an exact record of those changes; retain and provide this build\'s modified '
              'corresponding source with these binaries.\n' if dirty else
              'This build identifies the following exact corresponding source revision.\n')
    tree_label = 'Base source tree (not complete modified build source)' if dirty else 'Corresponding source tree'
    archive_label = 'Base archive (does not contain uncommitted changes)' if dirty else 'Corresponding source archive'
    text = (f'VibeMiner {version}\nSPDX-License-Identifier: GPL-3.0-or-later\n'
            f'Source revision: {commit}\n{status}'
            f'{tree_label}: https://github.com/aircan80/vibeminer/tree/{commit}\n'
            f'{archive_label}: https://github.com/aircan80/vibeminer/archive/{commit}.tar.gz\n'
            'Build instructions: BUILDING.md at that exact revision (included in the source archive).\n'
            'The corresponding source includes project code, build scripts and required build inputs; '
            'follow BUILDING.md for dependency acquisition and native packaging.\n'
            'Repository/source downloads require the same authorized repository access as the release. '
            'This record does not claim public repository access. Distributors must provide recipients '
            'the corresponding source under GPLv3; a private URL alone is not a public source offer.\n'
            'For source archives built without Git, pass -PsourceRevision=<original exact revision> '
            'to Gradle; do not substitute a latest branch.\n'
            'Upstream runtime and dependency license notices retain their own terms and locations.\n')
    files['SOURCE.txt'] = text.encode('utf-8')
    return files


def process(source, image, target, commit, version, dirty=False, verify=False):
    destination = legal_directory(image, target)
    files = expected(source, commit, version, dirty)
    if verify:
        for name, data in files.items():
            if (destination / name).read_bytes() != data:
                raise ValueError(f'Packaged legal/source record differs: {name}')
    else:
        destination.mkdir(parents=True, exist_ok=True)
        for name, data in files.items():
            (destination / name).write_bytes(data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['copy', 'verify'])
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--dirty', action='store_true')
    args = parser.parse_args()
    process(args.source, args.image, args.target, args.commit, args.version,
            args.dirty, args.mode == 'verify')
    print('Packaged project license/source records verified' if args.mode == 'verify' else
          'Project license/source records copied')
