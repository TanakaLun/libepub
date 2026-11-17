package org.libepub.core.model

/**
 * Minimal reading-position model: which spine document, which anchor (when the
 * reader jumped to one) and which page. Everything else (percentage, chapter
 * title) is derived, so a saved locator stays valid when fonts or the viewport
 * change — the page is re-derived from the anchor/block on restore.
 */
data class Locator(
    val href: String,
    val fragment: String? = null,
    /** Logical block offset inside the chapter, used when no anchor matches. */
    val block: Float = 0f,
    /** Page index inside the chapter at save time (hint only). */
    val pageIndex: Int = 0,
)
