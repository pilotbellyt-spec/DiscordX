package discordx.plugins.message_logger;

import discordx.api.Chat;
import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class Plugin implements discordx.api.Plugin {
    private static final Pattern MESSAGES = Pattern.compile("https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/channels/([1-9][0-9]{16,19})/messages(?:\\?[^#]*)?");
    private static final Pattern MESSAGE = Pattern.compile("https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/channels/([1-9][0-9]{16,19})/messages/([1-9][0-9]{16,19})(?:\\?[^#]*)?");

    @Override public void start(PluginContext context) {
        MessageLogger.usePrefix(context.textTokenPrefix());
        MessageLogger.setEnabled(true);
        context.register(Hooks.GATEWAY, new Hooks.Gateway() {
            @Override public void opened(Hooks.Connection connection) { MessageLogger.socketOpened(connection, 0, connection.url); }
            @Override public void closed(Hooks.Connection connection) { MessageLogger.socketClosed(connection, 0); }
            @Override public byte[] frame(Hooks.Connection connection, byte[] bytes) { return bytes; }
        });
        android.content.SharedPreferences prefs = context.preferences();
        context.ui().settings("Message history", page -> {
            page.preferenceToggle("Log deleted messages", "deletes", true);
            page.preferenceToggle("Log edited messages", "edits", true);
            page.preferenceToggle("Show inline edit history", "inline", true);
            page.button("Clear cached history", MessageLogger::clear);
        });
        Chat chat = context.chat();
        chat.tint(id -> {
            MessageLogger.Decoration history = MessageLogger.history(id);
            return history != null && history.deleted ? Integer.valueOf(0xfff07d83) : null;
        });
        chat.accessory(Chat.Placement.TIMESTAMP, id -> {
            MessageLogger.Decoration history = MessageLogger.history(id);
            if (history == null) return null;
            return history.deleted ? new Chat.Label("Deleted", 0xffef777c) : new Chat.Label("Edited", 0xffb5adbf);
        });
        chat.accessory(Chat.Placement.CONTENT, id -> {
            MessageLogger.Decoration history = MessageLogger.history(id);
            return !prefs.getBoolean("inline", true) || history == null || history.versions.isEmpty() ? null
                : new Chat.Label(String.join("\n", history.versions), 0xffaaa7b3);
        });
        context.register(Hooks.GATEWAY_JSON, (connection, event) -> {
            byte[] json = event.toString().getBytes(StandardCharsets.UTF_8);
            byte[] marked = MessageLogger.onFrame(connection, 0, json, prefs.getBoolean("edits", true), prefs.getBoolean("deletes", true));
            if (marked == json) return event;
            JSONObject data = event.optJSONObject("d");
            String id = data == null ? "" : data.optString("id");
            if (MessageLogger.snowflake(id)) context.runOnMain(() -> chat.redraw(id));
            try { return new JSONObject(new String(marked, StandardCharsets.UTF_8)); }
            catch (JSONException invalid) { return event; }
        });
        context.register(Hooks.HTTP_RESPONSE, Plugin::received);
        context.register(Hooks.HTTP_REQUEST, request -> {
            if ("DELETE".equals(request.method) && request.url != null) {
                Matcher message = MESSAGE.matcher(request.url);
                if (message.matches()) MessageLogger.deleting(message.group(2), message.group(1));
            }
            return null;
        });
        context.register(Hooks.DISPLAY_TEXT, MessageHistoryText::render);
        context.register(Hooks.COMPOSER_TEXT, MessageHistoryText::clean);
        context.register(Hooks.CLIPBOARD, MessageHistoryText::clean);
    }

    private static void received(String url, byte[] bytes) {
        if (url == null || bytes == null || bytes.length > 1024 * 1024) return;
        Matcher route = MESSAGES.matcher(url);
        if (!route.matches()) return;
        try {
            JSONArray messages = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message != null && route.group(1).equals(message.optString("channel_id"))) MessageLogger.remember(message, route.group(1));
            }
        } catch (JSONException invalid) { /* Leave unrelated responses unchanged. */ }
    }
    @Override public void stop() { MessageLogger.setEnabled(false); }
}
