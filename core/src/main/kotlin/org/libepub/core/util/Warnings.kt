package org.libepub.core.util

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Collects non-fatal parse/render degradations. The library never throws on
 * unknown CSS, undefined classes, broken optional markup etc.; it degrades and
 * records one message per distinct key so callers can surface a report.
 */
class WarningSink {
    private val messages = CopyOnWriteArrayList<String>()
    private val seenKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    fun warn(message: String) {
        messages.add(message)
    }

    /** Records at most once per [key]; useful for per-rule/per-element spam. */
    fun warnOnce(key: String, message: String) {
        if (seenKeys.add(key)) messages.add(message)
    }

    val all: List<String> get() = messages.toList()

    override fun toString(): String = all.joinToString("\n")
}
