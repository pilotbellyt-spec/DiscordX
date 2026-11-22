package discordx.plugins.message_logger;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import java.util.regex.Matcher;

final class MessageHistoryText {
    private static final int DELETED = 0xfff07d83;
    private static final int PREVIOUS = 0xffb8b5c3;

    private MessageHistoryText() {}

    static CharSequence render(CharSequence text) {
        if (!MessageLogger.issuedMarkers() || text == null || text.length() < 19) return text;
        Matcher match = MessageLogger.marker(text);
        if (!match.find()) return text;
        SpannableStringBuilder display = new SpannableStringBuilder(text);
        do {
            MessageLogger.Decoration history = MessageLogger.decoration(match.group());
            display.replace(match.start(), match.end(), "");
            if (history == null) return display;
            if (history.deleted) {
                color(display, 0, DELETED);
                int start = display.length();
                if (start != 0) display.append("  ");
                display.append("(deleted)");
                small(display, start, DELETED);
            }
            for (String version : history.versions) {
                int start = display.length();
                if (start != 0) display.append("\n");
                display.append(version);
                small(display, start, PREVIOUS);
            }
            match = MessageLogger.marker(display.toString());
        } while (match.find());
        return display;
    }

    static CharSequence clean(CharSequence text) {
        if (!MessageLogger.issuedMarkers() || text == null || text.length() < 19) return text;
        Matcher markers = MessageLogger.marker(text);
        if (!markers.find()) return text;
        if (text instanceof String) return MessageLogger.stripMarkers((String) text);
        SpannableStringBuilder cleaned = new SpannableStringBuilder(text);
        int removed = 0;
        do {
            cleaned.delete(markers.start() - removed, markers.end() - removed);
            removed += markers.end() - markers.start();
        } while (markers.find());
        return cleaned;
    }

    private static void small(SpannableStringBuilder text, int start, int color) {
        text.setSpan(new RelativeSizeSpan(0.86f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        color(text, start, color);
    }

    private static void color(SpannableStringBuilder text, int start, int color) {
        if (start < text.length()) {
            text.setSpan(new ForegroundColorSpan(color), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
