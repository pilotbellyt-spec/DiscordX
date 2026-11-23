package discordx.plugins.translate;

import discordx.api.PluginContext;
import discordx.api.TextTokens;
import java.util.Locale;
import android.util.LruCache;
import discordx.api.Chat;
import discordx.api.Hooks;
import org.json.JSONObject;

public final class Plugin implements discordx.api.Plugin {
    private final LruCache<String, String> labels = new LruCache<>(256);
    private final java.util.Map<String, Object> pending = new java.util.HashMap<>();

    @Override public void start(PluginContext context) {
        String fallback = Locale.getDefault().getLanguage();
        if (fallback.isEmpty()) fallback = "en";
        final String defaultLanguage = fallback;
        context.ui().settings("Translation", controls -> {
            controls.input("Target language", context.preferences().getString("language", defaultLanguage), false,
                value -> context.preferences().edit().putString("language", value.trim()).apply());
            controls.input("Send new messages in these channels to Google (channel IDs)", context.preferences().getString("channels", ""), false,
                value -> context.preferences().edit().putString("channels", value.trim()).apply());
        });
        context.chat().accessory(Chat.Placement.CONTENT, id -> {
            String label = labels.get(id);
            return label == null ? null : new Chat.Label(label, 0xffadb6c4);
        });
        context.register(Hooks.GATEWAY_JSON, (connection, event) -> {
            String type = event.optString("t");
            JSONObject message = event.optJSONObject("d");
            if (message == null || !("MESSAGE_CREATE".equals(type) || "MESSAGE_UPDATE".equals(type) || "MESSAGE_DELETE".equals(type))) return event;
            String channel = message.optString("channel_id"), id = message.optString("id");
            if (!enabled(context, channel) || !id.matches("[1-9][0-9]{16,19}")) return event;
            boolean deleted = "MESSAGE_DELETE".equals(type);
            if (!deleted && !message.has("content")) return event;
            String text = deleted ? "" : TextTokens.clean(message.optString("content")).toString().trim();
            context.runOnMain(() -> translateIncoming(context, channel, id, text, defaultLanguage));
            return event;
        });
        context.ui().messageAction("Translate", message -> {
            String source = TextTokens.clean(message.content).toString();
            String language = context.preferences().getString("language", defaultLanguage);
            String[] result = {"Translating…"};
            context.ui().open("Translation", controls -> controls.text(result[0]));
            context.runAsync(() -> Translation.translate(source, language), translated -> {
                result[0] = translated;
                context.ui().refresh();
            }, error -> {
                result[0] = error.getMessage() == null ? "Translation failed." : error.getMessage();
                context.ui().refresh();
            });
        });
    }

    private static boolean enabled(PluginContext context, String channel) {
        if (channel.isEmpty()) return false;
        String channels = context.preferences().getString("channels", "");
        return !channels.isEmpty() && java.util.Arrays.asList(channels.split("[,\\s]+")).contains(channel);
    }

    private void translateIncoming(PluginContext context, String channel, String id, String text, String fallback) {
        pending.remove(id);
        if (labels.remove(id) != null) context.chat().redraw(id);
        if (text.isEmpty() || text.length() > 4000 || pending.size() >= 8) return;
        String language = context.preferences().getString("language", fallback);
        Object request = new Object();
        pending.put(id, request);
        try {
            context.runAsync(() -> enabled(context, channel) ? Translation.translate(text, language) : null, translated -> {
                if (!pending.remove(id, request) || translated == null || !enabled(context, channel)
                        || !language.equals(context.preferences().getString("language", fallback)) || text.equals(translated)) return;
                labels.put(id, translated);
                context.chat().redraw(id);
            }, error -> pending.remove(id, request));
        } catch (java.util.concurrent.RejectedExecutionException busy) { pending.remove(id, request); }
    }
}
