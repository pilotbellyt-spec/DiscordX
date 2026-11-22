package discordx.plugins.reduced_motion;

import discordx.api.Hooks;
import discordx.api.PluginContext;
public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) { context.register(Hooks.REDUCE_MOTION, preference -> true); }
}
