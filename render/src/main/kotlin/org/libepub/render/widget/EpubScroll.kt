package org.libepub.render.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import org.libepub.core.layout.PageSlice
import kotlin.math.roundToInt

/**
 * Continuous vertical reading of the same layout the pager slices: the whole
 * chapter is drawn as one tall page and scrolled. No re-layout happens between
 * modes, which is what keeps a mode switch instant.
 */
@Composable
fun EpubScroll(
    state: EpubReaderState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    SideEffect { state.density = density }

    Box(
        modifier
            .fillMaxSize()
            .testTag("scroll")
            .onSizeChanged { state.setViewport(it.width.toFloat(), it.height.toFloat()) },
    ) {
        val layout = state.chapterLayout() ?: return@Box
        val paged = state.pagedChapter()
        val scrollState = rememberScrollState()

        LaunchedEffect(state.spineIndex, state.fontSizeSp, state.writingMode, layout) {
            state.preloadVisibleImages()
        }

        LaunchedEffect(state.scrollTarget) {
            val target = state.scrollTarget ?: return@LaunchedEffect
            scrollState.scrollTo(target.roundToInt().coerceAtLeast(0))
            state.scrollTarget = null
        }

        LaunchedEffect(scrollState, paged) {
            snapshotFlow { scrollState.value }.collect { offset ->
                val block = (offset - state.contentInsetTopPx).coerceAtLeast(0f)
                val page = paged?.pageAt(block) ?: return@collect
                if (page >= 0) state.pageIndex = page
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // The host chrome is not allowed to cover text: the same reserve
                // the pager subtracts from the page box is appended as blank
                // space at the top/bottom of the chapter instead.
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { state.contentInsetTopPx.toDp() })
                        .testTag("scroll-top-inset"),
                )
                Box(
                    Modifier.offset { IntOffset(state.contentInsetLeftPx.roundToInt(), 0) },
                ) {
                    PageContent(
                        layout = layout,
                        page = PageSlice(
                            index = 0,
                            startLine = 0,
                            endLine = layout.lines.size,
                            startBlock = 0f,
                            endBlock = layout.contentBlockSize,
                        ),
                        pageWidthPx = state.settings.widthPx,
                        pageHeightPx = layout.contentBlockSize.coerceAtLeast(1f),
                        theme = state.theme,
                        drawer = state.drawer,
                        images = { src -> state.imageFor(src) },
                        onFootnoteClick = { marker -> state.openFootnote(marker) },
                        onImageClick = { src -> state.openImage(src) },
                        onLinkClick = { href, fragment -> state.openLink(href, fragment) },
                    )
                }
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { state.contentInsetBottomPx.toDp() })
                        .testTag("scroll-bottom-inset"),
                )
            }
        }
    }
}
