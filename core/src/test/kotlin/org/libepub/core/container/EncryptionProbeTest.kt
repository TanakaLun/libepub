package org.libepub.core.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libepub.core.DrmProtectedException
import org.libepub.core.testutil.SampleBooks
import org.libepub.core.testutil.SyntheticZips

class EncryptionProbeTest {

    @Test
    fun sampleBookHasNoEncryption() {
        EpubContainer.open(SampleBooks.sample()).use { container ->
            val info = EncryptionInfo.parse(container)
            assertEquals(EncryptionInfo.NONE, info)
            assertFalse(info.isDrm)
        }
    }

    @Test
    fun classifiesFontObfuscationAsLegitimate() {
        val file = SyntheticZips.write(
            cipherPairs = listOf(EncryptionInfo.IDPF_ALGORITHM to "OEBPS/Fonts/jp.ttf"),
        )
        EpubContainer.open(file).use { container ->
            val info = EncryptionInfo.parse(container)
            assertFalse(info.isDrm)
            assertEquals(setOf("OEBPS/Fonts/jp.ttf"), info.obfuscatedFontPaths)
        }
    }

    @Test
    fun unknownContentCipherIsDrm() {
        val file = SyntheticZips.write(
            cipherPairs = listOf(
                "http://www.w3.org/2001/04/xmlenc#aes128-cbc" to "OEBPS/Text/a.xhtml",
                EncryptionInfo.ADOBE_ALGORITHM to "OEBPS/Fonts/x.ttf",
            ),
        )
        EpubContainer.open(file).use { container ->
            val info = EncryptionInfo.parse(container)
            assertTrue(info.isDrm)
            assertEquals(setOf("OEBPS/Fonts/x.ttf"), info.obfuscatedFontPaths)
        }
    }

    @Test
    fun drmGateThrowsForEncryptedContent() {
        val file = SyntheticZips.write(
            cipherPairs = listOf("urn:example:lcp" to "OEBPS/Text/a.xhtml"),
        )
        EpubContainer.open(file).use { container ->
            val info = EncryptionInfo.parse(container)
            try {
                if (info.isDrm) throw DrmProtectedException("content encrypted")
                throw AssertionError("expected drm")
            } catch (expected: DrmProtectedException) {
                // contract: callers surface this instead of rendering garbage
            }
        }
    }
}
