package org.libepub.render.image

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libepub.core.container.EpubContainer
import org.libepub.core.util.LruStore

/** Supplies decoded images to the render layer. */
interface ImageLoader {

    /** Already-decoded bitmap for [src] at (at most) [targetWidthPx], or null. */
    fun cached(src: String, targetWidthPx: Int = 0): ImageBitmap?

    /** Decodes on a background dispatcher; null when the resource is missing. */
    suspend fun load(src: String, targetWidthPx: Int = 0): ImageBitmap?

    fun clear()
}

/**
 * `BitmapFactory` loader with `inSampleSize` downsampling and a bounded LRU.
 *
 * Books ship 2000px illustrations that would cost tens of megabytes at full
 * size; sampling to roughly the target width keeps a chapter's worth of images
 * inside a few megabytes.
 */
class BitmapImageLoader(
    private val container: EpubContainer,
    maxEntries: Int = 12,
    private val maxTargetPx: Int = 2048,
) : ImageLoader {

    private val store = LruStore<String, ImageBitmap>(maxEntries)

    override fun cached(src: String, targetWidthPx: Int): ImageBitmap? =
        store.get(key(src, targetWidthPx))

    override suspend fun load(src: String, targetWidthPx: Int): ImageBitmap? {
        cached(src, targetWidthPx)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = runCatching { container.readBytes(src) }.getOrNull() ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, targetWidthPx)
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext null
            bitmap.asImageBitmap().also { store.put(key(src, targetWidthPx), it) }
        }
    }

    override fun clear() = store.clear()

    private fun key(src: String, targetWidthPx: Int): String =
        "$src@${targetWidthPx.coerceIn(0, maxTargetPx)}"

    private fun sampleSize(width: Int, targetWidthPx: Int): Int {
        if (width <= 0 || targetWidthPx <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetWidthPx) sample *= 2
        return sample
    }
}
