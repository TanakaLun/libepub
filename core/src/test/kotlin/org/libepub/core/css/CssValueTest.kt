package org.libepub.core.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.container.EpubContainer
import org.libepub.core.testutil.SampleBooks

class CssValueTest {

    private fun lengthOf(dimension: Dimension): Length = (dimension as Dimension.Of).length

    // ------------------------------------------------------------------ edges

    @Test
    fun marginExpandsOneToFourValues() {
        val four = Shorthand.parseMargin("4em 0 0 0")!!
        assertEquals(Length(4f, LengthUnit.EM), lengthOf(four.top))
        assertEquals(Length(0f, LengthUnit.PX), lengthOf(four.right))
        assertEquals(Length(0f, LengthUnit.PX), lengthOf(four.bottom))
        assertEquals(Length(0f, LengthUnit.PX), lengthOf(four.left))

        val two = Shorthand.parseMargin("10px 20px")!!
        assertEquals(Length(10f, LengthUnit.PX), lengthOf(two.top))
        assertEquals(Length(20f, LengthUnit.PX), lengthOf(two.right))
        assertEquals(Length(10f, LengthUnit.PX), lengthOf(two.bottom))
        assertEquals(Length(20f, LengthUnit.PX), lengthOf(two.left))

        val three = Shorthand.parseMargin("1em 2em 3em")!!
        assertEquals(Length(1f, LengthUnit.EM), lengthOf(three.top))
        assertEquals(Length(2f, LengthUnit.EM), lengthOf(three.right))
        assertEquals(Length(3f, LengthUnit.EM), lengthOf(three.bottom))
        assertEquals(Length(2f, LengthUnit.EM), lengthOf(three.left))

        val one = Shorthand.parseMargin("5%")!!
        assertEquals(Length(5f, LengthUnit.PERCENT), lengthOf(one.top))
        assertEquals(Length(5f, LengthUnit.PERCENT), lengthOf(one.left))
    }

    @Test
    fun marginAcceptsAutoButPaddingDoesNot() {
        val margin = Shorthand.parseMargin("0 auto")!!
        assertEquals(Length(0f, LengthUnit.PX), lengthOf(margin.top))
        assertEquals(Dimension.Auto, margin.left)
        assertEquals(Dimension.Auto, margin.right)

        assertNull(Shorthand.parsePadding("0 auto"))
        val padding = Shorthand.parsePadding("0.5em 0 1em 0")!!
        assertEquals(Length(0.5f, LengthUnit.EM), padding.top)
        assertEquals(Length(0f, LengthUnit.PX), padding.right)
        assertEquals(Length(1f, LengthUnit.EM), padding.bottom)
        assertEquals(Length(0f, LengthUnit.PX), padding.left)
    }

    // ----------------------------------------------------------------- border

    @Test
    fun borderShorthandParsesAnyOrder() {
        val first = Shorthand.parseBorderSide("solid 2px #000")!!
        assertEquals(Length(2f, LengthUnit.PX), first.width)
        assertEquals(BorderStyle.SOLID, first.style)
        assertEquals(CssColor.BLACK, first.color)

        val reordered = Shorthand.parseBorderSide("#000 2px dashed")!!
        assertEquals(Length(2f, LengthUnit.PX), reordered.width)
        assertEquals(BorderStyle.DASHED, reordered.style)
        assertEquals(CssColor.BLACK, reordered.color)

        val zero = Shorthand.parseBorder("0")!!
        assertEquals(BorderSide(Length(0f, LengthUnit.PX), null, null), zero.top)
        assertEquals(zero.top, zero.left)
    }

    @Test
    fun borderTopOverridesOnlyThatSide() {
        val base = Shorthand.parseBorder("1px solid black")!!
        val overridden = base.with(Side.TOP, Shorthand.parseBorderSide("dashed 2px red")!!)
        assertEquals(BorderStyle.DASHED, overridden.top.style)
        assertEquals(Length(2f, LengthUnit.PX), overridden.top.width)
        assertEquals(BorderStyle.SOLID, overridden.bottom.style)
        assertEquals(BorderStyle.SOLID, overridden.left.style)
        assertEquals(BorderStyle.SOLID, overridden.right.style)
    }

    @Test
    fun borderLonghandsExpandPerSide() {
        val widths = Shorthand.parseBorderWidths("1px 2px 3px 4px")!!
        assertEquals(Length(4f, LengthUnit.PX), widths.left)
        val styles = Shorthand.parseBorderStyles("solid none")!!
        assertEquals(BorderStyle.SOLID, styles.top)
        assertEquals(BorderStyle.NONE, styles.right)
        assertEquals(BorderStyle.SOLID, styles.bottom)
        val colors = Shorthand.parseBorderColors("#716F71")!!
        assertEquals(CssColor.rgb(0x71, 0x6F, 0x71), colors.top)
    }

