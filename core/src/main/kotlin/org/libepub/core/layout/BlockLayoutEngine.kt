package org.libepub.core.layout

import org.libepub.core.css.BorderStyle
import org.libepub.core.css.BoxSizing
import org.libepub.core.container.ResourcePath
import org.libepub.core.css.CssFloat
import org.libepub.core.css.Dimension
import org.libepub.core.css.Length
import org.libepub.core.css.PageBreak
import org.libepub.core.css.TextAlign
import org.libepub.core.css.VerticalAlign
import org.libepub.core.css.WritingMode
import org.libepub.core.model.Document
import org.libepub.core.style.ComputedStyle
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.DocNode

/**
 * Block flow + inline composition for one chapter.
 *
 * Produces [ChapterLayout], the single layout output consumed by both the
 * pager and the scroll view. All coordinates are logical: [LayoutLine.main] /
 * [LayoutLine.block] are inline/stacking axes; vertical writing only remaps
 * them in the render layer.
 *
 * Implemented here: block stacking with sibling margin collapsing, anonymous
 * inline blocks, first-line indent, text-align (incl. justify), line-height,
 * floats with text wrapping, inline images and inline-blocks, lists with
 * markers, basic tables (colspan/rowspan), `<hr>`, page-break points and
 * anchors.
 */
class BlockLayoutEngine(
    private val measurer: GlyphMeasurer,
    private val imageSizes: ImageSizeProvider = ImageSizeProvider.NONE,
    private val warnings: WarningSink = WarningSink(),
) {

    fun layout(
        document: Document,
        styles: Map<DocNode, ComputedStyle>,
        config: LayoutConfig,
    ): ChapterLayout = LayoutPass(document, styles, config, measurer, imageSizes, warnings).layout()
}

