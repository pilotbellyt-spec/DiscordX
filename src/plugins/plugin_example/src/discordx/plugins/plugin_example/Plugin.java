package discordx.plugins.plugin_example;

import discordx.api.Chat;
import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.util.Locale;
import org.json.JSONObject;
import org.json.JSONException;

public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
        context.ui().settings("Example settings", page -> {
            page.toggle("Uppercase incoming messages", context.preferences().getBoolean("shout", false),
                enabled -> context.preferences().edit().putBoolean("shout", enabled).apply());
            page.input("Suffix", context.preferences().getString("suffix", ""), false,
                value -> context.preferences().edit().putString("suffix", value).apply());
            page.input("Every nth message", String.valueOf(context.preferences().getInt("every", 1)), false, text -> {
                try { context.preferences().edit().putInt("every", Math.max(1, Integer.parseInt(text))).apply(); }
                catch (NumberFormatException ignored) { }
            });
            page.button("Reset counter", () -> seen.set(0));
        });
        context.register(Hooks.GATEWAY_JSON, (connection, event) -> {
            JSONObject message = event.optJSONObject("d");
            if (!"MESSAGE_CREATE".equals(event.optString("t")) || message == null) return event;
            String text = message.optString("content");
            if (text.isEmpty() || seen.incrementAndGet() % context.preferences().getInt("every", 1) != 0) return event;
            if (context.preferences().getBoolean("shout", false)) text = text.toUpperCase(Locale.ROOT);
            try { message.put("content", text + context.preferences().getString("suffix", "")); }
            catch (JSONException ignored) { }
            return event;
        });
        context.chat().button(Chat.Button.HEADER, "Example", "res://ic_mana_puzzle_piece_plus", () -> local(context, "Header button pressed"));
        context.chat().button(Chat.Button.COMPOSER, "Shrug", "res://ic_mana_angle_brackets", () -> local(context, "¯\\_(ツ)_/¯"));
        context.chat().accessory(Chat.Placement.TIMESTAMP, id -> new Chat.Label("#" + id.substring(Math.max(0, id.length() - 4)), 0xffaab0bb));
        context.ui().messageAction("Show message ID", message -> context.chat().reply(message.id, message.id));
        context.ui().profileAction("Show user ID", id -> context.ui().open("User ID", page -> page.text(id)));
        context.chat().slashCommand("echo", "Echo locally", value -> local(context, value.isEmpty() ? "(empty)" : value));
        context.chat().beforeSend(text -> text == null ? null : text.replaceAll("\\bteh\\b", "the"));
    }

    private static void local(PluginContext context, String text) {
        context.runAsync(() -> context.chat().selectedChannel(), id -> {
            if (id != null) context.chat().localMessage(id, text);
        }, error -> {});
    }
}
