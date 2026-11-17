package org.libepub.core.css

// ----------------------------------------------------------------- lengths

enum class LengthUnit { PX, EM, REM, EX, CH, VW, VH, VMIN, VMAX, PT, PC, IN, CM, MM, PERCENT }

/** A CSS length; [resolve] turns it into px against a concrete context. */
data class Length(val value: Float, val unit: LengthUnit) {
    fun resolve(ctx: LengthContext): Float = when (unit) {
        LengthUnit.PX -> value
        LengthUnit.EM -> value * ctx.fontSizePx
        LengthUnit.REM -> value * ctx.rootFontSizePx
        LengthUnit.EX, LengthUnit.CH -> value * ctx.fontSizePx * 0.5f
        LengthUnit.VW -> value * ctx.viewportWidthPx / 100f
        LengthUnit.VH -> value * ctx.viewportHeightPx / 100f
        LengthUnit.VMIN -> value * minOf(ctx.viewportWidthPx, ctx.viewportHeightPx) / 100f
        LengthUnit.VMAX -> value * maxOf(ctx.viewportWidthPx, ctx.viewportHeightPx) / 100f
        LengthUnit.PT -> value * 96f / 72f
        LengthUnit.PC -> value * 16f
        LengthUnit.IN -> value * 96f
        LengthUnit.CM -> value * 96f / 2.54f
        LengthUnit.MM -> value * 96f / 25.4f
        LengthUnit.PERCENT -> (ctx.percentBasePx ?: 0f) * value / 100f
    }
}

/** Context needed to resolve relative lengths. Percentages use [percentBasePx] (property-specific). */
data class LengthContext(
    val fontSizePx: Float,
    val rootFontSizePx: Float,
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val percentBasePx: Float? = null,
)

/** A length that may also be `auto` (margins, widths). */
sealed interface Dimension {
    data class Of(val length: Length) : Dimension
    data object Auto : Dimension
}

data class Edges<T>(val top: T, val right: T, val bottom: T, val left: T) {
    companion object {
        fun <T> all(value: T): Edges<T> = Edges(value, value, value, value)
    }
}

// ------------------------------------------------------------------ colors

sealed class CssColor {
    /** 0xAARRGGBB. */
    data class Rgba(val argb: Long) : CssColor()

    /** `currentColor` — resolved against the inherited `color` at cascade time. */
    data object Current : CssColor()

    val isTransparent: Boolean get() = this is Rgba && (argb ushr 24) == 0L

    companion object {
        val BLACK = Rgba(0xFF000000L)
        val WHITE = Rgba(0xFFFFFFFFL)
        val TRANSPARENT = Rgba(0x00000000L)

        fun rgb(red: Int, green: Int, blue: Int, alpha: Int = 255): Rgba =
            Rgba(((alpha and 0xFF).toLong() shl 24) or ((red and 0xFF).toLong() shl 16) or
                ((green and 0xFF).toLong() shl 8) or (blue and 0xFF).toLong())
    }
}

// ----------------------------------------------------------------- borders

enum class BorderStyle { NONE, HIDDEN, DOTTED, DASHED, SOLID, DOUBLE, GROOVE, RIDGE, INSET, OUTSET }

data class BorderSide(val width: Length?, val style: BorderStyle?, val color: CssColor?) {
    companion object {
        val NONE = BorderSide(null, BorderStyle.NONE, null)
    }
}

enum class Side { TOP, RIGHT, BOTTOM, LEFT }

data class BorderSides(val top: BorderSide, val right: BorderSide, val bottom: BorderSide, val left: BorderSide) {
    fun with(side: Side, value: BorderSide): BorderSides = when (side) {
        Side.TOP -> copy(top = value)
        Side.RIGHT -> copy(right = value)
        Side.BOTTOM -> copy(bottom = value)
        Side.LEFT -> copy(left = value)
    }

    companion object {
        fun all(value: BorderSide): BorderSides = BorderSides(value, value, value, value)
        val NONE = all(BorderSide.NONE)
    }
}

// -------------------------------------------------------------- background

data class Background(
    val color: CssColor? = null,
    /** First `url(...)`, unresolved (caller resolves against the CSS directory). */
    val imageUrl: String? = null,
    val repeat: String? = null,
    val position: String? = null,
    val size: String? = null,
    val attachment: String? = null,
)

// -------------------------------------------------------------- line height

sealed interface LineHeight {
    data object Normal : LineHeight
    data class Number(val value: Float) : LineHeight
    data class OfLength(val length: Length) : LineHeight
}

// ------------------------------------------------------------ text emphasis

data class TextEmphasis(val mark: String? = null, val filled: Boolean? = null, val color: CssColor? = null)

// --------------------------------------------------------------- transforms

sealed interface TransformFn {
    data class Rotate(val degrees: Float) : TransformFn
    data class Scale(val x: Float, val y: Float) : TransformFn
    data class Translate(val x: Length, val y: Length) : TransformFn
    data class Skew(val xDegrees: Float, val yDegrees: Float) : TransformFn
}

// -------------------------------------------------------------------- misc

enum class TextAlign { LEFT, RIGHT, CENTER, JUSTIFY, START, END }

enum class FontStyle { NORMAL, ITALIC, OBLIQUE }

enum class WritingMode { HORIZONTAL_TB, VERTICAL_RL, VERTICAL_LR }

/** `text-orientation`: how Latin glyphs sit in vertical text. */
enum class TextOrientation { MIXED, UPRIGHT, SIDEWAYS }

enum class WhiteSpace { NORMAL, NOWRAP, PRE, PRE_WRAP, PRE_LINE, BREAK_SPACES }

enum class Display {
    BLOCK, INLINE, INLINE_BLOCK, NONE, LIST_ITEM, TABLE, TABLE_ROW, TABLE_CELL, FLEX,
    RUBY, RUBY_TEXT, RUBY_BASE,
}

enum class BoxSizing { CONTENT_BOX, BORDER_BOX }

/** letter-spacing / word-spacing: `normal` or a fixed length. */
sealed interface Spacing {
    data object Normal : Spacing
    data class Fixed(val length: Length) : Spacing
}

sealed interface FontWeight {
    data class Numeric(val value: Int) : FontWeight
    data object Bolder : FontWeight
    data object Lighter : FontWeight
}

sealed interface VerticalAlign {
    data class Keyword(val value: String) : VerticalAlign
    data class OfLength(val length: Length) : VerticalAlign
}

data class TextDecoration(
    val lines: Set<Line>,
    val style: String? = null,
    val color: CssColor? = null,
) {
    enum class Line { UNDERLINE, OVERLINE, LINE_THROUGH }
}

/** One text-shadow layer. */
data class TextShadow(val x: Length, val y: Length, val blur: Length, val color: CssColor?)

enum class CssFloat { NONE, LEFT, RIGHT }

enum class WordWrap { NORMAL, BREAK_WORD }

enum class WordBreak { NORMAL, BREAK_ALL, KEEP_ALL }

enum class BorderCollapse { COLLAPSE, SEPARATE }

enum class PageBreak { AUTO, ALWAYS, AVOID }
