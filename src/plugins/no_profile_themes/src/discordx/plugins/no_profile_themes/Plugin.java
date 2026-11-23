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
                if (!owner.isEmpty() && value instanceof JSONObject) {
                    String self = exempt(context);
                    if (context.preferences().getBoolean("keepOwn", false) && (self == null || owner.equals(self))) return bytes;
                    return stripProfile((JSONObject)value) ? value.toString().getBytes(StandardCharsets.UTF_8) : bytes;
                }
                return strip(value, owner, exempt(context), false, 0)
                    ? value.toString().getBytes(StandardCharsets.UTF_8) : bytes;
            } catch (JSONException malformed) { return bytes; }
        });
    }

    private static boolean stripProfile(JSONObject profile) throws JSONException {
        JSONObject global = profile.optJSONObject("user_profile");
        JSONObject member = profile.optJSONObject("guild_member_profile");
        boolean changed = clearCosmetics(profile.optJSONObject("user"));
        changed |= clearCosmetics(global);
        changed |= clearCosmetics(member);
        JSONObject membership = profile.optJSONObject("guild_member");
        changed |= clearCosmetics(membership);
        if (membership != null) changed |= clearCosmetics(membership.optJSONObject("user"));
        // The profile response has a presentation tier separate from user and session data.
        // Omitting it selects the standard sheet without supplying any replacement colors.
        if (changed && profile.optInt("premium_type", 0) != 0) profile.remove("premium_type");
        return changed;
    }

    private static boolean clearCosmetics(JSONObject node) throws JSONException {
        if (node == null) return false;
        boolean customBanner = !node.isNull("accent_color") || !node.isNull("banner");
        boolean changed = false;
        for (String key : COSMETICS) {
            if (!node.isNull(key)) { node.put(key, JSONObject.NULL); changed = true; }
        }
        for (String key : new String[]{"banner", "banner_color", "banner_color_primary", "banner_color_secondary"}) {
            if ((customBanner || key.equals("banner")) && !node.isNull(key)) {
                node.put(key, JSONObject.NULL);
                changed = true;
            }
        }
        return changed;
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
