package org.libepub.core.css

import org.libepub.core.container.ResourcePath
import org.libepub.core.util.WarningSink

/**
 * Stylesheet parser. Tolerant by contract: unknown at-rules, malformed
 * declarations and invalid selectors are skipped (with a warning) rather than
 * thrown on, because one bad rule must never cost the whole book its styling.
 *
 * `@media` blocks whose media type is `screen`/`all` (or empty) are flattened
 * into the rule list in source order; `print` and other media are skipped.
 * Vendor prefixes are normalized (`-webkit-transform` -> `transform`).
 */
object CssParser {

    private val IMPORTANT = Regex("""\s*!\s*important\s*$""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""")
    private val VENDOR_PREFIXES = listOf("-webkit-", "-moz-", "-ms-", "-o-", "-epub-")

    fun parse(css: String, baseDir: String = "", warnings: WarningSink = WarningSink()): Stylesheet {
        val rules = ArrayList<StyleRule>(64)
        val fontFaces = ArrayList<FontFace>(2)
        val imports = ArrayList<String>(0)
        parseInto(css, baseDir, warnings, rules, fontFaces, imports, 0)
        return Stylesheet(rules, fontFaces, imports, warnings, baseDir)
    }

    fun parseDeclarations(block: String, warnings: WarningSink = WarningSink()): List<Declaration> {
        val lexer = CssLexer(block)
        val out = ArrayList<Declaration>(8)
        while (true) {
            lexer.skipTrivia()
            if (lexer.eof()) break
            val segment = lexer.readUntil(';')
            lexer.expect(';')
            parseDeclaration(segment)?.let { out.add(it) }
        }
        return out
    }

    // ---------------------------------------------------------------- internals

    private fun parseInto(
        css: String,
        baseDir: String,
        warnings: WarningSink,
        rules: MutableList<StyleRule>,
        fontFaces: MutableList<FontFace>,
        imports: MutableList<String>,
        startOrder: Int,
    ): Int {
        val lexer = CssLexer(css)
        var order = startOrder
        while (true) {
            lexer.skipTrivia()
            if (lexer.eof()) break
            when (lexer.peek()) {
                '@' -> order = parseAtRule(lexer, baseDir, warnings, rules, fontFaces, imports, order)
                '}' -> lexer.expect('}') // stray close brace: tolerate
                else -> {
                    val prelude = lexer.readUntil('{', ';', '}')
                    lexer.skipTrivia()
                    when (lexer.peek()) {
                        '{' -> {
                            val block = lexer.readBlock()
                            val selectors = SelectorParser.parseList(prelude, warnings)
                            if (selectors.isEmpty()) continue
                            rules.add(StyleRule(selectors, parseDeclarations(block, warnings), order++))
                        }
                        ';' -> lexer.expect(';')
                        else -> Unit // stray '}' or EOF: loop will handle it
                    }
                }
            }
        }
        return order
    }

    private fun parseAtRule(
        lexer: CssLexer,
        baseDir: String,
        warnings: WarningSink,
        rules: MutableList<StyleRule>,
        fontFaces: MutableList<FontFace>,
        imports: MutableList<String>,
        order: Int,
    ): Int {
        lexer.expect('@')
        val name = lexer.readName().lowercase()
        val prelude = lexer.readUntil('{', ';').trim()
        return when (lexer.peek()) {
            ';' -> {
                lexer.expect(';')
                if (name == "import" && prelude.isNotEmpty()) imports.add(prelude)
                order
            }
            '{' -> {
                val block = lexer.readBlock()
                when (name) {
                    "font-face" -> {
                        fontFaces.add(parseFontFace(block, baseDir, warnings))
                        order
                    }
                    "media" -> if (mediaApplies(prelude)) {
                        parseInto(block, baseDir, warnings, rules, fontFaces, imports, order)
                    } else {
                        order
                    }
                    else -> order // unknown at-rule: ignored
                }
            }
            else -> order
        }
    }

    private fun parseFontFace(block: String, baseDir: String, warnings: WarningSink): FontFace {
        val declarations = parseDeclarations(block, warnings)
        var family: String? = null
        var src: String? = null
        var weight: String? = null
        var style: String? = null
        for (declaration in declarations) {
            when (declaration.property) {
                "font-family" -> family = declaration.value.trim().trim('"', '\'')
                "src" -> {
                    if (src == null) {
                        src = URL.find(declaration.value)?.groupValues?.get(2)
                            ?.let { ResourcePath.resolve(baseDir, it.trim()) }
                    }
                }
                "font-weight" -> weight = declaration.value
                "font-style" -> style = declaration.value
            }
        }
        return FontFace(family, src, weight, style, declarations)
    }

    private fun parseDeclaration(segment: String): Declaration? {
        val lexer = CssLexer(segment)
        val rawProperty = lexer.readUntil(':')
        if (lexer.peek() != ':') return null
        lexer.expect(':')
        val rawValue = segment.substring(lexer.pos)
        var value = collapseWhitespace(rawValue)
        if (value.isEmpty()) return null
        var important = false
        IMPORTANT.find(value)?.let { match ->
            important = true
            value = value.substring(0, match.range.first).trim()
        }
        if (value.isEmpty()) return null
        val property = normalizeProperty(rawProperty)
        if (property.isEmpty()) return null
        return Declaration(property, rawProperty.trim(), value, important)
    }

    fun normalizeProperty(raw: String): String {
        var property = raw.trim().lowercase()
        if (property.isEmpty() || property.startsWith("--")) return property
        for (prefix in VENDOR_PREFIXES) {
            if (property.startsWith(prefix)) {
                property = property.removePrefix(prefix)
                break
            }
        }
        return property
    }

    /** Collapses whitespace runs to single spaces, leaving quoted strings intact. */
    private fun collapseWhitespace(input: String): String {
        val sb = StringBuilder(input.length)
        var quote = ' '
        var lastWasSpace = false
        for (c in input) {
            if (quote != ' ') {
                sb.append(c)
                if (c == quote && (sb.length < 2 || sb[sb.length - 2] != '\\')) quote = ' '
                continue
            }
            when {
                c == '"' || c == '\'' -> {
                    quote = c
                    sb.append(c)
                    lastWasSpace = false
                }
                c.isWhitespace() -> if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
                else -> {
                    sb.append(c)
                    lastWasSpace = false
                }
            }
        }
        return sb.toString().trim()
    }

    private fun mediaApplies(prelude: String): Boolean {
        if (prelude.isBlank()) return true
        for (part in splitTopLevelCommas(prelude)) {
            val query = part.trim()
            if (query.isEmpty()) return true
            val type = query.substringBefore('(').substringBefore(' ').trim().lowercase()
            if (type.isEmpty() || type == "all" || type == "screen") return true
        }
        return false
    }
}
