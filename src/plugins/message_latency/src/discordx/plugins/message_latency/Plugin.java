package discordx.plugins.message_latency;

import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.util.regex.Pattern;
import org.json.*;
import java.nio.charset.StandardCharsets;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import java.util.regex.Matcher;
import java.util.Locale;

public final class Plugin implements discordx.api.Plugin {
    private static final String API = "https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/";
    private static final Pattern MESSAGES = Pattern.compile(API + "channels/[1-9][0-9]{16,19}/messages(?:/[1-9][0-9]{16,19})?(?:\\?[^#]*)?");
    private String prefix;
    private Pattern marker;
    @Override public void start(PluginContext context) {
        prefix = context.textTokenPrefix();
        marker = Pattern.compile("\u2063" + Pattern.quote(prefix) + "([0-9a-f]{8})\u2063");
        context.register(Hooks.DISPLAY_TEXT, this::render);

        context.register(Hooks.GATEWAY_JSON, (connection, event) -> {
            JSONObject data = event.optJSONObject("d");
            String type = event.optString("t");
            if (data != null && ("MESSAGE_CREATE".equals(type) || "MESSAGE_UPDATE".equals(type))) {
                try { mark(data); } catch (JSONException ignored) { }
            }
            return event;
        });
        context.register(Hooks.HTTP_BODY, (url, bytes) -> {
            if (url == null || !MESSAGES.matcher(url).matches()) return bytes;
            try {
                Object response = new JSONTokener(new String(bytes, StandardCharsets.UTF_8)).nextValue();
                boolean changed = false;
                if (response instanceof JSONObject) {
                    JSONObject data = (JSONObject) response;
                    changed = mark(data);
                } else if (response instanceof JSONArray) {
                    JSONArray messages = (JSONArray) response;
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject data = messages.optJSONObject(i);
                        if (data != null) changed |= mark(data);
                    }
                }
                return changed ? response.toString().getBytes(StandardCharsets.UTF_8) : bytes;
            } catch (JSONException malformed) { return bytes; }
        });
    }

    static Long delay(JSONObject message) {
        JSONObject author = message.optJSONObject("author");
        if (author != null && author.optBoolean("bot")) return null;
        String id = message.optString("id"), nonce = message.optString("nonce");
        if (!id.matches("[1-9][0-9]{16,18}") || !nonce.matches("[1-9][0-9]{16,18}")) return null;
        try {
            long delta = (Long.parseLong(id) >>> 22) - (Long.parseLong(nonce) >>> 22);
            return delta >= 2000 && delta < 120000 ? delta : null;
        } catch (NumberFormatException invalid) { return null; }
    }


    private boolean mark(JSONObject message) throws JSONException {
        String content = message.optString("content");
        Long delay = delay(message);
        if (delay == null || (message.optInt("flags") & 64) != 0 || content.isEmpty() || marker.matcher(content).find()) return false;
        message.put("content", content + "\u2063" + prefix + String.format(Locale.ROOT, "%08x", delay) + "\u2063");
        return true;
    }

    private CharSequence render(CharSequence value) {
        if (value == null) return null;
        Matcher tokens = marker.matcher(value);
        if (!tokens.find()) return value;
        SpannableStringBuilder result = new SpannableStringBuilder(value);
        int offset = 0;
        do {
            long delay = Long.parseLong(tokens.group(1), 16);
            String label = String.format(Locale.ROOT, " · send delay %.2f s", delay / 1000.0);
            int start = tokens.start() + offset;
            result.replace(start, tokens.end() + offset, label);
            result.setSpan(new RelativeSizeSpan(0.8f), start, start + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            offset += label.length() - (tokens.end() - tokens.start());
        } while (tokens.find());
        return result;
    }
}
