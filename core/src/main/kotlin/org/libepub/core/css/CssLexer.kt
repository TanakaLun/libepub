package org.libepub.core.css

/**
 * Character-level CSS scanner: comment skipping, string scanning with escapes,
 * and nesting-aware reading of rule preludes and `{...}` blocks.
 */
internal class CssLexer(private val text: String) {

    var pos: Int = 0
        private set

    fun eof(): Boolean = pos >= text.length

    fun peek(offset: Int = 0): Char? = text.getOrNull(pos + offset)

    fun expect(expected: Char) {
        if (peek() == expected) pos++
    }

    /** Skips whitespace and CSS block comments. */
    fun skipTrivia() {
        while (pos < text.length) {
            val c = text[pos]
            if (c.isWhitespace()) {
                pos++
                continue
            }
            if (c == '/' && peek(1) == '*') {
                val end = text.indexOf("*/", pos + 2)
                pos = if (end < 0) text.length else end + 2
                continue
            }
            break
        }
    }

    /** Reads an identifier (`@media`, property names, ...). */
    fun readName(): String {
        val start = pos
        while (pos < text.length && isNameChar(text[pos])) pos++
        return text.substring(start, pos)
    }

    /**
     * Reads until one of [stops] at nesting depth 0, skipping strings and
     * comments. The stop character is left unconsumed.
     */
    fun readUntil(vararg stops: Char): String {
        val start = pos
        var depth = 0
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == '"' || c == '\'' -> skipString()
                c == '/' && peek(1) == '*' -> {
                    val end = text.indexOf("*/", pos + 2)
                    pos = if (end < 0) text.length else end + 2
                }
                c == '(' || c == '[' -> {
                    depth++
                    pos++
                }
                c == ')' || c == ']' -> {
                    if (depth > 0) depth--
                    pos++
                }
                depth == 0 && c in stops -> return text.substring(start, pos)
                else -> pos++
            }
        }
        return text.substring(start, pos)
    }

    /** Reads a `{...}` block (nested braces included) and returns its inner text. */
    fun readBlock(): String {
        if (peek() != '{') return ""
        pos++
        val start = pos
        var depth = 1
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == '"' || c == '\'' -> skipString()
                c == '/' && peek(1) == '*' -> {
                    val end = text.indexOf("*/", pos + 2)
                    pos = if (end < 0) text.length else end + 2
                }
                c == '{' -> {
                    depth++
                    pos++
                }
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val content = text.substring(start, pos)
                        pos++
                        return content
                    }
                    pos++
                }
                else -> pos++
            }
        }
        return text.substring(start, pos)
    }

    private fun skipString() {
        val quote = text[pos]
        pos++
        while (pos < text.length) {
            val c = text[pos]
            if (c == '\\') {
                pos += 2
                continue
            }
            pos++
            if (c == quote) return
        }
    }
}

internal fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '_'

/** Splits a selector list / media query list on top-level commas. */
internal fun splitTopLevelCommas(text: String): List<String> {
    val lexer = CssLexer(text)
    val parts = ArrayList<String>(2)
    while (true) {
        parts.add(lexer.readUntil(','))
        if (lexer.peek() == ',') lexer.expect(',') else break
        if (lexer.eof()) break
    }
    return parts
}
