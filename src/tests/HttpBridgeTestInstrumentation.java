package discordx;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import discordx.api.Hooks;
import discordx.api.PluginInfo;
import discordx.api.Registration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Headless verification against Discord's actual obfuscated HTTP classes. */
public final class HttpBridgeTestInstrumentation extends Instrumentation {
    private static final String PROFILE = "https://discord.com/api/v9/users/12345678901234567/profile";
    private static final byte[] ORIGINAL = "{\"user\":{\"id\":\"12345678901234567\",\"username\":\"http_fixture\"}}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MODIFIED = "{\"fixture\":\"modified\"}".getBytes(StandardCharsets.UTF_8);
    private final StringBuilder report = new StringBuilder();
    private ClassLoader loader;
    private PluginManager manager;
    private PluginScope scope;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            loader = getTargetContext().getClassLoader();
            manager = new PluginManager(getTargetContext());
            DiscordX.manager = manager;
            Object buffer = buffer();
            check(buffer.getClass().getName().equals("w90.h"), "Patched host factory allocates the real host buffer");
            intercept();
            observeAndRewrite();
            observerOnly();
            gzipAndPassthrough();
            filterFailure();
            report.append("ALL PASSED\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED;
            String details = android.util.Log.getStackTraceString(failure);
            report.append("FAIL ").append(details.substring(0, Math.min(details.length(), 6000))).append('\n');
        } finally {
            if (scope != null) scope.close();
            if (manager != null) manager.background.shutdownNow();
        }
        result.putString("stream", report.toString());
        android.util.Log.i("DiscordXHttpTest", report.toString());
        finish(code, result);
    }

    private PluginScope freshScope() {
        if (scope != null) scope.close();
        PluginInfo info = new PluginInfo("http_fixture", "HTTP fixture", "1.0.0", "Verification only", "DiscordX",
            "fixture.Plugin", String.join("", Collections.nCopies(64, "0")));
        PluginManager.Entry entry = new PluginManager.Entry(info, false);
        manager.entries.put(info.id, entry);
        scope = new PluginScope(manager, entry);
        entry.scope = scope;
        entry.status = PluginManager.Status.RUNNING;
        return scope;
    }

    private void intercept() throws Exception {
        PluginScope active = freshScope();
        Registration registration = active.register(Hooks.HTTP_REQUEST, request ->
            request.method.equals("POST") && request.url.endsWith("/typing") ? new Hooks.Reply(204, null, new byte[0]) : null);
        Object request = request("https://discord.com/api/v9/channels/12345678901234567/typing", "POST");
        Object response = HttpBridge.intercept(request);
        check(response != null && response.getClass().getField("v").getInt(response) == 204,
            "Request interception constructs a real host 204 response");
        check(read(response).length == 0, "Intercepted response has a readable empty body");
        registration.close();
        check(HttpBridge.intercept(request) == null, "Closing request registration restores the host request path");
    }

    private void observeAndRewrite() throws Exception {
        PluginScope active = freshScope();
        AtomicInteger observations = new AtomicInteger();
        active.register(Hooks.HTTP_RESPONSE, (url, body) -> {
            if (!url.equals(PROFILE) || !Arrays.equals(body, ORIGINAL)) throw new AssertionError("Observer input differs");
            observations.incrementAndGet();
        });
        active.register(Hooks.HTTP_BODY, (url, body) -> MODIFIED.clone());
        Object original = response(ORIGINAL, "", ORIGINAL.length);
        Object result = HttpBridge.response(original);
        check(result != original && Arrays.equals(read(result), MODIFIED), "Body filter replaces the consumed host response body");
        check(observations.get() == 1, "HTTP observer receives the original profile JSON once");
        check("12345678901234567".equals(ProfileIdentity.forUsername("http_fixture")),
            "Real HTTP adapter populates profile identity before rendering");
        Object oldBody = original.getClass().getField("y").get(original);
        Object oldSource = loader.loadClass("g90.s0").getMethod("j").invoke(oldBody);
        check(oldSource.getClass().getField("e").getLong(oldSource) == 0, "Original host body was consumed and safely replaced");
    }

    private void observerOnly() throws Exception {
        PluginScope active = freshScope();
        AtomicInteger observations = new AtomicInteger();
        active.register(Hooks.HTTP_RESPONSE, (url, body) -> observations.incrementAndGet());
        Object result = HttpBridge.response(response(ORIGINAL, "", ORIGINAL.length));
        check(Arrays.equals(read(result), ORIGINAL) && observations.get() == 1,
            "Observer-only adaptation preserves readable original bytes");
    }

