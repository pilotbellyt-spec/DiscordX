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
    private static final Map<String, Boolean> palettes = new LinkedHashMap<>();

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


    static synchronized void resetAccount() { palettes.clear(); users.clear(); }
    static synchronized Boolean customPalette(String user, String guild) {
        if (user == null || user.isEmpty()) return null;
        Boolean global = palettes.get(user);
        if (Boolean.TRUE.equals(global)) return true;
        if (guild != null) {
            Boolean scoped = palettes.get(user + "/" + guild);
            if (scoped != null) return scoped;
        }
        // An unidentified guild must never cause an observed server override to be recolored.
        for (Map.Entry<String,Boolean> entry : palettes.entrySet())
            if (entry.getValue() && entry.getKey().startsWith(user + "/")) return true;
        return global;
    }

    private static synchronized void record(String key, Boolean custom) {
        if (custom == null) return;
        palettes.put(key, custom);
        while (palettes.size() > 768) palettes.remove(palettes.keySet().iterator().next());
    }

    private static Boolean selectedPalette(JSONObject payload) {
        JSONObject global = payload.optJSONObject("user_profile");
        JSONObject member = payload.optJSONObject("guild_member_profile");
        if (global == null && member == null) return null;
        return hasColors(global) || hasColors(member) || hasColors(payload);
    }

    private static boolean hasColors(JSONObject profile) {
        if (profile == null || profile.isNull("theme_colors")) return false;
        org.json.JSONArray colors = profile.optJSONArray("theme_colors");
        return colors == null || colors.length() > 0;
    }

    static void response(String url, byte[] body) {
        if (url == null || body == null || body.length == 0 || body.length > MAX_RESPONSE) return;
        Matcher route = ROUTE.matcher(url);
        if (!route.matches()) return;
        String profile = route.group(1);
        String channel = route.group(2);
        try {
            String json = new String(body, StandardCharsets.UTF_8);
            if (profile != null) {
                JSONObject raw = new JSONObject(json);
                JSONObject global = raw.optJSONObject("user_profile");
                if (global != null) record(profile, hasColors(global) || hasColors(raw));
                String guild = android.net.Uri.parse(url).getQueryParameter("guild_id");
                if (Ids.snowflake(guild)) record(profile + "/" + guild, selectedPalette(raw));
                JSONObject user = raw.optJSONObject("user");
                if (user != null && profile.equals(user.optString("id"))) remember(user);
                return;
            }
        } catch (JSONException ignored) {
            // Phoenix still receives its original response.
        }
    }
}
