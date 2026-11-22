"""Build DiscordX for Phoenix 356207."""

import argparse
from hashlib import file_digest, sha256
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parent
LAUNCH_TARGET = "com/discord/phoenix_launch/PhoenixLaunch.smali"
TARGET = "com/discord/nativeapp/MainActivity.smali"
LINK_TARGET = "com/discord/nativeapp/modules/w8.smali"
GATEWAY_TARGET = "com/discord/nativeapp/modules/or.smali"
DRAWABLE_TARGET = "lh/l.smali"
TEXT_SPAN_TARGET = "com/discord/nativeapp/modules/props/b.smali"
TEXT_VIEW_TARGET = "com/discord/nativeapp/modules/b7.smali"
EDIT_TEXT_TARGET = "com/discord/nativeapp/modules/ej.smali"
HTTP_TARGET = "com/discord/nativeapp/modules/bb.smali"
OVERLAY_TARGET = "com/discord/nativeapp/modules/overlays/a1.smali"
RENDER_TARGET = "com/discord/nativeapp/modules/j.smali"
VIEW_EVENT_TARGET = "com/discord/nativeapp/modules/qo.smali"
CLIPBOARD_TARGET = "com/discord/nativeapp/modules/f2.smali"
MOTION_TARGET = "com/discord/nativeapp/modules/i2.smali"
HOST = "Ldiscordx/loader/Bootstrap;"
VERSION = "356207"
SOURCES = sorted((ROOT / "discordx").glob("*.java"))
BOOTSTRAP_SOURCES = sorted((ROOT / "bootstrap/discordx/loader").glob("*.java"))
MODULE_ASSET = "assets/discordx/module.dex"
PHOENIX_HOST = "lib/x86_64/liblibdiscord_wasm_host.so"
PHOENIX_ACCESS_ANCHOR = b"2026-11-phoenix-app-access"
PHOENIX_ACCESS_OFFSET = 0x2EAC1C4
PHOENIX_ACCESS_PROLOGUE = bytes.fromhex("554157415641554154534883ec28b302")


def run(*command, capture=False, env=None):
    return subprocess.run(
        [str(part) for part in command], check=True, text=True,
        stdout=subprocess.PIPE if capture else None, env=env,
    ).stdout


def method_body(smali, signature, edit):
    pattern = re.compile(
        r"(^\.method [^\n]* " + re.escape(signature) + r"\n)(.*?)(^\.end method$)",
        re.MULTILINE | re.DOTALL,
    )
    patched, count = pattern.subn(lambda m: m[1] + edit(m[2]) + m[3], smali)
    if count != 1:
        raise ValueError(f"Expected one method: {signature}, found {count}")
    return patched


def splice(body, anchor, addition):
    patched, count = re.subn(anchor, lambda m: m[0] + "\n" + addition, body, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"Unsupported Phoenix activity: expected one match for {anchor}, found {count}")
    return patched


def patch_launch(smali):
    def prefer_phoenix(body):
        start, key, read = body.partition('    const-string v0, "launch_client"\n')
        if not key or "Landroid/content/SharedPreferences;->getString" not in read:
            raise ValueError("Expected the saved Phoenix launch preference")
        return start + key + exact(read, "    const/4 v1, 0x0\n", '    const-string v1, "phoenix"\n')

    smali = method_body(smali, "a(Landroid/content/Context;)Ljava/lang/String;", prefer_phoenix)

    def force_phoenix(body):
        if "unhealthy_phoenix_launches" not in body or "Phoenix didn\\'t stay up for 3 launches" not in body:
            raise ValueError("Expected the Phoenix crash-loop gate")
        return "    .locals 1\n\n    const/4 v0, 0x1\n    return v0\n"

    return method_body(smali, "i(Landroid/content/ContextWrapper;)Z", force_phoenix)


