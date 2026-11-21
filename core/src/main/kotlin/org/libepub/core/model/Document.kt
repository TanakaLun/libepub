package org.libepub.core.model

import org.libepub.core.semantics.Footnote
import org.libepub.core.semantics.ImageGroup
import org.libepub.core.semantics.PageBreak
import org.libepub.core.script.ScriptAudit
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.DocNode

/**
 * A parsed XHTML content document: element tree plus build-time indexes.
 * Layout consumes this; nothing here depends on Android.
 */
class Document(
    /** The `<html>` element (documents without one are rejected at parse time). */
    val root: DocNode,
    /** Zip-root path of the source document. */
    val href: String,
    /** id -> every node carrying it; duplicate ids are preserved, not merged. */
    val ids: Map<String, List<DocNode>>,
    /** Raw CSS text of inline `<style>` elements, in document order. */
    val styleSheets: List<String>,
    /** First `<title>` text, when the document has one. */
    val title: String?,
    val warnings: WarningSink,
    /** Footnote markers paired with their content (duokan/EPUB3 noteref). */
    val footnotes: List<Footnote> = emptyList(),
    /** Duokan image blocks (single illustrations, galleries, standalone cells). */
    val imageGroups: List<ImageGroup> = emptyList(),
    /** `epub:type="pagebreak"` markers in document order. */
    val pageBreaks: List<PageBreak> = emptyList(),
    /** Scripts in the document and how the reader answers them (never executed). */
    val scriptAudit: ScriptAudit = ScriptAudit.NONE,
) {
    /** First node with [id], or null (duplicate ids resolve to the first in document order). */
    fun byId(id: String): DocNode? = ids[id]?.firstOrNull()

    /** Footnote whose marker is [marker] (identity match), if any. */
    fun footnoteFor(marker: DocNode): Footnote? = footnotes.firstOrNull { it.marker === marker }
}
