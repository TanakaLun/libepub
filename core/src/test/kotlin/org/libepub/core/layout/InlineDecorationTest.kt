package org.libepub.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.css.CssColor
import org.libepub.core.css.CssParser
import org.libepub.core.css.WritingMode
import org.libepub.core.model.Document
import org.libepub.core.style.StyleResolver
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.XhtmlParser

class InlineDecorationTest {

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

    private class Fixture(val doc: Document, val layout: ChapterLayout)

    private val measurer = FixedMeasurer()

    private fun layout(
        html: String,
        css: String,
        writingMode: WritingMode = WritingMode.HORIZONTAL_TB,
        width: Float = 200f,
    ): Fixture {
        val warnings = WarningSink()
        val sheet = CssParser.parse(css, "OEBPS/Styles", warnings)
        val doc = XhtmlParser.parse(html.trimIndent(), "OEBPS/Text/x.xhtml", warnings)
        val styles = StyleResolver(listOf(sheet), warnings = warnings).resolve(doc)
        val engine = BlockLayoutEngine(measurer, ImageSizeProvider.NONE)
        return Fixture(doc, engine.layout(doc, styles, LayoutConfig(width, 400f, writingMode)))
    }

    private val base = """
        html, body, div, p { margin: 0; padding: 0 }
        body { font-size: 10px; line-height: 1 }
    """.trimIndent()

    private fun line(fixture: Fixture, index: Int = 0): LayoutLine = fixture.layout.lines[index]

    // -------------------------------------------------------------------- ruby

    @Test
    fun rubyReservesAnnotationSpaceAndCentersIt() {
        val fixture = layout(
            "<html><body><p><ruby>漢<rt>か</rt></ruby></p></body></html>",
            base,
        )
        val line = line(fixture)
        // annotation band (5) + base ascent (8) + base descent (2)
        assertEquals(15f, line.blockSize, 0.01f)
        assertEquals(13f, line.baseline, 0.01f)
        assertEquals("漢", line.text)
        val annotation = line.rubyAnnotations.single()
        assertEquals("か", annotation.text)
        // centered over the 10px base: 0 + (10 - 5) / 2
        assertEquals(2.5f, annotation.main, 0.01f)
        assertEquals(5f, annotation.mainSize, 0.01f)
        assertEquals(0f, annotation.block, 0.01f)
        assertEquals(5f, annotation.blockSize, 0.01f)
        // base text sits below the annotation band
        val glyph = line.fragments.single { it.kind == FragmentKind.TEXT }
        assertEquals(5f, glyph.block, 0.01f)
    }

    @Test
    fun rubyDistributesLongAnnotationOverMultipleBaseChars() {
        val fixture = layout(
            "<html><body><p><ruby>漢字<rt>かんじ</rt></ruby></p></body></html>",
            base,
        )
        val annotations = line(fixture).rubyAnnotations
        assertEquals(listOf("かん", "じ"), annotations.map { it.text })
        // 3 annotation glyphs over 2 base chars: first gets two, second one
        assertEquals(listOf(0f, 12.5f), annotations.map { it.main })
        assertEquals(listOf(10f, 5f), annotations.map { it.mainSize })
    }

    @Test
    fun rubyRpFallbackIsHidden() {
        val fixture = layout(
            "<html><body><p><ruby>漢<rp>(</rp><rt>かん</rt><rp>)</rp></ruby></p></body></html>",
            base,
        )
        val line = line(fixture)
        assertEquals("漢", line.text)
        assertEquals("かん", line.rubyAnnotations.single().text)
        assertFalse(line.fragments.any { it.text?.contains("(") == true })
        assertFalse(line.fragments.any { it.text?.contains(")") == true })
    }

    // ----------------------------------------------------------- vertical align

    @Test
    fun supAndSubOffsetFragments() {
        val fixture = layout(
            "<html><body><p>x<sup>s</sup><sub>u</sub></p></body></html>",
            base + "\nsup { vertical-align: super } sub { vertical-align: sub }",
        )
        val fragments = line(fixture).fragments
        val normal = fragments.first { it.text == "x" }
        val sup = fragments.first { it.text == "s" }
        val sub = fragments.first { it.text == "u" }
        assertTrue(sup.block < normal.block)
        assertTrue(sub.block > normal.block)
    }

    @Test
    fun verticalAlignTopMiddleBottom() {
        val fixture = layout(
            """
            <html><body><p>t<span style="vertical-align: top; font-size: 20px">T</span><span style="vertical-align: middle; font-size: 20px">M</span><span style="vertical-align: bottom; font-size: 20px">B</span></p></body></html>
            """,
            base + "\np { line-height: 40px }",
        )
        val line = line(fixture)
        val top = line.fragments.first { it.text == "T" }
        val middle = line.fragments.first { it.text == "M" }
        val bottom = line.fragments.first { it.text == "B" }
        assertEquals(40f, line.blockSize, 0.01f)
        assertEquals(0f, top.block, 0.01f)
        assertEquals(10f, middle.block, 0.01f)
        assertEquals(20f, bottom.block, 0.01f)
    }

    // ---------------------------------------------------------------- emphasis

    @Test
    fun emphasisMarksSitAboveEachCjkGlyph() {
        val fixture = layout(
            "<html><body><p>漢字</p></body></html>",
            base + "\np { text-emphasis: filled circle }",
        )
        val line = line(fixture)
        val glyph = line.fragments.single { it.kind == FragmentKind.TEXT }
        val marks = line.emphasisMarks
        assertEquals(listOf("\u25CF", "\u25CF"), marks.map { it.mark })
        // synthetic measurer treats ● as half-width: (10 - 5) / 2 offset per glyph
        assertEquals(listOf(2.5f, 12.5f), marks.map { it.main })
        assertEquals(10f, marks[1].main - marks[0].main, 0.01f)
        assertTrue(marks.all { it.block < glyph.block })
        assertTrue(marks.all { it.mainSize == 5f })
    }

