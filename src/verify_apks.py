"""Check that DiscordX changes DEX only and signs the three APKs consistently."""

import argparse
from hashlib import file_digest
from pathlib import Path
import re
from zipfile import ZipFile

from build import MODULE_ASSET, PHOENIX_HOST, badging, is_old_signature, run


def payloads(apk):
    with ZipFile(apk) as archive:
        hashes = {}
        for entry in archive.infolist():
            if entry.is_dir() or is_old_signature(entry.filename) or entry.filename == "stamp-cert-sha256":
                continue
            with archive.open(entry) as data:
                hashes[entry.filename] = file_digest(data, "sha256").hexdigest()
        return hashes


def verify(base, density, abi, out, tools):
    abi_split = badging(tools, abi)
    if abi_split not in {"config.x86_64", "config.arm64_v8a"}:
        raise AssertionError("Expected an arm64_v8a or x86_64 ABI split")
    architecture = "x86_64" if abi_split == "config.x86_64" else "arm64-v8a"
    host_path = f"lib/{architecture}/liblibdiscord_wasm_host.so"
    expected = {
        "base.apk": (base, "base"),
        "hdpi.apk": (density, "config.hdpi"),
        f"{architecture}.apk": (abi, abi_split),
    }
    certs = set()
    for name, (source, split) in expected.items():
        output = out / name
        if badging(tools, source) != split or badging(tools, output) != split:
            raise AssertionError(f"Incorrect split: {name}")
        before, after = payloads(source), payloads(output)
        changes = {item for item in before.keys() | after.keys() if before.get(item) != after.get(item)}
        if split == "base":
            existing_dex = [item for item in changes & before.keys()
                            if re.fullmatch(r"classes\d*\.dex", item)]
            added_dex = [item for item in changes - before.keys()
                         if re.fullmatch(r"classes\d+\.dex", item)]
            if len(changes) != 3 or len(existing_dex) != 1 or len(added_dex) != 1 or MODULE_ASSET not in changes:
                raise AssertionError(f"Unexpected changes in {name}: {sorted(changes)}")
            with ZipFile(output) as archive:
                bootstrap = archive.read(added_dex[0])
                module = archive.read(MODULE_ASSET)
                if b"Ldiscordx/loader/Bootstrap;" not in bootstrap or b"Ldiscordx/DiscordXModule;" in bootstrap:
                    raise AssertionError("Bootstrap and plugin implementations must be separate")
                if not module.startswith(b"dex\n") or b"Ldiscordx/DiscordXModule;" not in module:
                    raise AssertionError("Missing module entry class")
                if module != (out / "module.dex").read_bytes():
                    raise AssertionError("Exported module differs from the signed asset")
        elif split == abi_split and changes != {host_path}:
            raise AssertionError(f"Unexpected changes in {name}: {sorted(changes)}")
        elif split != abi_split and changes:
            raise AssertionError(f"Unexpected changes in {name}: {sorted(changes)}")
        print(f"{name}: {', '.join(sorted(changes)) or 'all payloads unchanged'}", flush=True)
        signature = run(tools / "apksigner.bat", "verify", "--print-certs", output, capture=True)
        digests = re.findall(r"certificate SHA-256 digest: ([a-f0-9]+)", signature)
        if len(digests) != 1:
            raise AssertionError(f"Expected one signer: {name}")
        certs.update(digests)
    if len(certs) != 1:
        raise AssertionError("Split signatures differ")
    print("All three splits verify with the same signer.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", type=Path)
    parser.add_argument("density", type=Path)
    parser.add_argument("abi", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("build_tools", type=Path)
    args = parser.parse_args()
    verify(args.base, args.density, args.abi, args.out, args.build_tools)
