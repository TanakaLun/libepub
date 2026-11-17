package org.libepub.core.style

import org.libepub.core.css.BorderStyle
import org.libepub.core.css.CssColor
import org.libepub.core.css.Length
import org.libepub.core.css.LengthUnit
import org.libepub.core.css.Shorthand
import org.libepub.core.css.ValueParser

/**
 * Expands shorthand declarations into longhands *before* the cascade merges
 * them, so `border: 1px solid` followed by `border-top: 2px dashed` and then
 * `border-top-color: red` composes exactly like CSS: each longhand has one
 * winning value.
 *
 * Returns null when the value is invalid; the whole declaration is then
 * dropped (CSS error handling), leaving earlier longhands untouched.
 */
internal object DeclarationExpander {

    private val SIDES = listOf("top", "right", "bottom", "left")

    fun expand(property: String, value: String): List<Pair<String, String>>? = when (property) {
        "margin" -> Shorthand.parseMargin(value)?.let {
            listOf(
                "margin-top" to dimensionToCss(it.top),
                "margin-right" to dimensionToCss(it.right),
                "margin-bottom" to dimensionToCss(it.bottom),
                "margin-left" to dimensionToCss(it.left),
            )
        }
        "padding" -> Shorthand.parsePadding(value)?.let {
            listOf(
                "padding-top" to lengthToCss(it.top),
                "padding-right" to lengthToCss(it.right),
                "padding-bottom" to lengthToCss(it.bottom),
                "padding-left" to lengthToCss(it.left),
            )
        }
        "border" -> Shorthand.parseBorder(value)?.let { sides ->
            SIDES.flatMap { side ->
                val parsed = when (side) {
                    "top" -> sides.top
                    "right" -> sides.right
                    "bottom" -> sides.bottom
                    else -> sides.left
                }
                sideDeclarations(side, parsed)
            }
        }
        "border-top", "border-right", "border-bottom", "border-left" -> {
            val side = property.removePrefix("border-")
            Shorthand.parseBorderSide(value)?.let { parsed -> sideDeclarations(side, parsed) }
        }
        "border-width" -> Shorthand.parseBorderWidths(value)?.let {
            listOf(
                "border-top-width" to lengthToCss(it.top),
                "border-right-width" to lengthToCss(it.right),
                "border-bottom-width" to lengthToCss(it.bottom),
                "border-left-width" to lengthToCss(it.left),
            )
        }
        "border-style" -> Shorthand.parseBorderStyles(value)?.let {
            listOf(
                "border-top-style" to styleToCss(it.top),
                "border-right-style" to styleToCss(it.right),
                "border-bottom-style" to styleToCss(it.bottom),
                "border-left-style" to styleToCss(it.left),
            )
        }
        "border-color" -> Shorthand.parseBorderColors(value)?.let {
            listOf(
                "border-top-color" to colorToCss(it.top),
                "border-right-color" to colorToCss(it.right),
                "border-bottom-color" to colorToCss(it.bottom),
                "border-left-color" to colorToCss(it.left),
            )
        }
        "background" -> expandBackground(value)
        "font" -> expandFont(value)
        "text-emphasis" -> expandTextEmphasis(value)
        "list-style" -> expandListStyle(value)
        else -> listOf(property to value)
    }

    // ---------------------------------------------------------------- helpers

