package org.libepub.core.script

/** Whether a script lives in the document or is pulled from the container. */
enum class ScriptOrigin { INLINE, EXTERNAL }

/**
 * How well the reader reproduces what a script does. Scripts are never
 * executed; the engine either has a native equivalent ([HANDLED]), covers part
 * of the effect ([PARTIAL]) or shows the static markup as written
 * ([UNSUPPORTED]).
 */
enum class ScriptSupport { HANDLED, PARTIAL, UNSUPPORTED }

/**
 * One behaviour recognised in a script, together with the reader's answer.
 * [id] is stable and safe to switch on; [evidence] is a short literal from the
 * source that triggered the match, useful in reports and bug reports.
 */
class ScriptPattern(
    val id: String,
    val description: String,
    val support: ScriptSupport,
    val evidence: String,
) {
    override fun toString(): String = "$id ($support)"
}

/** One `<script>` element, resolved and analysed. */
class ScriptSource(
    val origin: ScriptOrigin,
    /** The `src` attribute exactly as written; null for inline scripts. */
    val src: String? = null,
    /** Zip-root path the `src` resolved to, when the file exists. */
    val href: String? = null,
    /** `type` (or legacy `language`) attribute, when present. */
    val type: String? = null,
    /** Characters of script text that were analysed. */
    val chars: Int = 0,
    /** Bundled library this file ships (`jQuery`, `Zepto`, ...), if recognised. */
    val library: String? = null,
    val patterns: List<ScriptPattern> = emptyList(),
) {
    /** Human-readable identity for warnings and reports. */
    val label: String get() = href ?: src ?: "inline script"
}

/**
 * Every script found in one content document, with the behaviours recognised
 * in them. Empty for the vast majority of books.
 */
class ScriptAudit(
    val sources: List<ScriptSource> = emptyList(),
    /** Scripts that were not analysed: non-JS `type`, remote URL, missing file. */
    val skipped: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = sources.isEmpty() && skipped.isEmpty()

    /** Distinct patterns across all sources, in first-seen order. */
    val patterns: List<ScriptPattern> by lazy {
        val seen = LinkedHashMap<String, ScriptPattern>(4)
        for (source in sources) {
            for (pattern in source.patterns) {
                if (!seen.containsKey(pattern.id)) seen[pattern.id] = pattern
            }
        }
        seen.values.toList()
    }

    val handled: List<ScriptPattern> get() = patterns.filter { it.support == ScriptSupport.HANDLED }

    val unsupported: List<ScriptPattern> get() = patterns.filter { it.support == ScriptSupport.UNSUPPORTED }

    /** True when the document ships scripts and every one of them is reproduced natively. */
    val fullyHandled: Boolean get() = sources.isNotEmpty() && unsupported.isEmpty()

    /** Libraries bundled by this document, e.g. `["jQuery"]`. */
    val libraries: List<String> get() = sources.mapNotNull { it.library }.distinct()

    companion object {
        val NONE = ScriptAudit()
    }
}

/**
 * Book-level view over every chapter that ships scripts, produced by
 * [org.libepub.core.parse.BookParser.scriptReport]. The reader can use it to
 * tell the user "this book relies on scripting, and here is what we do with
 * it" without ever running any JavaScript.
 */
class ScriptReport(val chapters: Map<String, ScriptAudit> = emptyMap()) {

    val isEmpty: Boolean get() = chapters.isEmpty()

    /** Distinct patterns across the whole book, in first-seen order. */
    val patterns: List<ScriptPattern>
        get() = chapters.values.flatMap { it.patterns }.distinctBy { it.id }

    val handled: List<ScriptPattern> get() = patterns.filter { it.support == ScriptSupport.HANDLED }

    val unsupported: List<ScriptPattern> get() = patterns.filter { it.support == ScriptSupport.UNSUPPORTED }

    /** True when at least one chapter ships scripts and none of them is unsupported. */
    val fullyHandled: Boolean get() = chapters.isNotEmpty() && unsupported.isEmpty()

    /** Bundled libraries across the book, e.g. `["jQuery"]`. */
    val libraries: List<String>
        get() = chapters.values.flatMap { it.sources }.mapNotNull { it.library }.distinct()

    /** Chapter hrefs whose scripts use [patternId]. */
    fun chaptersUsing(patternId: String): List<String> =
        chapters.filterValues { audit -> audit.patterns.any { it.id == patternId } }.keys.toList()
}
