package discordx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import discordx.loader.ModuleApi;

public final class DiscordXModule implements ModuleApi {
    private NativeDiscordUi ui;
    @Override public int abi() { return ModuleApi.ABI; }
    @Override public void prepare(Activity activity) {
        DiscordX.prepare(activity);
        ui = new NativeDiscordUi(activity);
        DiscordX.manager.activity = activity;
        DiscordX.manager.ui = ui;
        DiscordX.manager.initializeOnce();
    }
    @Override public void attach(Activity activity) { DiscordX.manager.attached(activity); }
    @Override public void detach(Activity activity) {
        if (ui == null || ui.activity != activity) return;
        ui.reset();
        DiscordX.manager.detached(activity);
        ui = null;
    }
    @Override public boolean handleKey(Activity activity, KeyEvent event) {
        if (ui == null || ui.activity != activity || event.getAction() != KeyEvent.ACTION_UP) return false;
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) return ui.back();
        return event.getKeyCode() == KeyEvent.KEYCODE_X && event.hasModifiers(KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON)
            && ui.settingsShortcut();
    }
    @Override public Uri cleanLink(Context context, Uri uri) { return DiscordX.cleanLink(context, uri); }
    @Override public Intent browserIntent(Intent original) { return DiscordX.browserIntent(original); }
    @Override public CharSequence cleanCopiedText(CharSequence text) { return DiscordX.cleanCopiedText(text); }
    @Override public boolean reduceMotion(boolean preference) { return DiscordX.reduceMotion(preference); }
    @Override public void themeFill(GradientDrawable view, int color) { DiscordX.themeFill(view, color); }
    @Override public void themeRoot(View view, int color) { DiscordX.themeRoot(view, color); }
    @Override public void themeText(TextView view) { DiscordX.themeText(view); }
    @Override public int foreground(int color) { return DiscordX.foreground(color); }
    @Override public int[] gradient(int[] colors) { return DiscordX.gradient(colors); }
    @Override public void socketOpened(Object owner, int id, String url) { DiscordX.manager.opened(owner, id, url); }
    @Override public void socketClosed(Object owner, int id) { DiscordX.manager.closed(owner, id); }
    @Override public byte[] onFrame(Object owner, int id, byte[] bytes) { return DiscordX.manager.frame(owner, id, bytes); }
    @Override public Object interceptHttp(Object request) { return HttpBridge.intercept(request); }
    @Override public Object onHttpResponse(Object response) { return HttpBridge.response(response); }
    @Override public CharSequence decorateMessageText(CharSequence text) { return DiscordX.displayText(text); }
    @Override public CharSequence cleanMessageText(CharSequence text) { return DiscordX.cleanMessageText(text); }
    @Override public boolean onActivityResult(int request, int result, Intent data) { return DiscordX.manager.result(request, result, data); }
    @Override public boolean onOverlayEvent(View handle, String event) {
        PhoenixModal.event(handle, event);
        return ui != null && ui.overlayEvent(handle, event);
    }
    @Override public Object onRenderBatch(Object renderer, Object batch) {
        return ui == null ? batch : ui.onBatch(renderer, batch);
    }
    @Override public boolean onViewEvent(int id, String event, Object payload, Object completion) {
        return ui != null && ui.event(id, event, payload, completion);
    }
}
