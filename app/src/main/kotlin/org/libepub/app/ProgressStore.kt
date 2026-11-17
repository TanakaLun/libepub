package org.libepub.app

import android.content.Context
import org.libepub.core.model.Locator

/** Persistence seam so the reader state machine is testable on the JVM. */
interface ProgressStore {
    fun load(bookKey: String): Locator?
    fun save(bookKey: String, locator: Locator)
}

/**
 * `SharedPreferences`-backed progress store. A locator is encoded as four
 * fields separated by U+0001 (never valid in an href or fragment), which keeps
 * persistence dependency-free and human-debuggable via `adb shell run-as`.
 */
class SharedPreferencesProgressStore(context: Context) : ProgressStore {

    private val prefs = context.getSharedPreferences("libepub-progress", Context.MODE_PRIVATE)

    override fun load(bookKey: String): Locator? {
        val raw = prefs.getString(key(bookKey), null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size < 4) return null
        return Locator(
            href = parts[0],
            fragment = parts[1].ifEmpty { null },
            block = parts[2].toFloatOrNull() ?: 0f,
            pageIndex = parts[3].toIntOrNull() ?: 0,
        )
    }

    override fun save(bookKey: String, locator: Locator) {
        val encoded = listOf(
            locator.href,
            locator.fragment.orEmpty(),
            locator.block.toString(),
            locator.pageIndex.toString(),
        ).joinToString(SEPARATOR.toString())
        prefs.edit().putString(key(bookKey), encoded).apply()
    }

    private fun key(bookKey: String): String = "locator:$bookKey"

    private companion object {
        const val SEPARATOR = '\u0001'
    }
}
