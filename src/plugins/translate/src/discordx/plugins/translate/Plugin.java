package discordx.plugins.translate;

import discordx.api.PluginContext;
import discordx.api.TextTokens;
import java.util.Locale;

public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        String fallback = Locale.getDefault().getLanguage();
        if (fallback.isEmpty()) fallback = "en";
        final String defaultLanguage = fallback;
        context.ui().settings("Target language", controls ->
            controls.input("Language code", context.preferences().getString("language", defaultLanguage), false,
                value -> context.preferences().edit().putString("language", value.trim()).apply()));
        context.ui().messageAction("Translate", message -> {
            String source = TextTokens.clean(message.content).toString();
            String language = context.preferences().getString("language", defaultLanguage);
            String[] result = {"Translating…"};
            context.ui().open("Translation", controls -> controls.text(result[0]));
            context.runAsync(() -> Translation.translate(source, language), translated -> {
                result[0] = translated;
                context.ui().refresh();
            }, error -> {
                result[0] = error.getMessage() == null ? "Translation failed." : error.getMessage();
                context.ui().refresh();
            });
        });
    }
}