def patch_activity(smali):
    if HOST in smali:
        raise ValueError("The input already contains DiscordX")
    smali = method_body(smali, "onCreate(Landroid/os/Bundle;)V", lambda body: splice(
        body, r"^    \.locals \d+$",
        f"    invoke-static/range {{p0 .. p0}}, {HOST}->prepare(Landroid/app/Activity;)V\n",
    ))
    smali = method_body(smali, "onCreate(Landroid/os/Bundle;)V", lambda body: splice(
        body, r"^    invoke-virtual(?:/range)? \{[^\n]+\}, [^\n]+->setContentView\(Landroid/view/View;\)V$",
        f"    invoke-static/range {{p0 .. p0}}, {HOST}->attach(Landroid/app/Activity;)V\n",
    ))
    smali = exact(smali, "invoke-virtual {v3, v0}, Landroid/view/View;->setBackgroundColor(I)V",
                  f"invoke-static {{v3, v0}}, {HOST}->themeRoot(Landroid/view/View;I)V")
    smali = method_body(smali, "dispatchKeyEvent(Landroid/view/KeyEvent;)Z", lambda body: splice(
        body, r"^    \.locals [1-9]\d*$",
        f"    invoke-static/range {{p0 .. p1}}, {HOST}->handleKey(Landroid/app/Activity;Landroid/view/KeyEvent;)Z\n"
        "    move-result v0\n"
        "    if-eqz v0, :discordx_continue\n"
        "    return v0\n"
        "    :discordx_continue\n",
    ))
    return method_body(smali, "onDestroy()V", lambda body: splice(
        body, r"^    \.locals \d+$",
        f"    invoke-static/range {{p0 .. p0}}, {HOST}->detach(Landroid/app/Activity;)V\n",
    ))


def patch_browser(smali):
    if HOST in smali or "android.support.customtabs.extra.TITLE_VISIBILITY" not in smali:
        raise ValueError("Unexpected Phoenix browser handler")
    smali = method_body(smali, "invoke(Ljava/lang/Object;)Ljava/lang/Object;", lambda body: splice(
        body, r"^    :try_start_5$(?=\n    invoke-virtual \{v5, v0\}, Landroid/content/Intent;->setData\(Landroid/net/Uri;\)Landroid/content/Intent;)",
        f"    invoke-static {{v1, v0}}, {HOST}->cleanLink(Landroid/content/Context;Landroid/net/Uri;)Landroid/net/Uri;\n"
        "    move-result-object v0",
    ))
    return exact(smali, "invoke-virtual {v1, v5, p1}, Landroid/content/Context;->startActivity(Landroid/content/Intent;Landroid/os/Bundle;)V",
                 f"invoke-static {{v1, v5, p1}}, {HOST}->openLink(Landroid/content/Context;Landroid/content/Intent;Landroid/os/Bundle;)V")


def exact(text, anchor, replacement):
    count = text.count(anchor)
    if count != 1:
        raise ValueError(f"Expected one Phoenix anchor for {anchor!r}, found {count}")
    return text.replace(anchor, replacement)


def patch_gateway(smali):
    smali = method_body(smali, "g(ILjava/lang/String;Ljava/lang/String;)V", lambda body: splice(
        body, r"^    \.locals 8$",
        f"    invoke-static/range {{p0 .. p2}}, {HOST}->socketOpened(Ljava/lang/Object;ILjava/lang/String;)V\n",
    ))
    smali = exact(smali, "    new-instance v4, Lcom/discord/nativeapp/modules/mr;",
                  "    move-object/from16 v4, p0\n"
                  f"    invoke-static {{v4, v2, v3}}, {HOST}->socketOpened(Ljava/lang/Object;ILjava/lang/String;)V\n"
                  "    new-instance v4, Lcom/discord/nativeapp/modules/mr;")
    smali = method_body(smali, "e(ILjava/lang/String;[BLcom/discord/nativeapp/modules/sd;)Z",
                        lambda body: splice(
                            body, r"^    if-eqz p4, :cond_2$",
                            f"    invoke-static {{p0, p1, p3}}, {HOST}->onFrame(Ljava/lang/Object;I[B)[B\n"
                            "    move-result-object p3\n",
                        ))
    return method_body(smali, "h(I)V", lambda body: splice(
        body, r"^    \.locals 2$",
        f"    invoke-static/range {{p0 .. p1}}, {HOST}->socketClosed(Ljava/lang/Object;I)V\n",
    ))


def patch_drawables(smali):
    for drawable, color in (("v4", "v1"), ("v0", "p5")):
        registers = f"{{{drawable}, {color}}}"
        smali = exact(smali, f"invoke-virtual {registers}, Landroid/graphics/drawable/GradientDrawable;->setColor(I)V",
                      f"invoke-static {registers}, {HOST}->themeFill(Landroid/graphics/drawable/GradientDrawable;I)V")
    anchor = "    iget-object p5, p1, Llh/k3;->i:Ljava/lang/Float;"
    return exact(smali, anchor, f"    invoke-static {{v0}}, {HOST}->gradient([I)[I\n"
                               "    move-result-object v0\n" + anchor)


