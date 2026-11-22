package discordx.api;

import java.util.function.Consumer;

public interface PluginUi {
    Registration settings(String title, Page page);
    Registration profileAction(String title, Consumer<String> action);
    Registration messageAction(String title, Consumer<Message> action);
    Registration command(String name, String description, Runnable action);
    Registration open(String title, Page page);
    Registration androidModal(android.widget.LinearLayout content);
    void refresh();
    void close();

    interface Page { void render(Controls controls); }
    final class Message {
        public final String id, channelId, authorId, content, selectedText;
        public Message(String id, String channelId, String authorId, String content, String selectedText) {
            this.id = id; this.channelId = channelId; this.authorId = authorId; this.content = content; this.selectedText = selectedText;
        }
    }
    interface Controls {
        void text(String text);
        void heading(String text);
        void secondary(String text);
        void row(Consumer<Controls> content);
        void column(Consumer<Controls> content);
        void card(Consumer<Controls> content);
        void divider();
        void image(String url, String description);
        void input(String label, String value, boolean multiline, Consumer<String> changed);
        void button(String label, Runnable action);
        void toggle(String label, boolean checked, Consumer<Boolean> changed);
    }
}
