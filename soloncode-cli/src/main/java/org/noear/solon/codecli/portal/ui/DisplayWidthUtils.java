package org.noear.solon.codecli.portal.ui;

/**
 * Terminal-oriented display width helpers.
 */
public final class DisplayWidthUtils {
    private DisplayWidthUtils() {
    }

    public static int displayWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += charDisplayWidth(codePoint);
            i += Character.charCount(codePoint);
        }

        return width;
    }

    public static String clipToWidth(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }

        if (displayWidth(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int targetWidth = Math.max(0, maxWidth - displayWidth(ellipsis));
        StringBuilder builder = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charWidth = charDisplayWidth(codePoint);
            if (width + charWidth > targetWidth) {
                break;
            }
            builder.appendCodePoint(codePoint);
            width += charWidth;
            i += Character.charCount(codePoint);
        }

        builder.append(ellipsis);
        return builder.toString();
    }

    private static int charDisplayWidth(int codePoint) {
        if (codePoint == 0x200D || codePoint == 0xFE0E || codePoint == 0xFE0F) {
            return 0;
        }

        if (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF) {
            return 0;
        }

        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK
            || type == Character.FORMAT) {
            return 0;
        }

        if (isWideCjk(codePoint) || isWideEmoji(codePoint)) {
            return 2;
        }

        if (Character.isSupplementaryCodePoint(codePoint)) {
            return 2;
        }

        if (Character.isISOControl(codePoint)) {
            return 0;
        }

        return 1;
    }

    private static boolean isWideEmoji(int codePoint) {
        return (codePoint >= 0x2300 && codePoint <= 0x23FF)
            || (codePoint >= 0x2600 && codePoint <= 0x27BF)
            || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF);
    }

    private static boolean isWideCjk(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
            || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT
            || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            || block == Character.UnicodeBlock.HIRAGANA
            || block == Character.UnicodeBlock.KATAKANA
            || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
            || block == Character.UnicodeBlock.HANGUL_JAMO
            || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A
            || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B
            || block == Character.UnicodeBlock.HANGUL_SYLLABLES
            || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
            || block == Character.UnicodeBlock.BOPOMOFO
            || block == Character.UnicodeBlock.BOPOMOFO_EXTENDED
            || block == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS
            || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
