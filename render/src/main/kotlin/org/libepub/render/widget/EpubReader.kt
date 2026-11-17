package org.libepub.render.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The reader: page or scroll mode plus the three overlays (footnote popup,
 * gallery, full-screen zoom). Hosts only need to place this inside their screen
 * chrome and drive [EpubReaderState].
 *
 * [contentModifier] insets the reading surface only (the host draws its own
 * bars on top); the overlays stay full-bleed.
 */
@Composable
fun EpubReader(
    state: EpubReaderState,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(state.theme.background)) {
        EpubReaderContent(state, contentModifier)
        EpubReaderOverlays(state)
    }
}

/** Page/scroll surface only, without popups — for hosts that own the chrome. */
@Composable
fun EpubReaderContent(
    state: EpubReaderState,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (state.mode) {
            ReaderMode.PAGED -> EpubPager(state)
            ReaderMode.SCROLL -> EpubScroll(state)
        }
    }
}

/** Footnote popup, gallery and zoom viewer; draw last so they cover all chrome. */
@Composable
fun EpubReaderOverlays(state: EpubReaderState) {
    FootnotePopup(state)
    ImageGalleryPage(state)
    state.zoomedImage?.let { src -> ZoomOverlay(state, src) }
}

@Composable
private fun ZoomOverlay(state: EpubReaderState, src: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("zoom-overlay"),
    ) {
        ZoomableImage(
            bitmap = state.imageFor(src),
            contentDescription = null,
            onScaleChange = { state.zoomScale = it },
        )
        OverlayTextButton(
            text = "关闭",
            color = Color.White,
            onClick = { state.closeZoom() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
                .testTag("zoom-close"),
        )
    }
}
