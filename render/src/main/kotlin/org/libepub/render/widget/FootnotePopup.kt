package org.libepub.render.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.libepub.render.draw.ReaderTheme
import org.libepub.render.draw.toComposeColor

/**
 * Footnote popup. The content is the real `aside[epub:type=footnote]` subtree,
 * laid out by the same engine with the book's own stylesheets — not a plain
 * text dump — so font family, size, indentation and inline markup all match the
 * page.
 */
@Composable
fun FootnotePopup(
    state: EpubReaderState,
    modifier: Modifier = Modifier,
) {
    val footnote = state.footnote ?: return
    val layout = state.footnoteLayout ?: return
    val theme: ReaderTheme = state.theme
    val widthPx = state.footnoteWidthPx.takeIf { it > 1f } ?: state.settings.widthPx
    val contentHeight = layout.contentBlockSize.coerceAtLeast(1f)

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .testTag("footnote-popup"),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(theme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                text = "脚注",
                style = TextStyle(
                    color = theme.accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                ),
            )
            PageContent(
                layout = layout,
                page = org.libepub.core.layout.PageSlice(
                    index = 0,
                    startLine = 0,
                    endLine = layout.lines.size,
                    startBlock = 0f,
                    endBlock = layout.contentBlockSize,
                ),
                pageWidthPx = widthPx,
                pageHeightPx = contentHeight,
                theme = theme,
                drawer = state.drawer,
                images = { src -> state.imageFor(src) },
            )
            if (footnote.text.isBlank()) {
                BasicText(
                    text = footnote.markerText,
                    style = TextStyle(
                        color = theme.foreground ?: Color.Black,
                        fontSize = 14.sp,
                    ),
                )
            }
            OverlayTextButton(
                text = "关闭",
                color = theme.accent,
                onClick = { state.closeFootnote() },
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("footnote-close"),
            )
        }
    }
}

/**
 * Duokan image gallery: one image per screen, swipe horizontally. The current
 * index is written back to [EpubReaderState.gallery] so the reader can resume
 * at the same cell.
 */
@Composable
fun ImageGalleryPage(
    state: EpubReaderState,
    modifier: Modifier = Modifier,
) {
    val gallery = state.gallery ?: return
    if (gallery.images.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = gallery.index.coerceIn(0, gallery.images.lastIndex),
    ) { gallery.images.size }

    LaunchedEffect(pagerState.currentPage) {
        state.gallery = gallery.copy(index = pagerState.currentPage)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("gallery"),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val image = gallery.images.getOrNull(page) ?: return@HorizontalPager
            Box(Modifier.fillMaxSize().testTag("gallery-page")) {
                ZoomableImage(
                    bitmap = state.imageFor(image.src),
                    contentDescription = image.alt,
                    onScaleChange = { state.zoomScale = it },
                )
            }
        }
        BasicText(
            text = "${pagerState.currentPage + 1} / ${gallery.images.size}",
            style = TextStyle(color = Color.White, fontSize = 13.sp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth(),
        )
        OverlayTextButton(
            text = "关闭",
            color = Color.White,
            onClick = { state.closeGallery() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
                .testTag("gallery-close"),
        )
    }
}
