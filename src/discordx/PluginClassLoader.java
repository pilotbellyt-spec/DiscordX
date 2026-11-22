package discordx;

import dalvik.system.InMemoryDexClassLoader;
import discordx.api.Plugin;
import java.nio.ByteBuffer;

/** Only Android platform classes and the public API are shared with plugin code. */
final class PluginClassLoader extends ClassLoader {
    private PluginClassLoader() { super(null); }
    static ClassLoader open(byte[] dex) {
        return new InMemoryDexClassLoader(ByteBuffer.wrap(dex), new PluginClassLoader());
    }
    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("discordx.api.")) return Plugin.class.getClassLoader().loadClass(name);
        return Class.forName(name, false, null);
    }
}
