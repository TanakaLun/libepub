package org.libepub.core.layout

import org.libepub.core.css.WritingMode
import org.libepub.core.util.LruStore

/**
 * Layout cache keyed by everything that changes geometry: the chapter, the
 * reader's font size, the viewport, the writing direction and the theme.
 *
 * Theme does not change line geometry in this engine, but keeping it in the key
 * means a future theme that does (e.g. per-theme margins) cannot silently reuse
 * a stale layout.
 */
class ChapterLayoutCache(maxEntries: Int = 8) {

    data class Key(
        val href: String,
        val fontSizePx: Float,
        val widthPx: Float,
        val heightPx: Float,
        val writingMode: WritingMode,
        val theme: String = "default",
    )

    private val store = LruStore<Key, ChapterLayout>(maxEntries)

    val size: Int get() = store.size

    fun get(key: Key): ChapterLayout? = store.get(key)

    fun put(key: Key, layout: ChapterLayout): ChapterLayout? = store.put(key, layout)

    fun clear() = store.clear()

    /** Keys in least-recently-used order (oldest first). */
    fun keys(): List<Key> = store.keys()
}
