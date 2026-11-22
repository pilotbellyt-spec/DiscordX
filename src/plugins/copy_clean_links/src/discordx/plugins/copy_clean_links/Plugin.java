package discordx.plugins.copy_clean_links;

import discordx.api.Hooks;
import discordx.api.PluginContext;
public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        context.register(Hooks.CLIPBOARD, text -> text instanceof String ? CleanLinks.strip((String) text) : text);
    }
}
