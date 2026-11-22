package discordx.plugins.external_browser;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

final class DiscordLinks {
    private static final Pattern JUMP = Pattern.compile(
        "https://(?:www\\.)?discord\\.com/(?:channels/(?:@me|[1-9][0-9]{16,19})/"
            + "[1-9][0-9]{16,19}(?:/[1-9][0-9]{16,19})?|users/[1-9][0-9]{16,19})/?");

    private DiscordLinks() {}

    static String jumpDestination(String input) {
        String candidate = input.trim();
        return JUMP.matcher(candidate).matches() ? candidate : null;
    }

    static boolean canOpenExternally(String input) {
        try {
            URI uri = URI.create(input);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return false;
            if (uri.getHost() == null || uri.getRawUserInfo() != null) return false;
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("/auth") || path.contains("/oauth") || path.contains("/login")
                    || path.contains("/signin") || path.contains("/sso") || path.contains("/callback")) return false;
            String query = uri.getQuery();
            if (query == null) return true;
            query = query.toLowerCase(Locale.ROOT);
            return !query.contains("redirect_uri=") && !query.contains("code_challenge=")
                && !query.contains("token=") && !query.contains("state=") && !query.contains("code=");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
