package org.libepub.core.layout

import org.libepub.core.css.CssColor
import org.libepub.core.css.TextDecoration
import org.libepub.core.style.ComputedStyle

/**
 * Turns `text-decoration` into positioned strokes.
 *
 * Offsets are logical: underline sits on the block-after side of the baseline
 * (below the text horizontally, left of the column vertically), line-through
 * crosses the middle of the ascent, overline hugs the top of the em box.
 */
object DecorationLayout {

    fun strokes(
        fragment: InlineFragment,
        baseline: Float,
        style: ComputedStyle,
    ): List<DecorationStroke> {
        val decoration = style.textDecoration ?: return emptyList()
        if (decoration.lines.isEmpty()) return emptyList()
        val fontSize = style.fontSizePx
        val color = decoration.color?.takeIf { it != CssColor.Current } ?: style.color
        val thickness = thicknessFor(decoration, fontSize)
        val out = ArrayList<DecorationStroke>(decoration.lines.size)

        for (kind in decoration.lines) {
            val block = when (kind) {
                TextDecoration.Line.UNDERLINE -> baseline + fontSize * UNDERLINE_OFFSET
                TextDecoration.Line.LINE_THROUGH -> baseline - fragment.baseline * 0.35f
                TextDecoration.Line.OVERLINE -> fragment.block - thickness
            }
            out.add(
                DecorationStroke(
                    line = when (kind) {
                        TextDecoration.Line.UNDERLINE -> DecorationLine.UNDERLINE
                        TextDecoration.Line.LINE_THROUGH -> DecorationLine.LINE_THROUGH
                        TextDecoration.Line.OVERLINE -> DecorationLine.OVERLINE
                    },
                    main = fragment.main,
                    mainSize = fragment.mainSize,
                    block = block,
                    thickness = thickness,
                    color = color,
                ),
            )
        }
        return out
    }

    private fun thicknessFor(decoration: TextDecoration, fontSize: Float): Float {
        val base = (fontSize / 14f).coerceIn(1f, 3f)
        return when (decoration.style) {
            "double" -> base
            "dotted", "dashed" -> base
            else -> base
        }
    }

    private const val UNDERLINE_OFFSET = 0.09f
}
