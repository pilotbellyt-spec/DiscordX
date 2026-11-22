package discordx;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** The decoded render transaction in Phoenix build 356207. */
final class PhoenixRender {
    static final int CREATE = 1, UPDATE = 2, CHILDREN = 3, DELETE = 4;
    private static final String MODULE = "com.discord.nativeapp.modules.";
    private static final java.util.Set<String> TEMPLATE_PROPERTIES = new java.util.HashSet<>(Arrays.asList(
        "width", "height", "minWidth", "maxWidth", "minHeight", "maxHeight", "flex", "direction", "align",
        "justify", "alignSelf", "gap", "padding", "margin", "radius", "clip", "background", "activeBackground",
        "hoverBackground", "borderColor", "borderWidth", "opacity", "fontSize", "fontWeight", "fontFamily",
        "lineHeight", "lineHeightExact", "textAlign", "text", "color", "uri", "scale", "tint"));

    private final Constructor<?> transaction, pool, apply, array;
    private final Field count, metadata, values, children, batchProperties, propertyPool;
    private final Field used, propertyIds, tags, numbers, objects, floatCount, floats;
    private final Method put, putInsets, arrayAdd, applyNow, complete;
    private final String[] propertyNames;
    private final Map<?, ?> propertyCodes;
    private final Field payloadFields, rendererViews;
    private final Field listValues;

    PhoenixRender(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> batch = loader.loadClass(MODULE + "rq");
        Class<?> operand = loader.loadClass("kz.d");
        Class<?> runner = loader.loadClass(MODULE + "j");
        Class<?> list = loader.loadClass("lh.i3");
        Class<?> keys = loader.loadClass("lh.c2");
        transaction = batch.getConstructor(int.class, int[].class, Object[].class, int[].class, operand, boolean.class);
        pool = operand.getConstructor(int.class);
        apply = runner.getConstructor(int.class, Object.class, Object.class);
        array = list.getConstructor();
        applyNow = runner.getMethod("invoke");
        complete = loader.loadClass("kotlin.jvm.functions.Function0").getMethod("invoke");
        put = operand.getMethod("a", int.class, byte.class, long.class, Object.class);
        putInsets = operand.getMethod("c", float.class, float.class, float.class, float.class, int.class);
        arrayAdd = list.getMethod("f", Object.class);
        count = batch.getField("a");
        metadata = batch.getField("b");
        children = batch.getField("c");
        values = batch.getField("d");
        batchProperties = batch.getField("e");
        propertyPool = loader.loadClass("b0.b").getField("c");
        used = operand.getField("d");
        propertyIds = operand.getField("v");
        tags = operand.getField("e");
        numbers = operand.getField("w");
        objects = operand.getField("x");
        floatCount = operand.getField("i");
        floats = operand.getField("y");
        propertyNames = (String[]) keys.getField("a").get(null);
        propertyCodes = (Map<?, ?>) keys.getField("b").get(null);
        payloadFields = loader.loadClass("lh.j3").getField("a");
        rendererViews = loader.loadClass(MODULE + "gq").getField("c");
        listValues = list.getField("a");
    }

    Batch read(Object transaction) throws IllegalAccessException {
        return new Batch(count.getInt(transaction), (int[]) metadata.get(transaction),
            (Object[]) values.get(transaction), (int[]) children.get(transaction),
            propertyPool.get(batchProperties.get(transaction)));
    }

    String inputText(Object payload) throws IllegalAccessException {
        Map<?, ?> fields = (Map<?, ?>) payloadFields.get(payload);
        for (String key : new String[] {"text", "value"}) {
            Object value = fields.get(key);
            if (value instanceof String) return (String) value;
        }
        return null;
    }

    void complete(Object callback) throws ReflectiveOperationException {
        if (callback != null) complete.invoke(callback);
    }

    android.view.View view(Object renderer, int id) throws IllegalAccessException {
        return (android.view.View) ((Map<?, ?>) rendererViews.get(renderer)).get(id);
    }

    boolean containsText(Object value, String expected, int depth) throws IllegalAccessException {
        if (depth == 0 || value == null) return false;
        if (value instanceof String) return expected.equals(value);
        Iterable<?> values;
        if (listValues.getDeclaringClass().isInstance(value)) values = (Iterable<?>) listValues.get(value);
        else if (payloadFields.getDeclaringClass().isInstance(value)) values = ((Map<?, ?>) payloadFields.get(value)).values();
        else return false;
        int inspected = 0;
        for (Object item : values) {
            if (++inspected > 12) break;
            if (containsText(item, expected, depth - 1)) return true;
        }
        return false;
    }

    final class Batch {
        final int count;
        final int[] operations, ids;
        final Object[] types;
        final Object pool;
        final int[] keys;
        final byte[] tags;
        final long[] numbers;
        final Object[] objects;

        Batch(int count, int[] operations, Object[] types, int[] ids, Object pool) throws IllegalAccessException {
            this.count = count;
            this.operations = operations;
            this.types = types;
            this.ids = ids;
            this.pool = pool;
            keys = (int[]) propertyIds.get(pool);
            tags = (byte[]) PhoenixRender.this.tags.get(pool);
            numbers = (long[]) PhoenixRender.this.numbers.get(pool);
            objects = (Object[]) PhoenixRender.this.objects.get(pool);
        }

