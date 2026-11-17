package org.libepub.app

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libepub.core.layout.BoxKind
import org.libepub.core.model.Locator
import org.libepub.render.image.ContainerImageSizeProvider
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end: real sample book → bookshelf → reader → illustration → zoom.
 *
 * Uses a filesystem [BookSource] instead of APK assets: Robolectric's asset
 * manager can list the sample but cannot open its non-ASCII name, which is a
 * test-harness limitation, not an app one. The production path (assets) is
 * exercised by the installed APK.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppE2ETest {

    @get:Rule
    val compose = createComposeRule()

    private class FileBookSource(private val file: File) : BookSource {
        override fun list(): List<BookEntry> =
            listOf(BookEntry(file.name, file.name.removeSuffix(".epub")))

        override fun ensureLocal(entry: BookEntry): File = file
    }

    private class InMemoryProgress : ProgressStore {
        var locator: Locator? = null
        override fun load(bookKey: String): Locator? = locator
        override fun save(bookKey: String, locator: Locator) {
            this.locator = locator
        }
    }

    private val opened = ArrayList<Any>()

    @After
    fun tearDown() {
        opened.forEach { if (it is org.libepub.core.layout.LayoutSession) it.close() }
        opened.clear()
    }

    private fun sampleBook(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val assets = File(dir, "assets")
            if (assets.isDirectory) {
                assets.listFiles()?.firstOrNull { it.extension.equals("epub", ignoreCase = true) }
                    ?.let { return it }
            }
            dir = dir.parentFile
        }
        // The book is copyrighted and not committed: skip on a fresh clone.
        org.junit.Assume.assumeTrue(
            "no sample epub in assets/; drop one there or pass -Dlibepub.sample=/path/to/book.epub",
            false,
        )
        error("unreachable: the assumption above always fails here")
    }

    @Test
    fun bookshelfOpensTheBookAndShowsAClickableIllustration() {
        compose.setContent { LibEpubApp(FileBookSource(sampleBook())) }
        compose.waitForIdle()

        val books = compose.onAllNodesWithTag("book").fetchSemanticsNodes()
        assertTrue("bookshelf should list the book", books.isNotEmpty())

        compose.onAllNodesWithTag("book")[0].performClick()

        // Cover chapter: image-only line, then the cover illustration overlay.
        compose.waitUntil(timeoutMillis = 180_000) {
            compose.onAllNodesWithTag("line").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 180_000) {
            compose.onAllNodesWithTag("image").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onAllNodesWithTag("image")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("zoom-overlay").assertExists()

        compose.onNodeWithTag("zoom-close").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("page").assertExists()
    }

    @Test
    fun openedBookSizesIllustrationsFromTheContainer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = FileBookSource(sampleBook())
        val entry = source.list().first()
        val book = openBook(entry, source, context, InMemoryProgress())
        opened.add(book.session)
        try {
            val spine = book.reader.session.book.readableSpine
            val index = spine.indexOfFirst { it.href!!.endsWith("Illus1.xhtml") }
            assertTrue("illustration chapter must be in the spine", index >= 0)
            book.reader.goToSpine(index)

            val layout = book.reader.chapterLayout()!!
            val box = layout.boxes.single { it.kind == BoxKind.IMAGE }
            assertNotNull("block image must expose its resolved src", box.src)
            assertTrue(box.src!!.startsWith("OEBPS/"))
            assertTrue(book.reader.session.book.container.exists(box.src!!))
            assertFalse("image must not be the 4em placeholder", box.placeholder)
            // 1000px-wide art clamped to the column, not the 4em fallback square.
            assertTrue("image should fill the column: ${box.mainSize}", box.mainSize > 300f)
            assertTrue("image should be centered: ${box.main}", box.main > 0f)

            val intrinsic = ContainerImageSizeProvider(
                book.reader.session.book.container,
            ).intrinsicSize(box.src!!)
            assertNotNull("intrinsic size must decode", intrinsic)
            assertEquals(
                "image width is the intrinsic width clamped to the column",
                minOf(intrinsic!!.width, layout.widthPx),
                box.mainSize,
                layout.widthPx * 0.05f,
            )
        } finally {
            book.session.close()
        }
    }
}
