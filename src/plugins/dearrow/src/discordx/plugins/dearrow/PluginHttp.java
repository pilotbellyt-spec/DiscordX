package discordx.plugins.dearrow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class PluginHttp {
    private PluginHttp() {}

    static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "DiscordX/356207");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("Service returned HTTP " + code);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (output.size() + count > 1024 * 1024) throw new IOException("Service response is too large");
                    output.write(buffer, 0, count);
                }
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }
}
