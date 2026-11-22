"""Compile independent Java plugins against the public SDK and package DEX ZIPs."""
import argparse
import hashlib
import json
import os
import re
import time
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile, ZIP_DEFLATED, ZipInfo
from build import ROOT, compile_dex


def entry(archive, name, data):
    info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)


def build_api(sdk, output, work):
    _, classes = compile_dex(sdk / 'build-tools/36.1.0', sdk / 'platforms/android-36/android.jar',
                             work / 'api', sorted((ROOT / 'api').rglob('*.java')))
    target = output / 'discordx-plugin-api.jar'
    with ZipFile(target, 'w', compression=ZIP_DEFLATED) as archive:
        for file in sorted(classes.rglob('*.class')):
            entry(archive, file.relative_to(classes).as_posix(), file.read_bytes())
    return target


def package_plugin(directory, dex, target):
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    manifest['dexSha256'] = hashlib.sha256(dex.read_bytes()).hexdigest()
    with ZipFile(target, 'w', compression=ZIP_DEFLATED) as archive:
        entry(archive, 'manifest.json', json.dumps(manifest, indent=2).encode('utf-8'))
        entry(archive, 'classes.dex', dex.read_bytes())
        assets = directory / 'assets'
        if assets.is_dir():
            for file in sorted(assets.rglob('*')):
                if file.is_file(): entry(archive, 'assets/' + file.relative_to(assets).as_posix(), file.read_bytes())
    return manifest['id']


def build_plugins(sdk, output, work, directories=None):
    output.mkdir(parents=True, exist_ok=True)
    sdk_jar = build_api(sdk, output, work)
    packages = []
    directories = directories or sorted(path.parent for path in (ROOT / 'plugins').glob('*/manifest.json'))
    ids = set()
    for directory in directories:
        metadata = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
        ident = metadata['id']
        if ident in ids or not re.fullmatch(r'[a-z][a-z0-9_.-]{1,63}', ident):
            raise ValueError('Invalid or duplicate plugin ID: ' + ident)
        ids.add(ident)
        dex, _ = compile_dex(sdk / 'build-tools/36.1.0', sdk / 'platforms/android-36/android.jar',
                             work / ident, sorted((directory / 'src').rglob('*.java')), (sdk_jar,))
        target = output / (ident + '.zip')
        package_plugin(directory, dex, target)
        packages.append(target)
        print('Packaged plugin:', ident, flush=True)
    index = output / 'index.json'
    index.write_text(json.dumps([file.name for file in packages], indent=2), encoding='utf-8')
    return index, packages


def watch_plugin(sdk, directory, output):
    """Keep one plugin ZIP up to date for a linked document or a synced folder."""
    with TemporaryDirectory(prefix='dx-watch-sdk-') as sdk_work:
        sdk_jar = build_api(sdk, Path(sdk_work), Path(sdk_work))
        last = None
        while True:
            files = sorted(path for name in ('manifest.json', 'src', 'assets')
                           for path in ([directory / name] if name == 'manifest.json' else (directory / name).rglob('*'))
                           if path.is_file())
            current = [(path, path.stat().st_mtime_ns, path.stat().st_size) for path in files]
            if current != last:
                last = current
                try:
                    with TemporaryDirectory(prefix='dx-watch-build-') as work:
                        dex, _ = compile_dex(sdk / 'build-tools/36.1.0', sdk / 'platforms/android-36/android.jar',
                                             Path(work), sorted((directory / 'src').rglob('*.java')), (sdk_jar,))
                        with TemporaryDirectory(dir=output) as staging:
                            temp = Path(staging) / 'package.zip'
                            ident = package_plugin(directory, dex, temp)
                            if not re.fullmatch(r'[a-z][a-z0-9_.-]{1,63}', ident):
                                raise ValueError('Invalid plugin ID')
                            temp.replace(output / (ident + '.zip'))
                            print('Updated', output / (ident + '.zip'), flush=True)
                except Exception as error:
                    print('Build failed:', error, flush=True)
            time.sleep(0.5)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('plugins', nargs='*', type=Path, help='Plugin source directories; defaults to all bundled plugins')
    parser.add_argument('--sdk', type=Path, default=Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')
    parser.add_argument('--out', type=Path, default=ROOT / 'dist/plugins')
    parser.add_argument('--dex', type=Path, help='Package an already compiled Java/Kotlin DEX for one source manifest')
    parser.add_argument('--watch', action='store_true', help='Recompile one plugin on source changes')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.watch:
        if args.dex or len(args.plugins) != 1: parser.error('--watch requires one source folder')
        try: watch_plugin(args.sdk, args.plugins[0], args.out)
        except KeyboardInterrupt: pass
    elif args.dex:
        if len(args.plugins) != 1: parser.error('--dex requires exactly one plugin directory')
        ident = json.loads((args.plugins[0] / 'manifest.json').read_text(encoding='utf-8'))['id']
        if not re.fullmatch(r'[a-z][a-z0-9_.-]{1,63}', ident): parser.error('Invalid plugin ID')
        package_plugin(args.plugins[0], args.dex, args.out / (ident + '.zip'))
    else:
        with TemporaryDirectory(prefix='dx-plugins-') as temporary:
            build_plugins(args.sdk, args.out, Path(temporary), args.plugins)
