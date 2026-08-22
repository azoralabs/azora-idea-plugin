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

import org.azora.lang.idea.highlighting.AzoraPalette
import org.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A hole in a string is a hole: the `$`, `${` and `}` that cut it belong to the
 * literal and read as punctuation on it, while what is inside is ordinary code
 * and reads exactly as it would outside the quotes.
 *
 * The delimiters are blue, which is the same accent an escape sequence wears -
 * both are places the literal stops being literal.
 */
class AzoraInterpolationLexTest {

    private fun tokenize(source: String): List<Pair<IElementType, String>> {
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)
        val out = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            out.add(lexer.tokenType!! to source.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return out
    }

    private fun typed(source: String, type: IElementType): List<String> =
        tokenize(source).filter { it.first == type }.map { it.second }

    // -- the delimiters ------------------------------------------------------

    @Test
    fun `a braced hole is opened and closed by its own tokens`() {
        val source = """"total: ${'$'}{count + 1}""""
        assertEquals(listOf("$" + "{"), typed(source, AzoraTokenTypes.INTERPOLATION_START))
        assertEquals(listOf("}"), typed(source, AzoraTokenTypes.INTERPOLATION_END))
    }

    @Test
    fun `a bare hole is opened by the dollar alone`() {
        val source = """"hello ${'$'}name""""
        assertEquals(listOf("$"), typed(source, AzoraTokenTypes.INTERPOLATION_START))
    }

    @Test
    fun `the delimiters are blue`() {
        assertEquals(
            AzoraPalette.STRING_ESCAPE,
            AzoraSyntaxHighlighter.INTERPOLATION.defaultAttributes.foregroundColor,
            "a hole's delimiters share the escape accent",
        )
    }

    // -- and the code inside -------------------------------------------------

    @Test
    fun `the expression inside is tokenized as code`() {
        val source = """"total: ${'$'}{count + 1}""""
        val inside = tokenize(source).map { it.first to it.second }
        assertTrue(
            inside.any { it.first == AzoraTokenTypes.IDENTIFIER && it.second == "count" },
            "the name is an identifier, not string text: $inside",
        )
        assertTrue(
            inside.any { it.first == AzoraTokenTypes.INT_LITERAL && it.second == "1" },
            "the number is a number: $inside",
        )
        assertTrue(
            inside.any { it.first == AzoraTokenTypes.OPERATOR && it.second == "+" },
            "the operator is an operator: $inside",
        )
    }

    @Test
    fun `a bare hole names an identifier`() {
        assertEquals(listOf("name"), typed(""""hello ${'$'}name"""", AzoraTokenTypes.IDENTIFIER))
    }

    @Test
    fun `a call inside a hole reads as a call`() {
        val source = """"at ${'$'}{point.x()}""""
        val kinds = tokenize(source)
        assertTrue(kinds.any { it.first == AzoraTokenTypes.DOT }, "the member dot survives: $kinds")
        assertTrue(kinds.any { it.first == AzoraTokenTypes.L_PAREN }, "the call parens survive: $kinds")
    }

    @Test
    fun `an escaped dollar opens nothing`() {
        val source = """"costs \${'$'}5""""
        assertEquals(emptyList<String>(), typed(source, AzoraTokenTypes.INTERPOLATION_START))
    }

    @Test
    fun `the runs tile the literal exactly`() {
        val source = """fin s = "total: ${'$'}{count + 1} and ${'$'}name""""
        var expected = 0
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)
        while (lexer.tokenType != null) {
            assertEquals(expected, lexer.tokenStart, "token at $expected")
            expected = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(source.length, expected)
    }
}