    private void gzipAndPassthrough() throws Exception {
        PluginScope active = freshScope();
        active.register(Hooks.HTTP_RESPONSE, (url, body) -> {
            if (!Arrays.equals(body, ORIGINAL)) throw new AssertionError("Gzip observer did not receive decoded bytes");
        });
        active.register(Hooks.HTTP_BODY, (url, body) -> MODIFIED.clone());
        byte[] compressed = gzip(ORIGINAL);
        Object rewritten = HttpBridge.response(response(compressed, "gzip", compressed.length));
        check(Arrays.equals(gunzip(read(rewritten)), MODIFIED), "Gzip responses are decoded, transformed, and re-encoded");
        Object unsupported = response(ORIGINAL, "br", ORIGINAL.length);
        check(HttpBridge.response(unsupported) == unsupported && Arrays.equals(read(unsupported), ORIGINAL),
            "Unsupported content encoding leaves the original response untouched");
        Object unknownLength = response(ORIGINAL, "", -1);
        Object bounded = HttpBridge.response(unknownLength);
        check(bounded != unknownLength && Arrays.equals(read(bounded), MODIFIED),
            "Bounded unknown-size profile responses are observed and transformed");
        byte[] oversizedBytes = new byte[2 * 1024 * 1024 + 1];
        oversizedBytes[0] = 42;
        Object oversized = response(oversizedBytes, "", -1);
        check(HttpBridge.response(oversized) == oversized && Arrays.equals(read(oversized), oversizedBytes),
            "Oversized unknown-size responses remain untouched and fully readable");
        Object unknownGzip = HttpBridge.response(response(compressed, "gzip", -1));
        check(Arrays.equals(gunzip(read(unknownGzip)), MODIFIED),
            "Bounded unknown-size gzip responses are transformed correctly");
        byte[] invalidGzip = "invalid-gzip".getBytes(StandardCharsets.UTF_8);
        Object invalid = HttpBridge.response(response(invalidGzip, "gzip", invalidGzip.length));
        check(Arrays.equals(read(invalid), invalidGzip), "Malformed gzip restores original bytes after the body is consumed");
    }

    private void filterFailure() throws Exception {
        PluginScope active = freshScope();
        active.register(Hooks.HTTP_BODY, (url, body) -> { throw new IllegalStateException("Expected fixture failure"); });
        Object result = HttpBridge.response(response(ORIGINAL, "", ORIGINAL.length));
        check(Arrays.equals(read(result), ORIGINAL) && !active.active,
            "Throwing plugin filter is quarantined while original response remains readable");
    }

    private Object buffer() throws Exception {
        return loader.loadClass("com.discord.nativeapp.modules.bb").getMethod("discordxBuffer").invoke(null);
    }

    private Object request(String url, String method) throws Exception {
        Class<?> addressType = loader.loadClass("g90.b0"), headersType = loader.loadClass("g90.a0");
        Object address = addressType.getConstructor(String.class, String.class, String.class, String.class, int.class,
            ArrayList.class, ArrayList.class, String.class, String.class).newInstance("https", "", "", "discord.com", 443,
                new ArrayList<>(Collections.singletonList("")), null, null, url);
        Object headers = headersType.getConstructor(String[].class).newInstance((Object) new String[0]);
        return loader.loadClass("g90.k0").getConstructor(addressType, String.class, headersType, loader.loadClass("g90.o0"),
            java.util.Map.class).newInstance(address, method, headers, null, Collections.emptyMap());
    }

    private Object response(byte[] bytes, String encoding, long size) throws Exception {
        Class<?> builderType = loader.loadClass("g90.p0");
        Object builder = builderType.getConstructor().newInstance();
        builderType.getField("a").set(builder, request(PROFILE, "GET"));
        builderType.getField("b").set(builder, loader.loadClass("g90.j0").getField("i").get(null));
        builderType.getField("c").setInt(builder, 200);
        builderType.getField("d").set(builder, "OK");
        Object contents = buffer();
        contents.getClass().getMethod("write", byte[].class).invoke(contents, (Object) bytes);
        Object mediaType = loader.loadClass("g90.e").getMethod("r", String.class).invoke(null, "application/json");
        Object body = loader.loadClass("g90.r0").getConstructor(Object.class, long.class, loader.loadClass("w90.j"), int.class)
            .newInstance(mediaType, size, contents, 0);
        builderType.getField("g").set(builder, body);
        String[] names = encoding.isEmpty() ? new String[0] : new String[] {"Content-Encoding", encoding};
        Object headers = loader.loadClass("g90.a0").getConstructor(String[].class).newInstance((Object) names);
        builderType.getMethod("c", loader.loadClass("g90.a0")).invoke(builder, headers);
        return builderType.getMethod("a").invoke(builder);
    }

    private byte[] read(Object response) throws Exception {
        Object body = response.getClass().getField("y").get(response);
        return (byte[]) loader.loadClass("g90.s0").getMethod("f").invoke(body);
    }

    private static byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(bytes); }
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] bytes) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return PluginPackage.bounded(gzip, 2 * 1024 * 1024);
        }
    }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        report.append("PASS ").append(label).append('\n');
    }
}
