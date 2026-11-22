package discordx.plugins.clean_links;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Locale;

final class CleanLinks {
    private CleanLinks() {}

    static String strip(String url) {
        try {
            URI parsed = URI.create(url);
            String scheme = parsed.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return url;
            String query = parsed.getRawQuery();
            if (query == null || parsed.getHost() == null || parsed.getRawUserInfo() != null) return url;
            String path = parsed.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("/oauth") || path.contains("/auth/")) return url;
            String[] parameters = query.split("&", -1);
            ArrayList<String> kept = new ArrayList<>();
            for (String part : parameters) {
                int equals = part.indexOf('=');
                String name = URLDecoder.decode(equals < 0 ? part : part.substring(0, equals), "UTF-8")
                    .toLowerCase(Locale.ROOT);
                if (sensitive(name)) return url;
                if (!name.startsWith("utm_") && !name.equals("fbclid") && !name.equals("gclid")
                        && !name.equals("dclid") && !name.equals("mc_cid") && !name.equals("mc_eid")) {
                    kept.add(part);
                }
            }
            if (kept.size() == parameters.length) return url;
            int start = url.indexOf('?');
            int end = start + 1 + query.length();
            StringBuilder result = new StringBuilder(url.substring(0, start));
            String delimiter = "?";
            for (String part : kept) {
                result.append(delimiter).append(part);
                delimiter = "&";
            }
            return result.append(url.substring(end)).toString();
        } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
            return url;
        }
    }

    private static boolean sensitive(String name) {
        return name.equals("sig") || name.contains("signature") || name.contains("token")
            || name.equals("code") || name.equals("state") || name.equals("ex")
            || name.equals("hm") || name.equals("expires") || name.equals("policy")
            || name.equals("key-pair-id") || name.startsWith("x-amz-") || name.startsWith("x-goog-");
    }
}
