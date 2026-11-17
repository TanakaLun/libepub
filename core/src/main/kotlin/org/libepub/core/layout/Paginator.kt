package org.libepub.core.layout

import org.libepub.core.css.WritingMode

/** Physical page box (the reader's content area, insets already removed). */
data class PageBox(val widthPx: Float, val heightPx: Float)

/**
 * One page of a chapter: a half-open range of [ChapterLayout.lines] plus the
 * logical block range it covers. Lines are never split across pages — a line
 * taller than the page gets a page of its own — so page boundaries always fall
 * on line boundaries and the line's own width is preserved.
 */
data class PageSlice(
    val index: Int,
    val startLine: Int,
    val endLine: Int,
    val startBlock: Float,
    val endBlock: Float,
    /** True when the page starts because of a `page-break-before/after: always`. */
    val forced: Boolean = false,
) {
    val lineCount: Int get() = endLine - startLine

    fun containsLine(lineIndex: Int): Boolean = lineIndex in startLine until endLine
}

/** A chapter sliced into pages; the pager only needs this plus the layout. */
data class PagedChapter(val layout: ChapterLayout, val pages: List<PageSlice>) {

    val pageCount: Int get() = pages.size

    fun pageAt(block: Float): Int {
        if (pages.isEmpty()) return -1
        var low = 0
        var high = pages.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pages[mid].startBlock <= block + 0.01f) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    fun pageForLine(lineIndex: Int): Int =
        pages.indexOfFirst { it.containsLine(lineIndex) }.coerceAtLeast(0)

    fun pageForAnchor(id: String): Int? {
        val anchor = layout.anchors[id] ?: return null
        val line = layout.lineIndexAt(anchor.block)
        if (line < 0) return null
        return pageForLine(line)
    }

    /** Printed-page labels whose break point falls on [pageIndex]. */
    fun labelsOnPage(pageIndex: Int): List<String> {
        val page = pages.getOrNull(pageIndex) ?: return emptyList()
        return layout.pageBreaks
            .filter { it.label != null && it.block >= page.startBlock - 0.01f && it.block < page.endBlock - 0.01f }
            .mapNotNull { it.label }
    }
}

/**
 * Line-boundary paginator shared by horizontal and vertical reading.
 *
 * In horizontal writing the block axis is vertical, so a page holds
 * `heightPx` worth of lines. In vertical writing lines stack horizontally, so
 * a page holds `floor(widthPx / lineAdvance)` columns; page extent is
 * quantized to whole columns so a column is never cut in half.
 */
class Paginator {

    fun paginate(layout: ChapterLayout, page: PageBox): PagedChapter {
        val lines = layout.lines
        if (lines.isEmpty()) {
            // Image-only chapters (covers, illustration pages) have boxes but no
            // line boxes; they still need one page so the renderer can draw them.
            return if (layout.contentBlockSize > 0f) {
                PagedChapter(
                    layout,
                    listOf(PageSlice(0, 0, 0, 0f, layout.contentBlockSize)),
                )
            } else {
                PagedChapter(layout, emptyList())
            }
        }

        val extent = blockExtent(layout, page)
        val breaks = layout.pageBreaks.map { it.block }.sorted()
        val pages = ArrayList<PageSlice>(lines.size / 4 + 1)

        var index = 0
        var pendingForced = false
        while (index < lines.size) {
            val startLine = index
            val startBlock = lines[startLine].block
            val limit = startBlock + extent
            var end = startLine + 1
            var forcedNext = false

            while (end < lines.size) {
                val previous = lines[end - 1]
                val line = lines[end]
                val forcedHere = breaks.any { it > previous.block + 0.01f && it <= line.block + 0.01f }
                if (forcedHere) {
                    forcedNext = true
                    break
                }
                if (line.block + line.blockSize > limit + 0.01f) break
                end++
            }

            val last = lines[end - 1]
            pages.add(
                PageSlice(
                    index = pages.size,
                    startLine = startLine,
                    endLine = end,
                    startBlock = startBlock,
                    endBlock = last.block + last.blockSize,
                    forced = pendingForced,
                ),
            )
            pendingForced = forcedNext
            index = end
        }
        return PagedChapter(layout, pages)
    }

    /** Block-axis extent of one page (whole columns in vertical writing). */
    fun blockExtent(layout: ChapterLayout, page: PageBox): Float =
        when (layout.writingMode) {
            WritingMode.HORIZONTAL_TB -> page.heightPx
            else -> {
                val advance = layout.lines.maxOfOrNull { it.blockSize }?.takeIf { it > 0f }
                    ?: page.widthPx
                val columns = (page.widthPx / advance).toInt().coerceAtLeast(1)
                columns * advance
            }
        }
}
