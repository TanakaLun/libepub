package org.libepub.core

import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeTest {
    @Test
    fun toolchainWired() {
        // Slice 1 wiring canary: proves :core compiles Kotlin and runs JUnit on JVM.
        assertTrue("libepub core is wired".endsWith("wired"))
    }
}
