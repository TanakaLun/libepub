package org.libepub.core.layout

import org.libepub.core.css.WhiteSpace
import org.libepub.core.css.WordBreak
import org.libepub.core.css.WordWrap

/** Line-breaking inputs derived from a computed style. */
data class BreakOptions(
    val whiteSpace: WhiteSpace = WhiteSpace.NORMAL,
    val wordBreak: WordBreak = WordBreak.NORMAL,
    val wordWrap: WordWrap = WordWrap.NORMAL,
    val letterSpacingPx: Float = 0f,
)

/**
 * One laid-out line. [start]/[end] index the prepared cluster list (for style
 * runs); [startOffset]/[endOffset] are code-unit offsets into the source text
 * (for anchors/locators). Trailing whitespace is excluded from [text] and
 * [widthPx].
 */
data class BrokenLine(
    val text: String,
    val start: Int,
    val end: Int,
    val widthPx: Float,
    val hardBreak: Boolean,
    val startOffset: Int,
    val endOffset: Int,
)

/** A grapheme plus the advance and arbitrary caller payload (style, node, ...). */
class GraphemeInput(
    val text: String,
    val start: Int,
    val end: Int,
    val width: Float,
    val payload: Any? = null,
)

/** Result of whitespace processing; indices are stable for line composition. */
class PreparedCluster(
    val text: String,
    val start: Int,
    val end: Int,
    val width: Float,
    val whitespace: Boolean,
    val hardBreak: Boolean,
    val payload: Any?,
)

/**
 * Direction-agnostic line breaker: advances are along the inline axis, so the
 * same code path serves horizontal and vertical text. CJK kinsoku, Latin word
 * boundaries, hyphen breaks, grapheme clusters, `white-space` and `word-break`
 * are honored; a single unbreakable segment wider than the line (long URL) is
 * force-split at cluster boundaries rather than clipped.
 *
 * The breaker works on [PreparedCluster]s so the inline composer can feed
 * mixed-style runs without duplicating the wrapping algorithm.
 */
class LineBreaker(private val measurer: GlyphMeasurer) {

    fun breakText(
        text: String,
        font: FontSpec,
        maxWidthPx: Float,
        options: BreakOptions = BreakOptions(),
        firstLineIndentPx: Float = 0f,
    ): List<BrokenLine> {
        val graphemes = GraphemeCluster.split(text)
        val spaceWidth = measurer.advance(" ", font) + options.letterSpacingPx
        val inputs = graphemes.map { grapheme ->
            GraphemeInput(
                text = grapheme.text,
                start = grapheme.start,
                end = grapheme.end,
                width = measurer.advance(grapheme.text, font) + options.letterSpacingPx,
            )
        }
        return breakPrepared(
            prepare(inputs, options.whiteSpace, spaceWidth),
            options,
            maxWidthPx,
            firstLineIndentPx,
        )
    }

    /**
     * Collapses/preserves whitespace according to [whiteSpace]. Newlines become
     * hard-break clusters only in `pre*` modes.
     */
    fun prepare(
        graphemes: List<GraphemeInput>,
        whiteSpace: WhiteSpace,
        spaceWidth: Float,
    ): List<PreparedCluster> {
        val preserveSpaces = whiteSpace == WhiteSpace.PRE ||
            whiteSpace == WhiteSpace.PRE_WRAP ||
            whiteSpace == WhiteSpace.BREAK_SPACES
        val preserveNewlines = whiteSpace == WhiteSpace.PRE ||
            whiteSpace == WhiteSpace.PRE_WRAP ||
            whiteSpace == WhiteSpace.PRE_LINE
        val out = ArrayList<PreparedCluster>(graphemes.size)

        for (grapheme in graphemes) {
            val isNewline = grapheme.text.any { it == '\n' || it == '\r' }
            if (isNewline && preserveNewlines) {
                out.add(
                    PreparedCluster("", grapheme.start, grapheme.end, 0f, false, true, grapheme.payload),
                )
                continue
            }
            val collapsible = isCollapsibleSpace(grapheme.text)
            if (collapsible && !preserveSpaces) {
                val previous = out.lastOrNull()
                if (previous == null || previous.whitespace || previous.hardBreak) continue
                out.add(PreparedCluster(" ", grapheme.start, grapheme.end, spaceWidth, true, false, grapheme.payload))
                continue
            }
            if (collapsible) {
                val text = if (grapheme.text == "\t") " " else grapheme.text
                val width = if (text == " ") spaceWidth else grapheme.width
                out.add(PreparedCluster(text, grapheme.start, grapheme.end, width, true, false, grapheme.payload))
                continue
            }
            out.add(
                PreparedCluster(
                    text = grapheme.text,
                    start = grapheme.start,
                    end = grapheme.end,
                    width = grapheme.width,
                    whitespace = false,
                    hardBreak = false,
                    payload = grapheme.payload,
                ),
            )
        }
        return out
    }

    fun breakPrepared(
        clusters: List<PreparedCluster>,
        options: BreakOptions = BreakOptions(),
        maxWidthPx: Float,
        firstLineIndentPx: Float = 0f,
    ): List<BrokenLine> = breakLines(clusters, options, maxWidthPx, firstLineIndentPx, Int.MAX_VALUE)

