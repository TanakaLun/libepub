package org.libepub.core.semantics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.container.EpubContainer
import org.libepub.core.container.PackageParser
import org.libepub.core.model.Document
import org.libepub.core.testutil.SampleBooks
import org.libepub.core.xhtml.XhtmlParser

class SemanticIndexTest {

    // ---------- helpers ----------

    private fun sampleDoc(name: String): Document =
        EpubContainer.open(SampleBooks.sample()).use { container ->
            val href = "OEBPS/Text/$name"
            XhtmlParser.parse(container.readText(href), href, container.warnings)
        }

    private fun allSampleDocs(): List<Document> =
        EpubContainer.open(SampleBooks.sample()).use { container ->
            val pkg = PackageParser(container).parse()
            pkg.spine.map { item ->
                val href = item.href!!
                XhtmlParser.parse(container.readText(href), href, container.warnings)
            }
        }

    private fun parseSynthetic(xhtml: String): Document =
        XhtmlParser.parse(xhtml, "OEBPS/Text/synthetic.xhtml")

    // ---------- sample: footnotes ----------

    @Test
    fun section000FootnotePairing() {
        val doc = sampleDoc("Section000.xhtml")
        assertEquals(1, doc.footnotes.size)
        val fn = doc.footnotes.single()
        assertEquals("n1", fn.refId)
        assertEquals("a", fn.marker.name)
        assertEquals("noteref", fn.marker.attrLocal("type"))
        assertEquals("aside", fn.target?.name)
        assertEquals("注：出自动画《新机动战记钢弹W》。", fn.text)
        assertEquals("【注】", fn.markerText)
        assertSame("index lookup by marker identity", fn, doc.footnoteFor(fn.marker))
    }

    @Test
    fun section003TwoFootnotesPairCorrectly() {
        val doc = sampleDoc("Section003.xhtml")
        assertEquals(2, doc.footnotes.size)
        assertEquals(listOf("n1", "n2"), doc.footnotes.map { it.refId })
        assertNotSame("targets must be distinct elements", doc.footnotes[0].target, doc.footnotes[1].target)
        assertTrue(doc.footnotes[0].text.contains("廷达洛斯"))
        assertTrue(doc.footnotes[1].text.contains("ハーゴン"))
    }

    @Test
    fun duplicateMarkerIdsDoNotMismatch() {
        val doc = sampleDoc("Section003.xhtml")
        // both noteref anchors carry id="A_1" in this book
        assertEquals("A_1", doc.footnotes[0].marker.attr("id"))
        assertEquals("A_1", doc.footnotes[1].marker.attr("id"))
        // pairing is by href fragment, so the two targets stay distinct
        assertEquals("n1", doc.footnotes[0].target?.attr("id"))
        assertEquals("n2", doc.footnotes[1].target?.attr("id"))
    }

    @Test
    fun duplicateTargetIdPrefersTheFootnoteElement() {
        val doc = sampleDoc("Section000.xhtml")
        // aside and its <li> both carry id="n1"
        assertEquals(2, doc.ids["n1"]?.size)
        val target = doc.footnotes.single().target
        assertEquals("aside", target?.name)
        assertEquals("footnote", target?.attrLocal("type"))
    }

    @Test
    fun allFourFootnotesAcrossTheBookAreIndexed() {
        val docs = allSampleDocs()
        val footnotes = docs.flatMap { doc -> doc.footnotes.map { doc.href to it } }
        assertEquals(4, footnotes.size)
        assertEquals(
            listOf(
                "OEBPS/Text/Section000.xhtml" to "n1",
                "OEBPS/Text/Section003.xhtml" to "n1",
                "OEBPS/Text/Section003.xhtml" to "n2",
                "OEBPS/Text/Section006.xhtml" to "n1",
            ),
            footnotes.map { (href, fn) -> href to fn.refId },
        )
        assertTrue(footnotes.all { (_, fn) -> fn.target != null })
        assertTrue(footnotes.all { (_, fn) -> fn.markerText == "【注】" })
    }

    // ---------- sample: image blocks ----------

