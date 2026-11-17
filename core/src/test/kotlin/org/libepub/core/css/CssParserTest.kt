package org.libepub.core.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.container.EpubContainer
import org.libepub.core.testutil.SampleBooks

class CssParserTest {

    private fun sampleStylesheet(): Stylesheet =
        EpubContainer.open(SampleBooks.sample()).use { container ->
            CssParser.parse(container.readText("OEBPS/Styles/style.css"), "OEBPS/Styles")
        }

    private fun rule(sheet: Stylesheet, selectorText: String): StyleRule =
        sheet.rules.firstOrNull { rule -> rule.selectors.any { it.text == selectorText } }
            ?: error("no rule for '$selectorText'")

    // ---------- real sample stylesheet (458 lines) ----------

    @Test
    fun sampleRuleAndFontFaceCounts() {
        val sheet = sampleStylesheet()
        assertEquals(88, sheet.rules.size)
        assertEquals(3, sheet.fontFaces.size)
        assertTrue(sheet.imports.isEmpty())
    }

    @Test
    fun sampleFontFacesResolvePathsRelativeToCss() {
        val sheet = sampleStylesheet()
        assertEquals(listOf("jp", "title", "illus1"), sheet.fontFaces.map { it.family })
        assertEquals(
            listOf("OEBPS/Fonts/jp.ttf", "OEBPS/Fonts/title.ttf", "OEBPS/Fonts/illus1.ttf"),
            sheet.fontFaces.map { it.src },
        )
    }

    @Test
    fun sampleBodyDeclarationsParsed() {
        val body = rule(sampleStylesheet(), "body")
        val byProp = body.declarations.associateBy { it.property }
        assertEquals("130%", byProp["line-height"]?.value)
        assertEquals("justify", byProp["text-align"]?.value)
        assertEquals("1%", byProp["margin-left"]?.value)
        assertEquals("0%", byProp["margin-top"]?.value)
    }

    @Test
    fun sampleEm30SpecificityAndValue() {
        val em30 = rule(sampleStylesheet(), ".em30")
        assertEquals("3em", em30.declarations.single().value)
        assertEquals(Specificity(0, 1, 0), em30.selectors.single().specificity)
    }

    @Test
    fun sampleVendorPrefixesNormalized() {
        val sheet = sampleStylesheet()
        val rotate = rule(sheet, ".rotate1")
        assertEquals(listOf("transform", "transform"), rotate.declarations.map { it.property })
        assertEquals("rotate(-4deg)", rotate.declarations.first().value)

        val emDot = rule(sheet, ".em-dot")
        val props = emDot.declarations.map { it.property }
        assertEquals(2, props.count { it == "text-emphasis-style" })
        assertTrue("text-emphasis shorthand kept", "text-emphasis" in props)
        assertFalse("no vendor prefix survives", props.any { it.startsWith("-") })
    }

    @Test
    fun sampleImportantFlagsDetected() {
        val sheet = sampleStylesheet()
        val important = sheet.rules.flatMap { it.declarations }.filter { it.important }
        assertEquals(3, important.size)
        assertTrue(important.all { !it.value.contains("important", ignoreCase = true) })
    }

    @Test
    fun sampleCommentsStripped() {
        val sheet = sampleStylesheet()
        assertTrue(sheet.rules.none { rule -> rule.selectors.any { "/*" in it.text } })
        // the first rule of the file follows the first comment block
        assertEquals("body", sheet.rules.first().selectors.single().text)
    }

    // ---------- selector parsing goldens ----------

    @Test
    fun selectorListParsesCompoundAndComplexSelectors() {
        val sheet = CssParser.parse("div p, .a.b, #id, tag, *, div > p + span ~ a { color: red }")
        val selectors = sheet.rules.single().selectors
        assertEquals(6, selectors.size)
        assertEquals(Specificity(0, 0, 2), selectors[0].specificity)
        assertEquals(Specificity(0, 2, 0), selectors[1].specificity)
        assertEquals(Specificity(1, 0, 0), selectors[2].specificity)
        assertEquals(Specificity(0, 0, 1), selectors[3].specificity)
        assertEquals(Specificity(0, 0, 0), selectors[4].specificity)
        assertEquals(Specificity(0, 0, 4), selectors[5].specificity)
        assertEquals(
            listOf(Combinator.CHILD, Combinator.ADJACENT, Combinator.SIBLING),
            selectors[5].combinators,
        )
        assertEquals(listOf(Combinator.DESCENDANT), selectors[0].combinators)
    }

