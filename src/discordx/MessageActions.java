package discordx;

import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import discordx.api.PluginUi;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

final class MessageActions {
    private static final int GROUP = 0x7e4458;
    private static final Map<TextView, WeakReference<Selection>> views = new WeakHashMap<>();
    private MessageActions() {}

    static void attach(PluginManager manager, TextView view) {
        if (manager.messageActions.isEmpty() || MessageIdentity.find(view.getText()) == null) return;
        WeakReference<Selection> existing = views.get(view);
        if (existing != null && existing.get() != null && view.getCustomSelectionActionModeCallback() == existing.get()) return;
        Selection selection = new Selection(manager, view);
        views.put(view, new WeakReference<>(selection));
        if (!view.isTextSelectable()) view.setTextIsSelectable(true);
        view.setCustomSelectionActionModeCallback(selection);
    }

    static void reset(PluginManager manager) {
        for (WeakReference<Selection> reference : new ArrayList<>(views.values())) {
            Selection selection = reference.get();
            if (selection != null && selection.manager == manager) selection.restore();
        }
    }

    private static final class Selection implements ActionMode.Callback {
        final PluginManager manager;
        final WeakReference<TextView> view;
        final ActionMode.Callback original;
        final boolean selectable;
        final Map<Integer, PluginUiBridge.MessageAction> actions = new HashMap<>();
        ActionMode mode;

        Selection(PluginManager manager, TextView view) {
            this.manager = manager; this.view = new WeakReference<>(view);
            original = view.getCustomSelectionActionModeCallback(); selectable = view.isTextSelectable();
        }
        @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            if (original != null && !original.onCreateActionMode(mode, menu)) return false;
            this.mode = mode;
            add(menu);
            return true;
        }
        @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            boolean result = original != null && original.onPrepareActionMode(mode, menu);
            add(menu);
            return result;
        }
        private void add(Menu menu) {
            menu.removeGroup(GROUP); actions.clear();
            TextView text = view.get();
            if (text == null || MessageIdentity.find(text.getText()) == null) return;
            int id = 0x7e450000;
            for (PluginUiBridge.MessageAction action : new ArrayList<>(manager.messageActions)) {
                if (!action.scope.active) continue;
                menu.add(GROUP, id, Menu.NONE, action.title);
                actions.put(id++, action);
            }
        }
        @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            PluginUiBridge.MessageAction action = actions.get(item.getItemId());
            if (action == null) return original != null && original.onActionItemClicked(mode, item);
            TextView text = view.get();
            PluginUi.Message message = text == null ? null : MessageIdentity.find(text.getText());
            if (message != null) {
                int start = text.getSelectionStart(), end = text.getSelectionEnd();
                String selected = start >= 0 && end > start && end <= text.length() ? text.getText().subSequence(start, end).toString() : message.content;
                PluginUi.Message value = new PluginUi.Message(message.id, message.channelId, message.authorId, message.content, selected);
                action.scope.guard(() -> action.action.accept(value));
            }
            if (mode != null) mode.finish();
            return true;
        }
        @Override public void onDestroyActionMode(ActionMode mode) {
            actions.clear(); this.mode = null;
            if (original != null) original.onDestroyActionMode(mode);
        }
        void restore() {
            TextView text = view.get();
            if (mode != null) mode.finish();
            actions.clear();
            if (text != null && text.getCustomSelectionActionModeCallback() == this) {
                text.setCustomSelectionActionModeCallback(original);
                text.setTextIsSelectable(selectable);
                views.remove(text);
            }
        }
    }
}
