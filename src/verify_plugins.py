"""Build the plugin verification APK; optionally run it on an Android test device."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import threading
import warnings
from zipfile import ZipFile, ZIP_DEFLATED
import build
from build_plugins import build_plugins


def fixture(source, target, mutate, extra=None):
    with ZipFile(source) as original:
        contents = {name: original.read(name) for name in original.namelist()}
    manifest = json.loads(contents['manifest.json'])
    mutate(manifest, contents)
    contents['manifest.json'] = json.dumps(manifest).encode()
    with ZipFile(target, 'w', compression=ZIP_DEFLATED) as archive:
        for name, data in contents.items(): archive.writestr(name, data)
        if extra: archive.writestr(*extra)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')
    parser.add_argument('--out', type=Path, default=build.ROOT / 'dist/plugin-verification')
    parser.add_argument('--bundle', type=Path, help='Verify the exact plugin ZIPs from a built base APK')
    parser.add_argument('--adb', help='ADB serial; installs and clears only discordx.verification test-app data')
    parser.add_argument('--user', help='Android user ID for the test app; defaults to current user')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    tools = args.sdk / 'build-tools/36.1.0'
    android = args.sdk / 'platforms/android-36/android.jar'
    with TemporaryDirectory(prefix='dx-verification-') as temporary:
        work = Path(temporary)
        assets = work / 'assets'
        plugins = assets / 'discordx/plugins'
        if args.bundle:
            with ZipFile(args.bundle) as bundle:
                prefix = 'assets/discordx/plugins/'
                index = bundle.read(prefix + 'index.json')
                plugins.mkdir(parents=True)
                (plugins / 'index.json').write_bytes(index)
                for name in json.loads(index):
                    if not re.fullmatch(r'[a-z][a-z0-9_.-]+\.zip', name):
                        raise ValueError('Invalid bundled plugin name')
                    (plugins / name).write_bytes(bundle.read(prefix + name))
        else:
            build_plugins(args.sdk, plugins, work / 'builtins')
        tests = assets / 'tests'
        tests.mkdir(parents=True)
        build_plugins(args.sdk, tests, work / 'external', [build.ROOT / 'examples/clipboard_prefix'])
        example = tests / 'example.clipboard_prefix.zip'
        example.rename(tests / 'clipboard_prefix.zip')
        example = tests / 'clipboard_prefix.zip'
        fixture(example, tests / 'clipboard_failure.zip', lambda manifest, files: manifest.update(entrypoint='example.clipboard.FailingPlugin', version='2.0.0'))
        fixture(example, tests / 'invalid_api.zip', lambda manifest, files: manifest.update(minApi=2, maxApi=2))
        def invalid_dex(manifest, files):
            files['classes.dex'] = b'dex\n038\x00' + bytes(104)
            manifest['dexSha256'] = hashlib.sha256(files['classes.dex']).hexdigest()
        fixture(example, tests / 'invalid_dex.zip', invalid_dex)
        fixture(example, tests / 'invalid_path.zip', lambda manifest, files: None, ('assets/../escape', b'no'))
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            fixture(example, tests / 'duplicate.zip', lambda manifest, files: None, ('classes.dex', b'duplicate'))
        fixture(example, tests / 'oversized.zip', lambda manifest, files: None, ('assets/large', bytes(4 * 1024 * 1024 + 1)))
        dex, _ = build.compile_dex(tools, android, work / 'app', build.SOURCES + build.BOOTSTRAP_SOURCES + [build.ROOT / 'tests/PluginRuntimeTestInstrumentation.java'])
        shell = work / 'shell.apk'
        build.run(tools / 'aapt2.exe', 'link', '-I', android, '--manifest', build.ROOT / 'tests/AndroidManifest.xml', '-A', assets, '-o', shell)
        unsigned = work / 'unsigned.apk'
        build.repack(shell, unsigned, {'classes.dex': dex})
        output = args.out / 'plugin-verification.apk'
        script = """
import {readFile,writeFile} from 'node:fs/promises';
import {readZip,writeZip} from './web/public/lib/zip.mjs';
import {signV2,decodeIdentity} from './web/public/lib/sign.mjs';
const [source,target]=process.argv.slice(1);
const entries=readZip(new Uint8Array(await readFile(source)));
const identity=decodeIdentity(JSON.parse(await readFile('web/public/signing-key.json','utf8')));
await writeFile(target,Buffer.concat(await signV2(writeZip(entries),identity)));
"""
        subprocess.run(['node', '--input-type=module', '-e', script, str(unsigned), str(output)], cwd=build.ROOT.parent, check=True)
        build.run(tools / 'apksigner.bat', 'verify', output)
    print('Verification APK:', output, flush=True)
    if args.adb:
        adb = args.sdk / 'platform-tools/adb.exe'
        def device(*command, capture=False): return build.run(adb, '-s', args.adb, *command, capture=capture)
        user = args.user or device('shell', 'am', 'get-current-user', capture=True).strip()
        device('install', '--user', user, '-r', output)
        device('shell', 'pm', 'clear', '--user', user, 'discordx.verification')
        command = [str(adb), '-s', args.adb, 'shell', 'am', 'instrument', '--user', user, '-w', '-r',
                   'discordx.verification/discordx.PluginRuntimeTestInstrumentation']
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        timer = threading.Timer(90, process.kill)
        timer.start()
        report = bytearray()
        overflow = False
        try:
            while chunk := process.stdout.read(4096):
                available = 64 * 1024 - len(report)
                report.extend(chunk[:available])
                if len(chunk) > available:
                    overflow = True
                    process.kill()
                    break
            process.wait()
        finally:
            timer.cancel()
            process.stdout.close()
            if process.poll() is None:
                process.kill()
                process.wait()
        text = report.decode('utf-8', errors='replace')
        (args.out / 'report.txt').write_text(text, encoding='utf-8')
        print(text)
        if overflow or process.returncode != 0 or 'ALL PASSED' not in text or 'INSTRUMENTATION_CODE: -1' not in text:
            raise SystemExit('Headless plugin verification failed; see report.txt')


if __name__ == '__main__': main()
