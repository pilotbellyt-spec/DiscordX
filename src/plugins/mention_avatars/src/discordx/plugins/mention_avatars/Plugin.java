package discordx.plugins.mention_avatars;

import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class Plugin implements discordx.api.Plugin {
    private final Map<String,Map<String,String>> messages = new LinkedHashMap<>();
    private static final Pattern HISTORY = Pattern.compile("https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/channels/[0-9]+/messages(?:\\?[^#]*)?");

    @Override public void start(PluginContext context) {
        context.register(Hooks.MENTION_AVATAR, (message, name) -> {
            synchronized (messages) {
                Map<String,String> users = messages.get(message);
                return users == null ? null : users.get(name);
            }
        });
        context.register(Hooks.GATEWAY_JSON, (connection, event) -> {
            String type = event.optString("t");
            if ("READY".equals(type)) { synchronized (messages) { messages.clear(); } }
            if ("MESSAGE_CREATE".equals(type) || "MESSAGE_UPDATE".equals(type)) remember(event.optJSONObject("d"));
            return event;
        });
        context.register(Hooks.HTTP_RESPONSE, (url, body) -> {
            if (url == null || !HISTORY.matcher(url).matches()) return;
            try {
                JSONArray rows = new JSONArray(new String(body, StandardCharsets.UTF_8));
                for (int i = 0; i < rows.length(); i++) remember(rows.optJSONObject(i));
            } catch (JSONException ignored) { }
        });
    }

    private void remember(JSONObject message) {
        if (message == null) return;
        String messageId = message.optString("id");
        JSONArray mentions = message.optJSONArray("mentions");
        if (mentions == null || messageId.isEmpty()) return;
        Map<String,String> users = new HashMap<>();
        for (int i = 0; i < mentions.length(); i++) {
            JSONObject user = mentions.optJSONObject(i);
            if (user == null) continue;
            String id = user.optString("id");
            if (!id.matches("[1-9][0-9]{16,18}")) continue;
            String hash = user.optString("avatar", "");
            String url;
            if (hash.matches("(?:a_)?[a-fA-F0-9]{32}")) url = "https://cdn.discordapp.com/avatars/" + id + "/" + hash + ".png?size=64";
            else {
                int discriminator = user.optInt("discriminator", 0);
                long slot = discriminator > 0 ? discriminator % 5 : (Long.parseUnsignedLong(id) >>> 22) % 6;
                url = "https://cdn.discordapp.com/embed/avatars/" + slot + ".png";
            }
            JSONObject member = user.optJSONObject("member");
            for (String name : new String[] {user.optString("username"), user.optString("global_name"),
                    member == null ? "" : member.optString("nick")}) {
                if (name.isEmpty() || "null".equals(name)) continue;
                if (!users.containsKey(name)) users.put(name, url);
                else if (!url.equals(users.get(name))) users.put(name, null);
            }
        }
        synchronized (messages) {
            messages.remove(messageId);
            if (!users.isEmpty()) messages.put(messageId, users);
            if (messages.size() > 512) messages.remove(messages.keySet().iterator().next());
        }
    }
}
