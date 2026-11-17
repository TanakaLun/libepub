package org.libepub.render.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal text button built from foundation primitives only.
 *
 * The library intentionally has no Material dependency: overlays must look
 * native whether the host uses Material 2, Material 3, Cupertino or a
 * hand-rolled design system. Press feedback still comes from the host, because
 * `clickable` reads `LocalIndication` — Material themes install their ripple,
 * other toolkits install theirs, and the fallback is a neutral highlight.
 *
 * 48dp minimum touch target, matching the platform guidance.
 */
@Composable
internal fun OverlayTextButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = text, style = TextStyle(color = color, fontSize = 14.sp))
    }
}
