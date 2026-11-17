package org.libepub.core.layout

import org.libepub.core.css.CssColor
import org.libepub.core.css.TextEmphasis
import org.libepub.core.style.ComputedStyle

/**
 * Lays out `text-emphasis` marks. A mark is placed once per grapheme cluster on
 * the block-before side of the glyph and centered on its advance, which gives
 * the even CJK spacing readers expect; in vertical writing the same logical
 * position maps to the right of the column.
 *
 * CSS keyword marks are translated to concrete Unicode glyphs here so the
 * render layer never needs to know the keyword table.
 */
object EmphasisLayout {

    fun marks(
        fragment: InlineFragment,
        emphasis: TextEmphasis?,
        measurer: GlyphMeasurer,
    ): List<EmphasisMark> {
        val style = fragment.style
        val emphasisStyle = emphasis ?: style.textEmphasis ?: return emptyList()
        val glyph = glyphFor(emphasisStyle) ?: return emptyList()
        val text = fragment.text ?: return emptyList()
        if (text.isEmpty()) return emptyList()

        val color = emphasisStyle.color?.takeIf { it != CssColor.Current } ?: style.color
        val font = style.toFontSpec()
        val markWidth = measurer.advance(glyph, font)
        val markHeight = measurer.metrics(font).height
        val markBlock = fragment.block - markHeight

        val out = ArrayList<EmphasisMark>(text.length)
        var main = fragment.main
        for (grapheme in GraphemeCluster.split(text)) {
            val advance = measurer.advance(grapheme.text, font)
            if (grapheme.text.isNotBlank()) {
                out.add(
                    EmphasisMark(
                        mark = glyph,
                        main = main + (advance - markWidth) / 2f,
                        mainSize = markWidth,
                        block = markBlock,
                        blockSize = markHeight,
                        style = style.copy(fontSizePx = style.fontSizePx, color = color),
                    ),
                )
            }
            main += advance
        }
        return out
    }

    /** Concrete glyph for a `text-emphasis-style` value, or null for `none`. */
    fun glyphFor(emphasis: TextEmphasis): String? {
        val mark = emphasis.mark ?: return null
        if (mark == "none") return null
        val filled = emphasis.filled ?: true
        return when (mark) {
            "dot" -> if (filled) "\u2022" else "\u25E6" // • ◦
            "circle" -> if (filled) "\u25CF" else "\u25CB" // ● ○
            "double-circle" -> if (filled) "\u25C9" else "\u25CE" // ◉ ◎
            "triangle" -> if (filled) "\u25B2" else "\u25B3" // ▲ △
            "sesame" -> if (filled) "\uFE45" else "\uFE46" // ﹅ ﹆
            else -> mark.trim('\'', '"').ifEmpty { null }
        }
    }
}
