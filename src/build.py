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
HOST = "Ldiscordx/loader/Bootstrap;"
VERSION = "356207"
SOURCES = sorted((ROOT / "discordx").glob("*.java")) + sorted((ROOT / "api").rglob("*.java"))
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
    from host_discovery import method_signature
    preference = method_signature(smali, ['"launch_client"'], '(Landroid/content/Context;)Ljava/lang/String;')
    health = method_signature(smali, ['unhealthy_phoenix_launches', '3 launches'], '(Landroid/content/ContextWrapper;)Z')

    def prefer_phoenix(body):
        key = re.search(r'const-string ([vp]\d+), "launch_client"', body)
        if key is None: raise ValueError('Expected launch preference key')
        call = re.search(r'invoke-interface \{[^,]+, ' + re.escape(key[1]) + r', ([vp]\d+)\}, Landroid/content/SharedPreferences;->getString', body[key.end():])
        if call is None: raise ValueError('Expected saved launch preference lookup')
        default = call[1]
        start, end = key.end(), key.end() + call.end()
        between = body[start:end]
        anchors = re.findall(r'^    const/(?:4|16) ' + re.escape(default) + r', 0x0$', between, re.MULTILINE)
        if len(anchors) != 1: raise ValueError('Expected one default launch preference value')
        return body[:start] + between.replace(anchors[0], f'    const-string {default}, "phoenix"') + body[end:]

    smali = method_body(smali, preference, prefer_phoenix)
    return method_body(smali, health, lambda body: '    .locals 1\n\n    const/4 v0, 0x1\n    return v0\n')


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
    smali = patch_activity_result(smali)
    return method_body(smali, "onDestroy()V", lambda body: splice(
        body, r"^    \.locals \d+$",
        f"    invoke-static/range {{p0 .. p0}}, {HOST}->detach(Landroid/app/Activity;)V\n",
    ))


def patch_activity_result(smali):
    signature = "onActivityResult(IILandroid/content/Intent;)V"
    addition = f"""    invoke-static {{p1, p2, p3}}, {HOST}->onActivityResult(IILandroid/content/Intent;)Z
    move-result v0
    if-eqz v0, :discordx_activity_result
    return-void
    :discordx_activity_result
"""
    if re.search(r"^\.method [^\n]+ " + re.escape(signature), smali, re.MULTILINE):
        return method_body(smali, signature, lambda body: splice(body, r"^    \.locals [1-9]\d*$", addition))
    parent = re.search(r"^\.super (.+)$", smali, re.MULTILINE).group(1)
    return smali.rstrip() + f"""

.method protected {signature}
    .locals 1

{addition}
    invoke-super {{p0, p1, p2, p3}}, {parent}->{signature}
    return-void
.end method
"""


def patch_browser(smali):
    if HOST in smali or "android.support.customtabs.extra.TITLE_VISIBILITY" not in smali:
        raise ValueError("Unexpected Phoenix browser handler")
    data = re.findall(r'invoke-virtual \{(\w+), (\w+)\}, Landroid/content/Intent;->setData\(Landroid/net/Uri;\)Landroid/content/Intent;', smali)
    launch = re.findall(r'invoke-virtual \{(\w+), (\w+), (\w+)\}, Landroid/content/Context;->startActivity\(Landroid/content/Intent;Landroid/os/Bundle;\)V', smali)
    if len(data) != 1 or len(launch) != 1 or data[0][0] != launch[0][1]:
        raise ValueError('Expected one custom-tab URI and launch handoff')
    intent, uri = data[0]
    context, _, options = launch[0]
    anchor = f'invoke-virtual {{{intent}, {uri}}}, Landroid/content/Intent;->setData(Landroid/net/Uri;)Landroid/content/Intent;'
    smali = exact(smali, anchor, f'invoke-static {{{context}, {uri}}}, {HOST}->cleanLink(Landroid/content/Context;Landroid/net/Uri;)Landroid/net/Uri;\n'
                  f'    move-result-object {uri}\n    ' + anchor)
    anchor = f'invoke-virtual {{{context}, {intent}, {options}}}, Landroid/content/Context;->startActivity(Landroid/content/Intent;Landroid/os/Bundle;)V'
    return exact(smali, anchor, f'invoke-static {{{context}, {intent}, {options}}}, {HOST}->openLink(Landroid/content/Context;Landroid/content/Intent;Landroid/os/Bundle;)V')


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
    def edit(body):
        if body.count("    return-object p1") != 2:
            raise ValueError("Expected Cronet and fallback HTTP returns")
        body = body.replace("    return-object p1", f"    invoke-static {{p1}}, {HOST}->onHttpResponse(Ljava/lang/Object;)Ljava/lang/Object;\n"
                            "    move-result-object p1\n    check-cast p1, Lg90/q0;\n    return-object p1")
        anchor = "    iget-object v0, p1, Lm90/e;->e:Lg90/k0;"
        return exact(body, anchor, anchor + f"\n    invoke-static {{v0}}, {HOST}->interceptHttp(Ljava/lang/Object;)Ljava/lang/Object;\n"
                     "    move-result-object v1\n    if-eqz v1, :discordx_http_request\n"
                     "    check-cast v1, Lg90/q0;\n    return-object v1\n    :discordx_http_request\n")
    if "discordxBuffer()" in smali:
        raise ValueError("HTTP host already contains the DiscordX buffer bridge")
    return method_body(smali, "a(Lm90/e;)Lg90/q0;", edit) + """

.method public static discordxBuffer()Ljava/lang/Object;
    .locals 1
    new-instance v0, Lw90/h;
    invoke-direct {v0}, Ljava/lang/Object;-><init>()V
    return-object v0
.end method
"""


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
    run("javac", "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-cp", classpath, "-d", classes, *sources)
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
    for file in (base, apktool, Path(__file__), ROOT / "host_discovery.py"):
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
    from host_discovery import discover
    targets = discover(decoded / "smali", HOST_PATCHES)
    for target, modified in targets:
        target.write_text(modified, encoding="utf-8", newline="\n")
    rebuilt = work / "rebuilt.apk"
    run("java", "-Xmx2g", "-jar", apktool, "b", "-o", rebuilt, decoded)
    with ZipFile(rebuilt) as archive, TemporaryDirectory(prefix="cache-", dir=cache) as staging:
        dex = Path(staging) / "classes.dex"
        dex.write_bytes(archive.read("classes.dex"))
        dex.replace(cached)
    return cached


HOST_PATCHES = (
    patch_launch,
    patch_activity,
    patch_browser,
    patch_gateway,
    patch_drawables,
    patch_text_span,
    patch_text_view,
    patch_edit_text,
    patch_http,
    patch_overlay,
    patch_render,
    patch_view_events,
    patch_clipboard,
    patch_motion,
)


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
    from build_plugins import build_plugins
    index, packages = build_plugins(args.sdk, args.out / "plugins", work / "plugin-build")
    base = inputs["base"]
    replacement = host_dex(base, args.apktool, work)
    with ZipFile(base) as archive:
        dex_names = {entry for entry in archive.namelist() if re.fullmatch(r"classes\d*\.dex", entry)}
    bootstrap_name = f"classes{len(dex_names) + 1}.dex"
    if bootstrap_name in dex_names:
        raise ValueError("Unexpected source DEX layout")
    patched = work / "discordx-base.apk"
    replacements = {"classes.dex": replacement, bootstrap_name: bootstrap, MODULE_ASSET: payload,
                    "assets/discordx/plugins/index.json": index}
    replacements.update({"assets/discordx/plugins/" + package.name: package for package in packages})
    repack(base, patched, replacements)

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
