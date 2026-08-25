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

import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A doc comment is one lexical thing but three things to read: the prose, the
 * `@param` that opens a clause of it, and the name that clause is about.
 *
 * The runs must tile the comment exactly - every character keeps a token, so
 * nothing can fall through a gap and lose its color. That is what the offset
 * assertions here are for, and it is the property most easily broken by adding
 * a case to the splitter.
 */
class AzoraDocCommentLexTest {

    private fun tokenize(source: String): List<Triple<IElementType, String, Int>> {
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)
        val out = mutableListOf<Triple<IElementType, String, Int>>()
        while (lexer.tokenType != null) {
            out.add(Triple(lexer.tokenType!!, source.substring(lexer.tokenStart, lexer.tokenEnd), lexer.tokenStart))
            lexer.advance()
        }
        return out
    }

    private fun texts(source: String, type: IElementType): List<String> =
        tokenize(source).filter { it.first == type }.map { it.second }

    // -- the three runs -----------------------------------------------------

    @Test
    fun `a tag is its own token`() {
        assertEquals(
            listOf("@param"),
            texts("/** @param capacity The size. */", AzoraTokenTypes.DOC_TAG),
        )
    }

    @Test
    fun `the documented name is its own token`() {
        assertEquals(
            listOf("capacity"),
            texts("/** @param capacity The size. */", AzoraTokenTypes.DOC_TAG_VALUE),
        )
    }

    @Test
    fun `the prose around them stays doc comment`() {
        val prose = texts("/** @param capacity The size. */", AzoraTokenTypes.DOC_COMMENT)
        assertTrue(prose.any { "The size." in it }, "prose kept its own run: $prose")
    }

    @Test
    fun `every tag in a multi-line comment is found`() {
        val source = """
            /**
             * Builds a buffer.
             *
             * @param capacity The size to reserve.
             * @generic T The element type.
             * @return The buffer.
             */
        """.trimIndent()
        assertEquals(listOf("@param", "@generic", "@return"), texts(source, AzoraTokenTypes.DOC_TAG))
        assertEquals(listOf("capacity", "T"), texts(source, AzoraTokenTypes.DOC_TAG_VALUE))
    }

    // -- and only where a name is actually meant ----------------------------

    @Test
    fun `a tag that introduces prose takes no name`() {
        // `@return The buffer.` documents the result, not something called
        // "The" - coloring the first word would suggest a link that is absent.
        assertEquals(emptyList<String>(), texts("/** @return The buffer. */", AzoraTokenTypes.DOC_TAG_VALUE))
    }

    @Test
    fun `an at sign inside a word is not a tag`() {
        assertEquals(
            emptyList<String>(),
            texts("/** write to support@azora.dev for help */", AzoraTokenTypes.DOC_TAG),
        )
    }

    @Test
    fun `a plain block comment is not split`() {
        assertEquals(emptyList<String>(), texts("/* @param capacity */", AzoraTokenTypes.DOC_TAG))
    }

    // -- the runs tile the comment exactly ----------------------------------

    @Test
    fun `the runs cover every character with no gap or overlap`() {
        val source = "/**\n * @param capacity The size.\n * @return it.\n */\nfunc f() {}"
        var expected = 0
        for ((_, text, start) in tokenize(source)) {
            assertEquals(expected, start, "token '$text' starts where the last one ended")
            expected = start + text.length
        }
        assertEquals(source.length, expected, "the stream reaches the end of the buffer")
    }

    @Test
    fun `an unterminated doc comment still tiles`() {
        val source = "/** @param capacity"
        var expected = 0
        for ((_, text, start) in tokenize(source)) {
            assertEquals(expected, start)
            expected = start + text.length
        }
        assertEquals(source.length, expected)
    }
}
