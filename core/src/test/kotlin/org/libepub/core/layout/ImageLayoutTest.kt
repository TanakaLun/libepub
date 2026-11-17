package org.libepub.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.css.CssParser
import org.libepub.core.model.Document
import org.libepub.core.style.StyleResolver
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.XhtmlParser

class ImageLayoutTest {

    private class FixedMeasurer : GlyphMeasurer {
        override fun advance(grapheme: String, font: FontSpec): Float {
            if (grapheme.isEmpty()) return 0f
            val em = font.sizePx
            return when {
                grapheme == " " -> em * 0.25f
                Kinsoku.isCjkCodePoint(grapheme.codePointAt(0)) -> em
                else -> em * 0.5f
            }
        }

        override fun metrics(font: FontSpec): FontMetrics =
            FontMetrics(font.sizePx * 0.8f, font.sizePx * 0.2f)
    }

    private class Fixture(val doc: Document, val layout: ChapterLayout, val warnings: WarningSink)

    private val measurer = FixedMeasurer()

    private fun layout(
        html: String,
        css: String = "",
        width: Float = 200f,
        height: Float = 400f,
        images: Map<String, IntrinsicSize> = emptyMap(),
    ): Fixture {
        val warnings = WarningSink()
        val sheet = CssParser.parse(BASE + "\n" + css, "OEBPS/Styles", warnings)
        val doc = XhtmlParser.parse(
            "<html><body>${html.trimIndent()}</body></html>",
            "OEBPS/Text/x.xhtml",
            warnings,
        )
        val styles = StyleResolver(listOf(sheet), warnings = warnings).resolve(doc)
        val provider = object : ImageSizeProvider {
            override fun intrinsicSize(src: String): IntrinsicSize? = images[src]
        }
        val engine = BlockLayoutEngine(measurer, provider, warnings)
        return Fixture(doc, engine.layout(doc, styles, LayoutConfig(width, height)), warnings)
    }

    private val BASE = """
        html, body, div, p, figure, figcaption { margin: 0; padding: 0 }
        body { font-size: 10px; line-height: 1 }
        .illus { text-align: center }
    """.trimIndent()

    private fun src(name: String) = "OEBPS/Text/$name"

    private fun imageFragments(fixture: Fixture): List<InlineFragment> =
        fixture.layout.lines.flatMap { line -> line.fragments.filter { it.kind == FragmentKind.IMAGE } }

    private fun imageBoxes(fixture: Fixture): List<LaidOutBox> =
        fixture.layout.boxes.filter { it.kind == BoxKind.IMAGE }

    // ------------------------------------------------------------ natural size

    @Test
    fun intrinsicSizeIsUsedAtNaturalScale() {
        val fixture = layout(
            """<div class="illus"><img src="a.png"/></div>""",
            images = mapOf(src("a.png") to IntrinsicSize(300f, 200f)),
            width = 400f,
        )
        val image = imageFragments(fixture).single()
        assertEquals(300f, image.mainSize, 0.01f)
        assertEquals(200f, image.blockSize, 0.01f)
        // image ascent (200) + text descent (2)
        assertEquals(202f, fixture.layout.lines.single().blockSize, 0.01f)
    }

    @Test
    fun oversizedImageIsScaledToTheColumn() {
        val fixture = layout(
            """<div class="illus"><img src="a.png"/></div>""",
            images = mapOf(src("a.png") to IntrinsicSize(800f, 400f)),
        )
        val image = imageFragments(fixture).single()
        assertEquals(200f, image.mainSize, 0.01f)
        assertEquals(100f, image.blockSize, 0.01f)
    }

    // -------------------------------------------------------------- max limits

    @Test
    fun maxWidthPercentClampsAndKeepsAspectRatio() {
        val fixture = layout(
            """<div class="illus"><img src="a.png"/></div>""",
            "img { max-width: 50% }",
            images = mapOf(src("a.png") to IntrinsicSize(800f, 400f)),
        )
        val image = imageFragments(fixture).single()
        assertEquals(100f, image.mainSize, 0.01f)
        assertEquals(50f, image.blockSize, 0.01f)
    }

    @Test
    fun maxHeightClampsWidth() {
        val fixture = layout(
            """<div class="illus"><img src="a.png"/></div>""",
            "img { max-height: 50px }",
            images = mapOf(src("a.png") to IntrinsicSize(800f, 400f)),
        )
        val image = imageFragments(fixture).single()
        assertEquals(100f, image.mainSize, 0.01f)
        assertEquals(50f, image.blockSize, 0.01f)
    }

    // ----------------------------------------------------------- explicit size

