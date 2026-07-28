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

package org.azora.lang.idea.highlighting

import org.azora.lang.idea.AzoraLexerAdapter
import org.azora.lang.idea.AzoraTokenTypes
import org.azora.lang.idea.symbol.AzoraMacros
import com.intellij.openapi.editor.colors.TextAttributesKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests the semantic coloring pass.
 *
 * The behaviour that matters most here is the `with` rule the plugin is built
 * around: the same word must be a keyword in one position and a macro in
 * another, decided by what the project actually declares rather than by any
 * list of special words.
 */
class AzoraSemanticModelTest {

    /** Lexes [source] into the flat token list the model consumes. */
    private fun tokensOf(source: String): List<AzoraToken> {
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)
        val tokens = mutableListOf<AzoraToken>()
        while (lexer.tokenType != null) {
            tokens.add(
                AzoraToken(
                    lexer.tokenType!!,
                    lexer.tokenStart,
                    lexer.tokenEnd,
                    source.substring(lexer.tokenStart, lexer.tokenEnd),
                )
            )
            lexer.advance()
        }
        return tokens
    }

    /** The attribute assigned to the [occurrence]-th token whose text is [word]. */
    private fun keyFor(
        source: String,
        word: String,
        macros: AzoraMacros = AzoraMacros.EMPTY,
        occurrence: Int = 0,
    ): TextAttributesKey? {
        val tokens = tokensOf(source)
        val classified = AzoraSemanticModel.classify(tokens, macros)
        val token = tokens.filter { it.text == word }.getOrNull(occurrence) ?: return null
        return classified[token.start]
    }

    @Test
    fun `a keyword-named macro is a macro between operands and a keyword elsewhere`() {
        val macros = AzoraMacros(infix = setOf("with"))

        // Statement position: `with (ctx) { … }` is the language's own keyword.
        assertNull(
            keyFor("with (context) {\n    run()\n}", "with", macros),
            "`with` opening a context block must keep its keyword color",
        )

        // Operator position: `Base with Filter` is the engine's type macro.
        assertEquals(
            AzoraSyntaxHighlighter.MACRO,
            keyFor("fin q: Query<Position with Velocity> = make()", "with", macros),
        )
    }

    @Test
    fun `an undeclared keyword between operands stays a keyword`() {
        // Nothing declares `with`, so the plugin must not guess that it is a macro.
        assertNull(keyFor("fin q: Query<Position with Velocity> = make()", "with", AzoraMacros.EMPTY))
        // Grammar keywords that sit between operands are never macros either.
        assertNull(keyFor("for i in 0..10 by 2 {\n}", "by", AzoraMacros.EMPTY))
        assertNull(keyFor("for item in items {\n}", "in", AzoraMacros.EMPTY))
    }

    @Test
    fun `an unknown identifier between operands is treated as an infix macro`() {
        // Azora has no other meaning for `a word b`, so this is safe to color.
        assertEquals(
            AzoraSyntaxHighlighter.MACRO,
            keyFor("fin pair = \"a\" joinedWith 1", "joinedWith"),
        )
    }

    @Test
    fun `identifiers on consecutive lines are not read as an infix expression`() {
        val source = """
            fin first = compute()
            second = 2
        """.trimIndent()

        // Without a same-line guard, `second` would look like an infix operator
        // sitting between `compute()` and `2`.
        assertNotEquals(AzoraSyntaxHighlighter.MACRO, keyFor(source, "second"))
    }

    @Test
    fun `a declared prefix macro applied to an operand is colored`() {
        val macros = AzoraMacros(prefix = setOf("res"))
        assertEquals(
            AzoraSyntaxHighlighter.MACRO,
            keyFor("func system(world: res World) {\n}", "res", macros),
        )
    }

    @Test
    fun `a call is not mistaken for a macro applied to a parenthesised operand`() {
        // `println(x)` has no space, so the paren is a call, not an operand.
        assertNotEquals(AzoraSyntaxHighlighter.MACRO, keyFor("fin a = b\nprintln(x)", "println"))
    }

    @Test
    fun `declaration names and calls are colored distinctly`() {
        val source = """
            pack Point {
                var x: Real
            }

            func distance(): Real {
                return compute()
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.TYPE_DECLARATION, keyFor(source, "Point"))
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_DECLARATION, keyFor(source, "distance"))
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_CALL, keyFor(source, "compute"))
    }

    @Test
    fun `a member access is never colored as a macro`() {
        assertNotEquals(AzoraSyntaxHighlighter.MACRO, keyFor("fin v = point.with", "with", AzoraMacros(infix = setOf("with"))))
    }

    @Test
    fun `a binding narrowed by is gets the smart cast attribute inside the branch`() {
        val source = """
            func describe(shape: Shape): String {
                if shape is Circle {
                    return toString(shape.radius)
                }
                return "other"
            }
        """.trimIndent()

        // The second `shape` — the one inside the narrowed branch.
        assertEquals(AzoraSyntaxHighlighter.SMART_CAST, keyFor(source, "shape", occurrence = 2))
    }

    @Test
    fun `classification covers only tokens that need a semantic color`() {
        val tokens = tokensOf("fin x = 1\n")
        val classified = AzoraSemanticModel.classify(tokens, AzoraMacros.EMPTY)

        // Keywords, numbers and punctuation are the lexer's job.
        val keyword = tokens.first { it.type == AzoraTokenTypes.DECLARATION_KEYWORD }
        assertNull(classified[keyword.start])
    }
}
