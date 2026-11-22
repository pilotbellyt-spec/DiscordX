package discordx.api;

public final class PluginInfo {
    public final String id, name, version, description, author, entrypoint, hash;
    public PluginInfo(String id, String name, String version, String description, String author, String entrypoint, String hash) {
        this.id = id; this.name = name; this.version = version; this.description = description;
        this.author = author; this.entrypoint = entrypoint; this.hash = hash;
    }
}
