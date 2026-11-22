package discordx;

import discordx.api.Chat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Owns the extra children for each chat container. Phoenix retains its original child list. */
final class ChatSurface {
    private final NativeDiscordUi owner;
    private final Map<Integer, NativeControls> mounted = new HashMap<>();
    private final Map<Integer,String> anchors = new HashMap<>();
    private final Set<Integer> dirty = new HashSet<>();
    private final Map<Integer,String> drafts = new HashMap<>();
    private final Map<Integer,Long> tinted = new HashMap<>();
    private final Set<Integer> updatedText = new HashSet<>();
    private final Set<String> redrawIds = new HashSet<>();
    private boolean redrawAll;
    private String channel, channelSignature;
    private int channelGeneration;
    private int pickerRows = -1;
    private int localBefore = -1, localParent = -1;
    ChatSurface(NativeDiscordUi owner) { this.owner = owner; }

    void observe(NativeDiscordUi.Node node) {
        if ("text".equals(node.type) && (!DiscordX.manager.chatTints.isEmpty() || tinted.containsKey(node.id))) updatedText.add(node.id);
        String tag = node.tag("suggestions") ? "suggestions" : node.access;
        String previous = anchors.get(node.id);
        boolean known = tag.equals("suggestions") || tag.equals("search.entry") || tag.equals("composer.expressions") ||
            tag.startsWith("message.content:") || tag.startsWith("message.timestamp:");
        if (known && !tag.equals(previous)) { anchors.put(node.id, tag); mark(node.id); }
        if (!known && previous != null) { anchors.remove(node.id); mark(node.id); }
        if (node.tag("composer")) drafts.putIfAbsent(node.id, "");
        else drafts.remove(node.id);
    }

    void removed(int id) {
        anchors.remove(id);
        tinted.remove(id);
        updatedText.remove(id);
        drafts.remove(id);
        NativeControls old = mounted.remove(id);
        if (old != null) owner.main.post(() -> owner.delete(old));
    }

    void reset() { pickerRows = -1; tinted.clear(); updatedText.clear(); redrawIds.clear(); redrawAll = false; localBefore = localParent = -1; channel = null; channelSignature = null; channelGeneration++; mounted.clear(); dirty.clear(); drafts.clear(); anchors.clear(); }
    private void mark(int id) {
        Integer parent = owner.parentId(id);
        if (parent != null) dirty.add(parent);
        if (anchors.getOrDefault(id,"").startsWith("message.content:") || mounted.containsKey(id)) dirty.add(id);
    }
    void invalidate(String message) {
        if (message == null) redrawAll = true; else redrawIds.add(message);
        for (Map.Entry<Integer,String> anchor : anchors.entrySet()) {
            if (message == null || anchor.getValue().endsWith(":" + message)) mark(anchor.getKey());
        }
        if (message == null) { dirty.addAll(mounted.keySet()); dirty.add(localParent); dirty.add(pickerRows); }
    }

    void navigated() { channelSignature = null; channel = null; channelGeneration++; }

