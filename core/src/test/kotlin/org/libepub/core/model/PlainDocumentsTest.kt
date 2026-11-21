package org.libepub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.css.WritingMode
import org.libepub.core.layout.BlockLayoutEngine
import org.libepub.core.layout.FontMetrics
import org.libepub.core.layout.FontSpec
import org.libepub.core.layout.GlyphMeasurer
import org.libepub.core.layout.Kinsoku
import org.libepub.core.layout.LayoutConfig
import org.libepub.core.layout.PageBox
import org.libepub.core.layout.Paginator

class PlainDocumentsTest {

    private class FixedMeasurer : GlyphMeasurer {
        override fun advance(grapheme: String, font: FontSpec): Float {
            if (grapheme.isEmpty()) return 0f
            val em = font.sizePx
            return if (Kinsoku.isCjkCodePoint(grapheme.codePointAt(0))) em else em * 0.5f
        }

        override fun metrics(font: FontSpec): FontMetrics =
            FontMetrics(font.sizePx * 0.8f, font.sizePx * 0.2f)
    }

    private fun build(blocks: List<PlainBlock>) = PlainDocuments.of(
        title = "测试章",
        blocks = blocks,
    )

    @Test
    fun buildsHeadingAndParagraphs() {
        val doc = build(
            listOf(
                PlainBlock("第一章 开端", PlainBlockKind.Heading),
                PlainBlock("这是一段正文。"),
                PlainBlock("这是第二段正文。"),
            ),
        )
        assertEquals("plain://chapter", doc.href)
        assertEquals("测试章", doc.title)
        val body = doc.root.firstOrNull("body")!!
        assertEquals(listOf("h2", "p", "p"), body.children.map { it.name })
        assertEquals("这是一段正文。", body.children[1].textContent())
    }

    @Test
    fun layoutProducesLinesAndPages() {
        val doc = build(
            listOf(
                PlainBlock("第一章 开端", PlainBlockKind.Heading),
                PlainBlock("夜色像一层薄薄的墨水，慢慢覆盖了整个小镇。"),
            ),
        )
        val styles = PlainDocuments.defaultStyles(doc, fontSizePx = 20f)
        val layout = BlockLayoutEngine(FixedMeasurer()).layout(
            doc,
            styles,
            LayoutConfig(widthPx = 320f, heightPx = 480f),
        )
        assertTrue("expected laid out lines", layout.lines.isNotEmpty())
        val paged = Paginator().paginate(layout, PageBox(320f, 480f))
        assertTrue("expected at least one page", paged.pages.isNotEmpty())
    }

    @Test
    fun verticalLayoutWorksToo() {
        val doc = build(listOf(PlainBlock("横竖排共用同一套布局", PlainBlockKind.Paragraph)))
        val styles = PlainDocuments.defaultStyles(doc, fontSizePx = 18f)
        val layout = BlockLayoutEngine(FixedMeasurer()).layout(
            doc,
            styles,
            LayoutConfig(widthPx = 300f, heightPx = 400f, writingMode = WritingMode.VERTICAL_RL),
        )
        assertTrue(layout.lines.isNotEmpty())
    }
}
