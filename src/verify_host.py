"""Prove hook discovery survives file renames and rejects missing/ambiguous targets."""
import argparse
from pathlib import Path
from tempfile import TemporaryDirectory
import hashlib
from build import HOST_PATCHES
from host_discovery import discover


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('smali', type=Path, help='Original, unpatched classes.dex Smali directory')
    args = parser.parse_args()
    targets = discover(args.smali, HOST_PATCHES)
    with TemporaryDirectory(prefix='dx-host-discovery-') as work:
        root = Path(work)
        for index, (source, _) in enumerate(targets):
            target = root / 'com/discord/nativeapp' / f'renamed_{index}.smali'
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())
        before = {p: hashlib.sha256(p.read_bytes()).digest() for p in root.rglob('*.smali')}
        discovered = discover(root, HOST_PATCHES)
        assert len(discovered) == len(HOST_PATCHES)
        print('PASS all hook targets discovered after file renames')
        target = discovered[0][0]
        duplicate = target.with_name('duplicate.smali')
        duplicate.write_bytes(target.read_bytes())
        try:
            discover(root, HOST_PATCHES)
            raise AssertionError('Ambiguous patch target was accepted')
        except ValueError as error:
            assert 'found 2' in str(error), error
        duplicate.unlink()
        for path, digest in before.items(): assert hashlib.sha256(path.read_bytes()).digest() == digest
        target.unlink()
        try:
            discover(root, HOST_PATCHES)
            raise AssertionError('Missing patch target was accepted')
        except ValueError as error:
            assert 'found 0' in str(error), error
        print('PASS missing and duplicate targets rejected without changing source files')


if __name__ == '__main__': main()
