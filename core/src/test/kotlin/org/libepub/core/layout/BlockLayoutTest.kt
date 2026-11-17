package org.libepub.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.css.CssParser
import org.libepub.core.css.WritingMode
import org.libepub.core.model.Document
import org.libepub.core.style.StyleResolver
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.XhtmlParser

class BlockLayoutTest {

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
        width: Float = 200f,
        height: Float = 400f,
        writingMode: WritingMode = WritingMode.HORIZONTAL_TB,
        images: Map<String, IntrinsicSize> = emptyMap(),
    ): Fixture {
        val warnings = WarningSink()
        val sheet = CssParser.parse(css, "OEBPS/Styles", warnings)
        val doc = XhtmlParser.parse(html.trimIndent(), "OEBPS/Text/x.xhtml", warnings)
        val styles = StyleResolver(listOf(sheet), warnings = warnings).resolve(doc)
        val provider = object : ImageSizeProvider {
            override fun intrinsicSize(src: String): IntrinsicSize? = images[src]
        }
        val engine = BlockLayoutEngine(measurer, provider)
        return Fixture(doc, engine.layout(doc, styles, LayoutConfig(width, height, writingMode)))
    }

    private val base = """
        html, body, div, p { margin: 0; padding: 0 }
        body { font-size: 10px; line-height: 1 }
    """.trimIndent()

    private fun box(fixture: Fixture, name: String): LaidOutBox =
        fixture.layout.boxes.first { it.node?.name == name }

    private fun boxes(fixture: Fixture, name: String): List<LaidOutBox> =
        fixture.layout.boxes.filter { it.node?.name == name }

    // ------------------------------------------------------------- block flow

    @Test
    fun paragraphsStackWithCollapsedMargins() {
        val fixture = layout(
            "<html><body><p>a</p><p>b</p></body></html>",
            base + "\np { margin: 10px 0 }",
        )
        val paragraphs = boxes(fixture, "p")
        assertEquals(2, paragraphs.size)
        assertEquals(10f, paragraphs[0].block, 0.01f)
        assertEquals(30f, paragraphs[1].block, 0.01f)
        assertEquals(listOf(10f, 30f), fixture.layout.lines.map { it.block })
        assertEquals(10f, paragraphs[0].blockSize, 0.01f)
    }

    @Test
    fun anonymousBlocksWrapMixedInlineRuns() {
        val fixture = layout(
            "<html><body><div>text<p>block</p>tail</div></body></html>",
            base,
        )
        assertEquals(listOf("text", "block", "tail"), fixture.layout.lines.map { it.text })
        assertEquals(listOf(0f, 10f, 20f), fixture.layout.lines.map { it.block })
        val div = box(fixture, "div")
        assertEquals(30f, div.blockSize, 0.01f)
    }

    @Test
    fun nestedPaddingShrinksContentAndShiftsIt() {
        val fixture = layout(
            "<html><body><div><p>abcdefghij</p></div></body></html>",
            base + "\ndiv { padding: 10px }",
        )
        val div = box(fixture, "div")
        assertEquals(180f, div.contentMainSize, 0.01f)
        assertEquals(10f, div.contentMain, 0.01f)
        val line = fixture.layout.lines.first()
        assertEquals(10f, line.main, 0.01f)
        assertTrue(line.mainSize <= 180.01f)
    }

    @Test
    fun boxSizingBorderBoxSubtractsPaddingAndBorder() {
        val fixture = layout(
            "<html><body><div style=\"width: 100px; box-sizing: border-box; padding: 10px; border: 2px solid\">x</div></body></html>",
            base,
        )
        val div = box(fixture, "div")
        assertEquals(76f, div.contentMainSize, 0.01f)
    }

    // -------------------------------------------------------------- inline text

    @Test
    fun textIndentShiftsOnlyTheFirstLine() {
        val fixture = layout(
            "<html><body><p>${"あ".repeat(15)}</p></body></html>",
            base + "\np { text-indent: 2em }",
            width = 100f,
        )
        val lines = fixture.layout.lines
        assertEquals(2, lines.size)
        assertEquals(20f, lines[0].main, 0.01f)
        assertEquals(0f, lines[1].main, 0.01f)
        assertEquals(8, lines[0].text.length)
        assertEquals(7, lines[1].text.length)
    }

    @Test
    fun lineHeightControlsLineSpacing() {
        val fixture = layout(
            "<html><body><p>${"あ".repeat(15)}</p></body></html>",
            base + "\np { line-height: 20px }",
            width = 100f,
        )
        assertEquals(listOf(0f, 20f), fixture.layout.lines.map { it.block })
        assertEquals(20f, fixture.layout.lines[0].blockSize, 0.01f)
    }

    @Test
    fun centerAndRightAlignmentShiftLines() {
        val center = layout(
            "<html><body><p>abc</p></body></html>",
            base + "\np { text-align: center }",
        )
        assertEquals(92.5f, center.layout.lines.single().main, 0.01f)

        val right = layout(
            "<html><body><p>abc</p></body></html>",
            base + "\np { text-align: right }",
        )
        assertEquals(185f, right.layout.lines.single().main, 0.01f)
    }

    @Test
    fun justifyAddsExtraSpaceExceptOnTheLastLine() {
        val fixture = layout(
            "<html><body><p>aaaa bbbb cccc dddd</p></body></html>",
            base + "\np { text-align: justify }",
            width = 70f,
        )
        val lines = fixture.layout.lines
        assertEquals(2, lines.size)
        assertEquals("aaaa bbbb cccc", lines[0].text)
        assertEquals(5f, lines[0].justifyExtraPx, 0.01f)
        assertEquals(0f, lines[1].justifyExtraPx, 0.01f)
    }

    @Test
    fun supAndSubShiftFragmentsVertically() {
        val fixture = layout(
            "<html><body><p>x<sup>s</sup><sub>u</sub></p></body></html>",
            base + "\nsup { vertical-align: super } sub { vertical-align: sub }",
        )
        val fragments = fixture.layout.lines.single().fragments
        val normal = fragments.first { it.text == "x" }
        val sup = fragments.first { it.text == "s" }
        val sub = fragments.first { it.text == "u" }
        assertTrue("sup is drawn above the baseline", sup.block < normal.block)
        assertTrue("sub is drawn below the baseline", sub.block > normal.block)
    }

    // ----------------------------------------------------------------- floats

    @Test
    fun leftFloatNarrowsLinesUntilItEnds() {
        val fixture = layout(
            """
            <html><body><div>
              <img class="f" src="f.png" style="float: left; width: 50px; height: 50px"/>
              <p>${"あ".repeat(100)}</p>
            </div></body></html>
            """,
            base,
            width = 200f,
            images = mapOf("OEBPS/Text/f.png" to IntrinsicSize(50f, 50f)),
        )
        val lines = fixture.layout.lines
        val wrapped = lines.filter { it.block < 49.9f }
        assertTrue("lines inside the float band are narrowed", wrapped.isNotEmpty())
        assertTrue(wrapped.all { it.main == 50f && it.mainSize <= 150.01f })
        val below = lines.first { it.block >= 50f }
        assertEquals(0f, below.main, 0.01f)
        assertTrue(below.mainSize > 150f)
    }

    @Test
    fun rightFloatNarrowsFromTheRight() {
        val fixture = layout(
            """
            <html><body><div>
              <img class="f" src="f.png" style="float: right; width: 50px; height: 40px"/>
              <p>${"あ".repeat(30)}</p>
            </div></body></html>
            """,
            base,
            width = 200f,
        )
        val wrapped = fixture.layout.lines.filter { it.block < 39.9f }
        assertTrue(wrapped.isNotEmpty())
        assertTrue(wrapped.all { it.main == 0f && it.mainSize <= 150.01f })
    }

    // ------------------------------------------------------------------ atoms

    @Test
    fun inlineImageSitsOnTheBaseline() {
        val fixture = layout(
            """<html><body><p>ab<img src="i.png"/>cd</p></body></html>""",
            base,
            images = mapOf("OEBPS/Text/i.png" to IntrinsicSize(20f, 20f)),
        )
        val line = fixture.layout.lines.single()
        val image = line.fragments.first { it.kind == FragmentKind.IMAGE }
        assertEquals(20f, image.mainSize, 0.01f)
        assertEquals(20f, image.blockSize, 0.01f)
        // image ascent (20) + text descent (2)
        assertEquals(22f, line.blockSize, 0.01f)
        assertEquals(0f, image.block, 0.01f)
        assertEquals(20f, line.baseline, 0.01f)
        assertEquals(10f, image.main, 0.01f)
    }

    @Test
    fun inlineBlockUsesItsExplicitSize() {
        val fixture = layout(
            """<html><body><p>a<span style="display: inline-block; width: 30px; height: 25px">b</span></p></body></html>""",
            base,
        )
        val line = fixture.layout.lines.single()
        val atomic = line.fragments.first { it.kind == FragmentKind.ATOMIC }
        assertEquals(30f, atomic.mainSize, 0.01f)
        assertEquals(25f, atomic.blockSize, 0.01f)
        // atomic ascent (25) + text descent (2)
        assertEquals(27f, line.blockSize, 0.01f)
        assertEquals(0f, atomic.block, 0.01f)
    }

    @Test
    fun hrOccupiesItsBorderHeight() {
        val fixture = layout("<html><body><hr/></body></html>", base)
        val hr = box(fixture, "hr")
        assertEquals(BoxKind.HR, hr.kind)
        assertEquals(2f, hr.blockSize, 0.01f)
        assertEquals(5f, hr.block, 0.01f)
        assertEquals(200f, hr.mainSize, 0.01f)
    }

    // ------------------------------------------------------------------ lists

    @Test
    fun unorderedListPlacesBulletMarkers() {
        val fixture = layout(
            "<html><body><ul><li>a</li><li>b</li></ul></body></html>",
            base,
        )
        val markers = fixture.layout.lines.mapNotNull { line ->
            line.fragments.firstOrNull { it.kind == FragmentKind.LIST_MARKER }
        }
        assertEquals(listOf("\u2022", "\u2022"), markers.map { it.text })
        assertTrue(markers[0].main < fixture.layout.lines[0].main)
        assertEquals(40f, fixture.layout.lines[0].main - 10f, 0.01f)
    }

    @Test
    fun orderedListNumbersItems() {
        val fixture = layout(
            "<html><body><ol><li>a</li><li>b</li></ol></body></html>",
            base,
        )
        val markers = fixture.layout.lines.mapNotNull { line ->
            line.fragments.firstOrNull { it.kind == FragmentKind.LIST_MARKER }
        }
        assertEquals(listOf("1.", "2."), markers.map { it.text })
    }

    @Test
    fun listStyleTypeNoneSuppressesTheMarker() {
        val fixture = layout(
            "<html><body><ul style=\"list-style-type: none\"><li>a</li></ul></body></html>",
            base,
        )
        assertTrue(fixture.layout.lines.none { line ->
            line.fragments.any { it.kind == FragmentKind.LIST_MARKER }
        })
    }

    // ----------------------------------------------------------------- tables

    @Test
    fun tableLaysOutTwoEqualColumns() {
        val fixture = layout(
            """
            <html><body><table>
              <tr><td>a</td><td>b</td></tr>
              <tr><td>c</td><td>d</td></tr>
            </table></body></html>
            """,
            base,
        )
        val table = box(fixture, "table")
        assertEquals(200f, table.mainSize, 0.01f)
        val rows = boxes(fixture, "tr")
        assertEquals(2, rows.size)
        assertEquals(0f, rows[0].block, 0.01f)
        assertEquals(12f, rows[1].block, 0.01f)
        val cells = boxes(fixture, "td")
        assertEquals(listOf(0f, 100f, 0f, 100f), cells.map { it.main })
        assertEquals(100f, cells[0].mainSize, 0.01f)
        assertEquals(12f, cells[0].blockSize, 0.01f)
    }

    @Test
    fun tableColspanMergesColumns() {
        val fixture = layout(
            "<html><body><table><tr><td colspan=\"2\">wide</td></tr></table></body></html>",
            base,
        )
        val cell = box(fixture, "td")
        assertEquals(200f, cell.mainSize, 0.01f)
    }

    // ------------------------------------------------------------- page breaks

    @Test
    fun pageBreakBeforeRecordsAPoint() {
        val fixture = layout(
            "<html><body><p>a</p><p class=\"pb\">b</p></body></html>",
            base + "\n.pb { page-break-before: always }",
        )
        assertEquals(1, fixture.layout.pageBreaks.size)
        assertEquals(10f, fixture.layout.pageBreaks.single().block, 0.01f)
    }

    @Test
    fun inlinePagebreakMarkerRecordsItsLabel() {
        val fixture = layout(
            """<html><body><p>a<span epub:type="pagebreak" id="pb1" title="12"></span>b</p></body></html>""",
            base,
        )
        val point = fixture.layout.pageBreaks.single()
        assertEquals("12", point.label)
        assertNotNull(fixture.layout.anchors["pb1"])
    }

    @Test
    fun noterefMarkerBecomesNativeFootnoteTextAndAnchor() {
        val fixture = layout(
            """<html><body><p>x<a epub:type="noteref" href="#n1" id="A_1">m</a></p></body></html>""",
            base,
        )
        val line = fixture.layout.lines.single()
        assertTrue(line.text.contains("【注】"))
        assertNotNull(fixture.layout.anchors["A_1"])
    }

    @Test
    fun duokanFootnoteClassAlsoBecomesNativeFootnoteText() {
        val fixture = layout(
            """<html><body><p>x<a class="duokan-footnote no-d" href="#n1" id="A_1"><sup><img src="note.png"/></sup></a></p></body></html>""",
            base,
        )
        assertEquals("x【注】", fixture.layout.lines.single().text)
    }

    // --------------------------------------------------------------- vertical

    @Test
    fun verticalWritingUsesTheSameLineDivisions() {
        val html = "<html><body><p>${"あ".repeat(15)}</p></body></html>"
        val horizontal = layout(html, base, width = 100f)
        val vertical = layout(html, base, width = 100f, writingMode = WritingMode.VERTICAL_RL)
        assertEquals(
            horizontal.layout.lines.map { it.text },
            vertical.layout.lines.map { it.text },
        )
        assertEquals(WritingMode.VERTICAL_RL, vertical.layout.writingMode)
    }
}
