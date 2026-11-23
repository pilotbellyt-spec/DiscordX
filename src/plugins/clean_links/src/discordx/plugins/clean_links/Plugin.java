package discordx.plugins.clean_links;

import android.net.Uri;
import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.json.JSONException;

public final class Plugin implements discordx.api.Plugin {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\x60\\[\\]]+", Pattern.CASE_INSENSITIVE);

    @Override public void start(PluginContext context) {
        context.register(Hooks.OPEN_LINK, uri -> uri == null ? null : Uri.parse(CleanLinks.strip(uri.toString())));
        context.chat().beforeSend(text -> text == null ? null : clean(text));
        context.register(Hooks.GATEWAY_JSON, (connection, event) -> {
            String type = event.optString("t");
            JSONObject message = event.optJSONObject("d");
            if (message != null && ("MESSAGE_CREATE".equals(type) || "MESSAGE_UPDATE".equals(type))) {
                String content = message.optString("content");
                String cleaned = clean(content);
                if (!content.equals(cleaned)) {
                    try { message.put("content", cleaned); } catch (JSONException ignored) {}
                }
            }
            return event;
        });
    }

    private static String clean(String text) {
        Matcher urls = URL.matcher(text);
        StringBuffer output = new StringBuffer();
        while (urls.find()) {
            String match = urls.group();
            int end = match.length();
            while (end > 0 && ").,;!?".indexOf(match.charAt(end - 1)) >= 0) end--;
            String url = match.substring(0, end);
            String cleaned = CleanLinks.strip(url);
            if (!url.equals(cleaned)) urls.appendReplacement(output, Matcher.quoteReplacement(cleaned + match.substring(end)));
        }
        return urls.appendTail(output).toString();
    }
}
