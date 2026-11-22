package discordx;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Session-only history for message events delivered to this Phoenix session. */
public final class MessageLogger {
    private static final int CACHE_LIMIT = 512;
    private static final int HISTORY_LIMIT = 128;
    private static final int FRAME_LIMIT = 128 * 1024;
    private static final Map<Object, Set<Integer>> sockets = new WeakHashMap<>();
    private static final LinkedHashMap<String, Message> messages = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Decoration> decorations = new LinkedHashMap<>();
    private static final String TOKEN_PREFIX = "DXH" + UUID.randomUUID().toString().substring(0, 8);
    private static final Pattern MARKER = Pattern.compile("[ \\u2063]*" + TOKEN_PREFIX + "[0-9a-f]{8}\\u2063?");
    private static final int EPHEMERAL = 1 << 6;
    private static final Pattern GATEWAY_HOST = Pattern.compile("gateway(?:-[a-z0-9-]+)?\\.discord\\.gg",
        Pattern.CASE_INSENSITIVE);
    private static int nextToken;
    private static volatile boolean issuedMarkers;
    private static boolean enabled;

    private MessageLogger() {}

    public static synchronized void socketOpened(Object owner, int socket, String url) {
        socketClosed(owner, socket);
        if (url == null) return;
        try {
            URI parsed = URI.create(url);
            if ("wss".equalsIgnoreCase(parsed.getScheme()) && parsed.getHost() != null
                    && GATEWAY_HOST.matcher(parsed.getHost()).matches()
                    && parsed.getRawUserInfo() == null) {
                sockets.computeIfAbsent(owner, ignored -> new HashSet<>()).add(socket);
            }
        } catch (IllegalArgumentException ignored) {
            // The existing Discord connection owns validation and error handling.
        }
    }

    public static synchronized void socketClosed(Object owner, int socket) {
        Set<Integer> registered = sockets.get(owner);
        if (registered == null) return;
        registered.remove(socket);
        if (registered.isEmpty()) sockets.remove(owner);
    }

    static synchronized boolean tracking(Object owner, int socket) {
        Set<Integer> registered = sockets.get(owner);
        return enabled && registered != null && registered.contains(socket);
    }

