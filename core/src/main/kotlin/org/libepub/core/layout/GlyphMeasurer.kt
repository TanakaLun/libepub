package org.libepub.core.layout

import org.libepub.core.css.FontStyle
import org.libepub.core.style.ComputedStyle

/** Everything the measurer needs to know about a piece of text. */
data class FontSpec(
    val family: List<String> = listOf("serif"),
    val sizePx: Float = 16f,
    val weight: Int = 400,
    val italic: Boolean = false,
)

/** Vertical metrics of a font at a concrete size; both values are positive distances from the baseline. */
data class FontMetrics(val ascent: Float, val descent: Float) {
    val height: Float get() = ascent + descent
}

/**
 * Injected text measurement. `:core` never touches Android/Compose; the render
 * module supplies an implementation backed by the platform text stack, tests
 * supply synthetic advances for exact golden coordinates.
 */
interface GlyphMeasurer {

    /** Advance width (horizontal) / height (vertical) of one grapheme cluster. */
    fun advance(grapheme: String, font: FontSpec): Float

    fun metrics(font: FontSpec): FontMetrics

    /** Advance of a whole run; defaults to the sum of its clusters. */
    fun advanceRun(text: String, font: FontSpec): Float {
        var total = 0f
        for (grapheme in GraphemeCluster.split(text)) total += advance(grapheme.text, font)
        return total
    }
}

/** Bridge from computed style to measurement input. */
fun ComputedStyle.toFontSpec(): FontSpec = FontSpec(
    family = fontFamily,
    sizePx = fontSizePx,
    weight = fontWeight,
    italic = fontStyle != FontStyle.NORMAL,
)