    /**
     * Breaks only the next line off [clusters]. The block layout engine calls
     * this once per line so the available width can change between lines
     * (floats narrowing a band, then full width below it) without re-running
     * the whole paragraph.
     */
    fun breakFirstLine(
        clusters: List<PreparedCluster>,
        options: BreakOptions = BreakOptions(),
        maxWidthPx: Float,
        firstLineIndentPx: Float = 0f,
    ): BrokenLine? = breakLines(clusters, options, maxWidthPx, firstLineIndentPx, 1).firstOrNull()

    private fun breakLines(
        clusters: List<PreparedCluster>,
        options: BreakOptions,
        maxWidthPx: Float,
        firstLineIndentPx: Float,
        maxLines: Int,
    ): List<BrokenLine> {
        if (clusters.isEmpty() || maxLines <= 0) return emptyList()

        val width = maxWidthPx.coerceAtLeast(1f)
        val lines = ArrayList<BrokenLine>(4)
        var lineStart = 0
        var lineWidth = 0f
        var lastBreak = -1
        var index = 0

        while (index < clusters.size && lines.size < maxLines) {
            val cluster = clusters[index]

            if (cluster.hardBreak) {
                lines.add(buildLine(clusters, lineStart, index, hardBreak = true))
                index++
                lineStart = index
                lineWidth = 0f
                lastBreak = -1
                continue
            }

            if (cluster.whitespace && index == lineStart && !preservesLeadingSpace(options.whiteSpace)) {
                index++
                continue
            }

            val limit = if (lines.isEmpty()) {
                (width - firstLineIndentPx).coerceAtLeast(1f)
            } else {
                width
            }

            if (index > lineStart && lineWidth + cluster.width > limit && !neverWraps(options.whiteSpace)) {
                val breakAt = lastBreak
                if (breakAt > lineStart) {
                    lines.add(buildLine(clusters, lineStart, breakAt, hardBreak = false))
                    lineStart = breakAt
                    while (lineStart < index && clusters[lineStart].whitespace &&
                        !preservesLeadingSpace(options.whiteSpace)
                    ) {
                        lineStart++
                    }
                    lineWidth = widthOf(clusters, lineStart, index)
                    lastBreak = -1
                    continue
                }
                // No legal break: force-split before the overflowing cluster.
                lines.add(buildLine(clusters, lineStart, index, hardBreak = false))
                lineStart = index
                lineWidth = 0f
                lastBreak = -1
                continue
            }

            lineWidth += cluster.width
            if (index + 1 < clusters.size && canBreakBetween(cluster, clusters[index + 1], options)) {
                lastBreak = index + 1
            }
            index++
        }

        if (lineStart < clusters.size && lines.size < maxLines) {
            lines.add(buildLine(clusters, lineStart, clusters.size, hardBreak = false))
        } else if (lines.size < maxLines && clusters.last().hardBreak) {
            val offset = clusters.last().end
            lines.add(BrokenLine("", lineStart, lineStart, 0f, true, offset, offset))
        }
        return lines
    }

    // ---------------------------------------------------------------- breaking

    private fun preservesLeadingSpace(whiteSpace: WhiteSpace): Boolean =
        whiteSpace == WhiteSpace.PRE ||
            whiteSpace == WhiteSpace.PRE_WRAP ||
            whiteSpace == WhiteSpace.BREAK_SPACES

    private fun neverWraps(whiteSpace: WhiteSpace): Boolean =
        whiteSpace == WhiteSpace.NOWRAP || whiteSpace == WhiteSpace.PRE

    private fun isCollapsibleSpace(text: String): Boolean =
        text.isNotEmpty() && text.all { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000C' }

    private fun canBreakBetween(
        previous: PreparedCluster,
        next: PreparedCluster,
        options: BreakOptions,
    ): Boolean {
        if (options.whiteSpace == WhiteSpace.NOWRAP || options.whiteSpace == WhiteSpace.PRE) return false
        if (next.whitespace) return false
        if (previous.whitespace) return true
        if (options.wordBreak == WordBreak.BREAK_ALL) return true
        if (Kinsoku.prohibitsBreakBefore(next.text)) return false
        if (Kinsoku.prohibitsBreakAfter(previous.text)) return false
        if (options.wordBreak == WordBreak.KEEP_ALL &&
            Kinsoku.isCjk(previous.text) && Kinsoku.isCjk(next.text)
        ) {
            return false
        }
        return Kinsoku.allowsBreakBetween(previous.text, next.text)
    }

    // ------------------------------------------------------------------ lines

    private fun widthOf(clusters: List<PreparedCluster>, from: Int, to: Int): Float {
        var total = 0f
        for (index in from until to) total += clusters[index].width
        return total
    }

    private fun buildLine(
        clusters: List<PreparedCluster>,
        start: Int,
        endExclusive: Int,
        hardBreak: Boolean,
    ): BrokenLine {
        var end = endExclusive
        while (end > start && clusters[end - 1].whitespace) end--
        val text = if (end > start) {
            val builder = StringBuilder()
            for (index in start until end) builder.append(clusters[index].text)
            builder.toString()
        } else {
            ""
        }
        val width = widthOf(clusters, start, end)
        val startOffset = when {
            start < clusters.size -> clusters[start].start
            clusters.isNotEmpty() -> clusters.last().end
            else -> 0
        }
        val endOffset = if (end > start) clusters[end - 1].end else startOffset
        return BrokenLine(text, start, end, width, hardBreak, startOffset, endOffset)
    }
}
