package org.libepub.core.layout

/**
 * Minimal UAX #29-style grapheme cluster segmentation, implemented locally
 * rather than through `java.text.BreakIterator` so JVM and Android agree and
 * the behavior is locked by golden tests.
 *
 * Handles combining marks, variation selectors, emoji modifiers, ZWJ
 * sequences, regional-indicator pairs and CRLF. Hangul jamo composition is
 * treated as plain code points (readable, just not merged).
 */
object GraphemeCluster {

    /** One grapheme cluster with its offsets in the original string (code units). */
    data class Grapheme(val text: String, val start: Int, val end: Int)

    fun split(text: String): List<Grapheme> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Grapheme>(text.length)
        var index = 0
        while (index < text.length) {
            val start = index
            val first = text.codePointAt(index)
            index += Character.charCount(first)

            if (first == CARRIAGE_RETURN && index < text.length && text.codePointAt(index) == LINE_FEED) {
                index += 1
            } else if (isRegionalIndicator(first) &&
                index < text.length && isRegionalIndicator(text.codePointAt(index))
            ) {
                index += Character.charCount(text.codePointAt(index))
            } else {
                var joinNext = false
                while (index < text.length) {
                    val codePoint = text.codePointAt(index)
                    if (isExtend(codePoint) || isEmojiModifier(codePoint)) {
                        joinNext = codePoint == ZERO_WIDTH_JOINER
                        index += Character.charCount(codePoint)
                        continue
                    }
                    if (joinNext) {
                        joinNext = false
                        index += Character.charCount(codePoint)
                        continue
                    }
                    break
                }
            }
            out.add(Grapheme(text.substring(start, index), start, index))
        }
        return out
    }

    /** True when [text] is a single grapheme cluster. */
    fun isSingleCluster(text: String): Boolean = split(text).size <= 1

    private fun isExtend(codePoint: Int): Boolean {
        if (codePoint == ZERO_WIDTH_JOINER) return true
        if (codePoint in VARIATION_SELECTORS_1 || codePoint in VARIATION_SELECTORS_2) return true
        return when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            -> true
            else -> false
        }
    }

    private fun isEmojiModifier(codePoint: Int): Boolean = codePoint in EMOJI_MODIFIERS

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in REGIONAL_INDICATORS

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val CARRIAGE_RETURN = 0x0D
    private const val LINE_FEED = 0x0A

    private val VARIATION_SELECTORS_1 = 0xFE00..0xFE0F
    private val VARIATION_SELECTORS_2 = 0xE0100..0xE01EF
    private val EMOJI_MODIFIERS = 0x1F3FB..0x1F3FF
    private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF
}
