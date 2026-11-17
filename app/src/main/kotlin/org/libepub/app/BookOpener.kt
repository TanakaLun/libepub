package org.libepub.app

import android.content.Context
import org.libepub.core.layout.LayoutSession
import org.libepub.core.parse.BookParser
import org.libepub.render.draw.PageDrawer
import org.libepub.render.font.FontLoader
import org.libepub.render.font.FontRegistry
import org.libepub.render.image.BitmapImageLoader
import org.libepub.render.image.ContainerImageSizeProvider
import org.libepub.render.measurer.ComposeGlyphMeasurer
import org.libepub.render.widget.EpubReaderState
import java.io.File

/** An opened book: the layout session plus the render/app state machines. */
internal class LoadedBook(
    val session: LayoutSession,
    val reader: EpubReaderState,
    val app: ReaderState,
)

/**
 * Opens the container, wires the render layer with the book's own resources and
 * restores the saved position. Runs off the main thread.
 *
 * The [ContainerImageSizeProvider] is essential: without it the layout engine
 * has no intrinsic image sizes, every illustration collapses to the 4em
 * fallback square and the reader shows thumbnails instead of full-width art.
 */
internal fun openBook(
    entry: BookEntry,
    repository: BookSource,
    context: Context,
    progress: ProgressStore,
): LoadedBook {
    val file = repository.ensureLocal(entry)
    val book = BookParser.open(file)
    var session: LayoutSession? = null
    try {
        val warnings = book.warnings

        val registry = FontRegistry(
            FontLoader(book.container, File(context.cacheDir, "fonts"), warnings),
            warnings,
        )
        // The book's stylesheet is shared by every chapter; registering it once
        // from the first spine document keeps cold open inside the budget.
        val first = book.readableSpine.firstOrNull()?.href
        if (first != null) {
            runCatching { registry.register(book.stylesheetsFor(book.chapter(first))) }
        }

        val measurer = ComposeGlyphMeasurer(registry)
        val opened = LayoutSession.open(
            book = book,
            measurer = measurer,
            imageSizes = ContainerImageSizeProvider(book.container),
        )
        session = opened

        val reader = EpubReaderState(
            session = opened,
            drawer = PageDrawer(measurer, registry),
            imageLoader = BitmapImageLoader(book.container),
        )

        val appState = ReaderState(
            bookKey = entry.fileName,
            spineHrefs = book.readableSpine.mapNotNull { it.href },
            toc = book.toc.entries,
            progress = progress,
        )

        reader.fontSizeSp = appState.fontSizeSp
        reader.themeKey = appState.themeKey
        reader.mode = appState.mode
        reader.goToSpine(appState.spineIndex)
        appState.consumePendingFragment()?.let { reader.goToAnchor(it) }

        return LoadedBook(opened, reader, appState)
    } catch (t: Throwable) {
        session?.close() ?: book.close()
        throw t
    }
}
