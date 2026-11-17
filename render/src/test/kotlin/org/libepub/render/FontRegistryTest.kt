package org.libepub.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libepub.core.layout.FontSpec
import org.libepub.core.layout.LayoutSession
import org.libepub.core.parse.BookParser
import org.libepub.core.util.WarningSink
import org.libepub.render.font.FontLoader
import org.libepub.render.font.FontRegistry
import org.libepub.render.measurer.ComposeGlyphMeasurer
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FontRegistryTest {

    private val opened = ArrayList<LayoutSession>()

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun sampleSession(): LayoutSession {
        val session = LayoutSession.open(BookParser.open(TestBooks.sampleFile()), ComposeGlyphMeasurer())
        opened.add(session)
        return session
    }

    private fun cacheDir(): File {
        val dir = File.createTempFile("libepub-fonts-", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    @Test
    fun sampleFontFacesAreRegisteredWithResolvedPaths() {
        val session = sampleSession()
        val href = session.book.readableSpine.first { it.href!!.endsWith("Section000.xhtml") }.href!!
        val document = session.book.chapter(href)
        val warnings = WarningSink()
        val registry = FontRegistry(null, warnings)
        registry.register(session.book.stylesheetsFor(document))

        assertEquals(setOf("jp", "title", "illus1"), registry.families)

        // Without a loader every family falls back to the platform default, silently.
        assertNull(registry.typefaceFor(FontSpec(family = listOf("jp"))))
        assertTrue(warnings.all.isEmpty())
    }

    @Test
    fun fontsAreExtractedFromTheContainerAndLoaded() {
        val session = sampleSession()
        val href = session.book.readableSpine.first { it.href!!.endsWith("Section000.xhtml") }.href!!
        val document = session.book.chapter(href)
        val warnings = WarningSink()
        val loader = FontLoader(session.book.container, cacheDir(), warnings)
        val registry = FontRegistry(loader, warnings)
        registry.register(session.book.stylesheetsFor(document))

        val typeface = registry.typefaceFor(FontSpec(family = listOf("jp"), sizePx = 16f))
        assertNotNull("jp.ttf should decode to a Typeface", typeface)
        // Second lookup is served from the registry cache (same instance).
        assertSame(typeface, registry.typefaceFor(FontSpec(family = listOf("jp"), sizePx = 22f)))
        assertTrue(warnings.all.isEmpty())
    }

    @Test
    fun missingFamilyFallsBackOnceWithAWarning() {
        val session = sampleSession()
        val href = session.book.readableSpine.first { it.href!!.endsWith("Section000.xhtml") }.href!!
        val document = session.book.chapter(href)
        val warnings = WarningSink()
        val loader = FontLoader(session.book.container, cacheDir(), warnings)
        val registry = FontRegistry(loader, warnings)
        registry.register(session.book.stylesheetsFor(document))

        val spec = FontSpec(family = listOf("DK-SONGTI"), sizePx = 16f)
        assertNull(registry.typefaceFor(spec))
        assertNull(registry.typefaceFor(spec))
        assertEquals(1, warnings.all.count { it.contains("dk-songti", ignoreCase = true) })

        // Generic families never warn: the platform handles them.
        assertNull(registry.typefaceFor(FontSpec(family = listOf("serif"))))
        assertEquals(1, warnings.all.count { it.contains("font family not found") })
    }

    @Test
    fun loaderCachesBySource() {
        val session = sampleSession()
        val warnings = WarningSink()
        val loader = FontLoader(session.book.container, cacheDir(), warnings)
        val src = "OEBPS/Fonts/jp.ttf"
        val first = loader.load(src)
        assertNotNull(first)
        assertSame(first, loader.load(src))
        assertNull(loader.load("OEBPS/Fonts/missing.ttf"))
        assertEquals(1, warnings.all.count { it.contains("missing.ttf") })
    }
}
