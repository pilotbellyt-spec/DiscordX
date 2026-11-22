package discordx;

import discordx.api.PluginUi;
import discordx.api.Registration;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Translates plugin-owned pages into Phoenix modal render operations. */
final class PluginPages {
    private final NativeDiscordUi owner;
    private final ArrayList<Page> pages = new ArrayList<>();
    PluginPages(NativeDiscordUi owner) { this.owner = owner; }

    Registration open(PluginScope scope, String title, PluginUi.Page content) {
        Page page = new Page(scope, content);
        try {
            PhoenixRender.Builder batch = owner.builder();
            NativeControls ui = new NativeControls(owner, batch);
            page.root = ui;
            page.modal = ui.node("modal", "fullscreen", false, "chrome", "guest", "dismissible", true,
                "on", new String[] {"dismiss", "exitEnd"});
            int frame = ui.node("view", "width", "fill", "height", "fill", "direction", "column",
                "justify", "center", "align", "center", "padding", NativeControls.insets(16, 24));
            int width = Math.min(600, Math.max(240, (int) (owner.activity.getResources().getDisplayMetrics().widthPixels
                / owner.activity.getResources().getDisplayMetrics().density) - 32));
            int panel = ui.node("view", "direction", "column", "width", width,
                "background", ui.background, "borderWidth", 1,
                "borderColor", NativeControls.color(owner.isDark() ? 0xff3b3d47 : 0xffdddfe7),
                "radius", 16, "padding", NativeControls.insets(20, 12));
            page.container = ui.column(12, NativeControls.insets(0, 4));
            int heading = ui.node("view", "direction", "row", "align", "center", "gap", 12);
            int titleSlot = ui.node("view", "flex", 1);
            batch.children(titleSlot, ui.label(title, 22, 700, ui.text));
            batch.children(heading, titleSlot, ui.iconButton("Close", "res://ic_m3_chip_close", (event, value) -> close(page)));
            int scroll = ui.node("scroll", "width", "fill", "maxHeight", Math.max(180,
                (int) (owner.activity.getResources().getDisplayMetrics().heightPixels
                    / owner.activity.getResources().getDisplayMetrics().density) - 180));
            batch.children(scroll, page.container);
            batch.children(panel, heading, scroll);
            batch.children(frame, panel);
            batch.children(page.modal, frame);
            ui.actions.put(page.modal, (event, value) -> close(page));
            pages.add(page);
            draw(page, batch);
            owner.submit(batch);
            owner.events.putAll(ui.actions);
            java.lang.ref.WeakReference<PluginPages> host = new java.lang.ref.WeakReference<>(this);
            page.registration = scope.ownUi(() -> { PluginPages current = host.get(); if (current != null) current.close(page); });
            return page.registration;
        } catch (ReflectiveOperationException | RuntimeException error) {
            pages.remove(page);
            throw new IllegalStateException("Could not open plugin page", error);
        }
    }

    private void draw(Page page, PhoenixRender.Builder batch) throws ReflectiveOperationException {
        if (page.body != null) owner.discard(batch, page.body);
        NativeControls ui = new NativeControls(owner, batch);
        ArrayList<Integer> rows = new ArrayList<>();
        page.content.render(new Controls(page, ui, rows, false));
        batch.children(page.container, PhoenixRender.integers(rows));
        page.body = ui;
        owner.events.putAll(ui.actions);
    }

