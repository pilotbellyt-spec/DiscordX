package discordx.plugins.text_replace;

import discordx.api.PluginContext;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Plugin implements discordx.api.Plugin {
    private volatile Map<String,String> rules;
    private String find = "", replacement = "";

    @Override public void start(PluginContext context) {
        rules = new LinkedHashMap<>();
        for (Map.Entry<String,?> item : context.preferences().getAll().entrySet())
            if (!item.getKey().isEmpty() && item.getValue() instanceof String)
                rules.put(item.getKey(), (String)item.getValue());
        context.chat().beforeSend(this::replace);
        context.ui().settings("Replacement rules", page -> {
            for (Map.Entry<String,String> rule : rules.entrySet()) {
                page.text(rule.getKey() + " → " + rule.getValue());
                page.button("Remove " + rule.getKey(), () -> save(context, rule.getKey(), null));
            }
            page.input("Find", find, false, value -> find = value);
            page.input("Replacement", replacement, false, value -> replacement = value);
            page.button("Save rule", () -> {
                if (find.isEmpty()) return;
                String key = find, value = replacement;
                find = replacement = "";
                save(context, key, value);
            });
        });
    }

    private void save(PluginContext context, String key, String value) {
        Map<String,String> next = new LinkedHashMap<>(rules);
        if (value == null) next.remove(key); else next.put(key, value);
        context.preferences().edit().putString(key, value).apply();
        rules = next;
        context.ui().refresh();
    }

    private String replace(String draft) {
        Map<String,String> snapshot = rules;
        if (draft == null || snapshot.isEmpty()) return draft;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < draft.length();) {
            String match = "";
            for (String key : snapshot.keySet())
                if (key.length() > match.length() && draft.startsWith(key, i)) match = key;
            if (match.isEmpty()) result.append(draft.charAt(i++));
            else { result.append(snapshot.get(match)); i += match.length(); }
        }
        return result.toString();
    }
}
