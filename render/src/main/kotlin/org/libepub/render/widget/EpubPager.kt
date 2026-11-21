package org.libepub.render.widget

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.libepub.render.draw.ReaderTheme

/**
 * Horizontal pager: one page per screen, swipe left for the next page and
 * right for the previous one. Chapter boundaries are crossed transparently.
 *
 * The gesture detector lives on the container, not on the page content, so the
 * per-line click targets (footnotes, images) keep working while a drag still
 * turns the page.
 */
@Composable
fun EpubPager(
    state: EpubReaderState,
    modifier: Modifier = Modifier,
    onPageChanged: (Int) -> Unit = {},
    swipeEnabled: Boolean = true,
) {
    val density = LocalDensity.current
    SideEffect { state.density = density }

    val threshold = with(density) { 48.dp.toPx() }
    val paged = state.pagedChapter()
    val page = paged?.pages?.getOrNull(state.pageIndex)

    // Preload keys include the viewport: on first composition the viewport is
    // only known after layout, and the page would otherwise keep placeholders.
    LaunchedEffect(
        state.spineIndex,
        state.pageIndex,
        state.fontSizeSp,
        state.writingMode,
        state.themeKey,
        state.settings.widthPx,
        state.settings.heightPx,
    ) {
        state.preloadVisibleImages()
        onPageChanged(state.pageIndex)
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag("page")
            .onSizeChanged { state.setViewport(it.width.toFloat(), it.height.toFloat()) }
            .then(if (swipeEnabled) Modifier.pointerInput(state.spineIndex, paged?.pageCount, state.pageIndex) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total <= -threshold) state.nextPage()
                        else if (total >= threshold) state.previousPage()
                    },
                    onDragCancel = { total = 0f },
                ) { _, dragAmount -> total += dragAmount }
            } else Modifier)
    ) {
        val theme: ReaderTheme = state.theme
        if (page != null) {
            // The page box is already the safe content size; translate it below
            // the status/top chrome (and right of the left chrome in vertical
            // writing, where the block axis is horizontal).
            Box(
                Modifier.offset {
                    IntOffset(
                        state.contentInsetLeftPx.roundToInt(),
                        state.contentInsetTopPx.roundToInt(),
                    )
                },
            ) {
                PageContent(
                    layout = paged.layout,
                    page = page,
                    pageWidthPx = state.settings.widthPx,
                    pageHeightPx = state.settings.heightPx,
                    theme = theme,
                    drawer = state.drawer,
                    images = { src -> state.imageFor(src) },
                    blockOffset = state.pageBlockOffset(page, paged.layout),
                    onFootnoteClick = { marker -> state.openFootnote(marker) },
                    onImageClick = { src -> state.openImage(src) },
                    onLinkClick = { href, fragment -> state.openLink(href, fragment) },
                )
            }
        }
    }
}
