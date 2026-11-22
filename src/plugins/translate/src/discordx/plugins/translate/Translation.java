package discordx.plugins.translate;

import java.io.IOException;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONException;

final class Translation {
    private Translation() {}

    static String translate(String text, String language) throws IOException {
        if (text.trim().isEmpty() || text.length() > 4000) throw new IOException("Enter between 1 and 4,000 characters.");
        if (!language.matches("[a-z]{2,3}(?:-[A-Za-z]{2,4})?")) throw new IOException("Use a language code such as en, es, or ja.");
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl="
            + URLEncoder.encode(language, "UTF-8") + "&q=" + URLEncoder.encode(text, "UTF-8");
        try { return parse(new JSONArray(PluginHttp.get(url))); }
        catch (JSONException malformed) { throw new IOException("Translation service returned an unsupported response.", malformed); }
    }

    static String parse(JSONArray response) throws JSONException, IOException {
        JSONArray parts = response.getJSONArray(0);
        StringBuilder translated = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONArray part = parts.getJSONArray(i);
            if (!part.isNull(0)) translated.append(part.getString(0));
        }
        if (translated.length() == 0) throw new IOException("No translation was returned.");
        return translated.toString();
    }
}