        int kind(int row) { return operations[row * 4]; }
        int id(int row) { return operations[row * 4 + 1]; }
        int start(int row) { return operations[row * 4 + 2]; }
        int size(int row) { return operations[row * 4 + 3]; }
        String key(int index) { return propertyNames[keys[index]]; }
        int[] children(int row) { return Arrays.copyOfRange(ids, start(row), start(row) + size(row)); }
    }

    Builder addTo(Batch base) throws ReflectiveOperationException { return new Builder(base); }

    void submit(Object renderer, Builder builder) throws ReflectiveOperationException {
        applyNow.invoke(apply.newInstance(24, renderer, builder.build()));
    }

    final class Builder {
        private final ArrayList<Integer> ops = new ArrayList<>();
        private final ArrayList<Object> payloads = new ArrayList<>();
        private final ArrayList<Integer> childIds = new ArrayList<>();
        private final Object operands;

        Builder(Batch base) throws ReflectiveOperationException {
            int size = base == null ? 0 : used.getInt(base.pool);
            operands = pool.newInstance(size + 64);
            if (base == null) return;
            used.setInt(operands, size);
            propertyIds.set(operands, Arrays.copyOf(base.keys, size + 64));
            tags.set(operands, Arrays.copyOf(base.tags, size + 64));
            numbers.set(operands, Arrays.copyOf(base.numbers, size + 64));
            objects.set(operands, Arrays.copyOf(base.objects, size + 64));
            floatCount.setInt(operands, floatCount.getInt(base.pool));
            floats.set(operands, ((float[]) floats.get(base.pool)).clone());
            for (int i = 0; i < base.count; i++) {
                add(base.kind(i), base.id(i), base.start(i), base.size(i), base.types[i]);
            }
            // The host reuses its child array entries, including offsets in other op types.
            for (int id : base.ids) childIds.add(id);
        }

        void create(int id, String type, Object... properties) throws ReflectiveOperationException {
            props(CREATE, id, type, properties);
        }

        void update(int id, Object... properties) throws ReflectiveOperationException {
            props(UPDATE, id, null, properties);
        }

        private void props(int op, int id, String type, Object[] properties) throws ReflectiveOperationException {
            int start = used.getInt(operands);
            putProperties(properties);
            add(op, id, start, used.getInt(operands) - start, type);
        }

        void mirror(Batch source, int origin, int id, String type, Object... overrides) throws ReflectiveOperationException {
            int start = used.getInt(operands);
            Map<String, Integer> picked = new LinkedHashMap<>();
            for (int row = 0; row < source.count; row++) {
                if (source.id(row) != origin || source.kind(row) != CREATE && source.kind(row) != UPDATE) continue;
                for (int slot = source.start(row); slot < source.start(row) + source.size(row); slot++) {
                    String key = source.key(slot);
                    if (TEMPLATE_PROPERTIES.contains(key)) picked.put(key, slot);
                }
            }
            for (int i = 0; i < overrides.length; i += 2) picked.remove(overrides[i]);
            for (int slot : picked.values()) {
                // This builder copied the original pool, so packed inset offsets are still valid.
                put.invoke(operands, source.keys[slot], source.tags[slot], source.numbers[slot], source.objects[slot]);
            }
            putProperties(overrides);
            add(CREATE, id, start, used.getInt(operands) - start, type);
        }

        private void putProperties(Object[] properties) throws ReflectiveOperationException {
            for (int i = 0; i < properties.length; i += 2) {
                int key = (Integer) propertyCodes.get(properties[i]);
                Object value = properties[i + 1];
                if (value instanceof float[]) {
                    float[] sides = (float[]) value;
                    putInsets.invoke(operands, sides[0], sides[1], sides[2], sides[3], key);
                } else if (value instanceof String[]) {
                    Object list = array.newInstance();
                    for (String item : (String[]) value) arrayAdd.invoke(list, item);
                    put.invoke(operands, key, (byte) 6, 0L, list);
                } else if (value instanceof String) {
                    put.invoke(operands, key, (byte) 6, 0L, value);
                } else if (value instanceof Boolean) {
                    put.invoke(operands, key, (byte) ((Boolean) value ? 2 : 1), 0L, null);
                } else if (value instanceof Long) {
                    put.invoke(operands, key, (byte) 4, (Long) value, null);
                } else if (value instanceof Integer) {
                    put.invoke(operands, key, (byte) 3, ((Integer) value).longValue(), null);
                } else {
                    throw new IllegalArgumentException("Unsupported render property " + properties[i]);
                }
            }
        }

        void children(int parent, int... ids) {
            ids(CHILDREN, parent, ids);
        }

        void delete(int... ids) {
            if (ids.length > 0) ids(DELETE, 0, ids);
        }

        private void ids(int kind, int parent, int[] ids) {
            add(kind, parent, childIds.size(), ids.length, null);
            for (int id : ids) childIds.add(id);
        }

        private void add(int kind, int id, int start, int size, Object payload) {
            ops.add(kind);
            ops.add(id);
            ops.add(start);
            ops.add(size);
            payloads.add(payload);
        }

        Object build() throws ReflectiveOperationException {
            return transaction.newInstance(payloads.size(), integers(ops), payloads.toArray(), integers(childIds), operands, false);
        }
    }

    static int[] integers(ArrayList<Integer> list) {
        int[] result = new int[list.size()];
        for (int i = 0; i < result.length; i++) result[i] = list.get(i);
        return result;
    }
}