    @Test
    fun emphasisOpenCircleUsesHollowGlyph() {
        val fixture = layout(
            "<html><body><p>漢</p></body></html>",
            base + "\np { text-emphasis: open circle }",
        )
        assertEquals("\u25CB", line(fixture).emphasisMarks.single().mark)
    }

    @Test
    fun emphasisSesameKeepsItsColor() {
        val fixture = layout(
            "<html><body><p>漢</p></body></html>",
            base + "\np { text-emphasis: sesame #ff0000 }",
        )
        val mark = line(fixture).emphasisMarks.single()
        assertEquals("\uFE45", mark.mark)
        assertEquals(CssColor.rgb(0xFF, 0x00, 0x00), mark.style.color)
    }

    // -------------------------------------------------------------- decoration

    @Test
    fun underlineSitsBelowTheBaseline() {
        val fixture = layout(
            "<html><body><p>漢字</p></body></html>",
            base + "\np { text-decoration: underline }",
        )
        val line = line(fixture)
        val stroke = line.decorations.single()
        assertEquals(DecorationLine.UNDERLINE, stroke.line)
        assertTrue(stroke.block > line.baseline)
        assertTrue(stroke.thickness >= 1f)
        assertEquals(0f, stroke.main, 0.01f)
        assertEquals(20f, stroke.mainSize, 0.01f)
        assertEquals(line.fragments.single().style.color, stroke.color)
    }

    @Test
    fun lineThroughCrossesTheMiddleOfTheAscent() {
        val fixture = layout(
            "<html><body><p>漢字</p></body></html>",
            base + "\np { text-decoration: line-through }",
        )
        val line = line(fixture)
        val stroke = line.decorations.single()
        assertEquals(DecorationLine.LINE_THROUGH, stroke.line)
        assertTrue(stroke.block > line.block)
        assertTrue(stroke.block < line.baseline)
    }

    @Test
    fun textDecorationNoneClearsInheritedDecoration() {
        val fixture = layout(
            "<html><body><p class=\"u\">a<span class=\"n\">b</span></p></body></html>",
            base + "\n.u { text-decoration: underline }\n.n { text-decoration: none }",
        )
        val strokes = line(fixture).decorations
        assertEquals(1, strokes.size)
        assertEquals(5f, strokes.single().mainSize, 0.01f)
        assertEquals(0f, strokes.single().main, 0.01f)
    }

    @Test
    fun textShadowKeepsEveryLayer() {
        val fixture = layout(
            "<html><body><p>a</p></body></html>",
            base + "\np { text-shadow: 1px 1px 0 red, 2px 2px 1px blue }",
        )
        val shadows = line(fixture).fragments.single().style.textShadow
        assertEquals(2, shadows.size)
        assertEquals(1f, shadows[0].x.value, 0.01f)
        assertEquals(2f, shadows[1].x.value, 0.01f)
        assertEquals(1f, shadows[1].blur.value, 0.01f)
    }

    // ---------------------------------------------------------------- vertical

    @Test
    fun verticalCjkStaysUprightWhileLatinRotates() {
        val fixture = layout(
            "<html><body><p>漢a</p></body></html>",
            base,
            writingMode = WritingMode.VERTICAL_RL,
        )
        val orientations = line(fixture).orientations
        assertEquals(listOf("漢", "a"), orientations.map { it.grapheme })
        assertTrue(orientations[0].upright)
        assertFalse(orientations[1].upright)
        assertEquals(listOf(0f, 10f), orientations.map { it.main })
    }

    @Test
    fun verticalEmphasisSitsOnTheRightOfTheColumn() {
        val fixture = layout(
            "<html><body><p>漢</p></body></html>",
            base + "\np { text-emphasis: filled circle }",
            writingMode = WritingMode.VERTICAL_RL,
        )
        val line = line(fixture)
        val glyph = line.fragments.single { it.kind == FragmentKind.TEXT }
        val mark = line.emphasisMarks.single()
        val glyphRect = VerticalText.toPhysical(
            glyph.main, glyph.block, glyph.mainSize, glyph.blockSize, WritingMode.VERTICAL_RL, 200f,
        )
        val markRect = VerticalText.toPhysical(
            mark.main, mark.block, mark.mainSize, mark.blockSize, WritingMode.VERTICAL_RL, 200f,
        )
        assertTrue("mark is block-before (right in vertical-rl)", mark.block < glyph.block)
        assertTrue("mark is drawn to the right: $markRect vs $glyphRect", markRect.x >= glyphRect.right - 0.01f)
    }

    @Test
    fun verticalRubySitsOnTheRightOfTheBase() {
        val fixture = layout(
            "<html><body><p><ruby>漢<rt>か</rt></ruby></p></body></html>",
            base,
            writingMode = WritingMode.VERTICAL_RL,
        )
        val line = line(fixture)
        val baseFragment = line.fragments.single { it.kind == FragmentKind.TEXT }
        val annotation = line.rubyAnnotations.single()
        val baseRect = VerticalText.toPhysical(
            baseFragment.main, baseFragment.block, baseFragment.mainSize, baseFragment.blockSize,
            WritingMode.VERTICAL_RL, 200f,
        )
        val annotationRect = VerticalText.toPhysical(
            annotation.main, annotation.block, annotation.mainSize, annotation.blockSize,
            WritingMode.VERTICAL_RL, 200f,
        )
        assertTrue(annotation.block < baseFragment.block)
        assertTrue(annotationRect.x >= baseRect.right - 0.01f)
    }
}
