package discordx;

final class Ids {
    private Ids() {}
    static boolean snowflake(String value) { return value != null && value.matches("[1-9][0-9]{16,19}"); }
}
