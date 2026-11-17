package org.libepub.core.css

import org.libepub.core.util.WarningSink

enum class Combinator { DESCENDANT, CHILD, ADJACENT, SIBLING }

data class AttributeSelector(
    val name: String,
    /** null = presence check; otherwise `=`, `~=`, `|=`, `^=`, `$=`, `*=`. */
    val operator: String?,
    val value: String?,
)

data class CompoundSelector(
    /** Element type, or null when absent (universal / no type). */
    val type: String? = null,
    val id: String? = null,
    val classes: List<String> = emptyList(),
    val attributes: List<AttributeSelector> = emptyList(),
    /** Pseudo-classes as written (`first-child`, `not(.x)`), lowercased. */
    val pseudoClasses: List<String> = emptyList(),
    val pseudoElement: String? = null,
    val universal: Boolean = false,
)

/** CSS specificity (a=ids, b=classes/attrs/pseudo-classes, c=types/pseudo-elements). */
data class Specificity(val ids: Int, val classes: Int, val types: Int) : Comparable<Specificity> {
    override fun compareTo(other: Specificity): Int =
        compareValuesBy(this, other, { it.ids }, { it.classes }, { it.types })

    override fun toString(): String = "($ids,$classes,$types)"
}

/** A complex selector: compounds left-to-right with combinators between them. */
data class Selector(
    val compounds: List<CompoundSelector>,
    /** Size is `compounds.size - 1`. */
    val combinators: List<Combinator>,
    val specificity: Specificity,
    val text: String,
)

object SelectorParser {

    private val ATTR = Regex("""^\s*([^\s~|^$*=]+)\s*(?:([~|^$*]?=)\s*(.+?))?\s*$""")

    fun parseList(prelude: String, warnings: WarningSink = WarningSink()): List<Selector> {
        val out = ArrayList<Selector>(2)
        for (part in splitTopLevelCommas(prelude)) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val selector = parseOne(trimmed)
            if (selector != null) {
                out.add(selector)
            } else {
                warnings.warnOnce("css.selector:$trimmed", "invalid selector ignored: $trimmed")
            }
        }
        return out
    }

    private fun parseOne(text: String): Selector? {
        val compounds = ArrayList<CompoundSelector>(2)
        val combinators = ArrayList<Combinator>(1)
        var i = 0
        var pendingCombinator: Combinator? = null
        while (i < text.length) {
            var sawSpace = false
            while (i < text.length && text[i].isWhitespace()) {
                sawSpace = true
                i++
            }
            if (i >= text.length) break
            val c = text[i]
            if (c == '>' || c == '+' || c == '~') {
                if (pendingCombinator != null) return null
                pendingCombinator = when (c) {
                    '>' -> Combinator.CHILD
                    '+' -> Combinator.ADJACENT
                    else -> Combinator.SIBLING
                }
                i++
                continue
            }
            if (compounds.isNotEmpty()) {
                combinators.add(pendingCombinator ?: if (sawSpace) Combinator.DESCENDANT else return null)
            } else if (pendingCombinator != null) {
                return null
            }
            pendingCombinator = null
            val parsed = parseCompound(text, i) ?: return null
            compounds.add(parsed.first)
            i = parsed.second
        }
        if (compounds.isEmpty() || pendingCombinator != null) return null
        if (combinators.size != compounds.size - 1) return null
        return Selector(compounds, combinators, specificityOf(compounds), text)
    }

    /** Parses exactly one compound selector (`p`, `.a.b`, `[href]`, ...); null if [text] is more than that. */
    fun parseCompoundOnly(text: String): CompoundSelector? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parsed = parseCompound(trimmed, 0) ?: return null
        return if (parsed.second == trimmed.length) parsed.first else null
    }

    private fun parseCompound(text: String, start: Int): Pair<CompoundSelector, Int>? {
        var i = start
        var type: String? = null
        var id: String? = null
        var universal = false
        val classes = ArrayList<String>(2)
        val attributes = ArrayList<AttributeSelector>(1)
        val pseudoClasses = ArrayList<String>(1)
        var pseudoElement: String? = null
        var any = false

        if (i < text.length && text[i] == '*') {
            universal = true
            any = true
            i++
        } else if (i < text.length && (text[i].isLetter() || text[i] == '_')) {
            val nameStart = i
            while (i < text.length && isNameChar(text[i])) i++
            type = text.substring(nameStart, i).lowercase()
            any = true
        }

        while (i < text.length) {
            when (text[i]) {
                '#' -> {
                    i++
                    val nameStart = i
                    while (i < text.length && isNameChar(text[i])) i++
                    if (i == nameStart) return null
                    id = text.substring(nameStart, i)
                    any = true
                }
                '.' -> {
                    i++
                    val nameStart = i
                    while (i < text.length && isNameChar(text[i])) i++
                    if (i == nameStart) return null
                    classes.add(text.substring(nameStart, i))
                    any = true
                }
                '[' -> {
                    val end = findClosing(text, i, '[', ']') ?: return null
                    attributes.add(parseAttribute(text.substring(i + 1, end)))
                    i = end + 1
                    any = true
                }
                ':' -> {
                    i++
                    if (i < text.length && text[i] == ':') {
                        i++
                        val nameStart = i
                        while (i < text.length && isNameChar(text[i])) i++
                        if (i == nameStart) return null
                        pseudoElement = text.substring(nameStart, i).lowercase()
                    } else {
                        val nameStart = i
                        while (i < text.length && isNameChar(text[i])) i++
                        if (i == nameStart) return null
                        var name = text.substring(nameStart, i).lowercase()
                        if (i < text.length && text[i] == '(') {
                            val end = findClosing(text, i, '(', ')') ?: return null
                            name += text.substring(i, end + 1).replace(WHITESPACE, "")
                            i = end + 1
                        }
                        pseudoClasses.add(name)
                    }
                    any = true
                }
                else -> break
            }
        }
        if (!any) return null
        return CompoundSelector(type, id, classes, attributes, pseudoClasses, pseudoElement, universal) to i
    }

    private fun parseAttribute(inner: String): AttributeSelector {
        val match = ATTR.matchEntire(inner) ?: return AttributeSelector(inner.trim().lowercase(), null, null)
        val name = match.groupValues[1].lowercase()
        val op = match.groupValues[2].ifEmpty { null }
        val value = match.groupValues[3].ifEmpty { null }?.trim()?.trim('"', '\'')
        return AttributeSelector(name, op, value)
    }

    private fun findClosing(text: String, openIndex: Int, open: Char, close: Char): Int? {
        var depth = 0
        var quote = ' '
        var i = openIndex
        while (i < text.length) {
            val c = text[i]
            if (quote != ' ') {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == quote) quote = ' '
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }

    private fun specificityOf(compounds: List<CompoundSelector>): Specificity {
        var ids = 0
        var classes = 0
        var types = 0
        for (compound in compounds) {
            if (compound.id != null) ids++
            classes += compound.classes.size + compound.attributes.size + compound.pseudoClasses.size
            if (compound.type != null) types++
            if (compound.pseudoElement != null) types++
        }
        return Specificity(ids, classes, types)
    }

    private val WHITESPACE = Regex("\\s+")
}