private class LayoutPass(
    private val document: Document,
    private val styles: Map<DocNode, ComputedStyle>,
    private val config: LayoutConfig,
    private val measurer: GlyphMeasurer,
    private val imageSizes: ImageSizeProvider,
    private val warnings: WarningSink,
) {
    private val lineBreaker = LineBreaker(measurer)

    private val lines = ArrayList<LayoutLine>(256)
    private val boxes = ArrayList<LaidOutBox>(128)
    private val anchors = HashMap<String, LayoutAnchor>(32)
    private val pageBreaks = ArrayList<PageBreakPoint>(4)

    private companion object {
        /** Hard stop against pathological input (also guards float-skip loops). */
        const val MAX_LINES_PER_BOX = 20_000
    }

    private class ClusterPayload(
        val style: ComputedStyle,
        val atomic: AtomicMetrics? = null,
        val markerNode: DocNode? = null,
        val anchorNode: DocNode? = null,
        val linkHref: String? = null,
        val linkFragment: String? = null,
    )

    private class ResolvedImage(val width: Float, val height: Float, val placeholder: Boolean)

    private class AtomicMetrics(
        val item: InlineItem,
        val width: Float,
        val height: Float,
        val ascent: Float,
        val descent: Float,
        val ruby: RubyMetrics? = null,
        val placeholder: Boolean = false,
    )

    /** Pre-measured ruby base/annotation, relative to the atomic's main start. */
    private class RubyMetrics(
        val baseRuns: List<InlineItem.Text>,
        val baseMains: List<Float>,
        val baseWidths: List<Float>,
        val annotation: String,
        val annotationStyle: ComputedStyle,
        val annotationHeight: Float,
        val baseAscent: Float,
    )

    fun layout(): ChapterLayout {
        val root = BoxTreeBuilder(document, styles).build()
        val tracker = FloatTracker(0f, config.widthPx)
        val used = layoutBox(root, 0f, 0f, config.widthPx, tracker)
        return ChapterLayout(
            document = document,
            writingMode = config.writingMode,
            widthPx = config.widthPx,
            heightPx = config.heightPx,
            contentBlockSize = used,
            lines = lines,
            boxes = boxes,
            anchors = anchors,
            pageBreaks = pageBreaks.sortedBy { it.block }.distinctBy { it.block },
        )
    }

    // ------------------------------------------------------------------ blocks

    private fun layoutBox(
        box: Box,
        main: Float,
        block: Float,
        availableMain: Float,
        floats: FloatTracker,
    ): Float {
        val style = box.style
        if (style.isHidden) return 0f

        when (box.kind) {
            BoxKind.IMAGE -> return layoutImageBox(box, main, block, availableMain)
            BoxKind.HR -> return layoutRule(box, main, block, availableMain)
            BoxKind.TABLE -> return layoutTable(box, main, block, availableMain, floats)
            else -> Unit
        }

        val marginLeft = marginOf(style.margin.left, availableMain)
        val marginRight = marginOf(style.margin.right, availableMain)
        val borderLeft = borderWidth(style.border.left)
        val borderRight = borderWidth(style.border.right)
        val borderTop = borderWidth(style.border.top)
        val borderBottom = borderWidth(style.border.bottom)
        val paddingLeft = style.resolvePx(style.padding.left, availableMain)
        val paddingRight = style.resolvePx(style.padding.right, availableMain)
        val paddingTop = style.resolvePx(style.padding.top, availableMain)
        val paddingBottom = style.resolvePx(style.padding.bottom, availableMain)

        val contentMainSize = when (val width = style.width) {
            null, Dimension.Auto -> availableMain - marginLeft - marginRight -
                borderLeft - borderRight - paddingLeft - paddingRight
            is Dimension.Of -> {
                val resolved = style.resolvePx(width.length, availableMain)
                if (style.boxSizing == BoxSizing.BORDER_BOX) {
                    resolved - borderLeft - borderRight - paddingLeft - paddingRight
                } else {
                    resolved
                }
            }
        }.coerceAtLeast(0f)

        var contentMain = main + marginLeft + borderLeft + paddingLeft
        var usableMain = contentMainSize

        var markerGutter = 0f
        var markerMain = contentMain
        if (box.kind == BoxKind.LIST_ITEM && box.marker != null) {
            markerGutter = markerGutter(box)
            markerMain = contentMain
            contentMain += markerGutter
            usableMain = (usableMain - markerGutter).coerceAtLeast(1f)
        }

        val contentBlock = block + borderTop + paddingTop
        var usedBlock = 0f
        if (box.inline.isNotEmpty()) {
            usedBlock = composeInline(
                box = box,
                contentMain = contentMain,
                contentBlock = contentBlock,
                availableMain = usableMain,
                floats = floats,
                markerGutter = markerGutter,
                markerMain = markerMain,
            )
        }
        if (box.children.isNotEmpty()) {
            usedBlock += layoutChildren(
                children = box.children,
                main = contentMain,
                block = contentBlock + usedBlock,
                availableMain = usableMain,
                floats = floats,
            )
        }

        val contentBlockSize = when (val height = style.height) {
            null, Dimension.Auto -> usedBlock
            is Dimension.Of -> {
                val resolved = style.resolvePx(height.length, availableMain)
                if (style.boxSizing == BoxSizing.BORDER_BOX) {
                    resolved - borderTop - borderBottom - paddingTop - paddingBottom
                } else {
                    resolved
                }
            }
        }.coerceAtLeast(0f)

        val borderBoxMain = contentMainSize + paddingLeft + paddingRight + borderLeft + borderRight
        val borderBoxBlock = contentBlockSize + paddingTop + paddingBottom + borderTop + borderBottom

        recordBox(
            box = box,
            main = main + marginLeft,
            block = block,
            mainSize = borderBoxMain,
            blockSize = borderBoxBlock,
            contentMain = contentMain,
            contentBlock = contentBlock,
            contentMainSize = usableMain,
            contentBlockSize = contentBlockSize,
        )
        recordAnchor(box.node, main + marginLeft, block)

        if (style.pageBreakBefore == PageBreak.ALWAYS) pageBreaks.add(PageBreakPoint(block))
        if (style.pageBreakAfter == PageBreak.ALWAYS) pageBreaks.add(PageBreakPoint(block + borderBoxBlock))

        return borderBoxBlock
    }

    private fun layoutChildren(
        children: List<Box>,
        main: Float,
        block: Float,
        availableMain: Float,
        floats: FloatTracker,
    ): Float {
        var cursor = block
        var previousMarginBottom = 0f
        var first = true
        var floatBottom = block

        for (child in children) {
            if (child.style.isHidden) continue
            if (child.style.float != CssFloat.NONE) {
                placeFloat(child, main, availableMain, cursor, floats)
                floatBottom = maxOf(floatBottom, floats.bottomOfFloatsStartedAtOrAfter(block))
                continue
            }
            val marginTop = marginOf(child.style.margin.top, availableMain)
            val gap = if (first) marginTop else collapseMargins(previousMarginBottom, marginTop)
            val childBlock = cursor + gap
            val height = layoutBox(child, main, childBlock, availableMain, floats)
            cursor = childBlock + height
            previousMarginBottom = marginOf(child.style.margin.bottom, availableMain)
            first = false
        }
        val contentBottom = if (first) block else cursor + previousMarginBottom
        // A floated child still contributes to its containing block's height.
        return maxOf(contentBottom, floatBottom) - block
    }

    private fun placeFloat(
        box: Box,
        main: Float,
        availableMain: Float,
        block: Float,
        floats: FloatTracker,
    ) {
        val style = box.style
        val width = style.width?.let { style.resolvePx(it, availableMain) }
            ?: intrinsicWidth(box)
            ?: (availableMain * 0.5f)
        val x = if (style.float == CssFloat.LEFT) main else main + availableMain - width
        val height = layoutBox(box, x, block, width, floats)
        floats.add(FloatBox(style.float, x, width, block, height))
    }

    // ------------------------------------------------------------------ inline

    private fun composeInline(
        box: Box,
        contentMain: Float,
        contentBlock: Float,
        availableMain: Float,
        floats: FloatTracker,
        markerGutter: Float,
        markerMain: Float,
    ): Float {
        val style = box.style
        val prepared = prepareInline(box, style, availableMain)
        if (prepared.isEmpty()) return 0f

        val options = BreakOptions(
            whiteSpace = style.whiteSpace,
            wordBreak = style.wordBreak,
            wordWrap = style.wordWrap,
            letterSpacingPx = style.letterSpacingPx,
        )
        val indent = style.resolvePx(style.textIndent, availableMain)

        var cursor = contentBlock
        var clusterIndex = 0
        var lineIndex = 0
        var guard = 0

        // One line at a time: the available inline interval can change between
        // lines (a float band narrows it, then full width resumes below), so the
        // width must be re-queried before every break.
        while (clusterIndex < prepared.size && guard++ < MAX_LINES_PER_BOX) {
            var interval = floats.availableAt(cursor)
            while (interval == null && guard++ < MAX_LINES_PER_BOX) {
                val next = floats.nextBottom(cursor)
                if (next <= cursor + 0.01f) break
                cursor = next
                interval = floats.availableAt(cursor)
            }
            val intervalStart = interval?.first ?: contentMain
            val intervalWidth = interval?.second ?: availableMain
            val intervalEnd = intervalStart + intervalWidth
            val availableStart = maxOf(intervalStart, contentMain)
            val availableWidth = (minOf(intervalEnd, contentMain + availableMain) - availableStart)
                .coerceAtLeast(1f)

            val firstLine = lineIndex == 0
            val indentPx = if (firstLine) indent.coerceIn(0f, (availableWidth - 1f).coerceAtLeast(0f)) else 0f
            val lineStart = availableStart + indentPx
            val lineAvailable = (availableWidth - indentPx).coerceAtLeast(1f)

            val remaining = prepared.subList(clusterIndex, prepared.size)
            val brokenLine = lineBreaker.breakFirstLine(remaining, options, availableWidth, indentPx) ?: break
            val consumed = brokenLine.end
            if (consumed <= 0) break
            val clusters = remaining.subList(brokenLine.start, consumed)
            clusterIndex += consumed
            lineIndex++
            var ascent = 0f
            var descent = 0f
            var lineHeight = 0f
            if (clusters.isEmpty()) {
                val metrics = measurer.metrics(style.toFontSpec())
                ascent = metrics.ascent
                descent = metrics.descent
                lineHeight = style.lineHeightPx ?: metrics.height
            }
            for (cluster in clusters) {
                val payload = cluster.payload as? ClusterPayload ?: continue
                val metrics = measurer.metrics(payload.style.toFontSpec())
                val shift = verticalShift(payload.style)
                ascent = maxOf(ascent, (metrics.ascent - shift).coerceAtLeast(0f))
                descent = maxOf(descent, (metrics.descent + shift).coerceAtLeast(0f))
                lineHeight = maxOf(lineHeight, payload.style.lineHeightPx ?: metrics.height)
                payload.atomic?.let {
                    ascent = maxOf(ascent, it.ascent)
                    descent = maxOf(descent, it.descent)
                }
            }
            lineHeight = maxOf(lineHeight, ascent + descent).coerceAtLeast(1f)
            val baseline = cursor + ascent

            val fragments = ArrayList<InlineFragment>(clusters.size)
            val rubyAnnotations = ArrayList<RubyAnnotation>(2)
            var mainCursor = lineStart
            var index = 0
            while (index < clusters.size) {
                val cluster = clusters[index]
                val payload = cluster.payload as? ClusterPayload
                if (payload == null) {
                    mainCursor += cluster.width
                    index++
                    continue
                }
                val atomic = payload.atomic
                if (atomic != null) {
                    payload.markerNode?.let { recordAnchor(it, mainCursor, cursor) }
                    val ruby = atomic.ruby
                    if (ruby != null) {
                        val atomicMain = mainCursor
                        val atomicTop = cursor + (ascent - atomic.ascent)
                        val baseBlock = atomicTop + ruby.annotationHeight
                        val baseBlockSize = (ruby.baseAscent + atomic.descent).coerceAtLeast(1f)
                        (atomic.item as? InlineItem.Ruby)?.node?.let { recordAnchor(it, atomicMain, atomicTop) }
                        for (run in ruby.baseRuns) {
                            val runWidth = measurer.advanceRun(run.text, run.style.toFontSpec())
                            fragments.add(
                                InlineFragment(
                                    text = run.text,
                                    style = run.style,
                                    main = mainCursor,
                                    block = baseBlock,
                                    mainSize = runWidth,
                                    blockSize = baseBlockSize,
                                    baseline = ruby.baseAscent,
                                    kind = FragmentKind.TEXT,
                                    node = run.node,
                                ),
                            )
                            mainCursor += runWidth
                        }
                        rubyAnnotations += RubyLayout.distribute(
                            baseMains = ruby.baseMains.map { atomicMain + it },
                            baseWidths = ruby.baseWidths,
                            annotation = ruby.annotation,
                            block = atomicTop,
                            blockSize = ruby.annotationHeight,
                            style = ruby.annotationStyle,
                            measurer = measurer,
                        )
                        index++
                        continue
                    }
                    fragments.add(
                        InlineFragment(
                            text = null,
                            style = payload.style,
                            main = mainCursor,
                            block = fragmentTop(
                                style = payload.style,
                                cursor = cursor,
                                lineHeight = lineHeight,
                                ascent = ascent,
                                height = atomic.height,
                                defaultTop = cursor + (ascent - atomic.ascent),
                            ),
                            mainSize = atomic.width,
                            blockSize = atomic.height,
                            baseline = atomic.ascent,
                            kind = if ((atomic.item as? InlineItem.Atomic)?.kind == FragmentKind.IMAGE) {
                                FragmentKind.IMAGE
                            } else {
                                FragmentKind.ATOMIC
                            },
                            node = (atomic.item as? InlineItem.Atomic)?.node,
                            src = (atomic.item as? InlineItem.Atomic)?.src,
                            alt = (atomic.item as? InlineItem.Atomic)?.node?.attr("alt"),
                            placeholder = atomic.placeholder,
                        ),
                    )
                    mainCursor += atomic.width
                    index++
                    continue
                }
                if (payload.markerNode != null) {
                    recordAnchor(payload.markerNode, mainCursor, cursor)
                    index++
                    continue
                }

                payload.anchorNode?.let { recordAnchor(it, mainCursor, cursor) }
                // An anchored run (a footnote marker, a TOC target) never merges
                // into neighbouring text: the renderer needs its own hit box.
                val runNode = payload.anchorNode
                val runStyle = payload.style
                val runLinkHref = payload.linkHref
                val runLinkFragment = payload.linkFragment
                val runText = StringBuilder()
                var runWidth = 0f
                while (index < clusters.size) {
                    val next = clusters[index]
                    val nextPayload = next.payload as? ClusterPayload ?: break
                    if (nextPayload.atomic != null || nextPayload.markerNode != null) break
                    if (nextPayload.anchorNode !== runNode) break
                    if (nextPayload.style !== runStyle) break
                    if (nextPayload.linkHref != runLinkHref || nextPayload.linkFragment != runLinkFragment) break
                    runText.append(next.text)
                    runWidth += next.width
                    index++
                }
                if (runWidth > 0f || runText.isNotEmpty()) {
                    val metrics = measurer.metrics(runStyle.toFontSpec())
                    val shift = verticalShift(runStyle)
                    val fragmentAscent = (metrics.ascent - shift).coerceAtLeast(0f)
                    val fragmentDescent = (metrics.descent + shift).coerceAtLeast(0f)
                    fragments.add(
                        InlineFragment(
                            text = runText.toString(),
                            style = runStyle,
                            main = mainCursor,
                            block = fragmentTop(
                                style = runStyle,
                                cursor = cursor,
                                lineHeight = lineHeight,
                                ascent = ascent,
                                height = fragmentAscent + fragmentDescent,
                                defaultTop = cursor + (ascent - fragmentAscent),
                            ),
                            mainSize = runWidth,
                            blockSize = fragmentAscent + fragmentDescent,
                            baseline = fragmentAscent,
                            kind = FragmentKind.TEXT,
                            node = runNode,
                            linkHref = runLinkHref,
                            linkFragment = runLinkFragment,
                        ),
                    )
                    mainCursor += runWidth
                }
            }

            val contentWidth = brokenLine.widthPx
            val offset = when (style.textAlign) {
                TextAlign.CENTER -> lineStart + (lineAvailable - contentWidth) / 2f
                TextAlign.RIGHT, TextAlign.END -> lineStart + lineAvailable - contentWidth
                else -> lineStart
            }
            val isLastLine = clusterIndex >= prepared.size
            val extra = if (style.textAlign == TextAlign.JUSTIFY && !isLastLine && !brokenLine.hardBreak &&
                contentWidth < lineAvailable
            ) {
                lineAvailable - contentWidth
            } else {
                0f
            }

            val delta = offset - lineStart
            val positioned = if (delta == 0f) fragments else fragments.map { it.copy(main = it.main + delta) }
            val withMarker = if (firstLine && box.marker != null) {
                positioned + markerFragment(box, markerMain, markerGutter, cursor, ascent)
            } else {
                positioned
            }

            val decorations = ArrayList<DecorationStroke>()
            val emphasisMarks = ArrayList<EmphasisMark>(clusters.size)
            val orientations = if (config.writingMode == WritingMode.HORIZONTAL_TB) {
                emptyList()
            } else {
                ArrayList<GlyphOrientation>(clusters.size)
            }
            for (fragment in positioned) {
                if (fragment.kind != FragmentKind.TEXT) continue
                decorations += DecorationLayout.strokes(fragment, baseline, fragment.style)
                emphasisMarks += EmphasisLayout.marks(fragment, fragment.style.textEmphasis, measurer)
                if (orientations is MutableList) {
                    orientations += VerticalText.orientations(
                        fragment.text.orEmpty(),
                        fragment.main,
                        fragment.style,
                        measurer,
                    )
                }
            }

            lines.add(
                LayoutLine(
                    index = lines.size,
                    block = cursor,
                    blockSize = lineHeight,
                    main = offset,
                    mainSize = contentWidth,
                    baseline = baseline,
                    fragments = withMarker,
                    justifyExtraPx = extra,
                    decorations = decorations,
                    emphasisMarks = emphasisMarks,
                    rubyAnnotations = rubyAnnotations,
                    orientations = orientations,
                ),
            )
            cursor += lineHeight
        }
        return cursor - contentBlock
    }

    /**
     * Resolves an author `href` to a zip-root path plus fragment. External and
     * non-navigable schemes are dropped (the reader never touches the network).
     */
    private fun resolveLink(raw: String): Pair<String?, String?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null to null
        if (trimmed.contains("://") || trimmed.startsWith("mailto:") || trimmed.startsWith("tel:")) {
            return null to null
        }
        val path = trimmed.substringBefore('#')
        val fragment = trimmed.substringAfter('#', "").ifEmpty { null }
        val href = if (path.isEmpty()) {
            document.href
        } else {
            ResourcePath.resolve(ResourcePath.parent(document.href), path)
        }
        return href to fragment
    }

    private fun prepareInline(
        box: Box,
        style: ComputedStyle,
        availableMain: Float,
    ): List<PreparedCluster> {
        val spaceWidth = measurer.advance(" ", style.toFontSpec()) + style.letterSpacingPx
        val out = ArrayList<PreparedCluster>(box.inline.size * 4)

        for (item in box.inline) {
            when (item) {
                is InlineItem.Text -> {
                    val font = item.style.toFontSpec()
                    val link = item.link?.let { resolveLink(it) }
                    val inputs = GraphemeCluster.split(item.text).map { grapheme ->
                        GraphemeInput(
                            text = grapheme.text,
                            start = grapheme.start,
                            end = grapheme.end,
                            width = measurer.advance(grapheme.text, font) + item.style.letterSpacingPx,
                            payload = ClusterPayload(
                                style = item.style,
                                anchorNode = item.node,
                                linkHref = link?.first,
                                linkFragment = link?.second,
                            ),
                        )
                    }
                    val prepared = lineBreaker.prepare(inputs, style.whiteSpace, spaceWidth)
                    val duplicateSpace = out.isNotEmpty() && prepared.isNotEmpty() &&
                        out.last().whitespace && prepared.first().whitespace
                    if (duplicateSpace) out.addAll(prepared.drop(1)) else out.addAll(prepared)
                }
                is InlineItem.HardBreak -> out.add(
                    PreparedCluster("", 0, 0, 0f, false, true, ClusterPayload(item.style)),
                )
                is InlineItem.Marker -> out.add(
                    PreparedCluster("", 0, 0, 0f, false, false, ClusterPayload(item.style, markerNode = item.node)),
                )
                is InlineItem.Atomic -> {
                    val metrics = atomicMetrics(item, availableMain)
                    out.add(
                        PreparedCluster(
                            text = "",
                            start = 0,
                            end = 0,
                            width = metrics.width,
                            whitespace = false,
                            hardBreak = false,
                            payload = ClusterPayload(item.style, atomic = metrics),
                        ),
                    )
                }
                is InlineItem.Ruby -> {
                    val metrics = rubyMetrics(item)
                    out.add(
                        PreparedCluster(
                            text = "",
                            start = 0,
                            end = 0,
                            width = metrics.width,
                            whitespace = false,
                            hardBreak = false,
                            payload = ClusterPayload(item.style, atomic = metrics),
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun markerFragment(
        box: Box,
        markerMain: Float,
        gutter: Float,
        lineBlock: Float,
        lineAscent: Float,
    ): InlineFragment {
        val marker = box.marker ?: ""
        val style = box.style
        val metrics = measurer.metrics(style.toFontSpec())
        val width = measurer.advanceRun(marker, style.toFontSpec())
        return InlineFragment(
            text = marker,
            style = style,
            main = markerMain + (gutter - width).coerceAtLeast(0f),
            block = lineBlock + (lineAscent - metrics.ascent),
            mainSize = width,
            blockSize = metrics.height,
            baseline = metrics.ascent,
            kind = FragmentKind.LIST_MARKER,
            node = box.node,
        )
    }

    // ------------------------------------------------------------------- atoms

    private fun atomicMetrics(item: InlineItem.Atomic, availableMain: Float): AtomicMetrics {
        if (item.kind == FragmentKind.IMAGE) {
            val image = resolveImageSize(item.src, item.style, availableMain)
            return AtomicMetrics(
                item = item,
                width = image.width,
                height = image.height,
                ascent = image.height,
                descent = 0f,
                placeholder = image.placeholder,
            )
        }
        val explicitWidth = item.style.width?.let { item.style.resolvePx(it, availableMain) }
        val explicitHeight = item.style.height?.let { item.style.resolvePx(it, availableMain) }
        val font = item.style.toFontSpec()
        val text = item.box?.inline?.joinToString("") { inline ->
            when (inline) {
                is InlineItem.Text -> inline.text
                is InlineItem.Atomic -> " "
                else -> ""
            }
        }.orEmpty()
        val width = explicitWidth ?: measurer.advanceRun(text, font).coerceAtMost(availableMain)
        val metrics = measurer.metrics(font)
        val height = explicitHeight ?: (item.style.lineHeightPx ?: metrics.height)
        return AtomicMetrics(
            item = item,
            width = width.coerceAtLeast(1f),
            height = height.coerceAtLeast(1f),
            ascent = height.coerceAtLeast(1f),
            descent = 0f,
        )
    }

    /**
     * Ruby is one unbreakable atomic: its width is the sum of the base
     * clusters, and its height reserves an annotation band on the block-before
     * side of the base text.
     */
    private fun rubyMetrics(item: InlineItem.Ruby): AtomicMetrics {
        val baseMains = ArrayList<Float>(item.baseRuns.size * 2)
        val baseWidths = ArrayList<Float>(item.baseRuns.size * 2)
        var width = 0f
        var baseAscent = 0f
        var baseDescent = 0f
        for (run in item.baseRuns) {
            val font = run.style.toFontSpec()
            val metrics = measurer.metrics(font)
            baseAscent = maxOf(baseAscent, metrics.ascent)
            baseDescent = maxOf(baseDescent, metrics.descent)
            for (grapheme in GraphemeCluster.split(run.text)) {
                val advance = measurer.advance(grapheme.text, font)
                baseMains.add(width)
                baseWidths.add(advance)
                width += advance
            }
        }
        val annotationHeight = measurer.metrics(item.annotationStyle.toFontSpec()).height
        val ascent = annotationHeight + baseAscent
        return AtomicMetrics(
            item = item,
            width = width.coerceAtLeast(1f),
            height = (ascent + baseDescent).coerceAtLeast(1f),
            ascent = ascent,
            descent = baseDescent,
            ruby = RubyMetrics(
                baseRuns = item.baseRuns,
                baseMains = baseMains,
                baseWidths = baseWidths,
                annotation = item.annotation,
                annotationStyle = item.annotationStyle,
                annotationHeight = annotationHeight,
                baseAscent = baseAscent,
            ),
        )
    }

    private fun resolveImageSize(
        src: String?,
        style: ComputedStyle,
        availableMain: Float,
    ): ResolvedImage {
        val intrinsic = src?.let { imageSizes.intrinsicSize(it) }
        val explicitWidth = style.width?.let { style.resolvePx(it, availableMain) }
        val explicitHeight = style.height?.let { style.resolvePx(it, availableMain) }
        var placeholder = false
        var width: Float
        var height: Float
        when {
            explicitWidth == null && explicitHeight == null -> {
                if (intrinsic != null) {
                    width = intrinsic.width
                    height = intrinsic.height
                } else {
                    width = minOf(availableMain, style.fontSizePx * 4f)
                    height = width
                    placeholder = imageSizes !== ImageSizeProvider.NONE
                }
            }
            explicitWidth == null -> {
                height = explicitHeight!!
                width = if (intrinsic != null && intrinsic.height > 0f) {
                    height * intrinsic.width / intrinsic.height
                } else {
                    height
                }
            }
            explicitHeight == null -> {
                width = explicitWidth
                height = if (intrinsic != null && intrinsic.width > 0f) {
                    width * intrinsic.height / intrinsic.width
                } else {
                    width
                }
            }
            else -> {
                width = explicitWidth
                height = explicitHeight
            }
        }
        if (placeholder && src != null) {
            warnings.warnOnce("image.missing:$src", "image resource not found, using a placeholder: $src")
        }

        // Fit the column first, then honor max-width/max-height (CSS 2.1 §10.4).
        if (availableMain > 0f && width > availableMain) {
            height = height * availableMain / width
            width = availableMain
        }
        style.maxWidth?.let { style.resolvePx(it, availableMain) }?.let { max ->
            if (max > 0f && width > max) {
                height = height * max / width
                width = max
            }
        }
        style.maxHeight?.let { style.resolvePx(it, config.heightPx) }?.let { max ->
            if (max > 0f && height > max) {
                width = width * max / height
                height = max
            }
        }
        return ResolvedImage(width.coerceAtLeast(1f), height.coerceAtLeast(1f), placeholder)
    }

    private fun intrinsicWidth(box: Box): Float? {
        val node = box.node ?: return null
        val src = node.attr("src") ?: return null
        return imageSizes.intrinsicSize(org.libepub.core.container.ResourcePath.resolve(
            org.libepub.core.container.ResourcePath.parent(document.href),
            src,
        ))?.width
    }

    private fun layoutImageBox(box: Box, main: Float, block: Float, availableMain: Float): Float {
        val style = box.style
        val marginLeft = marginOf(style.margin.left, availableMain)
        val marginRight = marginOf(style.margin.right, availableMain)
        val src = box.node?.attr("src")?.let {
            org.libepub.core.container.ResourcePath.resolve(
                org.libepub.core.container.ResourcePath.parent(document.href),
                it,
            )
        }
        val image = resolveImageSize(src, style, availableMain)
        val centered = style.textAlign == TextAlign.CENTER
        val mainOffset = if (centered) {
            marginLeft + ((availableMain - marginLeft - marginRight - image.width) / 2f).coerceAtLeast(0f)
        } else {
            marginLeft
        }
        recordBox(
            box = box,
            main = main + mainOffset,
            block = block,
            mainSize = image.width,
            blockSize = image.height,
            contentMain = main + mainOffset,
            contentBlock = block,
            contentMainSize = image.width,
            contentBlockSize = image.height,
            src = src,
            alt = box.node?.attr("alt"),
            placeholder = image.placeholder,
        )
        recordAnchor(box.node, main + mainOffset, block)
        return image.height
    }

    private fun layoutRule(box: Box, main: Float, block: Float, availableMain: Float): Float {
        val style = box.style
        val marginLeft = marginOf(style.margin.left, availableMain)
        val width = style.width?.let { style.resolvePx(it, availableMain) }
            ?: (availableMain - marginLeft - marginOf(style.margin.right, availableMain)).coerceAtLeast(0f)
        val height = maxOf(
            1f,
            borderWidth(style.border.top) + borderWidth(style.border.bottom),
        )
        recordBox(
            box = box,
            main = main + marginLeft,
            block = block,
            mainSize = width,
            blockSize = height,
            contentMain = main + marginLeft,
            contentBlock = block,
            contentMainSize = width,
            contentBlockSize = height,
        )
        recordAnchor(box.node, main + marginLeft, block)
        return height
    }

    // ------------------------------------------------------------------ tables

    private fun layoutTable(
        box: Box,
        main: Float,
        block: Float,
        availableMain: Float,
        floats: FloatTracker,
    ): Float {
        val style = box.style
        val marginLeft = marginOf(style.margin.left, availableMain)
        val marginRight = marginOf(style.margin.right, availableMain)
        val borderLeft = borderWidth(style.border.left)
        val borderRight = borderWidth(style.border.right)
        val borderTop = borderWidth(style.border.top)
        val borderBottom = borderWidth(style.border.bottom)
        val paddingLeft = style.resolvePx(style.padding.left, availableMain)
        val paddingRight = style.resolvePx(style.padding.right, availableMain)
        val paddingTop = style.resolvePx(style.padding.top, availableMain)
        val paddingBottom = style.resolvePx(style.padding.bottom, availableMain)

        val contentMainSize = (availableMain - marginLeft - marginRight - borderLeft - borderRight -
            paddingLeft - paddingRight).coerceAtLeast(1f)
        val contentMain = main + marginLeft + borderLeft + paddingLeft
        val contentBlock = block + borderTop + paddingTop

        val rows = box.children.filter { it.kind == BoxKind.TABLE_ROW }
        val columns = rows.maxOfOrNull { row -> row.children.sumOf { it.colSpan.coerceAtLeast(1) } }
            ?.coerceAtLeast(1) ?: 1
        val columnWidth = contentMainSize / columns

        var cursor = contentBlock
        for (row in rows) {
            val rowBlock = cursor
            var column = 0
            var bottom = cursor
            for (cell in row.children) {
                val span = cell.colSpan.coerceAtLeast(1)
                val cellMain = contentMain + column * columnWidth
                val cellWidth = columnWidth * span
                val cellHeight = layoutBox(cell, cellMain, rowBlock, cellWidth, floats)
                bottom = maxOf(bottom, rowBlock + cellHeight)
                column += span
            }
            val rowHeight = maxOf(bottom - rowBlock, 1f)
            boxes.add(
                LaidOutBox(
                    node = row.node,
                    kind = BoxKind.TABLE_ROW,
                    style = row.style,
                    main = contentMain,
                    block = rowBlock,
                    mainSize = contentMainSize,
                    blockSize = rowHeight,
                    contentMain = contentMain,
                    contentBlock = rowBlock,
                    contentMainSize = contentMainSize,
                    contentBlockSize = rowHeight,
                ),
            )
            cursor = bottom
        }

        val contentBlockSize = cursor - contentBlock
        val borderBoxMain = contentMainSize + paddingLeft + paddingRight + borderLeft + borderRight
        val borderBoxBlock = contentBlockSize + paddingTop + paddingBottom + borderTop + borderBottom
        recordBox(
            box = box,
            main = main + marginLeft,
            block = block,
            mainSize = borderBoxMain,
            blockSize = borderBoxBlock,
            contentMain = contentMain,
            contentBlock = contentBlock,
            contentMainSize = contentMainSize,
            contentBlockSize = contentBlockSize,
        )
        recordAnchor(box.node, main + marginLeft, block)
        return borderBoxBlock
    }

    // ----------------------------------------------------------------- helpers

    private fun recordBox(
        box: Box,
        main: Float,
        block: Float,
        mainSize: Float,
        blockSize: Float,
        contentMain: Float,
        contentBlock: Float,
        contentMainSize: Float,
        contentBlockSize: Float,
        src: String? = null,
        alt: String? = null,
        placeholder: Boolean = false,
    ) {
        boxes.add(
            LaidOutBox(
                node = box.node,
                kind = box.kind,
                style = box.style,
                main = main,
                block = block,
                mainSize = mainSize,
                blockSize = blockSize,
                contentMain = contentMain,
                contentBlock = contentBlock,
                contentMainSize = contentMainSize,
                contentBlockSize = contentBlockSize,
                src = src,
                alt = alt,
                placeholder = placeholder,
            ),
        )
    }

    private fun recordAnchor(node: DocNode?, main: Float, block: Float) {
        if (node == null) return
        val id = node.attr("id") ?: return
        anchors.putIfAbsent(id, LayoutAnchor(node, main, block))
        if (node.attrLocal("type") == "pagebreak") {
            val label = node.attr("title")?.takeIf { it.isNotBlank() }
                ?: node.textContent().trim().takeIf { it.isNotEmpty() }
                ?: id
            pageBreaks.add(PageBreakPoint(block, label))
        }
    }

    private fun markerGutter(box: Box): Float {
        val marker = box.marker ?: return 0f
        val style = box.style
        return measurer.advanceRun(marker, style.toFontSpec()) + style.fontSizePx * 0.5f
    }

    /** Block top of a fragment, honoring `vertical-align: top/middle/bottom`. */
    private fun fragmentTop(
        style: ComputedStyle,
        cursor: Float,
        lineHeight: Float,
        ascent: Float,
        height: Float,
        defaultTop: Float,
    ): Float = when (val align = style.verticalAlign) {
        is VerticalAlign.Keyword -> when (align.value) {
            "top", "text-top" -> cursor
            "bottom", "text-bottom" -> cursor + lineHeight - height
            "middle" -> cursor + (lineHeight - height) / 2f
            else -> defaultTop
        }
        else -> defaultTop
    }

    private fun verticalShift(style: ComputedStyle): Float = when (val align = style.verticalAlign) {
        is VerticalAlign.Keyword -> when (align.value) {
            "super" -> -style.fontSizePx * 0.33f
            "sub" -> style.fontSizePx * 0.33f
            else -> 0f
        }
        is VerticalAlign.OfLength -> -align.length.value
    }

    private fun marginOf(dimension: Dimension, basePx: Float): Float = when (dimension) {
        Dimension.Auto -> 0f
        is Dimension.Of -> {
            val length = dimension.length
            if (length.unit == org.libepub.core.css.LengthUnit.PERCENT) {
                basePx * length.value / 100f
            } else {
                length.value
            }
        }
    }

    private fun borderWidth(side: org.libepub.core.css.BorderSide): Float {
        if (side.style == null || side.style == BorderStyle.NONE || side.style == BorderStyle.HIDDEN) return 0f
        return side.width?.value ?: 0f
    }

    private fun collapseMargins(first: Float, second: Float): Float = when {
        first >= 0f && second >= 0f -> maxOf(first, second)
        first <= 0f && second <= 0f -> minOf(first, second)
        else -> first + second
    }
}
