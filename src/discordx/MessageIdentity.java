package discordx;

import discordx.api.PluginUi;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class MessageIdentity {
    private static final Pattern ROUTE = Pattern.compile("https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/channels/([1-9][0-9]{16,19})/messages(?:/[1-9][0-9]{16,19})?(?:\\?[^#]*)?");
    private static final Map<String, PluginUi.Message> messages = new LinkedHashMap<>();
    private MessageIdentity() {}
    static synchronized void clear() { messages.clear(); }

    static synchronized boolean observe(JSONObject message) {
        String id = message.optString("id"), channel = message.optString("channel_id"), content = message.optString("content");
        if (!Ids.snowflake(id) || !Ids.snowflake(channel) || content.isEmpty() || content.length() > 16000
                || (message.optInt("flags") & (1 << 6)) != 0) return false;
        JSONObject author = message.optJSONObject("author");
        content = discordx.api.TextTokens.clean(content).toString();
        String token = "\u2063DXH" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "\u2063";
        messages.put(token, new PluginUi.Message(id, channel, author == null ? "" : author.optString("id"), content, content));
        if (messages.size() > 512) messages.remove(messages.keySet().iterator().next());
        try { message.put("content", content + token); return true; }
        catch (org.json.JSONException impossible) { throw new IllegalArgumentException(impossible); }
    }

    static synchronized CharSequence decorate(CharSequence raw, CharSequence clean) {
        if (raw == null || clean == null) return clean;
        java.util.regex.Matcher tokens = java.util.regex.Pattern.compile("\\u2063DXH[0-9a-f]{16}\\u2063").matcher(raw);
        PluginUi.Message message = null;
        while (tokens.find()) {
            PluginUi.Message found = messages.get(tokens.group());
            if (found == null || message != null) return clean;
            message = found;
        }
        if (message == null) return clean;
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(clean);
        text.setSpan(new Identity(message), 0, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    static PluginUi.Message find(CharSequence text) {
        if (!(text instanceof android.text.Spanned)) return null;
        Identity[] identities = ((android.text.Spanned) text).getSpans(0, text.length(), Identity.class);
        return identities.length == 1 ? identities[0].message : null;
    }

    private static final class Identity {
        final PluginUi.Message message;
        Identity(PluginUi.Message message) { this.message = message; }
    }

    static void frame(JSONObject root) {
        if (root.optInt("op", -1) != 0) return;
        String type = root.optString("t");
        JSONObject message = root.optJSONObject("d");
        if ("READY".equals(type)) clear();
        else if (message != null && ("MESSAGE_CREATE".equals(type) || "MESSAGE_UPDATE".equals(type))) observe(message);
        else if (message != null && "MESSAGE_DELETE".equals(type)) {
            synchronized (MessageIdentity.class) { messages.values().removeIf(value -> value.id.equals(message.optString("id"))); }
        }
    }

    static byte[] response(String url, byte[] bytes) {
        Matcher route = ROUTE.matcher(url);
        if (!route.matches()) return bytes;
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            boolean changed = false;
            Object root;
            if (json.startsWith("[")) {
                JSONArray list = new JSONArray(json);
                root = list;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject message = list.optJSONObject(i);
                    if (message != null && route.group(1).equals(message.optString("channel_id"))) changed |= observe(message);
                }
            } else {
                JSONObject message = new JSONObject(json);
                root = message;
                if (route.group(1).equals(message.optString("channel_id"))) changed |= observe(message);
            }
            if (changed) return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException malformed) { /* An unknown response must not become a message context. */ }
        return bytes;
    }
}
