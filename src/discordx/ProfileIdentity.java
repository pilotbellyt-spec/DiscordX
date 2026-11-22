package discordx;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

final class ProfileIdentity {
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_.]{2,32}");
    private static final Pattern ROUTE = Pattern.compile(
        "https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/"
            + "(?:users/([1-9][0-9]{16,19})/profile|channels/([1-9][0-9]{16,19})/messages)(?:\\?[^#]*)?");
    private static final Map<String, String> users = new LinkedHashMap<>();


    private ProfileIdentity() {}

    private static synchronized void remember(JSONObject user) {
        if (user == null) return;
        String name = user.optString("username");
        String id = user.optString("id");
        if (!USERNAME.matcher(name).matches() || !Ids.snowflake(id)) return;
        String previous = users.get(name);
        users.put(name, previous == null || previous.equals(id) ? id : "");
        if (users.size() > 768) users.remove(users.keySet().iterator().next());
    }

    static synchronized String forUsername(CharSequence value) {
        if (value == null || value.length() > 32) return null;
        String id = users.get(value.toString());
        return id == null || id.isEmpty() ? null : id;
    }

    static boolean accepts(String url) { return url != null && ROUTE.matcher(url).matches(); }

    static void response(String url, byte[] body) {
        if (url == null || body == null || body.length == 0 || body.length > MAX_RESPONSE) return;
        Matcher route = ROUTE.matcher(url);
        if (!route.matches()) return;
        String profile = route.group(1);
        String channel = route.group(2);
        try {
            String json = new String(body, StandardCharsets.UTF_8);
            if (profile != null) {
                JSONObject user = new JSONObject(json).optJSONObject("user");
                if (user != null && profile.equals(user.optString("id"))) remember(user);
                return;
            }
        } catch (JSONException ignored) {
            // Phoenix still receives its original response.
        }
    }
}
