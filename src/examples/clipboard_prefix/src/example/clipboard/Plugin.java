package example.clipboard;

import discordx.api.Hooks;
import discordx.api.PluginContext;

public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        context.preferences().edit().putBoolean("cleanup", false).apply();
        context.onCleanup(() -> context.preferences().edit().putBoolean("cleanup", true).apply());
        context.preferences().edit().putBoolean("queued", false).putBoolean("async", false).apply();
        context.runOnMain(() -> context.preferences().edit().putBoolean("queued", true).apply());
        context.runAsync(() -> { Thread.sleep(200); return true; },
            value -> context.preferences().edit().putBoolean("async", true).apply(),
            error -> context.preferences().edit().putBoolean("async", true).apply());
        context.register(Hooks.CLIPBOARD, text -> {
            if ("fail".contentEquals(text)) throw new IllegalStateException("Example callback failure");
            return "Example: " + text;
        });
        context.ui().settings("Example settings", page -> {
            page.text("This page belongs to an independently loaded plugin.");
            page.button("Save example setting", () -> context.preferences().edit().putBoolean("saved", true).apply());
        });
        context.register(Hooks.GATEWAY_JSON, (connection, dispatch) -> {
            if ("SDK_TEST".equals(dispatch.optString("t"))) {
                try { dispatch.getJSONObject("d").put("value", "after"); }
                catch (org.json.JSONException invalid) { throw new IllegalArgumentException(invalid); }
            }
            return dispatch;
        });
        context.register(Hooks.HTTP_REQUEST, request -> "https://example.invalid/typing".equals(request.url)
            ? new Hooks.Reply(204, null, new byte[0]) : null);
        context.register(Hooks.HTTP_BODY, (url, bytes) -> "https://example.invalid/body".equals(url)
            ? "{\"modified\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8) : bytes);
    }
}
