package org.libepub.core.layout

import org.libepub.core.container.ResourcePath
import org.libepub.core.css.BorderSides
import org.libepub.core.css.CssFloat
import org.libepub.core.css.Display
import org.libepub.core.css.Edges
import org.libepub.core.css.Length
import org.libepub.core.css.LengthUnit
import org.libepub.core.model.Document
import org.libepub.core.style.ComputedStyle
import org.libepub.core.xhtml.DocNode
import org.libepub.core.xhtml.NodeKind

/** Inline-level content collected before line composition. */
internal sealed interface InlineItem {
    data class Text(
        val text: String,
        val style: ComputedStyle,
        val node: DocNode?,
        /** Raw (unresolved) `href` when this run is inside an `<a>`. */
        val link: String? = null,
    ) : InlineItem
    data class HardBreak(val style: ComputedStyle) : InlineItem
    data class Marker(val node: DocNode, val style: ComputedStyle) : InlineItem
    data class Atomic(
        val node: DocNode?,
        val style: ComputedStyle,
        val src: String?,
        val kind: FragmentKind,
        val box: Box? = null,
    ) : InlineItem

    /** `<ruby>`: base runs plus the `rt` annotation text (see RubyLayout). */
    data class Ruby(
        val node: DocNode,
        val baseRuns: List<Text>,
        val annotation: String,
        val annotationStyle: ComputedStyle,
        val style: ComputedStyle,
    ) : InlineItem
}

/** Internal box tree node; [inline] and [children] are mutually exclusive by construction. */
internal class Box(
    val node: DocNode?,
    val style: ComputedStyle,
    val kind: BoxKind,
    val children: MutableList<Box> = ArrayList(),
    val inline: MutableList<InlineItem> = ArrayList(),
    var marker: String? = null,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
)

/**
 * Turns a styled document into a box tree: block boxes, anonymous blocks for
 * mixed inline runs, list markers, table rows/cells and inline atoms
 * (images, inline-blocks). Floated elements are lifted to block level so the
 * float tracker sees them in document order.
 */
