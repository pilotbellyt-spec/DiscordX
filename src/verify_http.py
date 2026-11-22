"""Build headless HTTP-adapter tests using the actual patched Discord DEX classes."""
import argparse
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZipFile
import build


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')
    parser.add_argument('--host', type=Path, default=build.ROOT / 'dist/plugin-system/base.apk')
    parser.add_argument('--out', type=Path, default=build.ROOT / 'dist/http-verification')
    parser.add_argument('--adb', help='ADB serial; installs only the standalone HTTP verification package')
    parser.add_argument('--user', default='10', help='Android test user; never changes the foreground user')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    tools = args.sdk / 'build-tools/36.1.0'
    android = args.sdk / 'platforms/android-36/android.jar'
    with TemporaryDirectory(prefix='dx-http-verification-') as temporary:
        work = Path(temporary)
        dex, _ = build.compile_dex(tools, android, work / 'app',
            build.SOURCES + build.BOOTSTRAP_SOURCES + [build.ROOT / 'tests/HttpBridgeTestInstrumentation.java'])
        shell = work / 'shell.apk'
        build.run(tools / 'aapt2.exe', 'link', '-I', android, '--manifest', build.ROOT / 'tests/HttpBridgeManifest.xml', '-o', shell)
        replacements = {'classes.dex': dex}
        with ZipFile(args.host) as host:
            for source, target in [('classes.dex', 'classes2.dex'), ('classes2.dex', 'classes3.dex'), ('classes3.dex', 'classes4.dex')]:
                path = work / target
                path.write_bytes(host.read(source))
                replacements[target] = path
        unsigned = work / 'unsigned.apk'
        build.repack(shell, unsigned, replacements)
        output = args.out / 'http-verification.apk'
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
    print('HTTP verification APK:', output, flush=True)
    if args.adb:
        adb = args.sdk / 'platform-tools/adb.exe'
        build.run(adb, '-s', args.adb, 'install', '--user', args.user, '-t', '-r', output)
        result = subprocess.run([str(adb), '-s', args.adb, 'shell', 'am', 'instrument', '--user', args.user, '-w', '-r',
            'discordx.http.verification/discordx.HttpBridgeTestInstrumentation'], capture_output=True, text=True, timeout=120)
        report = result.stdout + result.stderr
        (args.out / 'report.txt').write_text(report[:16000], encoding='utf-8')
        print(report[:10000])
        if result.returncode != 0 or 'ALL PASSED' not in report or 'INSTRUMENTATION_CODE: -1' not in report:
            raise SystemExit('HTTP verification failed; see the bounded instrumentation report.')


if __name__ == '__main__':
    main()