    // ------------------------------------------------------------- background

    @Test
    fun backgroundShorthandCombinesParts() {
        val bg = Shorthand.parseBackground("url(../Images/cover.jpg) no-repeat center / cover #fff")!!
        assertEquals("../Images/cover.jpg", bg.imageUrl)
        assertEquals("no-repeat", bg.repeat)
        assertEquals("center", bg.position)
        assertEquals("cover", bg.size)
        assertEquals(CssColor.WHITE, bg.color)

        val plain = Shorthand.parseBackground("#333")!!
        assertEquals(CssColor.rgb(0x33, 0x33, 0x33), plain.color)
        assertNull(plain.imageUrl)
    }

    // --------------------------------------------------------------- font-size

    @Test
    fun fontSizeAcceptsLengthPercentAndKeywords() {
        assertEquals(Length(1.25f, LengthUnit.EM), ValueParser.parseFontSize("1.25em"))
        assertEquals(Length(120f, LengthUnit.PERCENT), ValueParser.parseFontSize("120%"))
        assertEquals(Length(16f, LengthUnit.PX), ValueParser.parseFontSize("medium"))
        assertEquals(Length(18f, LengthUnit.PX), ValueParser.parseFontSize("large"))
        assertEquals(Length(1.2f, LengthUnit.EM), ValueParser.parseFontSize("larger"))
        assertNull(ValueParser.parseFontSize("huge"))
    }

    @Test
    fun lineHeightAcceptsPercentLengthNormalAndNumber() {
        assertEquals(LineHeight.OfLength(Length(130f, LengthUnit.PERCENT)), ValueParser.parseLineHeight("130%"))
        assertEquals(LineHeight.OfLength(Length(1.3f, LengthUnit.EM)), ValueParser.parseLineHeight("1.3em"))
        assertEquals(LineHeight.Normal, ValueParser.parseLineHeight("normal"))
        assertEquals(LineHeight.Number(1.5f), ValueParser.parseLineHeight("1.5"))
        assertNull(ValueParser.parseLineHeight("wat"))
    }

    // ------------------------------------------------------------ text-emphasis

    @Test
    fun textEmphasisParsesMarkAndColor() {
        val emphasis = ValueParser.parseTextEmphasis("circle #000")!!
        assertEquals("circle", emphasis.mark)
        assertEquals(CssColor.BLACK, emphasis.color)

        val filled = ValueParser.parseTextEmphasis("filled dot")!!
        assertEquals("dot", filled.mark)
        assertEquals(true, filled.filled)
    }

    // ---------------------------------------------------------------- transform

    @Test
    fun transformParsesRotateAndScale() {
        val rotate = ValueParser.parseTransform("rotate(-4deg)")!!
        assertEquals(listOf(TransformFn.Rotate(-4f)), rotate)

        val combined = ValueParser.parseTransform("rotate(10deg) scale(2)")!!
        assertEquals(TransformFn.Rotate(10f), combined[0])
        assertEquals(TransformFn.Scale(2f, 2f), combined[1])

        val turn = ValueParser.parseTransform("rotate(0.5turn)")!!
        assertEquals(TransformFn.Rotate(180f), turn.single())
    }

    // ----------------------------------------------------------- misc longhands

    @Test
    fun boxSizingLetterSpacingWritingModeParse() {
        assertEquals(BoxSizing.BORDER_BOX, ValueParser.parseBoxSizing("border-box"))
        assertEquals(BoxSizing.CONTENT_BOX, ValueParser.parseBoxSizing("content-box"))
        assertEquals(Spacing.Fixed(Length(0.1f, LengthUnit.EM)), ValueParser.parseSpacing("0.1em"))
        assertEquals(Spacing.Normal, ValueParser.parseSpacing("normal"))
        assertEquals(WritingMode.VERTICAL_RL, ValueParser.parseWritingMode("vertical-rl"))
        assertEquals(WritingMode.HORIZONTAL_TB, ValueParser.parseWritingMode("horizontal-tb"))
        assertEquals(TextAlign.JUSTIFY, ValueParser.parseTextAlign("justify"))
        assertEquals(FontWeight.Numeric(700), ValueParser.parseFontWeight("bold"))
        assertEquals(FontWeight.Numeric(600), ValueParser.parseFontWeight("600"))
        assertNull(ValueParser.parseFontWeight("650"))
    }

    // ------------------------------------------------------------ length units

