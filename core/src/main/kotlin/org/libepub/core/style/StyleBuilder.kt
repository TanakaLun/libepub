package org.libepub.core.style

import org.libepub.core.css.BorderCollapse
import org.libepub.core.css.BorderSide
import org.libepub.core.css.BorderSides
import org.libepub.core.css.BorderStyle
import org.libepub.core.css.BoxSizing
import org.libepub.core.css.CssColor
import org.libepub.core.css.CssFloat
import org.libepub.core.css.Dimension
import org.libepub.core.css.Display
import org.libepub.core.css.Edges
import org.libepub.core.css.FontStyle
import org.libepub.core.css.Length
import org.libepub.core.css.LengthContext
import org.libepub.core.css.LengthUnit
import org.libepub.core.css.LineHeight
import org.libepub.core.css.PageBreak
import org.libepub.core.css.Side
import org.libepub.core.css.Spacing
import org.libepub.core.css.TextAlign
import org.libepub.core.css.TextDecoration
import org.libepub.core.css.TextEmphasis
import org.libepub.core.css.TextOrientation
import org.libepub.core.css.TextShadow
import org.libepub.core.css.TransformFn
import org.libepub.core.css.ValueParser
import org.libepub.core.css.VerticalAlign
import org.libepub.core.css.WhiteSpace
import org.libepub.core.css.WordBreak
import org.libepub.core.css.WordWrap
import org.libepub.core.css.WritingMode
import org.libepub.core.util.WarningSink

/**
 * Mutable accumulator for one element's style. Inherited properties start from
 * [parent]; everything else starts from its CSS initial value. Longhand
 * declarations are applied in a canonical order so `font-size` is known before
 * any `em` is resolved.
 */
