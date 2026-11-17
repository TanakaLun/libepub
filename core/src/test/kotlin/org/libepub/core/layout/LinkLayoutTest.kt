package org.libepub.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.libepub.core.css.CssParser
import org.libepub.core.model.Document
import org.libepub.core.style.StyleResolver
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.XhtmlParser

class LinkLayoutTest {

    private class FixedMeasurer : GlyphMeasurer {
        override fun advance(grapheme: String, font: FontSpec): Float {
            if (grapheme.isEmpty()) return 0f
            val em = font.sizePx
            return when {
                grapheme == " " -> em * 0.25f
                Kinsoku.isCjkCodePoint(grapheme.codePointAt(0)) -> em
                else -> em * 0.5f
            }
        }

        override fun metrics(font: FontSpec): FontMetrics =
            FontMetrics(font.sizePx * 0.8f, font.sizePx * 0.2f)
    }

    private val warnings = WarningSink()

    private fun layout(html: String): ChapterLayout {
        val sheet = CssParser.parse(
            "html, body, div, p { margin: 0; padding: 0 } body { font-size: 10px; line-height: 1 }",
            "OEBPS/Styles",
            warnings,
        )
        val document: Document = XhtmlParser.parse(html, "OEBPS/Text/x.xhtml", warnings)
        val styles = StyleResolver(listOf(sheet), warnings = warnings).resolve(document)
        return BlockLayoutEngine(FixedMeasurer(), ImageSizeProvider.NONE, warnings)
            .layout(document, styles, LayoutConfig(400f, 800f))
    }

    private fun textFragments(layout: ChapterLayout): List<InlineFragment> =
        layout.lines.flatMap { line -> line.fragments.filter { it.kind == FragmentKind.TEXT } }

    @Test
    fun relativeLinkResolvesAgainstTheDocument() {
        val layout = layout(
            """<html><body><p>前<a href="../Text/Section001.xhtml#foo">跳转</a>后</p></body></html>""",
        )
        val fragments = textFragments(layout)
        assertEquals(listOf("前", "跳转", "后"), fragments.map { it.text })
        assertNull(fragments[0].linkHref)
        assertEquals("OEBPS/Text/Section001.xhtml", fragments[1].linkHref)
        assertEquals("foo", fragments[1].linkFragment)
        assertNull(fragments[2].linkHref)
    }

    @Test
    fun sameDocumentFragmentLinkKeepsTheCurrentHref() {
        val layout = layout("""<html><body><p><a href="#note">注</a></p></body></html>""")
        val fragment = textFragments(layout).single()
        assertEquals("OEBPS/Text/x.xhtml", fragment.linkHref)
        assertEquals("note", fragment.linkFragment)
    }

    @Test
    fun linkWithoutFragmentResolvesToTheWholeDocument() {
        val layout = layout("""<html><body><p><a href="Section002.xhtml">第二章</a></p></body></html>""")
        val fragment = textFragments(layout).single()
        assertEquals("OEBPS/Text/Section002.xhtml", fragment.linkHref)
        assertNull(fragment.linkFragment)
    }

    @Test
    fun externalLinksAreIgnored() {
        val layout = layout(
            """<html><body><p><a href="https://example.com/x">外链</a><a href="mailto:a@b.c">信</a></p></body></html>""",
        )
        val fragments = textFragments(layout)
        assertEquals(listOf("外链", "信"), fragments.map { it.text })
        assertNull(fragments[0].linkHref)
        assertNull(fragments[1].linkHref)
    }

    @Test
    fun eachLinkRunGetsItsOwnFragmentForHitTesting() {
        val layout = layout(
            """
            <html><body><p>
            <a href="Section001.xhtml">一</a><a href="Section002.xhtml">二</a>三
            </p></body></html>
            """,
        )
        val fragments = textFragments(layout)
        assertEquals(listOf("一", "二", "三"), fragments.map { it.text })
        assertEquals("OEBPS/Text/Section001.xhtml", fragments[0].linkHref)
        assertEquals("OEBPS/Text/Section002.xhtml", fragments[1].linkHref)
        assertNull(fragments[2].linkHref)
    }
}
