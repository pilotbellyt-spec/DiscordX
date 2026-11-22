package discordx.api;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public interface PluginContext {
    int API_VERSION = 1;
    default HostInfo host() { return HostInfo.CURRENT; }
    String id();
    PluginInfo info();
    String textTokenPrefix();
    Context application();
    Activity activity();
    SharedPreferences preferences();
    PluginUi ui();
    Chat chat();
    <T> Registration register(Hook<T> hook, T callback);
    Registration onCleanup(Runnable cleanup);
    Registration runOnMain(Runnable action);
    <T> Registration runAsync(Callable<T> work, Consumer<T> completed, Consumer<Exception> failed);
    InputStream asset(String path) throws IOException;
}
