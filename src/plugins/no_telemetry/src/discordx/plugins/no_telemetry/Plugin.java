package discordx.plugins.no_telemetry;

import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.net.URI;
import java.util.regex.Pattern;

public final class Plugin implements discordx.api.Plugin {
    private static final Pattern COLLECTION = Pattern.compile("/api/(?:v[0-9]+/)?(?:science|track)/?");
    private static final Hooks.Reply EMPTY = new Hooks.Reply(204, "application/json", new byte[0]);

    @Override public void start(PluginContext context) {
        context.register(Hooks.HTTP_REQUEST, request -> blocks(request.method, request.url) ? EMPTY : null);
    }

    static boolean blocks(String method, String url) {
        if (!"POST".equals(method) || url == null) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost(), path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || path == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.equals("discord.com") || host.equals("canary.discord.com") || host.equals("ptb.discord.com"))
                return COLLECTION.matcher(path).matches();
            return (host.equals("sentry.io") || host.endsWith(".ingest.sentry.io") || host.equals("sentry.discord.com")
                || host.equals("release-errmon.discord.com")) && path.matches("/api/[0-9]+/(envelope|store)/?");
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
