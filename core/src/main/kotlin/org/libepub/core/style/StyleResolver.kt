package org.libepub.core.style

import org.libepub.core.container.ResourcePath
import org.libepub.core.css.CssParser
import org.libepub.core.css.Specificity
import org.libepub.core.css.Stylesheet
import org.libepub.core.model.Document
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.DocNode
import org.libepub.core.xhtml.NodeKind

/**
 * Cascade + computed-style pass.
 *
 * Ordering is the CSS cascade reduced to what a reading system needs:
 * UA normal < presentational hints < author normal < inline normal <
 * author `!important` < inline `!important` < UA `!important`; ties break on
 * specificity, then source order.
 *
 * Shorthands are expanded to longhands *before* merging, so mixing
 * `border`/`border-top` and `margin`/`margin-left` composes correctly
 * regardless of declaration order.
 *
 * The resolver is stateless after construction and safe to reuse for many
 * documents; the returned map is identity-keyed on [DocNode].
 */
class StyleResolver(
    authorSheets: List<Stylesheet> = emptyList(),
    /** Base font size and `rem` basis (the reader's font-size setting). */
    private val initialFontSizePx: Float = 16f,
    private val viewportWidthPx: Float = 400f,
    private val viewportHeightPx: Float = 800f,
    private val warnings: WarningSink = WarningSink(),
    uaSheet: Stylesheet = UAStyles.stylesheet(),
) {

    private class ExpandedDeclaration(val property: String, val value: String, val important: Boolean)

    private class RuleEntry(
        val selector: org.libepub.core.css.Selector,
        val declarations: List<ExpandedDeclaration>,
        val normalRank: Int,
        val importantRank: Int,
        val order: Int,
    )

    private class Candidate(
        val rank: Int,
        val specificity: Specificity,
        val order: Int,
        val property: String,
        val value: String,
    )

    private val uaRules: Map<String, List<RuleEntry>> =
        index(listOf(uaSheet), UA_RANK, UA_IMPORTANT_RANK, 0).first

    private val authorRules: Map<String, List<RuleEntry>> =
        index(authorSheets, AUTHOR_RANK, AUTHOR_IMPORTANT_RANK, 0).first

    // ------------------------------------------------------------------ public

    /** Computes styles for every element in [document]. */
    fun resolve(document: Document): Map<DocNode, ComputedStyle> {
        val out = HashMap<DocNode, ComputedStyle>(256)
        val baseDir = ResourcePath.parent(document.href)
        resolveInto(document.root, null, baseDir, out)
        return out
    }

    /** Computes one element against an explicit parent style (footnote panels, previews). */
    fun compute(node: DocNode, parent: ComputedStyle?, baseDir: String = ""): ComputedStyle {
        val candidates = ArrayList<Candidate>(12)
        collect(node, baseDir, candidates)
        val merged = LinkedHashMap<String, String>(12)
        if (candidates.isNotEmpty()) {
            candidates.sortWith(compareBy({ it.rank }, { it.specificity }, { it.order }))
            for (candidate in candidates) merged[candidate.property] = candidate.value
        }
        val builder = StyleBuilder(parent, initialFontSizePx, viewportWidthPx, viewportHeightPx, warnings)
        builder.applyAll(merged)
        return builder.build()
    }

    // ---------------------------------------------------------------- internal

    private fun resolveInto(
        node: DocNode,
        parent: ComputedStyle?,
        baseDir: String,
        out: MutableMap<DocNode, ComputedStyle>,
    ) {
        if (node.kind != NodeKind.ELEMENT) return
        val style = compute(node, parent, baseDir)
        out[node] = style
        for (child in node.children) resolveInto(child, style, baseDir, out)
    }

    private fun collect(node: DocNode, baseDir: String, into: MutableList<Candidate>) {
        val type = node.name.lowercase()
        collectBucket(uaRules[type], node, into)
        collectBucket(uaRules[UNIVERSAL], node, into)
        collectBucket(authorRules[type], node, into)
        collectBucket(authorRules[UNIVERSAL], node, into)

        for ((property, value) in presentationalHints(node)) {
            val longhands = DeclarationExpander.expand(property, value) ?: continue
            for ((longhand, longhandValue) in longhands) {
                into.add(
                    Candidate(
                        rank = HINT_RANK,
                        specificity = Specificity(0, 0, 0),
                        order = HINT_ORDER,
                        property = longhand,
                        value = resolveUrls(longhand, longhandValue, baseDir),
                    ),
                )
            }
        }

        node.inlineStyle?.forEachIndexed { index, declaration ->
            val property = CssParser.normalizeProperty(declaration.property)
            val longhands = DeclarationExpander.expand(property, declaration.value) ?: return@forEachIndexed
            val rank = if (declaration.important) INLINE_IMPORTANT_RANK else INLINE_RANK
            for ((longhand, longhandValue) in longhands) {
                into.add(
                    Candidate(
                        rank = rank,
                        specificity = Specificity(0, 0, 0),
                        order = index,
                        property = longhand,
                        value = resolveUrls(longhand, longhandValue, baseDir),
                    ),
                )
            }
        }
    }

    private fun collectBucket(entries: List<RuleEntry>?, node: DocNode, into: MutableList<Candidate>) {
        if (entries == null) return
        for (entry in entries) {
            if (!SelectorMatcher.matches(entry.selector, node)) continue
            for (declaration in entry.declarations) {
                into.add(
                    Candidate(
                        rank = if (declaration.important) entry.importantRank else entry.normalRank,
                        specificity = entry.selector.specificity,
                        order = entry.order,
                        property = declaration.property,
                        value = declaration.value,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------- index

    private fun index(
        sheets: List<Stylesheet>,
        normalRank: Int,
        importantRank: Int,
        startOrder: Int,
    ): Pair<Map<String, List<RuleEntry>>, Int> {
        val buckets = HashMap<String, MutableList<RuleEntry>>(64)
        var order = startOrder
        for (sheet in sheets) {
            if (sheet.imports.isNotEmpty()) {
                warnings.warnOnce(
                    "css.import",
                    "@import is not supported; ${sheet.imports.size} import(s) ignored",
                )
            }
            for (rule in sheet.rules) {
                val expanded = ArrayList<ExpandedDeclaration>(rule.declarations.size)
                for (declaration in rule.declarations) {
                    val longhands = DeclarationExpander.expand(declaration.property, declaration.value) ?: continue
                    for ((property, value) in longhands) {
                        expanded.add(
                            ExpandedDeclaration(
                                property = property,
                                value = resolveUrls(property, value, sheet.baseDir),
                                important = declaration.important,
                            ),
                        )
                    }
                }
                if (expanded.isEmpty()) continue
                for (selector in rule.selectors) {
                    val key = selector.compounds.lastOrNull()?.type ?: UNIVERSAL
                    buckets.getOrPut(key) { ArrayList(2) }
                        .add(RuleEntry(selector, expanded, normalRank, importantRank, order))
                }
                order++
            }
        }
        return buckets to order
    }

    // ------------------------------------------------------- presentational HTML

    private fun presentationalHints(node: DocNode): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(2)
        val align = node.attr("align")?.trim()?.lowercase()
        if (align != null) {
            if (node.name == "img" && (align == "left" || align == "right")) {
                out.add("float" to align)
            } else if (align in ALIGN_KEYWORDS) {
                out.add("text-align" to align)
            }
        }
        node.attr("valign")?.trim()?.lowercase()?.let {
            if (it in VALIGN_KEYWORDS) out.add("vertical-align" to it)
        }
        node.attr("bgcolor")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            out.add("background-color" to it)
        }
        node.attr("width")?.let { normalizeHtmlDimension(it)?.let { value -> out.add("width" to value) } }
        node.attr("height")?.let { normalizeHtmlDimension(it)?.let { value -> out.add("height" to value) } }
        if (node.attr("nowrap") != null) out.add("white-space" to "nowrap")
        return out
    }

    private fun normalizeHtmlDimension(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.endsWith("%")) return text
        if (text.all { it.isDigit() }) return "${text}px"
        return null
    }

    // ------------------------------------------------------------------- URLs

    private fun resolveUrls(property: String, value: String, baseDir: String): String {
        if (baseDir.isEmpty() || property !in URL_PROPERTIES) return value
        if (!value.contains("url(", ignoreCase = true)) return value
        return URL_REGEX.replace(value) { match ->
            val raw = match.groupValues[2].trim()
            if (raw.startsWith("data:") || raw.contains("://")) {
                match.value
            } else {
                "url(${ResourcePath.resolve(baseDir, raw)})"
            }
        }
    }

    private companion object {
        const val UNIVERSAL = "*"

        const val UA_RANK = 0
        const val HINT_RANK = 1
        const val AUTHOR_RANK = 2
        const val INLINE_RANK = 3
        const val AUTHOR_IMPORTANT_RANK = 4
        const val INLINE_IMPORTANT_RANK = 5
        const val UA_IMPORTANT_RANK = 6

        const val HINT_ORDER = -1

        val URL_REGEX = Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""")

        val URL_PROPERTIES = setOf("background-image", "list-style-image", "border-image")

        val ALIGN_KEYWORDS = setOf("left", "right", "center", "justify")

        val VALIGN_KEYWORDS = setOf("top", "middle", "bottom", "baseline")
    }
}
