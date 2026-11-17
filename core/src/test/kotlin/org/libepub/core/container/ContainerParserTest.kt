package org.libepub.core.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerParserTest {

    private fun openSample(): EpubContainer = EpubContainer.open(org.libepub.core.testutil.SampleBooks.sample())

    @Test
    fun readsSampleMetadata() {
        openSample().use { container ->
            val pkg = PackageParser(container).parse()
            assertEquals("无职转生 ～到了异世界就拿出真本事～ 7", pkg.metadata.title)
            assertEquals(listOf("理不尽な孙の手"), pkg.metadata.creators)
            assertEquals("zh-TW", pkg.metadata.language)
            assertEquals("2.0", pkg.version)
        }
    }

    @Test
    fun resolvesManifestAndSpine() {
        openSample().use { container ->
            val pkg = PackageParser(container).parse()
            assertEquals(24, pkg.spine.size)
            assertTrue(pkg.spine.all { it.linear })
            assertNotNull("style.css must be in manifest", pkg.manifestByHref["OEBPS/Styles/style.css"])
            assertNotNull("jp.ttf must be in manifest", pkg.manifestByHref["OEBPS/Fonts/jp.ttf"])
            // spine order starts with the cover page
            assertEquals("OEBPS/Text/cover.xhtml", pkg.spine.first().href)
            assertEquals("application/xhtml+xml", pkg.manifestByHref[pkg.spine.first().href]?.mediaType)
        }
    }

    @Test
    fun resolvesCoverAndNcx() {
        openSample().use { container ->
            val pkg = PackageParser(container).parse()
            assertEquals("OEBPS/Images/cover.jpg", pkg.coverImageHref)
            assertNotNull("guide cover reference", pkg.guide.firstOrNull { it.type == "cover" })
            assertEquals("OEBPS/toc.ncx", pkg.tocHref)
        }
    }

    @Test
    fun containerXmlLocatesOpf() {
        openSample().use { container ->
            assertEquals("OEBPS/content.opf", container.opfPath)
            assertTrue(container.exists("OEBPS/Text/Section000.xhtml"))
            assertTrue(container.readText("mimetype").startsWith("application/epub+zip"))
        }
    }
}
