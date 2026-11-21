package org.libepub.core.script

import org.libepub.core.container.ResourcePath
import org.libepub.core.util.WarningSink

/** Supplies the text of an external script (`zip-root path -> source`). */
fun interface ScriptSourceProvider {
    fun read(href: String): String?
}

/** A `<script>` captured by the markup normalizer, before `src` is resolved. */
internal class RawScript(
    val src: String?,
    val type: String?,
    val inline: String,
)

/**
 * Recognises what a script *does* instead of running it, and maps that to the
 * reader's native capabilities.
 *
 * EPUB scripts in practice come in a handful of shapes: a bundled jQuery plus
 * a few lines that rewrite duokan footnote markers, a visibility toggle, an
 * image resize, or a scroll/anchor fix. This auditor classifies those cases so
 * the engine can either claim a native equivalent (the footnote marker, image
 * groups) or report precisely what it is dropping.
 *
 * Matching is deliberately payload-based (`duokan-footnote`, `【注】`,
 * `document.write`) rather than method-name based: a minified jQuery contains
 * `.hide()`, `.show()` and `scrollTo` itself, so generic method names would
 * produce false positives. When a library is detected, only the application
 * tail after its closing IIFE is classified.
 */
object ScriptAuditor {

    /** Zip-root path -> source; missing files return null. */
    internal fun audit(
        documentHref: String,
        raw: List<RawScript>,
        provider: ScriptSourceProvider?,
        warnings: WarningSink,
    ): ScriptAudit {
        if (raw.isEmpty()) return ScriptAudit.NONE

        val baseDir = ResourcePath.parent(documentHref)
        val sources = ArrayList<ScriptSource>(raw.size)
        val skipped = ArrayList<String>(0)

        for (script in raw) {
            if (!isJavaScript(script.type)) {
                skipped.add(script.src ?: script.type ?: "inline script")
                warnings.warnOnce(
                    "script.non-js:${script.src ?: script.type}",
                    "ignored <script> with non-JavaScript type \"${script.type}\"",
                )
                continue
            }

            val src = script.src?.trim()?.takeIf { it.isNotEmpty() }
            val text: String
            val href: String?
            if (src == null) {
                text = script.inline
                href = null
            } else {
                if (src.contains("://")) {
                    skipped.add(src)
                    warnings.warnOnce("script.remote:$src", "remote script ignored: $src")
                    continue
                }
                href = ResourcePath.resolve(baseDir, src)
                val resolved = provider?.read(href)
                if (resolved == null) {
                    skipped.add(href)
                    warnings.warnOnce("script.missing:$href", "missing script: $href")
                    continue
                }
                text = resolved
            }

            val body = text.trim()
            if (body.isEmpty()) continue

            val library = detectLibrary(body)
            val code = applicationCode(body, library)
            val patterns = classify(code)

            sources.add(
                ScriptSource(
                    origin = if (src == null) ScriptOrigin.INLINE else ScriptOrigin.EXTERNAL,
                    src = src,
                    href = href,
                    type = script.type,
                    chars = body.length,
                    library = library,
                    patterns = patterns,
                ),
            )
        }

        for (source in sources) report(source, warnings)
        return ScriptAudit(sources, skipped)
    }

    // --------------------------------------------------------------- reporting

    private fun report(source: ScriptSource, warnings: WarningSink) {
        val name = source.label
        val unsupported = source.patterns.filter { it.support == ScriptSupport.UNSUPPORTED }
        when {
            unsupported.isNotEmpty() -> warnings.warnOnce(
                "script.unsupported:$name",
                "script $name uses unsupported ${unsupported.joinToString(", ") { it.id }}; " +
                    "JS is not executed and the static markup is shown",
            )
            source.patterns.isNotEmpty() -> warnings.warnOnce(
                "script.handled:$name",
                "script $name: ${source.patterns.joinToString(", ") { it.id }} handled natively (JS is not executed)",
            )
            source.library != null -> warnings.warnOnce(
                "script.library:$name",
                "script $name bundles ${source.library} but runs no application code; nothing to emulate",
            )
        }
    }

    // ------------------------------------------------------------ recognition

    private class Rule(
        val id: String,
        val description: String,
        val support: ScriptSupport,
        val match: (String) -> String?,
    )

    private fun contains(vararg needles: String): (String) -> String? = { code ->
        needles.firstOrNull { code.contains(it) }
    }

