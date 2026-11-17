package org.libepub.core.toc

import org.libepub.core.container.localName
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

internal data class NavResult(
    val title: String?,
    val entries: List<TocEntry>,
    val landmarks: List<Landmark>,
    val pageLabels: List<PageLabel>,
)

/**
 * EPUB3 navigation document parser: the `nav[epub:type=toc]` nested list becomes
 * the TOC, `nav[epub:type=landmarks]` and `nav[epub:type=page-list]` are surfaced
 * separately. Chrome outside those navs is ignored.
 */
internal object NavParser {

    private const val EPUB_NS = "http://www.idpf.org/2007/ops"

    fun parse(xml: String, baseDir: String): NavResult {
        val parser = newParser(xml)
        var title: String? = null
        var inHeadTitle = false
        var titleBuf = StringBuilder()
        val tocEntries = ArrayList<TocEntry>(16)
        val landmarks = ArrayList<Landmark>(4)
        val pageLabels = ArrayList<PageLabel>(8)

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "title" -> {
                        inHeadTitle = true
                        titleBuf = StringBuilder()
                    }
                    "nav" -> {
                        val type = parser.getAttributeValue(EPUB_NS, "type")
                            ?: parser.getAttributeValue(null, "type")
                            ?: "toc"
                        parseNav(parser, type, baseDir, tocEntries, landmarks, pageLabels)
                    }
                }
                XmlPullParser.TEXT -> if (inHeadTitle) titleBuf.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.localName == "title" && inHeadTitle) {
                    if (title.isNullOrBlank()) title = titleBuf.toString().trim()
                    inHeadTitle = false
                }
                XmlPullParser.END_DOCUMENT -> break
                else -> Unit
            }
        }
        return NavResult(title, tocEntries, landmarks, pageLabels)
    }

    /**
     * Consumes one <nav> element (leaves the parser on its END_TAG).
     * toc: recursive ol/li/anchor walk. landmarks/page-list: flat anchor scan.
     */
    private fun parseNav(
        parser: KXmlParser,
        type: String,
        baseDir: String,
        toc: MutableList<TocEntry>,
        landmarks: MutableList<Landmark>,
        pageLabels: MutableList<PageLabel>,
    ) {
        when (type) {
            "toc" -> {
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.START_TAG -> if (parser.localName == "ol") {
                            toc.addAll(parseList(parser, baseDir))
                            // parseList consumed through </ol> of the top-level list
                        }
                        XmlPullParser.END_TAG -> if (parser.localName == "nav") return
                        XmlPullParser.END_DOCUMENT -> return
                        else -> Unit
                    }
                }
            }
            else -> {
                val sink: (String, String?, String, String?) -> Unit = when (type) {
                    "landmarks" -> { label, ltype, href, frag ->
                        if (ltype != null) landmarks.add(Landmark(ltype, label.ifEmpty { null }, href, frag))
                    }
                    else -> { label, _, href, frag -> pageLabels.add(PageLabel(label, href, frag)) }
                }
                scanFlatAnchors(parser, baseDir, sink)
            }
        }
    }

    /** Parses the current <ol> through its matching </ol>; returns its top-level entries. */
    private fun parseList(parser: KXmlParser, baseDir: String): List<TocEntry> {
        val entries = ArrayList<TocEntry>(8)
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "li" -> parseListItem(parser, baseDir)?.let { entries.add(it) }
                    "ol" -> parseList(parser, baseDir) // stray nested ol without li: consume
                }
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "ol" -> return entries
                    "nav" -> return entries // malformed: nav closed early
                }
                XmlPullParser.END_DOCUMENT -> return entries
                else -> Unit
            }
        }
    }

    /** Parses one <li> through its </li>; children come from a nested <ol> inside it. */
    private fun parseListItem(parser: KXmlParser, baseDir: String): TocEntry? {
        var label: String? = null
        var href: String? = null
        var frag: String? = null
        var inAnchor = false
        var anchorBuf = StringBuilder()
        val children = ArrayList<TocEntry>(4)

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "a" -> {
                        inAnchor = true
                        anchorBuf = StringBuilder()
                        val raw = parser.getAttributeValue(null, "href")
                        val resolved = HrefResolver.splitResolve(baseDir, raw)
                        href = resolved.first.ifEmpty { null }
                        frag = resolved.second
                    }
                    "ol" -> children.addAll(parseList(parser, baseDir))
                }
                XmlPullParser.TEXT -> if (inAnchor) anchorBuf.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "a" -> if (inAnchor) {
                        val text = anchorBuf.toString().trim()
                        if (label == null || label.isEmpty()) label = text
                        inAnchor = false
                    }
                    "li" -> return TocEntry(
                        title = label.orEmpty(),
                        href = href,
                        fragment = frag,
                        children = children,
                    )
                }
                XmlPullParser.END_DOCUMENT -> return null
                else -> Unit
            }
        }
    }

    /** Flat scan of anchors inside a landmarks/page-list <nav> until its </nav>. */
    private fun scanFlatAnchors(
        parser: KXmlParser,
        baseDir: String,
        sink: (label: String, epubType: String?, href: String, frag: String?) -> Unit,
    ) {
        var inAnchor = false
        var buf = StringBuilder()
        var rawHref: String? = null
        var epubType: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> if (parser.localName == "a") {
                    inAnchor = true
                    buf = StringBuilder()
                    rawHref = parser.getAttributeValue(null, "href")
                    epubType = parser.getAttributeValue(EPUB_NS, "type")
                }
                XmlPullParser.TEXT -> if (inAnchor) buf.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "a" -> if (inAnchor) {
                        val raw = rawHref
                        if (raw != null) {
                            val (href, frag) = HrefResolver.splitResolve(baseDir, raw)
                            sink(buf.toString().trim(), epubType, href, frag)
                        }
                        inAnchor = false
                        rawHref = null
                        epubType = null
                    }
                    "nav" -> return
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
        return parser
    }
}
