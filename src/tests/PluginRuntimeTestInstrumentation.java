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
            verifyQuickJumpNavigation();
            runOnMainSync(this::runChecks);
            if (!completed.await(30, TimeUnit.SECONDS)) {
                runOnMainSync(() -> finishReport(new AssertionError("Timed out waiting for asynchronous checks")));
            }
        } catch (Throwable error) { finishReport(error); }
        Bundle results = new Bundle();
        results.putString("stream", report.toString());
        finish(passed ? -1 : 0, results);
    }

    private void verifyQuickJumpNavigation() {
        String url = "https://discord.com/channels/@me/123456789012345678/223456789012345678";
        android.content.Intent[] opened = {null};
        runOnMainSync(() -> {
            Context fixture = new android.content.ContextWrapper(target) {
                @Override public Context getApplicationContext() { return this; }
                @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                    return target.getSharedPreferences("quick_jump_test." + name, mode);
                }
            };
            manager = new PluginManager(fixture);
            manager.initializeOnce();
            manager.disableAll();
            manager.activity = new android.app.Activity() {
                @Override public void startActivity(android.content.Intent intent) { opened[0] = intent; }
            };
            manager.toggle(manager.entries.get("quick_jump"));
            manager.hooks.each(Hooks.LOCAL_COMMAND, handler -> handler.accept("quick-jump", "link:" + url));
        });
        // The plugin posts navigation after Phoenix clears the composer.
        runOnMainSync(() -> {
            manager.disableAll();
            manager.background.shutdownNow();
        });
        check(opened[0] != null && android.content.Intent.ACTION_VIEW.equals(opened[0].getAction())
                && url.equals(opened[0].getDataString()) && target.getPackageName().equals(opened[0].getPackage()),
            "Quick Jump opens the exact submitted message link in the current app");
    }

    private void runChecks() {
        try {
            manager = new PluginManager(target);
            target.getSharedPreferences("discordx_plugin_manager", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled.hide_typing", true).putBoolean("bundled.hide_typing", true).commit();
            manager.initializeOnce();
            check(manager.entries.get("ghost_mode").status == PluginManager.Status.RUNNING
                    && manager.entries.get("hide_typing").status == PluginManager.Status.DISABLED,
                "Existing outgoing typing preference migrates to GhostMode only");
            manager.disableAll();
            check(manager.entries.size() == 21, "Twenty-one independently packaged built-ins discovered");
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
            rejectsCommand(() -> entry.scope.chat().slashCommand("verify", "", args -> {}));
            rejectsCommand(() -> entry.scope.ui().command("Two Words", "", () -> {}));
            discordx.api.Registration fromChat = entry.scope.chat().slashCommand("second", "", args -> {});
            rejectsCommand(() -> entry.scope.ui().command("second", "", () -> {}));
            fromChat.close();
            entry.scope.ui().command("second", "", () -> {}).close();
            check(manager.commands.size() == 1, "Both command APIs enforce one namespace and release names on close");
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

    private static void rejectsCommand(Runnable register) {
        try { register.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid or duplicate command was registered");
    }

    private void checkBundledHooks(String id) throws Exception {
        String api = "https://discord.com/api/v9/";
        String channel = api + "channels/123456789012345678/";
        switch (id) {
            case "hide_typing":
                Object socket = new Object();
                manager.opened(socket, 0, "wss://gateway.discord.gg");
                String incoming = "{\"op\":0,\"s\":7,\"t\":\"TYPING_START\",\"d\":{}}";
                org.json.JSONObject hidden = new org.json.JSONObject(new String(manager.frame(socket, 0, incoming.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
                check(!"TYPING_START".equals(hidden.getString("t")) && hidden.getInt("s") == 7 && !manager.hooks.has(Hooks.HTTP_REQUEST),
                    "HideTyping suppresses the incoming dispatch and preserves sequence and outgoing typing");
                manager.closed(socket, 0);
                break;
            case "ghost_mode":
                Hooks.Reply reply = manager.hooks.fold(Hooks.HTTP_REQUEST, (Hooks.Reply) null,
                    (filter, value) -> filter.apply(new Hooks.Request(channel + "typing", "POST")));
                check(reply != null && reply.status == 204, "GhostMode handles the typing POST locally");
                check(manager.hooks.fold(Hooks.HTTP_REQUEST, (Hooks.Reply) null,
                    (filter, value) -> filter.apply(new Hooks.Request(channel + "messages", "POST"))) == null,
                    "GhostMode passes message sends through");
                break;
            case "custom_themes":
                String route = api + "users/123456789012345678/profile?guild_id=234567890123456789";
                byte[] plain = "{\"user_profile\":{\"bio\":\"hello\"},\"guild_member_profile\":null}".getBytes(StandardCharsets.UTF_8);
                ProfileIdentity.response(route, plain);
                int[] defaultStops = manager.hooks.fold(Hooks.DEFAULT_PROFILE_COLORS, (int[])null, (provider, old) -> provider.get());
                check(defaultStops != null && Boolean.FALSE.equals(ProfileIdentity.customPalette("123456789012345678", "234567890123456789")),
                    "Custom Themes supplies a surface for a stock profile independently");
                byte[] guildColors = "{\"user_profile\":{},\"guild_member_profile\":{\"theme_colors\":[0,0]}}".getBytes(StandardCharsets.UTF_8);
                ProfileIdentity.response(route, guildColors);
                check(Boolean.TRUE.equals(ProfileIdentity.customPalette("123456789012345678", null)), "An explicitly black guild palette is protected");
                byte[] globalColors = "{\"user_profile\":{\"theme_colors\":[1,2]}}".getBytes(StandardCharsets.UTF_8);
                ProfileIdentity.response(route, globalColors);
                check(Boolean.TRUE.equals(ProfileIdentity.customPalette("123456789012345678", "234567890123456789")), "A personal palette takes precedence");
                manager.preferences(id).edit().putBoolean("theme_default_profiles", false).apply();
                check(manager.hooks.fold(Hooks.DEFAULT_PROFILE_COLORS, (int[])null, (provider, old) -> provider.get()) == null, "Default profile theming switches off independently");
                ProfileIdentity.resetAccount();
                manager.preferences(id).edit().remove("theme_default_profiles").apply();
                break;
            case "no_telemetry":
                for (String url : new String[] {api + "science", channel + "messages", "https://example.org/api/v9/science"}) {
                    Hooks.Reply response = manager.hooks.fold(Hooks.HTTP_REQUEST, (Hooks.Reply)null,
                        (filter, previous) -> filter.apply(new Hooks.Request(url, "POST")));
                    check((response != null) == url.equals(api + "science"), "NoTelemetry restricts analytics interception to the collection route");
                }
                break;
            case "mention_avatars":
                String mentions = "[{\"id\":\"123\",\"mentions\":[{\"id\":\"123456789012345678\",\"username\":\"alice\"}]}]";
                manager.hooks.each(Hooks.HTTP_RESPONSE, callback -> callback.received(channel + "messages", mentions.getBytes(StandardCharsets.UTF_8)));
                String avatar = manager.hooks.fold(Hooks.MENTION_AVATAR, (String)null, (callback, old) -> callback.apply("123", "alice"));
                check(avatar != null && avatar.contains("/embed/avatars/"), "Mention Avatars resolves a message-scoped user from history");
                break;
            case "text_replace":
                manager.stop(manager.entries.get(id));
                manager.preferences(id).edit().putString("brb", "be right back").putString("br", "short").apply();
                manager.toggle(manager.entries.get(id));
                check("be right back short".equals(ChatSubmission.apply(manager, "brb br")), "Text Replace applies longest literal rules without cascading");
                break;
            case "no_profile_themes":
                String stock = "{\"user\":{\"username\":\"tester\",\"accent_color\":null,\"banner_color\":\"#778899\"},\"user_profile\":{\"theme_colors\":null}}";
                check(stock.equals(body(api + "users/123456789012345678/profile", stock)),
                    "NoProfileThemes leaves the stock profile and its derived banner color untouched");
                String profile = body(api + "users/123456789012345678/profile", "{\"user_profile\":{\"theme_colors\":[1,2],\"bio\":\"test\"}}");
                org.json.JSONObject data = new org.json.JSONObject(profile).getJSONObject("user_profile");
                check(data.isNull("theme_colors") && "test".equals(data.getString("bio")), "NoProfileThemes preserves other profile fields");
                String themed = "{\"user\":{\"id\":\"123456789012345678\",\"username\":\"tester\",\"banner\":\"a_animated\","
                    + "\"avatar\":\"portrait\",\"avatar_decoration_data\":{},\"collectibles\":{\"nameplate\":{}}},"
                    + "\"guild_member_profile\":{\"banner\":\"static\",\"profile_effect\":{},\"theme_colors\":[1,2]}}";
                org.json.JSONObject neutral = new org.json.JSONObject(body(api + "users/123456789012345678/profile", themed));
                org.json.JSONObject person = neutral.getJSONObject("user");
                check(!person.has("banner") && !person.has("collectibles") && !person.has("avatar_decoration_data")
                    && "portrait".equals(person.getString("avatar")) && neutral.getJSONObject("guild_member_profile").length() == 0,
                    "NoProfileThemes removes banners, guild colors, nameplates, decorations and effects without removing the avatar");
                Object profileSocket = new Object();
                manager.opened(profileSocket, 0, "wss://gateway.discord.gg");
                manager.preferences(id).edit().putBoolean("keepOwn", true).apply();
                String ready = "{\"op\":0,\"t\":\"READY\",\"d\":{\"user\":{\"id\":\"123456789012345678\",\"collectibles\":{}},"
                    + "\"guilds\":[{\"members\":[{\"user\":{\"id\":\"200\",\"collectibles\":{},\"username\":\"other\"}}]}]}}";
                String cleaned = new String(manager.frame(profileSocket, 0, ready.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
                org.json.JSONObject readyData = new org.json.JSONObject(cleaned).getJSONObject("d");
                org.json.JSONObject otherUser = readyData.getJSONArray("guilds").getJSONObject(0).getJSONArray("members")
                    .getJSONObject(0).getJSONObject("user");
                check(readyData.getJSONObject("user").has("collectibles") && !otherUser.has("collectibles") && otherUser.has("username"),
                    "NoProfileThemes strips nested guild cosmetics while preserving the signed-in user's appearance");
                manager.closed(profileSocket, 0);
                break;
            case "message_latency":
                String history = body(channel + "messages", "[{\"id\":\"1000000012582912000\",\"nonce\":\"1000000000000000000\",\"content\":\"test\"}]");
                String marked = new org.json.JSONArray(history).getJSONObject(0).getString("content");
                CharSequence label = manager.hooks.fold(Hooks.DISPLAY_TEXT, (CharSequence) marked, (filter, value) -> filter.apply(value));
                check(label.toString().endsWith("3.00 s") && "test".contentEquals(discordx.api.TextTokens.clean(marked)),
                    "MessageLatency renders a three-second delay and preserves copied text");
                break;
            case "quick_jump":
                String index = api + "guilds/123456789012345678/application-command-index";
                String empty = "{\"applications\":[],\"application_commands\":[]}";
                String added = body(index, empty);
                org.json.JSONObject catalog = new org.json.JSONObject(added);
                org.json.JSONObject command = catalog.getJSONArray("application_commands").getJSONObject(0);
                org.json.JSONObject option = command.getJSONArray("options").getJSONObject(0);
                check("quick-jump".equals(command.getString("name")) && "link".equals(option.getString("name"))
                        && option.getBoolean("required") && option.getInt("type") == 3
                        && added.equals(body(index, added)), "Quick Jump publishes one native command with a required string");
                String other = "{\"applications\":[],\"application_commands\":[{\"id\":\"9\",\"name\":\"quick-jump\"}]}";
                check(other.equals(body(index, other)) && empty.equals(body(channel + "messages", empty)),
                    "Quick Jump preserves command-name collisions and unrelated responses");
                break;
            case "fake_nitro":
                Object emojiSocket = new Object();
                manager.opened(emojiSocket, 0, "wss://gateway.discord.gg");
                String catalogReady = "{\"op\":0,\"t\":\"READY\",\"d\":{\"guilds\":["
                    + "{\"id\":\"10\",\"name\":\"Atlas\",\"emojis\":[{\"id\":\"123456789012345678\",\"animated\":true,\"name\":\"party\"}]},"
                    + "{\"id\":\"20\",\"name\":\"Birch\",\"emojis\":[{\"id\":\"223456789012345678\",\"name\":\"party\"}]}]}}";
                manager.frame(emojiSocket, 0, catalogReady.getBytes(StandardCharsets.UTF_8));
                String picked = manager.hooks.fold(Hooks.PICKER_EMOJIS, (String)null,
                    (handler, old) -> handler.link("223456789012345678", "party", ""));
                check(picked.endsWith("223456789012345678.png?size=128"), "Native picker shares the selected emoji by ID across guilds");
                manager.preferences(id).edit().putBoolean("named", true).apply();
                String named = manager.hooks.fold(Hooks.PICKER_EMOJIS, (String)null,
                    (handler, old) -> handler.link("123456789012345678", "party", ""));
                check(named.startsWith("[:party:]") && named.equals(ChatSubmission.apply(manager, named)), "Sending a named picker link preserves its Markdown");
                manager.preferences(id).edit().remove("named").apply();
                manager.closed(emojiSocket, 0);
                String emoji = "<a:party:123456789012345678>";
                String rewritten = ChatSubmission.apply(manager, "hi " + emoji);
                check(rewritten.equals("hi https://cdn.discordapp.com/emojis/123456789012345678.gif?size=128")
                        && ("`" + emoji + "`").equals(ChatSubmission.apply(manager, "`" + emoji + "`")),
                    "FakeNitro converts animated emoji to ordinary links and preserves inline code");
                break;
            case "clean_links":
                check("See [post](https://example.org/post?id=2).".equals(ChatSubmission.apply(manager,
                    "See [post](https://example.org/post?id=2&utm_source=chat).")), "Clean Links preserves Markdown destinations");
                break;
            case "plugin_example":
                check(manager.chatButtons.size() == 2 && manager.commands.size() == 1 && manager.accessories.size() == 1
                    && "the".equals(ChatSubmission.apply(manager, "teh")), "Developer example registers scoped contributions");
                break;
            case "translate":
                check(manager.messageActions.size() == 1 && manager.settings.size() == 1 && manager.accessories.size() == 1 && manager.hooks.has(Hooks.GATEWAY_JSON), "Translate registers manual and channel translation");
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
            check(reloaded.entries.size() == 21, "Installed package selection persists across manager restart");
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
