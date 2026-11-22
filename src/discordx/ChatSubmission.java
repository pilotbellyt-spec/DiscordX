package discordx;

import discordx.api.Hooks;

final class ChatSubmission {
    private ChatSubmission() {}
    /** Null consumes the event. Unchanged text follows the original submit path. */
    static String apply(PluginManager manager, String draft) {
        if (draft.startsWith("/")) {
            int end = 1;
            while (end < draft.length() && !Character.isWhitespace(draft.charAt(end))) end++;
            String name = draft.substring(1, end);
            String args = draft.substring(end).trim();
            for (PluginUiBridge.Command command : new java.util.ArrayList<>(manager.commands)) {
                if (command.scope.active && command.name.equals(name)) {
                    command.scope.guard(() -> command.execute.accept(args));
                    return null;
                }
            }
        }
        return manager.hooks.fold(Hooks.BEFORE_SEND, draft, (filter, current) -> current == null ? null : filter.apply(current));
    }
}
