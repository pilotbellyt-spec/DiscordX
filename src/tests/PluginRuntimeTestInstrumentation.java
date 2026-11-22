package discordx;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import discordx.api.Hooks;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class PluginRuntimeTestInstrumentation extends Instrumentation {
    private Context target;
    private final CountDownLatch completed = new CountDownLatch(1);
    private boolean reportFinished;
    private boolean passed;
    private PluginManager manager;
    private final StringBuilder report = new StringBuilder();
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        target = getTargetContext();
        try {
            runOnMainSync(this::runChecks);
            if (!completed.await(30, TimeUnit.SECONDS)) {
                runOnMainSync(() -> finishReport(new AssertionError("Timed out waiting for asynchronous checks")));
            }
        } catch (Throwable error) { finishReport(error); }
        Bundle results = new Bundle();
        results.putString("stream", report.toString());
        finish(passed ? -1 : 0, results);
    }

    private void runChecks() {
        try {
            manager = new PluginManager(target);
            manager.initializeOnce();
            manager.disableAll();
            check(manager.entries.size() == 15, "Fifteen independently packaged built-ins discovered");
            for (PluginManager.Entry entry : new java.util.ArrayList<>(manager.entries.values())) {
                manager.toggle(entry);
                check(entry.status == PluginManager.Status.RUNNING, "Loaded built-in: " + entry.pack.id + " " + entry.error);
                checkBundledHooks(entry.pack.id);
                manager.stop(entry);
            }
            check(manager.settings.isEmpty() && manager.profiles.isEmpty(), "Built-in UI registrations removed on stop");
            PluginPackage example = read("clipboard_prefix.zip");
            manager.store(example);
            manager.install(example);
            PluginManager.Entry entry = manager.entries.get(example.info.id);
            check(entry.status == PluginManager.Status.DISABLED, "Import does not execute code");
            manager.toggle(entry);
            check(entry.status == PluginManager.Status.RUNNING, "External plugin loaded through ART");
            check(entry.plugin.getClass().getClassLoader() != getClass().getClassLoader(), "External plugin has an independent class loader");
            check("Example: text".contentEquals(copy("text")), "External clipboard hook changes real dispatch output");
            check(manager.settings.size() == 1, "External settings contribution registered");
            check(entry.scope.info().id.equals(example.info.id), "Plugin receives immutable manifest metadata");
            discordx.api.Registration action = entry.scope.ui().messageAction("Verify message", message -> {});
            discordx.api.Registration command = entry.scope.ui().command("verify", "Test command", () -> {});
            check(manager.messageActions.size() == 1 && manager.commands.size() == 1, "Independent message actions and commands register");
            org.json.JSONObject message = new org.json.JSONObject("{\"id\":\"123456789012345678\",\"channel_id\":\"223456789012345678\",\"content\":\"identical label\"}");
            MessageIdentity.observe(message);
            String marked = message.getString("content");
            CharSequence decorated = MessageIdentity.decorate(marked, discordx.api.TextTokens.clean(marked));
            check(MessageIdentity.find("identical label") == null, "Plain labels never acquire message IDs from matching text");
            check(MessageIdentity.find(decorated).id.equals("123456789012345678"), "Host-owned provenance supplies the correct message ID");
            check("identical label".contentEquals(discordx.api.TextTokens.clean(marked)), "Message identity tokens cannot leak into copied text");
            final boolean[] uiRejected = {false};
            Thread worker = new Thread(() -> {
                try { entry.scope.ui().refresh(); }
                catch (IllegalStateException expected) { uiRejected[0] = true; }
                command.close();
            });
            worker.start(); worker.join();
            check(uiRejected[0], "Worker-thread UI calls fail at the API boundary");
            Object connectionOwner = new Object();
            manager.opened(connectionOwner, 1, "wss://gateway.discord.gg");
            byte[] frame = "{\"op\":0,\"t\":\"SDK_TEST\",\"d\":{\"value\":\"before\"}}".getBytes(StandardCharsets.UTF_8);
            check(new String(manager.frame(connectionOwner, 1, frame), StandardCharsets.UTF_8).contains("after"), "External JSON Gateway hook transforms dispatches");
            byte[] etf = etfFrame();
            byte[] transformed = manager.frame(connectionOwner, 1, etf);
            check((transformed[0] & 255) == 131, "Shared Gateway codec preserves ETF transport");
            final boolean[] decoded = {false};
            GatewayCodec.transform(transformed, json -> { decoded[0] = new String(json, StandardCharsets.UTF_8).contains("after"); return json; });
            check(decoded[0], "External JSON hook also transforms ETF dispatches");
            manager.closed(connectionOwner, 1);
            Hooks.Request request = new Hooks.Request("https://example.invalid/typing", "POST");
            Hooks.Reply reply = manager.hooks.fold(Hooks.HTTP_REQUEST, (Hooks.Reply) null, (filter, value) -> filter.apply(request));
            check(reply != null && reply.status == 204, "External HTTP interception supplies a local response");
            byte[] modified = manager.hooks.fold(Hooks.HTTP_BODY, new byte[0], (filter, value) -> filter.transform("https://example.invalid/body", value));
            check(new String(modified, StandardCharsets.UTF_8).contains("modified"), "External HTTP body hook transforms data");
            String marker = "message\u2063" + entry.scope.textTokenPrefix() + "00000001\u2063";
            manager.stop(entry);
            check("message".contentEquals(discordx.api.TextTokens.clean(marker)), "Host strips decoration tokens after plugin disable");
            manager.stop(entry);
            check("text".contentEquals(copy("text")) && manager.settings.isEmpty() && manager.messageActions.isEmpty() && manager.commands.isEmpty(), "Disable removes hook and UI contribution");
            check(manager.preferences(example.info.id).getBoolean("cleanup", false), "Plugin-owned cleanup executes");
            manager.toggle(entry);
            check("fail".contentEquals(copy("fail")), "Throwing callback preserves previous host result");
            manager.main.post(() -> afterCallbackFailure(example));
        } catch (Throwable error) { finishReport(error); }
    }

    private void checkBundledHooks(String id) throws Exception {
        String api = "https://discord.com/api/v9/";
        String channel = api + "channels/123456789012345678/";
        switch (id) {
            case "hide_typing":
                Hooks.Reply reply = manager.hooks.fold(Hooks.HTTP_REQUEST, (Hooks.Reply) null,
                    (filter, value) -> filter.apply(new Hooks.Request(channel + "typing", "POST")));
                check(reply != null && reply.status == 204, "HideTyping handles the typing POST locally");
                check(manager.hooks.fold(Hooks.HTTP_REQUEST, (Hooks.Reply) null,
                    (filter, value) -> filter.apply(new Hooks.Request(channel + "messages", "POST"))) == null,
                    "HideTyping passes message sends through");
                break;
            case "no_profile_themes":
                String profile = body(api + "users/123456789012345678/profile", "{\"user_profile\":{\"theme_colors\":[1,2],\"bio\":\"test\"}}");
                org.json.JSONObject data = new org.json.JSONObject(profile).getJSONObject("user_profile");
                check(data.isNull("theme_colors") && "test".equals(data.getString("bio")), "NoProfileThemes preserves other profile fields");
                break;
            case "message_latency":
                String history = body(channel + "messages", "[{\"id\":\"1000000012582912000\",\"nonce\":\"1000000000000000000\",\"content\":\"test\"}]");
                String marked = new org.json.JSONArray(history).getJSONObject(0).getString("content");
                CharSequence label = manager.hooks.fold(Hooks.DISPLAY_TEXT, (CharSequence) marked, (filter, value) -> filter.apply(value));
                check(label.toString().endsWith("3.00 s") && "test".contentEquals(discordx.api.TextTokens.clean(marked)),
                    "MessageLatency renders a three-second delay and preserves copied text");
                break;
            case "translate":
                check(manager.messageActions.size() == 1 && manager.settings.size() == 1, "Translate registers message action and language setting");
                break;
        }
    }

    private String body(String url, String json) {
        byte[] bytes = manager.hooks.fold(Hooks.HTTP_BODY, json.getBytes(StandardCharsets.UTF_8),
            (filter, value) -> filter.transform(url, value));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void afterCallbackFailure(PluginPackage example) {
        try {
            PluginManager.Entry entry = manager.entries.get(example.info.id);
            check(entry.status == PluginManager.Status.FAILED && manager.settings.isEmpty(), "Callback failure quarantines and cleans the plugin");
            check(!manager.preferences(example.info.id).getBoolean("queued", true), "Queued main-thread callbacks cannot run after disable");
            PluginPackage broken = read("clipboard_failure.zip");
            manager.store(broken);
            manager.install(broken);
            manager.toggle(manager.entries.get(example.info.id));
            PluginManager.Entry restored = manager.entries.get(example.info.id);
            check(restored.pack.hash.equals(example.info.hash), "Failed update restores the previous package");
            check(manager.settings.isEmpty() && "text".contentEquals(copy("text")), "Partial startup registrations are removed");
            manager.toggle(restored);
            check("Example: text".contentEquals(copy("text")), "Restored package can start again");
            manager.uninstall(restored);
            check(!manager.entries.containsKey(example.info.id) && manager.settings.isEmpty(), "Uninstall stops and removes the plugin");
            for (String name : new String[] {"invalid_api.zip", "invalid_dex.zip", "invalid_path.zip", "duplicate.zip", "oversized.zip"}) {
                boolean rejected = false;
                try { read(name); } catch (java.io.IOException expected) { rejected = true; }
                check(rejected, "Rejected invalid package: " + name);
            }
            PluginManager reloaded = new PluginManager(target);
            reloaded.initializeOnce();
            check(reloaded.entries.size() == 15, "Installed package selection persists across manager restart");
            manager.main.postDelayed(() -> {
                try { check(!manager.preferences(example.info.id).getBoolean("async", true), "Managed asynchronous callbacks cannot run after disable"); finishReport(null); }
                catch (Throwable error) { finishReport(error); }
            }, 350);
        } catch (Throwable error) { finishReport(error); }
    }

    private static byte[] etfFrame() throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeByte(131); out.writeByte(116); out.writeInt(3);
        binary(out, "op"); out.writeByte(97); out.writeByte(0);
        binary(out, "t"); binary(out, "SDK_TEST");
        binary(out, "d"); out.writeByte(116); out.writeInt(1);
        binary(out, "value"); binary(out, "before");
        return bytes.toByteArray();
    }
    private static void binary(java.io.DataOutputStream out, String text) throws java.io.IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); out.writeByte(109); out.writeInt(bytes.length); out.write(bytes);
    }

    private PluginPackage read(String name) throws Exception {
        try (InputStream input = target.getAssets().open("tests/" + name)) { return PluginPackage.read(input); }
    }
    private CharSequence copy(String text) {
        return manager.hooks.fold(Hooks.CLIPBOARD, (CharSequence) text, (filter, value) -> filter.apply(value));
    }
    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        report.append("PASS ").append(label).append('\n');
    }
    private synchronized void finishReport(Throwable failure) {
        if (reportFinished) return;
        reportFinished = true;
        passed = failure == null;
        if (failure != null) {
            report.append("FAIL ").append(android.util.Log.getStackTraceString(failure));
            android.util.Log.e("DiscordXPluginTest", report.toString(), failure);
        } else report.append("ALL PASSED\n");
        try (FileOutputStream output = new FileOutputStream(new File(target.getFilesDir(), "plugin-verification.txt"))) {
            output.write(report.toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException error) { android.util.Log.e("DiscordXPluginTest", "Could not save report", error); }
        completed.countDown();
    }
}
