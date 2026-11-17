package org.libepub.core.css

/**
 * Typed CSS value parsers. Every parser returns null for values it does not
 * understand — invalid CSS must degrade, never throw.
 */
object ValueParser {

    // ------------------------------------------------------------ tokenizing

    /** Splits a value into tokens: whitespace-separated, parens/strings kept whole, `/` separate. */
    fun tokenize(value: String): List<String> {
        val out = ArrayList<String>(4)
        var i = 0
        val n = value.length
        while (i < n) {
            while (i < n && value[i].isWhitespace()) i++
            if (i >= n) break
            if (value[i] == '/') {
                out.add("/")
                i++
                continue
            }
            val start = i
            var depth = 0
            var quote = ' '
            while (i < n) {
                val c = value[i]
                if (quote != ' ') {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == quote) quote = ' '
                    i++
                    continue
                }
                when {
                    c == '"' || c == '\'' -> {
                        quote = c
                        i++
                    }
                    c == '(' -> {
                        depth++
                        i++
                    }
                    c == ')' -> {
                        if (depth > 0) depth--
                        i++
                    }
                    depth == 0 && c.isWhitespace() -> break
                    depth == 0 && c == '/' -> break
                    else -> i++
                }
            }
            out.add(value.substring(start, i))
        }
        return out
    }

    // --------------------------------------------------------------- lengths

    private val LENGTH = Regex(
        """^([+-]?(?:\d+\.?\d*|\.\d+))(px|em|rem|ex|ch|vw|vh|vmin|vmax|pt|pc|in|cm|mm|%)$""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMBER = Regex("""^[+-]?(?:\d+\.?\d*|\.\d+)$""")

    fun parseNumber(token: String): Float? = token.trim().toFloatOrNull()?.takeIf { NUMBER.matches(token.trim()) }

    fun parseLength(token: String): Length? {
        val text = token.trim()
        if (text.isEmpty()) return null
        if (text == "0" || text == "+0" || text == "-0") return Length(0f, LengthUnit.PX)
        val match = LENGTH.matchEntire(text) ?: return null
        val unit = when (match.groupValues[2].lowercase()) {
            "px" -> LengthUnit.PX
            "em" -> LengthUnit.EM
            "rem" -> LengthUnit.REM
            "ex" -> LengthUnit.EX
            "ch" -> LengthUnit.CH
            "vw" -> LengthUnit.VW
            "vh" -> LengthUnit.VH
            "vmin" -> LengthUnit.VMIN
            "vmax" -> LengthUnit.VMAX
            "pt" -> LengthUnit.PT
            "pc" -> LengthUnit.PC
            "in" -> LengthUnit.IN
            "cm" -> LengthUnit.CM
            "mm" -> LengthUnit.MM
            else -> LengthUnit.PERCENT
        }
        val number = match.groupValues[1].toFloatOrNull() ?: return null
        return Length(number, unit)
    }

    // ---------------------------------------------------------------- colors

    private val HEX = Regex("""^#([0-9a-fA-F]{3,8})$""")

    fun parseColor(token: String): CssColor? {
        val text = token.trim().lowercase()
        if (text.isEmpty()) return null
        when (text) {
            "transparent" -> return CssColor.TRANSPARENT
            "currentcolor" -> return CssColor.Current
        }
        HEX.matchEntire(text)?.let { match ->
            val hex = match.groupValues[1]
            return when (hex.length) {
                3 -> CssColor.rgb(
                    hex[0].digit() * 17,
                    hex[1].digit() * 17,
                    hex[2].digit() * 17,
                )
                4 -> CssColor.rgb(
                    hex[0].digit() * 17,
                    hex[1].digit() * 17,
                    hex[2].digit() * 17,
                    hex[3].digit() * 17,
                )
                6 -> CssColor.rgb(
                    hex.substring(0, 2).toInt(16),
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
                )
                8 -> CssColor.rgb(
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
                    hex.substring(6, 8).toInt(16),
                    hex.substring(0, 2).toInt(16),
                )
                else -> null
            }
        }
        if (text.startsWith("rgb")) return parseRgbFunction(text)
        NAMED_COLORS[text]?.let { return CssColor.Rgba(0xFF000000L or it.toLong()) }
        return null
    }

    private fun parseRgbFunction(text: String): CssColor? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val parts = text.substring(open + 1, close).split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        fun channel(raw: String): Int? = if (raw.endsWith("%")) {
            (raw.dropLast(1).toFloatOrNull()?.let { (it * 255f / 100f).toInt() })
        } else {
            raw.toFloatOrNull()?.toInt()
        }
        val red = channel(parts[0]) ?: return null
        val green = channel(parts[1]) ?: return null
        val blue = channel(parts[2]) ?: return null
        val alpha = parts.getOrNull(3)?.let { raw ->
            val value = if (raw.endsWith("%")) {
                raw.dropLast(1).toFloatOrNull()?.div(100f)
            } else {
                raw.toFloatOrNull()
            }
            value?.let { (it.coerceIn(0f, 1f) * 255f).toInt() }
        } ?: 255
        return CssColor.rgb(red, green, blue, alpha)
    }

    private fun Char.digit(): Int = Character.digit(this, 16)

    // ------------------------------------------------------------- font-size

    private val ABSOLUTE_FONT_SIZES = mapOf(
        "xx-small" to 9f, "x-small" to 10f, "small" to 13f, "medium" to 16f,
        "large" to 18f, "x-large" to 24f, "xx-large" to 32f, "xxx-large" to 48f,
    )

    fun parseFontSize(value: String): Length? {
        val text = value.trim().lowercase()
        parseLength(text)?.let { return it }
        ABSOLUTE_FONT_SIZES[text]?.let { return Length(it, LengthUnit.PX) }
        return when (text) {
            "larger" -> Length(1.2f, LengthUnit.EM)
            "smaller" -> Length(1f / 1.2f, LengthUnit.EM)
            else -> null
        }
    }

    // ------------------------------------------------------------ line-height

    fun parseLineHeight(value: String): LineHeight? {
        val text = value.trim()
        if (text.equals("normal", ignoreCase = true)) return LineHeight.Normal
        parseNumber(text)?.let { return LineHeight.Number(it) }
        parseLength(text)?.let { return LineHeight.OfLength(it) }
        return null
    }

    // ---------------------------------------------------------------- colors

    fun parseTextEmphasis(value: String): TextEmphasis? {
        var mark: String? = null
        var filled: Boolean? = null
        var color: CssColor? = null
        var any = false
        for (token in tokenize(value)) {
            val lower = token.lowercase()
            when {
                lower == "filled" || lower == "open" -> {
                    filled = lower == "filled"
                    any = true
                }
                mark == null && lower in EMPHASIS_MARKS -> {
                    mark = lower
                    any = true
                }
                color == null && parseColor(token) != null -> {
                    color = parseColor(token)
                    any = true
                }
            }
        }
        // CSS initial fill is `filled`; a bare mark (`circle`) therefore means filled.
        if (mark != null && filled == null) filled = true
        return if (any) TextEmphasis(mark, filled, color) else null
    }

    // -------------------------------------------------------------- transform

    private val TRANSFORM_FN = Regex("""([a-zA-Z]+)\(([^)]*)\)""")

    fun parseTransform(value: String): List<TransformFn>? {
        val out = ArrayList<TransformFn>(2)
        for (match in TRANSFORM_FN.findAll(value)) {
            val name = match.groupValues[1].lowercase()
            val args = match.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            when (name) {
                "rotate" -> parseAngle(args.getOrNull(0))?.let { out.add(TransformFn.Rotate(it)) }
                "scale" -> {
                    val x = parseNumber(args.getOrNull(0) ?: return null) ?: return null
                    val y = args.getOrNull(1)?.let { parseNumber(it) } ?: x
                    out.add(TransformFn.Scale(x, y))
                }
                "scalex" -> parseNumber(args.getOrNull(0) ?: return null)?.let { out.add(TransformFn.Scale(it, 1f)) }
                "scaley" -> parseNumber(args.getOrNull(0) ?: return null)?.let { out.add(TransformFn.Scale(1f, it)) }
                "translate" -> {
                    val x = parseLength(args.getOrNull(0) ?: return null) ?: return null
                    val y = args.getOrNull(1)?.let { parseLength(it) } ?: Length(0f, LengthUnit.PX)
                    out.add(TransformFn.Translate(x, y))
                }
                "translatex" -> parseLength(args.getOrNull(0) ?: return null)
                    ?.let { out.add(TransformFn.Translate(it, Length(0f, LengthUnit.PX))) }
                "translatey" -> parseLength(args.getOrNull(0) ?: return null)
                    ?.let { out.add(TransformFn.Translate(Length(0f, LengthUnit.PX), it)) }
                "skew" -> {
                    val x = parseAngle(args.getOrNull(0) ?: return null) ?: return null
                    val y = args.getOrNull(1)?.let { parseAngle(it) } ?: 0f
                    out.add(TransformFn.Skew(x, y))
                }
                "skewx" -> parseAngle(args.getOrNull(0) ?: return null)?.let { out.add(TransformFn.Skew(it, 0f)) }
                "skewy" -> parseAngle(args.getOrNull(0) ?: return null)?.let { out.add(TransformFn.Skew(0f, it)) }
                else -> Unit // unknown function: ignored
            }
        }
        return out.ifEmpty { null }
    }

    private fun parseAngle(token: String?): Float? {
        if (token == null) return null
        val text = token.trim().lowercase()
        val match = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+))(deg|rad|grad|turn)$""").matchEntire(text)
            ?: return parseNumber(text)
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        return when (match.groupValues[2]) {
            "deg" -> value
            "rad" -> value * 180f / Math.PI.toFloat()
            "grad" -> value * 0.9f
            else -> value * 360f
        }
    }

    // ------------------------------------------------------------- longhands

    fun parseTextAlign(value: String): TextAlign? = when (value.trim().lowercase()) {
        "left" -> TextAlign.LEFT
        "right" -> TextAlign.RIGHT
        "center" -> TextAlign.CENTER
        "justify" -> TextAlign.JUSTIFY
        "start" -> TextAlign.START
        "end" -> TextAlign.END
        else -> null
    }

    fun parseWritingMode(value: String): WritingMode? = when (value.trim().lowercase()) {
        "horizontal-tb" -> WritingMode.HORIZONTAL_TB
        "vertical-rl", "tb-rl" -> WritingMode.VERTICAL_RL
        "vertical-lr" -> WritingMode.VERTICAL_LR
        else -> null
    }

    fun parseWhiteSpace(value: String): WhiteSpace? = when (value.trim().lowercase()) {
        "normal" -> WhiteSpace.NORMAL
        "nowrap" -> WhiteSpace.NOWRAP
        "pre" -> WhiteSpace.PRE
        "pre-wrap" -> WhiteSpace.PRE_WRAP
        "pre-line" -> WhiteSpace.PRE_LINE
        "break-spaces" -> WhiteSpace.BREAK_SPACES
        else -> null
    }

    fun parseDisplay(value: String): Display? = when (value.trim().lowercase()) {
        "block" -> Display.BLOCK
        "inline" -> Display.INLINE
        "inline-block" -> Display.INLINE_BLOCK
        "none" -> Display.NONE
        "list-item" -> Display.LIST_ITEM
        "table" -> Display.TABLE
        "table-row" -> Display.TABLE_ROW
        "table-cell" -> Display.TABLE_CELL
        "flex" -> Display.FLEX
        "ruby" -> Display.RUBY
        "ruby-text" -> Display.RUBY_TEXT
        "ruby-base" -> Display.RUBY_BASE
        else -> null
    }

    fun parseBoxSizing(value: String): BoxSizing? = when (value.trim().lowercase()) {
        "border-box" -> BoxSizing.BORDER_BOX
        "content-box" -> BoxSizing.CONTENT_BOX
        else -> null
    }

    fun parseSpacing(value: String): Spacing? {
        val text = value.trim()
        if (text.equals("normal", ignoreCase = true)) return Spacing.Normal
        return parseLength(text)?.let { Spacing.Fixed(it) }
    }

    fun parseFontWeight(value: String): FontWeight? {
        val text = value.trim().lowercase()
        if (text == "normal") return FontWeight.Numeric(400)
        if (text == "bold") return FontWeight.Numeric(700)
        if (text == "bolder") return FontWeight.Bolder
        if (text == "lighter") return FontWeight.Lighter
        val numeric = text.toIntOrNull() ?: return null
        if (numeric % 100 != 0 || numeric !in 100..900) return null
        return FontWeight.Numeric(numeric)
    }

    fun parseVerticalAlign(value: String): VerticalAlign? {
        val text = value.trim().lowercase()
        if (text in VERTICAL_ALIGN_KEYWORDS) return VerticalAlign.Keyword(text)
        return parseLength(text)?.let { VerticalAlign.OfLength(it) }
    }

    fun parseTextOrientation(value: String): TextOrientation? = when (value.trim().lowercase()) {
        "mixed" -> TextOrientation.MIXED
        "upright" -> TextOrientation.UPRIGHT
        "sideways", "sideways-right", "sideways-left", "use-glyph-orientation" -> TextOrientation.SIDEWAYS
        else -> null
    }

    fun parseTextDecoration(value: String): TextDecoration? {
        val lines = LinkedHashSet<TextDecoration.Line>(2)
        var style: String? = null
        var color: CssColor? = null
        for (token in tokenize(value)) {
            when (val lower = token.lowercase()) {
                "underline" -> lines.add(TextDecoration.Line.UNDERLINE)
                "overline" -> lines.add(TextDecoration.Line.OVERLINE)
                "line-through" -> lines.add(TextDecoration.Line.LINE_THROUGH)
                "none" -> Unit
                "solid", "double", "dotted", "dashed", "wavy" -> style = lower
                else -> if (color == null) parseColor(token)?.let { color = it }
            }
        }
        if (lines.isEmpty() && style == null && color == null) {
            return if (value.trim().equals("none", ignoreCase = true)) {
                TextDecoration(emptySet())
            } else {
                null
            }
        }
        return TextDecoration(lines, style, color)
    }

    fun parseUrl(value: String): String? =
        Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""").find(value)?.groupValues?.get(2)?.trim()

    fun parseFontFamily(value: String): List<String>? {
        val families = value.split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
        return families.ifEmpty { null }
    }

    fun parseTextShadow(value: String): List<TextShadow>? {
        val text = value.trim()
        if (text.isEmpty() || text.equals("none", ignoreCase = true)) return null
        val shadows = ArrayList<TextShadow>(2)
        // split on top-level commas (color functions contain commas)
        var depth = 0
        var start = 0
        val parts = ArrayList<String>(2)
        for (i in text.indices) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    parts.add(text.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(text.substring(start))
        for (part in parts) {
            val lengths = ArrayList<Length>(3)
            var color: CssColor? = null
            for (token in tokenize(part)) {
                if (color == null && parseColor(token) != null) {
                    color = parseColor(token)
                    continue
                }
                parseLength(token)?.let { lengths.add(it) }
            }
            if (lengths.size >= 2) {
                shadows.add(
                    TextShadow(
                        x = lengths[0],
                        y = lengths[1],
                        blur = lengths.getOrElse(2) { Length(0f, LengthUnit.PX) },
                        color = color,
                    ),
                )
            }
        }
        return shadows.ifEmpty { null }
    }

    fun parseCssFloat(value: String): CssFloat? = when (value.trim().lowercase()) {
        "left" -> CssFloat.LEFT
        "right" -> CssFloat.RIGHT
        "none" -> CssFloat.NONE
        else -> null
    }

    fun parseWordWrap(value: String): WordWrap? = when (value.trim().lowercase()) {
        "normal" -> WordWrap.NORMAL
        "break-word", "anywhere" -> WordWrap.BREAK_WORD
        else -> null
    }

    fun parseWordBreak(value: String): WordBreak? = when (value.trim().lowercase()) {
        "normal" -> WordBreak.NORMAL
        "break-all" -> WordBreak.BREAK_ALL
        "keep-all" -> WordBreak.KEEP_ALL
        else -> null
    }

    fun parseBorderCollapse(value: String): BorderCollapse? = when (value.trim().lowercase()) {
        "collapse" -> BorderCollapse.COLLAPSE
        "separate" -> BorderCollapse.SEPARATE
        else -> null
    }

    fun parsePageBreak(value: String): PageBreak? = when (value.trim().lowercase()) {
        "auto" -> PageBreak.AUTO
        "always", "page", "left", "right" -> PageBreak.ALWAYS
        "avoid" -> PageBreak.AVOID
        else -> null
    }

    fun parseBackgroundRepeat(value: String): String? {
        val tokens = tokenize(value).map { it.lowercase() }
        return tokens.firstOrNull { it in BACKGROUND_REPEATS }
    }

    // ------------------------------------------------------------------ data

    private val EMPHASIS_MARKS = setOf("none", "dot", "circle", "double-circle", "triangle", "sesame")

    private val VERTICAL_ALIGN_KEYWORDS = setOf(
        "baseline", "sub", "super", "top", "text-top", "middle", "bottom", "text-bottom",
    )

    private val BACKGROUND_REPEATS = setOf("repeat", "repeat-x", "repeat-y", "no-repeat", "space", "round")

    /** CSS named colors (CSS3 + rebeccapurple). Values are 0xRRGGBB. */
    private val NAMED_COLORS: Map<String, Int> = mapOf(
        "aliceblue" to 0xF0F8FF, "antiquewhite" to 0xFAEBD7, "aqua" to 0x00FFFF, "aquamarine" to 0x7FFFD4,
        "azure" to 0xF0FFFF, "beige" to 0xF5F5DC, "bisque" to 0xFFE4C4, "black" to 0x000000,
        "blanchedalmond" to 0xFFEBCD, "blue" to 0x0000FF, "blueviolet" to 0x8A2BE2, "brown" to 0xA52A2A,
        "burlywood" to 0xDEB887, "cadetblue" to 0x5F9EA0, "chartreuse" to 0x7FFF00, "chocolate" to 0xD2691E,
        "coral" to 0xFF7F50, "cornflowerblue" to 0x6495ED, "cornsilk" to 0xFFF8DC, "crimson" to 0xDC143C,
        "cyan" to 0x00FFFF, "darkblue" to 0x00008B, "darkcyan" to 0x008B8B, "darkgoldenrod" to 0xB8860B,
        "darkgray" to 0xA9A9A9, "darkgreen" to 0x006400, "darkgrey" to 0xA9A9A9, "darkkhaki" to 0xBDB76B,
        "darkmagenta" to 0x8B008B, "darkolivegreen" to 0x556B2F, "darkorange" to 0xFF8C00, "darkorchid" to 0x9932CC,
        "darkred" to 0x8B0000, "darksalmon" to 0xE9967A, "darkseagreen" to 0x8FBC8F, "darkslateblue" to 0x483D8B,
        "darkslategray" to 0x2F4F4F, "darkslategrey" to 0x2F4F4F, "darkturquoise" to 0x00CED1, "darkviolet" to 0x9400D3,
        "deeppink" to 0xFF1493, "deepskyblue" to 0x00BFFF, "dimgray" to 0x696969, "dimgrey" to 0x696969,
        "dodgerblue" to 0x1E90FF, "firebrick" to 0xB22222, "floralwhite" to 0xFFFAF0, "forestgreen" to 0x228B22,
        "fuchsia" to 0xFF00FF, "gainsboro" to 0xDCDCDC, "ghostwhite" to 0xF8F8FF, "gold" to 0xFFD700,
        "goldenrod" to 0xDAA520, "gray" to 0x808080, "green" to 0x008000, "greenyellow" to 0xADFF2F,
        "grey" to 0x808080, "honeydew" to 0xF0FFF0, "hotpink" to 0xFF69B4, "indianred" to 0xCD5C5C,
        "indigo" to 0x4B0082, "ivory" to 0xFFFFF0, "khaki" to 0xF0E68C, "lavender" to 0xE6E6FA,
        "lavenderblush" to 0xFFF0F5, "lawngreen" to 0x7CFC00, "lemonchiffon" to 0xFFFACD, "lightblue" to 0xADD8E6,
        "lightcoral" to 0xF08080, "lightcyan" to 0xE0FFFF, "lightgoldenrodyellow" to 0xFAFAD2, "lightgray" to 0xD3D3D3,
        "lightgreen" to 0x90EE90, "lightgrey" to 0xD3D3D3, "lightpink" to 0xFFB6C1, "lightsalmon" to 0xFFA07A,
        "lightseagreen" to 0x20B2AA, "lightskyblue" to 0x87CEFA, "lightslategray" to 0x778899, "lightslategrey" to 0x778899,
        "lightsteelblue" to 0xB0C4DE, "lightyellow" to 0xFFFFE0, "lime" to 0x00FF00, "limegreen" to 0x32CD32,
        "linen" to 0xFAF0E6, "magenta" to 0xFF00FF, "maroon" to 0x800000, "mediumaquamarine" to 0x66CDAA,
        "mediumblue" to 0x0000CD, "mediumorchid" to 0xBA55D3, "mediumpurple" to 0x9370DB, "mediumseagreen" to 0x3CB371,
        "mediumslateblue" to 0x7B68EE, "mediumspringgreen" to 0x00FA9A, "mediumturquoise" to 0x48D1CC,
        "mediumvioletred" to 0xC71585, "midnightblue" to 0x191970, "mintcream" to 0xF5FFFA, "mistyrose" to 0xFFE4E1,
        "moccasin" to 0xFFE4B5, "navajowhite" to 0xFFDEAD, "navy" to 0x000080, "oldlace" to 0xFDF5E6,
        "olive" to 0x808000, "olivedrab" to 0x6B8E23, "orange" to 0xFFA500, "orangered" to 0xFF4500,
        "orchid" to 0xDA70D6, "palegoldenrod" to 0xEEE8AA, "palegreen" to 0x98FB98, "paleturquoise" to 0xAFEEEE,
        "palevioletred" to 0xDB7093, "papayawhip" to 0xFFEFD5, "peachpuff" to 0xFFDAB9, "peru" to 0xCD853F,
        "pink" to 0xFFC0CB, "plum" to 0xDDA0DD, "powderblue" to 0xB0E0E6, "purple" to 0x800080,
        "rebeccapurple" to 0x663399, "red" to 0xFF0000, "rosybrown" to 0xBC8F8F, "royalblue" to 0x4169E1,
        "saddlebrown" to 0x8B4513, "salmon" to 0xFA8072, "sandybrown" to 0xF4A460, "seagreen" to 0x2E8B57,
        "seashell" to 0xFFF5EE, "sienna" to 0xA0522D, "silver" to 0xC0C0C0, "skyblue" to 0x87CEEB,
        "slateblue" to 0x6A5ACD, "slategray" to 0x708090, "slategrey" to 0x708090, "snow" to 0xFFFAFA,
        "springgreen" to 0x00FF7F, "steelblue" to 0x4682B4, "tan" to 0xD2B48C, "teal" to 0x008080,
        "thistle" to 0xD8BFD8, "tomato" to 0xFF6347, "turquoise" to 0x40E0D0, "violet" to 0xEE82EE,
        "wheat" to 0xF5DEB3, "white" to 0xFFFFFF, "whitesmoke" to 0xF5F5F5, "yellow" to 0xFFFF00,
        "yellowgreen" to 0x9ACD32,
    )
}
