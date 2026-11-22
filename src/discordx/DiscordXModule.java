package discordx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicBoolean;
import discordx.loader.ModuleApi;

public final class DiscordXModule implements ModuleApi {
    private NativeDiscordUi ui;
    private final AtomicBoolean loggedJson = new AtomicBoolean();
    private final AtomicBoolean loggedEtf = new AtomicBoolean();
    private final AtomicBoolean loggedGateway = new AtomicBoolean();
    private final AtomicBoolean loggedRewrite = new AtomicBoolean();
    private final AtomicBoolean loggedRender = new AtomicBoolean();
    @Override public int abi() { return ModuleApi.ABI; }
    @Override public void prepare(Activity activity) {
        DiscordX.prepare(activity);
        ui = new NativeDiscordUi(activity);
    }
    @Override public void attach(Activity activity) { DiscordX.applyWindowPreferences(activity); }
    @Override public void detach(Activity activity) {
        if (ui == null || ui.activity != activity) return;
        ui.reset();
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
    @Override public int foreground(int color) { return ThemePalette.foreground(color); }
    @Override public int[] gradient(int[] colors) { return ThemePalette.gradient(colors); }
    @Override public void socketOpened(Object owner, int id, String url) {
        MessageLogger.socketOpened(owner, id, url);
        if (!loggedGateway.get() && MessageLogger.tracking(owner, id) && loggedGateway.compareAndSet(false, true)) {
            Log.i("DiscordX", "MessageLogger registered the gateway socket");
        }
    }
    @Override public void socketClosed(Object owner, int id) { MessageLogger.socketClosed(owner, id); }
    @Override public byte[] onFrame(Object owner, int id, byte[] bytes) {
        if (bytes != null && bytes.length > 0) {
            boolean etf = Byte.toUnsignedInt(bytes[0]) == GatewayTerms.VERSION;
            AtomicBoolean logged = etf ? loggedEtf : loggedJson;
            if ((etf || bytes[0] == '{') && !logged.get() && MessageLogger.tracking(owner, id)
                    && logged.compareAndSet(false, true)) {
                Log.i("DiscordX", etf ? "MessageLogger received ETF" : "MessageLogger received JSON");
            }
        }
        byte[] result = MessageLogger.onFrame(owner, id, bytes);
        if (result != bytes && loggedRewrite.compareAndSet(false, true)) {
            Log.i("DiscordX", "MessageLogger decorated a chat event");
        }
        return result;
    }
    @Override public void onResponse(String url, byte[] bytes) { ProfileIdentity.response(url, bytes); }
    @Override public CharSequence decorateMessageText(CharSequence text) {
        CharSequence result = MessageHistoryText.render(text);
        if (result != text && loggedRender.compareAndSet(false, true)) {
            Log.i("DiscordX", "MessageLogger decorated the native text view");
        }
        return result;
    }
    @Override public CharSequence cleanMessageText(CharSequence text) { return MessageHistoryText.clean(text); }
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
