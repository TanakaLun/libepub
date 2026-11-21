package org.libepub.core.xhtml

import org.libepub.core.script.RawScript
import org.libepub.core.util.WarningSink

/**
 * Rewrites raw XHTML into well-formed XML that kxml2 parses without exceptions
 * and without any network access:
 *
 *  - strips `<!DOCTYPE ...>` / declarations (an external DTD is never fetched)
 *  - drops `<script>` elements from the tree (JS never runs and raw `<` inside
 *    would break XML lexing); when a collector is supplied the element's text
 *    and attributes are handed to the script auditor instead of being lost
 *  - keeps `<style>` content as CDATA (CSS is collected by the parser)
 *  - expands HTML4 named entities; escapes bare `&` and stray `<`
 *  - repairs tag balance: mismatched end tags auto-close intervening elements,
 *    stray end tags are dropped, elements still open at EOF are closed
 *  - re-serializes start tags: void elements become self-closing and unquoted
 *    attribute values are quoted
 */
internal object MarkupNormalizer {

    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    private val XML_PREDEFINED = setOf("amp", "lt", "gt", "quot", "apos")

    fun normalize(
        raw: String,
        warnings: WarningSink,
        scripts: MutableList<RawScript>? = null,
    ): String {
        val out = StringBuilder(raw.length + 64)
        val stack = ArrayList<String>(8)
        var i = 0
        val n = raw.length
        while (i < n) {
            when (raw[i]) {
                '<' -> i = handleLt(raw, i, out, stack, warnings, scripts)
                '&' -> i = handleAmp(raw, i, out, warnings)
                else -> {
                    out.append(raw[i])
                    i++
                }
            }
        }
        if (stack.isNotEmpty()) {
            warnings.warnOnce(
                "xhtml.unclosed",
                "implicitly closed ${stack.size} unclosed element(s) at end of document",
            )
            for (k in stack.size - 1 downTo 0) out.append("</").append(stack[k]).append('>')
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- markup

    private fun handleLt(
        raw: String,
        i: Int,
        out: StringBuilder,
        stack: ArrayList<String>,
        warnings: WarningSink,
        scripts: MutableList<RawScript>?,
    ): Int {
        val n = raw.length
        if (raw.startsWith("<!--", i)) {
            val end = raw.indexOf("-->", i + 4)
            return if (end < 0) n else end + 3 // comments are dropped
        }
        if (raw.startsWith("<![CDATA[", i)) {
            val end = raw.indexOf("]]>", i + 9)
            return if (end < 0) {
                out.append("&lt;")
                i + 1
            } else {
                out.append(raw, i, end + 3)
                end + 3
            }
        }
        if (raw.startsWith("<!", i)) return skipDeclaration(raw, i)
        if (raw.startsWith("<?", i)) {
            val end = raw.indexOf("?>", i + 2)
            return if (end < 0) n else end + 2 // processing instructions are dropped
        }
        if (raw.startsWith("</", i)) return handleEndTag(raw, i, out, stack, warnings)
        if (i + 1 < n && raw[i + 1].isLetter()) return handleStartTag(raw, i, out, stack, warnings, scripts)
        out.append("&lt;")
        return i + 1
    }

    private fun handleEndTag(
        raw: String,
        i: Int,
        out: StringBuilder,
        stack: ArrayList<String>,
        warnings: WarningSink,
    ): Int {
        val gt = raw.indexOf('>', i + 2)
        if (gt < 0) {
            out.append("&lt;")
            return i + 1
        }
        val name = readName(raw, i + 2, gt)
        if (name.isEmpty()) {
            out.append("&lt;")
            return i + 1
        }
        val topIdx = stack.indexOfLast { it.equals(name, ignoreCase = true) }
        when {
            topIdx == stack.lastIndex -> {
                val stored = stack.removeAt(stack.lastIndex)
                if (stored == name) out.append(raw, i, gt + 1)
                else out.append("</").append(stored).append('>')
            }
            topIdx >= 0 -> {
                while (stack.lastIndex > topIdx) {
                    out.append("</").append(stack.removeAt(stack.lastIndex)).append('>')
                }
                out.append("</").append(stack.removeAt(stack.lastIndex)).append('>')
            }
            else -> warnings.warnOnce("xhtml.stray-end", "dropped stray end tag </$name>")
        }
        return gt + 1
    }

    private fun handleStartTag(
        raw: String,
        i: Int,
        out: StringBuilder,
        stack: ArrayList<String>,
        warnings: WarningSink,
        scripts: MutableList<RawScript>?,
    ): Int {
        val n = raw.length
        val gt = findTagEnd(raw, i)
        if (gt < 0) {
            out.append("&lt;")
            return i + 1
        }
        var inner = raw.substring(i + 1, gt)
        val name = readNameFrom(inner)
        if (name.isEmpty()) {
            out.append("&lt;")
            return i + 1
        }
        val lower = name.lowercase()
        val selfClosed = inner.trimEnd().endsWith("/")
        if (selfClosed) inner = inner.trimEnd().dropLast(1)
        val body = rebuildTag(name, inner) ?: inner

        when (lower) {
            "script" -> {
                val close = raw.indexOf("</script", gt + 1, ignoreCase = true)
                val innerEnd = if (close < 0) n else close
                if (scripts != null) {
                    scripts.add(
                        RawScript(
                            src = attrValue(body, "src"),
                            type = attrValue(body, "type") ?: attrValue(body, "language"),
                            inline = raw.substring(gt + 1, innerEnd),
                        ),
                    )
                } else {
                    warnings.warnOnce("xhtml.script", "ignored <script> element (JS is never executed)")
                }
                if (close < 0) {
                    warnings.warnOnce("xhtml.script-unclosed", "unclosed <script> at end of document")
                    return n
                }
                val gt2 = raw.indexOf('>', close)
                return if (gt2 < 0) n else gt2 + 1
            }
            "style" -> {
                if (selfClosed) {
                    out.append('<').append(body.trimEnd()).append("/>")
                    return gt + 1
                }
                val close = raw.indexOf("</style", gt + 1, ignoreCase = true)
                val innerEnd = if (close < 0) n else close
                val css = raw.substring(gt + 1, innerEnd).replace("]]>", "]]&gt;")
                out.append('<').append(body).append('>')
                out.append("<![CDATA[").append(css).append("]]>")
                if (close < 0) {
                    warnings.warnOnce("xhtml.style-unclosed", "unclosed <style> at end of document")
                    out.append("</style>")
                    return n
                }
                out.append("</style>")
                val gt2 = raw.indexOf('>', close)
                return if (gt2 < 0) n else gt2 + 1
            }
            else -> {
                if (selfClosed || lower in VOID) {
                    out.append('<').append(body.trimEnd()).append("/>")
                } else {
                    out.append('<').append(body).append('>')
                    stack.add(name)
                }
                return gt + 1
            }
        }
    }

    /**
     * Re-serializes `name + attrs` with quoted, entity-expanded values.
     * Returns null when the attribute list cannot be understood (caller falls
     * back to the original text).
     */
    private fun rebuildTag(name: String, rest: String): String? {
        val sb = StringBuilder(name.length + rest.length + 8)
        sb.append(name)
        var i = 0
        val n = rest.length
        while (i < n) {
            while (i < n && rest[i].isWhitespace()) i++
            if (i >= n) break
            val nameStart = i
            while (i < n && !rest[i].isWhitespace() && rest[i] != '=' && rest[i] != '/') i++
            if (i == nameStart) return null
            val attrName = rest.substring(nameStart, i)
            while (i < n && rest[i].isWhitespace()) i++
            var value: String? = null
            if (i < n && rest[i] == '=') {
                i++
                while (i < n && rest[i].isWhitespace()) i++
                if (i >= n) return null
                val quote = rest[i]
                if (quote == '"' || quote == '\'') {
                    i++
                    val valueStart = i
                    while (i < n && rest[i] != quote) i++
                    if (i >= n) return null
                    value = rest.substring(valueStart, i)
                    i++
                } else {
                    val valueStart = i
                    while (i < n && !rest[i].isWhitespace()) i++
                    value = rest.substring(valueStart, i)
                }
            }
            sb.append(' ').append(attrName)
            if (value != null) {
                sb.append("=\"").append(escapeAttr(expandEntities(value))).append('"')
            } else {
                sb.append("=\"\"")
            }
        }
        return sb.toString()
    }

    // -------------------------------------------------------------- entities

    private fun handleAmp(raw: String, i: Int, out: StringBuilder, warnings: WarningSink): Int {
        val n = raw.length
        var j = i + 1
        if (j >= n) {
            out.append("&amp;")
            return i + 1
        }
        if (raw[j] == '#') {
            j++
            val hex = j < n && (raw[j] == 'x' || raw[j] == 'X')
            if (hex) j++
            val digitsStart = j
            while (j < n && isDigit(raw[j], hex)) j++
            if (j > digitsStart && j < n && raw[j] == ';') {
                out.append(raw, i, j + 1) // numeric refs are resolved by the XML parser
                return j + 1
            }
            out.append("&amp;")
            return i + 1
        }
        val nameStart = j
        while (j < n && raw[j].isLetterOrDigit()) j++
        if (j > nameStart && j < n && raw[j] == ';') {
            val name = raw.substring(nameStart, j)
            val replacement = HtmlEntities.MAP[name]
            when {
                replacement != null -> out.append(replacement)
                name in XML_PREDEFINED -> out.append(raw, i, j + 1)
                else -> warnings.warnOnce("xhtml.entity:$name", "unknown entity &$name; ignored")
            }
            return j + 1
        }
        out.append("&amp;")
        return i + 1
    }

    /** Resolves every entity reference in an attribute value to plain text. */
    private fun expandEntities(value: String): String {
        if (!value.contains('&')) return value
        val sb = StringBuilder(value.length)
        var i = 0
        val n = value.length
        while (i < n) {
            val c = value[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            var j = i + 1
            if (j < n && value[j] == '#') {
                j++
                val hex = j < n && (value[j] == 'x' || value[j] == 'X')
                if (hex) j++
                val digitsStart = j
                while (j < n && isDigit(value[j], hex)) j++
                if (j > digitsStart && j < n && value[j] == ';') {
                    val cp = value.substring(digitsStart, j).toIntOrNull(if (hex) 16 else 10)
                    if (cp != null && cp in 1..0x10FFFF) sb.appendCodePoint(cp)
                    i = j + 1
                    continue
                }
                sb.append('&')
                i++
                continue
            }
            val nameStart = j
            while (j < n && value[j].isLetterOrDigit()) j++
            if (j > nameStart && j < n && value[j] == ';') {
                val name = value.substring(nameStart, j)
                when {
                    name in XML_PREDEFINED -> sb.append(
                        when (name) {
                            "amp" -> '&'
                            "lt" -> '<'
                            "gt" -> '>'
                            "quot" -> '"'
                            else -> '\''
                        },
                    )
                    else -> HtmlEntities.MAP[name]?.let { sb.append(it) }
                }
                i = j + 1
                continue
            }
            sb.append('&')
            i++
        }
        return sb.toString()
    }

    private fun escapeAttr(value: String): String {
        var needs = false
        for (c in value) if (c == '&' || c == '<' || c == '"') {
            needs = true
            break
        }
        if (!needs) return value
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    // ----------------------------------------------------------------- utils

    private fun skipDeclaration(raw: String, start: Int): Int {
        var i = start + 2
        var depth = 0
        var quote = ' '
        while (i < raw.length) {
            val c = raw[i]
            if (quote != ' ') {
                if (c == quote) quote = ' '
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '[' -> depth++
                    ']' -> if (depth > 0) depth--
                    '>' -> if (depth == 0) return i + 1
                }
            }
            i++
        }
        return raw.length
    }

    private fun findTagEnd(raw: String, start: Int): Int {
        var i = start + 1
        var quote = ' '
        while (i < raw.length) {
            val c = raw[i]
            if (quote != ' ') {
                if (c == quote) quote = ' '
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '>' -> return i
                }
            }
            i++
        }
        return -1
    }

    private fun readName(raw: String, from: Int, until: Int): String {
        var i = from
        while (i < until && isNameChar(raw[i])) i++
        return raw.substring(from, i)
    }

    private fun readNameFrom(inner: String): String {
        var i = 0
        while (i < inner.length && isNameChar(inner[i])) i++
        return inner.substring(0, i)
    }

    /** Value of [name] in a re-serialized tag (`name attr="value"`), or null. */
    private fun attrValue(tag: String, name: String): String? {
        var from = 0
        while (true) {
            val at = tag.indexOf(name, from)
            if (at < 0) return null
            from = at + name.length
            if (at > 0 && !tag[at - 1].isWhitespace()) continue
            if (tag.indexOf('=', at + name.length) != at + name.length) continue
            val quoteAt = at + name.length + 1
            val quote = tag.getOrNull(quoteAt) ?: continue
            if (quote != '"' && quote != '\'') continue
            val end = tag.indexOf(quote, quoteAt + 1)
            if (end < 0) continue
            return tag.substring(quoteAt + 1, end)
        }
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c in "-_:."

    private fun isDigit(c: Char, hex: Boolean): Boolean =
        if (hex) c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' else c in '0'..'9'
}
