package discordx.plugins.custom_themes;

import android.content.SharedPreferences;
import android.graphics.Color;
import discordx.api.Hooks;
import discordx.api.PluginContext;
import java.util.Locale;

public final class Plugin implements discordx.api.Plugin {
    private PluginContext context;
    @Override public void start(PluginContext context) {
        this.context = context;
        SharedPreferences prefs = context.preferences();
        ThemePalette.set(new ThemePalette(prefs.getInt("theme_surface", ThemePalette.DEFAULT_SURFACE),
            prefs.getInt("theme_text", ThemePalette.DEFAULT_TEXT), prefs.getInt("theme_accent", ThemePalette.DEFAULT_ACCENT)));
        context.register(Hooks.DEFAULT_PROFILE_COLORS, () -> {
            if (!prefs.getBoolean("theme_default_profiles", true)) return null;
            int color = prefs.getInt("theme_surface", ThemePalette.DEFAULT_SURFACE) & 0xffffff;
            return new int[] {color, color};
        });
        context.register(Hooks.FILL_COLOR, ThemePalette::fill);
        context.register(Hooks.TEXT_COLOR, ThemePalette::baseText);
        context.register(Hooks.FOREGROUND_COLOR, ThemePalette::foreground);
        context.register(Hooks.GRADIENT, ThemePalette::gradient);
        String[] values = {hex(prefs.getInt("theme_surface", ThemePalette.DEFAULT_SURFACE)),
            hex(prefs.getInt("theme_text", ThemePalette.DEFAULT_TEXT)), hex(prefs.getInt("theme_accent", ThemePalette.DEFAULT_ACCENT))};
        String[] labels = {"Background", "Text", "Accent"}, status = {""};
        context.ui().settings("Edit palette", page -> {
            page.preferenceToggle("Theme default profiles", "theme_default_profiles", true);
            for (int i = 0; i < labels.length; i++) {
                final int slot = i;
                page.input(labels[i], values[i], false, value -> values[slot] = value);
            }
            if (!status[0].isEmpty()) page.text(status[0]);
            page.button("Apply palette", () -> {
                int[] colors = new int[3];
                for (int i = 0; i < colors.length; i++) {
                    if (!values[i].trim().matches("#[0-9a-fA-F]{6}")) {
                        status[0] = "Use six-digit hex colors such as #19252C.";
                        context.ui().refresh();
                        return;
                    }
                    colors[i] = Color.parseColor(values[i].trim());
                }
                if (!ThemePalette.readable(colors[1], colors[0]) || !ThemePalette.readable(colors[2], colors[0])) {
                    status[0] = "Choose text and accent colors with more contrast.";
                    context.ui().refresh();
                    return;
                }
                prefs.edit().putInt("theme_surface", colors[0]).putInt("theme_text", colors[1]).putInt("theme_accent", colors[2]).apply();
                ThemePalette.set(new ThemePalette(colors[0], colors[1], colors[2]));
                context.activity().recreate();
            });
        });
    }
    @Override public void stop() {
        ThemePalette.set(null);
        if (context.activity() != null) context.activity().recreate();
        context = null;
    }
    private static String hex(int value) { return String.format(Locale.ROOT, "#%06X", value & 0xffffff); }
}
