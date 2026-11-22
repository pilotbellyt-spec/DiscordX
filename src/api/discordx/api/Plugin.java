package discordx.api;

public interface Plugin {
    void start(PluginContext context) throws Exception;
    default void stop() throws Exception {}
}