def patch_text_span(smali):
    anchor = "    invoke-direct {v3, v2}, Landroid/text/style/ForegroundColorSpan;-><init>(I)V"
    return exact(smali, anchor, f"    invoke-static {{v2}}, {HOST}->foreground(I)I\n"
                               "    move-result v2\n" + anchor)


def patch_text_view(smali):
    styled = method_body(smali, "onDraw(Landroid/graphics/Canvas;)V", lambda body: splice(
        body, r"^    \.locals \d+$",
        f"    invoke-static/range {{p0 .. p0}}, {HOST}->themeText(Landroid/widget/TextView;)V\n",
    ))
    return hook_set_text(styled, "Landroid/widget/TextView;", "decorateMessageText")


def hook_set_text(smali, parent, hook):
    signature = "setText(Ljava/lang/CharSequence;Landroid/widget/TextView$BufferType;)V"
    if re.search(r"^\.method [^\n]+ " + re.escape(signature), smali, re.MULTILINE):
        raise ValueError("Unexpected Phoenix setText override")
    return smali.rstrip() + f"""

.method public {signature}
    .locals 0

    invoke-static {{p1}}, {HOST}->{hook}(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
    move-result-object p1

    invoke-super {{p0, p1, p2}}, {parent}->{signature}
    return-void
.end method
"""


def patch_edit_text(smali):
    return hook_set_text(smali, "Landroid/widget/EditText;", "cleanMessageText")


def patch_overlay(smali):
    return method_body(smali, "b(Ljava/lang/String;Llh/j3;)V", lambda body: splice(
        body, r"^    \.locals 2$",
        f"    invoke-static {{p0, p1}}, {HOST}->onOverlayEvent(Landroid/view/View;Ljava/lang/String;)Z\n"
        "    move-result v0\n"
        "    if-eqz v0, :discordx_host_overlay\n"
        "    return-void\n"
        "    :discordx_host_overlay\n",
    ))


def patch_render(smali):
    anchor = "    iget v5, v2, Lcom/discord/nativeapp/modules/rq;->a:I"
    return exact(smali, anchor, f"    invoke-static {{v0, v2}}, {HOST}->onRenderBatch(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;\n"
                 "    move-result-object v2\n"
                 "    check-cast v2, Lcom/discord/nativeapp/modules/rq;\n\n" + anchor)


def patch_view_events(smali):
    return method_body(smali, "f(Llh/k3;Ljava/lang/String;Llh/j3;Lkotlin/jvm/functions/Function0;)V", lambda body: splice(
        body, r"^    \.locals 4$",
        "    iget v0, p1, Llh/k3;->a:I\n"
        f"    invoke-static {{v0, p2, p3, p4}}, {HOST}->onViewEvent(ILjava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Z\n"
        "    move-result v0\n"
        "    if-eqz v0, :discordx_host_event\n"
        "    return-void\n"
        "    :discordx_host_event\n",
    ))


def patch_http(smali):
    anchor = '    const-string v2, "request"'
    return exact(smali, anchor, "    iget-object v2, v0, Lg90/k0;->a:Lg90/b0;\n"
                 "    iget-object v2, v2, Lg90/b0;->i:Ljava/lang/String;\n"
                 f"    invoke-static {{v2, v1}}, {HOST}->onResponse(Ljava/lang/String;[B)V\n\n" + anchor)


def patch_clipboard(smali):
    anchor = "    invoke-static {v4, v3}, Landroid/content/ClipData;->newPlainText(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Landroid/content/ClipData;"
    return exact(smali, anchor, f"    invoke-static {{v3}}, {HOST}->cleanCopiedText(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;\n"
                               "    move-result-object v3\n" + anchor)


def patch_motion(smali):
    anchor = "    invoke-virtual {p1}, Lmh/u;->g()Z\n\n    move-result v0"
    return exact(smali, anchor, anchor + f"\n    invoke-static {{v0}}, {HOST}->reduceMotion(Z)Z\n"
                                         "    move-result v0")


