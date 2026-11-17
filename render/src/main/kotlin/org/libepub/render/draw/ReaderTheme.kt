package org.libepub.render.draw

import androidx.compose.ui.graphics.Color
import org.libepub.core.css.CssColor

/** Reader colors. Text colors normally come from the book's CSS; [foreground]
 *  overrides them for night/sepia modes without re-laying out. */
data class ReaderTheme(
    val key: String = "paper",
    val background: Color = Color(0xFFF7F3E9),
    val foreground: Color? = null,
    val accent: Color = Color(0xFF8A6D3B),
) {
    companion object {
        val PAPER = ReaderTheme()
        val NIGHT = ReaderTheme(
            key = "night",
            background = Color(0xFF121212),
            foreground = Color(0xFFE6E1D6),
            accent = Color(0xFFB9A06A),
        )
        val SEPIA = ReaderTheme(
            key = "sepia",
            background = Color(0xFFF0E2C8),
            foreground = Color(0xFF3B2F1E),
            accent = Color(0xFF8A6D3B),
        )

        fun of(key: String): ReaderTheme = when (key) {
            NIGHT.key -> NIGHT
            SEPIA.key -> SEPIA
            else -> PAPER
        }
    }
}

fun CssColor.toComposeColor(): Color = when (this) {
    CssColor.Current -> Color.Black
    is CssColor.Rgba -> Color(argb.toInt())
}
