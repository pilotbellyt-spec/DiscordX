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
    /** Registers a local command with named input fields. A parser signals invalid input with IllegalArgumentException. */
    Registration slashCommand(String name, String description, java.util.List<Option<?>> options,
                              Consumer<Arguments> execute);

    final class Option<T> {
        public final String name;
        public final boolean required;
        public final Function<String,T> parse;
        public Option(String name, boolean required, Function<String,T> parse) {
            if (name == null || !name.matches("[a-z][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("Invalid option name");
            this.name = name;
            this.required = required;
            this.parse = java.util.Objects.requireNonNull(parse);
        }
    }
    final class Arguments {
        private final java.util.Map<Option<?>,Object> values = new java.util.HashMap<>();
        public <T> void put(Option<T> option, T value) { values.put(option, value); }
        @SuppressWarnings("unchecked")
        public <T> T get(Option<T> option) { return (T)values.get(option); }
    }
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
