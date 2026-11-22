package discordx.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class HostInfo {
    public static final HostInfo CURRENT = new HostInfo();
    public final int apiVersion = PluginContext.API_VERSION;
    public final int discordBuild = 356207;
    public final Set<String> capabilities;

    private HostInfo() {
        LinkedHashSet<String> supported = new LinkedHashSet<>();
        for (java.lang.reflect.Field field : Hooks.class.getFields()) {
            if (field.getType() != Hook.class) continue;
            try { supported.add(field.get(null).toString()); }
            catch (IllegalAccessException impossible) { throw new AssertionError(impossible); }
        }
        Collections.addAll(supported, "ui.settings", "ui.profile_actions", "ui.message_actions", "ui.commands", "ui.pages", "ui.layouts", "chat.buttons", "chat.accessories", "chat.commands", "chat.command_options", "chat.local_replies", "chat.local_messages", "chat.cache", "ui.android_modal");
        capabilities = Collections.unmodifiableSet(supported);
    }
}
