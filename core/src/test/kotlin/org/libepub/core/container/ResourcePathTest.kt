package org.libepub.core.container

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourcePathTest {

    @Test
    fun normalizesParentSegments() {
        assertEquals(
            "OEBPS/Images/001.jpg",
            ResourcePath.resolve("OEBPS/Text", "../Images/001.jpg"),
        )
    }

    @Test
    fun normalizesCurrentAndNestedSegments() {
        assertEquals(
            "OEBPS/Text/ch.xhtml",
            ResourcePath.resolve("OEBPS", "./Text/./ch.xhtml"),
        )
        assertEquals(
            "Text/ch.xhtml",
            ResourcePath.resolve("", "Text/ch.xhtml"),
        )
    }

    @Test
    fun clampsAboveRoot() {
        assertEquals("x.jpg", ResourcePath.resolve("OEBPS", "../../x.jpg"))
    }

    @Test
    fun percentDecodesUtf8() {
        assertEquals(
            "OEBPS/章.xhtml",
            ResourcePath.resolve("OEBPS", "%E7%AB%A0.xhtml"),
        )
    }

    @Test
    fun toleratesBackslashes() {
        assertEquals(
            "OEBPS/Text/ch.xhtml",
            ResourcePath.resolve("OEBPS", "Text\\ch.xhtml"),
        )
    }

    @Test
    fun splitsFragmentAndQuery() {
        assertEquals(
            "Text/ch.xhtml" to "sec2",
            ResourcePath.splitFragment("Text/ch.xhtml#sec2"),
        )
        assertEquals(
            "Text/ch.xhtml" to null,
            ResourcePath.splitFragment("Text/ch.xhtml"),
        )
        assertEquals(
            "Text/ch.xhtml" to "sec2",
            ResourcePath.splitFragment("Text/ch.xhtml?x=1#sec2"),
        )
    }

    @Test
    fun idempotentOnAlreadyRootedPath() {
        assertEquals("OEBPS/a.xhtml", ResourcePath.resolve("", "/OEBPS/a.xhtml"))
    }
}
