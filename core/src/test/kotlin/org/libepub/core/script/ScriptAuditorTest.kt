package org.libepub.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.XhtmlParser

/**
 * Scripts are never executed: the auditor only has to recognise what they do
 * and say whether the engine already reproduces it. These tests pin that
 * mapping, including the false-positive traps in minified libraries.
 */
class ScriptAuditorTest {

    private fun audit(
        text: String,
        href: String? = null,
        type: String? = null,
        provider: ScriptSourceProvider? = null,
    ): ScriptAudit {
        val raw = RawScript(src = href, type = type, inline = text)
        return ScriptAuditor.audit("OEBPS/Text/a.xhtml", listOf(raw), provider, WarningSink())
    }

    @Test
    fun duokanFootnoteScriptIsHandledNatively() {
        val result = audit(
            """
            $(document).ready(function(){
                $(".duokan-footnote").html("【注】");
            });
            """.trimIndent(),
        )

        assertEquals(ScriptSupport.HANDLED, result.patterns.single().support)
        assertEquals("duokan-footnote-marker", result.patterns.single().id)
        assertTrue(result.fullyHandled)
        assertTrue("no script should be reported unsupported", result.unsupported.isEmpty())
    }

    @Test
    fun bareMarkerLiteralIsAlsoHandled() {
        val result = audit("""$('.duokan-footnote').text('【注】')""")
        assertEquals("duokan-footnote-marker", result.patterns.single().id)
        assertEquals(ScriptSupport.HANDLED, result.patterns.single().support)
    }

    @Test
    fun jqueryBundleIsRecognisedAndItsInternalsAreNotClassified() {
        // jQuery itself contains ".hide(", ".show(" and "scrollTo"; only the
        // application tail after the IIFE may be classified.
        val library = """
            /*! jQuery v1.7.2 jquery.com | jquery.org/license */
            (function(a,b){function hide(){return this}var scrollTo=1;b.show=function(){}})(window);
        """.trimIndent()
        val app = """$(document).ready(function(){ $(".duokan-footnote").html("【注】"); });"""

        val result = audit(library + "\n" + app)

        assertEquals("jQuery", result.sources.single().library)
        assertEquals(listOf("duokan-footnote-marker"), result.patterns.map { it.id })
        assertTrue(result.unsupported.isEmpty())
    }

    @Test
    fun libraryWithoutApplicationCodeReportsNothingToEmulate() {
        val result = audit(
            """/*! jQuery v1.7.2 jquery.com */ (function(a,b){var x=1;})(window);""",
        )

        assertEquals("jQuery", result.sources.single().library)
        assertTrue(result.patterns.isEmpty())
        assertTrue("a library alone is not an unsupported behaviour", result.unsupported.isEmpty())
    }

    @Test
    fun navigationScriptIsUnsupported() {
        val result = audit("""window.scrollTo(0, 0); location.href = "#top";""")

        assertEquals("navigation", result.patterns.single().id)
        assertEquals(ScriptSupport.UNSUPPORTED, result.patterns.single().support)
        assertFalse(result.fullyHandled)
    }

    @Test
    fun documentWriteIsUnsupported() {
        val result = audit("""document.write("<p>generated</p>")""")

        assertEquals("document-write", result.patterns.single().id)
        assertEquals(ScriptSupport.UNSUPPORTED, result.patterns.single().support)
    }

    @Test
    fun visibilityToggleIsUnsupported() {
        val result = audit("""$(".spoiler").toggle();""")
        assertEquals("visibility-toggle", result.patterns.single().id)
        assertEquals(ScriptSupport.UNSUPPORTED, result.patterns.single().support)
    }

    @Test
    fun imageResizeIsUnsupported() {
        val result = audit("""$("img").css('width', '100%');""")
        assertEquals("image-resize", result.patterns.single().id)
    }

    @Test
    fun unknownBehaviourIsReportedWithEvidence() {
        val result = audit("""var secret = atob("aGVsbG8=");""")

        val pattern = result.patterns.single()
        assertEquals("unknown", pattern.id)
        assertEquals(ScriptSupport.UNSUPPORTED, pattern.support)
        assertTrue(pattern.evidence.contains("atob"))
    }

    @Test
    fun nonJavaScriptTypeIsSkippedNotClassified() {
        val result = audit(
            """<div class="tpl">x</div>""",
            href = "tpl.html",
            type = "text/template",
        )

        assertTrue(result.sources.isEmpty())
        assertEquals(listOf("tpl.html"), result.skipped)
    }

    @Test
    fun legacyLanguageAttributeStillCountsAsJavaScript() {
        val result = audit("""$(".duokan-footnote").html("【注】")""", type = "javascript")
        assertEquals(1, result.sources.size)
    }

    @Test
    fun missingExternalScriptIsSkipped() {
        val warnings = WarningSink()
        val raw = RawScript(src = "../Misc/gone.js", type = null, inline = "")
        val result = ScriptAuditor.audit("OEBPS/Text/a.xhtml", listOf(raw), ScriptSourceProvider { null }, warnings)

        assertTrue(result.sources.isEmpty())
        assertEquals(listOf("OEBPS/Misc/gone.js"), result.skipped)
        assertTrue(warnings.all.any { it.contains("missing script") })
    }

    @Test
    fun externalScriptIsResolvedRelativeToTheDocument() {
        val result = audit(
            "",
            href = "../Misc/note.js",
            provider = { path ->
                if (path == "OEBPS/Misc/note.js") {
                    """$(".duokan-footnote").html("【注】")"""
                } else {
                    null
                }
            },
        )

        val source = result.sources.single()
        assertEquals(ScriptOrigin.EXTERNAL, source.origin)
        assertEquals("../Misc/note.js", source.src)
        assertEquals("OEBPS/Misc/note.js", source.href)
        assertNotNull(source.patterns.firstOrNull { it.id == "duokan-footnote-marker" })
    }

    @Test
    fun remoteScriptIsIgnoredWithoutNetworkAccess() {
        val result = audit("", href = "https://cdn.example.com/x.js")
        assertTrue(result.sources.isEmpty())
        assertEquals(listOf("https://cdn.example.com/x.js"), result.skipped)
    }

    @Test
    fun emptyInlineScriptIsNotReported() {
        val result = audit("   \n  ")
        assertTrue(result.isEmpty)
    }

    @Test
    fun xhtmlParserKeepsScriptsOutOfTheTreeButAuditsThem() {
        val document = XhtmlParser.parse(
            """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
              <p>text</p>
              <script type="text/javascript">window.scrollTo(0,0)</script>
            </body></html>
            """.trimIndent(),
            "OEBPS/Text/a.xhtml",
        )

        assertTrue(
            "script elements must never reach the layout tree",
            document.root.walk().none { it.name == "script" },
        )
        assertEquals(1, document.scriptAudit.sources.size)
        assertEquals("navigation", document.scriptAudit.patterns.single().id)
        assertNull(document.byId("nope"))
    }
}
