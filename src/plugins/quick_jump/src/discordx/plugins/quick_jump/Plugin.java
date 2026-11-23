package discordx.plugins.quick_jump;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import discordx.api.Hooks;
import discordx.api.PluginContext;

public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        context.register(Hooks.HTTP_BODY, QuickJumpCatalog::append);
        context.register(Hooks.LOCAL_COMMAND, (name, options) -> {
            if (!"quick-jump".equals(name) || !options.startsWith("link:")) return;
            String value = options.substring("link:".length());
            context.runOnMain(() -> {
                String error = open(context, value);
                if (error != null) Toast.makeText(context.application(), error, Toast.LENGTH_SHORT).show();
            });
        });
        String[] input = {""}, status = {""};
        discordx.api.PluginUi.Page screen = page -> {
            page.input("Discord channel, message, or profile link", input[0], false, value -> input[0] = value);
            if (!status[0].isEmpty()) page.text(status[0]);
            page.button("Open in Discord", () -> {
                status[0] = open(context, input[0]);
                if (status[0] == null) {
                    status[0] = "";
                    context.ui().close();
                    return;
                }
                context.ui().refresh();
            });
        };
        context.ui().settings("Open a Discord link", screen);
    }

    private static String open(PluginContext context, String link) {
        String url = DiscordLinks.jumpDestination(link);
        if (url == null) return "Paste a valid Discord channel, message, or profile link.";
        if (context.activity() == null) return "Open Discord to use Quick Jump.";
        try {
            context.activity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage(context.application().getPackageName()));
            return null;
        } catch (ActivityNotFoundException unavailable) {
            return "Phoenix could not open this link.";
        }
    }
}
