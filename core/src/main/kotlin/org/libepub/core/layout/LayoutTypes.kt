package org.libepub.core.layout

import org.libepub.core.css.CssColor
import org.libepub.core.css.WritingMode
import org.libepub.core.model.Document
import org.libepub.core.style.ComputedStyle
import org.libepub.core.xhtml.DocNode

/** Intrinsic pixel size of an image resource. */
data class IntrinsicSize(val width: Float, val height: Float)

/** Supplies image dimensions to the layout engine without touching Android. */
interface ImageSizeProvider {
    fun intrinsicSize(src: String): IntrinsicSize?

    companion object {
        val NONE: ImageSizeProvider = object : ImageSizeProvider {
            override fun intrinsicSize(src: String): IntrinsicSize? = null
        }
    }
}

/** Inputs for one chapter layout. Width/height are the viewport (already used to resolve vw/vh in styles). */
data class LayoutConfig(
    val widthPx: Float,
    val heightPx: Float,
    val writingMode: WritingMode = WritingMode.HORIZONTAL_TB,
)

enum class BoxKind {
    BLOCK, ANONYMOUS, LIST_ITEM, TABLE, TABLE_ROW, TABLE_CELL, INLINE_BLOCK, IMAGE, HR, RUBY,
}

/** A block-level box with logical-axis geometry (main = inline direction, block = line stacking direction). */
data class LaidOutBox(
    val node: DocNode?,
    val kind: BoxKind,
    val style: ComputedStyle,
    val main: Float,
    val block: Float,
    val mainSize: Float,
    val blockSize: Float,
    val contentMain: Float,
    val contentBlock: Float,
    val contentMainSize: Float,
    val contentBlockSize: Float,
    /** Zip-root resolved image source (block images only). */
    val src: String? = null,
    /** `alt` text of an image box (used when the image cannot be decoded). */
    val alt: String? = null,
    /** True when the image resource is missing and the box is a placeholder. */
    val placeholder: Boolean = false,
)

enum class FragmentKind { TEXT, IMAGE, ATOMIC, LIST_MARKER }

/**
 * One positioned inline fragment. [main]/[block] are absolute chapter
 * coordinates; [baseline] is the distance from the fragment's block top to the
 * line baseline it sits on.
 */
data class InlineFragment(
    val text: String?,
    val style: ComputedStyle,
    val main: Float,
    val block: Float,
    val mainSize: Float,
    val blockSize: Float,
    val baseline: Float,
    val kind: FragmentKind = FragmentKind.TEXT,
    val node: DocNode? = null,
    val src: String? = null,
    val alt: String? = null,
    val placeholder: Boolean = false,
    /** Zip-root target of an internal hyperlink, when the run sits inside `<a href>`. */
    val linkHref: String? = null,
    /** Fragment part of the link target, when present. */
    val linkFragment: String? = null,
)

/** One laid-out line box. */
data class LayoutLine(
    val index: Int,
    val block: Float,
    val blockSize: Float,
    val main: Float,
    val mainSize: Float,
    val baseline: Float,
    val fragments: List<InlineFragment>,
    /** Extra px to distribute between clusters for `text-align: justify` (0 on the last line). */
    val justifyExtraPx: Float = 0f,
    /** `text-decoration` strokes, already positioned in logical coordinates. */
    val decorations: List<DecorationStroke> = emptyList(),
    /** `text-emphasis` marks, one per emphasized cluster. */
    val emphasisMarks: List<EmphasisMark> = emptyList(),
    /** Ruby annotations (block-before side of their base text). */
    val rubyAnnotations: List<RubyAnnotation> = emptyList(),
    /** Per-cluster orientation for vertical writing (empty in horizontal mode). */
    val orientations: List<GlyphOrientation> = emptyList(),
) {
    /** Visible text of the line (markers and atoms excluded). */
    val text: String
        get() = fragments.joinToString("") { fragment ->
            if (fragment.kind == FragmentKind.TEXT) fragment.text.orEmpty() else ""
        }
}

// ------------------------------------------------------------------ decoration

enum class DecorationLine { UNDERLINE, OVERLINE, LINE_THROUGH }

/**
 * A decoration stroke in logical coordinates. In horizontal writing [block] is
 * a y coordinate (underline below the baseline); in vertical writing the same
 * value is an x coordinate, which puts underlines on the block-after side of
 * the column — exactly where a vertical reader expects them.
 */
data class DecorationStroke(
    val line: DecorationLine,
    val main: Float,
    val mainSize: Float,
    val block: Float,
    val thickness: Float,
    val color: CssColor,
)

/** One emphasis mark drawn on the block-before side of a cluster. */
data class EmphasisMark(
    val mark: String,
    val main: Float,
    val mainSize: Float,
    val block: Float,
    val blockSize: Float,
    val style: ComputedStyle,
)

/** One ruby annotation box, centered over its base cluster group. */
data class RubyAnnotation(
    val text: String,
    val main: Float,
    val mainSize: Float,
    val block: Float,
    val blockSize: Float,
    val style: ComputedStyle,
)

/** How one cluster is drawn in vertical text: upright or rotated 90°. */
data class GlyphOrientation(
    val grapheme: String,
    val main: Float,
    val mainSize: Float,
    val upright: Boolean,
)

/** Where an element/fragment lives, for TOC jumps, footnotes and locators. */
data class LayoutAnchor(
    val node: DocNode?,
    val main: Float,
    val block: Float,
)

/** A mandatory page break discovered during layout. */
data class PageBreakPoint(
    val block: Float,
    val label: String? = null,
)

/**
 * The single source of truth for one chapter: line boxes, boxes, anchors and
 * page-break points. The pager slices [lines] by the viewport block size; the
 * scroll view renders all of them. Both consume this same object.
 */
data class ChapterLayout(
    val document: Document,
    val writingMode: WritingMode,
    val widthPx: Float,
    val heightPx: Float,
    val contentBlockSize: Float,
    val lines: List<LayoutLine>,
    val boxes: List<LaidOutBox>,
    val anchors: Map<String, LayoutAnchor>,
    val pageBreaks: List<PageBreakPoint>,
) {
    /** Index of the line covering [block] (binary search; -1 when empty). */
    fun lineIndexAt(block: Float): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lines[mid]
            if (line.block <= block + 0.01f) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result.coerceAtLeast(0)
    }
}
