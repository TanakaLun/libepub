package org.libepub.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libepub.core.layout.BoxKind
import org.libepub.core.layout.FragmentKind
import org.libepub.core.layout.LayoutSession
import org.libepub.core.parse.BookParser
import org.libepub.render.image.ContainerImageSizeProvider
import org.libepub.render.measurer.ComposeGlyphMeasurer
import org.robolectric.annotation.Config

/**
 * Image pipeline: intrinsic sizes must come from the container (so the layout
 * scales art to the column) and block image boxes must carry the zip-resolved
 * source (so the loader can actually find the resource).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImagePipelineTest {

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

    @Test
    fun providerReadsIntrinsicBoundsFromTheContainer() {
        val session = sampleSession()
        val provider = ContainerImageSizeProvider(session.book.container)
        val size = provider.intrinsicSize("OEBPS/Images/cover.jpg")
        println("image size: $size")
        assertNotNull("cover.jpg bounds should decode", size)
        assertTrue(size!!.width > 0f)
        assertTrue(size.height > 0f)
        // Cached and identical on the second call.
        assertEquals(size, provider.intrinsicSize("OEBPS/Images/cover.jpg"))
        assertNull(provider.intrinsicSize("OEBPS/Images/does-not-exist.jpg"))
    }

    @Test
    fun illustrationsAreSizedFromTheContainerNotTheFourEmFallback() {
        val session = sampleSession()
        val provider = ContainerImageSizeProvider(session.book.container)
        val href = session.book.readableSpine.first { it.href!!.endsWith("Illus1.xhtml") }.href!!
        val settings = LayoutSession.Settings(fontSizePx = 18f, widthPx = 400f, heightPx = 800f)
        val layout = LayoutSession(
            session.book,
            session.measurer,
            imageSizes = provider,
        ).also { opened.add(it) }.layout(href, settings)

        val box = layout.boxes.single { it.kind == BoxKind.IMAGE }
        assertNotNull("block image box must carry a resolved src", box.src)
        assertTrue("src must be zip-root resolved: ${box.src}", box.src!!.startsWith("OEBPS/"))
        assertTrue(session.book.container.exists(box.src!!))
        assertTrue("image must not be a placeholder", !box.placeholder)
        // Full column width (the art is wider than 400px) and centered by .illus.
        assertTrue("image should fill the column: ${box.mainSize}", box.mainSize > 380f)
        assertTrue("image should be centered", box.main > 0f)
    }

    @Test
    fun inlineCoverImageCarriesAResolvedSource() {
        val session = sampleSession()
        val provider = ContainerImageSizeProvider(session.book.container)
        val href = session.book.readableSpine.first { it.href!!.endsWith("cover.xhtml") }.href!!
        val settings = LayoutSession.Settings(fontSizePx = 18f, widthPx = 400f, heightPx = 800f)
        val layout = LayoutSession(
            session.book,
            session.measurer,
            imageSizes = provider,
        ).also { opened.add(it) }.layout(href, settings)

        val image = layout.lines.flatMap { it.fragments }.single { it.kind == FragmentKind.IMAGE }
        assertNotNull(image.src)
        assertTrue(image.src!!.startsWith("OEBPS/"))
        assertTrue(session.book.container.exists(image.src!!))
        assertTrue(image.mainSize > 0f)
    }
}
