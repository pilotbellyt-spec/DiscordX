package discordx.plugins.quick_jump;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class QuickJumpCatalog {
    private static final Pattern INDEX = Pattern.compile(
        "https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/(?:guilds/[0-9]+|users/@me|channels/[0-9]+)/application-command-index(?:\\?[^#]*)?");
    private static final String APP = "4194305", COMMAND = "4194306";

    private QuickJumpCatalog() {}

    static byte[] append(String url, byte[] bytes) {
        if (!INDEX.matcher(url).matches()) return bytes;
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray apps = root.getJSONArray("applications");
            JSONArray commands = root.getJSONArray("application_commands");
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                if (APP.equals(app.optString("id")) || "DiscordX Local".equals(app.optString("name"))) return bytes;
            }
            for (int i = 0; i < commands.length(); i++) {
                JSONObject existing = commands.getJSONObject(i);
                if (COMMAND.equals(existing.optString("id")) || "quick-jump".equals(existing.optString("name"))) return bytes;
            }
            apps.put(new JSONObject().put("id", APP).put("name", "DiscordX Local")
                .put("description", "Local DiscordX commands").put("icon", JSONObject.NULL).put("bot_id", APP));
            JSONObject link = new JSONObject().put("type", 3).put("name", "link")
                .put("description", "Discord channel, message, or profile link").put("required", true);
            commands.put(new JSONObject().put("id", COMMAND).put("application_id", APP)
                .put("version", "4194307").put("type", 1).put("name", "quick-jump")
                .put("description", "Open a Discord link").put("options", new JSONArray().put(link)));
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException unsupported) {
            return bytes;
        }
    }
}
