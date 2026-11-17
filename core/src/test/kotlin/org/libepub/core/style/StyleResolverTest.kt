package org.libepub.core.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.container.EpubContainer
import org.libepub.core.container.PackageParser
import org.libepub.core.css.CssColor
import org.libepub.core.css.CssParser
import org.libepub.core.css.Dimension
import org.libepub.core.css.Display
import org.libepub.core.css.FontStyle
import org.libepub.core.css.Length
import org.libepub.core.css.LengthUnit
import org.libepub.core.css.LineHeight
import org.libepub.core.css.Stylesheet
import org.libepub.core.css.TextAlign
import org.libepub.core.css.TextDecoration
import org.libepub.core.css.TransformFn
import org.libepub.core.css.VerticalAlign
import org.libepub.core.model.Document
import org.libepub.core.testutil.SampleBooks
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.DocNode
import org.libepub.core.xhtml.XhtmlParser

class StyleResolverTest {

    // ---------------------------------------------------------------- fixtures

    private class Fixture(val doc: Document, val styles: Map<DocNode, ComputedStyle>, val warnings: WarningSink)

    private fun sampleFixture(name: String): Fixture {
        EpubContainer.open(SampleBooks.sample()).use { container ->
            val warnings = WarningSink()
            val href = "OEBPS/Text/$name"
            val sheet = CssParser.parse(container.readText("OEBPS/Styles/style.css"), "OEBPS/Styles", warnings)
            val doc = XhtmlParser.parse(container.readText(href), href, warnings)
            val resolver = StyleResolver(listOf(sheet), warnings = warnings)
            return Fixture(doc, resolver.resolve(doc), warnings)
        }
    }

    private fun synthetic(html: String, css: String? = null): Fixture {
        val warnings = WarningSink()
        val sheet = css?.let { CssParser.parse(it, "OEBPS/Styles", warnings) }
        val doc = XhtmlParser.parse(html.trimIndent(), "OEBPS/Text/synthetic.xhtml", warnings)
        val resolver = StyleResolver(listOfNotNull(sheet), warnings = warnings)
        return Fixture(doc, resolver.resolve(doc), warnings)
    }

    private fun styleOf(fixture: Fixture, node: DocNode): ComputedStyle =
        fixture.styles[node] ?: error("no computed style for <${node.name}>")

    private fun element(fixture: Fixture, name: String): DocNode =
        fixture.doc.root.walk().first { it.isElement && it.name == name }

    private fun byId(fixture: Fixture, id: String): DocNode =
        fixture.doc.root.walk().first { it.isElement && it.attr("id") == id }

    private fun byClass(fixture: Fixture, token: String): DocNode =
        fixture.doc.root.walk().first {
            it.isElement && it.attr("class")?.split(Regex("\\s+"))?.contains(token) == true
        }

    private fun lengthPx(value: Float) = Length(value, LengthUnit.PX)

    private fun px(dimension: Dimension): Float = (dimension as Dimension.Of).length.value

    // ------------------------------------------------------- sample goldens

    @Test
    fun bodyUsesPercentMarginsAndPercentLineHeight() {
        val fixture = sampleFixture("Section000.xhtml")
        val body = styleOf(fixture, element(fixture, "body"))
        assertEquals(Display.BLOCK, body.display)
        assertEquals(1f, px(body.margin.left), 0.001f)
        assertEquals(LengthUnit.PERCENT, (body.margin.left as Dimension.Of).length.unit)
        assertEquals(0f, px(body.margin.top), 0.001f)
        assertEquals(LineHeight.OfLength(lengthPx(20.8f)), body.lineHeight)
        assertEquals(TextAlign.JUSTIFY, body.textAlign)
    }

    @Test
    fun paragraphGoldenFromSampleStylesheet() {
        val fixture = sampleFixture("Section000.xhtml")
        val paragraph = styleOf(fixture, fixture.doc.root.walk().first {
            it.isElement && it.name == "p" && it.attr("class") == null
        })
        assertEquals(Display.BLOCK, paragraph.display)
        assertEquals(lengthPx(32f), paragraph.textIndent)
        assertEquals(TextAlign.JUSTIFY, paragraph.textAlign)
        assertEquals(LineHeight.OfLength(lengthPx(20.8f)), paragraph.lineHeight)
        assertEquals(6.4f, px(paragraph.margin.top), 0.001f)
        assertEquals(6.4f, px(paragraph.margin.bottom), 0.001f)
        assertEquals(0f, px(paragraph.margin.left), 0.001f)
        assertEquals(0f, px(paragraph.margin.right), 0.001f)
        assertEquals(16f, paragraph.fontSizePx, 0.001f)
    }

