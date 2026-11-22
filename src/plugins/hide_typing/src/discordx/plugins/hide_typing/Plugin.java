package discordx.plugins.hide_typing;

import discordx.api.Hooks;
import discordx.api.PluginContext;
import org.json.JSONException;

public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        context.register(Hooks.GATEWAY_JSON, (connection, dispatch) -> {
            if (dispatch.optInt("op", -1) == 0 && "TYPING_START".equals(dispatch.optString("t"))) {
                // An unhandled dispatch retains the Gateway sequence for resume.
                try { dispatch.put("t", "DISCORDX_HIDDEN_TYPING"); }
                catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            }
            return dispatch;
        });
    }
}
