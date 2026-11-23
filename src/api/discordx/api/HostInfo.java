package discordx.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class HostInfo {
    public static final HostInfo CURRENT = new HostInfo();
    public final int apiVersion = PluginContext.API_VERSION;
    public final int discordBuild = 356207;
    public final Set<String> capabilities;

    private final java.util.Map<String,Integer> operations = discoverBridge();

    /** Returns the Phoenix enqueue operation index, or -1 if absent from this host. */
    public int bridgeOperation(String name) { return operations.getOrDefault(name, -1); }

    private static java.util.Map<String,Integer> discoverBridge() {
        java.util.Map<String,Integer> result = new java.util.HashMap<>();
        try {
            Class<?> host = Class.forName("com.discord.nativeapp.bridge.GuestHost", false, HostInfo.class.getClassLoader());
            for (java.lang.reflect.Field field : host.getDeclaredFields()) {
                if (field.getType() != String[].class || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                String[] names = (String[]) field.get(null);
                if (names == null || names.length == 0 || names[0] == null || !names[0].startsWith("guest.enqueue:")) continue;
                for (int i = 0; i < names.length; i++) {
                    String name = names[i];
                    if (name != null && name.startsWith("guest.enqueue:")) {
                        if (result.put(name, i) != null) result.put(name, -1);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { result.clear(); }
        return result;
    }

    private HostInfo() {
        LinkedHashSet<String> supported = new LinkedHashSet<>();
        for (java.lang.reflect.Field field : Hooks.class.getFields()) {
            if (field.getType() != Hook.class) continue;
            try { supported.add(field.get(null).toString()); }
            catch (IllegalAccessException impossible) { throw new AssertionError(impossible); }
        }
        Collections.addAll(supported, "ui.settings", "ui.profile_actions", "ui.message_actions", "ui.commands", "ui.pages", "ui.layouts", "chat.buttons", "chat.accessories", "chat.commands", "chat.command_options", "chat.local_replies", "chat.local_messages", "chat.cache", "ui.android_modal");
        Collections.addAll(supported, "ui.preference_toggles", "chat.current_user");
        for (String name : operations.keySet()) if (bridgeOperation(name) >= 0) supported.add("bridge." + name);
        capabilities = Collections.unmodifiableSet(supported);
    }
}
