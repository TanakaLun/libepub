package org.libepub.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.parse.BookParser
import org.libepub.core.testutil.SampleBooks

/**
 * Performance floor for the parser + layout pipeline. The thresholds are the
 * "JVM, relaxed" numbers from the acceptance table: cold open under a second
 * and a single chapter under 500 ms on this container. Real-device numbers are
 * tracked by `docs/acceptance-device-checklist.md`.
 */
class PerfBenchmarkTest {

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

    private fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun coldOpenParsesContainerPackageAndTocUnderOneSecond() {
        val elapsed = millis { BookParser.open(SampleBooks.sample()).close() }
        println("perf: cold open ${elapsed}ms")
        assertTrue("cold open took ${elapsed}ms", elapsed < 1_000)
    }

    @Test
    fun singleChapterLayoutMedianStaysUnderFiveHundredMilliseconds() {
        LayoutSession.open(BookParser.open(SampleBooks.sample()), FixedMeasurer()).use { session ->
            val href = session.book.readableSpine.first { it.href!!.contains("Section003") }.href!!

            // Warm up JIT and the shared stylesheet cache.
            repeat(2) { index -> session.layout(href, settings.copy(theme = "warm$index")) }

            val samples = (1..5).map { index ->
                millis { session.layout(href, settings.copy(theme = "sample$index")) }
            }.sorted()
            val median = samples[samples.size / 2]
            println("perf: chapter layout median ${median}ms samples=$samples")
            assertTrue("chapter layout median ${median}ms", median < 500)
        }
    }

    @Test
    fun tenPageTurnsReuseTheSameLayoutObject() {
        LayoutSession.open(BookParser.open(SampleBooks.sample()), FixedMeasurer()).use { session ->
            val href = session.book.readableSpine.first { it.href!!.contains("Section003") }.href!!
            val layout = session.layout(href, settings)
            val paged = session.paginate(href, settings, PageBox(settings.widthPx, settings.heightPx))
            assertTrue("chapter should have several pages", paged.pageCount > 1)

            repeat(10) { turn ->
                val page = paged.pages[turn % paged.pageCount]
                assertTrue(page.lineCount > 0)
                assertSame("page turn $turn must not relayout", layout, session.layout(href, settings))
            }
            assertEquals("only the current chapter's layout is cached", 1, session.cache.size)
        }
    }
}
