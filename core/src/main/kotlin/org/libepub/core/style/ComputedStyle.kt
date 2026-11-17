package org.libepub.core.style

import org.libepub.core.css.Background
import org.libepub.core.css.BorderCollapse
import org.libepub.core.css.BorderSides
import org.libepub.core.css.BoxSizing
import org.libepub.core.css.CssColor
import org.libepub.core.css.CssFloat
import org.libepub.core.css.Dimension
import org.libepub.core.css.Display
import org.libepub.core.css.Edges
import org.libepub.core.css.FontStyle
import org.libepub.core.css.Length
import org.libepub.core.css.LengthUnit
import org.libepub.core.css.LineHeight
import org.libepub.core.css.PageBreak
import org.libepub.core.css.TextAlign
import org.libepub.core.css.TextDecoration
import org.libepub.core.css.TextEmphasis
import org.libepub.core.css.TextOrientation
import org.libepub.core.css.TextShadow
import org.libepub.core.css.TransformFn
import org.libepub.core.css.VerticalAlign
import org.libepub.core.css.WhiteSpace
import org.libepub.core.css.WordBreak
import org.libepub.core.css.WordWrap
import org.libepub.core.css.WritingMode

/**
 * Fully resolved style for one element: every relative unit that can be
 * resolved without layout (em/rem/pt/vw/vh) is already px, inherited values
 * are already copied from the parent, and `currentColor` is already replaced
 * by the element's own [color]. Only percentages (which depend on the
 * containing block) stay symbolic.
 *
 * Instances are immutable and safe to share; the layout engine keys caches on
 * them.
 */
data class ComputedStyle(
    val display: Display = Display.INLINE,
    val float: CssFloat = CssFloat.NONE,
    val writingMode: WritingMode = WritingMode.HORIZONTAL_TB,

    // ------------------------------------------------------------- fonts/text
    val fontFamily: List<String> = DEFAULT_FONT_FAMILIES,
    val fontSizePx: Float = 16f,
    val fontWeight: Int = 400,
    val fontStyle: FontStyle = FontStyle.NORMAL,
    val lineHeight: LineHeight = LineHeight.Normal,
    val color: CssColor = CssColor.BLACK,
    val textAlign: TextAlign = TextAlign.START,
    /** px, or a percentage of the containing block width. */
    val textIndent: Length = Length(0f, LengthUnit.PX),
    val textDecoration: TextDecoration? = null,
    val textEmphasis: TextEmphasis? = null,
    val textShadow: List<TextShadow> = emptyList(),
    val letterSpacingPx: Float = 0f,
    val wordSpacingPx: Float = 0f,
    val whiteSpace: WhiteSpace = WhiteSpace.NORMAL,
    val wordWrap: WordWrap = WordWrap.NORMAL,
    val wordBreak: WordBreak = WordBreak.NORMAL,
    val verticalAlign: VerticalAlign = VerticalAlign.Keyword("baseline"),
    val textOrientation: TextOrientation = TextOrientation.MIXED,

    // ---------------------------------------------------------------- box model
    /** px / percent / auto. */
    val margin: Edges<Dimension> = ZERO_MARGIN,
    /** px / percent. */
    val padding: Edges<Length> = ZERO_PADDING,
    val border: BorderSides = BorderSides.NONE,
    val borderCollapse: BorderCollapse = BorderCollapse.SEPARATE,
    val borderSpacingPx: Float = 0f,
    val boxSizing: BoxSizing = BoxSizing.CONTENT_BOX,
    /** Explicit width/height; null means auto. Percent stays symbolic. */
    val width: Dimension? = null,
    val height: Dimension? = null,
    val maxWidth: Dimension? = null,
    val maxHeight: Dimension? = null,

    // -------------------------------------------------------------- background
    val background: Background = Background(),

    // ------------------------------------------------------------------- lists
    val listStyleType: String? = null,
    val listStylePosition: String? = null,

    // ------------------------------------------------------------------- misc
    val pageBreakBefore: PageBreak = PageBreak.AUTO,
    val pageBreakAfter: PageBreak = PageBreak.AUTO,
    val transform: List<TransformFn> = emptyList(),
    val opacity: Float = 1f,
) {
    /** True for boxes that start a new block-level flow. */
    val isBlockLevel: Boolean
        get() = display == Display.BLOCK || display == Display.LIST_ITEM ||
            display == Display.TABLE || display == Display.FLEX

    val isHidden: Boolean get() = display == Display.NONE

    /** Used line height in px, or null when `line-height: normal` (font metrics decide). */
    val lineHeightPx: Float?
        get() = when (val lh = lineHeight) {
            LineHeight.Normal -> null
            is LineHeight.Number -> lh.value * fontSizePx
            is LineHeight.OfLength -> lh.length.value
        }

    /** Resolves a percentage-bearing length against [basePx]. */
    fun resolvePx(length: Length, basePx: Float): Float = when (length.unit) {
        LengthUnit.PERCENT -> basePx * length.value / 100f
        else -> length.value
    }

    fun resolvePx(dimension: Dimension, basePx: Float): Float? = when (dimension) {
        Dimension.Auto -> null
        is Dimension.Of -> resolvePx(dimension.length, basePx)
    }

    /** True when the element paints any border on any side. */
    val hasVisibleBorder: Boolean
        get() = listOf(border.top, border.right, border.bottom, border.left).any {
            it.style != null && it.style != org.libepub.core.css.BorderStyle.NONE &&
                it.style != org.libepub.core.css.BorderStyle.HIDDEN && (it.width?.value ?: 0f) > 0f
        }

    companion object {
        val DEFAULT_FONT_FAMILIES: List<String> = listOf("serif")

        val ZERO_MARGIN: Edges<Dimension> =
            Edges.all(Dimension.Of(Length(0f, LengthUnit.PX)))

        val ZERO_PADDING: Edges<Length> = Edges.all(Length(0f, LengthUnit.PX))

        /** Root of every cascade (no parent). */
        val INITIAL: ComputedStyle = ComputedStyle()
    }
}
