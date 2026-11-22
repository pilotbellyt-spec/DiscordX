package discordx;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ProfileIdentity {
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_.]{2,32}");
    private static final Pattern ROUTE = Pattern.compile(
        "https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/"
            + "(?:users/([1-9][0-9]{16,19})/profile|channels/([1-9][0-9]{16,19})/messages)(?:\\?[^#]*)?");
    private static final Map<String, String> users = new LinkedHashMap<>();
    private static volatile boolean enabled;

    private ProfileIdentity() {}

    static synchronized void setEnabled(boolean value) {
        enabled = value;
        if (!value) users.clear();
    }

    private static synchronized void remember(JSONObject user) {
        if (!enabled || user == null) return;
        String name = user.optString("username");
        String id = user.optString("id");
        if (!USERNAME.matcher(name).matches() || !MessageLogger.snowflake(id)) return;
        String previous = users.get(name);
        users.put(name, previous == null || previous.equals(id) ? id : "");
        if (users.size() > 768) users.remove(users.keySet().iterator().next());
    }

    static synchronized String forUsername(CharSequence value) {
        if (!enabled || value == null || value.length() > 32) return null;
        String id = users.get(value.toString());
        return id == null || id.isEmpty() ? null : id;
    }

    static void response(String url, byte[] body) {
        if (url == null || body == null || body.length == 0 || body.length > MAX_RESPONSE) return;
        Matcher route = ROUTE.matcher(url);
        if (!route.matches()) return;
        String profile = route.group(1);
        String channel = route.group(2);
        try {
            String json = new String(body, StandardCharsets.UTF_8);
            if (profile != null && enabled) {
                JSONObject user = new JSONObject(json).optJSONObject("user");
                if (user != null && profile.equals(user.optString("id"))) remember(user);
                return;
            }
            if (channel == null) return;
            JSONArray messages = new JSONArray(json);
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null || !channel.equals(message.optString("channel_id"))) continue;
                MessageLogger.remember(message, channel);
            }
        } catch (JSONException ignored) {
            // Phoenix still receives its original response.
        }
    }
}
