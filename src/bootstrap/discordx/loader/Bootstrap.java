package discordx.loader;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import dalvik.system.InMemoryDexClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public final class Bootstrap {
    public static final String MODULE_ASSET = "discordx/module.dex";
    private static final int MODULE_LIMIT = 2 * 1024 * 1024;
    private static final String TAG = "DiscordXLoader";
    private static volatile ModuleApi module;
    private static boolean attempted;

    private Bootstrap() {}

    public static void prepare(Activity activity) {
        ModuleApi loaded = load(activity);
        if (loaded != null) loaded.prepare(activity);
    }

    private static synchronized ModuleApi load(Context context) {
        if (attempted) return module;
        attempted = true;
        if (Build.VERSION.SDK_INT < 26) {
            Log.w(TAG, "DiscordX modules require Android 8 or later");
            return null;
        }
        // Android verifies this asset as part of the signed APK. Only this asset
        // supplies executable code to the loader.
        try (InputStream input = context.getAssets().open(MODULE_ASSET)) {
            ByteArrayOutputStream contents = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1;) {
                if (contents.size() + n > MODULE_LIMIT) throw new IOException("Module exceeds size limit");
                contents.write(buffer, 0, n);
            }
            ClassLoader loader = new InMemoryDexClassLoader(ByteBuffer.wrap(contents.toByteArray()),
                Bootstrap.class.getClassLoader());
            Class<?> entry = Class.forName("discordx.DiscordXModule", true, loader);
            if (entry.getClassLoader() != loader) throw new IllegalStateException("Module was not isolated");
            ModuleApi instance = entry.asSubclass(ModuleApi.class).getConstructor().newInstance();
            if (instance.abi() != ModuleApi.ABI) throw new IllegalStateException("Module ABI mismatch");
            module = instance;
            Log.i(TAG, "Loaded signed DiscordX module, ABI " + instance.abi());
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.e(TAG, "Module unavailable; continuing with Phoenix defaults", error);
        }
        return module;
    }

    public static void attach(Activity activity) {
        if (module != null) module.attach(activity);
    }

    public static void detach(Activity activity) {
        if (module != null) module.detach(activity);
    }

    public static boolean handleKey(Activity activity, KeyEvent event) {
        return module != null && module.handleKey(activity, event);
    }

    public static Uri cleanLink(Context context, Uri uri) {
        return module == null ? uri : module.cleanLink(context, uri);
    }

    public static void openLink(Context context, Intent intent, Bundle options) {
        Intent selected = module == null ? intent : module.browserIntent(intent);
        try {
            context.startActivity(selected, options);
        } catch (ActivityNotFoundException error) {
            if (selected == intent) throw error;
            context.startActivity(intent, options);
        }
    }

    public static CharSequence cleanCopiedText(CharSequence text) {
        return module == null ? text : module.cleanCopiedText(text);
    }

    public static boolean reduceMotion(boolean preference) {
        return module == null ? preference : module.reduceMotion(preference);
    }

    public static void themeFill(GradientDrawable view, int color) {
        if (module == null) view.setColor(color);
        else module.themeFill(view, color);
    }

    public static void themeRoot(View view, int color) {
        if (module == null) view.setBackgroundColor(color);
        else module.themeRoot(view, color);
    }

    public static void themeText(TextView view) {
        if (module != null) module.themeText(view);
    }

    public static int foreground(int color) {
        return module == null ? color : module.foreground(color);
    }

    public static int[] gradient(int[] colors) {
        return module == null ? colors : module.gradient(colors);
    }

    public static void socketOpened(Object owner, int id, String url) {
        if (module != null) module.socketOpened(owner, id, url);
    }

    public static void socketClosed(Object owner, int id) {
        if (module != null) module.socketClosed(owner, id);
    }

    public static byte[] onFrame(Object owner, int id, byte[] bytes) {
        return module == null ? bytes : module.onFrame(owner, id, bytes);
    }

    public static void onResponse(String url, byte[] bytes) {
        if (module != null) module.onResponse(url, bytes);
    }

    public static CharSequence decorateMessageText(CharSequence text) {
        return module == null ? text : module.decorateMessageText(text);
    }

    public static CharSequence cleanMessageText(CharSequence text) {
        return module == null ? text : module.cleanMessageText(text);
    }

    public static boolean onOverlayEvent(View handle, String event) {
        return module != null && module.onOverlayEvent(handle, event);
    }

    public static Object onRenderBatch(Object renderer, Object batch) {
        return module == null ? batch : module.onRenderBatch(renderer, batch);
    }

    public static boolean onViewEvent(int id, String event, Object payload, Object completion) {
        return (id >= 0x70000000 || "navigate".equals(event) || "appeared".equals(event))
            && module != null && module.onViewEvent(id, event, payload, completion);
    }
}
