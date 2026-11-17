package org.libepub.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.libepub.core.model.Locator
import org.libepub.core.toc.TocEntry
import org.libepub.render.widget.ReaderMode

/**
 * App-level reader state machine: which spine document is open, which TOC entry
 * was last chosen, reading mode and persisted progress. Deliberately free of
 * Android types so the navigation/progress rules are unit-testable.
 */
class ReaderState(
    val bookKey: String,
    val spineHrefs: List<String>,
    val toc: List<TocEntry>,
    private val progress: ProgressStore,
) {
    var spineIndex by mutableStateOf(0)
        private set

    /** Fragment to jump to after the chapter is composed, consumed by the UI. */
    var pendingFragment by mutableStateOf<String?>(null)

    var mode by mutableStateOf(ReaderMode.PAGED)
    var fontSizeSp by mutableStateOf(18f)
    var themeKey by mutableStateOf("paper")

    init {
        restore()
    }

    /** All TOC entries in reading order (depth-first). */
    val flatToc: List<TocEntry> by lazy {
        val out = ArrayList<TocEntry>(toc.size * 2)
        fun walk(entries: List<TocEntry>) {
            for (entry in entries) {
                out.add(entry)
                walk(entry.children)
            }
        }
        walk(toc)
        out
    }

    private fun restore() {
        val locator = progress.load(bookKey) ?: return
        val index = spineHrefs.indexOf(locator.href)
        if (index < 0) return
        spineIndex = index
        pendingFragment = locator.fragment
    }

    /**
     * Jumps to a TOC entry. Returns false when the entry has no href or points
     * outside the spine (dangling TOC entries are common in real books).
     */
    fun selectToc(entry: TocEntry): Boolean {
        val href = entry.href ?: return false
        val index = spineHrefs.indexOf(href)
        if (index < 0) return false
        spineIndex = index
        pendingFragment = entry.fragment
        return true
    }

    fun consumePendingFragment(): String? {
        val fragment = pendingFragment
        pendingFragment = null
        return fragment
    }

    fun save(locator: Locator) {
        progress.save(bookKey, locator)
    }

    fun savedLocator(): Locator? = progress.load(bookKey)
}