    private val RULES = listOf(
        Rule(
            id = "duokan-footnote-marker",
            description = "duokan footnote markers are drawn as 【注】",
            support = ScriptSupport.HANDLED,
            match = { code ->
                when {
                    code.contains("duokan-footnote") && code.contains("【注】") -> "duokan-footnote → 【注】"
                    code.contains("duokan-footnote") -> "duokan-footnote"
                    code.contains("【注】") -> "【注】"
                    else -> null
                }
            },
        ),
        Rule(
            id = "duokan-image-group",
            description = "duokan image blocks and galleries are laid out natively",
            support = ScriptSupport.HANDLED,
            match = contains("duokan-image"),
        ),
        Rule(
            id = "document-write",
            description = "document.write injects markup that is not in the source",
            support = ScriptSupport.UNSUPPORTED,
            match = contains("document.write"),
        ),
        Rule(
            id = "navigation",
            description = "scripts that scroll or navigate are not executed",
            support = ScriptSupport.UNSUPPORTED,
            match = contains("scrollTo(", "location.href", "location.hash", "window.location"),
        ),
        Rule(
            id = "image-resize",
            description = "runtime image resizing is not applied",
            support = ScriptSupport.UNSUPPORTED,
            match = { code ->
                if (code.contains("img")) {
                    listOf(".width(", ".height(", "css('width", "css(\"width", "css('height", "css(\"height")
                        .firstOrNull { code.contains(it) }
                } else {
                    null
                }
            },
        ),
        Rule(
            id = "visibility-toggle",
            description = "scripts that hide or reveal elements at runtime are not executed",
            support = ScriptSupport.UNSUPPORTED,
            match = { code ->
                when {
                    code.contains(".toggle(") -> ".toggle("
                    code.contains(".hide(") -> ".hide("
                    code.contains(".show(") -> ".show("
                    code.contains("display") && code.contains("none") -> "display:none"
                    else -> null
                }
            },
        ),
    )

    private fun classify(code: String): List<ScriptPattern> {
        if (code.isBlank()) return emptyList()
        val out = ArrayList<ScriptPattern>(2)
        for (rule in RULES) {
            val evidence = rule.match(code) ?: continue
            out.add(ScriptPattern(rule.id, rule.description, rule.support, evidence))
        }
        if (out.isEmpty()) {
            out.add(
                ScriptPattern(
                    id = "unknown",
                    description = "unrecognised script behaviour",
                    support = ScriptSupport.UNSUPPORTED,
                    evidence = snippet(code),
                ),
            )
        }
        return out
    }

    // --------------------------------------------------------------- libraries

    private val LIBRARIES = listOf(
        "jQuery" to listOf("jQuery v", "jquery.com", "jQuery JavaScript Library", "define(\"jquery\""),
        "Zepto" to listOf("Zepto.js", "zeptojs.com"),
        "MooTools" to listOf("MooTools"),
        "Prototype" to listOf("Prototype JavaScript framework"),
        "Dojo" to listOf("dojo.js"),
    )

    private fun detectLibrary(code: String): String? {
        for ((name, signatures) in LIBRARIES) {
            if (signatures.any { code.contains(it) }) return name
        }
        return null
    }

    /** How a bundled library's IIFE usually closes; everything after is application code. */
    private val IIFE_ENDS = listOf("})(window);", "})(window)", "})(this);", "})(this)")

    /**
     * Application code inside [text]. For a recognised library this is the tail
     * after the last IIFE close, so library internals never get classified as
     * book behaviour; for a plain script it is the whole file.
     */
    private fun applicationCode(text: String, library: String?): String {
        if (library == null) return text
        var end = -1
        for (marker in IIFE_ENDS) {
            val at = text.lastIndexOf(marker)
            if (at >= 0) end = maxOf(end, at + marker.length)
        }
        return if (end >= 0) text.substring(end) else ""
    }

    // ------------------------------------------------------------------ types

    private fun isJavaScript(type: String?): Boolean {
        val t = type?.trim()?.lowercase() ?: return true
        if (t.isEmpty()) return true
        return t.contains("javascript") || t.contains("ecmascript") || t == "module"
    }

    private fun snippet(code: String): String {
        val flat = code.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 80) flat else flat.take(77) + "..."
    }
}