internal class StyleBuilder(
    private val parent: ComputedStyle?,
    private val rootFontSizePx: Float,
    private val viewportWidthPx: Float,
    private val viewportHeightPx: Float,
    private val warnings: WarningSink,
) {
    private var display: Display = Display.INLINE
    private var float: CssFloat = CssFloat.NONE
    private var writingMode: WritingMode = parent?.writingMode ?: WritingMode.HORIZONTAL_TB

    private var fontFamily: List<String> = parent?.fontFamily ?: ComputedStyle.DEFAULT_FONT_FAMILIES
    private var fontSizePx: Float = parent?.fontSizePx ?: rootFontSizePx
    private var fontWeight: Int = parent?.fontWeight ?: 400
    private var fontStyle: FontStyle = parent?.fontStyle ?: FontStyle.NORMAL
    private var lineHeight: LineHeight = parent?.lineHeight ?: LineHeight.Normal
    private var color: CssColor = parent?.color ?: CssColor.BLACK
    private var textAlign: TextAlign = parent?.textAlign ?: TextAlign.START
    private var textIndent: Length = parent?.textIndent ?: Length(0f, LengthUnit.PX)
    private var textDecoration: TextDecoration? = parent?.textDecoration
    private var emphasisMark: String? = parent?.textEmphasis?.mark
    private var emphasisFilled: Boolean? = parent?.textEmphasis?.filled
    private var emphasisColor: CssColor? = parent?.textEmphasis?.color
    private var textShadow: List<TextShadow> = parent?.textShadow ?: emptyList()
    private var letterSpacingPx: Float = parent?.letterSpacingPx ?: 0f
    private var wordSpacingPx: Float = parent?.wordSpacingPx ?: 0f
    private var whiteSpace: WhiteSpace = parent?.whiteSpace ?: WhiteSpace.NORMAL
    private var wordWrap: WordWrap = parent?.wordWrap ?: WordWrap.NORMAL
    private var wordBreak: WordBreak = parent?.wordBreak ?: WordBreak.NORMAL
    private var verticalAlign: VerticalAlign = VerticalAlign.Keyword("baseline")
    private var textOrientation: TextOrientation = parent?.textOrientation ?: TextOrientation.MIXED

    private var margin: Edges<Dimension> = ComputedStyle.ZERO_MARGIN
    private var padding: Edges<Length> = ComputedStyle.ZERO_PADDING
    private var borderTop: BorderSide = BorderSide.NONE
    private var borderRight: BorderSide = BorderSide.NONE
    private var borderBottom: BorderSide = BorderSide.NONE
    private var borderLeft: BorderSide = BorderSide.NONE
    private var borderCollapse: BorderCollapse = BorderCollapse.SEPARATE
    private var borderSpacingPx: Float = 0f
    private var boxSizing: BoxSizing = BoxSizing.CONTENT_BOX
    private var width: Dimension? = null
    private var height: Dimension? = null
    private var maxWidth: Dimension? = null
    private var maxHeight: Dimension? = null

    private var backgroundColor: CssColor? = null
    private var backgroundImage: String? = null
    private var backgroundRepeat: String? = null
    private var backgroundPosition: String? = null
    private var backgroundSize: String? = null

    private var listStyleType: String? = parent?.listStyleType
    private var listStylePosition: String? = parent?.listStylePosition
    private var pageBreakBefore: PageBreak = PageBreak.AUTO
    private var pageBreakAfter: PageBreak = PageBreak.AUTO
    private var transform: List<TransformFn> = emptyList()
    private var opacity: Float = 1f

    fun applyAll(properties: Map<String, String>) {
        for (property in APPLY_ORDER) {
            val value = properties[property] ?: continue
            apply(property, value)
        }
        for (property in properties.keys) {
            if (property !in HANDLED) {
                warnings.warnOnce("css.prop:$property", "unsupported CSS property ignored: $property")
            }
        }
    }

    // --------------------------------------------------------------- applying

    private fun apply(property: String, value: String) {
        when (property) {
            "font-size" -> applyFontSize(value)
            "font-family" -> ValueParser.parseFontFamily(value)?.let { fontFamily = it }
            "font-weight" -> applyFontWeight(value)
            "font-style" -> when (value.trim().lowercase()) {
                "normal" -> fontStyle = FontStyle.NORMAL
                "italic" -> fontStyle = FontStyle.ITALIC
                "oblique" -> fontStyle = FontStyle.OBLIQUE
            }
            "font-variant" -> Unit // small-caps unsupported; harmless
            "line-height" -> applyLineHeight(value)
            "color" -> ValueParser.parseColor(value)?.let { color = if (it == CssColor.Current) color else it }
            "display" -> ValueParser.parseDisplay(value)?.let { display = it }
            "float" -> ValueParser.parseCssFloat(value)?.let { float = it }
            "writing-mode" -> ValueParser.parseWritingMode(value)?.let { writingMode = it }

            "text-align" -> ValueParser.parseTextAlign(value)?.let { textAlign = it }
            "text-indent" -> ValueParser.parseLength(value)?.let { textIndent = resolveToPx(it) }
            "text-decoration" -> applyTextDecoration(value)
            "text-decoration-line" -> applyTextDecorationLines(value)
            "text-decoration-color" -> Unit // carried by text-decoration only
            "text-decoration-style" -> Unit
            "text-emphasis-style" -> applyEmphasisStyle(value)
            "text-emphasis-color" -> ValueParser.parseColor(value)?.let {
                emphasisColor = if (it == CssColor.Current) color else it
            }
            "text-shadow" -> applyTextShadow(value)
            "letter-spacing" -> letterSpacingPx = spacingPx(value)
            "word-spacing" -> wordSpacingPx = spacingPx(value)
            "white-space" -> ValueParser.parseWhiteSpace(value)?.let { whiteSpace = it }
            "word-wrap" -> ValueParser.parseWordWrap(value)?.let { wordWrap = it }
            "word-break" -> ValueParser.parseWordBreak(value)?.let { wordBreak = it }
            "vertical-align" -> applyVerticalAlign(value)
            "text-orientation" -> ValueParser.parseTextOrientation(value)?.let { textOrientation = it }

            "margin-top" -> parseDimension(value)?.let { margin = margin.copy(top = it) }
            "margin-right" -> parseDimension(value)?.let { margin = margin.copy(right = it) }
            "margin-bottom" -> parseDimension(value)?.let { margin = margin.copy(bottom = it) }
            "margin-left" -> parseDimension(value)?.let { margin = margin.copy(left = it) }
            "padding-top" -> parsePadding(value)?.let { padding = padding.copy(top = it) }
            "padding-right" -> parsePadding(value)?.let { padding = padding.copy(right = it) }
            "padding-bottom" -> parsePadding(value)?.let { padding = padding.copy(bottom = it) }
            "padding-left" -> parsePadding(value)?.let { padding = padding.copy(left = it) }

            "border-top-width", "border-right-width", "border-bottom-width", "border-left-width" ->
                parseBorderWidth(value)?.let { setBorderWidth(sideOf(property), it) }
            "border-top-style", "border-right-style", "border-bottom-style", "border-left-style" ->
                parseBorderStyle(value)?.let { setBorderStyle(sideOf(property), it) }
            "border-top-color", "border-right-color", "border-bottom-color", "border-left-color" ->
                ValueParser.parseColor(value)?.let { setBorderColor(sideOf(property), it) }
            "border-collapse" -> ValueParser.parseBorderCollapse(value)?.let { borderCollapse = it }
            "border-spacing" -> ValueParser.parseLength(ValueParser.tokenize(value).firstOrNull() ?: value)
                ?.let { borderSpacingPx = resolveToPx(it).value }

            "background-color" -> ValueParser.parseColor(value)?.let {
                backgroundColor = if (it == CssColor.Current) color else it
            }
            "background-image" -> backgroundImage = if (value.equals("none", true)) {
                null
            } else {
                ValueParser.parseUrl(value) ?: backgroundImage
            }
            "background-repeat" -> backgroundRepeat = value.trim().lowercase()
            "background-position" -> backgroundPosition = value.trim().lowercase()
            "background-size" -> backgroundSize = value.trim().lowercase()
            "background-attachment" -> Unit

            "width" -> width = parseDimension(value)
            "height" -> height = parseDimension(value)
            "max-width" -> maxWidth = parseMaxDimension(value)
            "max-height" -> maxHeight = parseMaxDimension(value)
            "box-sizing" -> ValueParser.parseBoxSizing(value)?.let { boxSizing = it }

            "list-style-type" -> listStyleType = value.trim().lowercase()
            "list-style-position" -> listStylePosition = value.trim().lowercase()
            "page-break-before", "break-before" -> ValueParser.parsePageBreak(value)?.let { pageBreakBefore = it }
            "page-break-after", "break-after" -> ValueParser.parsePageBreak(value)?.let { pageBreakAfter = it }

            "transform" -> applyTransform(value)
            "opacity" -> ValueParser.parseNumber(value)?.let { opacity = it.coerceIn(0f, 1f) }
        }
    }

    private fun applyFontSize(value: String) {
        val length = ValueParser.parseFontSize(value) ?: return
        val parentSize = parent?.fontSizePx ?: rootFontSizePx
        val context = LengthContext(
            fontSizePx = parentSize,
            rootFontSizePx = rootFontSizePx,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            percentBasePx = parentSize,
        )
        fontSizePx = length.resolve(context).coerceAtLeast(1f)
    }

    private fun applyFontWeight(value: String) {
        when (val weight = ValueParser.parseFontWeight(value)) {
            null -> Unit
            is org.libepub.core.css.FontWeight.Numeric -> fontWeight = weight.value
            org.libepub.core.css.FontWeight.Bolder -> fontWeight = (fontWeight + 300).coerceAtMost(900)
            org.libepub.core.css.FontWeight.Lighter -> fontWeight = (fontWeight - 300).coerceAtLeast(100)
        }
    }

    private fun applyLineHeight(value: String) {
        lineHeight = when (val parsed = ValueParser.parseLineHeight(value) ?: return) {
            LineHeight.Normal -> LineHeight.Normal
            is LineHeight.Number -> parsed
            is LineHeight.OfLength -> {
                val length = parsed.length
                val px = if (length.unit == LengthUnit.PERCENT) {
                    fontSizePx * length.value / 100f
                } else {
                    length.resolve(context())
                }
                LineHeight.OfLength(Length(px, LengthUnit.PX))
            }
        }
    }

    private fun applyTextDecoration(value: String) {
        val parsed = ValueParser.parseTextDecoration(value) ?: return
        // `text-decoration: none` removes decorations propagated from ancestors.
        if (parsed.lines.isEmpty()) {
            textDecoration = null
            return
        }
        val inherited = textDecoration?.lines ?: emptySet()
        val lines = LinkedHashSet(inherited)
        lines.addAll(parsed.lines)
        textDecoration = TextDecoration(lines, parsed.style, parsed.color ?: textDecoration?.color)
    }

    private fun applyTextDecorationLines(value: String) {
        val parsed = ValueParser.parseTextDecoration(value) ?: return
        if (parsed.lines.isEmpty()) {
            textDecoration = null
            return
        }
        val inherited = textDecoration?.lines ?: emptySet()
        val lines = LinkedHashSet(inherited)
        lines.addAll(parsed.lines)
        textDecoration = TextDecoration(lines, textDecoration?.style, textDecoration?.color)
    }

    private fun applyEmphasisStyle(value: String) {
        val parsed = ValueParser.parseTextEmphasis(value) ?: return
        emphasisMark = parsed.mark
        emphasisFilled = parsed.filled
    }

    private fun applyTextShadow(value: String) {
        val parsed = ValueParser.parseTextShadow(value) ?: return
        textShadow = parsed.map { shadow ->
            shadow.copy(
                x = resolveToPx(shadow.x),
                y = resolveToPx(shadow.y),
                blur = resolveToPx(shadow.blur),
            )
        }
    }

    private fun spacingPx(value: String): Float = when (val spacing = ValueParser.parseSpacing(value)) {
        null -> 0f
        Spacing.Normal -> 0f
        is Spacing.Fixed -> if (spacing.length.unit == LengthUnit.PERCENT) 0f else resolveToPx(spacing.length).value
    }

    private fun applyVerticalAlign(value: String) {
        val parsed = ValueParser.parseVerticalAlign(value) ?: return
        verticalAlign = when (parsed) {
            is VerticalAlign.Keyword -> parsed
            is VerticalAlign.OfLength -> VerticalAlign.OfLength(resolveToPx(parsed.length))
        }
    }

    private fun applyTransform(value: String) {
        val parsed = ValueParser.parseTransform(value) ?: return
        transform = parsed.map { fn ->
            if (fn is TransformFn.Translate) {
                TransformFn.Translate(resolveToPx(fn.x), resolveToPx(fn.y))
            } else {
                fn
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private fun context(percentBase: Float? = null) = LengthContext(
        fontSizePx = fontSizePx,
        rootFontSizePx = rootFontSizePx,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        percentBasePx = percentBase,
    )

    /** Resolves em/rem/pt/vw/vh to px; percentages stay symbolic. */
    private fun resolveToPx(length: Length): Length =
        if (length.unit == LengthUnit.PERCENT) {
            length
        } else {
            Length(length.resolve(context()), LengthUnit.PX)
        }

    private fun parseDimension(value: String): Dimension? {
        val text = value.trim()
        if (text.equals("auto", ignoreCase = true)) return Dimension.Auto
        return ValueParser.parseLength(text)?.let { Dimension.Of(resolveToPx(it)) }
    }

    private fun parseMaxDimension(value: String): Dimension? {
        val text = value.trim()
        if (text.equals("none", ignoreCase = true) || text.equals("auto", ignoreCase = true)) return null
        return ValueParser.parseLength(text)?.let { Dimension.Of(resolveToPx(it)) }
    }

    private fun parsePadding(value: String): Length? =
        ValueParser.parseLength(value)?.let { resolveToPx(it) }

    private fun parseBorderWidth(value: String): Length? {
        val text = value.trim().lowercase()
        val keyword = when (text) {
            "thin" -> Length(1f, LengthUnit.PX)
            "medium" -> Length(3f, LengthUnit.PX)
            "thick" -> Length(5f, LengthUnit.PX)
            else -> null
        }
        if (keyword != null) return keyword
        val length = ValueParser.parseLength(text) ?: return null
        if (length.unit == LengthUnit.PERCENT) return null
        return resolveToPx(length)
    }

    private fun parseBorderStyle(value: String): BorderStyle? =
        BORDER_STYLES[value.trim().lowercase()]

    private fun sideOf(property: String): Side = when {
        property.contains("-top-") -> Side.TOP
        property.contains("-right-") -> Side.RIGHT
        property.contains("-bottom-") -> Side.BOTTOM
        else -> Side.LEFT
    }

    private fun setBorderWidth(side: Side, width: Length) {
        when (side) {
            Side.TOP -> borderTop = borderTop.copy(width = width)
            Side.RIGHT -> borderRight = borderRight.copy(width = width)
            Side.BOTTOM -> borderBottom = borderBottom.copy(width = width)
            Side.LEFT -> borderLeft = borderLeft.copy(width = width)
        }
    }

    private fun setBorderStyle(side: Side, style: BorderStyle) {
        when (side) {
            Side.TOP -> borderTop = borderTop.copy(style = style)
            Side.RIGHT -> borderRight = borderRight.copy(style = style)
            Side.BOTTOM -> borderBottom = borderBottom.copy(style = style)
            Side.LEFT -> borderLeft = borderLeft.copy(style = style)
        }
    }

    private fun setBorderColor(side: Side, value: CssColor) {
        when (side) {
            Side.TOP -> borderTop = borderTop.copy(color = value)
            Side.RIGHT -> borderRight = borderRight.copy(color = value)
            Side.BOTTOM -> borderBottom = borderBottom.copy(color = value)
            Side.LEFT -> borderLeft = borderLeft.copy(color = value)
        }
    }

    // ----------------------------------------------------------------- build

    fun build(): ComputedStyle {
        val emphasis = buildEmphasis()
        val shadows = textShadow.map { shadow ->
            val shadowColor = shadow.color
            shadow.copy(
                color = when {
                    shadowColor == null || shadowColor == CssColor.Current -> color
                    else -> shadowColor
                },
            )
        }
        val border = BorderSides(
            resolveBorderSide(borderTop),
            resolveBorderSide(borderRight),
            resolveBorderSide(borderBottom),
            resolveBorderSide(borderLeft),
        )
        return ComputedStyle(
            display = display,
            float = float,
            writingMode = writingMode,
            fontFamily = fontFamily,
            fontSizePx = fontSizePx,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            lineHeight = lineHeight,
            color = color,
            textAlign = textAlign,
            textIndent = textIndent,
            textDecoration = textDecoration,
            textEmphasis = emphasis,
            textShadow = shadows,
            letterSpacingPx = letterSpacingPx,
            wordSpacingPx = wordSpacingPx,
            whiteSpace = whiteSpace,
            wordWrap = wordWrap,
            wordBreak = wordBreak,
            verticalAlign = verticalAlign,
            textOrientation = textOrientation,
            margin = margin,
            padding = padding,
            border = border,
            borderCollapse = borderCollapse,
            borderSpacingPx = borderSpacingPx,
            boxSizing = boxSizing,
            width = width,
            height = height,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            background = org.libepub.core.css.Background(
                color = backgroundColor,
                imageUrl = backgroundImage,
                repeat = backgroundRepeat,
                position = backgroundPosition,
                size = backgroundSize,
            ),
            listStyleType = listStyleType,
            listStylePosition = listStylePosition,
            pageBreakBefore = pageBreakBefore,
            pageBreakAfter = pageBreakAfter,
            transform = transform,
            opacity = opacity,
        )
    }

    private fun buildEmphasis(): TextEmphasis? {
        val mark = emphasisMark
        if (mark == "none") return null
        if (mark == null && emphasisFilled == null && emphasisColor == null) return null
        val resolvedColor = when (val value = emphasisColor) {
            null, CssColor.Current -> color
            else -> value
        }
        return TextEmphasis(mark, emphasisFilled ?: true, resolvedColor)
    }

    private fun resolveBorderSide(side: BorderSide): BorderSide {
        val resolved = when (val value = side.color) {
            null, CssColor.Current -> color
            else -> value
        }
        return side.copy(color = resolved)
    }

    companion object {
        private val BORDER_STYLES = mapOf(
            "none" to BorderStyle.NONE, "hidden" to BorderStyle.HIDDEN,
            "dotted" to BorderStyle.DOTTED, "dashed" to BorderStyle.DASHED,
            "solid" to BorderStyle.SOLID, "double" to BorderStyle.DOUBLE,
            "groove" to BorderStyle.GROOVE, "ridge" to BorderStyle.RIDGE,
            "inset" to BorderStyle.INSET, "outset" to BorderStyle.OUTSET,
        )

        private val APPLY_ORDER = listOf(
            "font-size", "font-family", "font-weight", "font-style", "font-variant",
            "line-height", "color", "display", "float", "writing-mode",
            "text-align", "text-indent", "text-decoration", "text-decoration-line",
            "text-decoration-color", "text-decoration-style",
            "text-emphasis-style", "text-emphasis-color",
            "text-shadow", "letter-spacing", "word-spacing", "white-space",
            "word-wrap", "word-break", "vertical-align", "text-orientation",
            "margin-top", "margin-right", "margin-bottom", "margin-left",
            "padding-top", "padding-right", "padding-bottom", "padding-left",
            "border-top-width", "border-right-width", "border-bottom-width", "border-left-width",
            "border-top-style", "border-right-style", "border-bottom-style", "border-left-style",
            "border-top-color", "border-right-color", "border-bottom-color", "border-left-color",
            "border-collapse", "border-spacing",
            "background-color", "background-image", "background-repeat",
            "background-position", "background-size", "background-attachment",
            "width", "height", "max-width", "max-height", "box-sizing",
            "list-style-type", "list-style-position",
            "page-break-before", "page-break-after", "break-before", "break-after",
            "transform", "opacity",
        )

        private val HANDLED: Set<String> = APPLY_ORDER.toHashSet()
    }
}