    public static synchronized byte[] onFrame(Object owner, int socket, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 2 * 1024 * 1024) return bytes;
        if (!enabled) return bytes;
        Set<Integer> registered = sockets.get(owner);
        if (registered == null || !registered.contains(socket)) return bytes;
        if (Byte.toUnsignedInt(bytes[0]) == GatewayTerms.VERSION) return GatewayTerms.transform(owner, socket, bytes);
        if (bytes[0] != '{') return bytes;
        String frame = new String(bytes, StandardCharsets.UTF_8);
        if (!frame.contains("READY") && (bytes.length > FRAME_LIMIT || !frame.contains("MESSAGE_"))) return bytes;
        try {
            JSONObject payload = new JSONObject(frame);
            if (payload.optInt("op", -1) != 0) return bytes;
            JSONObject data = payload.optJSONObject("d");
            if (data == null) return bytes;
            String type = payload.optString("t");
            if ("READY".equals(type)) {
                clear();
                return bytes;
            }
            if (bytes.length > FRAME_LIMIT) return bytes;
            String channel = data.optString("channel_id");
            if (!snowflake(channel)) return bytes;
            String token;
            switch (type) {
                case "MESSAGE_CREATE":
                    remember(data, channel);
                    break;
                case "MESSAGE_UPDATE":
                    token = update(data, channel);
                    if (token != null) return withMarker(payload, data, data.getString("content"), token);
                    break;
                case "MESSAGE_DELETE":
                    byte[] retained = retainDeletion(payload, data.optString("id"), channel);
                    if (retained != null) return retained;
                    break;
                case "MESSAGE_DELETE_BULK":
                    JSONArray ids = data.optJSONArray("ids");
                    if (ids != null) {
                        if (ids.length() == 1) {
                            retained = retainDeletion(payload, ids.optString(0), channel);
                            if (retained != null) return retained;
                        }
                        for (int i = 0; i < ids.length(); i++) {
                            Message message = messages.get(ids.optString(i));
                            if (message != null && message.channel.equals(channel)) messages.remove(ids.optString(i));
                        }
                    }
                    break;
                default:
                    break;
            }
        } catch (JSONException ignored) {
            // Other frame types continue through the original Discord handler.
        }
        return bytes;
    }

    static synchronized void setEnabled(boolean value) {
        enabled = value;
        if (!value) clear();
    }

    static synchronized void clear() {
        messages.clear();
        decorations.clear();
    }

    static synchronized void remember(JSONObject data, String channel) {
        if (!enabled || (data.optInt("flags", 0) & EPHEMERAL) != 0) return;
        String id = data.optString("id");
        String content = content(data);
        if (!snowflake(id) || content == null || content.isEmpty()) return;
        Message existing = messages.get(id);
        if (existing != null && existing.channel.equals(channel)) return;
        messages.put(id, new Message(channel, data.optString("guild_id"), content));
        if (messages.size() > CACHE_LIMIT) messages.remove(messages.keySet().iterator().next());
    }

    private static String update(JSONObject data, String channel) {
        String id = data.optString("id");
        String after = content(data);
        Message before = messages.get(id);
        if (before == null || !before.channel.equals(channel) || after == null) return null;
        if (after.equals(before.text)) return before.token;
        before.versions.addFirst(before.text);
        if (before.versions.size() > 4) before.versions.removeLast();
        before.text = after;
        before.token = decorate(before.versions, false);
        return before.token;
    }

    private static byte[] retainDeletion(JSONObject payload, String id, String channel) throws JSONException {
        Message previous = messages.get(id);
        if (previous == null || !previous.channel.equals(channel)) return null;
        JSONObject update = new JSONObject().put("id", id).put("channel_id", channel);
        if (snowflake(previous.guild)) update.put("guild_id", previous.guild);
        String token = decorate(previous.versions, true);
        payload.put("t", "MESSAGE_UPDATE").put("d", update);
        byte[] result = withMarker(payload, update, previous.text, token);
        messages.remove(id);
        return result;
    }

    private static String decorate(ArrayDeque<String> versions, boolean deleted) {
        String token = TOKEN_PREFIX + String.format(java.util.Locale.ROOT, "%08x", ++nextToken);
        issuedMarkers = true;
        decorations.put(token, new Decoration(new ArrayList<>(versions), deleted));
        if (decorations.size() > HISTORY_LIMIT) decorations.remove(decorations.keySet().iterator().next());
        return token;
    }

    private static byte[] withMarker(JSONObject payload, JSONObject message, String content, String token) throws JSONException {
        message.put("content", stripMarkers(content) + " \u2063" + token + "\u2063");
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Matcher marker(CharSequence text) {
        return MARKER.matcher(text);
    }

    static boolean issuedMarkers() {
        return issuedMarkers;
    }

    static synchronized Decoration decoration(String marker) {
        int start = marker.indexOf(TOKEN_PREFIX);
        return start < 0 ? null : decorations.get(marker.substring(start, start + TOKEN_PREFIX.length() + 8));
    }

    static String stripMarkers(String text) {
        return text.contains(TOKEN_PREFIX) ? MARKER.matcher(text).replaceAll("") : text;
    }

    private static String content(JSONObject data) {
        Object value = data.opt("content");
        if (!(value instanceof String)) return null;
        String text = (String) value;
        return text.length() <= 4_096 ? stripMarkers(text) : null;
    }

    static boolean snowflake(String value) {
        if (value.length() < 17 || value.length() > 20 || value.charAt(0) == '0') return false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        return true;
    }

    static final class Decoration {
        final List<String> versions;
        final boolean deleted;

        Decoration(List<String> versions, boolean deleted) {
            this.versions = versions;
            this.deleted = deleted;
        }
    }

    private static final class Message {
        final String channel, guild;
        final ArrayDeque<String> versions = new ArrayDeque<>();
        String text;
        String token;

        Message(String channel, String guild, String text) {
            this.channel = channel;
            this.guild = guild;
            this.text = text;
        }
    }
}
