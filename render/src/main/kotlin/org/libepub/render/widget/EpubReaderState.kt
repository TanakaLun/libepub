package org.libepub.render.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.libepub.core.css.WritingMode
import org.libepub.core.layout.ChapterLayout
import org.libepub.core.layout.LayoutSession
import org.libepub.core.layout.PageBox
import org.libepub.core.layout.BoxKind
import org.libepub.core.layout.FragmentKind
import org.libepub.core.layout.PageSlice
import org.libepub.core.layout.Paginator
import org.libepub.core.layout.PagedChapter
import org.libepub.core.model.Document
import org.libepub.core.model.Locator
import org.libepub.core.semantics.Footnote
import org.libepub.core.semantics.ImageGroup
import org.libepub.core.semantics.ImageGroupKind
import org.libepub.core.semantics.ImageRef
import org.libepub.core.xhtml.DocNode
import org.libepub.render.draw.PageDrawer
import org.libepub.render.draw.ReaderTheme
import org.libepub.render.image.ImageLoader

/** Paged (one screen at a time) or continuous vertical scrolling. */
enum class ReaderMode { PAGED, SCROLL }

/** A duokan image group opened in the gallery viewer. */
data class GalleryState(val images: List<ImageRef>, val index: Int)

/**
 * Reader state shared by the pager, the scroll view, the footnote popup and the
 * settings panel. Holds the layout session, the viewport, the reader settings
 * and every transient UI state (current page, popup, gallery, zoom).
 *
 * All geometry that affects layout is part of [settings], so changing the font
 * size or writing direction produces a fresh cache entry instead of a stale
 * layout; [setFontSize] additionally keeps the reading position proportional.
 */
