package discordx.api;

import android.text.SpannableStringBuilder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Process-local decoration tokens are always removed from copied/composed text. */
public final class TextTokens {
    private static final Pattern ALL = Pattern.compile("\\u2063(?:DXP[0-9a-f]{24}|DXH[0-9a-f]{16})\\u2063");
    private TextTokens() {}

    public static CharSequence clean(CharSequence text) {
        if (text == null) return null;
        Matcher tokens = ALL.matcher(text);
        if (!tokens.find()) return text;
        if (text instanceof String) return tokens.replaceAll("");
        SpannableStringBuilder clean = new SpannableStringBuilder(text);
        do {
            clean.delete(tokens.start(), tokens.end());
            tokens = ALL.matcher(clean);
        } while (tokens.find());
        return clean;
    }
}
