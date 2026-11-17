package org.libepub.core.xhtml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.container.EpubContainer
import org.libepub.core.container.PackageParser
import org.libepub.core.testutil.SampleBooks
import org.libepub.core.util.WarningSink

class XhtmlParserTest {

    // ---------- helpers ----------

    private fun DocNode.textOf(): String = when (kind) {
        NodeKind.TEXT -> text
        NodeKind.ELEMENT -> children.joinToString("") { it.textOf() }
    }

    private fun parseSampleDoc(name: String): Pair<org.libepub.core.model.Document, EpubContainer> {
        val container = EpubContainer.open(SampleBooks.sample())
        val href = "OEBPS/Text/$name"
        val doc = XhtmlParser.parse(container.readText(href), href, container.warnings)
        return doc to container
    }

    // ---------- sample book ----------

    @Test
    fun sampleSection000ParsesPastDoctype() {
        val (doc, container) = parseSampleDoc("Section000.xhtml")
        container.use {
            assertEquals("html", doc.root.name)
            assertNotNull("body", doc.root.findAll("body").firstOrNull())
            assertTrue("序章 in body text", doc.root.findAll("body").first().textOf().contains("序章"))
        }
    }

    @Test
    fun sampleDoctypeIsStrippedBeforeParsing() {
        val doctypeHtml = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
              "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml"><body><p>ok</p></body></html>
        """.trimIndent()
        val normalized = MarkupNormalizer.normalize(doctypeHtml, WarningSink())
        assertFalse("DOCTYPE must be stripped (no network possible)", normalized.contains("DOCTYPE"))
        val doc = XhtmlParser.parse(doctypeHtml, "t.xhtml")
        assertEquals("ok", doc.root.findAll("p").first().textOf())
    }

    @Test
    fun sampleEpubTypeAttributesSurvive() {
        val (doc, container) = parseSampleDoc("Section000.xhtml")
        container.use {
            val noteref = doc.root.walk().firstOrNull { it.attr("epub:type") == "noteref" }
            assertNotNull("noteref anchor", noteref)
            assertEquals("#n1", noteref!!.attr("href"))
            val footnote = doc.root.walk().firstOrNull { it.attrLocal("type") == "footnote" && it.name == "aside" }
            assertNotNull("footnote aside", footnote)
        }
    }

    @Test
    fun sampleHeadChromeNeverEntersBody() {
        val (doc, container) = parseSampleDoc("Section000.xhtml")
        container.use {
            val body = doc.root.findAll("body").first()
            val under = body.walk().map { it.name }.toSet()
            assertFalse("script under body", "script" in under)
            assertFalse("link under body", "link" in under)
            assertFalse("head under body", "head" in under)
            // script dropped entirely from the tree
            assertTrue("script nowhere in tree", doc.root.findAll("script").isEmpty())
        }
    }

    @Test
    fun sampleDuplicateIdsAllRetained() {
        val (doc, container) = parseSampleDoc("Section000.xhtml")
        container.use {
            assertEquals(2, doc.ids["n1"]?.size)
            assertTrue(doc.ids["n1"]!!.all { it.name == "aside" || it.name == "li" })
        }
        val (doc3, c3) = parseSampleDoc("Section003.xhtml")
        c3.use {
            assertEquals("duplicate A_1 anchors must both be indexed", 2, doc3.ids["A_1"]?.size)
        }
    }

    @Test
    fun sampleInlineStyleParsesToDeclarations() {
        val (doc, container) = parseSampleDoc("title.xhtml")
        container.use {
            val div = doc.root.walk().firstOrNull { it.attr("style")?.contains("4em") == true }
            assertNotNull("div with inline style", div)
            val decls = div!!.inlineStyle
            assertNotNull(decls)
            assertEquals(listOf(StyleDecl("margin", "4em 0 0 0", false)), decls)
        }
    }

    @Test
    fun sampleRubyStructureIsBasePlusRt() {
        val (doc, container) = parseSampleDoc("Section002.xhtml")
        container.use {
            val ruby = doc.root.findAll("ruby").firstOrNull() ?: error("no ruby in Section002")
            assertEquals(2, ruby.children.size)
            assertEquals(NodeKind.TEXT, ruby.children.first().kind)
            assertEquals("狱炎火弹", ruby.children.first().text)
            val rt = ruby.children.firstOrNull { it.kind == NodeKind.ELEMENT && it.name == "rt" }
            assertNotNull("rt child", rt)
            assertEquals("Exodus Flame", rt!!.textOf())
        }
    }

    @Test
    fun sampleAllTwentyFourSpineDocumentsParse() {
        EpubContainer.open(SampleBooks.sample()).use { container ->
            val pkg = PackageParser(container).parse()
            assertEquals(24, pkg.spine.size)
            for (item in pkg.spine) {
                val href = item.href ?: error("dangling spine href for ${item.idref}")
                val doc = XhtmlParser.parse(container.readText(href), href, container.warnings)
                assertEquals("root of $href", "html", doc.root.name)
            }
        }
    }

    // ---------- synthetic tolerance fixtures ----------

    @Test
    fun unclosedTagsAreRecoveredAtMismatchAndEof() {
        // mismatch: <b> closed by </body>; parser must auto-close b and p
        val doc = XhtmlParser.parse("<html><body><p>text<b>bold</body></html>", "t.xhtml")
        val body = doc.root.findAll("body").first()
        assertEquals("textbold", body.textOf())
        val p = doc.root.findAll("p").first()
        val b = p.children.firstOrNull { it.name == "b" }
        assertNotNull("b kept open-by-mismatch", b)
        assertEquals("bold", b!!.textOf())

        // EOF: everything left open is implicitly closed
        val doc2 = XhtmlParser.parse("<html><body><p>text", "t.xhtml")
        assertEquals("text", doc2.root.findAll("p").first().textOf())
    }

    @Test
    fun strayEndTagIsDroppedNotFatal() {
        val doc = XhtmlParser.parse("<html><body><p>x</b></p></body></html>", "t.xhtml")
        assertEquals("x", doc.root.findAll("p").first().textOf())
        assertTrue(doc.warnings.all.any { it.contains("stray") })
    }

    @Test
    fun htmlNamedAndNumericEntitiesResolve() {
        val doc = XhtmlParser.parse(
            "<html><body><p>a&nbsp;b&#8220;c&#x4e2d;d&amp;e&lt;f&nbsp;g</p></body></html>",
            "t.xhtml",
        )
        assertEquals("a\u00A0b“c中d&e<f\u00A0g", doc.root.findAll("p").first().textOf())
    }

    @Test
    fun unknownEntityDropsWithWarningInsteadOfThrowing() {
        val doc = XhtmlParser.parse("<html><body><p>x&notarealentity;y</p></body></html>", "t.xhtml")
        assertEquals("xy", doc.root.findAll("p").first().textOf())
        assertTrue(doc.warnings.all.any { it.contains("notarealentity") })
    }

    @Test
    fun bareAmpersandIsEscapedNotFatal() {
        val doc = XhtmlParser.parse("<html><body><p>AT&T and 5<6</p></body></html>", "t.xhtml")
        assertEquals("AT&T and 5<6", doc.root.findAll("p").first().textOf())
    }

    @Test
    fun namespacePrefixMixIsTolerated() {
        val xml = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <epub:marker epub:type="pagebreak" xml:lang="zh">12</epub:marker>
                <ops:thing id="x">deep</ops:thing>
              </body>
            </html>
        """.trimIndent()
        val doc = XhtmlParser.parse(xml, "t.xhtml")
        val marker = doc.root.findAll("epub:marker").first()
        assertEquals("12", marker.textOf())
        assertEquals("pagebreak", marker.attr("epub:type"))
        assertEquals("deep", doc.root.findAll("ops:thing").first().textOf())
    }

