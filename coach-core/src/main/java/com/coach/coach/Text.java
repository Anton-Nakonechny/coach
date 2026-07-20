package com.coach.coach;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Static text helpers for the 字 word quiz mode. */
public final class Text {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Text() {}

    /**
     * NFD-decompose, strip diacritics ({@code \p{M}}), lowercase (ROOT locale), trim,
     * collapse inner whitespace. Used for accent- and case-insensitive grading and token
     * matching.
     */
    public static String normalizeKey(String s) {
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        String stripped = DIACRITICS.matcher(nfd).replaceAll("");
        return SPACES.matcher(stripped.toLowerCase(Locale.ROOT).trim()).replaceAll(" ");
    }
}
