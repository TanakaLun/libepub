package org.libepub.core.css

import org.libepub.core.util.WarningSink

/** One CSS declaration; [value] stays raw text until the value layer parses it. */
data class Declaration(
    /** Normalized property: lowercased, vendor prefixes stripped. */
    val property: String,
    /** Property exactly as authored (diagnostics only). */
    val rawProperty: String,
    /** Raw value text (whitespace collapsed, `!important` removed). */
    val value: String,
    val important: Boolean,
)

data class StyleRule(
    val selectors: List<Selector>,
    val declarations: List<Declaration>,
    /** Global source order across the stylesheet and flattened @media blocks. */
    val sourceOrder: Int,
)

data class FontFace(
    val family: String?,
    /** First `url(...)` of `src`, resolved to a zip-root path. */
    val src: String?,
    val weight: String?,
    val style: String?,
    val declarations: List<Declaration>,
)

/** Parsed stylesheet: style rules, font faces, and imports. */
class Stylesheet(
    val rules: List<StyleRule>,
    val fontFaces: List<FontFace>,
    val imports: List<String>,
    val warnings: WarningSink,
    /** Zip-root directory the sheet was loaded from; relative `url(...)`s resolve against it. */
    val baseDir: String = "",
) {
    companion object {
        fun empty(warnings: WarningSink = WarningSink()) = Stylesheet(emptyList(), emptyList(), emptyList(), warnings)
    }
}