    @Test
    fun em30TriplesFontSizeAndIndentFollowsElementFontSize() {
        val fixture = sampleFixture("title.xhtml")
        val em30 = styleOf(fixture, byClass(fixture, "em30"))
        assertEquals(48f, em30.fontSizePx, 0.001f)
        // .zin also matches this element and zeroes the indent
        assertEquals(lengthPx(0f), em30.textIndent)
        // inline `margin: 0` beats the UA p margin
        assertEquals(0f, px(em30.margin.top), 0.001f)
        assertEquals(0f, px(em30.margin.bottom), 0.001f)
    }

    @Test
    fun textIndentResolvesAgainstTheElementsOwnFontSize() {
        val fixture = synthetic(
            "<html><body><p>x</p></body></html>",
            "p { font-size: 3em; text-indent: 2em }",
        )
        val style = styleOf(fixture, element(fixture, "p"))
        assertEquals(48f, style.fontSizePx, 0.001f)
        assertEquals(lengthPx(96f), style.textIndent)
    }

    @Test
    fun duokanFootnoteItemCarriesFontShadowAndSize() {
        val fixture = sampleFixture("Section000.xhtml")
        val item = styleOf(fixture, byClass(fixture, "duokan-footnote-item"))
        assertEquals(14.4f, item.fontSizePx, 0.001f)
        assertEquals(listOf("DK-SONGTI"), item.fontFamily)
        assertEquals(TextAlign.LEFT, item.textAlign)
        assertEquals(lengthPx(0f), item.textIndent)
        val shadow = item.textShadow.single()
        assertEquals(0f, shadow.x.value, 0.001f)
        assertEquals(0f, shadow.y.value, 0.001f)
        assertEquals(1.44f, shadow.blur.value, 0.001f)
        assertEquals(CssColor.BLACK, shadow.color)
    }

    @Test
    fun footnoteAsideIsHiddenFromBodyFlow() {
        val fixture = sampleFixture("Section000.xhtml")
        val aside = fixture.doc.root.walk().first { it.isElement && it.name == "aside" }
        val style = styleOf(fixture, aside)
        assertTrue(style.isHidden)
        assertEquals(Display.NONE, style.display)
    }

    @Test
    fun duokanFootnoteAnchorImportantHeightBeatsInlineStyle() {
        val fixture = synthetic(
            """
            <html><body><p>x<a class="duokan-footnote" style="height: 3em" href="#n1">m</a></p></body></html>
            """,
            sampleCss(),
        )
        val anchor = styleOf(fixture, byClass(fixture, "duokan-footnote"))
        // 1.2em !important wins over inline 3em
        assertEquals(19.2f, px(anchor.height!!), 0.001f)
        assertEquals(Dimension.Auto, anchor.width)
        assertEquals(lengthPx(0f), anchor.border.top.width)
    }

    @Test
    fun supRuleUsesImportantVerticalAlignAndMultiplierLineHeight() {
        val fixture = sampleFixture("Section000.xhtml")
        val sup = styleOf(fixture, element(fixture, "sup"))
        assertEquals(12f, sup.fontSizePx, 0.001f)
        assertEquals(LineHeight.Number(1.2f), sup.lineHeight)
        assertEquals(VerticalAlign.Keyword("super"), sup.verticalAlign)
    }

    @Test
    fun undefinedClassFallsBackToInheritedStyle() {
        val fixture = sampleFixture("Section002.xhtml")
        val node = byClass(fixture, "gfont")
        val style = styleOf(fixture, node)
        assertEquals(16f, style.fontSizePx, 0.001f)
        assertEquals(ComputedStyle.DEFAULT_FONT_FAMILIES, style.fontFamily)
    }

