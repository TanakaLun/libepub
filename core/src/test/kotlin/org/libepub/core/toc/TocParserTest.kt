package org.libepub.core.toc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.container.EpubContainer
import org.libepub.core.container.PackageParser
import org.libepub.core.testutil.SampleBooks
import org.libepub.core.testutil.SyntheticZips

class TocParserTest {

    // ---------- real sample book (EPUB2 NCX, 16 navPoints, depth 2) ----------

    @Test
    fun sampleTocComesFromNcx() {
        EpubContainer.open(SampleBooks.sample()).use { container ->
            val toc = TocParser(container).parse()
            assertEquals(TocSource.NCX, toc.source)
            assertEquals("无职转生 ～到了异世界就拿出真本事～ 7", toc.title)
        }
    }

    @Test
    fun sampleTopLevelAndFlatCounts() {
        EpubContainer.open(SampleBooks.sample()).use { container ->
            val toc = TocParser(container).parse()
            assertEquals(8, toc.entries.size)
            assertEquals(16, toc.flat.size)
            // first five top-level titles
            val heads = toc.entries.take(5).map { it.title }
            assertEquals(listOf("封面", "制作信息", "简介", "彩页", "目录"), heads)
        }
    }

    @Test
    fun sampleChapterSevenHasEightChildren() {
        EpubContainer.open(SampleBooks.sample()).use { container ->
            val toc = TocParser(container).parse()
            val chapter7 = toc.entries.first { it.title.startsWith("第七章") }
            assertEquals(8, chapter7.children.size)
            assertEquals("序章", chapter7.children.first().title)
            assertEquals("终章", chapter7.children[7].title)
        }
    }

    @Test
    fun sampleEveryHrefExistsInContainer() {
        EpubContainer.open(SampleBooks.sample()).use { container ->
            val toc = TocParser(container).parse()
            for (entry in toc.flat) {
                val href = entry.href
                assertNotNull("entry '${entry.title}' must have href", href)
                assertTrue("missing $href", container.exists(href!!))
            }
        }
    }

    // ---------- synthetic EPUB3 nav-only book ----------

    @Test
    fun epub3NavOnlyBookParsesNav() {
        val file = SyntheticZips.write(packageXml = NAV_OPF, extra = mapOf("OEBPS/nav.xhtml" to NAV_DOC))
        EpubContainer.open(file).use { container ->
            val pkg = PackageParser(container).parse()
            val toc = TocParser(container, pkg).parse()
            assertEquals(TocSource.NAV, toc.source)
            assertEquals("Nav Book", toc.title)
            assertEquals(2, toc.entries.size)
            assertEquals("One", toc.entries[0].title)
            assertEquals("OEBPS/Text/a.xhtml", toc.entries[0].href)
            assertEquals("deep", toc.entries[0].children[0].fragment)
            assertEquals(3, toc.flat.size)
        }
    }

    @Test
    fun navLandmarksAndPageListAreExposed() {
        val file = SyntheticZips.write(packageXml = NAV_OPF, extra = mapOf("OEBPS/nav.xhtml" to NAV_DOC))
        EpubContainer.open(file).use { container ->
            val toc = TocParser(container).parse()
            assertEquals(2, toc.landmarks.size)
            assertEquals("cover", toc.landmarks[0].type)
            assertEquals("OEBPS/Text/a.xhtml", toc.landmarks[0].href)
            assertEquals(1, toc.pageLabels.size)
            assertEquals("12", toc.pageLabels[0].label)
            assertEquals("OEBPS/Text/a.xhtml", toc.pageLabels[0].href)
            assertEquals("p12", toc.pageLabels[0].fragment)
        }
    }

    // ---------- synthetic NCX pageList ----------

    @Test
    fun ncxPageListIsExtracted() {
        val file = SyntheticZips.write(packageXml = NCX_OPF, extra = mapOf("OEBPS/toc.ncx" to NCX_WITH_PAGES))
        EpubContainer.open(file).use { container ->
            val toc = TocParser(container).parse()
            assertEquals(TocSource.NCX, toc.source)
            assertEquals(1, toc.entries.size)
            assertEquals(2, toc.pageLabels.size)
            assertEquals("i", toc.pageLabels[0].label)
            assertEquals("OEBPS/Text/a.xhtml", toc.pageLabels[1].href)
            assertEquals("p1", toc.pageLabels[1].fragment)
        }
    }

    @Test
    fun missingTocYieldsNoneWithoutThrowing() {
        val file = SyntheticZips.write() // minimal OPF: no ncx, no nav
        EpubContainer.open(file).use { container ->
            val toc = TocParser(container).parse()
            assertEquals(TocSource.NONE, toc.source)
            assertTrue(toc.entries.isEmpty())
        }
    }

    // ---------- fixtures ----------

    private companion object {
        val NAV_OPF = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bid">urn:test</dc:identifier>
                <dc:title>Nav Book</dc:title>
                <dc:language>en</dc:language>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c" href="Text/a.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c"/></spine>
            </package>
        """.trimIndent()

        val NAV_DOC = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>Nav Book</title></head>
              <body>
                <nav epub:type="toc" id="toc">
                  <h1>Table of Contents</h1>
                  <ol>
                    <li><a href="Text/a.xhtml">One</a>
                      <ol><li><a href="Text/a.xhtml#deep">Deep</a></li></ol>
                    </li>
                    <li><a href="Text/a.xhtml#two">Two</a></li>
                  </ol>
                </nav>
                <nav epub:type="landmarks" hidden="">
                  <ol>
                    <li><a epub:type="cover" href="Text/a.xhtml">Cover</a></li>
                    <li><a epub:type="bodymatter" href="Text/a.xhtml#start">Start</a></li>
                  </ol>
                </nav>
                <nav epub:type="page-list" hidden="">
                  <ol><li><a href="Text/a.xhtml#p12">12</a></li></ol>
                </nav>
              </body>
            </html>
        """.trimIndent()

        val NCX_OPF = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bid">urn:test</dc:identifier>
                <dc:title>Ncx Book</dc:title>
                <dc:language>en</dc:language>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c" href="Text/a.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="c"/></spine>
            </package>
        """.trimIndent()

        val NCX_WITH_PAGES = """
            <?xml version="1.0" encoding="utf-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="urn:test"/></head>
              <docTitle><text>Ncx Book</text></docTitle>
              <navMap>
                <navPoint id="n1" playOrder="1">
                  <navLabel><text>Chapter</text></navLabel>
                  <content src="Text/a.xhtml"/>
                </navPoint>
              </navMap>
              <pageList>
                <pageTarget id="p1" type="front" value="i">
                  <navLabel><text>i</text></navLabel>
                  <content src="Text/a.xhtml"/>
                </pageTarget>
                <pageTarget id="p2" type="normal" value="12">
                  <navLabel><text>12</text></navLabel>
                  <content src="Text/a.xhtml#p1"/>
                </pageTarget>
              </pageList>
            </ncx>
        """.trimIndent()
    }
}
