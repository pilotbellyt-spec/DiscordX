package discordx.plugins.dearrow;

import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.util.regex.Pattern;
import org.json.*;
import java.nio.charset.StandardCharsets;

public final class Plugin implements discordx.api.Plugin {
    private static final String API = "https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/";
    private static final Pattern MESSAGES = Pattern.compile(API + "channels/[1-9][0-9]{16,19}/messages(?:/[1-9][0-9]{16,19})?(?:\\?[^#]*)?");
    private static int[] lookupBudget() {
        return new int[] {android.os.Looper.myLooper() == android.os.Looper.getMainLooper() ? 0 : 1};
    }

    @Override public void start(PluginContext context) {
        Dearrow branding = new Dearrow(context);

        context.register(Hooks.GATEWAY_JSON, (connection, event) -> {
            int[] budget = lookupBudget();
            JSONObject data = event.optJSONObject("d");
            String type = event.optString("t");
            if (data != null && ("MESSAGE_CREATE".equals(type) || "MESSAGE_UPDATE".equals(type))) {
                try { branding.embeds(data.optJSONArray("embeds"), budget); } catch (JSONException ignored) { }
            }
            return event;
        });
        context.register(Hooks.HTTP_BODY, (url, bytes) -> {
            if (url == null || !MESSAGES.matcher(url).matches()) return bytes;
            try {
                int[] budget = lookupBudget();
                Object response = new JSONTokener(new String(bytes, StandardCharsets.UTF_8)).nextValue();
                boolean changed = false;
                if (response instanceof JSONObject) {
                    JSONObject data = (JSONObject) response;
                    changed = branding.embeds(data.optJSONArray("embeds"), budget);
                } else if (response instanceof JSONArray) {
                    JSONArray messages = (JSONArray) response;
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject data = messages.optJSONObject(i);
                        if (data != null) changed |= branding.embeds(data.optJSONArray("embeds"), budget);
                    }
                }
                return changed ? response.toString().getBytes(StandardCharsets.UTF_8) : bytes;
            } catch (JSONException malformed) { return bytes; }
        });
    }
}