    @Test
    fun backgroundUrlResolvesAgainstStylesheetDirectory() {
        val fixture = synthetic("<html><body><div class=\"back\">x</div></body></html>", sampleCss())
        val style = styleOf(fixture, byClass(fixture, "back"))
        assertEquals("OEBPS/Images/contents.jpg", style.background.imageUrl)
        assertEquals("no-repeat", style.background.repeat)
        assertEquals("top center", style.background.position)
        assertEquals("cover", style.background.size)
    }

    @Test
    fun textEmphasisShorthandWinsOverPrefixedLonghands() {
        val fixture = synthetic("<html><body><span class=\"em-dot\">x</span></body></html>", sampleCss())
        val emphasis = styleOf(fixture, byClass(fixture, "em-dot")).textEmphasis
        assertNotNull(emphasis)
        assertEquals("circle", emphasis!!.mark)
        assertEquals(true, emphasis.filled)
        assertEquals(CssColor.BLACK, emphasis.color)
    }

    @Test
    fun transformRuleApplies() {
        val fixture = synthetic("<html><body><div class=\"rotate1\">x</div></body></html>", sampleCss())
        assertEquals(listOf(TransformFn.Rotate(-4f)), styleOf(fixture, byClass(fixture, "rotate1")).transform)
    }

    // ------------------------------------------------------------- UA goldens

    @Test
    fun uaHeadingSizesAndMargins() {
        val fixture = synthetic("<html><body><h1>a</h1><h2>b</h2><h6>c</h6></body></html>")
        val h1 = styleOf(fixture, element(fixture, "h1"))
        assertEquals(32f, h1.fontSizePx, 0.001f)
        assertEquals(700, h1.fontWeight)
        assertEquals(Display.BLOCK, h1.display)
        // 0.67em against the heading's own 32px font size
        assertEquals(21.44f, px(h1.margin.top), 0.01f)
        assertEquals(24f, styleOf(fixture, element(fixture, "h2")).fontSizePx, 0.001f)
        assertEquals(10.72f, styleOf(fixture, element(fixture, "h6")).fontSizePx, 0.01f)
    }

    @Test
    fun uaListBlockquoteAndInlineSemantics() {
        val fixture = synthetic(
            """
            <html><body>
              <ul><li>a</li></ul>
              <blockquote>q</blockquote>
              <b>bold</b><i>it</i><u>u</u><code>c</code>
            </body></html>
            """,
        )
        val ul = styleOf(fixture, element(fixture, "ul"))
        assertEquals(Display.BLOCK, ul.display)
        assertEquals(40f, ul.padding.left.value, 0.001f)
        assertEquals(16f, px(ul.margin.top), 0.001f)
        assertEquals(Display.LIST_ITEM, styleOf(fixture, element(fixture, "li")).display)
        assertEquals(40f, px(styleOf(fixture, element(fixture, "blockquote")).margin.left), 0.001f)
        assertEquals(700, styleOf(fixture, element(fixture, "b")).fontWeight)
        assertEquals(FontStyle.ITALIC, styleOf(fixture, element(fixture, "i")).fontStyle)
        assertEquals(
            setOf(TextDecoration.Line.UNDERLINE),
            styleOf(fixture, element(fixture, "u")).textDecoration?.lines,
        )
        assertEquals(listOf("monospace"), styleOf(fixture, element(fixture, "code")).fontFamily)
    }

    @Test
    fun uaTableStructure() {
        val fixture = synthetic("<html><body><table><tr><td>a</td><th>b</th></tr></table></body></html>")
        val table = styleOf(fixture, element(fixture, "table"))
        assertEquals(Display.TABLE, table.display)
        assertEquals(org.libepub.core.css.BorderCollapse.SEPARATE, table.borderCollapse)
        assertEquals(2f, table.borderSpacingPx, 0.001f)
        assertEquals(Display.TABLE_ROW, styleOf(fixture, element(fixture, "tr")).display)
        assertEquals(Display.TABLE_CELL, styleOf(fixture, element(fixture, "td")).display)
        assertEquals(VerticalAlign.Keyword("middle"), styleOf(fixture, element(fixture, "td")).verticalAlign)
        assertEquals(700, styleOf(fixture, element(fixture, "th")).fontWeight)
    }

    // -------------------------------------------------- hints / inline / cascade

