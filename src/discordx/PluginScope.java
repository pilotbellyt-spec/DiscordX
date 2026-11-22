package discordx;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import discordx.api.Hook;
import discordx.api.PluginContext;
import discordx.api.PluginUi;
import discordx.api.Registration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class PluginScope implements PluginContext {
    final PluginManager manager;
    final PluginManager.Entry entry;
    volatile boolean active = true;
    private final ArrayList<Registration> registrations = new ArrayList<>();
    private final PluginUi ui;
    final java.util.Map<String, byte[]> assets = new java.util.concurrent.ConcurrentHashMap<>();
    private final String textPrefix = "DXP" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    PluginScope(PluginManager manager, PluginManager.Entry entry) {
        this.manager = manager;
        this.entry = entry;
        ui = new PluginUiBridge(this);
    }

    @Override public String id() { return entry.pack.id; }
    @Override public discordx.api.PluginInfo info() { return entry.pack; }
    @Override public String textTokenPrefix() { return textPrefix; }
    @Override public Context application() { return manager.application; }
    @Override public Activity activity() { return manager.activity; }
    @Override public SharedPreferences preferences() { return manager.preferences(id()); }
    @Override public discordx.api.Chat chat() { return new ChatBridge(this); }
    @Override public PluginUi ui() { return ui; }

    @Override public <T> Registration register(Hook<T> hook, T callback) {
        requireActive();
        if (hook == null || callback == null) throw new IllegalArgumentException("Hook and callback are required");
        return own(manager.hooks.add(this, hook, callback));
    }

    Registration own(Registration registration) {
        AtomicBoolean closed = new AtomicBoolean();
        Registration tracked = new Registration() {
            @Override public void close() {
                if (!closed.compareAndSet(false, true)) return;
                synchronized (registrations) { registrations.remove(this); }
                registration.close();
            }
        };
        synchronized (registrations) {
            if (active) registrations.add(tracked);
            else tracked.close();
        }
        return tracked;
    }

    Registration ownUi(Registration registration) {
        Registration tracked = own(registration);
        return () -> {
            if (android.os.Looper.myLooper() == manager.main.getLooper()) tracked.close();
            else manager.main.post(tracked::close);
        };
    }

    @Override public Registration onCleanup(Runnable cleanup) { return own(cleanup::run); }

    @Override public Registration runOnMain(Runnable action) {
        requireActive();
        AtomicBoolean cancelled = new AtomicBoolean();
        Registration registration = own(() -> cancelled.set(true));
        manager.main.post(() -> {
            try { if (active && !cancelled.get()) guard(action); }
            finally { registration.close(); }
        });
        return registration;
    }

    @Override public <T> Registration runAsync(Callable<T> work, Consumer<T> completed, Consumer<Exception> failed) {
        requireActive();
        ClassLoader pluginLoader = entry.plugin.getClass().getClassLoader();
        java.util.concurrent.atomic.AtomicReference<Registration> handle = new java.util.concurrent.atomic.AtomicReference<>();
        FutureTask<Void> task = new FutureTask<Void>(() -> {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(pluginLoader);
                T result = work.call();
                manager.main.post(() -> { if (active) guard(() -> completed.accept(result)); });
            } catch (Exception error) {
                manager.main.post(() -> { if (active) guard(() -> failed.accept(error)); });
            } catch (Throwable error) {
                fail(error);
            } finally {
                thread.setContextClassLoader(previous);
            }
            return null;
        }) {
            @Override protected void done() { Registration registration = handle.get(); if (registration != null) registration.close(); }
        };
        Registration registration = own(() -> { task.cancel(true); manager.background.remove(task); });
        handle.set(registration);
        try { manager.background.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException busy) { registration.close(); throw busy; }
        return registration;
    }

    @Override public InputStream asset(String path) throws IOException {
        requireActive();
        if (!PluginPackage.safePath(path)) throw new IOException("Invalid asset path");
        byte[] bytes = assets.get("assets/" + path);
        if (bytes == null) throw new IOException("Plugin asset not found: " + path);
        return new ByteArrayInputStream(bytes);
    }

    void guard(Runnable action) {
        if (!active) return;
        try { action.run(); }
        catch (Throwable error) { fail(error); }
    }

    void fail(Throwable error) {
        if (error instanceof VirtualMachineError || error instanceof ThreadDeath) throw (Error) error;
        if (!active) return;
        active = false;
        manager.main.post(() -> manager.failed(entry, this, error));
    }

    void close() {
        active = false;
        Registration[] pending;
        synchronized (registrations) { pending = registrations.toArray(new Registration[0]); registrations.clear(); }
        for (int i = pending.length - 1; i >= 0; i--) {
            try { pending[i].close(); }
            catch (Throwable error) { Log.e("DiscordX", "Plugin cleanup failed: " + id(), error); }
        }
        assets.clear();
    }

    void requireActive() {
        if (!active) throw new IllegalStateException("Plugin is stopped");
    }
}
