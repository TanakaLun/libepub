package org.libepub.core.layout

import org.libepub.core.css.Display
import org.libepub.core.css.WritingMode
import org.libepub.core.model.Document
import org.libepub.core.xhtml.DocNode
import org.libepub.core.parse.BookParser
import org.libepub.core.style.StyleResolver
import org.libepub.core.util.WarningSink
import java.io.Closeable

/**
 * One reader session: a parsed book plus a measurer, an image-size provider
 * and a layout cache. Everything the UI needs is keyed by [Settings], so a
 * font-size or orientation change produces a different cache entry instead of
 * corrupting the previous layout.
 *
 * Typical use:
 * ```
 * LayoutSession.open(bookFile, measurer).use { session ->
 *     val layout = session.layout(href, settings)
 *     val paged = session.paginate(href, settings, PageBox(w, h))
 * }
 * ```
 */
class LayoutSession(
    val book: BookParser,
    val measurer: GlyphMeasurer,
    val imageSizes: ImageSizeProvider = ImageSizeProvider.NONE,
    val cache: ChapterLayoutCache = ChapterLayoutCache(),
    private val warnings: WarningSink = book.warnings,
) : Closeable {

    /** Reader configuration that affects layout geometry. */
    data class Settings(
        val fontSizePx: Float = 16f,
        val widthPx: Float,
        val heightPx: Float,
        val writingMode: WritingMode = WritingMode.HORIZONTAL_TB,
        val theme: String = "default",
    ) {
        fun cacheKey(href: String) = ChapterLayoutCache.Key(
            href = href,
            fontSizePx = fontSizePx,
            widthPx = widthPx,
            heightPx = heightPx,
            writingMode = writingMode,
            theme = theme,
        )
    }

    fun layout(href: String, settings: Settings): ChapterLayout {
        val key = settings.cacheKey(href)
        cache.get(key)?.let { return it }
        val document = book.chapter(href)
        val resolver = StyleResolver(
            authorSheets = book.stylesheetsFor(document),
            initialFontSizePx = settings.fontSizePx,
            viewportWidthPx = settings.widthPx,
            viewportHeightPx = settings.heightPx,
            warnings = warnings,
        )
        val styles = resolver.resolve(document)
        val engine = BlockLayoutEngine(measurer, imageSizes, warnings)
        val layout = engine.layout(
            document,
            styles,
            LayoutConfig(settings.widthPx, settings.heightPx, settings.writingMode),
        )
        cache.put(key, layout)
        return layout
    }

    fun paginate(href: String, settings: Settings, page: PageBox): PagedChapter =
        Paginator().paginate(layout(href, settings), page)

    /**
     * Lays out a single element subtree with the book's own stylesheets, at
     * [settings]' width. Used by the footnote popup: the `aside` is hidden in
     * the page flow (that is the whole point of the duokan footnote layout) but
     * must render with the same engine when the reader taps 【注】.
     */
    fun layoutFragment(
        document: Document,
        root: DocNode,
        settings: Settings,
        forceBlock: Boolean = true,
    ): ChapterLayout {
        val styles = StyleResolver(
            authorSheets = book.stylesheetsFor(document),
            initialFontSizePx = settings.fontSizePx,
            viewportWidthPx = settings.widthPx,
            viewportHeightPx = settings.heightPx,
            warnings = warnings,
        ).resolve(document).toMutableMap()
        if (forceBlock) {
            val style = styles[root]
            if (style != null) styles[root] = style.copy(display = Display.BLOCK)
        }
        val fragment = Document(
            root = root,
            href = document.href,
            ids = emptyMap(),
            styleSheets = emptyList(),
            title = null,
            warnings = warnings,
        )
        return BlockLayoutEngine(measurer, imageSizes, warnings).layout(
            fragment,
            styles,
            LayoutConfig(settings.widthPx, settings.heightPx, settings.writingMode),
        )
    }

    /** Drops cached layouts (e.g. after changing an image provider). */
    fun invalidate() = cache.clear()

    override fun close() = book.close()

    companion object {
        fun open(
            book: BookParser,
            measurer: GlyphMeasurer,
            imageSizes: ImageSizeProvider = ImageSizeProvider.NONE,
        ): LayoutSession = LayoutSession(book, measurer, imageSizes)
    }
}
