package org.libepub.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.model.Locator
import org.libepub.core.toc.TocEntry
import org.libepub.render.widget.ReaderMode

class ReaderStateTest {

    private class FakeStore(var locator: Locator? = null) : ProgressStore {
        val saved = ArrayList<Locator>()
        var loadedKey: String? = null

        override fun load(bookKey: String): Locator? {
            loadedKey = bookKey
            return locator
        }

        override fun save(bookKey: String, locator: Locator) {
            this.locator = locator
            saved += locator
        }
    }

    private val hrefs = listOf(
        "OEBPS/Text/cover.xhtml",
        "OEBPS/Text/Section000.xhtml",
        "OEBPS/Text/Section001.xhtml",
        "OEBPS/Text/Section002.xhtml",
    )

    private val toc = listOf(
        TocEntry("封面", hrefs[0], null, emptyList()),
        TocEntry(
            "第一章",
            hrefs[1],
            null,
            listOf(
                TocEntry("第一节", hrefs[1], "s1", emptyList()),
                TocEntry("第二节", hrefs[2], "s2", emptyList()),
            ),
        ),
        TocEntry("悬空条目", "OEBPS/Text/missing.xhtml", null, emptyList()),
        TocEntry("无链接", null, null, emptyList()),
    )

    private fun state(store: ProgressStore = FakeStore()) =
        ReaderState("book.epub", hrefs, toc, store)

    @Test
    fun defaultsToTheFirstSpineDocument() {
        val reader = state()
        assertEquals(0, reader.spineIndex)
        assertNull(reader.pendingFragment)
        assertEquals(ReaderMode.PAGED, reader.mode)
    }

    @Test
    fun restoresASavedLocator() {
        val store = FakeStore(Locator(hrefs[2], fragment = "s2", block = 120f, pageIndex = 3))
        val reader = state(store)
        assertEquals(2, reader.spineIndex)
        assertEquals("s2", reader.pendingFragment)
        assertEquals("book.epub", store.loadedKey)
    }

    @Test
    fun ignoresALocatorPointingOutsideTheSpine() {
        val store = FakeStore(Locator("OEBPS/Text/removed.xhtml", pageIndex = 9))
        val reader = state(store)
        assertEquals(0, reader.spineIndex)
        assertNull(reader.pendingFragment)
    }

    @Test
    fun selectingATocEntryMovesToItsSpineAndFragment() {
        val reader = state()
        val chapter = toc[1]
        assertTrue(reader.selectToc(chapter))
        assertEquals(1, reader.spineIndex)
        assertNull(reader.pendingFragment)

        val section = chapter.children[1]
        assertTrue(reader.selectToc(section))
        assertEquals(2, reader.spineIndex)
        assertEquals("s2", reader.pendingFragment)
        assertEquals("s2", reader.consumePendingFragment())
        assertNull(reader.pendingFragment)
    }

    @Test
    fun danglingAndLinklessTocEntriesAreRejected() {
        val reader = state()
        assertFalse(reader.selectToc(toc[2]))
        assertFalse(reader.selectToc(toc[3]))
        assertEquals(0, reader.spineIndex)
    }

    @Test
    fun progressIsPersistedPerBook() {
        val store = FakeStore()
        val reader = state(store)
        val locator = Locator(hrefs[1], fragment = "s1", block = 42f, pageIndex = 2)
        reader.save(locator)
        assertEquals(listOf(locator), store.saved)
        assertEquals(locator, reader.savedLocator())
    }

    @Test
    fun flatTocIsDepthFirstInReadingOrder() {
        val reader = state()
        assertEquals(
            listOf("封面", "第一章", "第一节", "第二节", "悬空条目", "无链接"),
            reader.flatToc.map { it.title },
        )
    }
}
