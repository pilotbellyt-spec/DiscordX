package discordx.plugins.hide_typing;

import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.util.regex.Pattern;

public final class Plugin implements discordx.api.Plugin {
    private static final String API = "https://(?:(?:canary|ptb)\\.)?discord\\.com/api/v[0-9]+/";
    private static final Pattern TYPING = Pattern.compile(API + "channels/[1-9][0-9]{16,19}/typing(?:\\?[^#]*)?");
    @Override public void start(PluginContext context) {
        context.register(Hooks.HTTP_REQUEST, request ->
            "POST".equals(request.method) && request.url != null && TYPING.matcher(request.url).matches()
                ? new Hooks.Reply(204, null, new byte[0]) : null);
    }
}