    @Test
    fun cdataAndCommentsHandled() {
        val doc = XhtmlParser.parse(
            "<html><body><p>a<!-- hidden -->b<![CDATA[<raw>]]>c</p></body></html>",
            "t.xhtml",
        )
        assertEquals("ab<raw>c", doc.root.findAll("p").first().textOf())
    }

    @Test
    fun scriptWithBareJsOperatorsIsDropped() {
        val xml = """
            <html><head><script src="a.js">var x = 1 < 2 && 3 > 2;</script></head>
            <body><p>ok</p></body></html>
        """.trimIndent()
        val doc = XhtmlParser.parse(xml, "t.xhtml")
        assertTrue(doc.root.findAll("script").isEmpty())
        assertFalse(doc.root.textOf().contains("var x"))
        assertEquals("ok", doc.root.findAll("p").first().textOf())
        assertTrue(doc.warnings.all.any { it.contains("script") })
    }

    @Test
    fun styleElementIsCapturedAsStylesheetNotRenderedText() {
        val doc = XhtmlParser.parse(
            "<html><head><style>p { color: red; }</style></head><body><p>x</p></body></html>",
            "t.xhtml",
        )
        assertEquals(listOf("p { color: red; }"), doc.styleSheets)
    }

    @Test
    fun unquotedAttributesAreNormalized() {
        val doc = XhtmlParser.parse("<html><body><img src=cover.jpg alt=TheCover><br></body></html>", "t.xhtml")
        val img = doc.root.findAll("img").first()
        assertEquals("cover.jpg", img.attr("src"))
        assertEquals("TheCover", img.attr("alt"))
        assertEquals(1, doc.root.findAll("br").size)
    }
}
