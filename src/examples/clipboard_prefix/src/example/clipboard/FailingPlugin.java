package example.clipboard;

import discordx.api.Hooks;
import discordx.api.PluginContext;

/** Failure fixture for verifying partial startup cleanup and update rollback. */
public final class FailingPlugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        context.register(Hooks.CLIPBOARD, text -> "This registration must be removed");
        context.ui().settings("Incomplete page", page -> page.text("This page must be removed"));
        throw new IllegalStateException("Example startup failure");
    }
}
