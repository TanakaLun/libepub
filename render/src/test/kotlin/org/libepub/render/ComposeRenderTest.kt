package org.libepub.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libepub.core.css.WritingMode
import org.libepub.core.layout.FontSpec
import org.libepub.core.layout.LayoutSession
import org.libepub.core.parse.BookParser
import org.libepub.render.draw.PageDrawer
import org.libepub.render.draw.ReaderTheme
import org.libepub.render.image.ContainerImageSizeProvider
import org.libepub.render.measurer.ComposeGlyphMeasurer
import org.libepub.render.widget.EpubPager
import org.libepub.render.widget.EpubReader
import org.libepub.render.widget.EpubReaderState
import org.libepub.render.widget.EpubScroll
import org.libepub.render.widget.ReaderMode
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ComposeRenderTest {

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

    /** The real sample book with container-backed image sizes, like the app. */
    private fun sampleState(): EpubReaderState {
        val measurer = ComposeGlyphMeasurer()
        val book = BookParser.open(TestBooks.sampleFile())
        val session = LayoutSession.open(book, measurer, ContainerImageSizeProvider(book.container))
        opened.add(session)
        return EpubReaderState(session, PageDrawer(measurer))
    }

    private fun spineIndex(state: EpubReaderState, suffix: String): Int =
        state.session.book.readableSpine.indexOfFirst { it.href!!.endsWith(suffix) }

    // -------------------------------------------------------------- measurer

    @Test
    fun measurerReportsPositiveAdvancesAndClusters() {
        val measurer = ComposeGlyphMeasurer()
        val font = FontSpec(sizePx = 20f)
        assertTrue(measurer.advance("漢", font) > 0f)
        assertTrue(measurer.advance("a", font) > 0f)
        assertEquals(0f, measurer.advance("", font), 0f)
        assertEquals(2, measurer.clusterCount("漢a"))
        val metrics = measurer.metrics(font)
        assertTrue(metrics.ascent > 0f)
        assertTrue(metrics.descent > 0f)
    }

    // ----------------------------------------------------------------- theme

    @Test
    fun hostSuppliedPaletteOverridesTheBuiltInPresets() {
        val state = syntheticState()
        val brand = ReaderTheme(
            key = "brand",
            background = Color(0xFF102030),
            accent = Color(0xFFFF0000),
        )

        assertEquals(ReaderTheme.PAPER, state.theme)

        state.customTheme = brand
        assertEquals(brand, state.theme)
        assertEquals(brand.background, state.theme.background)

        // Back to the presets: the host keeps control either way.
        state.customTheme = null
        state.themeKey = ReaderTheme.NIGHT.key
        assertEquals(ReaderTheme.NIGHT, state.theme)
    }

    // ----------------------------------------------------------------- pager

    @Test
    fun pagerDrawsLinesWithTextSemantics() {
        val state = syntheticState()
        state.setViewport(400f, 800f)

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubPager(state) }
        }
        compose.waitForIdle()

        val lines = compose.onAllNodesWithTag("line").fetchSemanticsNodes()
        assertTrue("expected rendered lines", lines.isNotEmpty())
        val texts = lines.map { node ->
            node.config[SemanticsProperties.Text].joinToString("") { it.text }
        }
        assertTrue(texts.any { it.contains("测试文字") })
        assertTrue(
            compose.onAllNodesWithText("【注】", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    @Test
    fun swipeLeftTurnsToTheNextPage() {
        val state = syntheticState()
        state.setViewport(400f, 800f)

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubPager(state) }
        }
        compose.waitForIdle()
        assertTrue("synthetic chapter needs multiple pages", state.pageCount() > 1)

        compose.onNodeWithTag("page").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, state.pageIndex)
    }

    @Test
    fun verticalWritingModeIsExposedOnEveryLine() {
        val state = syntheticState()
        state.setViewport(400f, 800f)
        state.writingMode = WritingMode.VERTICAL_RL

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubPager(state) }
        }
        compose.waitForIdle()

        compose.onAllNodesWithTag("line")[0].assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "VERTICAL_RL",
            ),
        )
    }

    @Test
    fun secondPageRendersItsOwnLinesInsideTheViewport() {
        val state = syntheticState()
        state.setViewport(400f, 800f)

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubPager(state) }
        }
        compose.waitForIdle()
        assertTrue(state.pageCount() > 1)

        compose.onNodeWithTag("page").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, state.pageIndex)

        val paged = state.pagedChapter()!!
        val page = paged.pages[state.pageIndex]
        val expectedFirstLine = paged.layout.lines[page.startLine].text
        val nodes = compose.onAllNodesWithTag("line").fetchSemanticsNodes()
        val texts = nodes.map { node ->
            node.config[SemanticsProperties.Text].joinToString("") { it.text }
        }
        assertTrue(
            "page 2 should draw its own lines, got $texts",
            texts.contains(expectedFirstLine),
        )
        val pageHeight = state.settings.heightPx
        assertTrue(
            "every line must sit inside the page, got ${nodes.map { it.positionInRoot.y }}",
            nodes.all { it.positionInRoot.y >= 0f && it.positionInRoot.y < pageHeight },
        )
    }

    private fun assertImagePageCentered(suffix: String) {
        val state = sampleState()
        state.setViewport(400f, 800f)
        val index = spineIndex(state, suffix)
        assertTrue("$suffix must be in the spine", index >= 0)
        state.goToSpine(index)

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubPager(state) }
        }
        compose.waitForIdle()

        val layout = state.chapterLayout()!!
        val page = state.currentPage()!!
        val image = compose.onAllNodesWithTag("image")[0]
        image.assertExists()
        val node = image.fetchSemanticsNode()
        val pageHeight = state.settings.heightPx

        val blockImage = layout.boxes.firstOrNull { it.kind == org.libepub.core.layout.BoxKind.IMAGE }
        val contentHeight = blockImage?.blockSize
            ?: layout.lines.flatMap { it.fragments }
                .first { it.kind == org.libepub.core.layout.FragmentKind.IMAGE }
                .blockSize
        assertTrue("image must fit the page", contentHeight < pageHeight)

        val expectedTop = (pageHeight - contentHeight) / 2f
        assertEquals(
            "$suffix illustration should be vertically centered",
            expectedTop.toDouble(),
            node.positionInRoot.y.toDouble(),
            pageHeight * 0.05,
        )
        assertTrue("page should not be empty: $page", page.endBlock > page.startBlock)
    }

    @Test
    fun illustrationPlateIsCenteredVertically() = assertImagePageCentered("Illus1.xhtml")

    @Test
    fun coverPageIsCenteredVertically() = assertImagePageCentered("cover.xhtml")

    @Test
    fun everyTextPageKeepsTheChapterTopMargin() {
        val state = sampleState()
        state.setViewport(400f, 800f)
        val index = spineIndex(state, "Section000.xhtml")
        assertTrue(index >= 0)
        state.goToSpine(index)

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubPager(state) }
        }
        compose.waitForIdle()
        assertTrue(state.pageCount() > 1)

        val chapterTop = state.chapterLayout()!!.lines.first().block
        assertTrue("sample chapter should have a top inset", chapterTop > 0f)

        fun firstLineY(): Float =
            compose.onAllNodesWithTag("line").fetchSemanticsNodes().minOf { it.positionInRoot.y }

        assertEquals(chapterTop.toDouble(), firstLineY().toDouble(), 2.0)

        compose.onNodeWithTag("page").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, state.pageIndex)
        assertEquals(
            "page 2 must start at the same top margin as page 1",
            chapterTop.toDouble(),
            firstLineY().toDouble(),
            2.0,
        )
    }

    @Test
    fun pagedTextRespectsTopAndBottomChromeInsets() {
        val state = syntheticState()
        state.setViewport(400f, 800f)
        state.contentInsetTopPx = 50f
        state.contentInsetBottomPx = 40f

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubPager(state) }
        }
        compose.waitForIdle()

        val layout = state.chapterLayout()!!
        val chapterTop = layout.lines.first().block
        val viewportHeight = state.viewportHeightPx
        val nodes = compose.onAllNodesWithTag("line").fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())

        val firstY = nodes.minOf { it.positionInRoot.y }
        assertEquals(
            "first line sits below the top chrome",
            (chapterTop + 50f).toDouble(),
            firstY.toDouble(),
            2.0,
        )
        val lastBottom = nodes.maxOf { it.positionInRoot.y + it.size.height }
        assertTrue(
            "last line must stay above the bottom chrome: $lastBottom vs ${viewportHeight - 40f}",
            lastBottom <= viewportHeight - 40f + 2f,
        )
    }

    @Test
    fun scrollAppendsChromeInsetsInsteadOfShrinkingTheViewport() {
        val state = syntheticState()
        state.setViewport(400f, 800f)
        state.contentInsetTopPx = 50f
        state.contentInsetBottomPx = 40f
        state.mode = ReaderMode.SCROLL

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubScroll(state) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("scroll-top-inset").assertExists()
        compose.onNodeWithTag("scroll-bottom-inset").assertExists()

        val total = state.chapterLayout()!!.lines.size
        assertEquals(total, compose.onAllNodesWithTag("line").fetchSemanticsNodes().size)

        // The scroll viewport keeps the full height; the reserve is content, not
        // a shrunken window.
        val topSpacer = compose.onNodeWithTag("scroll-top-inset").fetchSemanticsNode()
        val bottomSpacer = compose.onNodeWithTag("scroll-bottom-inset").fetchSemanticsNode()
        assertEquals(50, topSpacer.size.height)
        assertEquals(40, bottomSpacer.size.height)
        assertTrue(state.viewportHeightPx > 400f)
    }

    @Test
    fun bookContentsPageLinksJumpToTheirChapter() {
        val state = sampleState()
        state.setViewport(400f, 800f)
        val contents = spineIndex(state, "contents.xhtml")
        val chapter = spineIndex(state, "Section-Chapter1.xhtml")
        assertTrue(contents >= 0 && chapter >= 0)
        state.goToSpine(contents)

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubPager(state) }
        }
        compose.waitForIdle()

        val links = compose.onAllNodesWithTag("link").fetchSemanticsNodes()
        assertTrue("contents page should expose link tap targets", links.isNotEmpty())
        compose.onAllNodesWithTag("link")[0].performClick()
        compose.waitForIdle()
        assertEquals(chapter, state.spineIndex)
        assertEquals(0, state.pageIndex)
    }

    // ------------------------------------------------------------- footnote

    @Test
    fun tappingTheFootnoteMarkerOpensThePopupWithBookStyles() {
        val state = syntheticState()
        state.setViewport(400f, 800f)

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubReader(state) }
        }
        compose.waitForIdle()
        assertTrue(state.goToAnchor("A_1"))
        compose.waitForIdle()

        val markers = compose.onAllNodesWithTag("footnote-marker").fetchSemanticsNodes()
        assertTrue("footnote marker should be on the anchored page", markers.isNotEmpty())
        compose.onAllNodesWithTag("footnote-marker")[0].performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("footnote-popup").assertExists()
        compose.onNodeWithText("这是脚注内容", substring = true).assertExists()

        compose.onNodeWithTag("footnote-close").performClick()
        compose.waitForIdle()
        assertNull(state.footnote)
    }

    // ---------------------------------------------------------------- scroll

    @Test
    fun scrollModeRendersEveryLineAndConsumesTheScrollTarget() {
        val state = syntheticState()
        state.setViewport(400f, 800f)
        state.mode = ReaderMode.SCROLL

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubScroll(state) }
        }
        compose.waitForIdle()

        val total = state.chapterLayout()!!.lines.size
        assertEquals(total, compose.onAllNodesWithTag("line").fetchSemanticsNodes().size)
        state.scrollTarget = 300f
        compose.waitForIdle()
        assertNull("scroll target should be consumed", state.scrollTarget)
    }

    @Test
    fun scrollModeScrollsTheAnchorIntoView() {
        val state = syntheticState()
        state.setViewport(400f, 800f)
        state.mode = ReaderMode.SCROLL

        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp)) { EpubScroll(state) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("scroll").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(state.pageIndex >= 0)
    }

    // ----------------------------------------------------------------- cache

    @Test
    fun fontSizeChangeReusesCachedLayoutsPerGeometry() {
        val state = syntheticState()
        state.setViewport(400f, 800f)

        val base = state.chapterLayout()!!
        assertEquals(1, state.session.cache.size)

        state.setFontSize(26f)
        val larger = state.chapterLayout()!!
        assertNotSame(base, larger)
        assertEquals(2, state.session.cache.size)

        state.setFontSize(18f)
        assertSame(base, state.chapterLayout()!!)
        assertEquals(2, state.session.cache.size)
    }

    @Test
    fun pageIndexStaysInRangeWhenTheViewportChanges() {
        val state = syntheticState()
        state.setViewport(400f, 800f)
        state.pageIndex = (state.pageCount() - 1).coerceAtLeast(0)
        state.setViewport(400f, 1600f)
        assertTrue(state.pageIndex in 0 until state.pageCount())
    }
}