    @Test
    fun section004SingleImageIndexed() {
        val doc = sampleDoc("Section004.xhtml")
        assertEquals(1, doc.imageGroups.size)
        val group = doc.imageGroups.single()
        assertEquals(ImageGroupKind.SINGLE, group.kind)
        assertEquals(1, group.images.size)
        val img = group.images.single()
        assertEquals("OEBPS/Images/005.jpg", img.src)
        assertEquals("005", img.alt)
        assertEquals("img", img.node.name)
    }

    @Test
    fun allEighteenSampleImagesAreGrouped() {
        val groups = allSampleDocs().flatMap { it.imageGroups }
        assertEquals(18, groups.size)
        assertTrue("sample only ships single-image blocks", groups.all { it.kind == ImageGroupKind.SINGLE })
        assertEquals(18, groups.sumOf { it.images.size })
        assertTrue("every src resolved", groups.all { g -> g.images.all { it.src.isNotEmpty() } })
    }

    // ---------- synthetic: galleries, page breaks, tolerance ----------

    @Test
    fun galleryGroupsCellsAndStandaloneCellIsItsOwnGroup() {
        val doc = parseSynthetic(
            """
            <html><body>
              <div class="duokan-image-gallery">
                <div class="duokan-image-cell"><img src="../Images/a.jpg" alt="a"/></div>
                <div class="duokan-image-cell"><img src="../Images/b.jpg" alt="b"/></div>
                <div class="duokan-image-cell"><img src="../Images/c.jpg" alt="c"/></div>
              </div>
              <div class="duokan-image-cell"><img src="../Images/d.jpg" alt="d"/></div>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(2, doc.imageGroups.size)
        val gallery = doc.imageGroups[0]
        assertEquals(ImageGroupKind.GALLERY, gallery.kind)
        assertEquals(listOf("OEBPS/Images/a.jpg", "OEBPS/Images/b.jpg", "OEBPS/Images/c.jpg"), gallery.images.map { it.src })
        val cell = doc.imageGroups[1]
        assertEquals(ImageGroupKind.CELL, cell.kind)
        assertEquals("OEBPS/Images/d.jpg", cell.images.single().src)
    }

    @Test
    fun pageBreaksExposePrintedLabels() {
        val doc = parseSynthetic(
            """
            <html><body>
              <p>a<span epub:type="pagebreak" id="p12" title="12"></span>b</p>
              <p><span epub:type="pagebreak" id="p13">13</span></p>
              <p><span epub:type="pagebreak" id="p14"></span></p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(listOf("12", "13", "p14"), doc.pageBreaks.map { it.label })
        assertEquals(listOf("p12", "p13", "p14"), doc.pageBreaks.map { it.id })
    }

    @Test
    fun danglingNoterefIsTolerated() {
        val doc = parseSynthetic(
            """
            <html><body>
              <p>x<a epub:type="noteref" href="#missing" id="A_1"><sup><img class="footnote" alt="note"/></sup></a></p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(1, doc.footnotes.size)
        val fn = doc.footnotes.single()
        assertNull(fn.target)
        assertEquals("", fn.text)
        assertEquals("【注】", fn.markerText)
        assertTrue(doc.warnings.all.any { it.contains("missing") })
    }

    @Test
    fun noterefWithVisibleTextKeepsItAsMarker() {
        val doc = parseSynthetic(
            """
            <html><body>
              <p>x<a epub:type="noteref" href="#n1">1</a></p>
              <aside epub:type="footnote" id="n1">note body</aside>
            </body></html>
            """.trimIndent(),
        )
        assertEquals("1", doc.footnotes.single().markerText)
        assertEquals("note body", doc.footnotes.single().text)
    }

    @Test
    fun duokanClassAloneIsEnoughWhenEpubTypeIsMissing() {
        val doc = parseSynthetic(
            """
            <html><body>
              <p>x<a class="duokan-footnote no-d" href="#n1" id="A_1"><sup><img class="footnote" alt="note"/></sup></a></p>
              <aside epub:type="footnote" id="n1">legacy note</aside>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(1, doc.footnotes.size)
        assertEquals("legacy note", doc.footnotes.single().text)
        assertEquals("【注】", doc.footnotes.single().markerText)
    }
}
