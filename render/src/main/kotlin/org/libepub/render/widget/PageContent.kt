package org.libepub.render.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import org.libepub.core.css.WritingMode
import org.libepub.core.layout.BoxKind
import org.libepub.core.layout.ChapterLayout
import org.libepub.core.layout.FragmentKind
import org.libepub.core.layout.PageSlice
import org.libepub.core.layout.VerticalText
import org.libepub.core.xhtml.DocNode
import org.libepub.render.draw.PageDrawer
import org.libepub.render.draw.ReaderTheme
import kotlin.math.roundToInt

/** Marker duokan's `notereplace.js` puts in place of the footnote image. */
const val FOOTNOTE_MARKER = "【注】"

/**
 * Draws one page (or one scroll slice) of a chapter and lays a transparent
 * semantics/click box over every line, every footnote marker and every image.
 *
 * The Canvas owns all pixels; the overlay boxes exist so accessibility, tests
 * (`onNodeWithText`, `testTag("line")`) and taps work without re-implementing
 * hit testing. Overlay geometry comes from the same logical rectangles the
 * drawer uses, mapped through [VerticalText.toPhysical].
 */
@Composable
fun PageContent(
    layout: ChapterLayout,
    page: PageSlice,
    pageWidthPx: Float,
    pageHeightPx: Float,
    theme: ReaderTheme,
    drawer: PageDrawer,
    modifier: Modifier = Modifier,
    images: (String) -> ImageBitmap? = { null },
    blockOffset: Float = 0f,
    onFootnoteClick: (DocNode) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onLinkClick: (String, String?) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    Box(
        modifier
            .size(
                width = with(density) { pageWidthPx.toDp() },
                height = with(density) { pageHeightPx.toDp() },
            ),
    ) {
        Canvas(Modifier.size(with(density) { pageWidthPx.toDp() }, with(density) { pageHeightPx.toDp() })) {
            drawer.draw(
                scope = this,
                layout = layout,
                page = page,
                pageWidth = pageWidthPx,
                pageHeight = pageHeightPx,
                theme = theme,
                images = images,
                blockOffset = blockOffset,
            )
        }

        for (index in page.startLine until page.endLine) {
            val line = layout.lines.getOrNull(index) ?: continue
            val rect = physical(
                layout.writingMode,
                line.main,
                line.block - blockOffset,
                line.mainSize,
                line.blockSize,
                pageWidthPx,
            )
            Box(
                Modifier
                    .offset { IntOffset(rect.x.roundToInt(), rect.y.roundToInt()) }
                    .size(
                        with(density) { rect.width.toDp() },
                        with(density) { rect.height.toDp() },
                    )
                    .testTag("line")
                    .semantics {
                        text = AnnotatedString(line.text)
                        stateDescription = layout.writingMode.name
                    },
            )

            // Footnote markers and inline images get their own tap targets.
            for (fragment in line.fragments) {
                val src = fragment.src
                val fragmentRect = physical(
                    layout.writingMode,
                    fragment.main,
                    fragment.block - blockOffset,
                    fragment.mainSize,
                    fragment.blockSize,
                    pageWidthPx,
                )
                when {
                    fragment.kind == FragmentKind.IMAGE && src != null -> {
                        Box(
                            Modifier
                                .offset { IntOffset(fragmentRect.x.roundToInt(), fragmentRect.y.roundToInt()) }
                                .size(
                                    with(density) { fragmentRect.width.toDp() },
                                    with(density) { fragmentRect.height.toDp() },
                                )
                                .testTag("image")
                                .clickable { onImageClick(src) },
                        )
                    }
                    fragment.kind == FragmentKind.TEXT &&
                        fragment.text.orEmpty().contains(FOOTNOTE_MARKER) -> {
                        val node = fragment.node ?: continue
                        Box(
                            Modifier
                                .offset { IntOffset(fragmentRect.x.roundToInt(), fragmentRect.y.roundToInt()) }
                                .size(
                                    with(density) { fragmentRect.width.toDp() },
                                    with(density) { fragmentRect.height.toDp() },
                                )
                                .testTag("footnote-marker")
                                .clickable { onFootnoteClick(node) },
                        )
                    }
                    fragment.kind == FragmentKind.TEXT && fragment.linkHref != null -> {
                        val href = fragment.linkHref
                        Box(
                            Modifier
                                .offset { IntOffset(fragmentRect.x.roundToInt(), fragmentRect.y.roundToInt()) }
                                .size(
                                    with(density) { fragmentRect.width.toDp() },
                                    with(density) { fragmentRect.height.toDp() },
                                )
                                .testTag("link")
                                .clickable { href?.let { onLinkClick(it, fragment.linkFragment) } },
                        )
                    }
                }
            }
        }

        // Block images are not line fragments, so they need their own overlays.
        for (box in layout.boxes) {
            if (box.kind != BoxKind.IMAGE) continue
            if (box.block + box.blockSize < page.startBlock || box.block > page.endBlock) continue
            val src = box.src ?: continue
            val rect = physical(
                layout.writingMode,
                box.main,
                box.block - blockOffset,
                box.mainSize,
                box.blockSize,
                pageWidthPx,
            )
            Box(
                Modifier
                    .offset { IntOffset(rect.x.roundToInt(), rect.y.roundToInt()) }
                    .size(
                        with(density) { rect.width.toDp() },
                        with(density) { rect.height.toDp() },
                    )
                    .testTag("image")
                    .clickable { onImageClick(src) },
            )
        }
    }
}

internal fun physical(
    mode: WritingMode,
    main: Float,
    block: Float,
    mainSize: Float,
    blockSize: Float,
    pageWidthPx: Float,
) = VerticalText.toPhysical(main, block, mainSize, blockSize, mode, pageWidthPx)