def patch_phoenix_access(host):
    """Force the build-356207 x86_64 Phoenix access evaluator to Some(true)."""
    if host[:4] != b"\x7fELF" or host[4] != 2 or struct.unpack_from("<H", host, 18)[0] != 62:
        raise ValueError("Expected the x86_64 Phoenix host ELF")
    if host.count(PHOENIX_ACCESS_ANCHOR) != 2:
        raise ValueError("Expected the two Phoenix access experiment anchors")
    original = host[PHOENIX_ACCESS_OFFSET:PHOENIX_ACCESS_OFFSET + len(PHOENIX_ACCESS_PROLOGUE)]
    if original != PHOENIX_ACCESS_PROLOGUE:
        raise ValueError(
            f"Unexpected Phoenix access evaluator at {PHOENIX_ACCESS_OFFSET:#x}: {original.hex()}"
        )
    patched = bytearray(host)
    patched[PHOENIX_ACCESS_OFFSET:PHOENIX_ACCESS_OFFSET + 3] = bytes.fromhex("b001c3")
    return bytes(patched)


def is_old_signature(name):
    return re.fullmatch(r"META-INF/[^/]+\.(?:MF|SF|RSA|DSA|EC)", name, re.IGNORECASE) is not None


def repack(source, output, replacements):
    with ZipFile(source) as original, ZipFile(output, "w") as archive:
        for entry in original.infolist():
            if is_old_signature(entry.filename):
                continue
            if entry.filename in replacements:
                archive.writestr(entry, replacements.pop(entry.filename).read_bytes())
            elif entry.is_dir():
                archive.writestr(entry, b"")
            else:
                with original.open(entry) as data, archive.open(entry, "w") as target:
                    shutil.copyfileobj(data, target, 1024 * 1024)
        for name, path in replacements.items():
            archive.write(path, name)


def compile_dex(build, android_jar, work, sources, dependencies=()):
    classes = work / "classes"
    dex_dir = work / "dex"
    classes.mkdir(parents=True)
    dex_dir.mkdir()
    classpath = os.pathsep.join(str(path) for path in (android_jar, *dependencies))
    run("javac", "--release", "8", "-Xlint:-options", "-cp", classpath, "-d", classes, *sources)
    extra_classpath = [part for dependency in dependencies for part in ("--classpath", dependency)]
    run(build / "d8.bat", "--min-api", "24", "--lib", android_jar, "--output", dex_dir,
        *extra_classpath, *sorted(classes.rglob("*.class")))
    return dex_dir / "classes.dex", classes


def badging(build, path):
    output = run(build / "aapt.exe", "dump", "badging", path, capture=True)
    match = re.search(r"^package: name='com\.discord' versionCode='(\d+)'.*?(?: split='([^']+)')?$",
                      output, re.MULTILINE)
    if match is None or match[1] != VERSION:
        raise ValueError(f"Expected Discord build {VERSION}: {path}")
    return match[2] or "base"


def sign(build, args, unsigned, output, work):
    aligned = work / f"{output.stem}.aligned.apk"
    run(build / "zipalign.exe", "-P", "16", "-f", "4", unsigned, aligned)
    run(build / "apksigner.bat", "sign", "--ks", args.keystore,
        "--ks-key-alias", args.alias, "--ks-pass", "env:DISCORDX_SIGNING_PASSWORD",
        "--v4-signing-enabled", "false", "--out", output, aligned)
    run(build / "zipalign.exe", "-c", "-P", "16", "4", output)
    run(build / "apksigner.bat", "verify", "--print-certs", output)


def host_dex(base, apktool, work):
    cache = ROOT / ".cache" / "host-dex"
    cache.mkdir(parents=True, exist_ok=True)
    key = sha256()
    for file in (base, apktool, Path(__file__)):
        with file.open("rb") as stream:
            key.update(file_digest(stream, "sha256").digest())
    cached = cache / (key.hexdigest() + ".dex")
    if cached.is_file():
        print("Reusing verified-build Phoenix hooks", flush=True)
        return cached

    host_only = work / "host-input.apk"
    with ZipFile(base) as original, ZipFile(host_only, "w") as minimal:
        for name in ("AndroidManifest.xml", "resources.arsc", "classes.dex"):
            minimal.writestr(name, original.read(name))
    decoded = work / "decoded"
    run("java", "-Xmx2g", "-jar", apktool, "d", "-r", "-o", decoded, host_only)
    for relative, patch in HOST_PATCHES.items():
        target = decoded / "smali" / relative
        target.write_text(patch(target.read_text(encoding="utf-8")), encoding="utf-8", newline="\n")
    rebuilt = work / "rebuilt.apk"
    run("java", "-Xmx2g", "-jar", apktool, "b", "-o", rebuilt, decoded)
    with ZipFile(rebuilt) as archive, TemporaryDirectory(prefix="cache-", dir=cache) as staging:
        dex = Path(staging) / "classes.dex"
        dex.write_bytes(archive.read("classes.dex"))
        dex.replace(cached)
    return cached


