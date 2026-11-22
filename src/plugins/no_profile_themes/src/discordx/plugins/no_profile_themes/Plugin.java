package discordx.plugins.no_profile_themes;

import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.util.regex.Pattern;
import org.json.*;
import java.nio.charset.StandardCharsets;

public final class Plugin implements discordx.api.Plugin {
    private static final String API = "https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/";
    private static final Pattern PROFILE = Pattern.compile(API + "users/([1-9][0-9]{16,19})/profile(?:\\?[^#]*)?");
    @Override public void start(PluginContext context) {
        context.register(Hooks.HTTP_BODY, (url, bytes) -> {
            if (url == null || !PROFILE.matcher(url).matches()) return bytes;
            try {
                JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                boolean changed = clearTheme(value.optJSONObject("user_profile"));
                changed |= clearTheme(value.optJSONObject("guild_member_profile"));
                return changed ? value.toString().getBytes(StandardCharsets.UTF_8) : bytes;
            } catch (JSONException malformed) { return bytes; }
        });
    }

    private static boolean clearTheme(JSONObject profile) throws JSONException {
        if (profile == null) return false;
        boolean changed = false;
        for (String key : new String[] {"theme_colors", "accent_color"}) {
            if (profile.has(key) && !profile.isNull(key)) {
                profile.put(key, JSONObject.NULL);
                changed = true;
            }
        }
        return changed;
    }
}
