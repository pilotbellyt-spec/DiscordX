package discordx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import discordx.api.Hooks;
import discordx.api.Plugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;

final class PluginManager {
    static final int IMPORT_REQUEST = 0x4458;
    final Context application;
    final Handler main = new Handler(Looper.getMainLooper());
    final java.util.concurrent.ThreadPoolExecutor background = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0L,
        java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64));
    final HookRegistry hooks = new HookRegistry();
    final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    final ArrayList<PluginUiBridge.Setting> settings = new ArrayList<>();
    final ArrayList<PluginUiBridge.ProfileAction> profiles = new ArrayList<>();
    final ArrayList<PluginUiBridge.MessageAction> messageActions = new ArrayList<>();
    final ArrayList<PluginUiBridge.Command> commands = new ArrayList<>();
    final ArrayList<ChatBridge.ButtonItem> chatButtons = new ArrayList<>();
    final ArrayList<ChatBridge.Accessory> accessories = new ArrayList<>();
    final ArrayList<ChatBridge.Tint> chatTints = new ArrayList<>();
    final ArrayList<ChatBridge.Reply> localReplies = new ArrayList<>();
    final ChatCache cache;
    private final SharedPreferences state;
    private final File directory;
    Activity activity;
    NativeDiscordUi ui;
    final Map<Activity, ArrayList<discordx.api.Registration>> nativeModals = new java.util.WeakHashMap<>();
    String notice = "";
    private boolean initialized;
    private final Map<String, discordx.api.PluginInfo> bundled = new LinkedHashMap<>();
    private final Map<Object, Map<Integer, Hooks.Connection>> connections = new java.util.WeakHashMap<>();

    PluginManager(Context context) {
        application = context.getApplicationContext();
        cache = new ChatCache(application.getFilesDir());
        state = application.getSharedPreferences("discordx_plugin_manager", Context.MODE_PRIVATE);
        directory = new File(application.getFilesDir(), "discordx/plugins");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create plugin storage");
    }

    void initializeOnce() {
        if (initialized) return;
        initialized = true;
        String interrupted = state.getString("starting", "");
        if (!interrupted.isEmpty()) state.edit().putBoolean("enabled." + interrupted, false)
            .putString("error." + interrupted, "Stopped after an interrupted startup. Reload to retry.").remove("starting").commit();
        if (state.getBoolean("bundled.hide_typing", false) && !state.contains("package.ghost_mode")
                && !state.contains("migrated.typing_split")) {
            state.edit().putBoolean("enabled.ghost_mode", state.getBoolean("enabled.hide_typing", false))
                .putBoolean("enabled.hide_typing", false).putBoolean("migrated.typing_split", true).commit();
        }
        try (InputStream input = application.getAssets().open("discordx/plugins/index.json")) {
            JSONArray index = new JSONArray(new String(PluginPackage.bounded(input, 16 * 1024), java.nio.charset.StandardCharsets.UTF_8));
            for (int i = 0; i < index.length(); i++) {
                try (InputStream zip = application.getAssets().open("discordx/plugins/" + index.getString(i))) {
                    PluginPackage pack = PluginPackage.read(zip);
                    store(pack);
                    bundled.put(pack.info.id, pack.info);
                    if (!state.contains("package." + pack.info.id) || state.getBoolean("bundled." + pack.info.id, false)) {
                        state.edit().putString("package." + pack.info.id, pack.info.hash).putBoolean("bundled." + pack.info.id, true).commit();
                    }
                }
            }
        } catch (Exception error) { notice = "Bundled plugins: " + describe(error); Log.e("DiscordX", notice, error); }
        for (Map.Entry<String, ?> value : new java.util.TreeMap<>(state.getAll()).entrySet()) {
            if (!value.getKey().startsWith("package.")) continue;
            String id = value.getKey().substring(8);
            try {
                discordx.api.PluginInfo info = PluginPackage.describe(packageFile((String) value.getValue()), (String) value.getValue());
                if (!info.id.equals(id)) throw new IOException("Installed package ID changed");
                Entry entry = new Entry(info, state.getBoolean("bundled." + id, false));
                entry.error = state.getString("error." + id, "");
                if (!entry.error.isEmpty()) entry.status = Status.FAILED;
                entries.put(id, entry);
            } catch (Exception error) { notice = "Could not read " + id + ": " + describe(error); }
        }
        for (Entry entry : new ArrayList<>(entries.values())) {
            boolean enabled = state.contains("enabled." + entry.pack.id) ? state.getBoolean("enabled." + entry.pack.id, false)
                : application.getSharedPreferences("discordx_plugins", Context.MODE_PRIVATE).getBoolean(entry.pack.id, false);
            if (enabled) start(entry);
        }
    }

    void bind(Activity activity, NativeDiscordUi ui) { this.activity = activity; this.ui = ui; }

    void attached(Activity activity) { hooks.each(Hooks.ACTIVITY, callback -> callback.attached(activity)); }
    void detached(Activity activity) {
        MessageActions.reset(this);
        hooks.each(Hooks.ACTIVITY, callback -> callback.detached(activity));
        ArrayList<discordx.api.Registration> modals = nativeModals.remove(activity);
        if (modals != null) for (discordx.api.Registration modal : new ArrayList<>(modals)) modal.close();
        if (this.activity == activity) { this.activity = null; ui = null; }
    }

    void toggle(Entry entry) { if (entry.status == Status.RUNNING) stop(entry); else start(entry); changed(); }
    void reload(Entry entry) { stop(entry); start(entry); changed(); }

    private void start(Entry entry) {
        if (entry.scope != null) return;
        entry.status = Status.STARTING;
        PluginScope scope = new PluginScope(this, entry);
        entry.scope = scope;
        state.edit().putString("starting", entry.pack.id).commit();
        try {
            PluginPackage payload;
            try (InputStream input = new FileInputStream(packageFile(entry.pack.hash))) { payload = PluginPackage.read(input); }
            if (!payload.info.hash.equals(entry.pack.hash) || !payload.info.id.equals(entry.pack.id))
                throw new IOException("Installed package checksum changed");
            scope.assets.putAll(payload.assets);
            ClassLoader loader = PluginClassLoader.open(payload.dex);
            Class<?> type = Class.forName(entry.pack.entrypoint, true, loader);
            if (type.getClassLoader() != loader) throw new IllegalStateException("Plugin entrypoint was shadowed by the host");
            Plugin plugin = type.asSubclass(Plugin.class).getConstructor().newInstance();
            entry.plugin = plugin;
            plugin.start(scope);
            hooksForNewActivity(scope);
            if (!scope.active) throw new IllegalStateException("Plugin failed during startup");
            entry.status = Status.RUNNING;
            state.edit().remove("previous." + entry.pack.id).remove("previousBundled." + entry.pack.id).commit();
            entry.error = "";
            state.edit().putBoolean("enabled." + entry.pack.id, true).remove("error." + entry.pack.id).remove("starting").commit();
        } catch (Throwable error) { failed(entry, scope, error); }
        finally { state.edit().remove("starting").commit(); }
    }

    private void hooksForNewActivity(PluginScope scope) {
        if (activity != null) hooks.eachOwned(scope, Hooks.ACTIVITY, callback -> callback.attached(activity));
        ArrayList<Hooks.Connection> active = new ArrayList<>();
        synchronized (connections) { for (Map<Integer, Hooks.Connection> values : connections.values()) active.addAll(values.values()); }
        hooks.eachOwned(scope, Hooks.GATEWAY, callback -> { for (Hooks.Connection connection : active) callback.opened(connection); });
    }

    void opened(Object owner, int id, String url) {
        Hooks.Connection previous, connection = new Hooks.Connection(url);
        synchronized (connections) { previous = connections.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(id, connection); }
        if (previous != null) hooks.each(Hooks.GATEWAY, callback -> callback.closed(previous));
        hooks.each(Hooks.GATEWAY, callback -> callback.opened(connection));
    }
    void closed(Object owner, int id) {
        Hooks.Connection connection;
        synchronized (connections) {
            Map<Integer, Hooks.Connection> values = connections.get(owner);
            connection = values == null ? null : values.remove(id);
            if (values != null && values.isEmpty()) connections.remove(owner);
        }
        if (connection != null) hooks.each(Hooks.GATEWAY, callback -> callback.closed(connection));
    }
    byte[] frame(Object owner, int id, byte[] bytes) {
        Hooks.Connection connection;
        synchronized (connections) {
            Map<Integer, Hooks.Connection> values = connections.get(owner);
            connection = values == null ? null : values.get(id);
        }
        if (connection == null || bytes == null || bytes.length == 0) return bytes;
        byte[] decoded = bytes.length <= 2 * 1024 * 1024 && (hooks.has(Hooks.GATEWAY_JSON) || !messageActions.isEmpty() || GatewayCodec.mayBeReady(bytes))
            ? GatewayCodec.transform(bytes, json -> jsonFrame(connection, json)) : bytes;
        return hooks.fold(Hooks.GATEWAY, decoded, (callback, value) -> {
            byte[] result = callback.frame(connection, value.clone());
            if (result == null) throw new IllegalStateException("Gateway filters must return bytes");
            return result;
        });
    }

    private byte[] jsonFrame(Hooks.Connection connection, byte[] bytes) {
        if (bytes.length > 2 * 1024 * 1024) return bytes;
        try {
            org.json.JSONObject dispatch = new org.json.JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if ("READY".equals(dispatch.optString("t"))) {
                org.json.JSONObject data = dispatch.optJSONObject("d");
                org.json.JSONObject user = data == null ? null : data.optJSONObject("user");
                String id = user == null ? null : user.optString("id");
                cache.account = Ids.snowflake(id) ? id : null;
            }
            String original = dispatch.toString();
            if (!messageActions.isEmpty()) MessageIdentity.frame(dispatch);
            org.json.JSONObject updated = hooks.fold(Hooks.GATEWAY_JSON, dispatch, (callback, value) -> {
                try { return java.util.Objects.requireNonNull(callback.transform(connection, new org.json.JSONObject(value.toString())), "Gateway transform returned null"); }
                catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
            });
            return updated.toString().equals(original) ? bytes : updated.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (org.json.JSONException invalid) { return bytes; }
    }

    void stop(Entry entry) {
        entry.status = Status.STOPPING;
        if (entry.scope != null) entry.scope.active = false;
        try { if (entry.plugin != null) entry.plugin.stop(); }
        catch (Throwable error) { Log.e("DiscordX", "Plugin stop failed: " + entry.pack.id, error); }
        finally {
            if (entry.scope != null) entry.scope.close();
            entry.scope = null;
            entry.plugin = null;
            entry.status = Status.DISABLED;
            state.edit().putBoolean("enabled." + entry.pack.id, false).commit();
        }
    }

    void failed(Entry entry, PluginScope source, Throwable error) {
        if (error instanceof VirtualMachineError || error instanceof ThreadDeath) throw (Error) error;
        if (entry.scope == null || entry.scope != source) return;
        stop(entry);
        entry.status = Status.FAILED;
        entry.error = describe(error);
        String previous = state.getString("previous." + entry.pack.id, "");
        if (!previous.isEmpty()) {
            try (InputStream input = new FileInputStream(packageFile(previous))) {
                PluginPackage restored = PluginPackage.read(input);
                Entry rollback = new Entry(restored, state.getBoolean("previousBundled." + entry.pack.id, false));
                rollback.status = Status.FAILED;
                rollback.error = "Update failed; previous version restored. " + entry.error;
                entries.put(restored.info.id, rollback);
                state.edit().putString("package." + restored.info.id, restored.info.hash).putBoolean("bundled." + restored.info.id, rollback.bundled)
                    .remove("previous." + restored.info.id).remove("previousBundled." + restored.info.id).commit();
            } catch (Exception restoreError) { entry.error += "; rollback failed: " + describe(restoreError); }
        }
        state.edit().putString("error." + entry.pack.id, entries.get(entry.pack.id).error).commit();
        Log.e("DiscordX", "Plugin disabled: " + entry.pack.id, error);
        changed();
    }

    void uninstall(Entry entry) {
        if (entry.bundled) throw new IllegalStateException("Bundled plugins can be disabled but not removed");
        stop(entry);
        entries.remove(entry.pack.id);
        forgetDocument(entry.pack.id);
        state.edit().remove("package." + entry.pack.id).remove("enabled." + entry.pack.id)
            .remove("bundled." + entry.pack.id).remove("error." + entry.pack.id).commit();
        discordx.api.PluginInfo fallback = bundled.get(entry.pack.id);
        if (fallback != null) {
            entries.put(fallback.id, new Entry(fallback, true));
            state.edit().putString("package." + fallback.id, fallback.hash).putBoolean("bundled." + fallback.id, true).commit();
        }
        changed();
    }

    void picker(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.startActivityForResult(intent, IMPORT_REQUEST);
    }

    boolean result(int request, int result, Intent data) {
        if (request != IMPORT_REQUEST) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        readDocument(uri, null, (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0);
        return true;
    }

    private void forgetDocument(String id) {
        String source = state.getString("sourceUri." + id, null);
        if (source == null) return;
        state.edit().remove("sourceUri." + id).apply();
        if (state.getAll().containsValue(source)) return;
        try { application.getContentResolver().releasePersistableUriPermission(Uri.parse(source), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
    }

    boolean hasDocument(Entry entry) { return state.contains("sourceUri." + entry.pack.id); }

    void reloadDocument(Entry entry) {
        String source = state.getString("sourceUri." + entry.pack.id, null);
        if (source != null) readDocument(Uri.parse(source), entry, false);
    }

    private void readDocument(Uri uri, Entry expected, boolean persist) {
        submitImport(() -> {
            try {
                PluginPackage pack;
                try (InputStream input = application.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("Cannot open the selected ZIP");
                    pack = PluginPackage.read(input);
                }
                if (expected != null && !expected.pack.id.equals(pack.info.id)) throw new IOException("Plugin ID changed");
                store(pack);
                main.post(() -> {
                    if (expected != null && entries.get(pack.info.id) != expected) return;
                    boolean running = expected != null && expected.status == Status.RUNNING;
                    if (expected != null && expected.pack.hash.equals(pack.info.hash)) {
                        if (running) reload(expected);
                        return;
                    }
                    if (expected == null && !uri.toString().equals(state.getString("sourceUri." + pack.info.id, null)))
                        forgetDocument(pack.info.id);
                    install(pack);
                    if (persist) {
                        try {
                            application.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            state.edit().putString("sourceUri." + pack.info.id, uri.toString()).apply();
                        } catch (SecurityException unsupported) { /* This provider grants one-time reads. */ }
                    }
                    if (running) { start(entries.get(pack.info.id)); changed(); }
                });
            } catch (Exception error) { main.post(() -> { notice = "ZIP import failed: " + describe(error); changed(); }); }
        });
    }

    private void submitImport(Runnable work) {
        try { background.execute(work); }
        catch (java.util.concurrent.RejectedExecutionException busy) { notice = "Background queue full"; changed(); }
    }

    void importUrl(String address) {
        final java.net.URL url;
        try {
            java.net.URI uri = new java.net.URI(address.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IOException("Use an HTTPS plugin ZIP URL without credentials.");
            url = uri.toURL();
        } catch (Exception error) { notice = describe(error); changed(); return; }
        notice = "Downloading…";
        changed();
        submitImport(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                connection.setInstanceFollowRedirects(false);
                if (connection.getResponseCode() != 200) throw new IOException("Plugin download returned HTTP " + connection.getResponseCode());
                PluginPackage pack;
                try (InputStream input = connection.getInputStream()) { pack = PluginPackage.read(input); }
                store(pack);
                main.post(() -> { forgetDocument(pack.info.id); install(pack); });
            } catch (Exception error) { main.post(() -> { notice = "Download failed: " + describe(error); changed(); }); }
            finally { if (connection != null) connection.disconnect(); }
        });
    }

    void disableAll() {
        for (Entry entry : new ArrayList<>(entries.values())) stop(entry);
        notice = "All plugins disabled";
        changed();
    }

    void install(PluginPackage pack) {
        Entry previous = entries.get(pack.info.id);
        if (previous != null && previous.pack.hash.equals(pack.info.hash)) { notice = pack.info.name + " already installed"; changed(); return; }
        if (previous != null) state.edit().putString("previous." + pack.info.id, previous.pack.hash)
            .putBoolean("previousBundled." + pack.info.id, previous.bundled).commit();
        if (previous != null) stop(previous);
        Entry entry = new Entry(pack, false);
        entries.put(pack.info.id, entry);
        state.edit().putString("package." + pack.info.id, pack.info.hash).putBoolean("bundled." + pack.info.id, false)
            .putBoolean("enabled." + pack.info.id, false).remove("error." + pack.info.id).commit();
        notice = "Installed " + pack.info.name;
        changed();
    }

    synchronized void store(PluginPackage pack) throws IOException {
        File target = packageFile(pack.info.hash);
        if (target.isFile()) {
            try (InputStream input = new FileInputStream(target)) {
                if (PluginPackage.sha256(PluginPackage.bounded(input, PluginPackage.MAX_ZIP)).equals(pack.info.hash)) return;
            }
        }
        File staging = File.createTempFile("import-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(staging)) { output.write(pack.archive); output.getFD().sync(); }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { if (staging.exists() && !staging.delete()) Log.w("DiscordX", "Could not remove import staging file"); }
    }

    private File packageFile(String hash) throws IOException {
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid installed package identity");
        return new File(directory, hash + ".zip");
    }

    SharedPreferences preferences(String id) {
        SharedPreferences prefs = application.getSharedPreferences("discordx.plugin." + id, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("_migrated", false)) {
            SharedPreferences.Editor edit = prefs.edit();
            for (Map.Entry<String, ?> item : application.getSharedPreferences("discordx_plugins", Context.MODE_PRIVATE).getAll().entrySet()) {
                Object value = item.getValue();
                if (value instanceof Integer) edit.putInt(item.getKey(), (Integer) value);
                else if (value instanceof String) edit.putString(item.getKey(), (String) value);
            }
            edit.putBoolean("_migrated", true).apply();
        }
        return prefs;
    }

    void changed() {
        MessageActions.reset(this);
        if (messageActions.isEmpty()) MessageIdentity.clear();
        if (ui != null) ui.pluginsChanged();
    }
    static String describe(Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }
    enum Status { DISABLED, STARTING, RUNNING, STOPPING, FAILED }
    static final class Entry {
        final discordx.api.PluginInfo pack;
        final boolean bundled;
        Status status = Status.DISABLED;
        String error = "";
        PluginScope scope;
        Plugin plugin;
        Entry(PluginPackage pack, boolean bundled) { this(pack.info, bundled); }
        Entry(discordx.api.PluginInfo pack, boolean bundled) { this.pack = pack; this.bundled = bundled; }
    }
}