    private final class Controls implements PluginUi.Controls {
        final Page page;
        final NativeControls ui;
        final ArrayList<Integer> children;
        final boolean inRow;
        Controls(Page page, NativeControls ui, ArrayList<Integer> children, boolean inRow) {
            this.page = page; this.ui = ui; this.children = children; this.inRow = inRow;
        }
        private void add(Build action) {
            try { children.add(action.build()); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Could not render plugin control", error); }
        }
        @Override public void text(String text) { add(() -> ui.label(text, 14, 400, ui.text)); }
        @Override public void secondary(String text) { add(() -> ui.label(text, 12, 400, ui.muted)); }
        @Override public void heading(String text) { add(() -> ui.label(text, 16, 650, ui.text)); }
        @Override public void image(String url, String description) {
            add(() -> ui.node("image", "uri", url, "width", 40, "height", 40, "radius", 20, "clip", true,
                "accessibilityLabel", description));
        }
        @Override public void input(String label, String value, boolean multiline, Consumer<String> changed) {
            add(() -> ui.input(label, value, multiline, (event, payload) -> {
                String text = owner.inputText(payload);
                if (text != null) page.scope.guard(() -> changed.accept(text));
            }));
        }
        @Override public void button(String label, Runnable action) {
            add(() -> ui.link(label, (event, payload) -> page.scope.guard(action)));
        }
        @Override public void toggle(String label, boolean checked, Consumer<Boolean> changed) {
            add(() -> {
                int row = ui.node("view", "direction", "row", "align", "center", "gap", 12, "width", "fill");
                int title = ui.node("view", "flex", 1, "direction", "column");
                ui.batch.children(title, ui.label(label, 14, 500, ui.text));
                ui.batch.children(row, title, ui.toggle(label, checked, (event, payload) -> page.scope.guard(() -> changed.accept(!checked))));
                return row;
            });
        }
        @Override public void row(Consumer<PluginUi.Controls> content) { group(content, true, false); }
        @Override public void column(Consumer<PluginUi.Controls> content) { group(content, false, false); }
        @Override public void card(Consumer<PluginUi.Controls> content) { group(content, false, true); }
        private void group(Consumer<PluginUi.Controls> content, boolean row, boolean card) {
            add(() -> {
                int group = inRow && !row
                    ? ui.node("view", "direction", "column", "gap", 6, "flex", 1)
                    : ui.node("view", "direction", row ? "row" : "column", "gap", row ? 8 : 6, "width", "fill");
                if (row) ui.batch.update(group, "align", "center");
                if (card) ui.batch.update(group, "padding", NativeControls.insets(14, 10), "radius", 12, "background", ui.card);
                ArrayList<Integer> nested = new ArrayList<>();
                content.accept(new Controls(page, ui, nested, row));
                ui.batch.children(group, PhoenixRender.integers(nested));
                return group;
            });
        }
        @Override public void divider() {
            add(() -> ui.node("view", "width", "fill", "height", 1,
                "background", NativeControls.color(owner.isDark() ? 0xff3b3d47 : 0xffdddfe7)));
        }
    }

    void refresh(PluginScope scope) {
        for (Page page : new ArrayList<>(pages)) if (page.scope == scope && scope.active) {
            try { PhoenixRender.Builder batch = owner.builder(); draw(page, batch); owner.submit(batch); }
            catch (ReflectiveOperationException | RuntimeException error) { scope.fail(error); }
        }
    }

    void close(PluginScope scope) {
        for (Page page : new ArrayList<>(pages)) if (page.scope == scope) close(page);
    }

    private void close(Page page) {
        if (!pages.remove(page)) return;
        if (page.registration != null) page.registration.close();
        try {
            PhoenixRender.Builder batch = owner.builder();
            if (page.body != null) owner.discard(batch, page.body);
            if (page.root != null) owner.discard(batch, page.root);
            owner.submit(batch);
        } catch (ReflectiveOperationException | RuntimeException error) { owner.failure("Could not close plugin page", error); }
        page.body = null;
        page.root = null;
    }

    boolean overlayEvent(int id) {
        for (Page page : new ArrayList<>(pages)) if (page.modal == id) { close(page); return true; }
        return false;
    }
    void reset() {
        for (Page page : new ArrayList<>(pages)) {
            pages.remove(page);
            page.root = null; page.body = null;
            if (page.registration != null) page.registration.close();
        }
    }

    private interface Build { int build() throws ReflectiveOperationException; }
    private static final class Page {
        final PluginScope scope;
        final PluginUi.Page content;
        NativeControls root, body;
        Registration registration;
        int modal, container;
        Page(PluginScope scope, PluginUi.Page content) { this.scope = scope; this.content = content; }
    }
}
