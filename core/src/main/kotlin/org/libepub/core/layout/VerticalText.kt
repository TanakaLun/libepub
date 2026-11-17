package org.libepub.core.layout

import org.libepub.core.css.TextOrientation
import org.libepub.core.css.WritingMode

/** A physical rectangle in page coordinates (pixels, y down). */
data class PhysicalRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/**
 * Maps logical layout coordinates onto physical page coordinates.
 *
 * The layout engine never thinks in x/y: `main` is the inline axis and `block`
 * the line-stacking axis. Horizontal-tb maps them directly; vertical-rl runs
 * the inline axis downward and stacks lines leftward, so block 0 is the right
 * edge of the page. Everything a vertical reader sees on the "right" of a
 * glyph (ruby, emphasis) is block-before and therefore a smaller block value —
 * the same value a horizontal reader sees on "top".
 */
object VerticalText {

    fun toPhysical(
        main: Float,
        block: Float,
        mainSize: Float,
        blockSize: Float,
        writingMode: WritingMode,
        pageWidth: Float,
    ): PhysicalRect = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> PhysicalRect(main, block, mainSize, blockSize)
        WritingMode.VERTICAL_RL -> PhysicalRect(pageWidth - block - blockSize, main, blockSize, mainSize)
        WritingMode.VERTICAL_LR -> PhysicalRect(block, main, blockSize, mainSize)
    }

    /**
     * True when [grapheme] keeps its upright form in vertical text.
     * `mixed` (the initial value) rotates Latin/symbol clusters 90° clockwise
     * but keeps CJK, kana and fullwidth forms upright; `upright` forces every
     * cluster upright; `sideways` rotates everything.
     */
    fun isUpright(grapheme: String, orientation: TextOrientation): Boolean = when (orientation) {
        TextOrientation.UPRIGHT -> true
        TextOrientation.SIDEWAYS -> false
        TextOrientation.MIXED -> grapheme.isNotEmpty() && (
            Kinsoku.isCjk(grapheme) || grapheme.all { it.isWhitespace() }
            )
    }

    /** Orientation entries for one run of text, in logical coordinates. */
    fun orientations(
        text: String,
        main: Float,
        style: org.libepub.core.style.ComputedStyle,
        measurer: GlyphMeasurer,
    ): List<GlyphOrientation> {
        if (text.isEmpty()) return emptyList()
        val font = style.toFontSpec()
        val out = ArrayList<GlyphOrientation>(text.length)
        var cursor = main
        for (grapheme in GraphemeCluster.split(text)) {
            val advance = measurer.advance(grapheme.text, font)
            out.add(
                GlyphOrientation(
                    grapheme = grapheme.text,
                    main = cursor,
                    mainSize = advance,
                    upright = isUpright(grapheme.text, style.textOrientation),
                ),
            )
            cursor += advance
        }
        return out
    }
}
