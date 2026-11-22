package discordx;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import java.lang.reflect.Proxy;

final class PhoenixModal {
    private static final int MODAL_TAG = 0x7e445802;
    private static final String OVERLAYS = "com.discord.nativeapp.modules.overlays.";
    private final Class<?> callbackType;
    private final View handle;
    private boolean closed;

    private PhoenixModal(Class<?> callbackType, View handle) {
        this.callbackType = callbackType;
        this.handle = handle;
    }

    static PhoenixModal open(Activity activity, LinearLayout content) {
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> overlay = Class.forName(OVERLAYS + "a1", false, loader);
            Class<?> renderer = Class.forName(OVERLAYS + "w0", false, loader);
            Class<?> event = Class.forName("uf.t", false, loader);
            Class<?> callback = Class.forName("kotlin.jvm.functions.Function0", false, loader);
            View handle = (View) overlay.getConstructor(Activity.class, String.class, LinearLayout.class, event)
                .newInstance(activity, "modal", content, null);
            Object instance = renderer.getConstructor(overlay).newInstance(handle);
            overlay.getField("y").set(handle, instance);
            PhoenixModal modal = new PhoenixModal(callback, handle);
            handle.setTag(MODAL_TAG, modal);
            renderer.getMethod("l").invoke(instance);
            Dialog dialog = (Dialog) renderer.getField("v").get(instance);
            Window window = dialog.getWindow();
            window.setFormat(PixelFormat.TRANSLUCENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE));
            window.setDimAmount(0.40f);
            dialog.setOnKeyListener((source, key, keyEvent) -> {
                if (key != KeyEvent.KEYCODE_BACK && key != KeyEvent.KEYCODE_ESCAPE) return false;
                if (keyEvent.getAction() == KeyEvent.ACTION_UP) modal.close();
                return true;
            });
            Log.i("DiscordX", "Plugin opened the Phoenix modal");
            return modal;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Log.w("DiscordX", "Could not open the Phoenix modal", failure);
            return null;
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        handle.setTag(MODAL_TAG, null);
        try {
            Object removed = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[] {callbackType},
                (proxy, method, arguments) -> null);
            handle.getClass().getMethod("a", boolean.class, callbackType).invoke(handle, false, removed);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Log.w("DiscordX", "Could not close the Phoenix modal", failure);
        }
    }

    static void event(View handle, String event) {
        if (!"dismiss".equals(event)) return;
        Object modal = handle.getTag(MODAL_TAG);
        if (modal instanceof PhoenixModal) ((PhoenixModal) modal).close();
    }
}
