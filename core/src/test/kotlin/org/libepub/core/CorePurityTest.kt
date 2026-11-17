package org.libepub.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard rail: `:core` must stay a plain JVM library so the parser and layout
 * engine can be tested (and reused) without an Android runtime. Android types
 * belong to `:render`.
 */
class CorePurityTest {

    @Test
    fun coreSourcesNeverImportAndroidTypes() {
        val sourceRoot = File(System.getProperty("user.dir"), "src/main/kotlin")
        assertTrue("source root not found: $sourceRoot", sourceRoot.isDirectory)

        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")
                    }
                    .map { (index, line) -> "${file.relativeTo(sourceRoot)}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertTrue(":core must stay pure JVM, found Android imports:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }
}
