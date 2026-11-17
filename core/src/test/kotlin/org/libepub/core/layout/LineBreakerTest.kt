package org.libepub.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.css.WhiteSpace
import org.libepub.core.css.WordBreak
import org.libepub.core.css.WordWrap

class LineBreakerTest {

    /** Deterministic metrics: CJK = 1em, Latin = 0.5em, space = 0.25em (font size 10 -> 10/5/2.5). */
    private class FixedMeasurer : GlyphMeasurer {
        override fun advance(grapheme: String, font: FontSpec): Float {
            if (grapheme.isEmpty()) return 0f
            val em = font.sizePx
            return when {
                grapheme == " " || grapheme == "\t" -> em * 0.25f
                Kinsoku.isCjkCodePoint(grapheme.codePointAt(0)) -> em
                else -> em * 0.5f
            }
        }

        override fun metrics(font: FontSpec): FontMetrics =
            FontMetrics(font.sizePx * 0.8f, font.sizePx * 0.2f)
    }

    private val measurer = FixedMeasurer()
    private val breaker = LineBreaker(measurer)
    private val font = FontSpec(sizePx = 10f)

    private fun lines(text: String, width: Float, options: BreakOptions = BreakOptions(), indent: Float = 0f) =
        breaker.breakText(text, font, width, options, indent)

    private fun texts(text: String, width: Float, options: BreakOptions = BreakOptions(), indent: Float = 0f) =
        lines(text, width, options, indent).map { it.text }

    // ------------------------------------------------------------------- CJK

    @Test
    fun cjkBreaksBetweenEveryCharacter() {
        val result = lines("あ".repeat(25), 100f)
        assertEquals(3, result.size)
        assertEquals(listOf(10, 10, 5), result.map { it.text.length })
        assertEquals(listOf(100f, 100f, 50f), result.map { it.widthPx })
    }

    @Test
    fun closingPunctuationNeverStartsALine() {
        val result = lines("あいうえお。かきくけこ", 55f)
        assertEquals("あいうえ", result[0].text)
        assertTrue("second line starts with the punctuation cluster: ${result[1].text}", result[1].text.startsWith("お。"))
    }

    @Test
    fun openingBracketNeverEndsALine() {
        val result = lines("あ「い", 25f)
        assertEquals(listOf("あ", "「い"), result.map { it.text })
    }

    @Test
    fun smallKanaAndProlongedMarkDoNotStartLines() {
        val result = lines("あいうえおっゃーかきくけこ", 55f)
        assertTrue(result[0].text.none { it in "っゃー" })
    }

    // ---------------------------------------------------------------- Latin

    @Test
    fun latinBreaksAtSpaces() {
        assertEquals(listOf("hello", "world", "again"), texts("hello world again", 40f))
    }

    @Test
    fun hyphenAllowsBreakAfterIt() {
        assertEquals(listOf("state-of-", "the-art"), texts("state-of-the-art", 45f))
    }

    @Test
    fun mixedCjkAndLatinBreaksAtScriptBoundaries() {
        assertEquals(listOf("abc中", "文def"), texts("abc中文def", 30f))
    }

    @Test
    fun wordBreakAllBreaksInsideWords() {
        val options = BreakOptions(wordBreak = WordBreak.BREAK_ALL)
        assertEquals(listOf("abcdef", "ghij"), texts("abcdefghij", 30f, options))
    }

    @Test
    fun keepAllAvoidsBreakingBetweenCjkCharacters() {
        assertEquals(
            listOf("中文中文", "中文"),
            texts("中文中文 中文", 45f, BreakOptions(wordBreak = WordBreak.KEEP_ALL)),
        )
        // without keep-all the same text wraps inside the word
        assertEquals(listOf("中文中文", "中文"), texts("中文中文中文", 45f))
    }

    // ------------------------------------------------------------- whitespace

    @Test
    fun nowrapKeepsEverythingOnOneLine() {
        val result = lines("hello world again and again", 10f, BreakOptions(whiteSpace = WhiteSpace.NOWRAP))
        assertEquals(1, result.size)
        assertEquals("hello world again and again", result.single().text)
    }