HOST_PATCHES = {
    LAUNCH_TARGET: patch_launch,
    TARGET: patch_activity,
    LINK_TARGET: patch_browser,
    GATEWAY_TARGET: patch_gateway,
    DRAWABLE_TARGET: patch_drawables,
    TEXT_SPAN_TARGET: patch_text_span,
    TEXT_VIEW_TARGET: patch_text_view,
    EDIT_TEXT_TARGET: patch_edit_text,
    HTTP_TARGET: patch_http,
    OVERLAY_TARGET: patch_overlay,
    RENDER_TARGET: patch_render,
    VIEW_EVENT_TARGET: patch_view_events,
    CLIPBOARD_TARGET: patch_clipboard,
    MOTION_TARGET: patch_motion,
}


def build_mod(args, build, android_jar, work):
    inputs = {}
    for path in args.apks:
        split = badging(build, path)
        if split in inputs:
            raise ValueError(f"Duplicate split: {split}")
        inputs[split] = path
    abi = next((name for name in inputs if name in {"config.x86_64", "config.arm64_v8a"}), None)
    if abi is None or set(inputs) != {"base", "config.hdpi", abi}:
        raise ValueError("Supply base, hdpi, and one arm64_v8a or x86_64 split of build 356207")

    bootstrap, api = compile_dex(build, android_jar, work / "bootstrap", BOOTSTRAP_SOURCES)
    payload, _ = compile_dex(build, android_jar, work / "module", SOURCES, (api,))
    base = inputs["base"]
    replacement = host_dex(base, args.apktool, work)
    with ZipFile(base) as archive:
        dex_names = {entry for entry in archive.namelist() if re.fullmatch(r"classes\d*\.dex", entry)}
    bootstrap_name = f"classes{len(dex_names) + 1}.dex"
    if bootstrap_name in dex_names:
        raise ValueError("Unexpected source DEX layout")
    patched = work / "discordx-base.apk"
    repack(base, patched, {"classes.dex": replacement, bootstrap_name: bootstrap, MODULE_ASSET: payload})

    if not args.keystore:
        shutil.copyfile(patched, args.out / "DiscordX-base-unsigned.apk")
        return
    architecture = "x86_64" if abi == "config.x86_64" else "arm64-v8a"
    host_path = f"lib/{architecture}/liblibdiscord_wasm_host.so"
    patched_abi = work / f"discordx-{architecture}.apk"
    with ZipFile(inputs[abi]) as archive:
        access_host = work / "liblibdiscord_wasm_host.so"
        original_host = archive.read(host_path)
        if architecture == "x86_64":
            access_host.write_bytes(patch_phoenix_access(original_host))
        else:
            original_path = work / "original-host.so"
            original_path.write_bytes(original_host)
            run("node", ROOT.parent / "web/patch-arm64.mjs", original_path, access_host)
    repack(inputs[abi], patched_abi, {host_path: access_host})

    sign(build, args, patched, args.out / "base.apk", work)
    sign(build, args, inputs["config.hdpi"], args.out / "hdpi.apk", work)
    sign(build, args, patched_abi, args.out / f"{architecture}.apk", work)
    shutil.copyfile(payload, args.out / "module.dex")



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="*", type=Path)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    parser.add_argument("--apktool", type=Path, default=Path("C:/Windows/apktool.jar"))
    parser.add_argument("--out", type=Path, default=ROOT / "dist")
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--alias", default="androiddebugkey")
    args = parser.parse_args()
    if len(args.apks) != 3:
        parser.error("Provide the base, density, and ABI APKs")
    if args.keystore and not os.environ.get("DISCORDX_SIGNING_PASSWORD"):
        parser.error("Set DISCORDX_SIGNING_PASSWORD to use a keystore")
    args.out.mkdir(parents=True, exist_ok=True)
    build = args.sdk / "build-tools/36.1.0"
    android_jar = args.sdk / "platforms/android-36/android.jar"
    # Apktool expands resource file names that can exceed Windows path limits
    # when the project itself is already nested several directories deep.
    with TemporaryDirectory(prefix="dx-") as directory:
        build_mod(args, build, android_jar, Path(directory))


if __name__ == "__main__":
    main()
