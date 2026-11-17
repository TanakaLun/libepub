package org.libepub.core.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.libepub.core.testutil.SyntheticZips

/** Minimal-book behavior: no guide, no ncx, dangling cover id — must not throw. */
class SyntheticPackageTest {

    @Test
    fun parsesMinimalBookWithoutOptionalParts() {
        val file = SyntheticZips.write()
        EpubContainer.open(file).use { container ->
            val pkg = PackageParser(container).parse()
            assertEquals("2.0", pkg.version)
            assertEquals("Synthetic & Small", pkg.metadata.title)
            assertEquals(listOf("Tester"), pkg.metadata.creators)
            assertEquals(1, pkg.spine.size)
            assertEquals("OEBPS/Text/a.xhtml", pkg.spine.first().href)
            assertNull(pkg.tocHref)
            assertNull(pkg.coverImageHref) // dangling cover id must not fabricate a path
            assertEquals("OEBPS/Text/a.xhtml", pkg.coverPageHref)
        }
    }
}
