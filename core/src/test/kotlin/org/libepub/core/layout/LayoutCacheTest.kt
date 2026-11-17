package org.libepub.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.css.WritingMode
import org.libepub.core.model.Document
import org.libepub.core.parse.BookParser
import org.libepub.core.testutil.SampleBooks
import org.libepub.core.util.LruStore
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.XhtmlParser

class LayoutCacheTest {

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

    private val settings = LayoutSession.Settings(
        fontSizePx = 16f,
        widthPx = 400f,
        heightPx = 800f,
    )

    private fun session(): LayoutSession =
        LayoutSession.open(BookParser.open(SampleBooks.sample()), FixedMeasurer())

    private fun chapterHref(session: LayoutSession): String =
        session.book.readableSpine.first { it.href!!.contains("Section000") }.href!!

    // ------------------------------------------------------------------- hits

    @Test
    fun sameSettingsHitTheSameCachedLayout() {
        session().use { session ->
            val href = chapterHref(session)
            val first = session.layout(href, settings)
            val second = session.layout(href, settings)
            assertSame(first, second)
            assertEquals(1, session.cache.size)
        }
    }

    @Test
    fun fontSizeWidthHeightModeAndThemeAllChangeTheKey() {
        session().use { session ->
            val href = chapterHref(session)
            session.layout(href, settings)
            session.layout(href, settings.copy(fontSizePx = 18f))
            session.layout(href, settings.copy(widthPx = 320f))
            session.layout(href, settings.copy(heightPx = 640f))
            session.layout(href, settings.copy(writingMode = WritingMode.VERTICAL_RL))
            session.layout(href, settings.copy(theme = "sepia"))
            assertEquals(6, session.cache.size)

            val keys = session.cache.keys()
            assertTrue(keys.any { it.fontSizePx == 18f })
            assertTrue(keys.any { it.widthPx == 320f })
            assertTrue(keys.any { it.heightPx == 640f })
            assertTrue(keys.any { it.writingMode == WritingMode.VERTICAL_RL })
            assertTrue(keys.any { it.theme == "sepia" })
        }
    }

    @Test
    fun invalidateEmptiesTheCache() {
        session().use { session ->
            session.layout(chapterHref(session), settings)
            assertEquals(1, session.cache.size)
            session.invalidate()
            assertEquals(0, session.cache.size)
            assertNull(session.cache.get(settings.cacheKey(chapterHref(session))))
        }
    }

    // -------------------------------------------------------------------- LRU

    @Test
    fun lruStoreEvictsTheLeastRecentlyUsedEntry() {
        val store = LruStore<String, String>(maxEntries = 2)
        store.put("a", "1")
        store.put("b", "2")
        assertEquals("1", store.get("a")) // a becomes most recent
        store.put("c", "3")
        assertEquals(listOf("a", "c"), store.keys())
        assertNull(store.get("b"))
    }

    @Test
    fun chapterCacheKeepsOnlyTheConfiguredBudget() {
        val document: Document = XhtmlParser.parse(
            "<html><body><p>x</p></body></html>",
            "OEBPS/Text/x.xhtml",
            WarningSink(),
        )
        fun layout(href: String) = ChapterLayout(
            document = document,
            writingMode = WritingMode.HORIZONTAL_TB,
            widthPx = 100f,
            heightPx = 100f,
            contentBlockSize = 0f,
            lines = emptyList(),
            boxes = emptyList(),
            anchors = emptyMap(),
            pageBreaks = emptyList(),
        )
        val cache = ChapterLayoutCache(maxEntries = 2)
        cache.put(settings.cacheKey("a"), layout("a"))
        cache.put(settings.cacheKey("b"), layout("b"))
        cache.get(settings.cacheKey("a"))
        cache.put(settings.cacheKey("c"), layout("c"))
        assertEquals(listOf("a", "c"), cache.keys().map { it.href })
    }
}
