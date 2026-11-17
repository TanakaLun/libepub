package org.libepub.core.style

import org.libepub.core.css.AttributeSelector
import org.libepub.core.css.Combinator
import org.libepub.core.css.CompoundSelector
import org.libepub.core.css.Selector
import org.libepub.core.css.SelectorParser
import org.libepub.core.xhtml.DocNode
import org.libepub.core.xhtml.NodeKind

/**
 * CSS selector matching against a [DocNode] tree, right to left.
 *
 * Unknown pseudo-classes and pseudo-elements fail closed (never match): we do
 * not generate `::before`/`::after` content, and matching `:hover`/`:visited`
 * would be meaningless. Structural pseudo-classes are evaluated from the live
 * tree, so no precomputed sibling indexes are needed.
 */
object SelectorMatcher {

    fun matches(selector: Selector, node: DocNode): Boolean {
        if (node.kind != NodeKind.ELEMENT) return false
        return matchFrom(selector, selector.compounds.lastIndex, node)
    }

    private fun matchFrom(selector: Selector, index: Int, node: DocNode): Boolean {
        if (!matchesCompound(selector.compounds[index], node)) return false
        if (index == 0) return true
        return when (selector.combinators[index - 1]) {
            Combinator.DESCENDANT -> {
                var ancestor = node.parent
                while (ancestor != null) {
                    if (ancestor.kind == NodeKind.ELEMENT && matchFrom(selector, index - 1, ancestor)) return true
                    ancestor = ancestor.parent
                }
                false
            }
            Combinator.CHILD -> {
                val parent = node.parent
                parent != null && parent.kind == NodeKind.ELEMENT && matchFrom(selector, index - 1, parent)
            }
            Combinator.ADJACENT -> {
                val previous = previousElementSibling(node)
                previous != null && matchFrom(selector, index - 1, previous)
            }
            Combinator.SIBLING -> {
                var previous = previousElementSibling(node)
                while (previous != null) {
                    if (matchFrom(selector, index - 1, previous)) return true
                    previous = previousElementSibling(previous)
                }
                false
            }
        }
    }

    // ---------------------------------------------------------------- compound

    private fun matchesCompound(compound: CompoundSelector, node: DocNode): Boolean {
        if (compound.pseudoElement != null) return false

        if (compound.type != null && compound.type != node.name.lowercase()) return false

        if (compound.id != null && node.attr("id") != compound.id) return false

        if (compound.classes.isNotEmpty()) {
            val tokens = classTokens(node)
            if (tokens.isEmpty()) return false
            for (name in compound.classes) if (name !in tokens) return false
        }

        for (attribute in compound.attributes) {
            if (!matchesAttribute(attribute, node)) return false
        }

        for (pseudo in compound.pseudoClasses) {
            if (!matchesPseudoClass(pseudo, node)) return false
        }
        return true
    }

    private fun matchesAttribute(attribute: AttributeSelector, node: DocNode): Boolean {
        val actual = node.attr(attribute.name) ?: return false
        val expected = attribute.value ?: return true
        return when (attribute.operator) {
            null -> true
            "=" -> actual == expected
            "~=" -> expected.isNotEmpty() && expected.split(WHITESPACE).all { it in actual.split(WHITESPACE) }
            "|=" -> actual == expected || actual.startsWith("$expected-")
            "^=" -> expected.isNotEmpty() && actual.startsWith(expected)
            "$=" -> expected.isNotEmpty() && actual.endsWith(expected)
            "*=" -> expected.isNotEmpty() && actual.contains(expected)
            else -> false
        }
    }

    // ---------------------------------------------------------------- pseudos

