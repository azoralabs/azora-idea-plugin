/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.symbol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Current `macro` declaration scanner regressions. */
class AzoraMacroScannerTest {

    @Test
    fun `finds prefix block macros`() {
        val macros = AzoraMacroScanner.scan(
            """
            scope std {
                macro @vec {
                    [] => std::emptyList()
                    [...${'$'}items] => std::listOf(...${'$'}items)
                }
            }
            """.trimIndent(),
        )

        assertEquals(setOf("vec"), macros.prefix)
        assertTrue(macros.infix.isEmpty())
    }

    @Test
    fun `finds current infix macro declarations`() {
        val macros = AzoraMacroScanner.scan(
            """
            macro ${'$'}left @combine ${'$'}right => std::combine(${'$'}left, ${'$'}right)
            macro ${'$'}anchor @applyKey ${'$'}key => ${'$'}key
            """.trimIndent(),
        )

        assertEquals(setOf("combine", "applyKey"), macros.infix)
    }

    @Test
    fun `query block exposes with and without clause fragments`() {
        val macros = AzoraMacroScanner.scan(
            """
            macro @query {
                [${'$'}Q] => QueryOf<${'$'}Q>
                [${'$'}Q] with ${'$'}T => QueryWith<${'$'}Q, ${'$'}T>
                [${'$'}Q] with ${'$'}T without ${'$'}S => QueryWithout<${'$'}Q, ${'$'}S>
            }
            """.trimIndent(),
        )

        assertTrue("query" in macros.prefix)
        assertTrue("with" in macros.prefix)
        assertTrue("without" in macros.prefix)
    }

    @Test
    fun `single expression prefix macro is recognized`() {
        val macros = AzoraMacroScanner.scan("macro @children => inline \"react [; &]\"")
        assertEquals(setOf("children"), macros.prefix)
    }

    @Test
    fun `comments and strings do not declare macros`() {
        val macros = AzoraMacroScanner.scan(
            """
            // macro @commented => 1
            fin text = "macro @insideString => 2"
            /* macro ${'$'}a @hidden ${'$'}b => 3 */
            """.trimIndent(),
        )

        assertTrue(macros.all.isEmpty())
        assertFalse("commented" in macros.prefix)
    }
}