    @Test
    fun alignAttributeMapsToTextAlign() {
        val fixture = synthetic("<html><body><p align=\"right\">x</p><p align=\"center\">y</p></body></html>")
        val paragraphs = fixture.doc.root.walk().filter { it.isElement && it.name == "p" }.toList()
        assertEquals(TextAlign.RIGHT, styleOf(fixture, paragraphs[0]).textAlign)
        assertEquals(TextAlign.CENTER, styleOf(fixture, paragraphs[1]).textAlign)
    }

    @Test
    fun inlineStyleBeatsClassRule() {
        val fixture = synthetic(
            "<html><body><p class=\"x\" style=\"color: #00ff00\">a</p></body></html>",
            "p.x { color: #ff0000 }",
        )
        assertEquals(CssColor.rgb(0x00, 0xFF, 0x00), styleOf(fixture, element(fixture, "p")).color)
    }

    @Test
    fun specificityAndSourceOrderBreakTies() {
        val fixture = synthetic(
            "<html><body><p id=\"y\" class=\"x\">a</p><p class=\"x\">b</p><p>c</p></body></html>",
            """
            p { color: red }
            .x { color: blue }
            p.x { color: #00ff00 }
            #y { color: black }
            p { color: #333333 }
            """,
        )
        val paragraphs = fixture.doc.root.walk().filter { it.isElement && it.name == "p" }.toList()
        assertEquals(CssColor.BLACK, styleOf(fixture, paragraphs[0]).color)
        assertEquals(CssColor.rgb(0x00, 0xFF, 0x00), styleOf(fixture, paragraphs[1]).color)
        assertEquals(CssColor.rgb(0x33, 0x33, 0x33), styleOf(fixture, paragraphs[2]).color)
    }

    @Test
    fun combinatorsAndStructuralPseudosMatch() {
        val fixture = synthetic(
            """
            <html><body>
              <div>
                <p id="a">one</p>
                <p id="b">two</p>
                <p id="c" class="skip">three</p>
                <ul><li id="l1">1</li><li id="l2">2</li><li id="l3">3</li><li id="l4">4</li></ul>
              </div>
            </body></html>
            """,
            """
            div > p:first-child { color: red }
            p + p { color: blue }
            li:nth-child(2n) { color: green }
            div p:not(.skip) { text-indent: 3px }
            """,
        )
        assertEquals(CssColor.rgb(0xFF, 0x00, 0x00), styleOf(fixture, byId(fixture, "a")).color)
        assertEquals(lengthPx(3f), styleOf(fixture, byId(fixture, "a")).textIndent)
        assertEquals(CssColor.rgb(0x00, 0x00, 0xFF), styleOf(fixture, byId(fixture, "b")).color)
        assertEquals(lengthPx(3f), styleOf(fixture, byId(fixture, "b")).textIndent)
        assertEquals(CssColor.rgb(0x00, 0x00, 0xFF), styleOf(fixture, byId(fixture, "c")).color)
        assertEquals(lengthPx(0f), styleOf(fixture, byId(fixture, "c")).textIndent)
        assertEquals(CssColor.BLACK, styleOf(fixture, byId(fixture, "l1")).color)
        assertEquals(CssColor.rgb(0x00, 0x80, 0x00), styleOf(fixture, byId(fixture, "l2")).color)
        assertEquals(CssColor.rgb(0x00, 0x80, 0x00), styleOf(fixture, byId(fixture, "l4")).color)
    }

    @Test
    fun inheritedPropertiesSplitFromNonInherited() {
        val fixture = synthetic(
            """
            <html><body>
              <div id="outer" style="color: #123456; font-size: 20px; line-height: 1.5;
                   letter-spacing: 2px; text-align: right; text-indent: 10px;
                   margin: 5px; padding: 7px; border: 3px solid red"><span id="inner">x</span></div>
            </body></html>
            """,
        )
        val inner = styleOf(fixture, byId(fixture, "inner"))
        assertEquals(CssColor.rgb(0x12, 0x34, 0x56), inner.color)
        assertEquals(20f, inner.fontSizePx, 0.001f)
        assertEquals(LineHeight.Number(1.5f), inner.lineHeight)
        assertEquals(2f, inner.letterSpacingPx, 0.001f)
        assertEquals(TextAlign.RIGHT, inner.textAlign)
        assertEquals(lengthPx(10f), inner.textIndent)
        // non-inherited properties reset
        assertEquals(0f, px(inner.margin.top), 0.001f)
        assertEquals(0f, inner.padding.top.value, 0.001f)
        assertNull(inner.border.top.width)
        assertEquals(org.libepub.core.css.BorderStyle.NONE, inner.border.top.style)
        assertEquals(Display.INLINE, inner.display)
    }

