package discordx;

import discordx.api.Hook;
import discordx.api.Registration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;

final class HookRegistry {
    private final Map<Hook<?>, CopyOnWriteArrayList<Binding<?>>> bindings = new ConcurrentHashMap<>();

    boolean has(Hook<?> hook) {
        CopyOnWriteArrayList<Binding<?>> list = bindings.get(hook);
        if (list == null) return false;
        for (Binding<?> binding : list) if (binding.scope.active) return true;
        return false;
    }

    <T> Registration add(PluginScope scope, Hook<T> hook, T callback) {
        CopyOnWriteArrayList<Binding<?>> list = bindings.computeIfAbsent(hook, ignored -> new CopyOnWriteArrayList<>());
        Binding<T> binding = new Binding<>(scope, callback);
        list.add(binding);
        list.sort((left, right) -> left.scope.id().compareTo(right.scope.id()));
        return () -> list.remove(binding);
    }

    <T, R> R fold(Hook<T> hook, R value, BiFunction<T, R, R> invoke) {
        return foldOwned(hook, value, (scope, callback, current) -> invoke.apply(callback, current));
    }

    interface ScopedFold<T,R> { R apply(PluginScope scope, T callback, R current); }

    <T,R> R foldOwned(Hook<T> hook, R value, ScopedFold<T,R> invoke) {
        CopyOnWriteArrayList<Binding<?>> list = bindings.get(hook);
        if (list == null) return value;
        for (Binding<?> raw : list) {
            if (!raw.scope.active) continue;
            @SuppressWarnings("unchecked") Binding<T> binding = (Binding<T>) raw;
            try { value = invoke.apply(binding.scope, binding.callback, value); }
            catch (Throwable error) { binding.scope.fail(error); }
        }
        return value;
    }

    <T> void each(Hook<T> hook, Consumer<T> invoke) {
        fold(hook, Boolean.TRUE, (callback, value) -> { invoke.accept(callback); return value; });
    }

    <T> void eachOwned(PluginScope scope, Hook<T> hook, Consumer<T> invoke) {
        CopyOnWriteArrayList<Binding<?>> list = bindings.get(hook);
        if (list == null) return;
        for (Binding<?> raw : list) if (raw.scope == scope && scope.active) {
            @SuppressWarnings("unchecked") T callback = (T) raw.callback;
            try { invoke.accept(callback); }
            catch (Throwable error) { scope.fail(error); }
        }
    }

    private static final class Binding<T> {
        final PluginScope scope;
        final T callback;
        Binding(PluginScope scope, T callback) { this.scope = scope; this.callback = callback; }
    }
}
