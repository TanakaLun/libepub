package org.libepub.core.semantics

import org.libepub.core.xhtml.DocNode

/**
 * A footnote reference (in the body flow) paired with the footnote content it
 * points at. Duokan books use `a.duokan-footnote[epub:type=noteref]` whose
 * `notereplace.js` rewrote the reference image into the marker "【注】"; we do
 * the same natively via [markerText].
 */
data class Footnote(
    /** The noteref anchor. */
    val marker: DocNode,
    /** The footnote content element (`aside[epub:type=footnote]`), null when dangling. */
    val target: DocNode?,
    /** Plain text of the footnote content. */
    val text: String,
    /** What the reader displays in place of the marker image, e.g. "【注】". */
    val markerText: String,
    /** Fragment id referenced by the marker. */
    val refId: String,
)

/** One image inside a duokan image container, with its zip-root-resolved src. */
data class ImageRef(
    val src: String,
    val alt: String?,
    val node: DocNode,
)

enum class ImageGroupKind { SINGLE, GALLERY, CELL }

/** A duokan image block: single illustration, gallery, or standalone cell. */
data class ImageGroup(
    val container: DocNode,
    val kind: ImageGroupKind,
    val images: List<ImageRef>,
)

/** An `epub:type="pagebreak"` marker (printed page label). */
data class PageBreak(
    val node: DocNode,
    val label: String?,
    val id: String?,
)

/** Everything the semantic pass collected for one document. */
data class SemanticIndexResult(
    val footnotes: List<Footnote>,
    val imageGroups: List<ImageGroup>,
    val pageBreaks: List<PageBreak>,
) {
    companion object {
        val EMPTY = SemanticIndexResult(emptyList(), emptyList(), emptyList())
    }
}
