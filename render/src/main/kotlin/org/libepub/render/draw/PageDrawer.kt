package org.libepub.render.draw

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.libepub.core.css.WritingMode
import org.libepub.core.layout.BoxKind
import org.libepub.core.layout.ChapterLayout
import org.libepub.core.layout.DecorationLine
import org.libepub.core.layout.EmphasisMark
import org.libepub.core.layout.FragmentKind
import org.libepub.core.layout.GlyphOrientation
import org.libepub.core.layout.InlineFragment
import org.libepub.core.layout.LayoutLine
import org.libepub.core.layout.PageSlice
import org.libepub.core.layout.PhysicalRect
import org.libepub.core.layout.RubyAnnotation
import org.libepub.core.layout.VerticalText
import org.libepub.core.layout.toFontSpec
import org.libepub.core.style.ComputedStyle
import org.libepub.render.font.FontRegistry
import org.libepub.render.measurer.ComposeGlyphMeasurer
import kotlin.math.roundToInt

/**
 * Draws one [PageSlice] of a [ChapterLayout] onto a Compose canvas.
 *
 * This is the only place where logical (`main`, `block`) coordinates become
 * physical pixels; [VerticalText.toPhysical] performs the mapping for every
 * rectangle, so horizontal and vertical reading share the same drawing code.
 */
