package org.libepub.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libepub.core.layout.LayoutSession
import org.libepub.core.parse.BookParser
import org.libepub.render.draw.PageDrawer
import org.libepub.render.measurer.ComposeGlyphMeasurer
import org.libepub.render.widget.EpubReader
import org.libepub.render.widget.EpubReaderState
import org.libepub.render.widget.ImageGalleryPage
import org.libepub.render.widget.ZoomableImage
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FootnoteGalleryTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = ArrayList<LayoutSession>()

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun stateFor(file: File): EpubReaderState {
        val measurer = ComposeGlyphMeasurer()
        val session = LayoutSession.open(BookParser.open(file), measurer)
        opened.add(session)
        return EpubReaderState(session, PageDrawer(measurer))
    }

    private fun syntheticState(): EpubReaderState = stateFor(TestBooks.syntheticFile())

    // --------------------------------------------------------------- gallery

    @Test
    fun multiImageGroupOpensTheGalleryAndSwipesToTheNextCell() {
        val state = syntheticState()
        state.setViewport(400f, 800f)
        state.openImage("OEBPS/Images/g1.png")

        val gallery = state.gallery
        assertNotNull("gallery should open for a multi-image group", gallery)
        assertEquals(2, gallery!!.images.size)
        assertEquals(0, gallery.index)
        assertNull("a gallery must not open the zoom overlay", state.zoomedImage)

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { ImageGalleryPage(state) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("gallery").assertExists()

        compose.onNodeWithTag("gallery").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, state.gallery?.index)

        compose.onNodeWithTag("gallery-close").performClick()
        compose.waitForIdle()
        assertNull(state.gallery)
    }

    // ------------------------------------------------------------------ zoom

    @Test
    fun singleImageOpensTheZoomOverlayAndPinchChangesScale() {
        val state = syntheticState()
        state.setViewport(400f, 800f)
        state.openImage("OEBPS/Images/single.png")

        assertNotNull(state.zoomedImage)
        assertNull(state.gallery)

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubReader(state) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("zoom-overlay").assertExists()

        compose.onNodeWithTag("zoomable-image").performTouchInput {
            pinch(
                start0 = center - Offset(40f, 0f),
                end0 = center - Offset(140f, 0f),
                start1 = center + Offset(40f, 0f),
                end1 = center + Offset(140f, 0f),
            )
        }
        compose.waitForIdle()
        assertTrue("pinch should zoom in, was ${state.zoomScale}", state.zoomScale > 1f)

        compose.onNodeWithTag("zoom-close").performClick()
        compose.waitForIdle()
        assertNull(state.zoomedImage)
        assertEquals(1f, state.zoomScale, 0f)
    }

    @Test
    fun zoomableImageWorksStandalone() {
        var scale = 1f
        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                ZoomableImage(
                    bitmap = null,
                    contentDescription = "placeholder",
                    onScaleChange = { scale = it },
                )
            }
        }
        compose.onNodeWithTag("zoomable-image").assertExists()
        compose.onNodeWithTag("zoomable-image").performTouchInput {
            pinch(
                start0 = center - Offset(30f, 0f),
                end0 = center - Offset(110f, 0f),
                start1 = center + Offset(30f, 0f),
                end1 = center + Offset(110f, 0f),
            )
        }
        compose.waitForIdle()
        assertTrue(scale > 1f)
    }
}