    @Test
    fun currentColorResolvesForBordersAndShadow() {
        val fixture = synthetic(
            "<html><body><p style=\"color: #123456; border: 1px solid; text-shadow: 1px 1px\">x</p></body></html>",
        )
        val style = styleOf(fixture, element(fixture, "p"))
        assertEquals(CssColor.rgb(0x12, 0x34, 0x56), style.border.top.color)
        assertEquals(CssColor.rgb(0x12, 0x34, 0x56), style.textShadow.single().color)
    }

    @Test
    fun shorthandAndLonghandComposeAcrossDeclarations() {
        val fixture = synthetic(
            "<html><body><p>x</p></body></html>",
            "p { border: 1px solid black; border-top: 2px dashed red; margin: 1em; margin-left: 3px }",
        )
        val style = styleOf(fixture, element(fixture, "p"))
        assertEquals(2f, style.border.top.width!!.value, 0.001f)
        assertEquals(org.libepub.core.css.BorderStyle.DASHED, style.border.top.style)
        assertEquals(CssColor.rgb(0xFF, 0x00, 0x00), style.border.top.color)
        assertEquals(1f, style.border.bottom.width!!.value, 0.001f)
        assertEquals(org.libepub.core.css.BorderStyle.SOLID, style.border.bottom.style)
        assertEquals(3f, px(style.margin.left), 0.001f)
        assertEquals(16f, px(style.margin.top), 0.001f)
    }

    @Test
    fun fontShorthandParsesStyleWeightSizeLineHeightFamily() {
        val fixture = synthetic("<html><body><p>x</p></body></html>", "p { font: italic bold 12px/1.5 serif }")
        val style = styleOf(fixture, element(fixture, "p"))
        assertEquals(FontStyle.ITALIC, style.fontStyle)
        assertEquals(700, style.fontWeight)
        assertEquals(12f, style.fontSizePx, 0.001f)
        assertEquals(LineHeight.Number(1.5f), style.lineHeight)
        assertEquals(listOf("serif"), style.fontFamily)
    }

    @Test
    fun unsupportedPropertiesWarnButDoNotThrow() {
        val fixture = synthetic(
            "<html><body><p>x</p></body></html>",
            "p { position: absolute; -webkit-box-shadow: 0 0 1px red; color: red }",
        )
        val style = styleOf(fixture, element(fixture, "p"))
        assertEquals(CssColor.rgb(0xFF, 0x00, 0x00), style.color)
        val warnings = fixture.warnings.all.joinToString("\n")
        assertTrue(warnings.contains("position"))
        assertTrue(warnings.contains("box-shadow"))
    }

    @Test
    fun hiddenElementsStillComputeButAreMarked() {
        val fixture = synthetic(
            "<html><body><p style=\"display: none\">x</p><p>y</p></body></html>",
        )
        val paragraphs = fixture.doc.root.walk().filter { it.isElement && it.name == "p" }.toList()
        assertTrue(styleOf(fixture, paragraphs[0]).isHidden)
        assertFalse(styleOf(fixture, paragraphs[1]).isHidden)
    }

    @Test
    fun emptyDocumentWithoutStylesResolvesToUaOnly() {
        val fixture = synthetic("<html><body><div>x</div></body></html>")
        val div = styleOf(fixture, element(fixture, "div"))
        assertEquals(Display.BLOCK, div.display)
        assertEquals(16f, div.fontSizePx, 0.001f)
        assertNull(div.textEmphasis)
        assertTrue(div.textShadow.isEmpty())
    }

    // ----------------------------------------------------------------- helpers

    private fun sampleCss(): String =
        EpubContainer.open(SampleBooks.sample()).use { container ->
            container.readText("OEBPS/Styles/style.css")
        }
}
