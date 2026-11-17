package org.libepub.app

import android.content.Context
import java.io.File

/** One EPUB shipped in the app's assets directory. */
data class BookEntry(val fileName: String, val title: String)

/**
 * Bookshelf source. The production implementation reads APK assets; tests can
 * plug a filesystem source (also useful for "open an EPUB from storage").
 */
interface BookSource {
    fun list(): List<BookEntry>
    fun ensureLocal(entry: BookEntry): File
}

/**
 * Bookshelf source: every `.epub` in `assets/`. Assets live inside the APK, so
 * the parser gets a real file by copying the book to the cache on first open.
 */
class BookRepository(private val context: Context) : BookSource {

    override fun list(): List<BookEntry> =
        context.assets.list("")
            .orEmpty()
            .filter { it.endsWith(".epub", ignoreCase = true) }
            .sorted()
            .map { BookEntry(it, it.removeSuffix(".epub").removeSuffix(".EPUB")) }

    override fun ensureLocal(entry: BookEntry): File {
        val dir = File(context.cacheDir, "books").apply { mkdirs() }
        val target = File(dir, entry.fileName)
        if (!target.exists() || target.length() == 0L) {
            val temp = File(dir, entry.fileName + ".part")
            context.assets.open(entry.fileName).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        return target
    }
}
