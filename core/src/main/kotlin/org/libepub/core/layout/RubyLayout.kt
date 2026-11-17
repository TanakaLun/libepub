package org.libepub.core.layout

import org.libepub.core.style.ComputedStyle

/**
 * Ruby geometry. The annotation band sits on the block-before side of the base
 * text: above it in horizontal writing, to the right of the column in
 * vertical-rl (both are "block-before" in logical coordinates, so one rule
 * covers both).
 *
 * Distribution follows the CSS Ruby model used by Japanese typesetting:
 *  - when the annotation is not longer than the base, one centered box covers
 *    the whole base group;
 *  - when it is longer, the annotation is split as evenly as possible across
 *    the base clusters, each part centered on its own base cluster.
 */
object RubyLayout {

    fun distribute(
        baseMains: List<Float>,
        baseWidths: List<Float>,
        annotation: String,
        block: Float,
        blockSize: Float,
        style: ComputedStyle,
        measurer: GlyphMeasurer,
    ): List<RubyAnnotation> {
        if (baseMains.isEmpty() || baseMains.size != baseWidths.size) return emptyList()
        val glyphs = GraphemeCluster.split(annotation).filter { it.text.isNotBlank() }
        if (glyphs.isEmpty()) return emptyList()

        val font = style.toFontSpec()
        fun widthOf(from: Int, to: Int): Float {
            var total = 0f
            for (index in from until to) total += measurer.advance(glyphs[index].text, font)
            return total
        }

        val baseCount = baseMains.size
        val groupCount = glyphs.size

        if (groupCount <= baseCount) {
            val total = baseWidths.sum()
            val annotationWidth = widthOf(0, groupCount)
            return listOf(
                RubyAnnotation(
                    text = glyphs.joinToString("") { it.text },
                    main = baseMains.first() + (total - annotationWidth) / 2f,
                    mainSize = annotationWidth,
                    block = block,
                    blockSize = blockSize,
                    style = style,
                ),
            )
        }

        val out = ArrayList<RubyAnnotation>(baseCount)
        val base = groupCount / baseCount
        val extra = groupCount % baseCount
        var cursor = 0
        for (index in 0 until baseCount) {
            val count = base + if (index < extra) 1 else 0
            if (count == 0) continue
            val text = glyphs.subList(cursor, cursor + count).joinToString("") { it.text }
            val width = widthOf(cursor, cursor + count)
            out.add(
                RubyAnnotation(
                    text = text,
                    main = baseMains[index] + (baseWidths[index] - width) / 2f,
                    mainSize = width,
                    block = block,
                    blockSize = blockSize,
                    style = style,
                ),
            )
            cursor += count
        }
        return out
    }
}
