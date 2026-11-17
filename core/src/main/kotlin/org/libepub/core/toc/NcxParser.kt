package org.libepub.core.toc

import org.libepub.core.EpubFormatException
import org.libepub.core.container.localName
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

internal data class NcxResult(
    val title: String?,
    val entries: List<TocEntry>,
    val pageLabels: List<PageLabel>,
)

/** EPUB2 NCX (`toc.ncx`) parser with recursive navPoint handling. */
internal object NcxParser {

    fun parse(xml: String, baseDir: String): NcxResult {
        val parser = newParser(xml)
        var title: String? = null
        val entries = ArrayList<TocEntry>(16)
        val pageLabels = ArrayList<PageLabel>(8)
        var inDocTitle = false
        var inText = false
        var buf = StringBuilder()
        var sawNavMap = false

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "docTitle" -> inDocTitle = true
                    "text" -> {
                        inText = true
                        buf = StringBuilder()
                    }
                    "navPoint" -> entries.add(parseNavPoint(parser, baseDir))
                    "pageList" -> {
                        sawNavMap = true
                        parsePageList(parser, baseDir, pageLabels)
                    }
                }
                XmlPullParser.TEXT -> if (inText) buf.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "text" -> {
                        if (inDocTitle && title == null) title = buf.toString().trim()
                        inText = false
                    }
                    "docTitle" -> inDocTitle = false
                }
                XmlPullParser.END_DOCUMENT -> break
                else -> Unit
            }
        }
        if (!sawNavMap && entries.isEmpty() && title == null) {
            throw EpubFormatException("NCX has no navMap")
        }
        return NcxResult(title, entries, pageLabels)
    }

    private fun parseNavPoint(parser: KXmlParser, baseDir: String): TocEntry {
        val playOrder = parser.getAttributeValue(null, "playOrder")?.toIntOrNull()
        var label: String? = null
        var src: String? = null
        val children = ArrayList<TocEntry>(4)
        var inText = false
        var buf = StringBuilder()

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "text" -> {
                        inText = true
                        buf = StringBuilder()
                    }
                    "content" -> src = parser.getAttributeValue(null, "src")
                    "navPoint" -> children.add(parseNavPoint(parser, baseDir))
                }
                XmlPullParser.TEXT -> if (inText) buf.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "text" -> {
                        if (label == null) label = buf.toString().trim()
                        inText = false
                    }
                    "navPoint" -> {
                        val (href, frag) = HrefResolver.splitResolve(baseDir, src)
                        return TocEntry(
                            title = label.orEmpty(),
                            href = href.ifEmpty { null },
                            fragment = frag,
                            children = children,
                            playOrder = playOrder,
                        )
                    }
                }
                XmlPullParser.END_DOCUMENT -> throw EpubFormatException("NCX navPoint not closed")
                else -> Unit
            }
        }
    }

    private fun parsePageList(parser: KXmlParser, baseDir: String, out: MutableList<PageLabel>) {
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> if (parser.localName == "pageTarget") {
                    parsePageTarget(parser, baseDir, out)
                }
                XmlPullParser.END_TAG -> if (parser.localName == "pageList") return
                XmlPullParser.END_DOCUMENT -> return
                else -> Unit
            }
        }
    }

    private fun parsePageTarget(parser: KXmlParser, baseDir: String, out: MutableList<PageLabel>) {
        var label: String? = null
        var src: String? = null
        var inText = false
        var buf = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "text" -> {
                        inText = true
                        buf = StringBuilder()
                    }
                    "content" -> src = parser.getAttributeValue(null, "src")
                }
                XmlPullParser.TEXT -> if (inText) buf.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "text" -> {
                        if (label == null) label = buf.toString().trim()
                        inText = false
                    }
                    "pageTarget" -> {
                        val (href, frag) = HrefResolver.splitResolve(baseDir, src)
                        if (label != null && href.isNotEmpty()) out.add(PageLabel(label, href, frag))
                        return
                    }
                }
                XmlPullParser.END_DOCUMENT -> return
                else -> Unit
            }
        }
    }

    private fun newParser(xml: String): KXmlParser {
        val parser = KXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())
        // position before first event loop usage
        return parser
    }
}
