package discordx;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

final class NativeSettings {
    private final NativeDiscordUi owner;
    private int navigator, overview, root, previousIndex;
    private int[] previousChildren;
    private NativeControls page, body;

    NativeSettings(NativeDiscordUi owner) { this.owner = owner; }

    void open(int navigator, int overview) throws ReflectiveOperationException {
        if (page != null) return;
        NativeDiscordUi.Node nav = owner.node(navigator);
        if (nav == null || nav.children.length == 0 || owner.node(overview) == null) return;
        PhoenixRender.Builder batch = owner.builder();
        NativeControls ui = new NativeControls(owner, batch);
        this.navigator = navigator;
        this.overview = overview;
        previousChildren = nav.children.clone();
        previousIndex = nav.index >= 0 && nav.index < previousChildren.length ? nav.index : previousChildren.length - 1;
        root = ui.node("view", "width", "fill", "height", "fill", "direction", "column", "background", ui.background,
            "padding", new float[] {0, statusInset(), 0, 0}, "fullScreenBack", true, "accessibilityId", "discordx.settings");
        buildPage(ui, "", "");
        batch.update(navigator, "index", previousChildren.length);
        batch.children(navigator, NativeDiscordUi.inserting(previousChildren, previousChildren.length, root));
        owner.submit(batch);
        page = ui;
        owner.events.putAll(ui.actions);
        Log.i("DiscordX", "DiscordX Settings page opened in Phoenix navigator");
    }

    private void buildPage(NativeControls ui, String section, String feedback) throws ReflectiveOperationException {
        int header = ui.node("view", "direction", "row", "align", "center", "gap", 12, "height", 56,
            "background", ui.background, "padding", NativeControls.insets(12, 0));
        int back = ui.node("view", "width", 48, "height", 48, "direction", "row", "justify", "center", "align", "center",
            "on", NativeControls.PRESS, "role", "button", "accessibilityLabel", "Back");
        ui.batch.children(back, ui.icon("res://ic_arrow_back_black_24", 24, ui.muted));
        ui.actions.put(back, (event, payload) -> {
            if (section.isEmpty()) close();
            else show("", "");
        });
        ui.batch.children(header, back, ui.label(section.isEmpty() ? "DiscordX Plugins" : section, 20, 700, ui.text));
        int list = ui.column(8, NativeControls.insets(16, 12));
        ArrayList<Integer> rows = new ArrayList<>();
        if (!feedback.isEmpty()) rows.add(ui.label(feedback, 14, 500, ui.text));
        if ("Custom Themes".equals(section)) {
            paletteEditor(ui, rows);
        } else if ("Quick Jump".equals(section)) {
            quickJump(ui, rows);
        } else {
            for (DiscordX.Plugin plugin : DiscordX.PLUGINS) rows.add(toggle(ui, plugin));
            if (enabled("custom_themes")) rows.add(ui.row("Edit palette", "Custom Themes", "›", ui.card,
                (event, payload) -> show("Custom Themes", "")));
            if (enabled("quick_jump")) rows.add(ui.row("Open a Discord link", "Quick Jump", "›", ui.card,
                (event, payload) -> show("Quick Jump", "")));
        }
        ui.batch.children(list, PhoenixRender.integers(rows));
        ui.batch.children(root, header, ui.scroll(list));
    }

    private int toggle(NativeControls ui, DiscordX.Plugin plugin) throws ReflectiveOperationException {
        boolean enabled = enabled(plugin.id);
        int row = ui.node("view", "direction", "row", "align", "center", "width", "fill", "minHeight", 52,
            "gap", 14, "padding", NativeControls.insets(16, 10), "background", ui.card, "radius", 10,
            "role", "switch", "accessibilityLabel", plugin.title + (enabled ? ", on" : ", off"), "on", NativeControls.PRESS);
        int copy = ui.node("view", "direction", "column", "flex", 1, "gap", 4);
        ui.batch.children(copy, ui.label(plugin.title, 16, 600, ui.text), ui.label(plugin.description, 13, 400, ui.muted));
        int track = ui.node("view", "width", 38, "height", 22, "radius", 11, "direction", "row", "align", "center",
            "justify", enabled ? "end" : "start", "padding", NativeControls.insets(3, 0),
            "background", enabled ? ui.accent : NativeControls.color(owner.isDark() ? 0xff536079 : 0xff757d92));
        int thumb = ui.node("view", "width", 16, "height", 16, "radius", 8,
            "background", NativeControls.color(enabled && owner.isDark() ? 0xff252839 : 0xffe9efff));
        ui.batch.children(track, thumb);
        ui.batch.children(row, copy, track);
        ui.actions.put(row, (event, payload) -> {
            DiscordX.changePlugin(owner.activity, plugin, !enabled);
            if (!owner.activity.isChangingConfigurations()) show("", "");
        });
        return row;
    }

