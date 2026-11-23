package discordx.plugins.no_profile_themes;

import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.util.regex.Pattern;
import org.json.*;
import java.nio.charset.StandardCharsets;

public final class Plugin implements discordx.api.Plugin {
    private static final String API = "https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/";
    private static final Pattern PROFILE = Pattern.compile(API + "users/([1-9][0-9]{16,19})/profile(?:\\?[^#]*)?");
    private static final Pattern HISTORY = Pattern.compile(API + "channels/[0-9]+/messages(?:/[0-9]+)?(?:\\?[^#]*)?");
    private static final String[] COSMETICS = {"theme_colors", "accent_color", "profile_effect",
        "avatar_decoration_data", "display_name_styles", "collectibles", "nameplate", "profile_effect_id", "avatar_decoration"};

    @Override public void start(PluginContext context) {
        context.ui().settings("Profile appearance", page ->
            page.preferenceToggle("Keep my own profile appearance", "keepOwn", false));
        context.register(Hooks.PROFILE_APPEARANCE, owner -> {
            if (!context.preferences().getBoolean("keepOwn", false)) return true;
            String self = context.chat().currentUserId();
            return self != null && owner != null && !owner.isEmpty() && !owner.equals(self);
        });
        context.register(Hooks.GATEWAY_JSON, (connection, event) -> {
            strip(event.opt("d"), "", exempt(context), false, 0);
            return event;
        });
        context.register(Hooks.HTTP_BODY, (url, bytes) -> {
            if (url == null) return bytes;
            java.util.regex.Matcher profile = PROFILE.matcher(url);
            String owner = profile.matches() ? profile.group(1) : "";
            if (owner.isEmpty() && !HISTORY.matcher(url).matches()) return bytes;
            try {
                Object value = new JSONTokener(new String(bytes, StandardCharsets.UTF_8)).nextValue();
                return strip(value, owner, exempt(context), !owner.isEmpty(), 0)
                    ? value.toString().getBytes(StandardCharsets.UTF_8) : bytes;
            } catch (JSONException malformed) { return bytes; }
        });
    }

    private static String exempt(PluginContext context) {
        return context.preferences().getBoolean("keepOwn", false) ? context.chat().currentUserId() : null;
    }

    private static boolean strip(Object value, String inherited, String exempt, boolean profile, int depth) {
        if (depth > 64) return false;
        boolean changed = false;
        if (value instanceof JSONArray) {
            JSONArray rows = (JSONArray)value;
            for (int i = 0; i < rows.length(); i++) changed |= strip(rows.opt(i), inherited, exempt, profile, depth + 1);
        } else if (value instanceof JSONObject) {
            JSONObject node = (JSONObject)value;
            String owner = node.optString("user_id", node.optString("id", inherited));
            JSONObject user = node.optJSONObject("user");
            if (user != null) owner = user.optString("id", owner);
            boolean personalColor = !node.isNull("theme_colors") || !node.isNull("accent_color") || !node.isNull("banner");
            if (!owner.equals(exempt)) for (String key : COSMETICS) {
                if (!node.isNull(key)) { node.remove(key); changed = true; }
            }
            if (!owner.equals(exempt) && (profile || node.has("username") || node.has("user") && node.has("roles"))) {
                for (String key : new String[] {"banner", "banner_color", "banner_color_primary", "banner_color_secondary"}) {
                    if (!personalColor && key.startsWith("banner_color")) continue;
                    if (!node.isNull(key)) { node.remove(key); changed = true; }
                }
            }
            for (java.util.Iterator<String> keys = node.keys(); keys.hasNext();) {
                String key = keys.next();
                boolean childProfile = key.equals("user_profile") || key.equals("guild_member_profile") || key.equals("member")
                    || key.equals("guild_member") || key.equals("user") || key.equals("author");
                changed |= strip(node.opt(key), owner, exempt, childProfile, depth + 1);
            }
        }
        return changed;
    }
}
