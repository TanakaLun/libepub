package org.libepub.core.xhtml

/** One declaration from a `style=""` attribute; values stay raw until CSS value parsing. */
data class StyleDecl(val property: String, val value: String, val important: Boolean)

/**
 * Splits inline style attributes into declarations without interpreting values.
 * Tolerant: malformed segments are skipped, never thrown on.
 */
internal object InlineStyleAttr {

    private val IMPORTANT = Regex("""\s*!\s*important\s*$""", RegexOption.IGNORE_CASE)

    fun parse(style: String): List<StyleDecl> {
        if (style.isBlank()) return emptyList()
        val out = ArrayList<StyleDecl>(4)
        for (segment in splitTopLevel(style, ';')) {
            val colon = indexOfTopLevel(segment, ':')
            if (colon <= 0) continue
            var property = segment.substring(0, colon).trim()
            var value = segment.substring(colon + 1).trim()
            if (property.isEmpty() || value.isEmpty()) continue
            var important = false
            val m = IMPORTANT.find(value)
            if (m != null) {
                important = true
                value = value.substring(0, m.range.first).trim()
                if (value.isEmpty()) continue
            }
            if (!property.startsWith("--")) property = property.lowercase()
            out.add(StyleDecl(property, value, important))
        }
        return out
    }

    private fun splitTopLevel(input: String, separator: Char): List<String> {
        val parts = ArrayList<String>(4)
        var start = 0
        var depth = 0
        var quote = ' '
        for (i in input.indices) {
            val c = input[i]
            if (quote != ' ') {
                if (c == quote) quote = ' '
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                separator -> if (depth == 0) {
                    parts.add(input.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(input.substring(start))
        return parts
    }

    private fun indexOfTopLevel(input: String, target: Char): Int {
        var depth = 0
        var quote = ' '
        for (i in input.indices) {
            val c = input[i]
            if (quote != ' ') {
                if (c == quote) quote = ' '
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                target -> if (depth == 0) return i
            }
        }
        return -1
    }
}
