package org.libepub.render.image

import android.graphics.BitmapFactory
import org.libepub.core.container.EpubContainer
import org.libepub.core.layout.ImageSizeProvider
import org.libepub.core.layout.IntrinsicSize
import org.libepub.core.util.LruStore

/**
 * Reads image dimensions straight out of the EPUB container so the layout
 * engine can scale illustrations to the column before anything is decoded.
 *
 * Without this provider every image without an explicit CSS size collapses to
 * the 4em fallback square — the reader then shows a small thumbnail at the top
 * of the page instead of a full-width illustration.
 *
 * Bounds-only decoding (`inJustDecodeBounds`) never allocates a bitmap; results
 * are cached, and misses are remembered so a broken resource is not re-read for
 * every layout pass.
 */
class ContainerImageSizeProvider(
    private val container: EpubContainer,
    maxEntries: Int = 64,
) : ImageSizeProvider {

    private val hits = LruStore<String, IntrinsicSize>(maxEntries)
    private val misses = LinkedHashSet<String>()
    private val maxMisses = maxEntries

    override fun intrinsicSize(src: String): IntrinsicSize? {
        hits.get(src)?.let { return it }
        if (src in misses) return null

        val size = runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            container.openStream(src).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                IntrinsicSize(options.outWidth.toFloat(), options.outHeight.toFloat())
            } else {
                null
            }
        }.getOrNull()

        if (size == null) {
            if (misses.size >= maxMisses) misses.clear()
            misses.add(src)
        } else {
            hits.put(src, size)
        }
        return size
    }
}
