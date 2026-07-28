/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.idea

import org.azora.lang.idea.annotator.AzoraAnnotationInfo
import org.azora.lang.idea.annotator.AzoraExternalAnnotator
import org.azora.lang.idea.symbol.AzoraMacroScanner
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs the lexer and the diagnostics over real Azora sources.
 *
 * Synthetic snippets are easy to get right; whole files written by people are
 * where a lexer's edge cases actually show up. These tests are skipped when the
 * sources are not checked out beside the plugin, so they never fail a clean
 * clone — but where the sources exist, they are the strongest signal that the
 * plugin does not report problems in code that compiles.
 */
class RealSourcesSmokeTest {

    private val annotator = AzoraExternalAnnotator()

    /** A checked-out sibling directory, or `null` when it is not present. */
    private fun sibling(relative: String): File? =
        File(System.getProperty("user.home"), "azora/$relative").takeIf { it.exists() }

    /** Every `.az` file under [root]. */
    private fun sourcesUnder(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "az" }.toList()

    /** Lexes [source] and asserts the token stream covers it exactly. */
    private fun assertLexesCompletely(file: File) {
        val source = file.readText()
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)

        val rebuilt = StringBuilder()
        var previousEnd = 0
        while (lexer.tokenType != null) {
            assertEquals(previousEnd, lexer.tokenStart, "gap in the token stream of ${file.name}")
            rebuilt.append(source, lexer.tokenStart, lexer.tokenEnd)
            previousEnd = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(source.length, previousEnd, "token stream stopped short in ${file.name}")
        assertEquals(source, rebuilt.toString(), "token stream does not reproduce ${file.name}")
    }

    @Test
    fun `serializer az lexes and reports no errors`() {
        val file = sibling("azora-lang/std/serializer.az")
        assumeTrue(file != null, "azora-lang is not checked out beside the plugin")

        assertLexesCompletely(file!!)

        // The `'"'` char literal in this file is what used to produce a bogus
        // "Unterminated string literal" for everything after it.
        val diagnostics = annotator
            .doAnnotate(AzoraAnnotationInfo(file.readText(), file.name, file.path))
            .diagnostics
            .filter { it.severity == HighlightSeverity.ERROR }

        assertTrue(diagnostics.isEmpty(), "unexpected errors in serializer.az: $diagnostics")
    }

    @Test
    fun `the whole standard library lexes and reports no errors`() {
        val root = sibling("azora-lang/std")
        assumeTrue(root != null, "the standard library is not checked out beside the plugin")

        val failures = mutableListOf<String>()
        for (file in sourcesUnder(root!!)) {
            assertLexesCompletely(file)
            val errors = annotator
                .doAnnotate(AzoraAnnotationInfo(file.readText(), file.name, file.path))
                .diagnostics
                .filter { it.severity == HighlightSeverity.ERROR }
            if (errors.isNotEmpty()) failures.add("${file.name}: ${errors.map { it.message }}")
        }

        assertTrue(failures.isEmpty(), "the plugin reports errors in valid stdlib sources:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the engine lexes and reports no errors`() {
        val root = sibling("azora-engine/packages")
        assumeTrue(root != null, "azora-engine is not checked out beside the plugin")

        val failures = mutableListOf<String>()
        for (file in sourcesUnder(root!!)) {
            assertLexesCompletely(file)
            val errors = annotator
                .doAnnotate(AzoraAnnotationInfo(file.readText(), file.name, file.path))
                .diagnostics
                .filter { it.severity == HighlightSeverity.ERROR }
            if (errors.isNotEmpty()) failures.add("${file.name}: ${errors.map { it.message }}")
        }

        assertTrue(failures.isEmpty(), "the plugin reports errors in valid engine sources:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the engine's ECS module really does declare with as a macro`() {
        val file = sibling("azora-engine/packages/azora-ecs/src/ecs.az")
        assumeTrue(file != null, "azora-engine is not checked out beside the plugin")

        val macros = AzoraMacroScanner.scan(file!!.readText())

        // This is the case the highlighting design turns on: a language keyword
        // that a real dependency also declares as an infix type macro.
        assertTrue("with" in macros.infix, "expected 'with' among ${macros.infix}")
        assertTrue("without" in macros.infix)
        assertTrue("res" in macros.prefix, "expected 'res' among ${macros.prefix}")
        assertTrue("query" in macros.prefix)
    }
}
