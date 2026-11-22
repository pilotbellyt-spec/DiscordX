package discordx;

import android.content.ActivityNotFoundException;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

final class NativeSettings {
    private final NativeDiscordUi owner;
    private int navigator, overview, root, previousIndex;
    private int[] previousChildren;
    private NativeControls page, body;
    private int modal;

    NativeSettings(NativeDiscordUi owner) { this.owner = owner; }

    void openModal() throws ReflectiveOperationException {
        if (page != null) return;
        PhoenixRender.Builder batch = owner.builder();
        NativeControls ui = new NativeControls(owner, batch);
        modal = ui.node("modal", "fullscreen", true, "chrome", "guest", "dismissible", true,
            "on", new String[] {"dismiss", "exitEnd"});
        root = ui.node("view", "width", "fill", "height", "fill", "direction", "column", "background", ui.background,
            "padding", new float[] {0, statusInset(), 0, 0}, "accessibilityId", "discordx.settings");
        buildPage(ui, "", "");
        batch.children(modal, root);
        owner.submit(batch);
        page = ui;
        owner.events.putAll(ui.actions);
    }

    boolean overlayEvent(int id) { if (modal != 0 && id == modal) { back(); return true; } return false; }

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

    private String importUrl = "";
    private String query = "", selected = "";
    private boolean enabledOnly, importFromUrl;

    private int contentWidth() {
        android.util.DisplayMetrics metrics = owner.activity.getResources().getDisplayMetrics();
        return Math.min(880, Math.max(240, (int) (metrics.widthPixels / metrics.density) - 32));
    }

    private int horizontal(NativeControls ui, int gap, int... children) throws ReflectiveOperationException {
        int row = ui.node("view", "width", "fill", "direction", "row", "align", "center", "gap", gap);
        ui.batch.children(row, children);
        return row;
    }

    private int stretch(NativeControls ui, int child) throws ReflectiveOperationException {
        int wrapper = ui.node("view", "flex", 1, "direction", "column");
        ui.batch.children(wrapper, child);
        return wrapper;
    }

