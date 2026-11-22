package discordx.plugins.quick_jump;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import discordx.api.PluginContext;
public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        String[] input = {""}, status = {""};
        discordx.api.PluginUi.Page screen = page -> {
            page.input("Discord channel, message, or profile link", input[0], false, value -> input[0] = value);
            if (!status[0].isEmpty()) page.text(status[0]);
            page.button("Open in Discord", () -> {
                String url = DiscordLinks.jumpDestination(input[0]);
                if (url == null) status[0] = "Paste a valid Discord channel, message, or profile link.";
                else try {
                    context.activity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(context.application().getPackageName()));
                    context.ui().close();
                    return;
                } catch (ActivityNotFoundException error) { status[0] = "Phoenix could not open this link."; }
                context.ui().refresh();
            });
        };
        context.ui().settings("Open a Discord link", screen);
        context.ui().command("Quick Jump", "Open a Discord channel, message, or profile link.", () -> context.ui().open("Quick Jump", screen));
    }
}