    @Test
    fun explicitWidthUsesIntrinsicAspectRatio() {
        val fixture = layout(
            """<div class="illus"><img src="a.png"/></div>""",
            "img { width: 100px }",
            images = mapOf(src("a.png") to IntrinsicSize(800f, 400f)),
        )
        val image = imageFragments(fixture).single()
        assertEquals(100f, image.mainSize, 0.01f)
        assertEquals(50f, image.blockSize, 0.01f)
    }

    @Test
    fun explicitHeightUsesIntrinsicAspectRatio() {
        val fixture = layout(
            """<div class="illus"><img src="a.png"/></div>""",
            "img { height: 100px }",
            images = mapOf(src("a.png") to IntrinsicSize(800f, 400f)),
        )
        val image = imageFragments(fixture).single()
        assertEquals(200f, image.mainSize, 0.01f)
        assertEquals(100f, image.blockSize, 0.01f)
    }

    // ------------------------------------------------------------- duokan block

    @Test
    fun duokanSingleImageBecomesABlockBox() {
        val fixture = layout(
            """<div class="illus duokan-image-single"><img alt="002" src="a.png"/></div>""",
            images = mapOf(src("a.png") to IntrinsicSize(300f, 200f)),
            width = 400f,
        )
        val box = imageBoxes(fixture).single()
        assertEquals(300f, box.mainSize, 0.01f)
        assertEquals(200f, box.blockSize, 0.01f)
        assertEquals("002", box.alt)
        assertEquals("OEBPS/Text/a.png", box.src)
        assertFalse(box.placeholder)
        val wrapper = fixture.layout.boxes.first { it.node?.name == "div" }
        assertEquals(200f, wrapper.blockSize, 0.01f)
    }

    @Test
    fun illusCentersABlockImage() {
        val fixture = layout(
            """<div class="illus duokan-image-single"><img src="a.png"/></div>""",
            images = mapOf(src("a.png") to IntrinsicSize(100f, 50f)),
            width = 400f,
        )
        assertEquals(150f, imageBoxes(fixture).single().main, 0.01f)
    }

    @Test
    fun illusCentersAnInlineImage() {
        val fixture = layout(
            """<div class="illus"><img src="a.png"/></div>""",
            images = mapOf(src("a.png") to IntrinsicSize(100f, 50f)),
            width = 400f,
        )
        assertEquals(150f, fixture.layout.lines.single().main, 0.01f)
    }

    @Test
    fun galleryCellsUseTheFullColumnWidth() {
        val fixture = layout(
            """
            <div class="duokan-image-gallery">
              <div class="illus duokan-image-gallery-cell"><img src="a.png"/></div>
              <div class="illus duokan-image-gallery-cell"><img src="b.png"/></div>
            </div>
            """,
            images = mapOf(
                src("a.png") to IntrinsicSize(400f, 200f),
                src("b.png") to IntrinsicSize(600f, 300f),
            ),
        )
        val boxes = imageBoxes(fixture)
        assertEquals(2, boxes.size)
        assertEquals(listOf(200f, 200f), boxes.map { it.mainSize })
        assertEquals(listOf(100f, 100f), boxes.map { it.blockSize })
        assertEquals(listOf(0f, 100f), boxes.map { it.block })
    }

    // -------------------------------------------------------------- degradation

    @Test
    fun missingImageWarnsAndUsesAPlaceholder() {
        val fixture = layout("""<div class="illus"><img alt="gone" src="missing.png"/></div>""")
        val image = imageFragments(fixture).single()
        assertTrue(image.placeholder)
        assertEquals("gone", image.alt)
        // fallback: 4em square, clamped to the column
        assertEquals(40f, image.mainSize, 0.01f)
        assertEquals(40f, image.blockSize, 0.01f)
        val warning = fixture.warnings.all.joinToString("\n")
        assertTrue(warning.contains("missing.png"))
    }

    @Test
    fun altTextIsCarriedToTheInlineFragment() {
        val fixture = layout(
            """<div class="illus"><img alt="封面" src="a.png"/></div>""",
            images = mapOf(src("a.png") to IntrinsicSize(100f, 50f)),
        )
        assertEquals("封面", imageFragments(fixture).single().alt)
    }

    @Test
    fun figureCaptionStacksBelowTheImage() {
        val fixture = layout(
            """
            <figure><img src="a.png"/><figcaption>cap</figcaption></figure>
            """,
            images = mapOf(src("a.png") to IntrinsicSize(100f, 50f)),
        )
        val lines = fixture.layout.lines
        assertEquals(2, lines.size)
        assertTrue(lines[0].fragments.any { it.kind == FragmentKind.IMAGE })
        assertEquals("cap", lines[1].text)
        assertEquals(52f, lines[1].block, 0.01f)
    }
}
