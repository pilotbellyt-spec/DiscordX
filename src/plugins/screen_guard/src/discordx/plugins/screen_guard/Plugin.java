package discordx.plugins.screen_guard;

import android.app.Activity;
import android.view.WindowManager;
import discordx.api.Hooks;
import discordx.api.PluginContext;
public final class Plugin implements discordx.api.Plugin {
    private PluginContext context;
    @Override public void start(PluginContext context) {
        this.context = context;
        context.register(Hooks.ACTIVITY, new Hooks.ActivityEvents() {
            @Override public void attached(Activity activity) { activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); }
            @Override public void detached(Activity activity) { activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE); }
        });
    }
    @Override public void stop() {
        Activity activity = context.activity();
        if (activity != null) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        context = null;
    }
}