    private void buildPage(NativeControls ui, String section, String feedback) throws ReflectiveOperationException {
        selected = section;
        PluginManager manager = DiscordX.manager;
        PluginManager.Entry detail = manager.entries.get(section);
        int width = Math.min(contentWidth(), "@import".equals(section) ? 560 : detail != null ? 680 : 880);
        int content = ui.node("view", "width", width, "direction", "column", "gap", 16,
            "padding", NativeControls.insets(0, 16));
        ArrayList<Integer> rows = new ArrayList<>();
        String heading = section.isEmpty() ? "Plugins" : "@import".equals(section) ? "Add plugin"
            : "@commands".equals(section) ? "Commands" : "@manage".equals(section) ? "Manage plugins" : detail != null ? detail.pack.name : "Plugin";
        int title = ui.node("view", "flex", 1, "direction", "column", "gap", 2);
        ui.batch.children(title, ui.label(heading, 24, 700, ui.text));
        int back = ui.backButton((event, payload) -> { if (section.isEmpty()) close(); else show("", ""); });
        ArrayList<Integer> header = new ArrayList<>();
        header.add(back); header.add(title);
        if (section.isEmpty()) header.add(ui.button("Add plugin", true, (event, payload) -> show("@import", "")));
        else if (detail != null) header.add(ui.toggle("Enable " + detail.pack.name,
            detail.status == PluginManager.Status.RUNNING, (event, payload) -> manager.toggle(detail)));
        rows.add(horizontal(ui, 12, PhoenixRender.integers(header)));
        if (!manager.notice.isEmpty()) rows.add(ui.label(manager.notice, 13, 500, ui.muted));
        if ("@import".equals(section)) {
            int tabs = ui.node("view", "width", "fill", "direction", "row", "gap", 4,
                "padding", NativeControls.insets(4, 4), "radius", 12, "background", ui.card);
            ui.batch.children(tabs,
                tab(ui, "ZIP", !importFromUrl, (event, payload) -> { importFromUrl = false; show("@import", ""); }),
                tab(ui, "URL", importFromUrl, (event, payload) -> { importFromUrl = true; show("@import", ""); }));
            rows.add(tabs);
            if (importFromUrl) {
                rows.add(ui.input("https://…/plugin.zip", importUrl, false, (event, payload) -> {
                    String value = owner.inputText(payload); if (value != null) importUrl = value;
                }));
                rows.add(horizontal(ui, 8, ui.button("Import", true, (event, payload) -> manager.importUrl(importUrl))));
            } else {
                int file = ui.node("view", "width", "fill", "minHeight", 80, "direction", "row", "align", "center",
                    "justify", "center", "gap", 12, "radius", 12, "background", ui.card,
                    "on", NativeControls.PRESS, "role", "button", "accessibilityLabel", "Choose ZIP");
                ui.batch.children(file, ui.icon("res://ic_mana_puzzle_piece_plus", 24, ui.accent), ui.label("Choose ZIP", 16, 600, ui.text));
                ui.actions.put(file, (event, payload) -> {
                    try { manager.picker(owner.activity); }
                    catch (ActivityNotFoundException unavailable) { manager.notice = "Document picker unavailable"; manager.changed(); }
                });
                rows.add(file);
            }
        } else if ("@manage".equals(section)) {
            rows.add(ui.button("Disable all plugins", false, (event, payload) -> manager.disableAll()));
        } else if ("@commands".equals(section)) {
            for (PluginUiBridge.Command command : new ArrayList<>(manager.commands)) if (command.scope.active)
                rows.add(ui.row(command.name, command.description, "", ui.card,
                    (event, payload) -> command.scope.guard(command.action)));
            if (manager.commands.isEmpty()) rows.add(ui.label("No commands", 14, 400, ui.muted));
        } else if (!section.isEmpty()) {
            PluginManager.Entry entry = manager.entries.get(section);
            if (entry != null) {
                rows.add(ui.label(entry.pack.author + " · " + entry.pack.version, 14, 500, ui.muted));
                if (!entry.error.isEmpty()) rows.add(ui.label(entry.error, 14, 500, ui.text));
                ArrayList<Integer> settings = new ArrayList<>();
                for (PluginUiBridge.Setting setting : new ArrayList<>(manager.settings)) if (setting.scope.active && setting.scope.entry == entry)
                    settings.add(ui.row(setting.title, "", "", ui.card,
                        (event, payload) -> setting.scope.guard(() -> setting.scope.ui().open(setting.title, setting.page))));
                if (!settings.isEmpty()) {
                    rows.add(ui.label("Settings", 14, 600, ui.muted));
                    int group = ui.column(0, NativeControls.insets(0, 0));
                    ui.batch.update(group, "background", ui.card, "radius", 12, "clip", true);
                    ui.batch.children(group, PhoenixRender.integers(settings));
                    rows.add(group);
                }
                int metadata = ui.column(12, NativeControls.insets(16, 16));
                ui.batch.update(metadata, "background", ui.card, "radius", 12);
                ui.batch.children(metadata,
                    metadata(ui, "ID", entry.pack.id),
                    metadata(ui, "API", "1"),
                    metadata(ui, "Source", entry.bundled ? "Bundled" : "Imported"),
                    metadata(ui, "State", entry.status.name().toLowerCase(Locale.ROOT)),
                    metadata(ui, "Entrypoint", entry.pack.entrypoint),
                    horizontal(ui, 12, stretch(ui, metadata(ui, "SHA-256", entry.pack.hash.substring(0, 12) + "..." + entry.pack.hash.substring(52))),
                        ui.link("Copy hash", (event, payload) -> {
                            android.content.ClipboardManager clipboard = owner.activity.getSystemService(android.content.ClipboardManager.class);
                            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SHA-256", entry.pack.hash));
                        })));
                rows.add(metadata);
                ArrayList<Integer> actions = new ArrayList<>();
                actions.add(ui.button("Reload", false, (event, payload) -> manager.reload(entry)));
                if (manager.hasDocument(entry)) actions.add(ui.button("Reload ZIP", false, (event, payload) -> manager.reloadDocument(entry)));
                if (!entry.bundled) actions.add(ui.button("Remove", false, (event, payload) -> {
                    manager.uninstall(entry); show("", "");
                }));
                rows.add(horizontal(ui, 8, PhoenixRender.integers(actions)));
            } else rows.add(ui.label("Plugin removed", 14, 400, ui.muted));
        } else {
            int search = ui.input("Search plugins", query, false, (event, payload) -> {
                String value = owner.inputText(payload); if (value != null) query = value;
            });
            rows.add(horizontal(ui, 8, stretch(ui, search), ui.button("Search", false, (event, payload) -> show("", ""))));
            int enabled = 0;
            for (PluginManager.Entry entry : manager.entries.values()) if (entry.status == PluginManager.Status.RUNNING) enabled++;
            ArrayList<Integer> filters = new ArrayList<>();
            filters.add(ui.button("All " + manager.entries.size(), !enabledOnly, (event, payload) -> { enabledOnly = false; show("", ""); }));
            filters.add(ui.button("Enabled " + enabled, enabledOnly, (event, payload) -> { enabledOnly = true; show("", ""); }));
            if (width >= 540) {
                filters.add(stretch(ui, ui.node("view")));
                if (!manager.commands.isEmpty()) filters.add(ui.link("Commands", (event, payload) -> show("@commands", "")));
                filters.add(ui.link("Manage", (event, payload) -> show("@manage", "")));
            }
            rows.add(horizontal(ui, 8, PhoenixRender.integers(filters)));
            ArrayList<Integer> cards = new ArrayList<>();
            int columns = width >= 700 ? 2 : 1;
            int cardWidth = (width - (columns - 1) * 12) / columns;
            String term = query.trim().toLowerCase(Locale.ROOT);
            for (PluginManager.Entry entry : manager.entries.values()) {
                if (enabledOnly && entry.status != PluginManager.Status.RUNNING) continue;
                if (!(entry.pack.name + " " + entry.pack.description + " " + entry.pack.author).toLowerCase(Locale.ROOT).contains(term)) continue;
                cards.add(pluginCard(ui, entry, cardWidth));
                if (cards.size() == columns) { rows.add(horizontal(ui, 12, PhoenixRender.integers(cards))); cards.clear(); }
            }
            if (!cards.isEmpty()) rows.add(horizontal(ui, 12, PhoenixRender.integers(cards)));
            boolean matches = false;
            for (PluginManager.Entry entry : manager.entries.values()) if ((!enabledOnly || entry.status == PluginManager.Status.RUNNING)
                && (entry.pack.name + " " + entry.pack.description + " " + entry.pack.author).toLowerCase(Locale.ROOT).contains(term)) matches = true;
            if (!matches) rows.add(ui.label(enabledOnly && term.isEmpty() ? "No enabled plugins" : "No matches", 14, 400, ui.muted));
            if (width < 540) rows.add(horizontal(ui, 12, ui.link("Manage plugins", (event, payload) -> show("@manage", "")),
                ui.link("Commands", (event, payload) -> show("@commands", ""))));
        }
        ui.batch.children(content, PhoenixRender.integers(rows));
        int centered = ui.node("view", "width", "fill", "direction", "column", "align", "center");
        ui.batch.children(centered, content);
        ui.batch.children(root, ui.scroll(centered));
    }

