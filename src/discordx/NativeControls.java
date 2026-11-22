package discordx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** A small set of controls expressed in Phoenix's own render properties. */
final class NativeControls {
    interface Action {
        void run(String event, Object payload) throws Exception;
    }

    static final String[] PRESS = {"press"};
    final PhoenixRender.Builder batch;
    final NativeDiscordUi owner;
    final ArrayList<Integer> nodes = new ArrayList<>();
    final Map<Integer, Action> actions = new LinkedHashMap<>();
    final long background, card, text, muted, accent;

    NativeControls(NativeDiscordUi owner, PhoenixRender.Builder batch) {
        this.owner = owner;
        this.batch = batch;
        boolean dark = owner.isDark();
        background = owner.color("background", dark ? 0xff1a1a1e : 0xfff8f8f9);
        long capturedCard = owner.color("card", dark ? 0xff27272d : 0xffffffff);
        card = capturedCard == background || (capturedCard >>> 24) < 128
            ? color(dark ? 0xff24262d : 0xffffffff) : capturedCard;
        text = owner.color("text", dark ? 0xfff4f4f6 : 0xff222226);
        muted = owner.color("muted", dark ? 0xffafb0ba : 0xff56565f);
        accent = color(DiscordX.foreground(dark ? 0xffa9b5ff : 0xff4955c9));
    }

    int node(String type, Object... props) throws ReflectiveOperationException {
        int id = owner.nextId();
        batch.create(id, type, props);
        nodes.add(id);
        return id;
    }

    int label(String value, int size, int weight, long color) throws ReflectiveOperationException {
        return node("text", "text", value, "fontSize", size, "fontWeight", weight, "color", color);
    }

    int column(int gap, float[] padding) throws ReflectiveOperationException {
        return node("view", "direction", "column", "width", "fill", "gap", gap, "padding", padding);
    }

    int link(String title, Action action) throws ReflectiveOperationException {
        int id = node("view", "direction", "row", "align", "center", "minHeight", 48,
            "padding", insets(8, 0), "on", PRESS, "role", "button", "accessibilityLabel", title);
        batch.children(id, label(title, 14, 600, accent));
        actions.put(id, action);
        return id;
    }

    int backButton(Action action) throws ReflectiveOperationException {
        return iconButton("Back", owner.backIcon(), action);
    }

    int iconButton(String title, String uri, Action action) throws ReflectiveOperationException {
        int id = node("view", "width", 48, "height", 48, "align", "center", "justify", "center",
            "radius", 12, "on", PRESS, "role", "button", "accessibilityLabel", title);
        batch.children(id, icon(uri, 24, text));
        actions.put(id, action);
        return id;
    }

    int button(String title, boolean primary, Action action) throws ReflectiveOperationException {
        int id = node("view", "direction", "row", "align", "center", "justify", "center", "minHeight", 48,
            "padding", insets(16, 8), "radius", 10, "background", primary ? accent : card,
            "activeBackground", color(owner.isDark() ? 0xff454956 : 0xffdedfea),
            "on", PRESS, "role", "button", "accessibilityLabel", title);
        batch.children(id, label(title, 14, 600, primary ? color(owner.isDark() ? 0xff20294d : 0xffffffff) : text));
        actions.put(id, action);
        return id;
    }

    int toggle(String title, boolean checked, Action action) throws ReflectiveOperationException {
        int target = node("view", "width", 48, "minHeight", 48, "align", "center", "justify", "center",
            "on", PRESS, "role", "switch", "accessibilityLabel", title, "accessibilityChecked", checked);
        int track = node("view", "width", 40, "height", 24, "radius", 12,
            "direction", "row", "align", "center", "justify", checked ? "end" : "start",
            "padding", insets(3, 3), "borderWidth", checked ? 0 : 1, "borderColor", muted,
            "background", checked ? accent : color(owner.isDark() ? 0xff555966 : 0xff767b88));
        int thumb = node("view", "width", 18, "height", 18, "radius", 9, "background", color(0xffd7e5ff));
        batch.children(track, thumb);
        batch.children(target, track);
        actions.put(target, action);
        return target;
    }

    int icon(String uri, int size, long tint) throws ReflectiveOperationException {
        return node("image", "uri", uri, "width", size, "height", size, "scale", "fit", "tint", tint);
    }

    int settingsRow(String chevron, Action action) throws ReflectiveOperationException {
        int row = node("view", "width", "fill", "direction", "row", "align", "center", "gap", 12,
            "minHeight", 52, "padding", insets(16, 6), "background", card, "on", PRESS,
            "role", "button", "accessibilityLabel", "DiscordX Plugins", "accessibilityId", "settings.row.discordx");
        int copy = node("view", "flex", 1, "direction", "column");
        batch.children(copy, label("DiscordX Plugins", 16, 500, text));
        int trailing = chevron.isEmpty() ? label("›", 19, 500, muted) : icon(chevron, 16, muted);
        batch.children(row, icon("res://ic_mana_puzzle_piece_plus", 24, text), copy, trailing);
        actions.put(row, action);
        return row;
    }

    int profileLink(String title, String iconUri, long foreground, Action action) throws ReflectiveOperationException {
        int row = node("view", "width", "fill", "direction", "row", "align", "center", "gap", 10,
            "minHeight", 48, "radius", 12, "padding", insets(12, 8), "background", 0x18000000L,
            "activeBackground", 0x28000000L, "on", PRESS, "role", "button", "accessibilityLabel", title);
        batch.children(row, icon(iconUri, 20, foreground), label(title, 15, 600, foreground));
        actions.put(row, action);
        return row;
    }

    int row(String title, String details, String trailing, long surface, Action action) throws ReflectiveOperationException {
        int row = node("view", "width", "fill", "direction", "row", "align", "center", "gap", 12,
            "minHeight", 52, "padding", insets(16, 8), "background", surface, "on", PRESS,
            "role", "button", "accessibilityLabel", title);
        int copy = node("view", "direction", "column", "flex", 1, "gap", 3);
        int heading = label(title, 16, 500, text);
        if (details.isEmpty()) batch.children(copy, heading);
        else batch.children(copy, heading, label(details, 13, 400, muted));
        batch.children(row, copy, label(trailing, 15, 600, muted));
        actions.put(row, action);
        return row;
    }

    int input(String title, String value, boolean multiline, Action action) throws ReflectiveOperationException {
        int id = node("input", "accessibilityLabel", title, "placeholder", title, "value", value,
            "multiline", multiline, "width", "fill", "minHeight", 48, "padding", insets(12, 8),
            "radius", 8, "background", card, "color", text, "placeholderColor", muted,
            "on", new String[] {"changeText"});
        actions.put(id, action);
        return id;
    }

    int scroll(int content) throws ReflectiveOperationException {
        int scroll = node("scroll", "width", "fill", "flex", 1, "height", "fill");
        batch.children(scroll, content);
        return scroll;
    }

    static float[] insets(float horizontal, float vertical) {
        return new float[] {horizontal, vertical, horizontal, vertical};
    }

    static long color(int argb) { return argb & 0xffffffffL; }

    int[] nodeIds() { return PhoenixRender.integers(nodes); }
}