class EpubReaderState(
    val session: LayoutSession,
    val drawer: PageDrawer,
    private val imageLoader: ImageLoader? = null,
) {
    var density: Density = Density(1f)

    var mode by mutableStateOf(ReaderMode.PAGED)
    var fontSizeSp by mutableStateOf(18f)
    var writingMode by mutableStateOf(WritingMode.HORIZONTAL_TB)
    var themeKey by mutableStateOf(ReaderTheme.PAPER.key)

    /**
     * Host-supplied palette, e.g. from the app's own design system. When set it
     * wins over the [themeKey] presets; set it back to null to return to them.
     * Only colors are involved, so switching it never re-lays out the text.
     */
    var customTheme: ReaderTheme? by mutableStateOf(null)

    var spineIndex by mutableStateOf(0)
    var pageIndex by mutableStateOf(0)

    /** Block offset a scroll view should jump to (set by TOC/anchor navigation). */
    var scrollTarget by mutableStateOf<Float?>(null)

    var viewportWidthPx by mutableStateOf(0f)
    var viewportHeightPx by mutableStateOf(0f)

    /**
     * Chrome + system-bar reserves, in layout px, for all four edges. The host
     * sets these when its bars overlay the full-bleed reading surface.
     *
     * Layout happens inside this safe rectangle; the renderer translates the
     * page by (left, top). Paged mode subtracts the same reserves from the page
     * box; scroll mode keeps the full viewport and appends the reserves as blank
     * space at the start/end of the chapter, so text never hides under chrome.
     */
    var contentInsetLeftPx by mutableStateOf(0f)
    var contentInsetTopPx by mutableStateOf(0f)
    var contentInsetRightPx by mutableStateOf(0f)
    var contentInsetBottomPx by mutableStateOf(0f)

    var footnote by mutableStateOf<Footnote?>(null)
    var footnoteLayout by mutableStateOf<ChapterLayout?>(null)
    var footnoteWidthPx by mutableStateOf(0f)

    var gallery by mutableStateOf<GalleryState?>(null)
    var zoomedImage by mutableStateOf<String?>(null)
    var zoomScale by mutableStateOf(1f)

    private val imageCache = mutableStateMapOf<String, ImageBitmap>()

    val theme: ReaderTheme get() = customTheme ?: ReaderTheme.of(themeKey)

    val spineSize: Int get() = session.book.readableSpine.size

    val currentHref: String?
        get() = session.book.readableSpine.getOrNull(spineIndex)?.href

    /** Width of the safe content rectangle (viewport minus left/right chrome). */
    val contentWidthPx: Float
        get() = ((viewportWidthPx.takeIf { it > 1f } ?: DEFAULT_WIDTH_PX) -
            contentInsetLeftPx - contentInsetRightPx).coerceAtLeast(1f)

    /** Height of the safe content rectangle (viewport minus top/bottom chrome). */
    val contentHeightPx: Float
        get() = ((viewportHeightPx.takeIf { it > 1f } ?: DEFAULT_HEIGHT_PX) -
            contentInsetTopPx - contentInsetBottomPx).coerceAtLeast(1f)

    val settings: LayoutSession.Settings
        get() = LayoutSession.Settings(
            fontSizePx = with(density) { fontSizeSp.sp.toPx() },
            widthPx = contentWidthPx,
            heightPx = contentHeightPx,
            writingMode = writingMode,
            theme = themeKey,
        )

    val pageBox: PageBox
        get() = PageBox(settings.widthPx, settings.heightPx)

    // ------------------------------------------------------------- viewport

    fun setViewport(widthPx: Float, heightPx: Float) {
        if (widthPx <= 1f || heightPx <= 1f) return
        if (widthPx == viewportWidthPx && heightPx == viewportHeightPx) return
        val block = currentPage()?.startBlock ?: 0f
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        restorePageAtBlock(block)
    }

    /**
     * Re-derives [pageIndex] from a block position after the geometry changed.
     * Block-based restore (rather than a page ratio) keeps the same paragraph on
     * screen when the viewport rotates or the font size changes.
     */
    private fun restorePageAtBlock(block: Float) {
        val paged = pagedChapter() ?: return
        if (paged.pageCount == 0) return
        pageIndex = paged.pageAt(block).coerceIn(0, paged.pageCount - 1)
    }

    // ------------------------------------------------------------- chapters

    fun chapterLayout(): ChapterLayout? {
        val href = currentHref ?: return null
        return session.layout(href, settings)
    }

    fun currentDocument(): Document? {
        val href = currentHref ?: return null
        return session.book.chapter(href)
    }

    fun pagedChapter(): PagedChapter? {
        val href = currentHref ?: return null
        val layout = session.layout(href, settings)
        // The chapter's own top margin lives inside the flow; reserve it so the
        // shifted page never overflows the bottom of the safe rectangle.
        val chapterTop = if (hasVisibleText(layout)) chapterTop(layout) else 0f
        val extent = (settings.heightPx - chapterTop).coerceAtLeast(1f)
        return Paginator().paginate(layout, PageBox(settings.widthPx, extent))
    }

    fun pageCount(): Int = pagedChapter()?.pageCount ?: 0

    fun currentPage(): PageSlice? = pagedChapter()?.pages?.getOrNull(pageIndex)

    /**
     * Block offset the renderer subtracts from absolute layout coordinates.
     *
     * Text pages simply start at their own first line. Pages without any text
     * (covers, illustration plates) are centered on the page instead of hugging
     * the top edge, whether the art is a block box or an inline image.
     */
    fun pageBlockOffset(page: PageSlice, layout: ChapterLayout): Float {
        var hasText = false
        val tops = ArrayList<Float>(4)
        val bottoms = ArrayList<Float>(4)

        for (index in page.startLine until page.endLine) {
            val line = layout.lines.getOrNull(index) ?: continue
            if (line.fragments.any { it.kind == FragmentKind.TEXT && !it.text.isNullOrBlank() }) {
                hasText = true
                break
            }
            for (fragment in line.fragments) {
                if (fragment.kind != FragmentKind.IMAGE) continue
                tops.add(fragment.block)
                bottoms.add(fragment.block + fragment.blockSize)
            }
        }
        if (hasText) {
            // Keep the chapter's own top margin on every page; the host chrome
            // is applied as a draw translation by the pager.
            return page.startBlock - chapterTop(layout)
        }

        for (box in layout.boxes) {
            if (box.kind != BoxKind.IMAGE) continue
            if (box.block + box.blockSize < page.startBlock || box.block > page.endBlock) continue
            tops.add(box.block)
            bottoms.add(box.block + box.blockSize)
        }
        if (tops.isEmpty()) return page.startBlock

        val topAbsolute = tops.min()
        val contentHeight = bottoms.max() - topAbsolute
        val extent = Paginator().blockExtent(layout, pageBox)
        val topInPage = topAbsolute - page.startBlock
        val inset = ((extent - contentHeight) / 2f - topInPage).coerceAtLeast(0f)
        return page.startBlock - inset
    }

    /** Block coordinate where the chapter's first line starts (body margin/padding). */
    fun chapterTop(layout: ChapterLayout): Float =
        layout.lines.firstOrNull()?.block ?: 0f

    /** True when the chapter has at least one non-blank text line. */
    fun hasVisibleText(layout: ChapterLayout): Boolean =
        layout.lines.any { line ->
            line.fragments.any { it.kind == FragmentKind.TEXT && !it.text.isNullOrBlank() }
        }

    // -------------------------------------------------------------- paging

    fun nextPage(): Boolean {
        val paged = pagedChapter() ?: return false
        if (pageIndex + 1 < paged.pageCount) {
            pageIndex++
            return true
        }
        return nextChapter()
    }

    fun previousPage(): Boolean {
        if (pageIndex > 0) {
            pageIndex--
            return true
        }
        return previousChapter()
    }

    fun nextChapter(): Boolean {
        if (spineIndex + 1 >= spineSize) return false
        spineIndex++
        pageIndex = 0
        scrollTarget = 0f
        return true
    }

    fun previousChapter(): Boolean {
        if (spineIndex <= 0) return false
        spineIndex--
        pageIndex = 0
        scrollTarget = 0f
        return true
    }

    fun goToSpine(index: Int) {
        if (index !in 0 until spineSize) return
        spineIndex = index
        pageIndex = 0
        scrollTarget = 0f
    }

    fun goToHref(href: String, fragment: String? = null) {
        val index = session.book.readableSpine.indexOfFirst { it.href == href }
        if (index < 0) return
        spineIndex = index
        pageIndex = 0
        if (fragment != null) {
            goToAnchor(fragment)
        } else {
            scrollTarget = 0f
        }
    }

    /** Jump to an anchor in the current chapter (TOC fragment or footnote backlink). */
    fun goToAnchor(id: String): Boolean {
        val layout = chapterLayout() ?: return false
        val anchor = layout.anchors[id] ?: return false
        val paged = pagedChapter()
        pageIndex = paged?.pageForAnchor(id) ?: 0
        scrollTarget = anchor.block
        return true
    }

    // ------------------------------------------------------------- settings

    fun setFontSize(value: Float) {
        val clamped = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        if (clamped == fontSizeSp) return
        val block = currentPage()?.startBlock ?: 0f
        fontSizeSp = clamped
        restorePageAtBlock(block)
    }

    fun toggleWritingMode() {
        writingMode = when (writingMode) {
            WritingMode.HORIZONTAL_TB -> WritingMode.VERTICAL_RL
            WritingMode.VERTICAL_RL -> WritingMode.VERTICAL_LR
            WritingMode.VERTICAL_LR -> WritingMode.HORIZONTAL_TB
        }
        pageIndex = 0
        scrollTarget = 0f
    }

    fun setTheme(key: String) {
        themeKey = ReaderTheme.of(key).key
    }

    // ------------------------------------------------------------- progress

    /** Current position, stable across font/viewport changes. */
    fun locator(): Locator? {
        val href = currentHref ?: return null
        val page = currentPage()
        return Locator(
            href = href,
            block = page?.startBlock ?: 0f,
            pageIndex = pageIndex,
        )
    }

    /** Restores a saved position; the page is re-derived from the saved block. */
    fun restore(locator: Locator) {
        val index = session.book.readableSpine.indexOfFirst { it.href == locator.href }
        if (index >= 0) {
            spineIndex = index
            pageIndex = locator.pageIndex.coerceAtLeast(0)
            scrollTarget = locator.block
        }
    }

    /** Fraction of the book read, in `0..1`. */
    fun progress(): Float {
        if (spineSize == 0) return 0f
        val count = pageCount()
        val within = if (count > 0) (pageIndex + 1f) / count else 0f
        return ((spineIndex + within) / spineSize).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------- footnotes

    /**
     * Opens the popup for [marker]. The content is laid out with the same
     * engine and stylesheets as the page, at 84% of the viewport width.
     */
    fun openFootnote(marker: DocNode): Boolean {
        val document = currentDocument() ?: return false
        val entry = document.footnoteFor(marker) ?: return false
        val target = entry.target ?: return false
        val width = (settings.widthPx * FOOTNOTE_WIDTH_RATIO).coerceAtLeast(MIN_FOOTNOTE_WIDTH_PX)
        footnoteWidthPx = width
        footnoteLayout = session.layoutFragment(
            document = document,
            root = target,
            settings = settings.copy(widthPx = width, heightPx = settings.heightPx),
        )
        footnote = entry
        return true
    }

    fun closeFootnote() {
        footnote = null
        footnoteLayout = null
    }

    // -------------------------------------------------------- images/gallery

    fun imageFor(src: String): ImageBitmap? = imageCache[src]

    /** Decodes the images visible on the current page (or scroll slice). */
    suspend fun preloadVisibleImages() {
        val loader = imageLoader ?: return
        val layout = chapterLayout() ?: return
        val slice = if (mode == ReaderMode.PAGED) {
            currentPage() ?: return
        } else {
            PageSlice(0, 0, layout.lines.size, 0f, layout.contentBlockSize)
        }
        val targets = LinkedHashMap<String, Float>()
        for (index in slice.startLine until slice.endLine) {
            val line = layout.lines.getOrNull(index) ?: continue
            for (fragment in line.fragments) {
                val src = fragment.src ?: continue
                targets.putIfAbsent(src, fragment.mainSize)
            }
        }
        for (box in layout.boxes) {
            if (box.kind != org.libepub.core.layout.BoxKind.IMAGE) continue
            if (box.block + box.blockSize < slice.startBlock || box.block > slice.endBlock) continue
            val src = box.src ?: continue
            targets.putIfAbsent(src, box.mainSize)
        }
        for ((src, width) in targets) {
            if (imageCache.containsKey(src)) continue
            val bitmap = loader.load(src, width.toInt()) ?: continue
            imageCache[src] = bitmap
        }
    }

    fun clearImageCache() {
        imageCache.clear()
        imageLoader?.clear()
    }

    /**
     * Handles a tap on an image: a multi-image duokan gallery opens the gallery
     * viewer, a single illustration opens the full-screen zoom container.
     */
    /** Follows an internal hyperlink (book TOC page, cross references). */
    fun openLink(href: String, fragment: String?) {
        if (href == currentHref) {
            if (fragment != null) {
                goToAnchor(fragment)
            } else {
                pageIndex = 0
                scrollTarget = 0f
            }
        } else {
            goToHref(href, fragment)
        }
    }

    fun openImage(src: String) {
        val group = currentDocument()?.imageGroups?.firstOrNull { candidate ->
            candidate.images.any { it.src == src }
        }
        if (group != null && group.kind != ImageGroupKind.CELL && group.images.size > 1) {
            gallery = GalleryState(group.images, group.images.indexOfFirst { it.src == src }.coerceAtLeast(0))
        } else {
            zoomScale = 1f
            zoomedImage = src
        }
    }

    fun closeGallery() {
        gallery = null
    }

    fun closeZoom() {
        zoomedImage = null
        zoomScale = 1f
    }

    /** Footnote content element, when the popup is open. */
    val footnoteTarget: DocNode? get() = footnote?.target

    private companion object {
        const val DEFAULT_WIDTH_PX = 400f
        const val DEFAULT_HEIGHT_PX = 800f
        const val MIN_FONT_SP = 10f
        const val MAX_FONT_SP = 40f
        const val FOOTNOTE_WIDTH_RATIO = 0.84f
        const val MIN_FOOTNOTE_WIDTH_PX = 200f
    }
}
