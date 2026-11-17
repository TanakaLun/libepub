package org.libepub.core.layout

import org.libepub.core.css.CssFloat

internal class FloatBox(
    val side: CssFloat,
    val main: Float,
    val mainSize: Float,
    val block: Float,
    val blockSize: Float,
)

/**
 * Tracks floated boxes inside one block formatting context and reports the
 * remaining inline interval for a given block position.
 */
internal class FloatTracker(private val main: Float, private val mainSize: Float) {

    private val floats = ArrayList<FloatBox>(2)

    fun add(box: FloatBox) {
        floats.add(box)
    }

    /** Remaining (start, width) at [block], or null when floats cover the whole line. */
    fun availableAt(block: Float): Pair<Float, Float>? {
        var start = main
        var end = main + mainSize
        for (float in floats) {
            val overlaps = block + 0.01f >= float.block && block < float.block + float.blockSize - 0.01f
            if (!overlaps) continue
            if (float.side == CssFloat.LEFT) {
                start = maxOf(start, float.main + float.mainSize)
            } else {
                end = minOf(end, float.main)
            }
        }
        return if (end - start > 0.5f) start to (end - start) else null
    }

    /** First block position below every float that starts at or after [block]. */
    fun nextBottom(block: Float): Float {
        var bottom = block + 1f
        for (float in floats) {
            val floatBottom = float.block + float.blockSize
            if (floatBottom > block) bottom = maxOf(bottom, floatBottom)
        }
        return bottom
    }

    /** Bottom of the lowest float placed at or after [block] (for containing-block height). */
    fun bottomOfFloatsStartedAtOrAfter(block: Float): Float {
        var bottom = block
        for (float in floats) {
            if (float.block >= block - 0.01f) bottom = maxOf(bottom, float.block + float.blockSize)
        }
        return bottom
    }
}
