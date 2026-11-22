package discordx;

import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Adapter for the build-356207 HTTP request/response classes. */
final class HttpBridge {
    private HttpBridge() {}

    private static String url(Object request) throws ReflectiveOperationException {
        Object address = request.getClass().getField("a").get(request);
        return (String) address.getClass().getField("i").get(address);
    }

    static Object intercept(Object request) {
        if (!DiscordX.manager.hooks.has(discordx.api.Hooks.HTTP_REQUEST)) return null;
        try {
            String method = (String) request.getClass().getField("b").get(request);
            discordx.api.Hooks.Request input = new discordx.api.Hooks.Request(url(request), method);
            discordx.api.Hooks.Reply reply = DiscordX.manager.hooks.fold(discordx.api.Hooks.HTTP_REQUEST,
                (discordx.api.Hooks.Reply) null, (filter, previous) -> previous == null ? filter.apply(input) : previous);
            if (reply == null) return null;
            ClassLoader loader = request.getClass().getClassLoader();
            Class<?> builderClass = loader.loadClass("g90.p0");
            Object builder = builderClass.getConstructor().newInstance();
            builderClass.getField("a").set(builder, request);
            builderClass.getField("b").set(builder, loader.loadClass("g90.j0").getField("i").get(null));
            builderClass.getField("c").setInt(builder, reply.status);
            builderClass.getField("d").set(builder, reply.status == 204 ? "No Content" : "DiscordX plugin response");
            builderClass.getField("g").set(builder, new BodyFactory(loader).create(reply.contentType == null ? null : loader.loadClass("g90.e").getMethod("r", String.class).invoke(null, reply.contentType), reply.body));
            return builderClass.getMethod("a").invoke(builder);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("DiscordX", "Could not adapt HTTP request interception", error);
            return null;
        }
    }

    static Object response(Object response) {
        try {
            Object request = response.getClass().getField("d").get(response);
            String url = url(request);
            if (!DiscordX.manager.hooks.has(discordx.api.Hooks.HTTP_BODY)
                    && !DiscordX.manager.hooks.has(discordx.api.Hooks.HTTP_RESPONSE) && DiscordX.manager.messageActions.isEmpty() && !ProfileIdentity.accepts(url)) return response;
            Object body = response.getClass().getField("y").get(response);
            if (body == null) return response;
            ClassLoader loader = response.getClass().getClassLoader();
            Class<?> bodyClass = loader.loadClass("g90.s0");
            long size = (Long) bodyClass.getMethod("g").invoke(body);
            if (size > 2 * 1024 * 1024) return response;
            Object type = bodyClass.getMethod("i").invoke(body);
            if (type == null || !type.toString().toLowerCase(java.util.Locale.ROOT).contains("json")) return response;
            String encoding = (String) response.getClass().getMethod("f", String.class, String.class).invoke(response, "Content-Encoding", "");
            if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("gzip") && !encoding.equalsIgnoreCase("identity")) return response;
            Object builder = response.getClass().getMethod("i").invoke(response);
            java.lang.reflect.Field bodyField = builder.getClass().getField("g");
            Method build = builder.getClass().getMethod("a");
            BodyFactory factory = new BodyFactory(loader);
            // Resolve the replacement body before consuming the host's stream.
            Object buffer = factory.buffer.invoke(null);
            Object replacement = factory.body.newInstance(type, -1L, buffer, 0);
            bodyField.set(builder, replacement);
            Object result = build.invoke(builder);
            if (size < 0) {
                Object source = bodyClass.getMethod("j").invoke(body);
                // request() fills the buffer without consuming the host response.
                boolean overLimit = (Boolean) loader.loadClass("w90.j").getMethod("U", long.class)
                    .invoke(source, 2 * 1024 * 1024 + 1L);
                if (overLimit) return response;
            }
            byte[] bytes = (byte[]) bodyClass.getMethod("f").invoke(body);
            byte[] modified;
            try {
                byte[] decoded = bytes;
                if (encoding.equalsIgnoreCase("gzip")) {
                    try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                        decoded = PluginPackage.bounded(gzip, 2 * 1024 * 1024);
                    }
                }
                ProfileIdentity.response(url, decoded);
                if (!DiscordX.manager.messageActions.isEmpty()) decoded = MessageIdentity.response(url, decoded);
                final byte[] observed = decoded;
                DiscordX.manager.hooks.each(discordx.api.Hooks.HTTP_RESPONSE, callback -> callback.received(url, observed.clone()));
                modified = DiscordX.manager.hooks.fold(discordx.api.Hooks.HTTP_BODY, decoded,
                    (filter, value) -> java.util.Objects.requireNonNull(filter.transform(url, value.clone()), "HTTP transform returned null"));
                if (encoding.equalsIgnoreCase("gzip")) {
                    if (modified == decoded) modified = bytes;
                    else {
                        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(output)) { gzip.write(modified); }
                        modified = output.toByteArray();
                    }
                }
            }
            catch (java.io.IOException | RuntimeException error) {
                Log.e("DiscordX", "HTTP plugin failed; keeping original response", error);
                modified = bytes;
            }
            factory.write.invoke(buffer, (Object) modified);
            return result;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("DiscordX", "Could not adapt Phoenix HTTP response", error);
            return response;
        }
    }

    private static final class BodyFactory {
        final Method buffer;
        final Constructor<?> body;
        final Method write;

        BodyFactory(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> bufferClass = loader.loadClass("w90.h");
            buffer = loader.loadClass("com.discord.nativeapp.modules.bb").getMethod("discordxBuffer");
            write = bufferClass.getMethod("write", byte[].class);
            Class<?> bodyClass = loader.loadClass("g90.r0");
            body = bodyClass.getConstructor(Object.class, long.class, loader.loadClass("w90.j"), int.class);
        }

        Object create(Object type, byte[] bytes) throws ReflectiveOperationException {
            Object value = buffer.invoke(null);
            write.invoke(value, (Object) bytes);
            return body.newInstance(type, (long) bytes.length, value, 0);
        }

    }
}
