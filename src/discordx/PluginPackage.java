package discordx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

final class PluginPackage {
    static final int MAX_ZIP = 8 * 1024 * 1024;
    final discordx.api.PluginInfo info;
    final byte[] archive, dex;
    final Map<String, byte[]> assets;

    private PluginPackage(byte[] archive, byte[] dex, Map<String, byte[]> assets, JSONObject manifest)
            throws IOException, JSONException {
        info = describe(manifest, sha256(archive));
        if (!sha256(dex).equals(text(manifest, "dexSha256", 64))) throw new IOException("Plugin DEX checksum differs from its manifest.");
        this.archive = archive;
        this.dex = dex;
        this.assets = assets;
    }

    private static discordx.api.PluginInfo describe(JSONObject manifest, String hash) throws IOException, JSONException {
        if (manifest.getInt("schemaVersion") != 1 || manifest.getInt("minApi") > 1 || manifest.getInt("maxApi") < 1)
            throw new IOException("This plugin does not support DiscordX API 1.");
        String id = text(manifest, "id", 64);
        if (!id.matches("[a-z][a-z0-9_.-]{1,63}")) throw new IOException("Invalid plugin ID");
        String name = text(manifest, "name", 80);
        String version = text(manifest, "version", 32);
        String description = text(manifest, "description", 512);
        String author = text(manifest, "author", 120);
        String entrypoint = text(manifest, "entrypoint", 240);
        if (!id.matches("[a-z][a-z0-9_.-]{1,63}") || !entrypoint.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))
            throw new IOException("Invalid plugin ID or entrypoint.");
        org.json.JSONArray builds = manifest.getJSONArray("discordBuilds");
        boolean compatible = false;
        for (int i = 0; i < builds.length(); i++) compatible |= builds.getInt(i) == 356207;
        if (!compatible) throw new IOException("Plugin does not support Discord build 356207");
        org.json.JSONArray required = manifest.optJSONArray("requires");
        if (required != null) for (int i = 0; i < required.length(); i++) {
            String capability = required.getString(i);
            if (!discordx.api.HostInfo.CURRENT.capabilities.contains(capability)) throw new IOException("Missing host capability: " + capability);
        }
        return new discordx.api.PluginInfo(id, name, version, description, author, entrypoint, hash);
    }

    static discordx.api.PluginInfo describe(java.io.File file, String hash) throws IOException {
        if (file.length() > MAX_ZIP) throw new IOException("Plugin package exceeds its size limit.");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
            ZipEntry manifest = zip.getEntry("manifest.json");
            if (manifest == null) throw new IOException("Missing plugin manifest");
            try (InputStream input = zip.getInputStream(manifest)) {
                return describe(new JSONObject(new String(bounded(input, 16 * 1024), StandardCharsets.UTF_8)), hash);
            }
        } catch (JSONException invalid) { throw new IOException("Invalid plugin manifest", invalid); }
    }

    static PluginPackage read(InputStream input) throws IOException {
        byte[] archive = bounded(input, MAX_ZIP);
        Map<String, byte[]> entries = new HashMap<>();
        Set<String> names = new HashSet<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (!names.add(name) || names.size() > 128 || !safePath(name)) throw new IOException("Invalid or duplicate ZIP entry.");
                if (entry.isDirectory()) continue;
                if (!(name.equals("manifest.json") || name.equals("classes.dex") || name.startsWith("assets/")))
                    throw new IOException("Unexpected plugin ZIP entry: " + name);
                byte[] bytes = bounded(zip, name.equals("manifest.json") ? 16 * 1024 : 4 * 1024 * 1024);
                total += bytes.length;
                if (total > 12 * 1024 * 1024) throw new IOException("Expanded plugin package is too large.");
                entries.put(name, bytes);
            }
        }
        byte[] manifest = entries.remove("manifest.json"), dex = entries.remove("classes.dex");
        if (manifest == null || dex == null || dex.length < 112 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x'
                || dex[3] != '\n' || dex[7] != 0) throw new IOException("ZIP requires manifest.json and a valid classes.dex.");
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(dex).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        String version = new String(dex, 4, 3, StandardCharsets.US_ASCII);
        if (!version.matches("035|037|038|039|040") || header.getInt(32) != dex.length || header.getInt(36) != 112
                || header.getInt(40) != 0x12345678) throw new IOException("Invalid or unsupported DEX header");
        java.util.zip.Adler32 checksum = new java.util.zip.Adler32();
        checksum.update(dex, 12, dex.length - 12);
        if ((int) checksum.getValue() != header.getInt(8)) throw new IOException("Invalid DEX checksum");
        try { return new PluginPackage(archive, dex, entries, new JSONObject(new String(manifest, StandardCharsets.UTF_8))); }
        catch (JSONException invalid) { throw new IOException("Invalid plugin manifest: " + invalid.getMessage(), invalid); }
    }

    static boolean safePath(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0) return false;
        for (String part : name.split("/", -1)) if (part.equals("..") || part.equals(".")) return false;
        return true;
    }

    static byte[] bounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = input.read(buffer)) != -1;) {
            if (output.size() + n > limit) throw new IOException("Plugin package exceeds its size limit.");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    private static String text(JSONObject value, String key, int limit) throws JSONException, IOException {
        Object raw = value.get(key);
        if (!(raw instanceof String)) throw new IOException("Manifest " + key + " must be text.");
        String text = ((String) raw).trim();
        if (text.isEmpty() || text.length() > limit) throw new IOException("Invalid manifest " + key + '.');
        return text;
    }

    static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
