"""Prepare version-pinned DiscordX browser payloads and a PUBLIC web signing key."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile
from dex_patch import create_patch
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('original', type=Path)
parser.add_argument('patched', type=Path)
args = parser.parse_args()
assets = root / 'public/payload'
assets.mkdir(parents=True, exist_ok=True)
with ZipFile(args.original) as original, ZipFile(args.patched) as patched:
    source = original.read('classes.dex')
    host = patched.read('classes.dex')
    (assets / 'host.patch').write_bytes(create_patch(source, host))
    plugin_files = []
    for entry in patched.namelist():
        if entry.startswith('assets/discordx/plugins/') and not entry.endswith('/'):
            name = entry.removeprefix('assets/discordx/')
            destination = assets / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(patched.read(entry))
            plugin_files.append(name)
    for name, entry in [('bootstrap.dex', 'classes4.dex'), ('module.dex', 'assets/discordx/module.dex')]:
        (assets / name).write_bytes(patched.read(entry))
manifest = {'version': 356207, 'sourceDexSha256': hashlib.sha256(source).hexdigest(),
            'hostDexSha256': hashlib.sha256(host).hexdigest()}
manifest['files'] = {name: hashlib.sha256((assets / name).read_bytes()).hexdigest() for name in ['host.patch', 'bootstrap.dex', 'module.dex', *sorted(plugin_files)]}
(assets / 'manifest.json').write_text(json.dumps(manifest, indent=2))
(assets / 'host.dex').unlink(missing_ok=True)
# Deliberately separate from the user's installed-app keystore. This public key
# is for browser builds only and must never be used to sign a private release.
identity = root / 'public/signing-key.json'
if not identity.exists():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'DiscordX public web builder')])
    now = datetime.now(timezone.utc)
    cert = x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=10000)).sign(key, hashes.SHA256())
    b64 = lambda value: base64.b64encode(value).decode('ascii')
    identity.write_text(json.dumps({
        'pkcs8': b64(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())),
        'spki': b64(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)),
        'cert': b64(cert.public_bytes(serialization.Encoding.DER))
    }))
print('Prepared browser payloads. The web signing identity is intentionally public.')