    private void resolveChannel(PluginManager manager) {
        boolean localMessages = manager.localReplies.stream().anyMatch(item -> item.channel);
        if (!localMessages && channelSignature != null) navigated();
        if (!localMessages) return;
        String newest = "";
        int composer = -1;
        for (Map.Entry<Integer,String> entry : anchors.entrySet()) {
            String tag = entry.getValue();
            if (tag.equals("composer.expressions")) composer = entry.getKey();
            if (tag.startsWith("message.content:") && tag.compareTo(newest) > 0) newest = tag;
        }
        // Put channel-local messages above the composer row in its enclosing column.
        int before = composer;
        Integer parent = owner.parentId(before);
        while (parent != null && before != -1) {
            NativeDiscordUi.Node group = owner.node(parent);
            if (group == null) break;
            boolean containsDraft = false;
            for (int input : drafts.keySet()) {
                Integer ancestor = input;
                for (int depth = 0; ancestor != null && depth < 20; depth++, ancestor = owner.parentId(ancestor))
                    if (ancestor == before) { containsDraft = true; break; }
            }
            if (containsDraft && !"row".equals(group.direction)) break;
            before = parent;
            parent = owner.parentId(before);
        }
        int nextParent = parent == null ? -1 : parent;
        if (localParent != nextParent || localBefore != before) {
            dirty.add(localParent); dirty.add(nextParent);
            localParent = nextParent; localBefore = before;
        }
        String signature = composer + "/" + newest;
        if (signature.equals(channelSignature)) return;
        channelSignature = signature;
        channel = null;
        int generation = ++channelGeneration;
        invalidate(null);
        if (composer == -1) return;
        try {
            manager.background.execute(() -> {
                String selected = manager.cache.selectedChannel();
                owner.main.post(() -> {
                    if (generation != channelGeneration || owner.activity.isDestroyed()) return;
                    channel = selected;
                    owner.redrawChat(null);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { channelSignature = null; }
    }

    PhoenixRender.Builder render(PhoenixRender.Batch batch, PhoenixRender.Builder output, Set<Integer> changed)
            throws ReflectiveOperationException {
        PluginManager manager = DiscordX.manager;
        output = tints(batch, output, manager, changed);
        if (manager.chatButtons.isEmpty() && manager.accessories.isEmpty() && manager.localReplies.isEmpty() && manager.commands.isEmpty() && mounted.isEmpty()) {
            dirty.clear();
            return output;
        }
        resolveChannel(manager);
        int nextRows = manager.commands.isEmpty() ? -1 : pickerRows();
        if (nextRows != pickerRows) {
            dirty.add(pickerRows);
            dirty.add(nextRows);
            pickerRows = nextRows;
        }
        dirty.addAll(changed);
        Set<Integer> rebuild = new HashSet<>(dirty);
        dirty.clear();
        for (int parentId : rebuild) {
            NativeDiscordUi.Node parent = owner.node(parentId);
            if (parent == null) continue;
            String content = anchors.get(parentId);
            boolean relevant = parentId == pickerRows || parentId == localParent || content != null && content.startsWith("message.content:");
            for (int child : parent.children) relevant |= anchors.containsKey(child);
            NativeControls old = mounted.remove(parentId);
            if (!relevant && old == null) continue;
            if (output == null) output = owner.copyBatch(batch);
            if (old != null) owner.discard(output, old);
            NativeControls ui = new NativeControls(owner, output);
            ArrayList<Integer> children = new ArrayList<>();
            if (parentId == pickerRows) commandRows(ui, children, manager);
            for (int child : parent.children) {
                if (child == localBefore) composerPanel(ui, children, manager);
                String tag = anchors.get(child);
                if ("search.entry".equals(tag) || "composer.expressions".equals(tag)) {
                    Chat.Button place = tag.equals("search.entry") ? Chat.Button.HEADER : Chat.Button.COMPOSER;
                    for (ChatBridge.ButtonItem button : new ArrayList<>(manager.chatButtons)) {
                        if (button.owner.scope.active && button.placement == place)
                            children.add(ui.iconButton(button.title, button.icon, (event, payload) -> button.owner.scope.guard(button.action)));
                    }
                }
                children.add(child);
                if (tag != null && tag.startsWith("message.timestamp:"))
                    labels(ui, children, Chat.Placement.TIMESTAMP, tag.substring(tag.indexOf(':') + 1));
            }
            if (content != null && content.startsWith("message.content:")) {
                String id = content.substring(content.indexOf(':') + 1);
                labels(ui, children, Chat.Placement.CONTENT, id);
                for (ChatBridge.Reply reply : new ArrayList<>(manager.localReplies)) {
                    if (!reply.channel && reply.owner.scope.active && reply.id.equals(id))
                        children.add(ui.label(reply.owner.scope.info().name + " · " + reply.text, 14, 500, ui.muted));
                }
            }
            if (!ui.nodes.isEmpty()) {
                mounted.put(parentId, ui);
                owner.events.putAll(ui.actions);
            }
            if (old != null || !ui.nodes.isEmpty()) output.children(parentId, PhoenixRender.integers(children));
        }
        return output;
    }

    private PhoenixRender.Builder tints(PhoenixRender.Batch batch, PhoenixRender.Builder output, PluginManager manager,
            Set<Integer> changed) throws ReflectiveOperationException {
        if ((!manager.chatTints.isEmpty() || !tinted.isEmpty())
                && (redrawAll || !redrawIds.isEmpty() || !updatedText.isEmpty() || !changed.isEmpty())) {
            for (NativeDiscordUi.Node node : owner.visibleNodes()) {
                if (!"text".equals(node.type) || node.color == 0) continue;
                if (!redrawAll && redrawIds.isEmpty() && changed.isEmpty() && !updatedText.contains(node.id)) continue;
                String id = null;
                boolean reparented = false;
                NativeDiscordUi.Node parent = node;
                for (int depth = 0; parent != null && depth < 16; depth++) {
                    reparented |= changed.contains(parent.id);
                    String tag = parent.access;
                    if (tag.startsWith("message.author:") || tag.startsWith("message.content:")) {
                        id = tag.substring(tag.indexOf(':') + 1);
                        break;
                    }
                    Integer next = owner.parentId(parent.id);
                    parent = next == null ? null : owner.node(next);
                }
                if (!redrawAll && !reparented && !updatedText.contains(node.id) && !redrawIds.contains(id)) continue;
                Integer[] color = {null};
                final String message = id;
                if (Ids.snowflake(id)) for (ChatBridge.Tint tint : new ArrayList<>(manager.chatTints)) {
                    tint.owner.scope.guard(() -> {
                        Integer choice = tint.render.apply(message);
                        if (choice != null) color[0] = choice;
                    });
                }
                Long previous = tinted.get(node.id);
                Long next = color[0] == null ? null : NativeControls.color(color[0]);
                if (java.util.Objects.equals(previous, next) && (next == null || !updatedText.contains(node.id))) continue;
                if (output == null) output = owner.copyBatch(batch);
                output.update(node.id, "color", next == null ? node.color : next.longValue());
                if (next == null) tinted.remove(node.id); else tinted.put(node.id, next);
            }
        }
        redrawAll = false; redrawIds.clear(); updatedText.clear();
        return output;
    }

    private int pickerRows() {
        for (Map.Entry<Integer,String> anchor : anchors.entrySet()) {
            if (!anchor.getValue().equals("suggestions")) continue;
            NativeDiscordUi.Node root = owner.node(anchor.getKey());
            if (root == null || root.children.length != 1) continue;
            NativeDiscordUi.Node container = owner.node(root.children[0]);
            // A stack overlays siblings. Use its column so native results move down for plugin rows.
            while (container != null && !container.type.equals("view") && container.children.length > 0)
                container = owner.node(container.children[0]);
            if (container != null && container.type.equals("view") && !"row".equals(container.direction))
                return container.id;
        }
        return -1;
    }

    private void commandRows(NativeControls ui, ArrayList<Integer> rows, PluginManager manager)
            throws ReflectiveOperationException {
        if (drafts.size() != 1) return;
        int input = drafts.keySet().iterator().next();
        String draft = drafts.get(input);
        if (!draft.startsWith("/")) return;
        for (PluginUiBridge.Command command : manager.commands) {
            String name = "/" + command.name;
            if (!command.scope.active || !name.startsWith(draft)) continue;
            rows.add(ui.row(name, command.description, command.scope.info().name, 0L, (event, payload) -> {
                if (!command.scope.active || !manager.commands.contains(command) || !draft.equals(drafts.get(input))) return;
                android.view.View view = owner.nativeView(input);
                android.widget.EditText field = findField(view);
                if (field == null) return;
                String next = command.hasForm ? "" : name + " ";
                field.setText(next);
                field.setSelection(next.length());
                if (!next.equals(drafts.get(input))) owner.changeDraft(view, next);
                if (command.hasForm) command.scope.guard(command.action);
            }));
        }
    }

    private void composerPanel(NativeControls ui, ArrayList<Integer> children, PluginManager manager)
            throws ReflectiveOperationException {
        ArrayList<Integer> rows = new ArrayList<>();
        for (ChatBridge.Reply reply : manager.localReplies)
            if (reply.channel && reply.owner.scope.active && reply.id.equals(channel))
                rows.add(ui.label(reply.owner.scope.info().name + " · " + reply.text, 14, 500, ui.muted));
        if (rows.isEmpty()) return;
        int stack = ui.node("view", "direction", "column", "width", "fill", "gap", 4, "padding", NativeControls.insets(12, 6));
        ui.batch.children(stack, PhoenixRender.integers(rows));
        children.add(stack);
    }

    private void labels(NativeControls ui, ArrayList<Integer> children, Chat.Placement place, String id)
            throws ReflectiveOperationException {
        if (!Ids.snowflake(id)) return;
        for (ChatBridge.Accessory item : new ArrayList<>(DiscordX.manager.accessories)) {
            if (!item.owner.scope.active || item.placement != place) continue;
            Chat.Label[] result = new Chat.Label[1];
            item.owner.scope.guard(() -> result[0] = item.render.apply(id));
            Chat.Label label = result[0];
            if (label != null && label.text != null && !label.text.isEmpty())
                children.add(ui.label(label.text, 13, 500, NativeControls.color(label.color)));
        }
    }

    boolean event(NativeDiscordUi.Node node, String event, Object payload) throws ReflectiveOperationException {
        if (node == null) return false;
        if (node.tag("composer") && "changeText".equals(event)) {
            String text = owner.inputText(payload);
            if (text != null) {
                String prior = drafts.put(node.id, text);
                if (!DiscordX.manager.commands.isEmpty() && (text.startsWith("/") || prior != null && prior.startsWith("/")))
                    owner.redrawChat(null);
            }
            return false;
        }
        boolean keyboard = node.tag("composer") && "submit".equals(event);
        if (!keyboard && !(node.tag("send") && "press".equals(event))) return false;
        PluginManager manager = DiscordX.manager;
        if (manager.commands.isEmpty() && !manager.hooks.has(discordx.api.Hooks.BEFORE_SEND)) return false;
        if (!keyboard && drafts.size() != 1) return false;
        int input = keyboard ? node.id : drafts.keySet().iterator().next();
        android.view.View view = owner.nativeView(input);
        android.widget.EditText field = findField(view);
        String draft = field == null ? drafts.get(input) : field.getText().toString();
        if (draft == null || draft.isEmpty()) return false;
        String result = ChatSubmission.apply(manager, draft);
        if (draft.equals(result)) return false;
        String replacement = result == null ? "" : result;
        try {
            if (field != null) field.setText(replacement);
            if (!replacement.equals(drafts.get(input)) && view != null) owner.changeDraft(view, replacement);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            owner.failure("Could not replace composer draft", unavailable);
            return true;
        }
        return result == null || !replacement.equals(drafts.get(input));
    }

    private static android.widget.EditText findField(android.view.View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText)view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText field = findField(group.getChildAt(i));
                if (field != null) return field;
            }
        }
        return null;
    }
}
