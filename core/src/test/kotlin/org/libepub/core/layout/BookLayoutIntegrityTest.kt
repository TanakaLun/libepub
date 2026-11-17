package org.libepub.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.container.ResourcePath
import org.libepub.core.parse.BookParser
import org.libepub.core.semantics.ImageGroupKind
import org.libepub.core.testutil.SampleBooks

/**
 * Whole-book integrity: every spine document must parse, cascade, lay out and
 * paginate without throwing, every footnote must pair with its content, every
 * image must resolve inside the container, and no chapter may become
 * pathologically slow.
 */
class BookLayoutIntegrityTest {

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

    private val settings = LayoutSession.Settings(
        fontSizePx = 16f,
        widthPx = 400f,
        heightPx = 800f,
    )

    @Test
    fun everySpineDocumentParsesLaysOutAndPaginates() {
        LayoutSession.open(BookParser.open(SampleBooks.sample()), FixedMeasurer()).use { session ->
            val spine = session.book.readableSpine
            assertEquals("sample book has 24 spine documents", 24, spine.size)

            var footnotes = 0
            var images = 0
            var groups = 0
            var slowest = 0L
            val totalStart = System.nanoTime()

            for (item in spine) {
                val href = item.href!!
                val document = session.book.chapter(href)

                val start = System.nanoTime()
                val layout = session.layout(href, settings)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                slowest = maxOf(slowest, elapsedMs)
                assertTrue("chapter $href laid out in ${elapsedMs}ms", elapsedMs < 2_000)

                // image-only chapters (illustration pages) legitimately have no text lines
                assertTrue(
                    "chapter $href produced no content",
                    layout.lines.isNotEmpty() || layout.boxes.any { it.kind == BoxKind.IMAGE },
                )
                assertTrue("chapter $href has no content height", layout.contentBlockSize > 0f)
                assertTrue("lines are ordered in $href", layout.lines.zipWithNext().all { (a, b) -> b.block >= a.block })

                val paged = session.paginate(href, settings, PageBox(settings.widthPx, settings.heightPx))
                assertTrue("chapter $href produced no pages", paged.pageCount > 0)
                assertEquals(
                    "every line belongs to exactly one page in $href",
                    layout.lines.size,
                    paged.pages.sumOf { it.lineCount },
                )

                // Every image source must resolve inside the container.
                for (node in document.root.walk()) {
                    if (!node.isElement || node.name != "img") continue
                    images++
                    val src = node.attr("src") ?: continue
                    val resolved = ResourcePath.resolve(ResourcePath.parent(href), src)
                    assertTrue("missing image resource $resolved in $href", session.book.container.exists(resolved))
                }

                // Every footnote marker pairs with a content element carrying text.
                for (footnote in document.footnotes) {
                    footnotes++
                    assertTrue(
                        "dangling footnote #${footnote.refId} in $href",
                        footnote.target != null,
                    )
                    assertTrue(
                        "empty footnote #${footnote.refId} in $href",
                        footnote.text.isNotBlank(),
                    )
                    assertEquals("【注】", footnote.markerText)
                }

                groups += document.imageGroups.count { it.kind == ImageGroupKind.SINGLE }
            }

            val totalMs = (System.nanoTime() - totalStart) / 1_000_000
            println("book layout: ${spine.size} chapters in ${totalMs}ms (slowest ${slowest}ms)")

            assertEquals("sample book has 4 duokan footnotes", 4, footnotes)
            assertEquals("sample book has 23 <img> elements", 23, images)
            assertEquals("sample book has 18 single-illustration groups", 18, groups)
            assertTrue("whole-book layout should stay interactive: ${totalMs}ms", totalMs < 10_000)
        }
    }

    @Test
    fun chapterLayoutsAreStableAcrossCacheHits() {
        LayoutSession.open(BookParser.open(SampleBooks.sample()), FixedMeasurer()).use { session ->
            val href = session.book.readableSpine.first { it.href!!.contains("Section003") }.href!!
            val first = session.layout(href, settings)
            val second = session.layout(href, settings)
            assertEquals(first.lines.size, second.lines.size)
            assertEquals(first.contentBlockSize, second.contentBlockSize, 0.01f)
            assertEquals(first.anchors.keys, second.anchors.keys)
        }
    }
}