internal class BoxTreeBuilder(
    private val document: Document,
    private val styles: Map<DocNode, ComputedStyle>,
) {

    private val baseDir = ResourcePath.parent(document.href)

    fun build(): Box {
        val root = document.root
        val style = styles[root] ?: ComputedStyle.INITIAL
        return buildBlock(root, style)
    }

    private fun buildBlock(node: DocNode, style: ComputedStyle): Box {
        val kind = kindOf(node, style)
        val box = Box(node, style, kind)

        when (kind) {
            BoxKind.TABLE -> buildTable(box, node)
            BoxKind.TABLE_ROW -> buildRow(box, node)
            BoxKind.TABLE_CELL -> buildContainer(box, node, style)
            else -> buildContainer(box, node, style)
        }

        if (node.name == "ol" || node.name == "ul") assignListMarkers(box, node)
        return box
    }

    private fun buildContainer(box: Box, node: DocNode, style: ComputedStyle) {
        for (child in node.children) {
            if (child.kind == NodeKind.TEXT) {
                if (child.text.isNotEmpty()) box.inline.add(InlineItem.Text(child.text, style, child))
                continue
            }
            val childStyle = styles[child] ?: continue
            if (childStyle.isHidden) continue

            if (child.name == "br") {
                box.inline.add(InlineItem.HardBreak(childStyle))
                continue
            }
            if (child.attrLocal("type") == "pagebreak") {
                box.inline.add(InlineItem.Marker(child, childStyle))
                continue
            }

            val childKind = kindOf(child, childStyle)
            // Duokan image wrappers are zoomable block images even though `<img>`
            // is inline by default; promoting them gives the renderer a box to
            // hit-test for the full-screen viewer.
            val blockImage = (child.name == "img" || child.name == "image") && isImageWrapper(node)
            val isBlock = childStyle.isBlockLevel || childKind == BoxKind.TABLE ||
                childKind == BoxKind.TABLE_ROW || childKind == BoxKind.TABLE_CELL || blockImage

            when {
                childStyle.float != CssFloat.NONE -> {
                    wrapInline(box, style)
                    box.children.add(buildBlock(child, childStyle))
                }
                isBlock -> {
                    wrapInline(box, style)
                    box.children.add(buildBlock(child, childStyle))
                }
                else -> collectInlineElement(child, childStyle, box.inline)
            }
        }
        if (box.children.isNotEmpty()) wrapInline(box, style)
    }

    private fun wrapInline(box: Box, style: ComputedStyle) {
        if (box.inline.isEmpty()) return
        val anonymousStyle = style.copy(
            display = Display.BLOCK,
            float = CssFloat.NONE,
            margin = ComputedStyle.ZERO_MARGIN,
            padding = ComputedStyle.ZERO_PADDING,
            border = BorderSides.NONE,
            width = null,
            height = null,
        )
        box.children.add(
            Box(null, anonymousStyle, BoxKind.ANONYMOUS, inline = box.inline.toMutableList()),
        )
        box.inline.clear()
    }

    /** Handles one inline-level element (atom or container) and recurses. */
    private fun collectInlineElement(
        node: DocNode,
        style: ComputedStyle,
        out: MutableList<InlineItem>,
        inheritedLink: String? = null,
    ) {
        // Native equivalent of duokan's notereplace.js: the marker image is
        // replaced by the reader's own footnote text.
        if (isNoteref(node)) {
            out.add(InlineItem.Text(NOTEREF_MARKER, style, node, node.attr("href") ?: inheritedLink))
            return
        }
        // Internal hyperlinks: an `<a href>` keeps its target on every text run
        // inside it, so the renderer can build one tap target per contiguous run.
        val link = if (node.name == "a") node.attr("href") ?: inheritedLink else inheritedLink
        when {
            node.name == "br" -> out.add(InlineItem.HardBreak(style))
            node.attrLocal("type") == "pagebreak" -> out.add(InlineItem.Marker(node, style))
            node.name == "img" || node.name == "image" -> out.add(
                InlineItem.Atomic(
                    node = node,
                    style = style,
                    src = node.attr("src")?.let { ResourcePath.resolve(baseDir, it) },
                    kind = FragmentKind.IMAGE,
                ),
            )
            node.name == "ruby" -> collectRuby(node, style, out)
            style.display == Display.INLINE_BLOCK -> out.add(
                InlineItem.Atomic(
                    node = node,
                    style = style,
                    src = null,
                    kind = FragmentKind.ATOMIC,
                    box = buildBlock(node, style),
                ),
            )
            else -> collectInline(node, style, out, link)
        }
    }

    /** Collects the base text of a `<ruby>` and its `rt` annotation. */
    private fun collectRuby(node: DocNode, style: ComputedStyle, out: MutableList<InlineItem>) {
        val runs = ArrayList<InlineItem.Text>(2)
        collectRubyBase(node, style, runs)
        val annotation = StringBuilder()
        collectRubyAnnotation(node, annotation)
        val annotationStyle = firstRtStyle(node) ?: style.copy(fontSizePx = style.fontSizePx * 0.5f)
        if (annotation.isBlank() || runs.isEmpty()) {
            out.addAll(runs)
            return
        }
        out.add(InlineItem.Ruby(node, runs, annotation.toString(), annotationStyle, style))
    }

    private fun isNoteref(node: DocNode): Boolean {
        if (node.attrLocal("type") == "noteref") return true
        val classes = node.attr("class") ?: return false
        return classes.split(WHITESPACE).any { it == "duokan-footnote" || it == "footnote" }
    }

    private fun isImageWrapper(node: DocNode): Boolean {
        val classes = node.attr("class") ?: return false
        if (classes.isBlank()) return false
        val tokens = classes.split(WHITESPACE)
        return tokens.any { it == "duokan-image-single" || it == "duokan-image-gallery-cell" }
    }

    private fun collectRubyBase(node: DocNode, style: ComputedStyle, out: MutableList<InlineItem.Text>) {
        for (child in node.children) {
            if (child.kind == NodeKind.TEXT) {
                if (child.text.isNotEmpty()) out.add(InlineItem.Text(child.text, style, null))
                continue
            }
            if (child.name == "rt" || child.name == "rp") continue
            val childStyle = styles[child] ?: continue
            if (childStyle.isHidden) continue
            collectRubyBase(child, childStyle, out)
        }
    }

    private fun collectRubyAnnotation(node: DocNode, out: StringBuilder) {
        for (child in node.children) {
            if (child.kind == NodeKind.TEXT) continue
            if (child.name == "rt") {
                out.append(child.textContent())
            } else if (child.name != "rp") {
                collectRubyAnnotation(child, out)
            }
        }
    }

    private fun firstRtStyle(node: DocNode): ComputedStyle? {
        for (child in node.children) {
            if (child.kind != NodeKind.ELEMENT) continue
            if (child.name == "rt") return styles[child]
            firstRtStyle(child)?.let { return it }
        }
        return null
    }

    private fun collectInline(
        node: DocNode,
        style: ComputedStyle,
        out: MutableList<InlineItem>,
        link: String? = null,
    ) {
        for (child in node.children) {
            if (child.kind == NodeKind.TEXT) {
                if (child.text.isNotEmpty()) out.add(InlineItem.Text(child.text, style, null, link))
                continue
            }
            val childStyle = styles[child] ?: continue
            if (childStyle.isHidden) continue
            collectInlineElement(child, childStyle, out, link)
        }
    }

    // ------------------------------------------------------------------ tables

    private fun buildTable(box: Box, node: DocNode) {
        for (child in node.children) {
            if (child.kind != NodeKind.ELEMENT) continue
            val childStyle = styles[child] ?: continue
            if (childStyle.isHidden) continue
            when (child.name) {
                "tr" -> box.children.add(buildBlock(child, childStyle))
                "thead", "tbody", "tfoot" -> {
                    for (row in child.children) {
                        if (row.kind != NodeKind.ELEMENT || row.name != "tr") continue
                        val rowStyle = styles[row] ?: continue
                        if (rowStyle.isHidden) continue
                        box.children.add(buildBlock(row, rowStyle))
                    }
                }
            }
        }
    }

    private fun buildRow(box: Box, node: DocNode) {
        for (child in node.children) {
            if (child.kind != NodeKind.ELEMENT) continue
            if (child.name != "td" && child.name != "th") continue
            val childStyle = styles[child] ?: continue
            if (childStyle.isHidden) continue
            val cell = buildBlock(child, childStyle)
            box.children.add(
                Box(
                    node = cell.node,
                    style = cell.style,
                    kind = BoxKind.TABLE_CELL,
                    children = cell.children,
                    inline = cell.inline,
                    colSpan = child.attr("colspan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    rowSpan = child.attr("rowspan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                ),
            )
        }
    }

    // ------------------------------------------------------------- list markers

    private fun assignListMarkers(listBox: Box, listNode: DocNode) {
        val ordered = listNode.name == "ol"
        var counter = listNode.attr("start")?.toIntOrNull() ?: 1
        val type = listBox.style.listStyleType ?: typeAttribute(listNode.attr("type")) ?: if (ordered) "decimal" else "disc"
        for (child in listBox.children) {
            if (child.kind != BoxKind.LIST_ITEM) continue
            val itemType = child.style.listStyleType ?: type
            val value = child.node?.attr("value")?.toIntOrNull()
            if (value != null) counter = value
            child.marker = if (itemType == "none") null else markerText(itemType, counter)
            counter++
        }
    }

    private fun typeAttribute(value: String?): String? = when (value) {
        "1" -> "decimal"
        "a" -> "lower-alpha"
        "A" -> "upper-alpha"
        "i" -> "lower-roman"
        "I" -> "upper-roman"
        else -> null
    }

    private fun markerText(type: String, index: Int): String = when (type) {
        "disc" -> "\u2022"
        "circle" -> "\u25E6"
        "square" -> "\u25AA"
        "decimal" -> "$index."
        "decimal-leading-zero" -> index.toString().padStart(2, '0') + "."
        "lower-alpha" -> alpha(index).lowercase() + "."
        "upper-alpha" -> alpha(index).uppercase() + "."
        "lower-roman" -> roman(index).lowercase() + "."
        "upper-roman" -> roman(index).uppercase() + "."
        "lower-latin" -> alpha(index).lowercase() + "."
        "upper-latin" -> alpha(index).uppercase() + "."
        "lower-greek" -> alpha(index).lowercase() + "."
        "none" -> ""
        else -> "$index."
    }

    private fun alpha(index: Int): String {
        if (index <= 0) return "?"
        val builder = StringBuilder()
        var value = index
        while (value > 0) {
            val remainder = (value - 1) % 26
            builder.insert(0, ('a' + remainder))
            value = (value - 1) / 26
        }
        return builder.toString()
    }

    private fun roman(index: Int): String {
        if (index <= 0 || index >= 4000) return index.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val builder = StringBuilder()
        var remaining = index
        for (position in values.indices) {
            while (remaining >= values[position]) {
                builder.append(symbols[position])
                remaining -= values[position]
            }
        }
        return builder.toString()
    }

    // ------------------------------------------------------------------- kinds

    private val WHITESPACE = Regex("\\s+")

    private val NOTEREF_MARKER = "【注】"

    private fun kindOf(node: DocNode, style: ComputedStyle): BoxKind = when {
        node.name == "img" || node.name == "image" -> BoxKind.IMAGE
        node.name == "hr" -> BoxKind.HR
        node.name == "table" -> BoxKind.TABLE
        node.name == "tr" -> BoxKind.TABLE_ROW
        node.name == "td" || node.name == "th" -> BoxKind.TABLE_CELL
        style.display == Display.LIST_ITEM -> BoxKind.LIST_ITEM
        style.display == Display.INLINE_BLOCK -> BoxKind.INLINE_BLOCK
        else -> BoxKind.BLOCK
    }
}
