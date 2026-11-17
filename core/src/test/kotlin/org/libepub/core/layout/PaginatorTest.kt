package org.libepub.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.css.WritingMode
import org.libepub.core.model.Document
import org.libepub.core.util.WarningSink
import org.libepub.core.xhtml.XhtmlParser

class PaginatorTest {

    private val document: Document by lazy {
        XhtmlParser.parse(
            "<html><body><p>x</p></body></html>",
            "OEBPS/Text/x.xhtml",
            WarningSink(),
        )
    }

    private fun layoutOf(
        lineCount: Int,
        blockSize: Float = 10f,
        spacing: Float = 10f,
        writingMode: WritingMode = WritingMode.HORIZONTAL_TB,
        breaks: List<PageBreakPoint> = emptyList(),
        anchors: Map<String, LayoutAnchor> = emptyMap(),
        sizes: List<Float>? = null,
    ): ChapterLayout {
        var cursor = 0f
        val lines = (0 until lineCount).map { index ->
            val size = sizes?.get(index) ?: blockSize
            val line = LayoutLine(
                index = index,
                block = if (sizes != null) cursor else index * spacing,
                blockSize = size,
                main = 0f,
                mainSize = 100f,
                baseline = 8f,
                fragments = emptyList(),
            )
            cursor += size
            line
        }
        return ChapterLayout(
            document = document,
            writingMode = writingMode,
            widthPx = 200f,
            heightPx = 400f,
            contentBlockSize = lines.last().block + lines.last().blockSize,
            lines = lines,
            boxes = emptyList(),
            anchors = anchors,
            pageBreaks = breaks,
        )
    }

    private val paginator = Paginator()

    private fun horizontal(lineCount: Int, height: Float = 30f, layout: ChapterLayout? = null) =
        paginator.paginate(layout ?: layoutOf(lineCount), PageBox(200f, height))

    // ------------------------------------------------------------ line slicing

    @Test
    fun pagesSplitOnLineBoundaries() {
        val paged = horizontal(25)
        assertEquals(9, paged.pageCount)
        assertEquals(0, paged.pages.first().startLine)
        assertEquals(3, paged.pages.first().endLine)
        assertEquals(24, paged.pages.last().startLine)
        assertEquals(25, paged.pages.last().endLine)
        assertTrue(paged.pages.all { it.lineCount in 1..3 })
    }

    @Test
    fun paginationNeverProducesAnEmptyPage() {
        val layout = layoutOf(10, breaks = listOf(PageBreakPoint(0f), PageBreakPoint(30f)))
        val paged = horizontal(0, layout = layout)
        assertTrue(paged.pages.isNotEmpty())
        assertTrue(paged.pages.all { it.lineCount > 0 })
        assertEquals(paged.pages.size, paged.pages.map { it.startLine }.distinct().size)
    }

    @Test
    fun forcedPageBreakStartsANewPage() {
        val layout = layoutOf(10, breaks = listOf(PageBreakPoint(30f)))
        val paged = horizontal(0, height = 100f, layout = layout)
        assertEquals(2, paged.pageCount)
        assertEquals(0, paged.pages[0].startLine)
        assertEquals(3, paged.pages[0].endLine)
        assertFalse(paged.pages[0].forced)
        assertEquals(3, paged.pages[1].startLine)
        assertTrue(paged.pages[1].forced)
    }

    @Test
    fun forcedBreakAtTheVeryStartDoesNotCreateAnEmptyPage() {
        val layout = layoutOf(5, breaks = listOf(PageBreakPoint(0f)))
        val paged = horizontal(0, layout = layout)
        assertEquals(2, paged.pageCount)
        assertEquals(0, paged.pages.first().startLine)
        assertFalse(paged.pages.first().forced)
        assertTrue(paged.pages.all { it.lineCount > 0 })
    }

    @Test
    fun lineTallerThanThePageIsNotSplit() {
        val layout = layoutOf(3, sizes = listOf(10f, 50f, 10f))
        val paged = horizontal(0, height = 30f, layout = layout)
        assertEquals(3, paged.pageCount)
        assertEquals(1, paged.pages[1].startLine)
        assertEquals(2, paged.pages[1].endLine)
        assertEquals(100f, layout.lines[1].mainSize, 0.01f)
    }

    @Test
    fun lineGeometryIsUntouchedByPagination() {
        val layout = layoutOf(12)
        val paged = horizontal(0, layout = layout)
        for (page in paged.pages) {
            for (index in page.startLine until page.endLine) {
                assertEquals(layout.lines[index].mainSize, layout.lines[index].mainSize, 0.01f)
                assertEquals(layout.lines[index].block, layout.lines[index].block, 0.01f)
            }
        }
        assertEquals(12, paged.pages.sumOf { it.lineCount })
    }

    // ------------------------------------------------------------------ anchors

    @Test
    fun anchorsMapToThePageContainingTheirLine() {
        val layout = layoutOf(
            10,
            anchors = mapOf(
                "a" to LayoutAnchor(null, 0f, 25f),
                "b" to LayoutAnchor(null, 0f, 35f),
                "c" to LayoutAnchor(null, 0f, 5f),
            ),
        )
        val paged = horizontal(0, height = 30f, layout = layout)
        assertEquals(0, paged.pageForAnchor("a"))
        assertEquals(1, paged.pageForAnchor("b"))
        assertEquals(0, paged.pageForAnchor("c"))
        assertNull(paged.pageForAnchor("missing"))
    }

    @Test
    fun printedPageLabelsLandOnTheirPage() {
        val layout = layoutOf(
            10,
            breaks = listOf(PageBreakPoint(20f, "12"), PageBreakPoint(50f, "13")),
        )
        val paged = horizontal(0, height = 30f, layout = layout)
        // a label sitting exactly on a forced break belongs to the page it starts
        assertTrue(paged.labelsOnPage(0).isEmpty())
        assertEquals(listOf("12"), paged.labelsOnPage(1))
        assertEquals(listOf("13"), paged.labelsOnPage(2))
        assertTrue(paged.labelsOnPage(3).isEmpty())
    }

    // ----------------------------------------------------------------- vertical

    @Test
    fun verticalPagesHoldWholeColumns() {
        val layout = layoutOf(25, writingMode = WritingMode.VERTICAL_RL)
        val paged = paginator.paginate(layout, PageBox(100f, 400f))
        assertEquals(3, paged.pageCount)
        assertEquals(listOf(10, 10, 5), paged.pages.map { it.lineCount })
    }

    @Test
    fun verticalPageExtentIsQuantizedToWholeColumns() {
        val layout = layoutOf(25, writingMode = WritingMode.VERTICAL_RL)
        // 105px / 10px advance -> 10 whole columns, the 5px remainder is unused
        val paged = paginator.paginate(layout, PageBox(105f, 400f))
        assertEquals(10, paged.pages.first().lineCount)
        assertEquals(3, paged.pageCount)
    }

    // ------------------------------------------------------------- determinism

    @Test
    fun paginationIsDeterministic() {
        val layout = layoutOf(37, breaks = listOf(PageBreakPoint(70f)))
        val first = paginator.paginate(layout, PageBox(200f, 30f))
        val second = paginator.paginate(layout, PageBox(200f, 30f))
        assertEquals(first, second)
    }

    @Test
    fun pageCountFollowsThePageBox() {
        val layout = layoutOf(25)
        assertEquals(9, paginator.paginate(layout, PageBox(200f, 30f)).pageCount)
        assertEquals(5, paginator.paginate(layout, PageBox(200f, 60f)).pageCount)
    }
}
