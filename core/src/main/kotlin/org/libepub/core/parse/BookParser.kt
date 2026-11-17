package org.libepub.core.parse

import org.libepub.core.container.EpubContainer
import org.libepub.core.container.PackageParser
import org.libepub.core.container.ResourcePath
import org.libepub.core.css.CssParser
import org.libepub.core.css.Stylesheet
import org.libepub.core.model.Document
import org.libepub.core.model.EpubMetadata
import org.libepub.core.model.EpubPackage
import org.libepub.core.model.SpineItem
import org.libepub.core.toc.Toc
import org.libepub.core.toc.TocParser
import org.libepub.core.util.LruStore
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.XhtmlParser
import java.io.Closeable
import java.io.File

/**
 * Book-level parsing facade: open a container, read the package document and
 * navigation once, then hand out chapters and their stylesheets on demand.
 *
 * Parsed documents and stylesheets are kept in small LRU stores, so flipping
 * back and forth between two chapters does not re-parse or re-cascade them.
 * Layout lives one layer up ([org.libepub.core.layout.LayoutSession]) so this
 * class stays usable for search, TOC previews and metadata screens that never
 * lay anything out.
 */
class BookParser private constructor(
    val container: EpubContainer,
    val packageDoc: EpubPackage,
    val toc: Toc,
    private val documentCache: LruStore<String, Document> = LruStore(DOCUMENT_CACHE_SIZE),
    private val sheetCache: LruStore<String, List<Stylesheet>> = LruStore(SHEET_CACHE_SIZE),
) : Closeable {

    val warnings: WarningSink get() = container.warnings

    val metadata: EpubMetadata get() = packageDoc.metadata

    val spine: List<SpineItem> get() = packageDoc.spine

    /** Spine entries whose manifest href could be resolved. */
    val readableSpine: List<SpineItem> get() = spine.filter { it.href != null }

    /** Parses (or returns the cached) content document at [href]. */
    fun chapter(href: String): Document {
        documentCache.get(href)?.let { return it }
        val document = XhtmlParser.parse(container.readText(href), href, warnings)
        documentCache.put(href, document)
        return document
    }

    /**
     * Stylesheets that apply to [document], in document order: `<link
     * rel=stylesheet>` targets (relative to the document, so per-chapter
     * overrides work) followed by inline `<style>` blocks. Remote URLs are
     * ignored with a warning — the reader never touches the network.
     */
    fun stylesheetsFor(document: Document): List<Stylesheet> {
        sheetCache.get(document.href)?.let { return it }
        val baseDir = ResourcePath.parent(document.href)
        val sheets = ArrayList<Stylesheet>(4)

        for (node in document.root.walk()) {
            if (!node.isElement || node.name != "link") continue
            val rel = node.attr("rel") ?: continue
            if (rel.split(WHITESPACE).none { it.equals("stylesheet", ignoreCase = true) }) continue
            val raw = node.attr("href")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (raw.contains("://")) {
                warnings.warnOnce("css.remote:$raw", "remote stylesheet ignored: $raw")
                continue
            }
            val href = ResourcePath.resolve(baseDir, raw)
            val css = runCatching { container.readText(href) }.getOrElse {
                warnings.warnOnce("css.missing:$href", "missing stylesheet: $href")
                continue
            }
            sheets.add(CssParser.parse(css, ResourcePath.parent(href), warnings))
        }

        for (inline in document.styleSheets) {
            sheets.add(CssParser.parse(inline, baseDir, warnings))
        }

        sheetCache.put(document.href, sheets)
        return sheets
    }

    override fun close() = container.close()

    companion object {
        private const val DOCUMENT_CACHE_SIZE = 6
        private const val SHEET_CACHE_SIZE = 8
        private val WHITESPACE = Regex("\\s+")

        fun open(file: File): BookParser = open(EpubContainer.open(file))

        /** Takes ownership of [container] (closing the parser closes it). */
        fun open(container: EpubContainer): BookParser {
            val pkg = PackageParser(container).parse()
            val toc = TocParser(container, pkg).parse()
            return BookParser(container, pkg, toc)
        }
    }
}
