package org.libepub.core.css

/**
 * Shorthand expansion: box edges, border sides and background.
 * All functions return null for values they cannot understand.
 */
object Shorthand {

    // ------------------------------------------------------------------ edges

    /** `margin: 4em 0 0 0` -> four edges (auto allowed). */
    fun parseMargin(value: String): Edges<Dimension>? {
        val tokens = ValueParser.tokenize(value)
        if (tokens.isEmpty() || tokens.size > 4) return null
        val dims = tokens.map { token ->
            if (token.equals("auto", ignoreCase = true)) {
                Dimension.Auto
            } else {
                val length = ValueParser.parseLength(token) ?: return null
                Dimension.Of(length)
            }
        }
        return expand(dims) ?: return null
    }

    /** `padding: 0.5em 0 1em 0` -> four edges (auto is invalid here). */
    fun parsePadding(value: String): Edges<Length>? {
        val tokens = ValueParser.tokenize(value)
        if (tokens.isEmpty() || tokens.size > 4) return null
        val lengths = tokens.map { ValueParser.parseLength(it) ?: return null }
        return expand(lengths)
    }

    private fun <T> expand(values: List<T>): Edges<T>? = when (values.size) {
        1 -> Edges.all(values[0])
        2 -> Edges(values[0], values[1], values[0], values[1])
        3 -> Edges(values[0], values[1], values[2], values[1])
        4 -> Edges(values[0], values[1], values[2], values[3])
        else -> null
    }

    // ----------------------------------------------------------------- border

    private val BORDER_STYLES = mapOf(
        "none" to BorderStyle.NONE, "hidden" to BorderStyle.HIDDEN, "dotted" to BorderStyle.DOTTED,
        "dashed" to BorderStyle.DASHED, "solid" to BorderStyle.SOLID, "double" to BorderStyle.DOUBLE,
        "groove" to BorderStyle.GROOVE, "ridge" to BorderStyle.RIDGE, "inset" to BorderStyle.INSET,
        "outset" to BorderStyle.OUTSET,
    )

    private val BORDER_WIDTH_KEYWORDS = mapOf(
        "thin" to Length(1f, LengthUnit.PX),
        "medium" to Length(3f, LengthUnit.PX),
        "thick" to Length(5f, LengthUnit.PX),
    )

    /** One side of a border from any ordering of width/style/color tokens. */
    fun parseBorderSide(value: String): BorderSide? {
        val tokens = ValueParser.tokenize(value)
        if (tokens.isEmpty()) return null
        var width: Length? = null
        var style: BorderStyle? = null
        var color: CssColor? = null
        var any = false
        for (token in tokens) {
            val lower = token.lowercase()
            when {
                style == null && BORDER_STYLES.containsKey(lower) -> {
                    style = BORDER_STYLES[lower]
                    any = true
                }
                width == null && BORDER_WIDTH_KEYWORDS.containsKey(lower) -> {
                    width = BORDER_WIDTH_KEYWORDS[lower]
                    any = true
                }
                width == null && ValueParser.parseLength(token) != null -> {
                    width = ValueParser.parseLength(token)
                    any = true
                }
                color == null && ValueParser.parseColor(token) != null -> {
                    color = ValueParser.parseColor(token)
                    any = true
                }
            }
        }
        return if (any) BorderSide(width, style, color) else null
    }

    /** `border: 1px solid #000` -> all four sides. */
    fun parseBorder(value: String): BorderSides? {
        val side = parseBorderSide(value) ?: return null
        return BorderSides.all(side)
    }

    /** `border-width: 1px 2px` -> per-side widths. */
    fun parseBorderWidths(value: String): Edges<Length>? {
        val tokens = ValueParser.tokenize(value)
        if (tokens.isEmpty() || tokens.size > 4) return null
        val widths = tokens.map { token ->
            BORDER_WIDTH_KEYWORDS[token.lowercase()] ?: ValueParser.parseLength(token) ?: return null
        }
        return expand(widths)
    }

    /** `border-style: none` -> per-side styles. */
    fun parseBorderStyles(value: String): Edges<BorderStyle>? {
        val tokens = ValueParser.tokenize(value)
        if (tokens.isEmpty() || tokens.size > 4) return null
        val styles = tokens.map { BORDER_STYLES[it.lowercase()] ?: return null }
        return expand(styles)
    }

    /** `border-color: #716F71` -> per-side colors. */
    fun parseBorderColors(value: String): Edges<CssColor>? {
        val tokens = ValueParser.tokenize(value)
        if (tokens.isEmpty() || tokens.size > 4) return null
        val colors = tokens.map { ValueParser.parseColor(it) ?: return null }
        return expand(colors)
    }

    // ------------------------------------------------------------- background

    private val REPEATS = setOf("repeat", "repeat-x", "repeat-y", "no-repeat", "space", "round")
    private val ATTACHMENTS = setOf("scroll", "fixed", "local")
    private val BOXES = setOf("border-box", "padding-box", "content-box")
    private val POSITION_KEYWORDS = setOf("left", "right", "center", "top", "bottom")
    private val SIZE_KEYWORDS = setOf("cover", "contain")

    /**
     * `background: url(x.jpg) no-repeat center / cover #fff`.
     * Longhands (`background-image`, `-repeat`, `-position`, `-size`) are parsed
     * by their own dedicated functions in the cascade.
     */
    fun parseBackground(value: String): Background? {
        val tokens = ValueParser.tokenize(value)
        if (tokens.isEmpty()) return null
        var color: CssColor? = null
        var image: String? = null
        var repeat: String? = null
        var attachment: String? = null
        var position: String? = null
        var size: String? = null
        var afterSlash = false
        var any = false

        for (token in tokens) {
            val lower = token.lowercase()
            if (token == "/") {
                afterSlash = true
                continue
            }
            when {
                image == null && lower.startsWith("url(") -> {
                    image = ValueParser.parseUrl(token)
                    any = true
                }
                repeat == null && lower in REPEATS -> {
                    repeat = lower
                    any = true
                }
                attachment == null && lower in ATTACHMENTS -> {
                    attachment = lower
                    any = true
                }
                afterSlash && size == null && (lower in SIZE_KEYWORDS || ValueParser.parseLength(token) != null) -> {
                    size = lower
                    any = true
                }
                position == null && (lower in POSITION_KEYWORDS || ValueParser.parseLength(token) != null) -> {
                    position = if (position == null) lower else "$position $lower"
                    any = true
                }
                lower in BOXES -> any = true // origin/clip: recorded via longhands only
                color == null && ValueParser.parseColor(token) != null -> {
                    color = ValueParser.parseColor(token)
                    any = true
                }
            }
        }
        return if (any) Background(color, image, repeat, position, size, attachment) else null
    }
}
