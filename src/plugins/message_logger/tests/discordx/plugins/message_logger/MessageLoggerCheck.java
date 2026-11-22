package discordx.plugins.message_logger;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import org.json.JSONObject;

/** Run on the JVM with org.json to verify transport-to-history behavior. */
public final class MessageLoggerCheck {
    private static final String CHANNEL = "100000000000000001";
    private static final String MESSAGE = "100000000000000002";

    public static void main(String[] args) throws Exception {
        Object connection = new Object();
        MessageLogger.usePrefix("DXP0123456789abcdef");
        MessageLogger.setEnabled(true);
        MessageLogger.socketOpened(connection, 0, "wss://gateway.discord.gg/?v=10&encoding=json");

        byte[] create = event("MESSAGE_CREATE", message("before"));
        check(MessageLogger.onFrame(connection, 0, create) == create, "create must pass through");
        JSONObject edit = receive(connection, "MESSAGE_UPDATE", message("after"));
        MessageLogger.Decoration history = history(edit);
        check(history.versions.size() == 1 && history.versions.get(0).equals("before"), "edit must retain observed text");
        check(!history.deleted, "edit must not be marked deleted");
        JSONObject deletion = receive(connection, "MESSAGE_DELETE", message(null));
        check(deletion.getString("t").equals("MESSAGE_UPDATE"), "known deletion must become a local update");
        check(history(deletion).deleted, "retained deletion must have a decoration");
        check(MessageLogger.stripMarkers(deletion.getJSONObject("d").getString("content")).equals("after"), "clipboard text must not contain marker");
        JSONObject retained = deletion.getJSONObject("d");
        check(retained.has("author") && retained.getJSONObject("author").getString("id").equals("100000000000000003"), "deletion must preserve the cached author");
        check(retained.has("timestamp"), "deletion must preserve the original timeline timestamp");
        check(retained.getJSONArray("attachments").length() == 1, "deletion must preserve attachments");

        MessageLogger.clear();
        // These snapshots normally arrive through the host's HTTP_RESPONSE hook.
        MessageLogger.remember(message("loaded from HTTP"), CHANNEL);
        JSONObject loadedEdit = receive(connection, "MESSAGE_UPDATE", message("edited after load"));
        check(history(loadedEdit).versions.get(0).equals("loaded from HTTP"), "loaded message must seed edit history");
        MessageLogger.deleting(MESSAGE, CHANNEL);
        JSONObject ownDeletion = receive(connection, "MESSAGE_DELETE", message(null));
        check(ownDeletion.getString("t").equals("MESSAGE_CREATE"), "local deletion must reinsert a message already removed optimistically");
        check(history(ownDeletion).deleted, "reinserted message must be visibly marked deleted");
        JSONObject restored = ownDeletion.getJSONObject("d");
        check(MessageLogger.stripMarkers(restored.getString("content")).equals("edited after load"), "local restore must keep the latest text");
        check(restored.getJSONObject("author").getString("id").equals("100000000000000003"), "local restore must retain its author");
        check(restored.getString("timestamp").equals("2026-11-22T00:00:00.000Z"), "local restore must keep original ordering");
        check(restored.getJSONArray("attachments").length() == 1 && restored.has("embeds"), "local restore must retain rich message fields");
        check(history(ownDeletion).versions.get(0).equals("loaded from HTTP"), "deletion must retain earlier edits");
        JSONObject repeat = receive(connection, "MESSAGE_DELETE", message(null));
        check(repeat.getString("t").equals("MESSAGE_UPDATE") && history(repeat).deleted, "repeat deletion must retain its cached state without a second insertion");

        MessageLogger.clear();
        JSONObject unknown = message(null).put("id", "100000000000000099");
        MessageLogger.deleting(unknown.getString("id"), CHANNEL);
        byte[] unknownDelete = event("MESSAGE_DELETE", unknown);
        check(MessageLogger.onFrame(connection, 0, unknownDelete) == unknownDelete, "unseen deletion must pass through without inventing a message");

        MessageLogger.remember(message("partial update"), CHANNEL);
        JSONObject metadata = message(null).put("attachments", new org.json.JSONArray().put(new JSONObject().put("id", "100000000000000005").put("filename", "new.txt")));
        receive(connection, "MESSAGE_UPDATE", metadata);
        MessageLogger.deleting(MESSAGE, CHANNEL);
        JSONObject metadataDeletion = receive(connection, "MESSAGE_DELETE", message(null));
        check(metadataDeletion.getJSONObject("d").getJSONArray("attachments").getJSONObject(0).getString("filename").equals("new.txt"), "content-free edits must update the restorable snapshot");

        MessageLogger.clear();
        String padding = repeat('x', 60000);
        for (int i = 0; i < 80; i++) {
            JSONObject value = message("bounded").put("id", Long.toString(100000000000000100L + i)).put("test_padding", padding);
            MessageLogger.remember(value, CHANNEL);
        }
        byte[] evicted = event("MESSAGE_DELETE", message(null).put("id", "100000000000000100"));
        check(MessageLogger.onFrame(connection, 0, evicted) == evicted, "byte budget must evict old snapshots before the count limit");
        JSONObject recent = receive(connection, "MESSAGE_DELETE", message(null).put("id", "100000000000000179"));
        check(history(recent).deleted, "byte eviction must keep recent snapshots");
        MessageLogger.remember(message("oversized").put("test_padding", repeat('x', 66000)), CHANNEL);
        byte[] oversized = event("MESSAGE_DELETE", message(null));
        check(MessageLogger.onFrame(connection, 0, oversized) == oversized, "oversized snapshots must not be retained");

        MessageLogger.setEnabled(false);
        byte[] disabled = event("MESSAGE_UPDATE", message("disabled"));
        check(MessageLogger.onFrame(connection, 0, disabled) == disabled, "disabled logger must pass through");
        MessageLogger.setEnabled(true);
        check(MessageLogger.onFrame(connection, 0, disabled) == disabled, "re-enabling must not resurrect old snapshots");
        MessageLogger.socketClosed(connection, 0);
        byte[] closed = event("MESSAGE_CREATE", message("closed"));
        check(MessageLogger.onFrame(connection, 0, closed) == closed, "closed connection must not be observed");
        System.out.println("MessageLogger: edits, local/remote deletions, complete snapshots, cache bounds, markers, and lifecycle passed.");
    }

    private static JSONObject message(String content) throws Exception {
        JSONObject value = new JSONObject().put("id", MESSAGE).put("channel_id", CHANNEL);
        if (content != null) {
            value.put("author", new JSONObject().put("id", "100000000000000003").put("username", "test-author"));
            value.put("timestamp", "2026-11-22T00:00:00.000Z");
            value.put("attachments", new org.json.JSONArray().put(new JSONObject().put("id", "100000000000000004").put("filename", "test.txt")));
            value.put("embeds", new org.json.JSONArray());
        }
        if (content != null) value.put("content", content);
        return value;
    }

    private static byte[] event(String type, JSONObject message) throws Exception {
        return new JSONObject().put("op", 0).put("t", type).put("d", message).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JSONObject receive(Object connection, String type, JSONObject message) throws Exception {
        return new JSONObject(new String(MessageLogger.onFrame(connection, 0, event(type, message)), StandardCharsets.UTF_8));
    }

    private static MessageLogger.Decoration history(JSONObject event) throws Exception {
        Matcher marker = MessageLogger.marker(event.getJSONObject("d").getString("content"));
        check(marker.find(), "changed event must contain a display marker");
        MessageLogger.Decoration result = MessageLogger.decoration(marker.group());
        check(result != null, "display marker must resolve to history");
        return result;
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
