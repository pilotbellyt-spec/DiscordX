package discordx.api;

import java.util.function.Consumer;
import java.util.function.Function;
import org.json.JSONObject;

public interface Chat {
    enum Button { HEADER, COMPOSER }
    enum Placement { CONTENT, TIMESTAMP }
    Registration button(Button placement, String title, String resourceIcon, Runnable pressed);
    Registration accessory(Placement placement, Function<String, Label> render);
    Registration tint(Function<String, Integer> argbForMessage);
    void redraw(String messageId);
    Registration slashCommand(String name, String description, Consumer<String> execute);
    /** Null cancels submission. A replacement string changes the outgoing draft. */
    Registration beforeSend(Function<String, String> interceptor);

    /** A device-only reply below a specific chat message. Closing it removes the reply. */
    LocalReply localMessage(String channelId, String text);
    LocalReply reply(String messageId, String text);
    interface LocalReply extends Registration { void edit(String text); }

    /** Read-only lookups on a worker thread. Results are independent JSON snapshots. */
    JSONObject cachedMessage(String channelId, String messageId);
    String selectedChannel();
    org.json.JSONArray queryCache(String select, String... arguments);

    final class Label {
        public final String text;
        public final int color;
        public Label(String text, int argb) { this.text = text; this.color = argb; }
    }
}
