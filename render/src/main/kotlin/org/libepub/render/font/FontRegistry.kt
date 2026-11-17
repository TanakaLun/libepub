package org.libepub.render.font

import android.graphics.Typeface
import org.libepub.core.css.Stylesheet
import org.libepub.core.layout.FontSpec
import org.libepub.core.util.WarningSink

/**
 * Book font registry: `@font-face` families from every stylesheet the book
 * ships, resolved lazily through a [FontLoader].
 *
 * Unknown families fall back to the platform default once, with a warning — a
 * book that asks for `DK-SONGTI` (duokan's proprietary face) must still render.
 * Generic CSS families are not warned about: they are expected to be handled by
 * the platform.
 */
class FontRegistry(
    private val loader: FontLoader?,
    private val warnings: WarningSink,
) {
    private val faces = LinkedHashMap<String, String>() // lower-case family -> zip path
    private val resolved = HashMap<String, Typeface?>()
    private val warned = HashSet<String>()

    val families: Set<String> get() = faces.keys.toSet()

    fun register(sheets: List<Stylesheet>) {
        for (sheet in sheets) {
            for (face in sheet.fontFaces) {
                val family = face.family?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: continue
                val src = face.src ?: continue
                faces.putIfAbsent(family.lowercase(), src)
            }
        }
    }

    /** Typeface for [spec], or null to use the platform default. */
    fun typefaceFor(spec: FontSpec): Typeface? {
        if (faces.isEmpty()) return null
        for (name in spec.family) {
            val key = name.trim().trim('"', '\'').lowercase()
            if (key in GENERIC) continue
            val src = faces[key] ?: continue
            resolved[key]?.let { return it }
            val typeface = loader?.load(src)
            resolved[key] = typeface
            return typeface
        }
        // Only warn for a non-generic family that the book explicitly asked for
        // and did not provide; otherwise every Latin run would warn.
        for (name in spec.family) {
            val key = name.trim().trim('"', '\'').lowercase()
            if (key.isEmpty() || key in GENERIC) continue
            if (key !in faces && warned.add(key)) {
                warnings.warnOnce("font.missing:$key", "font family not found, using the default: $key")
            }
            break
        }
        return null
    }

    private companion object {
        val GENERIC = setOf(
            "serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui",
            "ui-serif", "ui-sans-serif", "ui-monospace", "ui-rounded", "emoji", "math",
        )
    }
}
