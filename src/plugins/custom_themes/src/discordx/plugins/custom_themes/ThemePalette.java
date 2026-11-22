package discordx.plugins.custom_themes;

public final class ThemePalette {
    static final int DEFAULT_SURFACE = 0xff19252c;
    static final int DEFAULT_TEXT = 0xffecf6f1;
    static final int DEFAULT_ACCENT = 0xff8bd9b5;
    private static volatile ThemePalette current;
    final int surface, text, accent;

    ThemePalette(int surface, int text, int accent) {
        this.surface = surface;
        this.text = text;
        this.accent = accent;
    }

    static void set(ThemePalette theme) {
        current = theme;
    }

    public static int fill(int original) {
        ThemePalette palette = current;
        if (palette == null || (original >>> 24) < 16) return original;
        if (isBrand(original)) return alpha(original, palette.accent);
        if (!isNeutral(original)) return original;
        int gray = (red(original) + green(original) + blue(original)) / 3;
        int delta = Math.max(-18, Math.min(18, gray < 128 ? (gray - 40) / 2 : (gray - 236) / 2));
        return (original & 0xff000000) | (limit(red(palette.surface) + delta) << 16)
            | (limit(green(palette.surface) + delta) << 8) | limit(blue(palette.surface) + delta);
    }

    public static int foreground(int original) {
        ThemePalette palette = current;
        if (palette == null || (original >>> 24) < 16) return original;
        if (isBrand(original)) return alpha(original, palette.accent);
        return isNeutral(original) ? alpha(original, palette.text) : original;
    }

    public static int baseText(int original) {
        ThemePalette palette = current;
        // Rich-text spans carry explicit color. This covers Phoenix's neutral
        // TextView fallback without rewriting unrelated linked/semantic paint.
        return palette != null && (original >>> 24) >= 16 && isNeutral(original)
            ? alpha(original, palette.text) : original;
    }

    public static int[] gradient(int[] original) {
        if (current == null || original == null) return original;
        int[] mapped = null;
        for (int i = 0; i < original.length; i++) {
            int value = fill(original[i]);
            if (mapped == null && value != original[i]) mapped = original.clone();
            if (mapped != null) mapped[i] = value;
        }
        return mapped == null ? original : mapped;
    }

    static boolean readable(int foreground, int background) {
        double first = luminance(foreground) + 0.05;
        double second = luminance(background) + 0.05;
        return Math.max(first, second) / Math.min(first, second) >= 4.5;
    }

    private static double luminance(int color) {
        return 0.2126 * linear(red(color)) + 0.7152 * linear(green(color)) + 0.0722 * linear(blue(color));
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static boolean isNeutral(int color) {
        int r = red(color), g = green(color), b = blue(color);
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 25;
    }

    private static boolean isBrand(int color) {
        int r = red(color), g = green(color), b = blue(color);
        return r >= 65 && r <= 128 && g >= 70 && g <= 150 && b >= 220 && b - g >= 90;
    }

    private static int alpha(int source, int target) {
        return (source & 0xff000000) | (target & 0x00ffffff);
    }

    private static int red(int value) { return value >>> 16 & 0xff; }
    private static int green(int value) { return value >>> 8 & 0xff; }
    private static int blue(int value) { return value & 0xff; }
    private static int limit(int value) { return Math.max(0, Math.min(value, 255)); }
}