    private void paletteEditor(NativeControls ui, ArrayList<Integer> rows) throws ReflectiveOperationException {
        SharedPreferences prefs = owner.activity.getSharedPreferences(DiscordX.SETTINGS, Context.MODE_PRIVATE);
        ThemePalette palette = DiscordX.savedPalette(prefs);
        String[] values = {hex(palette.surface), hex(palette.text), hex(palette.accent)};
        String[] labels = {"Background", "Text", "Accent"};
        for (int i = 0; i < labels.length; i++) {
            final int slot = i;
            rows.add(ui.label(labels[i], 14, 600, ui.muted));
            rows.add(ui.input(labels[i], values[i], false, (event, payload) -> {
                String value = owner.inputText(payload);
                if (value != null) values[slot] = value;
            }));
        }
        rows.add(ui.link("Apply palette", (event, payload) -> {
            int[] parsed = new int[3];
            for (int i = 0; i < values.length; i++) {
                if (!values[i].trim().matches("#[0-9a-fA-F]{6}")) {
                    show("Custom Themes", "Use six-digit hex colors like #19252C.");
                    return;
                }
                parsed[i] = Color.parseColor(values[i].trim());
            }
            if (!ThemePalette.readable(parsed[1], parsed[0]) || !ThemePalette.readable(parsed[2], parsed[0])) {
                show("Custom Themes", "Choose text and accent colors with more contrast.");
                return;
            }
            prefs.edit().putInt("theme_surface", parsed[0]).putInt("theme_text", parsed[1])
                .putInt("theme_accent", parsed[2]).apply();
            ThemePalette.set(new ThemePalette(parsed[0], parsed[1], parsed[2]));
            owner.activity.recreate();
        }));
    }

    private void quickJump(NativeControls ui, ArrayList<Integer> rows) throws ReflectiveOperationException {
        String[] input = {""};
        rows.add(ui.input("Discord channel, message, or profile link", "", false, (event, payload) -> {
            String value = owner.inputText(payload);
            if (value != null) input[0] = value;
        }));
        rows.add(ui.link("Open in Discord", (event, payload) -> {
            String url = DiscordLinks.jumpDestination(input[0]);
            if (url == null) {
                show("Quick Jump", "Paste a Discord channel, message, or profile link.");
                return;
            }
            try {
                owner.activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(owner.activity.getPackageName()));
                close();
            } catch (ActivityNotFoundException error) {
                show("Quick Jump", "Phoenix couldn’t open that link.");
            }
        }));
    }

    private void show(String section, String feedback) throws ReflectiveOperationException {
        PhoenixRender.Builder batch = owner.builder();
        NativeControls next = new NativeControls(owner, batch);
        buildPage(next, section, feedback);
        if (body == null) {
            page.nodes.remove(Integer.valueOf(root));
            owner.discard(batch, page);
        } else owner.discard(batch, body);
        owner.submit(batch);
        body = next;
        owner.events.putAll(next.actions);
    }

    boolean intercepts(int id, String event) {
        return page != null && id == navigator && "navigate".equals(event);
    }

    boolean back() {
        if (page == null) return false;
        try {
            close();
        } catch (ReflectiveOperationException error) {
            owner.failure("Could not return to Phoenix Settings", error);
        }
        return true;
    }

    void close() throws ReflectiveOperationException {
        if (page == null) return;
        PhoenixRender.Builder batch = owner.builder();
        NativeDiscordUi.Node nav = owner.node(navigator);
        if (nav != null && Arrays.equals(nav.children, previousChildren)) {
            batch.update(navigator, "index", previousIndex);
            batch.children(navigator, previousChildren);
        }
        owner.discard(batch, page);
        if (body != null) {
            owner.discard(batch, body);
            batch.delete(root);
        }
        reset();
        owner.submit(batch);
    }

    void hostRemoved(int id) {
        if (page != null && (id == overview || id == navigator)) owner.main.post(this::back);
    }

    void reset() {
        page = null;
        body = null;
    }

    private float statusInset() {
        android.view.WindowInsets window = owner.activity.getWindow().getDecorView().getRootWindowInsets();
        if (window == null) return 24;
        int pixels = android.os.Build.VERSION.SDK_INT >= 30
            ? window.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
            : window.getStableInsetTop();
        return pixels / owner.activity.getResources().getDisplayMetrics().density;
    }

    private boolean enabled(String id) { return DiscordX.enabled(owner.activity, id); }
    private static String hex(int color) { return String.format(Locale.ROOT, "#%06X", color & 0xffffff); }
}