    private fun matchesPseudoClass(pseudo: String, node: DocNode): Boolean {
        val name = pseudo.substringBefore('(')
        val argument = if (pseudo.length > name.length) {
            pseudo.substring(name.length + 1, pseudo.length - 1)
        } else {
            null
        }
        return when (name) {
            "first-child" -> position(node) == 1
            "last-child" -> position(node) == siblingCount(node)
            "only-child" -> siblingCount(node) == 1
            "nth-child" -> nthMatches(argument, position(node))
            "nth-last-child" -> nthMatches(argument, siblingCount(node) - position(node) + 1)
            "first-of-type" -> positionOfType(node) == 1
            "last-of-type" -> positionOfType(node) == countOfType(node)
            "only-of-type" -> countOfType(node) == 1
            "nth-of-type" -> nthMatches(argument, positionOfType(node))
            "nth-last-of-type" -> nthMatches(argument, countOfType(node) - positionOfType(node) + 1)
            "root" -> node.parent?.let { it.kind != NodeKind.ELEMENT } ?: true
            "empty" -> node.children.isEmpty()
            "not" -> {
                val inner = argument?.let { SelectorParser.parseCompoundOnly(it) }
                inner != null && !matchesCompound(inner, node)
            }
            "lang" -> matchesLang(argument, node)
            // Dynamic/unsupported states never match.
            "link", "visited", "hover", "active", "focus", "focus-within",
            "focus-visible", "target", "checked", "disabled", "enabled",
            "required", "optional", "valid", "invalid", "read-only", "read-write",
            "any-link", "default", "indeterminate", "in-range", "out-of-range",
            -> false
            else -> false
        }
    }

    private fun matchesLang(argument: String?, node: DocNode): Boolean {
        val wanted = argument?.trim()?.trim('"', '\'')?.lowercase() ?: return false
        var current: DocNode? = node
        while (current != null) {
            val lang = current.attrLocal("lang")
            if (lang != null) {
                val value = lang.lowercase()
                return value == wanted || value.startsWith("$wanted-")
            }
            current = current.parent
        }
        return false
    }

    // --------------------------------------------------------------- siblings

    private fun siblings(node: DocNode): List<DocNode> =
        node.parent?.children?.filter { it.kind == NodeKind.ELEMENT } ?: listOf(node)

    private fun position(node: DocNode): Int = siblings(node).indexOfFirst { it === node } + 1

    private fun siblingCount(node: DocNode): Int = siblings(node).size

    private fun positionOfType(node: DocNode): Int {
        var count = 0
        for (sibling in siblings(node)) {
            if (sibling.name == node.name) count++
            if (sibling === node) return count
        }
        return 0
    }

    private fun countOfType(node: DocNode): Int = siblings(node).count { it.name == node.name }

    private fun previousElementSibling(node: DocNode): DocNode? {
        val parent = node.parent ?: return null
        var previous: DocNode? = null
        for (child in parent.children) {
            if (child === node) return previous
            if (child.kind == NodeKind.ELEMENT) previous = child
        }
        return null
    }

    // ------------------------------------------------------------------- nth

    /** `an+b` as (a, b); null for invalid formulas. */
    internal fun parseNth(argument: String?): Pair<Int, Int>? {
        if (argument == null) return null
        val text = argument.replace(" ", "").lowercase()
        if (text.isEmpty()) return null
        if (text == "odd") return 2 to 1
        if (text == "even") return 2 to 0
        if (!text.contains('n')) return text.toIntOrNull()?.let { 0 to it }
        val index = text.indexOf('n')
        val aPart = text.substring(0, index)
        val bPart = text.substring(index + 1)
        val a = when (aPart) {
            "", "+" -> 1
            "-" -> -1
            else -> aPart.toIntOrNull() ?: return null
        }
        val b = if (bPart.isEmpty()) 0 else bPart.toIntOrNull() ?: return null
        return a to b
    }

    private fun nthMatches(argument: String?, index: Int): Boolean {
        val (a, b) = parseNth(argument) ?: return false
        if (index <= 0) return false
        if (a == 0) return index == b
        val diff = index - b
        if (diff % a != 0) return false
        return diff / a >= 0
    }

    private fun classTokens(node: DocNode): Set<String> {
        val raw = node.attr("class") ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(WHITESPACE).filterTo(HashSet()) { it.isNotEmpty() }
    }

    private val WHITESPACE = Regex("\\s+")
}
