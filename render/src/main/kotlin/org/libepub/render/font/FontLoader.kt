package org.libepub.render.font

import android.graphics.Typeface
import org.libepub.core.container.EpubContainer
import org.libepub.core.util.WarningSink
import java.io.File

/**
 * Loads `@font-face` resources out of the EPUB container and hands back
 * `Typeface`s. Fonts are extracted once to a cache directory because
 * `Typeface.createFromFile` needs a real file.
 *
 * Failure is always graceful: a missing or unreadable font returns null and the
 * measurer falls back to the platform default, with a single warning per source.
 */
class FontLoader(
    private val container: EpubContainer,
    private val cacheDir: File,
    private val warnings: WarningSink = container.warnings,
) {
    private val cache = HashMap<String, Typeface?>()

    fun load(src: String): Typeface? {
        cache[src]?.let { return it }
        val typeface = runCatching {
            val bytes = container.readBytes(src)
            cacheDir.mkdirs()
            val file = File(cacheDir, "font-${src.hashCode().toUInt().toString(16)}.ttf")
            if (!file.exists()) file.writeBytes(bytes)
            Typeface.createFromFile(file)
        }.getOrElse {
            warnings.warnOnce("font.load:$src", "unable to load font $src (${it.message})")
            null
        }
        cache[src] = typeface
        return typeface
    }
}
