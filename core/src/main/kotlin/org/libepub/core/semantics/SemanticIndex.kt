package org.libepub.core.semantics

import org.libepub.core.container.ResourcePath
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.DocNode
import org.libepub.core.xhtml.NodeKind

/**
 * Single-pass semantic indexer over a parsed document tree: footnotes
 * (duokan + EPUB3 noteref), duokan image blocks, and printed page breaks.
 *
 * Duplicate ids are expected in real books (the duokan aside and its list item
 * both carry `id="n1"`; every noteref in a chapter may carry `id="A_1"`), so
 * pairing resolves targets through the id index with a preference for the
 * footnote element and never assumes ids are unique.
 */
object SemanticIndex {

    fun build(
        root: DocNode,
        href: String,
        ids: Map<String, List<DocNode>>,
        warnings: WarningSink,
    ): SemanticIndexResult = SemanticIndexResult(
        footnotes = buildFootnotes(root, ids, warnings),
        imageGroups = buildImageGroups(root, href, warnings),
        pageBreaks = buildPageBreaks(root),
    )

    // ------------------------------------------------------------- footnotes

    private fun buildFootnotes(
        root: DocNode,
        ids: Map<String, List<DocNode>>,
        warnings: WarningSink,
    ): List<Footnote> {
        val out = ArrayList<Footnote>(4)
        for (node in root.walk()) {
            if (node.kind != NodeKind.ELEMENT || !isNoteref(node)) continue
            val href = node.attr("href") ?: continue
            val fragment = href.substringAfter('#', "").ifEmpty { null }
            if (fragment == null) {
                warnings.warnOnce("sem.noteref-href", "noteref without fragment: $href")
                continue
            }
            val candidates = ids[fragment].orEmpty()
            val target = candidates.firstOrNull { it.attrLocal("type") == "footnote" }
                ?: candidates.firstOrNull()
            if (target == null) {
                warnings.warnOnce("sem.noteref:$fragment", "noteref #$fragment has no target in document")
            }
            out.add(
                Footnote(
                    marker = node,
                    target = target,
                    text = target?.textContent()?.trim().orEmpty(),
                    markerText = node.textContent().trim().ifEmpty { DEFAULT_MARKER },
                    refId = fragment,
                ),
            )
        }
        return out
    }

    private fun isNoteref(node: DocNode): Boolean =
        node.attrLocal("type") == "noteref" || DUOKAN_NOTEREF_CLASS in node.classTokens()

    // ----------------------------------------------------------- image blocks

    private fun buildImageGroups(root: DocNode, href: String, warnings: WarningSink): List<ImageGroup> {
        val baseDir = ResourcePath.parent(href)
        val containers = ArrayList<Pair<DocNode, ImageGroupKind>>(4)
        for (node in root.walk()) {
            if (node.kind != NodeKind.ELEMENT) continue
            val tokens = node.classTokens()
            when {
                GALLERY_CLASS in tokens -> containers.add(node to ImageGroupKind.GALLERY)
                SINGLE_CLASS in tokens -> containers.add(node to ImageGroupKind.SINGLE)
                CELL_CLASS in tokens -> containers.add(node to ImageGroupKind.CELL)
            }
        }
        if (containers.isEmpty()) return emptyList()

        val containerNodes = containers.mapTo(HashSet(containers.size)) { it.first }
        val out = ArrayList<ImageGroup>(containers.size)
        for ((container, kind) in containers) {
            // Nested containers are covered by their outermost block.
            if (hasAncestorIn(container, containerNodes)) continue
            val images = container.walk()
                .filter { it.kind == NodeKind.ELEMENT && it.name == "img" }
                .map { img ->
                    val rawSrc = img.attr("src")
                    val src = if (rawSrc == null) {
                        warnings.warnOnce("sem.img-src", "image without src in $href")
                        ""
                    } else {
                        ResourcePath.resolve(baseDir, rawSrc)
                    }
                    ImageRef(src = src, alt = img.attr("alt"), node = img)
                }
                .toList()
            if (images.isEmpty()) {
                warnings.warnOnce("sem.img-empty", "image container without <img> in $href")
                continue
            }
            out.add(ImageGroup(container = container, kind = kind, images = images))
        }
        return out
    }

    private fun hasAncestorIn(node: DocNode, candidates: Set<DocNode>): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (parent in candidates) return true
            parent = parent.parent
        }
        return false
    }

    // ------------------------------------------------------------ page breaks

    private fun buildPageBreaks(root: DocNode): List<PageBreak> =
        root.walk()
            .filter { it.kind == NodeKind.ELEMENT && it.attrLocal("type") == "pagebreak" }
            .map { node ->
                PageBreak(
                    node = node,
                    label = node.attr("title")?.takeIf { it.isNotBlank() }
                        ?: node.attr("aria-label")?.takeIf { it.isNotBlank() }
                        ?: node.textContent().trim().takeIf { it.isNotEmpty() }
                        ?: node.attr("id"),
                    id = node.attr("id"),
                )
            }
            .toList()

    // ---------------------------------------------------------------- shared

    private fun DocNode.classTokens(): Set<String> {
        val raw = attr("class") ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(WHITESPACE).filterTo(LinkedHashSet()) { it.isNotEmpty() }
    }

    private const val DEFAULT_MARKER = "【注】"
    private const val DUOKAN_NOTEREF_CLASS = "duokan-footnote"
    private const val SINGLE_CLASS = "duokan-image-single"
    private const val GALLERY_CLASS = "duokan-image-gallery"
    private const val CELL_CLASS = "duokan-image-cell"
    private val WHITESPACE = Regex("\\s+")
}