    private fun sideDeclarations(side: String, parsed: org.libepub.core.css.BorderSide): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(3)
        parsed.width?.let { out.add("border-$side-width" to lengthToCss(it)) }
        parsed.style?.let { out.add("border-$side-style" to styleToCss(it)) }
        parsed.color?.let { out.add("border-$side-color" to colorToCss(it)) }
        return out
    }

    private fun expandBackground(value: String): List<Pair<String, String>>? {
        val background = Shorthand.parseBackground(value) ?: return null
        return listOf(
            "background-color" to (background.color?.let { colorToCss(it) } ?: "transparent"),
            "background-image" to (background.imageUrl?.let { "url($it)" } ?: "none"),
            "background-repeat" to (background.repeat ?: "repeat"),
            "background-position" to (background.position ?: "0% 0%"),
            "background-size" to (background.size ?: "auto"),
            "background-attachment" to (background.attachment ?: "scroll"),
        )
    }

    private fun expandFont(value: String): List<Pair<String, String>>? {
        val tokens = ValueParser.tokenize(value)
        if (tokens.isEmpty()) return null
        var index = 0
        var style: String? = null
        var weight: String? = null
        var variant: String? = null
        var size: String? = null
        var lineHeight: String? = null
        while (index < tokens.size) {
            val token = tokens[index].lowercase()
            when {
                token == "italic" || token == "oblique" -> {
                    style = token
                    index++
                }
                token == "normal" -> index++ // could be style/weight/variant; all are initial
                token == "small-caps" -> {
                    variant = token
                    index++
                }
                ValueParser.parseFontWeight(token) != null -> {
                    weight = token
                    index++
                }
                ValueParser.parseFontSize(token) != null -> {
                    size = token
                    index++
                    if (tokens.getOrNull(index) == "/") {
                        lineHeight = tokens.getOrNull(index + 1)
                        index += 2
                    }
                    break
                }
                else -> break
            }
        }
        if (size == null) return null
        val family = tokens.drop(index).joinToString(" ")
        if (family.isEmpty()) return null
        val out = ArrayList<Pair<String, String>>(6)
        out.add("font-style" to (style ?: "normal"))
        out.add("font-variant" to (variant ?: "normal"))
        out.add("font-weight" to (weight ?: "normal"))
        out.add("font-size" to size)
        out.add("line-height" to (lineHeight ?: "normal"))
        out.add("font-family" to family)
        return out
    }

    private fun expandTextEmphasis(value: String): List<Pair<String, String>>? {
        val emphasis = ValueParser.parseTextEmphasis(value) ?: return null
        val out = ArrayList<Pair<String, String>>(2)
        val mark = emphasis.mark
        if (mark != null) {
            out.add(
                "text-emphasis-style" to if (mark == "none") {
                    "none"
                } else {
                    "${if (emphasis.filled == false) "open" else "filled"} $mark"
                },
            )
        }
        emphasis.color?.let { out.add("text-emphasis-color" to colorToCss(it)) }
        return out.ifEmpty { null }
    }

    private val LIST_TYPES = setOf(
        "disc", "circle", "square", "decimal", "decimal-leading-zero", "lower-roman",
        "upper-roman", "lower-greek", "lower-latin", "upper-latin", "lower-alpha",
        "upper-alpha", "armenian", "georgian", "none",
    )

    private fun expandListStyle(value: String): List<Pair<String, String>>? {
        val tokens = ValueParser.tokenize(value)
        if (tokens.isEmpty()) return null
        var type: String? = null
        var position: String? = null
        var image: String? = null
        for (token in tokens) {
            val lower = token.lowercase()
            when {
                image == null && lower.startsWith("url(") -> image = ValueParser.parseUrl(token)
                type == null && lower in LIST_TYPES -> type = lower
                position == null && (lower == "inside" || lower == "outside") -> position = lower
            }
        }
        val out = ArrayList<Pair<String, String>>(3)
        type?.let { out.add("list-style-type" to it) }
        position?.let { out.add("list-style-position" to it) }
        image?.let { out.add("list-style-image" to "url($it)") }
        return out.ifEmpty { null }
    }

    // ------------------------------------------------------------- serializers

    fun lengthToCss(length: Length): String = when (length.unit) {
        LengthUnit.PX -> "${trim(length.value)}px"
        LengthUnit.PERCENT -> "${trim(length.value)}%"
        else -> "${trim(length.value)}${length.unit.name.lowercase()}"
    }

    private fun dimensionToCss(dimension: org.libepub.core.css.Dimension): String = when (dimension) {
        org.libepub.core.css.Dimension.Auto -> "auto"
        is org.libepub.core.css.Dimension.Of -> lengthToCss(dimension.length)
    }

    private fun styleToCss(style: BorderStyle): String = style.name.lowercase()

    fun colorToCss(color: CssColor): String = when (color) {
        CssColor.Current -> "currentColor"
        is CssColor.Rgba -> {
            val alpha = ((color.argb ushr 24) and 0xFF).toInt()
            val red = ((color.argb ushr 16) and 0xFF).toInt()
            val green = ((color.argb ushr 8) and 0xFF).toInt()
            val blue = (color.argb and 0xFF).toInt()
            if (alpha == 255) {
                "#%02x%02x%02x".format(red, green, blue)
            } else {
                "rgba($red, $green, $blue, ${trim(alpha / 255f)})"
            }
        }
    }

    private fun trim(value: Float): String {
        if (value == value.toInt().toFloat()) return value.toInt().toString()
        return value.toString()
    }
}
