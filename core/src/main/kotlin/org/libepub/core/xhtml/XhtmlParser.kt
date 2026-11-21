package org.libepub.core.xhtml

import org.libepub.core.EpubFormatException
import org.libepub.core.model.Document
import org.libepub.core.script.RawScript
import org.libepub.core.script.ScriptAuditor
import org.libepub.core.script.ScriptSourceProvider
import org.libepub.core.semantics.SemanticIndex
import org.libepub.core.util.WarningSink
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * Streaming XHTML reader. Markup is normalized first ([MarkupNormalizer]) so
 * that kxml2 only ever sees well-formed input; events are then folded into a
 * [DocNode] tree while ids and inline `<style>` sheets are indexed in the same
 * pass. Namespace processing is deliberately off: element/attribute names keep
 * their written form (`epub:type`, `xml:lang`), which is the most tolerant
 * reading of real-world EPUB markup.
 */
object XhtmlParser {

    fun parse(
        raw: String,
        href: String = "",
        warnings: WarningSink = WarningSink(),
        scriptSource: ScriptSourceProvider? = null,
    ): Document {
        val rawScripts = ArrayList<RawScript>(0)
        val normalized = MarkupNormalizer.normalize(raw, warnings, rawScripts)
        val parser = KXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(normalized.reader())

        val ids = LinkedHashMap<String, MutableList<DocNode>>(8)
        val styleSheets = ArrayList<String>(1)
        val stack = ArrayList<DocNode>(16)
        var root: DocNode? = null
        var title: String? = null
        var inTitle = false
        var titleBuf: StringBuilder? = null

        try {
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        val node = DocNode(
                            kind = NodeKind.ELEMENT,
                            name = parser.name,
                            attributes = readAttributes(parser),
                        )
                        val parent = stack.lastOrNull()
                        if (parent != null) {
                            node.parent = parent
                            parent.childList.add(node)
                        } else if (root == null) {
                            root = node
                        } else {
                            // Multiple top-level elements: keep everything under the first.
                            node.parent = root
                            root!!.childList.add(node)
                            warnings.warnOnce("xhtml.multiroot", "multiple top-level elements; nested under <${root!!.name}>")
                        }
                        stack.add(node)
                        node.attr("id")?.let { id ->
                            ids.getOrPut(id) { ArrayList(1) }.add(node)
                        }
                        if (node.name == "title" && title == null) {
                            inTitle = true
                            titleBuf = StringBuilder()
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val node = stack.removeLastOrNull()
                        if (node != null && node.name != parser.name) {
                            warnings.warnOnce(
                                "xhtml.tag-mismatch",
                                "mismatched end tag </${parser.name}> for <${node.name}>",
                            )
                        }
                        if (parser.name == "title" && inTitle) {
                            title = titleBuf?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                            inTitle = false
                            titleBuf = null
                        }
                        if (parser.name == "style" && node != null) {
                            val css = node.childList
                                .filter { it.kind == NodeKind.TEXT }
                                .joinToString("") { it.text }
                                .trim()
                            if (css.isNotEmpty()) styleSheets.add(css)
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val text = parser.text
                        if (inTitle) titleBuf?.append(text)
                        val parent = stack.lastOrNull() ?: continue
                        val last = parent.childList.lastOrNull()
                        if (last != null && last.kind == NodeKind.TEXT) {
                            last.text += text
                        } else {
                            val node = DocNode(NodeKind.TEXT, text = text)
                            node.parent = parent
                            parent.childList.add(node)
                        }
                    }

                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (e: Exception) {
            // Normalization should have made this unreachable; if the parser still
            // rejects the input, keep the tree built so far instead of failing the
            // whole chapter.
            warnings.warnOnce("xhtml.parse", "stopped parsing $href early (${e.message})")
        }

        val documentRoot = root
            ?: throw EpubFormatException("no root element in ${href.ifEmpty { "document" }}")

        val idIndex = ids.mapValues { (_, list) -> list.toList() }
        val semantics = SemanticIndex.build(documentRoot, href, idIndex, warnings)
        val scriptAudit = ScriptAuditor.audit(href, rawScripts, scriptSource, warnings)

        return Document(
            root = documentRoot,
            href = href,
            ids = idIndex,
            styleSheets = styleSheets,
            title = title,
            warnings = warnings,
            footnotes = semantics.footnotes,
            imageGroups = semantics.imageGroups,
            pageBreaks = semantics.pageBreaks,
            scriptAudit = scriptAudit,
        )
    }

    private fun readAttributes(parser: KXmlParser): Map<String, String> {
        val count = parser.attributeCount
        if (count == 0) return emptyMap()
        val map = LinkedHashMap<String, String>(count)
        for (i in 0 until count) {
            map[parser.getAttributeName(i)] = parser.getAttributeValue(i) ?: ""
        }
        return map
    }
}
