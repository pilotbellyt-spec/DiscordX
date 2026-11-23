package discordx;

import discordx.api.Hooks;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

final class EmojiPicker {
    private static final String PREFIX = "expression.emoji:custom:";
    private final NativeDiscordUi owner;
    private final Set<Integer> items = new HashSet<>(), styled = new HashSet<>(), filtered = new HashSet<>();
    private final Set<Integer> rebound = new HashSet<>();
    private int sheet = -1;
    private boolean composer;

    EmojiPicker(NativeDiscordUi owner) { this.owner = owner; }

    void observe(NativeDiscordUi.Node node) {
        if (items.contains(node.id) || emojiId(node) != null) rebound.add(node.id);
        if (node.tag("xp-sheet")) sheet = node.id;
        if (emojiId(node) != null || node.tag("emoji-search-upsell") || isBadge(node)) items.add(node.id);
        else items.remove(node.id);
    }

    void removed(int id) {
        rebound.remove(id); items.remove(id); styled.remove(id); filtered.remove(id);
        if (id == sheet) { sheet = -1; composer = false; }
    }

    void reset() {
        rebound.clear(); items.clear(); styled.clear(); filtered.clear(); sheet = -1; composer = false;
    }

    private static String emojiId(NativeDiscordUi.Node node) {
        String tag = node.access.startsWith(PREFIX) ? node.access : node.nativeId;
        if (!tag.startsWith(PREFIX)) return null;
        String id = tag.substring(PREFIX.length());
        return Ids.snowflake(id) ? id : null;
    }

    private static boolean isBadge(NativeDiscordUi.Node node) {
        return node.uri.startsWith("res://") && (node.uri.contains("_lock") || node.uri.contains("nitro_wheel"));
    }

    private NativeDiscordUi.Node tile(NativeDiscordUi.Node node) {
        for (int depth = 0; node != null && depth < 6; depth++) {
            if (emojiId(node) != null) return node;
            Integer p = owner.parentId(node.id);
            node = p == null ? null : owner.node(p);
        }
        return null;
    }

    boolean event(NativeDiscordUi.Node node, String event) throws ReflectiveOperationException {
        if (node == null || !"press".equals(event)) return false;
        if (node.tag("composer.expressions")) { composer = true; return false; }
        if (!composer || sheet < 0 || !DiscordX.manager.hooks.has(Hooks.PICKER_EMOJIS)) return false;
        NativeDiscordUi.Node emoji = tile(node);
        if (emoji == null) return false;
        String link = DiscordX.manager.hooks.fold(Hooks.PICKER_EMOJIS, (String)null, (handler, value) ->
            value != null ? value : handler.link(emojiId(emoji), emoji.label, emoji.uri));
        return link != null && owner.insertEmoji(link);
    }

    PhoenixRender.Builder render(PhoenixRender.Batch source, PhoenixRender.Builder batch, Set<Integer> changed)
            throws ReflectiveOperationException {
        boolean active = composer && sheet >= 0 && DiscordX.manager.hooks.has(Hooks.PICKER_EMOJIS);
        if (!active && styled.isEmpty() && filtered.isEmpty()) { rebound.clear(); return batch; }
        Set<Integer> hidden = new HashSet<>(), parents = new HashSet<>();
        if (active) for (int id : items) {
            NativeDiscordUi.Node node = owner.node(id);
            if (node == null) continue;
            if (node.tag("emoji-search-upsell") || (isBadge(node) && tile(node) != null)) {
                Integer p = owner.parentId(id);
                if (p != null) { parents.add(p); hidden.add(id); }
            }
        }
        Set<Integer> update = new HashSet<>(parents);
        update.addAll(filtered);
        for (int id : update) {
            NativeDiscordUi.Node parent = owner.node(id);
            if (parent == null || parents.contains(id) && filtered.contains(id) && !changed.contains(id) && rebound.isEmpty()) continue;
            if (batch == null) batch = owner.copyBatch(source);
            ArrayList<Integer> children = new ArrayList<>();
            for (int child : parent.children) if (!hidden.contains(child)) children.add(child);
            batch.children(id, PhoenixRender.integers(children));
        }
        filtered.clear(); filtered.addAll(parents);
        Set<Integer> tiles = new HashSet<>(styled);
        if (active) tiles.addAll(items);
        for (int id : tiles) {
            NativeDiscordUi.Node node = owner.node(id);
            if (node == null) continue;
            boolean share = active && emojiId(node) != null;
            if (share && styled.contains(id) && !rebound.contains(id)) continue;
            if (!share && !styled.remove(id)) continue;
            if (batch == null) batch = owner.copyBatch(source);
            if (share) styled.add(id);
            batch.update(id, "opacity", share ? 1.0 : node.opacity,
                "accessibilityLabel", share ? "Share " + node.label + " as a link" : node.label);
        }
        rebound.clear();
        return batch;
    }
}
