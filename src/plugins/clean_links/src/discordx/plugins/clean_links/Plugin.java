package discordx.plugins.clean_links;

import android.net.Uri;
import discordx.api.Hooks;
import discordx.api.PluginContext;
public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        context.register(Hooks.OPEN_LINK, uri -> uri == null ? null : Uri.parse(CleanLinks.strip(uri.toString())));
    }
}
