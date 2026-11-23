package discordx.plugins.no_profile_themes;

import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.util.regex.Pattern;
import org.json.*;
import java.nio.charset.StandardCharsets;

public final class Plugin implements discordx.api.Plugin {
    private static final String API = "https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/";
    private static final Pattern PROFILE = Pattern.compile(API + "users/([1-9][0-9]{16,19})/profile(?:\\?[^#]*)?");
    private volatile String self = "";

    @Override public void start(PluginContext context) {
        context.ui().settings("Profile appearance", page -> page.toggle("Keep my own profile appearance",
            context.preferences().getBoolean("keepOwn", false),
            value -> context.preferences().edit().putBoolean("keepOwn", value).apply()));
        context.register(Hooks.GATEWAY_JSON, (connection, event) -> {
            JSONObject data = event.optJSONObject("d");
            if (data == null) return event;
            if ("READY".equals(event.optString("t"))) {
                JSONObject me = data.optJSONObject("user");
                self = me == null ? "" : me.optString("id");
            }
            JSONObject user = data.optJSONObject("author");
            if (user == null) user = data.optJSONObject("user");
            if (user != null && shouldStrip(context, user.optString("id"))) clearTheme(user);
            return event;
        });
        context.register(Hooks.HTTP_BODY, (url, bytes) -> {
            java.util.regex.Matcher match = PROFILE.matcher(url);
            if (!match.matches() || !shouldStrip(context, match.group(1))) return bytes;
            try {
                JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                boolean changed = clearTheme(value);
                for (String field : new String[] {"user_profile", "guild_member_profile", "user", "guild_member"})
                    changed |= clearTheme(value.optJSONObject(field));
                return changed ? value.toString().getBytes(StandardCharsets.UTF_8) : bytes;
            } catch (JSONException malformed) { return bytes; }
        });
    }

    private boolean shouldStrip(PluginContext context, String id) {
        return !context.preferences().getBoolean("keepOwn", false) || !self.isEmpty() && !id.isEmpty() && !self.equals(id);
    }

    private static boolean clearTheme(JSONObject profile) {
        if (profile == null) return false;
        boolean changed = false;
        for (String key : new String[] {"theme_colors", "accent_color", "profile_effect", "avatar_decoration_data", "display_name_styles"}) {
            if (profile.has(key) && !profile.isNull(key)) {
                profile.remove(key);
                changed = true;
            }
        }
        return changed;
    }
}
