package discordx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public final class DiscordX {
    private static final String LOG = "DiscordX";
    static final String SETTINGS = "discordx_plugins";
    private static final Plugin REVIEW_DB = new Plugin(
        "review_db", "ReviewDB", "Read public reviews on Discord profiles."
    );
    private static final Plugin CLEAN_LINKS = new Plugin(
        "clean_links", "Clean Links", "Remove common tracking tags when Discord opens a web link."
    );
    private static final Plugin MESSAGE_LOGGER = new Plugin(
        "message_logger", "MessageLogger", "Show earlier versions and retained deleted messages in chat for this session."
    );
    private static final Plugin CUSTOM_THEMES = new Plugin(
        "custom_themes", "Custom Themes", "Apply your own background, text, and accent colors in Phoenix."
    );
    private static final Plugin COPY_CLEAN_LINKS = new Plugin(
        "copy_clean_links", "Clean Copied Links", "Remove trackers from web URLs you copy in Phoenix."
    );
    private static final Plugin EXTERNAL_BROWSER = new Plugin(
        "external_browser", "Open in Browser", "Use your default browser for ordinary links instead of an in-app tab."
    );
    private static final Plugin REDUCED_MOTION = new Plugin(
        "reduced_motion", "Reduce Motion", "Ask Phoenix to reduce animations. Reopen Discord if needed."
    );
    private static final Plugin QUICK_JUMP = new Plugin(
        "quick_jump", "Quick Jump", "Open Discord links to channels, messages, or profiles inside Phoenix."
    );
    private static final Plugin SCREEN_GUARD = new Plugin(
        "screen_guard", "Screen Share Privacy", "Hide the Discord window from screenshots and screen sharing."
    );
    private static final Plugin COPY_PROFILE_LINK = new Plugin(
        "copy_profile_link", "Copy Profile Link", "Copy a shareable link from the profile you’re viewing."
    );
    static final Plugin[] PLUGINS = { MESSAGE_LOGGER, CUSTOM_THEMES, REVIEW_DB, CLEAN_LINKS,
        COPY_CLEAN_LINKS, EXTERNAL_BROWSER, REDUCED_MOTION, QUICK_JUMP, SCREEN_GUARD, COPY_PROFILE_LINK };
    private static volatile boolean cleanCopies, useBrowser, forceReducedMotion;

    private DiscordX() {}

    public static void prepare(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        MessageLogger.setEnabled(prefs.getBoolean(MESSAGE_LOGGER.id, false));
        ProfileIdentity.setEnabled(prefs.getBoolean(REVIEW_DB.id, false) || prefs.getBoolean(COPY_PROFILE_LINK.id, false));
        ThemePalette.set(prefs.getBoolean(CUSTOM_THEMES.id, false) ? savedPalette(prefs) : null);
        readHookPreferences(prefs);
    }

    private static void readHookPreferences(SharedPreferences prefs) {
        cleanCopies = prefs.getBoolean(COPY_CLEAN_LINKS.id, false);
        useBrowser = prefs.getBoolean(EXTERNAL_BROWSER.id, false);
        forceReducedMotion = prefs.getBoolean(REDUCED_MOTION.id, false);
    }

    static ThemePalette savedPalette(SharedPreferences prefs) {
        return new ThemePalette(prefs.getInt("theme_surface", ThemePalette.DEFAULT_SURFACE),
            prefs.getInt("theme_text", ThemePalette.DEFAULT_TEXT),
            prefs.getInt("theme_accent", ThemePalette.DEFAULT_ACCENT));
    }

    public static void themeFill(GradientDrawable view, int color) {
        view.setColor(ThemePalette.fill(color));
    }

    static boolean enabled(Activity activity, String plugin) {
        return activity.getSharedPreferences(SETTINGS, Activity.MODE_PRIVATE).getBoolean(plugin, false);
    }

    static void applyWindowPreferences(Activity activity) {
        if (enabled(activity, SCREEN_GUARD.id)) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    static void changePlugin(Activity activity, Plugin plugin, boolean enabled) {
        SharedPreferences prefs = activity.getSharedPreferences(SETTINGS, Activity.MODE_PRIVATE);
        prefs.edit().putBoolean(plugin.id, enabled).apply();
        readHookPreferences(prefs);
        if (plugin == MESSAGE_LOGGER) MessageLogger.setEnabled(enabled);
        if (plugin == REVIEW_DB || plugin == COPY_PROFILE_LINK) {
            ProfileIdentity.setEnabled(prefs.getBoolean(REVIEW_DB.id, false) || prefs.getBoolean(COPY_PROFILE_LINK.id, false));
        }
        if (plugin == CUSTOM_THEMES && !enabled) {
            ThemePalette.set(null);
            activity.recreate();
        }
        if (plugin == SCREEN_GUARD) applyWindowPreferences(activity);
        Log.i(LOG, plugin.id + "=" + enabled);
    }

    public static void themeRoot(View view, int color) {
        view.setBackgroundColor(ThemePalette.fill(color));
    }

    public static void themeText(TextView view) {
        int original = view.getCurrentTextColor();
        int mapped = ThemePalette.baseText(original);
        if (original != mapped) view.setTextColor(mapped);
    }

    public static Uri cleanLink(Context context, Uri uri) {
        if (uri == null || !context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(CLEAN_LINKS.id, false)) return uri;
        String original = uri.toString();
        String clean = CleanLinks.strip(original);
        return clean.equals(original) ? uri : Uri.parse(clean);
    }

    public static CharSequence cleanCopiedText(CharSequence text) {
        CharSequence cleaned = MessageHistoryText.clean(text);
        return cleanCopies && cleaned instanceof String ? CleanLinks.strip((String) cleaned) : cleaned;
    }

    public static Intent browserIntent(Intent original) {
        if (!useBrowser || !Intent.ACTION_VIEW.equals(original.getAction())) return original;
        Uri uri = original.getData();
        if (uri == null || !DiscordLinks.canOpenExternally(uri.toString())) return original;
        return new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
    }

    public static boolean reduceMotion(boolean systemPreference) {
        return systemPreference || forceReducedMotion;
    }

    static final class Plugin {
        final String id;
        final String title;
        final String description;
        Plugin(String id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }

    }
}
