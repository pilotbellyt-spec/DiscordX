package discordx.plugins.fake_nitro;

import discordx.api.PluginContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Plugin implements discordx.api.Plugin {
    private static final Pattern TOKEN = Pattern.compile(
        "(?s)(`+).*?\\1|(?<!\\\\)<(a?):([A-Za-z0-9_]{2,32}):([1-9][0-9]{16,19})>|(?<![A-Za-z0-9_\\\\]):([A-Za-z0-9_]{2,32}):(?![A-Za-z0-9_])");

    private final Map<String, Map<String, String>> guilds = new HashMap<>();
    private volatile Map<String, String> names = java.util.Collections.emptyMap();

    private static Map<String, String> emojiNames(JSONArray emojis) {
        Map<String, String> result = new HashMap<>();
        if (emojis == null) return result;
        for (int i = 0; i < emojis.length(); i++) {
            JSONObject emoji = emojis.optJSONObject(i);
            if (emoji == null || !emoji.optString("id").matches("[1-9][0-9]{16,19}")) continue;
            result.put(emoji.optString("name"), emoji.optString("id") + (emoji.optBoolean("animated") ? ".gif" : ".png"));
        }
        return result;
    }

    private synchronized void rebuild() {
        Map<String, String> result = new HashMap<>();
        HashSet<String> ambiguous = new HashSet<>();
        for (Map<String, String> guild : guilds.values()) {
            for (Map.Entry<String, String> entry : guild.entrySet()) {
                String previous = result.put(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue())) ambiguous.add(entry.getKey());
            }
        }
        result.keySet().removeAll(ambiguous);
        names = result;
    }

    @Override public void start(PluginContext context) {
        context.runAsync(() -> context.chat().queryCache("SELECT k, json_extract(CAST(v AS TEXT), '$.emojis') AS emojis FROM guilds"),
            rows -> {
                synchronized (this) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row == null) continue;
                        try {
                            guilds.putIfAbsent(row.optString("k").replaceFirst("^0+", ""), emojiNames(new JSONArray(row.getString("emojis"))));
                        } catch (org.json.JSONException ignored) {}
                    }
                    rebuild();
                }
            }, error -> {});
        context.register(discordx.api.Hooks.GATEWAY_JSON, (connection, event) -> {
            String type = event.optString("t");
            JSONObject data = event.optJSONObject("d");
            if (data == null || !("GUILD_CREATE".equals(type) || "GUILD_EMOJIS_UPDATE".equals(type) || "GUILD_DELETE".equals(type))) return event;
            String guild = data.optString("guild_id", data.optString("id"));
            if (guild.isEmpty()) return event;
            synchronized (this) {
                if ("GUILD_DELETE".equals(type)) guilds.put(guild, java.util.Collections.emptyMap());
                else if (data.has("emojis")) guilds.put(guild, emojiNames(data.optJSONArray("emojis")));
                rebuild();
            }
            return event;
        });
        context.ui().settings("Emoji links", page -> {
            int size = context.preferences().getInt("size", 128);
            page.button("Image size: " + size, () -> {
                context.preferences().edit().putInt("size", size >= 512 ? 64 : size * 2).apply();
                context.ui().refresh();
            });
            page.toggle("Use emoji name as link text", context.preferences().getBoolean("named", false),
                value -> context.preferences().edit().putBoolean("named", value).apply());
        });
        context.chat().beforeSend(draft -> {
            if (draft == null || draft.indexOf(':') < 0) return draft;
            Matcher token = TOKEN.matcher(draft);
            StringBuffer result = new StringBuffer();
            while (token.find()) {
                if (token.group(1) != null) continue;
                String name = token.group(4) == null ? token.group(5) : token.group(3);
                String file = token.group(4) == null ? names.get(name)
                    : token.group(4) + (token.group(2).isEmpty() ? ".png" : ".gif");
                if (file == null) continue;
                String url = "https://cdn.discordapp.com/emojis/" + file + "?size=" + context.preferences().getInt("size", 128);
                String link = context.preferences().getBoolean("named", false) ? "[:" + name + ":](" + url + ")" : url;
                token.appendReplacement(result, Matcher.quoteReplacement(link));
            }
            return token.appendTail(result).toString();
        });
    }
}
