package discordx;

import android.os.Looper;
import discordx.api.Chat;
import discordx.api.Registration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.json.JSONObject;

final class ChatBridge implements Chat {
    final PluginScope scope;
    ChatBridge(PluginScope scope) { this.scope = scope; }
    private void main() {
        scope.requireActive();
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Use runOnMain for chat UI");
    }
    private <T> Registration add(ArrayList<T> list, T value) {
        main();
        list.add(value);
        scope.manager.changed();
        return scope.ownUi(() -> { list.remove(value); scope.manager.changed(); });
    }
    @Override public Registration button(Button place, String title, String icon, Runnable pressed) {
        if (title == null || title.trim().isEmpty() || icon == null || !icon.matches("res://[a-z0-9_]+"))
            throw new IllegalArgumentException("Expected a label and a resource icon");
        return add(scope.manager.chatButtons, new ButtonItem(this, Objects.requireNonNull(place), title, icon, Objects.requireNonNull(pressed)));
    }
    @Override public Registration accessory(Placement place, Function<String, Label> render) {
        return add(scope.manager.accessories, new Accessory(this, Objects.requireNonNull(place), Objects.requireNonNull(render)));
    }
    @Override public Registration tint(Function<String, Integer> color) {
        return add(scope.manager.chatTints, new Tint(this, Objects.requireNonNull(color)));
    }
    static final class Tint {
        final ChatBridge owner;
        final Function<String,Integer> render;
        Tint(ChatBridge owner, Function<String,Integer> render) { this.owner = owner; this.render = render; }
    }
    @Override public Registration slashCommand(String name, String description, Consumer<String> execute) {
        main();
        if (name == null || !name.matches("[a-z][a-z0-9_-]{0,31}") || scope.manager.commands.stream().anyMatch(item -> item.name.equals(name)))
            throw new IllegalArgumentException("Invalid or duplicate command name");
        PluginUiBridge.Command item = new PluginUiBridge.Command(scope, name, description, Objects.requireNonNull(execute));
        return add(scope.manager.commands, item);
    }
    @Override public Registration beforeSend(Function<String,String> filter) {
        return scope.register(discordx.api.Hooks.BEFORE_SEND, Objects.requireNonNull(filter));
    }
    @Override public LocalReply reply(String id, String text) {
        return local(id, text, false);
    }
    @Override public LocalReply localMessage(String channelId, String text) { return local(channelId, text, true); }
    private LocalReply local(String id, String text, boolean channel) {
        main();
        if (!Ids.snowflake(id) || text == null || text.isEmpty()) throw new IllegalArgumentException("Expected message ID and reply");
        Reply item = new Reply(this, id, text, channel);
        Registration registration = add(scope.manager.localReplies, item);
        return new LocalReply() {
            @Override public void edit(String value) {
                main();
                item.text = Objects.requireNonNull(value);
                if (channel) scope.manager.changed(); else redraw(id);
            }
            @Override public void close() { registration.close(); }
        };
    }
    @Override public void redraw(String id) {
        main();
        if (!Ids.snowflake(id)) throw new IllegalArgumentException("Invalid message ID");
        if (scope.manager.ui != null) scope.manager.ui.redrawChat(id);
    }
    @Override public JSONObject cachedMessage(String channel, String message) { scope.requireActive(); return scope.manager.cache.message(channel, message); }
    @Override public org.json.JSONArray queryCache(String sql, String... arguments) {
        scope.requireActive();
        return scope.manager.cache.query(sql, arguments);
    }
    @Override public String selectedChannel() { scope.requireActive(); return scope.manager.cache.selectedChannel(); }

    static final class ButtonItem {
        final ChatBridge owner; final Button placement; final String title, icon; final Runnable action;
        ButtonItem(ChatBridge owner, Button place, String title, String icon, Runnable action) {
            this.owner = owner; placement = place; this.title = title; this.icon = icon; this.action = action;
        }
    }
    static final class Accessory {
        final ChatBridge owner; final Placement placement; final Function<String,Label> render;
        Accessory(ChatBridge owner, Placement place, Function<String,Label> render) {
            this.owner = owner; placement = place; this.render = render;
        }
    }
    static final class Reply {
        final ChatBridge owner; final String id; String text; final boolean channel;
        Reply(ChatBridge owner, String id, String text, boolean channel) { this.owner = owner; this.id = id; this.text = text; this.channel = channel; }
    }
}