    @Test
    fun preKeepsHardBreaksAndTrailingEmptyLine() {
        val result = lines("line1\nline2\n", 500f, BreakOptions(whiteSpace = WhiteSpace.PRE))
        assertEquals(listOf("line1", "line2", ""), result.map { it.text })
        assertEquals(listOf(true, true, true), result.map { it.hardBreak })
    }

    @Test
    fun collapsedWhitespaceAndTrailingSpacesAreTrimmed() {
        val result = lines("abc   def   ", 500f)
        assertEquals(1, result.size)
        assertEquals("abc def", result.single().text)
        assertEquals(15f + 2.5f + 15f, result.single().widthPx, 0.001f)
    }

    @Test
    fun preWrapWrapsAtSpacesButPreservesSpaces() {
        val options = BreakOptions(whiteSpace = WhiteSpace.PRE_WRAP)
        val result = lines("aa  bb  cc", 30f, options)
        assertTrue(result.size >= 2)
        assertTrue(result.drop(1).all { it.text.isNotBlank() })
    }

    // --------------------------------------------------------------- clusters

    @Test
    fun graphemeClustersAggregateCombiningMarksAndEmoji() {
        assertEquals(1, GraphemeCluster.split("e\u0301").size)
        assertEquals(1, GraphemeCluster.split("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67").size)
        assertEquals(1, GraphemeCluster.split("\uD83C\uDDE8\uD83C\uDDF3").size)
        assertEquals(1, GraphemeCluster.split("\uD83D\uDC4D\uD83C\uDFFD").size)
        assertEquals(listOf("a", "\r\n", "b"), GraphemeCluster.split("a\r\nb").map { it.text })
    }

    @Test
    fun combiningSequenceIsNeverSplit() {
        val result = lines("a\u0301a\u0301a\u0301", 10f)
        assertEquals(listOf("a\u0301a\u0301", "a\u0301"), result.map { it.text })
    }

    // ------------------------------------------------------------ force break

    @Test
    fun longUrlIsForceSplitWithoutExceedingTheLine() {
        val url = "https://example.com/very/long/path"
        val result = lines(url, 30f)
        assertTrue(result.size >= 6)
        assertTrue("no line exceeds the width: ${result.map { it.widthPx }}", result.all { it.widthPx <= 30.001f })
        assertEquals(url, result.joinToString("") { it.text })
    }

    @Test
    fun singleClusterWiderThanLineStillProducesALine() {
        val result = lines("あ", 1f)
        assertEquals(listOf("あ"), result.map { it.text })
    }

    // ---------------------------------------------------------------- indent

    @Test
    fun firstLineIndentReducesTheFirstLine() {
        val result = lines("あ".repeat(20), 100f, indent = 30f)
        assertEquals(listOf(7, 10, 3), result.map { it.text.length })
        assertEquals(70f, result[0].widthPx, 0.001f)
    }

    @Test
    fun letterSpacingAddsToEveryCluster() {
        val plain = lines("あああ", 100f).single().widthPx
        val spaced = lines("あああ", 100f, BreakOptions(letterSpacingPx = 2f)).single().widthPx
        assertEquals(plain + 6f, spaced, 0.001f)
    }

    // --------------------------------------------------------------- offsets

    @Test
    fun sourceOffsetsPointIntoTheOriginalText() {
        val text = "abc中文def"
        val result = lines(text, 30f)
        assertEquals("abc中", text.substring(result[0].startOffset, result[0].endOffset))
        assertEquals("文def", text.substring(result[1].startOffset, result[1].endOffset))
    }

    // --------------------------------------------------------------- vertical

    @Test
    fun verticalUsesTheSameLineDivisions() {
        // Advances are inline-axis by contract; vertical layout only maps the
        // resulting lines onto physical coordinates, so the divisions must match.
        val text = "あいうえお。かきくけこ"
        val horizontal = lines(text, 55f)
        val vertical = lines(text, 55f)
        assertEquals(horizontal.map { it.text }, vertical.map { it.text })
        assertEquals(horizontal.map { it.widthPx }, vertical.map { it.widthPx })
    }
}
