"""Run MessageLogger transport/history regression checks on the JVM."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import urllib.request

ROOT = Path(__file__).resolve().parent
JSON_SHA256 = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json-jar', type=Path, default=ROOT / '.cache/json-20240303.jar')
    args = parser.parse_args()
    if not args.json_jar.exists():
        with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', timeout=20) as response:
            data = response.read(2 * 1024 * 1024)
        if hashlib.sha256(data).hexdigest() != JSON_SHA256:
            raise ValueError('org.json download checksum mismatch')
        args.json_jar.parent.mkdir(parents=True, exist_ok=True)
        args.json_jar.write_bytes(data)
    if hashlib.sha256(args.json_jar.read_bytes()).hexdigest() != JSON_SHA256:
        raise ValueError('Expected the pinned org.json:json:20240303 JAR')
    plugin = ROOT / 'plugins/message_logger'
    with TemporaryDirectory(prefix='dx-logger-check-') as work:
        subprocess.run(['javac', '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8', '-cp', str(args.json_jar), '-d', work,
                        str(plugin / 'src/discordx/plugins/message_logger/MessageLogger.java'),
                        str(plugin / 'tests/discordx/plugins/message_logger/MessageLoggerCheck.java')], check=True)
        subprocess.run(['java', '-cp', os.pathsep.join([work, str(args.json_jar)]),
                        'discordx.plugins.message_logger.MessageLoggerCheck'], check=True)


if __name__ == '__main__': main()
