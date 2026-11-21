package org.libepub.core.model

import org.libepub.core.css.Display
import org.libepub.core.css.Edges
import org.libepub.core.css.Length
import org.libepub.core.css.LengthUnit
import org.libepub.core.css.LineHeight
import org.libepub.core.css.TextAlign
import org.libepub.core.style.ComputedStyle
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.DocNode
import org.libepub.core.xhtml.NodeKind

/** One block of a plain-text source: matching XHTML semantics without any markup. */
data class PlainBlock(
    val text: String,
    val kind: PlainBlockKind = PlainBlockKind.Paragraph,
)

enum class PlainBlockKind { Title, Heading, Paragraph }

/**
 * Builds an XHTML-equivalent [Document] from plain text so that TXT books run
 * through the same block layout, line breaking and pagination as EPUB chapters.
 *
 * The document only uses elements the layout engine already understands
 * (`html > body > h1/h2/p`), so no CSS cascade is needed: [defaultStyles]
 * supplies a resolved style map directly.
 */
object PlainDocuments {

    const val DEFAULT_HREF = "plain://chapter"

    fun of(
        title: String?,
        blocks: List<PlainBlock>,
        href: String = DEFAULT_HREF,
    ): Document {
        val body = DocNode.element("body")
        for (block in blocks) {
            val tag = when (block.kind) {
                PlainBlockKind.Title -> "h1"
                PlainBlockKind.Heading -> "h2"
                PlainBlockKind.Paragraph -> "p"
            }
            body.append(DocNode.element(tag).appendText(block.text))
        }
        val html = DocNode.element("html").append(body)
        return Document(
            root = html,
            href = href,
            ids = emptyMap(),
            styleSheets = emptyList(),
            title = title,
            warnings = WarningSink(),
        )
    }

    /**
     * Resolved styles for every node in a document produced by [of]. Text nodes
     * inherit their parent's style (only `display` differs), which keeps the
     * measured font identical to the block's.
     */
    fun defaultStyles(
        document: Document,
        fontSizePx: Float,
        lineSpacing: Float = 1.65f,
        indentEm: Float = 2f,
        justify: Boolean = true,
    ): Map<DocNode, ComputedStyle> {
        val styles = HashMap<DocNode, ComputedStyle>()
        val paragraph = ComputedStyle(
            display = Display.BLOCK,
            fontSizePx = fontSizePx,
            lineHeight = LineHeight.Number(lineSpacing),
            textIndent = Length(indentEm, LengthUnit.EM),
            textAlign = if (justify) TextAlign.JUSTIFY else TextAlign.START,
            margin = Edges(
                top = org.libepub.core.css.Dimension.Of(Length(0f, LengthUnit.PX)),
                right = org.libepub.core.css.Dimension.Of(Length(0f, LengthUnit.PX)),
                bottom = org.libepub.core.css.Dimension.Of(Length(0f, LengthUnit.PX)),
                left = org.libepub.core.css.Dimension.Of(Length(0f, LengthUnit.PX)),
            ),
        )
        val title = paragraph.copy(
            fontSizePx = fontSizePx * 1.45f,
            fontWeight = 700,
            textAlign = TextAlign.CENTER,
            textIndent = Length(0f, LengthUnit.EM),
            lineHeight = LineHeight.Number(1.35f),
            margin = Edges(
                top = org.libepub.core.css.Dimension.Of(Length(fontSizePx * 1.2f, LengthUnit.PX)),
                right = org.libepub.core.css.Dimension.Of(Length(0f, LengthUnit.PX)),
                bottom = org.libepub.core.css.Dimension.Of(Length(fontSizePx * 0.8f, LengthUnit.PX)),
                left = org.libepub.core.css.Dimension.Of(Length(0f, LengthUnit.PX)),
            ),
        )
        val heading = paragraph.copy(
            fontSizePx = fontSizePx * 1.2f,
            fontWeight = 600,
            textIndent = Length(0f, LengthUnit.EM),
            lineHeight = LineHeight.Number(1.4f),
            margin = Edges(
                top = org.libepub.core.css.Dimension.Of(Length(fontSizePx * 0.8f, LengthUnit.PX)),
                right = org.libepub.core.css.Dimension.Of(Length(0f, LengthUnit.PX)),
                bottom = org.libepub.core.css.Dimension.Of(Length(fontSizePx * 0.4f, LengthUnit.PX)),
                left = org.libepub.core.css.Dimension.Of(Length(0f, LengthUnit.PX)),
            ),
        )
        val root = paragraph.copy(display = Display.BLOCK, textIndent = Length(0f, LengthUnit.EM))

        fun styleFor(node: DocNode): ComputedStyle = when (node.name) {
            "html", "body" -> root
            "h1" -> title
            "h2" -> heading
            else -> paragraph
        }

        for (node in document.root.walk()) {
            styles[node] = when (node.kind) {
                NodeKind.TEXT -> node.parent?.let { styles[it] }?.copy(display = Display.INLINE) ?: paragraph
                NodeKind.ELEMENT -> styleFor(node)
            }
        }
        return styles
    }
}
