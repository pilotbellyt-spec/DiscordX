package discordx.plugins.dearrow;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import discordx.api.PluginContext;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Dearrow {
    private static final Pattern VIDEO = Pattern.compile("(?:^|[?&])v=([A-Za-z0-9_-]{11})(?:&|$)");
    private final PluginContext context;
    private final Map<String, Branding> cache = new LinkedHashMap<>();
    private final Set<String> pending = new HashSet<>();
    Dearrow(PluginContext context) { this.context = context; }

    static String video(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null) return null;
            String host = uri.getHost(), path = uri.getPath(), id = null;
            if ("youtu.be".equals(host)) id = path.length() > 1 ? path.substring(1) : null;
            else if ("youtube.com".equals(host) || "www.youtube.com".equals(host) || "m.youtube.com".equals(host)) {
                if (path.startsWith("/embed/") || path.startsWith("/shorts/")) id = path.substring(path.lastIndexOf('/') + 1);
                else if ("/watch".equals(path) && uri.getRawQuery() != null) {
                    Matcher query = VIDEO.matcher(uri.getRawQuery());
                    if (query.find()) id = query.group(1);
                }
            }
            return id != null && id.matches("[A-Za-z0-9_-]{11}") ? id : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    boolean embeds(JSONArray embeds, int[] lookups) throws JSONException {
        if (embeds == null) return false;
        boolean changed = false;
        for (int i = 0; i < embeds.length(); i++) {
            JSONObject embed = embeds.optJSONObject(i);
            if (embed == null) continue;
            String id = video(embed.optString("url"));
            JSONObject player = embed.optJSONObject("video");
            if (id == null && player != null) id = video(player.optString("url"));
            if (id == null) continue;
            Branding result = cached(id);
            if (result == null) {
                if (lookups[0] > 0) { lookups[0]--; result = fetch(id); }
                else queue(id);
            }
            if (result == null) continue;
            if (result.title != null && !result.title.equals(embed.optString("title"))) {
                embed.put("title", result.title);
                changed = true;
            }
            JSONObject thumbnail = embed.optJSONObject("thumbnail");
            if (result.thumbnail != null && thumbnail != null && !result.thumbnail.equals(thumbnail.optString("url"))) {
                thumbnail.put("url", result.thumbnail);
                thumbnail.put("proxy_url", result.thumbnail);
                changed = true;
            }
        }
        return changed;
    }

    private synchronized Branding cached(String id) {
        Branding value = cache.get(id);
        return value != null && System.currentTimeMillis() - value.saved < 30 * 60 * 1000 ? value : null;
    }

    private synchronized void queue(String id) {
        if (pending.size() >= 8 || !pending.add(id)) return;
        try {
            context.runAsync(() -> fetch(id), value -> finished(id), error -> finished(id));
        } catch (RejectedExecutionException | IllegalStateException unavailable) {
            pending.remove(id);
        }
    }

    private synchronized void finished(String id) { pending.remove(id); }

    private Branding fetch(String id) {
        Branding value;
        try { value = parse(id, new JSONObject(PluginHttp.get("https://sponsor.ajay.app/api/branding?videoID=" + id))); }
        catch (Exception unavailable) { value = new Branding(null, null); }
        synchronized (this) {
            cache.put(id, value);
            if (cache.size() > 128) cache.remove(cache.keySet().iterator().next());
        }
        return value;
    }

    static Branding parse(String id, JSONObject response) {
        String title = null, thumbnail = null;
        JSONArray titles = response.optJSONArray("titles"), thumbnails = response.optJSONArray("thumbnails");
        JSONObject first = titles == null ? null : titles.optJSONObject(0);
        if (first != null && first.optInt("votes", -1) >= 0) {
            String text = first.optString("title").trim();
            if (!text.isEmpty() && text.length() <= 512) title = text;
        }
        first = thumbnails == null ? null : thumbnails.optJSONObject(0);
        if (first != null && first.optInt("votes", -1) >= 0 && !first.optBoolean("original", true)) {
            double time = first.optDouble("timestamp", -1);
            if (time >= 0 && Double.isFinite(time) && time < 864000) {
                thumbnail = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=" + id + "&time=" + time;
            }
        }
        return new Branding(title, thumbnail);
    }

    static final class Branding {
        final String title, thumbnail;
        final long saved = System.currentTimeMillis();
        Branding(String title, String thumbnail) { this.title = title; this.thumbnail = thumbnail; }
    }
}
