package discordx.plugins.external_browser;

import android.content.Intent;
import android.net.Uri;
import discordx.api.Hooks;
import discordx.api.PluginContext;
public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        context.register(Hooks.BROWSER_INTENT, original -> {
            if (!Intent.ACTION_VIEW.equals(original.getAction())) return original;
            Uri uri = original.getData();
            return uri != null && DiscordLinks.canOpenExternally(uri.toString())
                ? new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE) : original;
        });
    }
}
