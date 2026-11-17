package org.libepub.core.xhtml

enum class NodeKind { ELEMENT, TEXT }

/**
 * One node of a parsed XHTML document. Element [name]s are kept exactly as
 * written (namespace processing is off, so a name may carry a prefix such as
 * `epub:marker`); [attributes] keys are likewise the raw attribute names.
 */
class DocNode(
    val kind: NodeKind,
    val name: String = "",
    text: String = "",
    val attributes: Map<String, String> = emptyMap(),
) {
    internal val childList = ArrayList<DocNode>(4)

    var parent: DocNode? = null
        internal set

    /** TEXT node content; adjacent text is coalesced during parsing. */
    var text: String = text
        internal set

    val children: List<DocNode> get() = childList

    val isElement: Boolean get() = kind == NodeKind.ELEMENT

    /** Attribute by the exact name as written (`epub:type`, `xml:lang`, ...). */
    fun attr(name: String): String? = attributes[name]

    /** Exact match first, then any `prefix:local` suffix (namespace-tolerant). */
    fun attrLocal(local: String): String? {
        attributes[local]?.let { return it }
        for ((key, value) in attributes) {
            if (key.substringAfterLast(':') == local) return value
        }
        return null
    }

    /** Inline `style=""` declarations, parsed on first access (null when absent). */
    val inlineStyle: List<StyleDecl>? by lazy {
        attributes["style"]?.let { InlineStyleAttr.parse(it) }
    }

    fun walk(): Sequence<DocNode> = sequence {
        yield(this@DocNode)
        for (child in childList) yieldAll(child.walk())
    }

    fun findAll(name: String): List<DocNode> =
        walk().filter { it.kind == NodeKind.ELEMENT && it.name == name }.toList()

    fun firstOrNull(name: String): DocNode? =
        walk().firstOrNull { it.kind == NodeKind.ELEMENT && it.name == name }

    /** Concatenated text of the subtree (TEXT content for a text node). */
    fun textContent(): String = when (kind) {
        NodeKind.TEXT -> text
        NodeKind.ELEMENT -> childList.joinToString("") { it.textContent() }
    }
}