class PageDrawer(
    private val measurer: ComposeGlyphMeasurer,
    private val fonts: FontRegistry? = null,
) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun draw(
        scope: DrawScope,
        layout: ChapterLayout,
        page: PageSlice,
        pageWidth: Float,
        pageHeight: Float,
        theme: ReaderTheme,
        images: (String) -> ImageBitmap? = { null },
        blockOffset: Float = 0f,
    ) {
        scope.drawRect(theme.background, Offset.Zero, Size(pageWidth, pageHeight))

        drawBoxBackgrounds(scope, layout, page, pageWidth, blockOffset, theme, images)

        for (index in page.startLine until page.endLine) {
            val line = layout.lines.getOrNull(index) ?: continue
            drawLine(scope, layout, line, pageWidth, blockOffset, theme, images)
        }

        drawBlockImages(scope, layout, page, pageWidth, blockOffset, images)
    }

    // ------------------------------------------------------------------ boxes

    private fun drawBoxBackgrounds(
        scope: DrawScope,
        layout: ChapterLayout,
        page: PageSlice,
        pageWidth: Float,
        blockOffset: Float,
        theme: ReaderTheme,
        images: (String) -> ImageBitmap?,
    ) {
        for (box in layout.boxes) {
            if (box.block + box.blockSize < page.startBlock || box.block > page.endBlock) continue
            val rect = physical(layout.writingMode, box.main, box.block - blockOffset, box.mainSize, box.blockSize, pageWidth)
            box.style.background.color?.takeUnless { it.isTransparent }?.let { color ->
                scope.drawRect(color.toComposeColor(), Offset(rect.x, rect.y), Size(rect.width, rect.height))
            }
            box.style.background.imageUrl?.let { url ->
                images(url)?.let { bitmap ->
                    scope.drawImage(
                        bitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = IntOffset(rect.x.roundToInt(), rect.y.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
                    )
                }
            }
            if (box.style.hasVisibleBorder) {
                val color = (box.style.border.top.color ?: box.style.color).toComposeColor()
                scope.drawRect(
                    color,
                    Offset(rect.x, rect.y),
                    Size(rect.width, rect.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = (box.style.border.top.width?.value ?: 1f).coerceAtLeast(1f),
                    ),
                )
            }
        }
    }

    private fun drawBlockImages(
        scope: DrawScope,
        layout: ChapterLayout,
        page: PageSlice,
        pageWidth: Float,
        blockOffset: Float,
        images: (String) -> ImageBitmap?,
    ) {
        for (box in layout.boxes) {
            if (box.kind != BoxKind.IMAGE) continue
            if (box.block + box.blockSize < page.startBlock || box.block > page.endBlock) continue
            val src = box.src ?: continue
            drawImageBox(scope, layout.writingMode, box.main, box.block - blockOffset, box.mainSize, box.blockSize, pageWidth, images(src))
        }
    }

    // ------------------------------------------------------------------- lines

    private fun drawLine(
        scope: DrawScope,
        layout: ChapterLayout,
        line: LayoutLine,
        pageWidth: Float,
        blockOffset: Float,
        theme: ReaderTheme,
        images: (String) -> ImageBitmap?,
    ) {
        val mode = layout.writingMode
        for (fragment in line.fragments) {
            when (fragment.kind) {
                FragmentKind.IMAGE -> {
                    drawImageBox(
                        scope,
                        mode,
                        fragment.main,
                        fragment.block - blockOffset,
                        fragment.mainSize,
                        fragment.blockSize,
                        pageWidth,
                        images(fragment.src ?: ""),
                    )
                }
                FragmentKind.TEXT, FragmentKind.LIST_MARKER -> {
                    val rect = physical(mode, fragment.main, fragment.block - blockOffset, fragment.mainSize, fragment.blockSize, pageWidth)
                    val color = (theme.foreground ?: fragment.style.color.toComposeColor())
                    if (mode == WritingMode.HORIZONTAL_TB) {
                        drawHorizontalRun(scope, fragment, rect, color, line.justifyExtraPx)
                    } else {
                        drawVerticalRun(scope, fragment, line, rect, color)
                    }
                }
                FragmentKind.ATOMIC -> Unit
            }
        }

        for (stroke in line.decorations) {
            val rect = physical(mode, stroke.main, stroke.block - blockOffset, stroke.mainSize, stroke.thickness, pageWidth)
            scope.drawRect(stroke.color.toComposeColor(), Offset(rect.x, rect.y), Size(rect.width, rect.height))
        }
        for (mark in line.emphasisMarks) {
            drawMark(scope, mode, mark, pageWidth, blockOffset, theme)
        }
        for (annotation in line.rubyAnnotations) {
            drawRuby(scope, mode, annotation, pageWidth, blockOffset, theme)
        }
    }

    private fun drawHorizontalRun(
        scope: DrawScope,
        fragment: InlineFragment,
        rect: PhysicalRect,
        color: Color,
        justifyExtraPx: Float,
    ) {
        val text = fragment.text ?: return
        if (text.isEmpty()) return
        val style = fragment.style
        applyTextPaint(style, color)
        val baseline = rect.y + fragment.baseline
        val spacing = style.letterSpacingPx
        if (spacing == 0f && justifyExtraPx <= 0f) {
            drawText(scope, text, rect.x, baseline)
            return
        }
        val clusters = org.libepub.core.layout.GraphemeCluster.split(text)
        val gaps = (clusters.size - 1).coerceAtLeast(1)
        val extra = if (justifyExtraPx > 0f) justifyExtraPx / gaps else 0f
        var x = rect.x
        for (cluster in clusters) {
            drawText(scope, cluster.text, x, baseline)
            x += measurer.advance(cluster.text, style.toFontSpec()) + spacing + extra
        }
    }

    private fun drawVerticalRun(
        scope: DrawScope,
        fragment: InlineFragment,
        line: LayoutLine,
        rect: PhysicalRect,
        color: Color,
    ) {
        val text = fragment.text ?: return
        if (text.isEmpty()) return
        val style = fragment.style
        applyTextPaint(style, color)
        val orientations = line.orientations
            .filter { it.main >= fragment.main - 0.01f && it.main < fragment.main + fragment.mainSize + 0.01f }
            .ifEmpty { VerticalText.orientations(text, fragment.main, style, measurer) }
        val blockSize = fragment.blockSize
        for (glyph in orientations) {
            val box = physical(
                WritingMode.VERTICAL_RL,
                glyph.main,
                fragment.block,
                glyph.mainSize,
                blockSize,
                0f,
            )
            drawVerticalGlyph(scope, glyph, box, style, color)
        }
    }

    private fun drawVerticalGlyph(
        scope: DrawScope,
        glyph: GlyphOrientation,
        box: PhysicalRect,
        style: ComputedStyle,
        color: Color,
    ) {
        applyTextPaint(style, color)
        scope.drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (glyph.upright) {
                val width = textPaint.measureText(glyph.grapheme)
                val x = box.x + (box.width - width) / 2f
                val y = box.y + box.height / 2f + textPaint.textSize * 0.35f
                native.drawText(glyph.grapheme, x, y, textPaint)
            } else {
                val width = textPaint.measureText(glyph.grapheme)
                native.save()
                native.rotate(90f, box.x + box.width / 2f, box.y + box.height / 2f)
                native.drawText(
                    glyph.grapheme,
                    box.x + (box.width - width) / 2f,
                    box.y + box.height / 2f + textPaint.textSize * 0.35f,
                    textPaint,
                )
                native.restore()
            }
        }
    }

    private fun drawMark(
        scope: DrawScope,
        mode: WritingMode,
        mark: EmphasisMark,
        pageWidth: Float,
        blockOffset: Float,
        theme: ReaderTheme,
    ) {
        val rect = physical(mode, mark.main, mark.block - blockOffset, mark.mainSize, mark.blockSize, pageWidth)
        applyTextPaint(mark.style, theme.foreground ?: mark.style.color.toComposeColor())
        val width = textPaint.measureText(mark.mark)
        drawText(
            scope,
            mark.mark,
            rect.x + (rect.width - width) / 2f,
            rect.y + rect.height / 2f + textPaint.textSize * 0.35f,
        )
    }

    private fun drawRuby(
        scope: DrawScope,
        mode: WritingMode,
        annotation: RubyAnnotation,
        pageWidth: Float,
        blockOffset: Float,
        theme: ReaderTheme,
    ) {
        val rect = physical(mode, annotation.main, annotation.block - blockOffset, annotation.mainSize, annotation.blockSize, pageWidth)
        applyTextPaint(annotation.style, theme.foreground ?: annotation.style.color.toComposeColor())
        val width = textPaint.measureText(annotation.text)
        drawText(
            scope,
            annotation.text,
            rect.x + (rect.width - width) / 2f,
            rect.y + rect.height / 2f + textPaint.textSize * 0.35f,
        )
    }

    // ------------------------------------------------------------------ images

    private fun drawImageBox(
        scope: DrawScope,
        mode: WritingMode,
        main: Float,
        block: Float,
        mainSize: Float,
        blockSize: Float,
        pageWidth: Float,
        bitmap: ImageBitmap?,
    ) {
        val rect = physical(mode, main, block, mainSize, blockSize, pageWidth)
        if (bitmap == null) {
            scope.drawRect(
                Color(0x22000000),
                Offset(rect.x, rect.y),
                Size(rect.width, rect.height),
            )
            return
        }
        scope.drawImage(
            bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(rect.x.roundToInt(), rect.y.roundToInt()),
            dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
        )
    }

    // ----------------------------------------------------------------- helpers

    private fun physical(
        mode: WritingMode,
        main: Float,
        block: Float,
        mainSize: Float,
        blockSize: Float,
        pageWidth: Float,
    ): PhysicalRect = VerticalText.toPhysical(main, block, mainSize, blockSize, mode, pageWidth)

    private fun applyTextPaint(style: ComputedStyle, color: Color) {
        textPaint.textSize = style.fontSizePx
        textPaint.typeface = fonts?.typefaceFor(style.toFontSpec()) ?: Typeface.DEFAULT
        textPaint.isFakeBoldText = style.fontWeight >= 600
        textPaint.textSkewX = if (style.fontStyle != org.libepub.core.css.FontStyle.NORMAL) -0.25f else 0f
        textPaint.color = color.toArgb()
    }

    private fun drawText(scope: DrawScope, text: String, x: Float, y: Float) {
        scope.drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(text, x, y, textPaint) }
    }
}
