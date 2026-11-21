package org.libepub.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.parse.BookParser
import org.libepub.core.testutil.SampleBooks
import org.libepub.core.testutil.SyntheticZips
import java.io.File

/**
 * Book-level view: which chapters ship scripts, what they do, and whether the
 * reader covers all of it. Nothing here executes JavaScript.
 */
class ScriptReportTest {

    private fun syntheticBook(): File {
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bid">urn:test</dc:identifier>
    <dc:title>Scripts</dc:title>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="a" href="Text/a.xhtml" media-type="application/xhtml+xml"/>
    <item id="b" href="Text/b.xhtml" media-type="application/xhtml+xml"/>
    <item id="js" href="Misc/note.js" media-type="application/javascript"/>
  </manifest>
  <spine><itemref idref="a"/><itemref idref="b"/></spine>
</package>
"""
        return SyntheticZips.write(
            packageXml = opf,
            extra = mapOf(
                "OEBPS/Text/b.xhtml" to
                    """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>x</p>""" +
                    """<script src="../Misc/note.js" type="text/javascript"></script></body></html>""",
                "OEBPS/Misc/note.js" to
                    """$(document).ready(function(){ $(".duokan-footnote").html("【注】"); });""",
            ),
        )
    }

    @Test
    fun syntheticBookReportsHandledScripts() {
        BookParser.open(syntheticBook()).use { book ->
            val report = book.scriptReport()

            assertEquals(listOf("OEBPS/Text/b.xhtml"), report.chapters.keys.toList())
            assertEquals(listOf("duokan-footnote-marker"), report.patterns.map { it.id })
            assertEquals(ScriptSupport.HANDLED, report.patterns.single().support)
            assertTrue(report.unsupported.isEmpty())
            assertTrue(report.fullyHandled)
        }
    }

    @Test
    fun chaptersWithoutScriptsAreAbsentFromTheReport() {
        BookParser.open(syntheticBook()).use { book ->
            val report = book.scriptReport()
            assertTrue("OEBPS/Text/a.xhtml ships no script", "OEBPS/Text/a.xhtml" !in report.chapters)
            assertEquals(listOf("OEBPS/Text/b.xhtml"), report.chaptersUsing("duokan-footnote-marker"))
        }
    }

    @Test
    fun emptyReportForBooksWithoutScripts() {
        BookParser.open(SyntheticZips.write()).use { book ->
            val report = book.scriptReport()
            assertTrue(report.isEmpty)
            assertTrue(report.patterns.isEmpty())
            assertTrue("no scripts is not the same as fully handled", !report.fullyHandled)
        }
    }

    @Test
    fun sampleBookScriptsAreFullyHandled() {
        BookParser.open(SampleBooks.sample()).use { book ->
            val report = book.scriptReport()

            assertEquals("every scripted chapter is reported", 18, report.chapters.size)
            assertEquals(listOf("jQuery"), report.libraries)
            assertEquals(listOf("duokan-footnote-marker"), report.patterns.map { it.id })
            assertTrue("nothing in the sample book needs JS", report.unsupported.isEmpty())
            assertTrue(report.fullyHandled)
            assertEquals(18, report.chaptersUsing("duokan-footnote-marker").size)
        }
    }
}
