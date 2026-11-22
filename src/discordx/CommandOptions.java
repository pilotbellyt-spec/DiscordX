package discordx;

import discordx.api.Chat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class CommandOptions {
    private final PluginScope scope;
    private final String name;
    private final List<Chat.Option<?>> options;
    private final Consumer<Chat.Arguments> execute;

    CommandOptions(PluginScope scope, String name, List<Chat.Option<?>> options, Consumer<Chat.Arguments> execute) {
        this.scope = scope;
        this.name = name;
        this.options = new ArrayList<>(options);
        this.execute = Objects.requireNonNull(execute);
        HashSet<String> names = new HashSet<>();
        for (Chat.Option<?> option : this.options)
            if (!names.add(option.name)) throw new IllegalArgumentException("Duplicate option: " + option.name);
    }

    void submit(String draft) {
        String[] fields = new String[options.size()];
        java.util.Arrays.fill(fields, "");
        if (fields.length > 0) fields[0] = draft;
        String[] feedback = {""};
        if (fields.length == 0 || fields.length == 1 && !draft.isEmpty()) {
            Chat.Arguments args = parse(fields, feedback);
            if (args != null) { execute.accept(args); return; }
        }
        scope.ui().open("/" + name, page -> {
            for (int i = 0; i < fields.length; i++) {
                int index = i;
                page.input(options.get(i).name, fields[i], false, value -> fields[index] = value);
            }
            if (!feedback[0].isEmpty()) page.text(feedback[0]);
            page.button("Run", () -> {
                Chat.Arguments args = parse(fields, feedback);
                if (args == null) { scope.ui().refresh(); return; }
                scope.ui().close();
                execute.accept(args);
            });
        });
    }

    private Chat.Arguments parse(String[] fields, String[] feedback) {
        Chat.Arguments result = new Chat.Arguments();
        try {
            for (int i = 0; i < fields.length; i++) parseField(result, options.get(i), fields[i].trim());
            return result;
        } catch (IllegalArgumentException error) {
            feedback[0] = error.getMessage() == null ? "Invalid option" : error.getMessage();
            return null;
        }
    }

    private static <T> void parseField(Chat.Arguments result, Chat.Option<T> option, String value) {
        if (value.isEmpty()) {
            if (option.required) throw new IllegalArgumentException(option.name + " is required");
            return;
        }
        result.put(option, Objects.requireNonNull(option.parse.apply(value)));
    }
}
