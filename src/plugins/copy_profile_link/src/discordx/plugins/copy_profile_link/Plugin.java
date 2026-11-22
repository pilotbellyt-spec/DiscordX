package discordx.plugins.copy_profile_link;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import discordx.api.PluginContext;
public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        context.ui().profileAction("Copy Profile Link", user -> {
            ClipboardManager clipboard = (ClipboardManager) context.application().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Discord profile link", "https://discord.com/users/" + user));
        });
    }
}
