package org.libepub.core.layout

/**
 * Japanese/Chinese line-breaking prohibitions (kinsoku shori / 避头尾).
 *
 * Two rules cover virtually all real books:
 *  - a cluster that must not start a line ("closing" punctuation, small kana,
 *    iteration marks, prolonged sound mark);
 *  - a cluster that must not end a line ("opening" brackets, currency signs).
 *
 * The tables are deliberately compact strings so they are easy to audit and
 * extend.
 */
object Kinsoku {

    /** Must not appear at the start of a line. */
    private const val NO_BREAK_BEFORE: String =
        "\u3001\u3002\uFF0C\uFF0E\uFF1A\uFF1B\uFF1F\uFF01" + // 、。，．：；？！
            "\uFF09\uFF3D\uFF5D\u3015\u3009\u300B\u300D\u300F\u3011\u3017\u3019\u301B" + // closing brackets
            "\u2019\u201D\uFF60\u00BB" + // quotes
            "\u30FD\u30FE\u309D\u309E\u30FC\u3005\u303B" + // ヽヾゝゞー々〻
            "\u3041\u3043\u3045\u3047\u3049\u3063\u3083\u3085\u3087\u308E\u3095\u3096" + // small hiragana
            "\u30A1\u30A3\u30A5\u30A7\u30A9\u30C3\u30E3\u30E5\u30E7\u30EE\u30F5\u30F6" + // small katakana
            "\u30FB\u2026\u2025" + // ・…‥
            "%\u2030\u00B0\u2032\u2033\u2103" + // %‰°′″℃
            "\uFF1E\uFF3E" // ＞＾

    /** Must not appear at the end of a line. */
    private const val NO_BREAK_AFTER: String =
        "\uFF08\uFF3B\uFF5B\u3014\u3008\u300A\u300C\u300E\u3010\u3016\u3018\u301A" + // opening brackets
            "\u2018\u201C\uFF5F\u00AB" + // quotes
            "$\uFFE5\uFF04" // currency prefixes

    fun prohibitsBreakBefore(grapheme: String): Boolean =
        grapheme.isNotEmpty() && grapheme[0] in NO_BREAK_BEFORE

    fun prohibitsBreakAfter(grapheme: String): Boolean =
        grapheme.isNotEmpty() && grapheme[0] in NO_BREAK_AFTER

    /** True when the cluster belongs to a CJK script (where inter-character breaks are normal). */
    fun isCjk(grapheme: String): Boolean {
        if (grapheme.isEmpty()) return false
        return isCjkCodePoint(grapheme.codePointAt(0))
    }

    fun isCjkCodePoint(codePoint: Int): Boolean = when (codePoint) {
        in 0x1100..0x11FF, // Hangul jamo
        in 0x2E80..0x2EFF, // CJK radicals supplement
        in 0x2F00..0x2FDF, // Kangxi radicals
        in 0x3000..0x303F, // CJK symbols and punctuation
        in 0x3040..0x309F, // Hiragana
        in 0x30A0..0x30FF, // Katakana
        in 0x3100..0x312F, // Bopomofo
        in 0x31C0..0x31EF, // CJK strokes
        in 0x3200..0x32FF, // enclosed CJK
        in 0x3400..0x4DBF, // CJK extension A
        in 0x4E00..0x9FFF, // CJK unified ideographs
        in 0xAC00..0xD7AF, // Hangul syllables
        in 0xF900..0xFAFF, // CJK compatibility ideographs
        in 0xFE30..0xFE4F, // CJK compatibility forms
        in 0xFF00..0xFFEF, // Halfwidth and fullwidth forms
        in 0x20000..0x2FA1F, // CJK extensions B..F
        -> true
        else -> false
    }

    /** True when a line break is allowed between two adjacent clusters, ignoring whitespace. */
    fun allowsBreakBetween(previous: String, next: String): Boolean {
        if (prohibitsBreakBefore(next)) return false
        if (prohibitsBreakAfter(previous)) return false
        if (isCjk(previous) || isCjk(next)) return true
        return previous == "-" || previous == "\u00AD" // hyphen / soft hyphen
    }
}
