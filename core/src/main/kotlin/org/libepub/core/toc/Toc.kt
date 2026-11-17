package org.libepub.core.toc

/** One table-of-contents node; [href] is zip-root resolved, [fragment] kept apart. */
data class TocEntry(
    val title: String,
    val href: String?,
    val fragment: String?,
    val children: List<TocEntry>,
    val playOrder: Int? = null,
)

/** EPUB3 nav landmarks entry: type comes from `epub:type` on the anchor. */
data class Landmark(
    val type: String,
    val title: String?,
    val href: String,
    val fragment: String?,
)

/** Printed page label: display text plus the document position it points at. */
data class PageLabel(
    val label: String,
    val href: String,
    val fragment: String?,
)

enum class TocSource { NONE, NCX, NAV }

data class Toc(
    val title: String?,
    val entries: List<TocEntry>,
    val landmarks: List<Landmark>,
    val pageLabels: List<PageLabel>,
    val source: TocSource,
) {
    /** Depth-first flatten in reading order. */
    val flat: List<TocEntry> by lazy {
        val out = ArrayList<TocEntry>(32)
        fun walk(list: List<TocEntry>) {
            for (e in list) {
                out.add(e)
                walk(e.children)
            }
        }
        walk(entries)
        out
    }
}
