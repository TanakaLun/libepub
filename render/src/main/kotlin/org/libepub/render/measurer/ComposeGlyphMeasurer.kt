package org.libepub.render.measurer

import android.graphics.Paint
import android.graphics.Typeface
import org.libepub.core.layout.FontMetrics
import org.libepub.core.layout.FontSpec
import org.libepub.core.layout.GlyphMeasurer
import org.libepub.core.layout.GraphemeCluster
import org.libepub.core.layout.Kinsoku
import org.libepub.render.font.FontRegistry

/**
 * Platform text measurement for the layout engine. Advances are reported in
 * layout pixels (the engine's coordinate system); the render layer draws in the
 * same pixels, so no density scaling is applied here.
 *
 * The measurer never caches per-glyph advances: `Paint` already does, and the
 * layout cache above is keyed by the whole geometry.
 *
 * Some platforms (notably the JVM/Robolectric graphics stub) answer
 * `measureText` with a placeholder and report zero font metrics. When that
 * happens the measurer falls back to an em-based estimate so layout stays
 * finite instead of collapsing to zero-width lines. Real devices always take
 * the `Paint` path.
 */
class ComposeGlyphMeasurer(
    private val fonts: FontRegistry? = null,
    private val defaultTypeface: Typeface? = null,
) : GlyphMeasurer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        defaultTypeface?.let { typeface = it }
    }
    private val fontMetrics = Paint.FontMetrics()

    override fun advance(grapheme: String, font: FontSpec): Float {
        if (grapheme.isEmpty()) return 0f
        apply(font)
        val measured = paint.measureText(grapheme)
        return if (hasUsableMetrics()) measured else estimate(grapheme, font)
    }

    override fun advanceRun(text: String, font: FontSpec): Float {
        if (text.isEmpty()) return 0f
        apply(font)
        val measured = paint.measureText(text)
        return if (hasUsableMetrics()) measured else estimate(text, font)
    }

    override fun metrics(font: FontSpec): FontMetrics {
        apply(font)
        paint.getFontMetrics(fontMetrics)
        if (fontMetrics.ascent == 0f && fontMetrics.descent == 0f) {
            return FontMetrics(ascent = font.sizePx * 0.8f, descent = font.sizePx * 0.2f)
        }
        return FontMetrics(
            ascent = (-fontMetrics.ascent).coerceAtLeast(0f),
            descent = fontMetrics.descent.coerceAtLeast(0f),
        )
    }

    private fun apply(font: FontSpec) {
        paint.textSize = font.sizePx
        paint.typeface = fonts?.typefaceFor(font) ?: defaultTypeface
        paint.isFakeBoldText = font.weight >= 600
        paint.textSkewX = if (font.italic) -0.25f else 0f
    }

    private fun hasUsableMetrics(): Boolean {
        paint.getFontMetrics(fontMetrics)
        return fontMetrics.ascent != 0f || fontMetrics.descent != 0f
    }

    private fun estimate(grapheme: String, font: FontSpec): Float {
        val em = font.sizePx
        var total = 0f
        var index = 0
        while (index < grapheme.length) {
            val codePoint = grapheme.codePointAt(index)
            total += when {
                Character.isWhitespace(codePoint) -> em * 0.25f
                Kinsoku.isCjkCodePoint(codePoint) -> em
                else -> em * 0.5f
            }
            index += Character.charCount(codePoint)
        }
        return total
    }

    /** Advance of a single grapheme, exposed for the render golden tests. */
    fun advanceOf(grapheme: String, fontSizePx: Float): Float =
        advance(grapheme, FontSpec(sizePx = fontSizePx))

    /** Number of grapheme clusters in [text] (used by justify drawing). */
    fun clusterCount(text: String): Int = GraphemeCluster.split(text).size
}