    private int tab(NativeControls ui, String title, boolean selected, NativeControls.Action action)
            throws ReflectiveOperationException {
        int button = ui.button(title, selected, action);
        ui.batch.update(button, "width", "fill", "role", "tab", "accessibilitySelected", selected);
        return stretch(ui, button);
    }

    private int metadata(NativeControls ui, String label, String value) throws ReflectiveOperationException {
        int key = ui.node("view", "width", 88, "direction", "column");
        ui.batch.children(key, ui.label(label, 13, 400, ui.muted));
        return horizontal(ui, 12, key, stretch(ui, ui.label(value, 13, 500, ui.text)));
    }

    private int pluginCard(NativeControls ui, PluginManager.Entry entry, int width) throws ReflectiveOperationException {
        boolean enabled = entry.status == PluginManager.Status.RUNNING;
        int card = ui.node("view", "width", width, "minHeight", 172, "direction", "column", "gap", 8,
            "padding", NativeControls.insets(16, 12), "background", ui.card, "radius", 12,
            "borderWidth", 1, "borderColor", NativeControls.color(owner.isDark() ? 0xff3b3d47 : 0xffdddfe7));
        int name = ui.node("view", "flex", 1, "minHeight", 48, "direction", "row", "align", "center", "gap", 8,
            "on", NativeControls.PRESS, "role", "button", "accessibilityLabel", entry.pack.name + " details");
        ui.batch.children(name, ui.label(entry.pack.name, 16, 650, ui.text));
        ui.actions.put(name, (event, payload) -> show(entry.pack.id, ""));
        int top = horizontal(ui, 8, name, ui.toggle("Enable " + entry.pack.name, enabled,
            (event, payload) -> DiscordX.manager.toggle(entry)));
        int description = ui.node("view", "minHeight", 40, "direction", "column");
        ui.batch.children(description, ui.label(entry.pack.description, 13, 400, ui.muted));
        int footer = horizontal(ui, 8, stretch(ui, ui.label(entry.pack.author + " · " + entry.pack.version, 12, 400, ui.muted)),
            ui.link("Details", (event, payload) -> show(entry.pack.id, "")));
        if (entry.error.isEmpty()) ui.batch.children(card, top, description, footer);
        else ui.batch.children(card, top, description, ui.label("Failed", 12, 500, ui.text), footer);
        return card;
    }

    void refresh() {
        if (page == null) return;
        try { show(selected, ""); }
        catch (ReflectiveOperationException error) { owner.failure("Could not refresh plugin manager", error); }
    }

    private void show(String section, String feedback) throws ReflectiveOperationException {
        PhoenixRender.Builder batch = owner.builder();
        NativeControls next = new NativeControls(owner, batch);
        buildPage(next, section, feedback);
        if (body == null) {
            page.nodes.remove(Integer.valueOf(root));
            if (modal != 0) page.nodes.remove(Integer.valueOf(modal));
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
        if (modal == 0 && nav != null && Arrays.equals(nav.children, previousChildren)) {
            batch.update(navigator, "index", previousIndex);
            batch.children(navigator, previousChildren);
        }
        owner.discard(batch, page);
        if (body != null) {
            owner.discard(batch, body);
            batch.delete(root);
            if (modal != 0) batch.delete(modal);
        }
        reset();
        owner.submit(batch);
    }

    void hostRemoved(int id) {
        if (page != null && (id == overview || id == navigator)) owner.main.post(this::back);
    }

    void reset() {
        modal = 0;
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

}