    @Test
    fun lengthUnitsResolveAgainstContext() {
        val ctx = LengthContext(
            fontSizePx = 16f,
            rootFontSizePx = 20f,
            viewportWidthPx = 400f,
            viewportHeightPx = 800f,
            percentBasePx = 200f,
        )
        assertEquals(32f, Length(2f, LengthUnit.EM).resolve(ctx), 0.001f)
        assertEquals(30f, Length(1.5f, LengthUnit.REM).resolve(ctx), 0.001f)
        assertEquals(16f, Length(12f, LengthUnit.PT).resolve(ctx), 0.001f)
        assertEquals(40f, Length(10f, LengthUnit.VW).resolve(ctx), 0.001f)
        assertEquals(80f, Length(10f, LengthUnit.VH).resolve(ctx), 0.001f)
        assertEquals(100f, Length(50f, LengthUnit.PERCENT).resolve(ctx), 0.001f)
        assertEquals(8f, Length(8f, LengthUnit.PX).resolve(ctx), 0.001f)
        // percent without a base resolves to 0 rather than throwing
        assertEquals(0f, Length(50f, LengthUnit.PERCENT).resolve(ctx.copy(percentBasePx = null)), 0.001f)
    }

    // ------------------------------------------------------------------ colors

    @Test
    fun colorFormatsParse() {
        assertEquals(CssColor.rgb(0xFF, 0xFF, 0xFF), ValueParser.parseColor("#fff"))
        assertEquals(CssColor.rgb(0x80, 0x80, 0x80), ValueParser.parseColor("#808080"))
        assertEquals(CssColor.rgb(0xFF, 0x00, 0x00), ValueParser.parseColor("rgb(255, 0, 0)"))
        val half = ValueParser.parseColor("rgba(0, 0, 0, 0.5)") as CssColor.Rgba
        assertEquals(127L, half.argb ushr 24)
        assertEquals(CssColor.rgb(0xFF, 0x00, 0x00), ValueParser.parseColor("red"))
        assertEquals(CssColor.TRANSPARENT, ValueParser.parseColor("transparent"))
        assertEquals(CssColor.Current, ValueParser.parseColor("currentColor"))
        assertNull(ValueParser.parseColor("notacolor"))
    }

    // --------------------------------------------------------------- tolerance

    @Test
    fun invalidValuesReturnNullWithoutThrowing() {
        assertNull(ValueParser.parseLength("abc"))
        assertNull(ValueParser.parseLength("12qq"))
        assertNull(Shorthand.parseMargin("1px 2px 3px 4px 5px"))
        assertNull(Shorthand.parseBorderSide(""))
        assertNull(Shorthand.parseBorder("wat"))
        assertNull(ValueParser.parseTransform("rotate(deg)"))
        assertNull(ValueParser.parseTransform("unknown(1)"))
        assertNull(Shorthand.parseBackground(""))
        assertNull(ValueParser.parseDisplay("wat"))
    }

    // ----------------------------------------------------------- real sample

    @Test
    fun sampleDeclarationsParseWithTypedValues() {
        EpubContainer.open(SampleBooks.sample()).use { container ->
            val sheet = CssParser.parse(container.readText("OEBPS/Styles/style.css"), "OEBPS/Styles")

            fun rule(selector: String): StyleRule =
                sheet.rules.first { rule -> rule.selectors.any { it.text == selector } }

            fun decl(selector: String, property: String): Declaration =
                rule(selector).declarations.first { it.property == property }

            assertEquals(Length(1.65f, LengthUnit.EM), ValueParser.parseFontSize(decl("h1", "font-size").value))
            assertEquals(
                LineHeight.OfLength(Length(130f, LengthUnit.PERCENT)),
                ValueParser.parseLineHeight(decl("body", "line-height").value),
            )
            assertEquals(Length(2f, LengthUnit.EM), ValueParser.parseLength(decl("p", "text-indent").value))
            assertEquals(TransformFn.Rotate(-4f), ValueParser.parseTransform(decl(".rotate1", "transform").value)!!.single())

            val blackRule = sheet.rules.first { rule ->
                rule.declarations.any { it.property == "color" && it.value == "#000" }
            }
            assertEquals(CssColor.BLACK, ValueParser.parseColor(blackRule.declarations.first { it.property == "color" }.value))

            val padding = Shorthand.parsePadding("0.5em 0 1em 0")!!
            assertEquals(Length(0.5f, LengthUnit.EM), padding.top)
            assertEquals(Length(1f, LengthUnit.EM), padding.bottom)

            val bgRule = sheet.rules.first { rule ->
                rule.declarations.any { it.property == "background-image" }
            }
            fun bgDecl(property: String): String = bgRule.declarations.first { it.property == property }.value
            assertEquals("../Images/contents.jpg", ValueParser.parseUrl(bgDecl("background-image")))
            assertEquals("no-repeat", ValueParser.parseBackgroundRepeat(bgDecl("background-repeat")))
            assertEquals("cover", bgDecl("background-size"))
            assertEquals("top center", bgDecl("background-position"))
        }
    }
}
