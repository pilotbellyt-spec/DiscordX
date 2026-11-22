package discordx;

import discordx.api.PluginUi;
import discordx.api.Registration;
import java.util.function.Consumer;

final class PluginUiBridge implements PluginUi {
    private final PluginScope scope;
    PluginUiBridge(PluginScope scope) { this.scope = scope; }
    private void checkUi() {
        scope.requireActive();
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) throw new IllegalStateException("Use runOnMain for plugin UI changes");
    }


    @Override public Registration settings(String title, Page page) {
        checkUi();
        Setting setting = new Setting(scope, title, page);
        scope.manager.settings.add(setting);
        scope.manager.changed();
        return scope.ownUi(() -> { scope.manager.settings.remove(setting); scope.manager.changed(); });
    }

    @Override public Registration profileAction(String title, Consumer<String> action) {
        checkUi();
        ProfileAction profile = new ProfileAction(scope, title, action);
        scope.manager.profiles.add(profile);
        scope.manager.changed();
        return scope.ownUi(() -> { scope.manager.profiles.remove(profile); scope.manager.changed(); });
    }

    @Override public Registration open(String title, Page page) {
        checkUi();
        NativeDiscordUi ui = scope.manager.ui;
        if (ui == null) throw new IllegalStateException("Phoenix UI is not attached");
        return ui.pluginPages.open(scope, title, page);
    }

    @Override public Registration messageAction(String title, Consumer<Message> action) {
        checkUi();
        MessageAction item = new MessageAction(scope, title, action);
        scope.manager.messageActions.add(item);
        scope.manager.changed();
        return scope.ownUi(() -> { scope.manager.messageActions.remove(item); scope.manager.changed(); });
    }
    @Override public Registration command(String name, String description, Runnable action) {
        java.util.Objects.requireNonNull(action);
        return scope.chat().slashCommand(name, description, args -> action.run());
    }

    @Override public void refresh() {
        checkUi();
        if (scope.manager.ui != null) scope.manager.ui.pluginPages.refresh(scope);
    }
    @Override public Registration androidModal(android.widget.LinearLayout content) {
        checkUi();
        android.app.Activity activity = scope.activity();
        PhoenixModal modal = PhoenixModal.open(activity, content);
        if (modal == null) throw new IllegalStateException("Cannot open Android content in Phoenix");
        java.util.ArrayList<Registration> modals = scope.manager.nativeModals.computeIfAbsent(activity, ignored -> new java.util.ArrayList<>());
        java.util.concurrent.atomic.AtomicReference<Registration> handle = new java.util.concurrent.atomic.AtomicReference<>();
        android.view.View.OnAttachStateChangeListener listener = new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View view) {}
            @Override public void onViewDetachedFromWindow(android.view.View view) { Registration registration = handle.get(); if (registration != null) registration.close(); }
        };
        Registration registration = scope.ownUi(() -> {
            modals.remove(handle.get());
            content.removeOnAttachStateChangeListener(listener);
            modal.close();
        });
        handle.set(registration);
        modals.add(registration);
        content.addOnAttachStateChangeListener(listener);
        return registration;
    }
    @Override public void close() {
        checkUi();
        if (scope.manager.ui != null) scope.manager.ui.pluginPages.close(scope);
    }

    static final class Setting {
        final PluginScope scope;
        final String title;
        final Page page;
        Setting(PluginScope scope, String title, Page page) { this.scope = scope; this.title = title; this.page = page; }
    }
    static final class ProfileAction {
        final PluginScope scope;
        final String title;
        final Consumer<String> action;
        ProfileAction(PluginScope scope, String title, Consumer<String> action) { this.scope = scope; this.title = title; this.action = action; }
    }
    static final class MessageAction {
        final PluginScope scope;
        final String title;
        final Consumer<Message> action;
        MessageAction(PluginScope scope, String title, Consumer<Message> action) { this.scope = scope; this.title = title; this.action = action; }
    }
    static final class Command {
        final PluginScope scope;
        final String name, description;
        final Runnable action;
        final Consumer<String> execute;
        Command(PluginScope scope, String name, String description, Consumer<String> execute) {
            this.scope = scope; this.name = name; this.description = description;
            this.execute = execute; this.action = () -> execute.accept("");
        }
    }
}
