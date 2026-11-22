package discordx;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import discordx.api.Hooks;

public final class DiscordX {
    static PluginManager manager;
    private DiscordX() {}
    static void prepare(Activity activity) {
        if (manager == null) manager = new PluginManager(activity);
    }
    public static Uri cleanLink(android.content.Context context, Uri uri) {
        return manager.hooks.fold(Hooks.OPEN_LINK, uri, (filter, value) -> filter.apply(value));
    }
    public static Intent browserIntent(Intent intent) {
        return manager.hooks.fold(Hooks.BROWSER_INTENT, intent, (filter, value) -> filter.apply(value));
    }
    public static CharSequence cleanCopiedText(CharSequence text) {
        CharSequence clean = cleanMessageText(text);
        return manager.hooks.fold(Hooks.CLIPBOARD, clean, (filter, value) -> filter.apply(value));
    }
    static CharSequence cleanMessageText(CharSequence text) {
        return discordx.api.TextTokens.clean(manager.hooks.fold(Hooks.COMPOSER_TEXT, text, (filter, value) -> filter.apply(value)));
    }
    static CharSequence displayText(CharSequence text) {
        return MessageIdentity.decorate(text, discordx.api.TextTokens.clean(manager.hooks.fold(Hooks.DISPLAY_TEXT, text, (filter, value) -> filter.apply(value))));
    }
    public static boolean reduceMotion(boolean preference) {
        return preference || manager.hooks.fold(Hooks.REDUCE_MOTION, preference, (filter, value) -> filter.apply(value));
    }
    static int fill(int color) { return manager.hooks.fold(Hooks.FILL_COLOR, color, (filter, value) -> filter.applyAsInt(value)); }
    static int foreground(int color) { return manager.hooks.fold(Hooks.FOREGROUND_COLOR, color, (filter, value) -> filter.applyAsInt(value)); }
    static int[] gradient(int[] colors) { return manager.hooks.fold(Hooks.GRADIENT, colors, (filter, value) -> filter.apply(value.clone())); }
    public static void themeFill(GradientDrawable view, int color) { view.setColor(fill(color)); }
    public static void themeRoot(View view, int color) { view.setBackgroundColor(fill(color)); }
    public static void themeText(TextView view) {
        MessageActions.attach(manager, view);
        int original = view.getCurrentTextColor();
        int mapped = manager.hooks.fold(Hooks.TEXT_COLOR, original, (filter, value) -> filter.applyAsInt(value));
        if (mapped != original) view.setTextColor(mapped);
    }
}
