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

package org.azora.lang.idea.symbol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests that macro names come from real `meta` declarations.
 *
 * The point of these is that nothing is hardcoded: a name is a macro because
 * some source file says so, which is what lets a keyword-named macro such as
 * `with` be recognised without the plugin knowing the word in advance.
 */
class AzoraMacroScannerTest {

    @Test
    fun `finds prefix and infix macros declared with meta`() {
        val macros = AzoraMacroScanner.scan(
            """
            friend zone std::container {
                meta .Prefix("vec") {
                    [...${'$'}items] =>
                }
                meta .Infix("to") {
                    ${'$'}a ${'$'}b =>
                }
            }
            """.trimIndent()
        )

        assertTrue("vec" in macros.prefix)
        assertTrue("to" in macros.infix)
    }

    @Test
    fun `strips the mutable suffix and sigil prefixes from declared names`() {
        val macros = AzoraMacroScanner.scan(
            """
            meta .Prefix("vec!") { }
            meta .Prefix("# set") { }
            meta .Prefix("^ map") { }
            """.trimIndent()
        )

        // The editor sees the bare trailing word, which is what gets colored.
        assertEquals(setOf("vec", "set", "map"), macros.prefix)
    }

    @Test
    fun `finds the type macros an engine declares, including keyword-named ones`() {
        // This is azora-engine's real ECS declaration.
        val macros = AzoraMacroScanner.scan(
            """
            module engine.ecs

            meta type {
                res ${'$'}T => ref ${'$'}T
                mut res ${'$'}T => mut ref ${'$'}T
                query [...${'$'}T] => Query<...${'$'}T>
                ${'$'}Base with ${'$'}Filter => ${'$'}Base
                ${'$'}Base without ${'$'}Filter => ${'$'}Base
            }
            """.trimIndent()
        )

        // `with` is a language keyword, yet here it is also a macro operator.
        assertTrue("with" in macros.infix, "expected 'with' among ${macros.infix}")
        assertTrue("without" in macros.infix)
        assertTrue("res" in macros.prefix)
        assertTrue("query" in macros.prefix)
    }

    @Test
    fun `finds infx declarations`() {
        val macros = AzoraMacroScanner.scan("expose infx crossed(a: Int, b: Int): Int { }")
        assertTrue("crossed" in macros.infix)
    }

    @Test
    fun `source with no meta declarations declares no macros`() {
        val macros = AzoraMacroScanner.scan(
            """
            func main() {
                fin total = 1 + 2
                with (context) { }
            }
            """.trimIndent()
        )

        assertTrue(macros.all.isEmpty())
        // In particular, `with` used as a keyword is not treated as a macro.
        assertFalse("with" in macros.infix)
    }

    @Test
    fun `comments inside a meta type block do not become macro names`() {
        val macros = AzoraMacroScanner.scan(
            """
            meta type {
                // res ${'$'}T => ref ${'$'}T
                query [...${'$'}T] => Query<...${'$'}T>
            }
            """.trimIndent()
        )

        assertEquals(setOf("query"), macros.prefix)
    }
}