    @Test
    fun specificityOrdersAsDocumented() {
        val sheet = CssParser.parse("p, .em30, div.x#y, * { color: red }")
        val (tag, cls, compound, universal) = sheet.rules.single().selectors
        assertTrue(tag.specificity < cls.specificity)
        assertTrue(cls.specificity < compound.specificity)
        assertEquals(Specificity(1, 1, 1), compound.specificity)
        assertTrue(universal.specificity < tag.specificity)
    }

    @Test
    fun attributeAndPseudoSelectorsAreParsed() {
        val sheet = CssParser.parse("a[href^=\"#\"], p:first-child, li:nth-child(2n+1), p::before { x: y }")
        val selectors = sheet.rules.single().selectors
        assertEquals(4, selectors.size)
        val attr = selectors[0].compounds.single().attributes.single()
        assertEquals("href", attr.name)
        assertEquals("^=", attr.operator)
        assertEquals("#", attr.value)
        assertEquals(listOf("first-child"), selectors[1].compounds.single().pseudoClasses)
        assertEquals(listOf("nth-child(2n+1)"), selectors[2].compounds.single().pseudoClasses)
        assertEquals("before", selectors[3].compounds.single().pseudoElement)
    }

    // ---------- at-rules ----------

    @Test
    fun mediaScreenAndAllApplyPrintIsSkipped() {
        val css = """
            @media screen { p { color: red } }
            @media print { p { color: blue } }
            @media all { p { margin: 0 } }
        """.trimIndent()
        val sheet = CssParser.parse(css)
        assertEquals(2, sheet.rules.size)
        assertEquals("red", sheet.rules[0].declarations.single().value)
        assertEquals("0", sheet.rules[1].declarations.single().value)
        assertEquals(listOf(0, 1), sheet.rules.map { it.sourceOrder })
    }

    @Test
    fun unknownAtRulesAreIgnoredNotFatal() {
        val css = """
            @charset "utf-8";
            @namespace epub url(http://www.idpf.org/2007/ops);
            @supports (display: flex) { p { color: pink } }
            @page { margin: 1cm }
            @keyframes spin { from { x: 1 } to { x: 2 } }
            p { color: red }
        """.trimIndent()
        val sheet = CssParser.parse(css)
        assertEquals(1, sheet.rules.size)
        assertEquals("red", sheet.rules.single().declarations.single().value)
    }

    // ---------- declarations ----------

    @Test
    fun commentsAndQuotedStringsAreHandled() {
        val css = """/* pre */ p { font-family: "a\"b"; content: ";" } /* post */"""
        val sheet = CssParser.parse(css)
        val decls = sheet.rules.single().declarations
        assertEquals(2, decls.size)
        assertEquals("\"a\\\"b\"", decls[0].value)
        assertEquals("\";\"", decls[1].value)
    }

    @Test
    fun sourceOrderAndImportantFlagsPreserved() {
        val sheet = CssParser.parse("p { a: 1 } p { b: 2 !important }")
        assertEquals(listOf(0, 1), sheet.rules.map { it.sourceOrder })
        assertFalse(sheet.rules[0].declarations.single().important)
        assertTrue(sheet.rules[1].declarations.single().important)
        assertEquals("2", sheet.rules[1].declarations.single().value)
    }

    @Test
    fun invalidSelectorSkipsRuleWithoutThrowing() {
        val sheet = CssParser.parse("p..bad { color: red } p { color: blue }")
        assertEquals(1, sheet.rules.size)
        assertEquals("blue", sheet.rules.single().declarations.single().value)
    }

    @Test
    fun urlWithSemicolonInsideParensIsOneDeclaration() {
        val css = """p { background: url("data:image/svg+xml;base64,AAA=") no-repeat }"""
        val decl = CssParser.parse(css).rules.single().declarations.single()
        assertEquals("background", decl.property)
        assertTrue(decl.value.contains("base64,AAA="))
    }

    @Test
    fun emptyAndJunkDeclarationsAreSkipped() {
        val sheet = CssParser.parse("p { ; color: red; garbage; margin: }")
        assertEquals(listOf("color"), sheet.rules.single().declarations.map { it.property })
    }

    @Test
    fun stylesheetCanBeEmpty() {
        val sheet = CssParser.parse("")
        assertTrue(sheet.rules.isEmpty())
        assertNotNull(sheet.warnings)
    }
}
