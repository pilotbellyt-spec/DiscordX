package discordx;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** ETF dispatches preserve the original wire encoding of every untouched term. */
final class GatewayTerms {
    static final int VERSION = 131;
    private static final int BINARY = 109;
    private static final int MAP = 116;
    private static final int LIST = 108;
    private static final int NIL = 106;
    private static final byte[] MESSAGE = "MESSAGE_".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] READY = "READY".getBytes(StandardCharsets.US_ASCII);

    private GatewayTerms() {}

    static byte[] transform(Object owner, int socket, byte[] input) {
        if (!contains(input, MESSAGE) && !contains(input, READY)) return input;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(input);
            buffer.get();
            Term root = read(buffer, 0);
            if (buffer.hasRemaining() || !(root.value instanceof JSONObject)) return input;
            byte[] json = root.value.toString().getBytes(StandardCharsets.UTF_8);
            byte[] marked = MessageLogger.onFrame(owner, socket, json);
            if (marked == json) return input;
            ByteArrayOutputStream output = new ByteArrayOutputStream(marked.length + 16);
            DataOutputStream out = new DataOutputStream(output);
            out.writeByte(VERSION);
            write(out, input, root, new JSONObject(new String(marked, StandardCharsets.UTF_8)));
            return output.toByteArray();
        } catch (Exception unsupported) {
            return input;
        }
    }

    private static boolean contains(byte[] packet, byte[] signal) {
        for (int i = 0; i <= packet.length - signal.length; i++) {
            int j = 0;
            while (j < signal.length && packet[i + j] == signal[j]) j++;
            if (j == signal.length) return true;
        }
        return false;
    }

    private static Term read(ByteBuffer buffer, int depth) throws JSONException {
        if (depth > 32) throw new IllegalArgumentException("ETF nesting");
        Term term = new Term(buffer.position());
        int tag = Byte.toUnsignedInt(buffer.get());
        switch (tag) {
            case MAP:
                JSONObject object = new JSONObject();
                term.map = new LinkedHashMap<>();
                int pairs = count(buffer, buffer.getInt(), 2);
                for (int i = 0; i < pairs; i++) {
                    Term key = read(buffer, depth + 1);
                    if (!(key.value instanceof String)) throw new IllegalArgumentException("ETF map key");
                    Term value = read(buffer, depth + 1);
                    value.key = key;
                    String name = (String) key.value;
                    if (term.map.put(name, value) != null) throw new IllegalArgumentException("ETF duplicate key");
                    object.put(name, value.value);
                }
                term.value = object;
                break;
            case LIST:
                JSONArray list = new JSONArray();
                term.list = new ArrayList<>();
                int items = count(buffer, buffer.getInt(), 1);
                for (int i = 0; i < items; i++) {
                    Term item = read(buffer, depth + 1);
                    list.put(item.value);
                    term.list.add(item);
                }
                if (Byte.toUnsignedInt(buffer.get()) != NIL) throw new IllegalArgumentException("Improper ETF list");
                term.value = list;
                break;
            case NIL:
                term.value = new JSONArray();
                break;
            case BINARY:
                term.value = utf8(buffer, buffer.getInt());
                break;
            case 107:
                term.value = utf8(buffer, Short.toUnsignedInt(buffer.getShort()));
                break;
            case 100:
            case 118:
                term.value = atom(utf8(buffer, Short.toUnsignedInt(buffer.getShort())));
                break;
            case 115:
            case 119:
                term.value = atom(utf8(buffer, Byte.toUnsignedInt(buffer.get())));
                break;
            case 97:
                term.value = Byte.toUnsignedInt(buffer.get());
                break;
            case 98:
                term.value = buffer.getInt();
                break;
            case 70:
                term.value = buffer.getDouble();
                break;
            case 110:
                term.value = bigint(buffer, Byte.toUnsignedInt(buffer.get()));
                break;
            case 111:
                term.value = bigint(buffer, buffer.getInt());
                break;
            default:
                throw new IllegalArgumentException("Unsupported ETF tag");
        }
        term.end = buffer.position();
        return term;
    }

    private static int count(ByteBuffer buffer, int value, int perItem) {
        if (value < 0 || value > buffer.remaining() / perItem) throw new IllegalArgumentException("ETF length");
        return value;
    }

    private static String utf8(ByteBuffer buffer, int length) {
        byte[] data = new byte[count(buffer, length, 1)];
        buffer.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static Object atom(String name) {
        if ("true".equals(name)) return true;
        if ("false".equals(name)) return false;
        if ("nil".equals(name) || "null".equals(name)) return JSONObject.NULL;
        return name;
    }

    private static BigInteger bigint(ByteBuffer buffer, int length) {
        if (length > 32) throw new IllegalArgumentException("ETF integer range");
        int sign = Byte.toUnsignedInt(buffer.get());
        if (sign > 1) throw new IllegalArgumentException("ETF integer sign");
        byte[] bytes = new byte[count(buffer, length, 1)];
        for (int i = bytes.length - 1; i >= 0; i--) bytes[i] = buffer.get();
        return new BigInteger(sign == 0 ? 1 : -1, bytes);
    }

    private static void write(DataOutputStream out, byte[] input, Term original, Object value)
            throws IOException, JSONException {
        if (original != null && same(original.value, value)) {
            out.write(input, original.start, original.end - original.start);
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Map<String, Term> existing = original == null ? null : original.map;
            out.writeByte(MAP);
            out.writeInt(object.length());
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                Term source = existing == null ? null : existing.get(name);
                write(out, input, source == null ? null : source.key, name);
                write(out, input, source, object.get(name));
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() == 0) {
                out.writeByte(NIL);
                return;
            }
            out.writeByte(LIST);
            out.writeInt(array.length());
            for (int i = 0; i < array.length(); i++) {
                Term old = original != null && original.list != null && i < original.list.size()
                    ? original.list.get(i) : null;
                write(out, input, old, array.get(i));
            }
            out.writeByte(NIL);
        } else if (value instanceof String) {
            byte[] text = ((String) value).getBytes(StandardCharsets.UTF_8);
            out.writeByte(BINARY);
            out.writeInt(text.length);
            out.write(text);
        } else if (value == JSONObject.NULL || value instanceof Boolean) {
            String name = value == JSONObject.NULL ? "nil" : value.toString();
            out.writeByte(119);
            out.writeByte(name.length());
            out.writeBytes(name);
        } else if (value instanceof Integer) {
            out.writeByte(98);
            out.writeInt((Integer) value);
        } else if (value instanceof Long) {
            BigInteger number = BigInteger.valueOf((Long) value);
            byte[] magnitude = number.abs().toByteArray();
            out.writeByte(110);
            out.writeByte(magnitude.length);
            out.writeByte(number.signum() < 0 ? 1 : 0);
            for (int i = magnitude.length - 1; i >= 0; i--) out.writeByte(magnitude[i]);
        } else if (value instanceof Number) {
            out.writeByte(70);
            out.writeDouble(((Number) value).doubleValue());
        } else {
            throw new IllegalArgumentException("Unsupported ETF value");
        }
    }

    private static boolean same(Object original, Object updated) {
        if (Objects.equals(original, updated)) return true;
        return (original instanceof Number && updated instanceof Number
            || original instanceof JSONObject && updated instanceof JSONObject
            || original instanceof JSONArray && updated instanceof JSONArray)
            && original.toString().equals(updated.toString());
    }

    private static final class Term {
        final int start;
        int end;
        Object value;
        Term key;
        Map<String, Term> map;
        ArrayList<Term> list;

        Term(int start) {
            this.start = start;
        }
    }
}
