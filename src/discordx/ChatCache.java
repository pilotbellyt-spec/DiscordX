package discordx;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Read individual records from the active Phoenix account, never guess between accounts. */
final class ChatCache {
    private final File store;
    volatile String account;
    ChatCache(File files) { store = new File(files, "store"); }

    JSONObject message(String channel, String message) {
        if (!Ids.snowflake(channel) || !Ids.snowflake(message)) throw new IllegalArgumentException("Invalid message identity");
        return read("SELECT v FROM messages WHERE k=?", padded(channel) + ":" + padded(message));
    }

    private static String padded(String id) { return ("00000000000000000000" + id).substring(id.length()); }

    String selectedChannel() {
        JSONObject selected = read("SELECT v FROM meta WHERE k=?", "selected");
        String channel = selected == null ? null : selected.optString("channel_id");
        return Ids.snowflake(channel) ? channel : null;
    }

    private File database() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Use runAsync for cache queries");
        String user = account;
        File database;
        if (user != null) database = new File(new File(store, user), "cache.sqlite");
        else {
            File[] accounts = store.listFiles(dir -> new File(dir, "cache.sqlite").isFile());
            if (accounts == null || accounts.length != 1) return null;
            database = new File(accounts[0], "cache.sqlite");
        }
        return database.isFile() ? database : null;
    }

    org.json.JSONArray query(String select, String[] args) {
        String sql = select.trim();
        if (!sql.toUpperCase(java.util.Locale.ROOT).startsWith("SELECT ") || sql.indexOf(';') >= 0)
            throw new IllegalArgumentException("Expected a single SELECT");
        File path = database();
        org.json.JSONArray result = new org.json.JSONArray();
        if (path == null) return result;
        android.os.CancellationSignal cancel = new android.os.CancellationSignal();
        android.os.Handler timer = new android.os.Handler(Looper.getMainLooper());
        Runnable timeout = cancel::cancel;
        timer.postDelayed(timeout, 2000);
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
             Cursor cursor = db.rawQuery("SELECT * FROM (" + sql + ") LIMIT 500", args, cancel)) {
            int bytes = 0;
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    Object value;
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: value = cursor.getLong(i); break;
                        case Cursor.FIELD_TYPE_FLOAT: value = cursor.getDouble(i); break;
                        case Cursor.FIELD_TYPE_BLOB:
                            value = android.util.Base64.encodeToString(cursor.getBlob(i), android.util.Base64.NO_WRAP); break;
                        case Cursor.FIELD_TYPE_STRING: value = cursor.getString(i); break;
                        default: value = JSONObject.NULL;
                    }
                    row.put(cursor.getColumnName(i), value);
                }
                bytes += row.toString().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > 1024 * 1024) break;
                result.put(row);
            }
            return result;
        } catch (JSONException invalid) { throw new IllegalArgumentException(invalid); }
        finally { timer.removeCallbacks(timeout); }
    }

    private JSONObject read(String query, String key) {
        File database = database();
        if (database == null) return null;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
             Cursor row = db.rawQuery(query, new String[] {key})) {
            if (!row.moveToFirst() || row.isNull(0)) return null;
            String value = row.getType(0) == Cursor.FIELD_TYPE_BLOB
                ? new String(row.getBlob(0), StandardCharsets.UTF_8) : row.getString(0);
            return new JSONObject(value);
        } catch (android.database.sqlite.SQLiteException | JSONException unavailable) { return null; }
    }
}
