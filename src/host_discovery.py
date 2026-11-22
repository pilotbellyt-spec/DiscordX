"""Find supported Phoenix hook targets by validated signatures and semantic anchors."""
from pathlib import Path
import subprocess
import re


def method_signature(smali, anchors, descriptor):
    matches = []
    for method in re.finditer(r'^\.method ([^\n]+)\n(.*?)^\.end method$', smali, re.MULTILINE | re.DOTALL):
        signature = method[1].split()[-1]
        if signature.endswith(descriptor) and all(anchor in method[2] for anchor in anchors): matches.append(signature)
    if len(matches) != 1: raise ValueError(f'Expected one method matching {anchors} and {descriptor}, found {len(matches)}')
    return matches[0]


ANCHORS = {
    'patch_launch': ('unhealthy_phoenix_launches', 'launch_client'),
    'patch_activity': ('dispatchKeyEvent(Landroid/view/KeyEvent;)Z', '->setContentView(Landroid/view/View;)V', 'onDestroy()V'),
    'patch_browser': ('android.support.customtabs.extra.TITLE_VISIBILITY',),
    'patch_gateway': ('EarlyGateway', 'dropped by the guest'),
    'patch_drawables': ('Landroid/graphics/drawable/GradientDrawable;->setColor(I)V', 'Llh/k3;->i:Ljava/lang/Float;'),
    'patch_text_span': ('Landroid/text/style/ForegroundColorSpan;-><init>(I)V',),
    'patch_text_view': ('.super Landroid/widget/TextView;', 'onDraw(Landroid/graphics/Canvas;)V'),
    'patch_edit_text': ('.super Landroid/widget/EditText;',),
    'patch_http': ('Lorg/chromium/net/CronetEngine;', '"request"'),
    'patch_overlay': ('Landroid/widget/LinearLayout;', 'Landroid/app/Activity;', 'Lcom/discord/nativeapp/modules/overlays/'),
    'patch_render': ('Lcom/discord/nativeapp/modules/rq;->a:I',),
    'patch_view_events': ('(Llh/k3;Ljava/lang/String;Llh/j3;Lkotlin/jvm/functions/Function0;)V',),
    'patch_clipboard': ('Landroid/content/ClipData;->newPlainText',),
    'patch_motion': ('Lmh/u;->g()Z',),
}


def discover(directory, patches):
    pending = {patch.__name__: (patch, []) for patch in patches}
    directory = Path(directory).resolve()
    for name, (patch, found) in pending.items():
        anchors = ANCHORS[name]
        query = subprocess.run(['rg', '-l', '-F', '-g', '*.smali', '--', anchors[0], str(directory)],
                               capture_output=True, text=True, check=False)
        if query.returncode not in (0, 1): raise RuntimeError(query.stderr.strip())
        for filename in query.stdout.splitlines():
            path = Path(filename)
            relative = path.relative_to(directory).as_posix()
            if name not in {'patch_drawables', 'patch_launch'} and not relative.startswith('com/discord/nativeapp/'):
                continue
            text = path.read_text(encoding='utf-8')
            if not all(anchor in text for anchor in anchors): continue
            try: modified = patch(text)
            except ValueError: continue
            found.append((path, modified))
    result = []
    for name, (_, found) in pending.items():
        if len(found) != 1:
            paths = ', '.join(str(path.relative_to(directory)) for path, _ in found)
            raise ValueError(f'{name}: expected one structurally valid target, found {len(found)} ({paths}). Unsupported host layout; no patch applied.')
        path, modified = found[0]
        print(f'{name}: {path.relative_to(directory)}', flush=True)
        result.append((path, modified))
    return result
