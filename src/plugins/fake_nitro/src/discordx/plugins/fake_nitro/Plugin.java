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
    private volatile Map<String,String> filesById = java.util.Collections.emptyMap();
    private int generation;
    private volatile Map<String, String> names = java.util.Collections.emptyMap();

    private static Map<String, String> emojiNames(JSONArray emojis) {
        Map<String, String> result = new HashMap<>();
        if (emojis == null) return result;
        for (int i = 0; i < emojis.length(); i++) {
            JSONObject emoji = emojis.optJSONObject(i);
            if (emoji == null || !emoji.optString("id").matches("[1-9][0-9]{16,19}")) continue;
            result.put(emoji.optString("id") + (emoji.optBoolean("animated") ? ".gif" : ".png"), emoji.optString("name"));
        }
        return result;
    }

    private synchronized void rebuild() {
        Map<String, String> result = new HashMap<>();
        HashSet<String> ambiguous = new HashSet<>();
        for (Map<String, String> guild : guilds.values()) {
            for (Map.Entry<String, String> entry : guild.entrySet()) {
                String previous = result.put(entry.getValue(), entry.getKey());
                if (previous != null && !previous.equals(entry.getKey())) ambiguous.add(entry.getValue());
            }
        }
        result.keySet().removeAll(ambiguous);
        names = result;
        Map<String,String> files = new HashMap<>();
        for (Map<String,String> guild : guilds.values())
            for (String file : guild.keySet()) files.put(file.substring(0, file.indexOf('.')), file);
        filesById = files;
    }

    @Override public void start(PluginContext context) {
        loadCache(context);
        context.register(discordx.api.Hooks.PICKER_EMOJIS, (id, name, uri) -> {
            String file = filesById.get(id);
            if (file == null) file = id + (uri.contains("/" + id + ".gif") ? ".gif" : ".webp");
            return link(context, name.matches("[A-Za-z0-9_]{2,32}") ? name : "emoji", file);
        });
        context.register(discordx.api.Hooks.GATEWAY_JSON, (connection, event) -> {
            String type = event.optString("t");
            JSONObject data = event.optJSONObject("d");
            if (data == null) return event;
            synchronized (this) {
                if ("READY".equals(type)) {
                    generation++;
                    guilds.clear();
                    JSONArray joined = data.optJSONArray("guilds");
                    if (joined != null) for (int i = 0; i < joined.length(); i++) remember(joined.optJSONObject(i));
                    rebuild();
                    context.runOnMain(() -> loadCache(context));
                } else if ("GUILD_CREATE".equals(type) || "GUILD_UPDATE".equals(type) || "GUILD_EMOJIS_UPDATE".equals(type)) {
                    remember(data);
                    rebuild();
                } else if ("GUILD_DELETE".equals(type) && !data.optBoolean("unavailable")) {
                    guilds.put(data.optString("id"), java.util.Collections.emptyMap());
                    rebuild();
                }
            }
            return event;
        });
        context.ui().settings("Emoji links", page -> {
            int size = context.preferences().getInt("size", 128);
            page.button("Image size: " + size, () -> {
                context.preferences().edit().putInt("size", size >= 512 ? 64 : size * 2).apply();
                context.ui().refresh();
            });
            page.preferenceToggle("Use emoji name as link text", "named", false);
        });
        context.chat().beforeSend(draft -> {
            if (draft == null || draft.indexOf(':') < 0) return draft;
            Matcher token = TOKEN.matcher(draft);
            StringBuffer result = new StringBuffer();
            while (token.find()) {
                if (token.group(1) != null || (token.start() > 0 && draft.charAt(token.start() - 1) == '['
                        && draft.startsWith("](https://cdn.discordapp.com/emojis/", token.end()))) continue;
                String name = token.group(4) == null ? token.group(5) : token.group(3);
                String file = token.group(4) == null ? names.get(name)
                    : token.group(4) + (token.group(2).isEmpty() ? ".png" : ".gif");
                if (file == null) continue;
                token.appendReplacement(result, Matcher.quoteReplacement(link(context, name, file)));
            }
            return token.appendTail(result).toString();
        });
    }

    private static String link(PluginContext context, String name, String file) {
        String url = "https://cdn.discordapp.com/emojis/" + file + "?size=" + context.preferences().getInt("size", 128);
        return context.preferences().getBoolean("named", false) ? "[:" + name + ":](" + url + ")" : url;
    }

    private void remember(JSONObject data) {
        if (data == null) return;
        String id = data.optString("guild_id", data.optString("id"));
        if (id.isEmpty()) return;
        if (data.optJSONArray("emojis") != null) guilds.put(id, emojiNames(data.optJSONArray("emojis")));
    }

    private synchronized void loadCache(PluginContext context) {
        final int version = generation;
        context.runAsync(() -> {
            Map<String,JSONObject> records = new HashMap<>();
            String key = "";
            int index = -1;
            for (;;) {
                JSONArray rows = context.chat().queryCache("SELECT g.k AS guild, e.key AS idx, "
                    + "coalesce(json_extract(CAST(g.v AS TEXT),'$.name'),json_extract(CAST(g.v AS TEXT),'$.properties.name')) AS name, "
                    + "json_extract(e.value,'$.id') AS id, json_extract(e.value,'$.name') AS emoji, "
                    + "json_extract(e.value,'$.animated') AS animated FROM guilds g, "
                    + "json_each(CAST(g.v AS TEXT),'$.emojis') e WHERE g.k >= ? AND "
                    + "(g.k > ? OR e.key > CAST(? AS INTEGER)) ORDER BY g.k,e.key LIMIT 400",
                    key, key, Integer.toString(index));
                if (rows.length() == 0) break;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    key = row.getString("guild");
                    index = row.getInt("idx");
                    String guild = key.replaceFirst("^0+", "");
                    JSONObject record = records.get(guild);
                    if (record == null) {
                        record = new JSONObject().put("name", row.opt("name")).put("emojis", new JSONArray());
                        records.put(guild, record);
                    }
                    record.getJSONArray("emojis").put(new JSONObject().put("id", row.optString("id"))
                        .put("name", row.optString("emoji")).put("animated", row.optInt("animated") == 1));
                }
            }
            return records;
        }, records -> {
            synchronized (this) {
                if (version != generation) return;
                for (Map.Entry<String,JSONObject> entry : records.entrySet()) {
                    JSONObject row = entry.getValue();
                    guilds.putIfAbsent(entry.getKey(), emojiNames(row.optJSONArray("emojis")));
                }
                rebuild();
            }
        }, error -> android.util.Log.w("DiscordX", "Could not read the server emoji cache", error));
    }
}
