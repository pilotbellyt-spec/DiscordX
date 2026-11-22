package discordx.api;

public final class Hook<T> {
    private final String name;
    Hook(String name) { this.name = name; }
    @Override public String toString() { return name; }
}
