package org.libepub.core.testutil

import org.junit.Assume
import java.io.File

/** Locates repository test corpora without copying them into module resources. */
object SampleBooks {
    /** The sample novel shipped in <repo>/assets (duokan-style EPUB 2.0 + EPUB3 footnote attrs). */
    fun sample(): File = find("sample")

    private fun find(label: String): File {
        System.getProperty("libepub.sample")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val assets = File(dir, "assets")
            if (assets.isDirectory) {
                val epub = assets.listFiles()?.firstOrNull { it.extension.equals("epub", ignoreCase = true) }
                if (epub != null) return epub
            }
            dir = dir.parentFile
        }
        // The acceptance book is copyrighted and is not committed, so a fresh
        // clone skips the real-book tests instead of failing them.
        Assume.assumeTrue(
            "no sample epub in assets/ ($label); " +
                "drop one there or pass -Dlibepub.sample=/path/to/book.epub",
            false,
        )
        error("unreachable: the assumption above always fails here")
    }
}
