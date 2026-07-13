/*
 * Copyright 2026 AzoraTech
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

package com.azora.lang.idea

import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AzoraLexerAdapter].
 *
 * Verifies that the lexer correctly tokenizes all Azora language constructs
 * including keywords, identifiers, literals, comments, operators, and delimiters.
 */
class AzoraLexerTest {

    /**
     * Tokenizes [source] and returns a list of (tokenType, tokenText) pairs.
     */
    private fun tokenize(source: String): List<Pair<IElementType, String>> {
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            tokens.add(lexer.tokenType!! to source.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return tokens
    }

    /** Returns only non-whitespace tokens. */
    private fun tokenizeFiltered(source: String): List<Pair<IElementType, String>> =
        tokenize(source).filter { it.first != AzoraTokenTypes.WHITE_SPACE }

    // ── Keywords ───────────────────────────────────────────────────────

    @Test
    fun `declaration keywords are classified correctly`() {
        val keywords = listOf("func", "pack", "enum", "slot", "impl", "zone", "var", "fin", "spec")
        for (kw in keywords) {
            val tokens = tokenizeFiltered(kw)
            assertEquals(1, tokens.size, "Expected 1 token for '$kw'")
            assertEquals(AzoraTokenTypes.DECLARATION_KEYWORD, tokens[0].first, "Expected DECLARATION_KEYWORD for '$kw'")
        }
    }

    @Test
    fun `scope is no longer a keyword`() {
        val tokens = tokenizeFiltered("scope")
        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.IDENTIFIER, tokens[0].first)
    }

    @Test
    fun `control keywords are classified correctly`() {
        val keywords = listOf("if", "else", "for", "while", "loop", "when", "return", "break", "continue")
        for (kw in keywords) {
            val tokens = tokenizeFiltered(kw)
            assertEquals(1, tokens.size, "Expected 1 token for '$kw'")
            assertEquals(AzoraTokenTypes.CONTROL_KEYWORD, tokens[0].first, "Expected CONTROL_KEYWORD for '$kw'")
        }
    }

    @Test
    fun `modifier keywords are classified correctly`() {
        val keywords = listOf("expose", "confine", "mut", "ref", "inline")
        for (kw in keywords) {
            val tokens = tokenizeFiltered(kw)
            assertEquals(1, tokens.size, "Expected 1 token for '$kw'")
            assertEquals(AzoraTokenTypes.MODIFIER_KEYWORD, tokens[0].first, "Expected MODIFIER_KEYWORD for '$kw'")
        }
    }

    @Test
    fun `memory keywords are classified correctly`() {
        val keywords = listOf("alloc", "drop", "unsafe", "deref")
        for (kw in keywords) {
            val tokens = tokenizeFiltered(kw)
            assertEquals(1, tokens.size, "Expected 1 token for '$kw'")
            assertEquals(AzoraTokenTypes.MEMORY_KEYWORD, tokens[0].first, "Expected MEMORY_KEYWORD for '$kw'")
        }
    }

    @Test
    fun `get and set are ordinary identifiers`() {
        val functionName = tokenizeFiltered("func get(): String {}").first { it.second == "get" }
        assertEquals(AzoraTokenTypes.IDENTIFIER, functionName.first)

        val propertyName = tokenizeFiltered("""spec Into<T>: T { ref self } use as "to${'$'}{T.typeName}"""").first { it.second == "use" }
        assertEquals(AzoraTokenTypes.DECLARATION_KEYWORD, propertyName.first)

        val setterName = tokenizeFiltered("func set(value: Int) {}").first { it.second == "set" }
        assertEquals(AzoraTokenTypes.IDENTIFIER, setterName.first)
    }

    @Test
    fun `reactive keywords are classified correctly`() {
        val keywords = listOf("mem", "rem", "ret", "view", "effect")
        for (kw in keywords) {
            val tokens = tokenizeFiltered(kw)
            assertEquals(1, tokens.size, "Expected 1 token for '$kw'")
            assertEquals(AzoraTokenTypes.REACTIVE_KEYWORD, tokens[0].first, "Expected REACTIVE_KEYWORD for '$kw'")
        }
    }

    @Test
    fun `literal keywords are classified as KEYWORD`() {
        for (kw in listOf("true", "false", "null")) {
            val tokens = tokenizeFiltered(kw)
            assertEquals(AzoraTokenTypes.KEYWORD, tokens[0].first, "Expected KEYWORD for '$kw'")
        }
    }

    // ── Identifiers ────────────────────────────────────────────────────

    @Test
    fun `identifiers are tokenized correctly`() {
        val tokens = tokenizeFiltered("myVar _private camelCase")
        assertEquals(3, tokens.size)
        assertTrue(tokens.all { it.first == AzoraTokenTypes.IDENTIFIER })
        assertEquals("myVar", tokens[0].second)
        assertEquals("_private", tokens[1].second)
        assertEquals("camelCase", tokens[2].second)
    }

    // ── Numeric literals ───────────────────────────────────────────────

    @Test
    fun `integer literals are tokenized`() {
        val tokens = tokenizeFiltered("42 0xFF 0b1010")
        assertEquals(3, tokens.size)
        assertTrue(tokens.all { it.first == AzoraTokenTypes.INT_LITERAL })
    }

    @Test
    fun `real literals are tokenized`() {
        val tokens = tokenizeFiltered("3.14 1e-5 2.0f64")
        assertEquals(3, tokens.size)
        assertTrue(tokens.all { it.first == AzoraTokenTypes.REAL_LITERAL })
    }

    @Test
    fun `type-suffixed integers are tokenized`() {
        val tokens = tokenizeFiltered("42i32 100u64")
        assertEquals(2, tokens.size)
        assertEquals(AzoraTokenTypes.INT_LITERAL, tokens[0].first)
        assertEquals("42i32", tokens[0].second)
        assertEquals(AzoraTokenTypes.INT_LITERAL, tokens[1].first)
        assertEquals("100u64", tokens[1].second)
    }

    // ── String and char literals ───────────────────────────────────────

    @Test
    fun `string literals are tokenized`() {
        val tokens = tokenizeFiltered("\"hello world\"")
        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.STRING_LITERAL, tokens[0].first)
        assertEquals("\"hello world\"", tokens[0].second)
    }

    @Test
    fun `string with escapes is a single token`() {
        val tokens = tokenizeFiltered("\"hello \\\"world\\\"\"")
        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.STRING_LITERAL, tokens[0].first)
    }

    @Test
    fun `char literals are tokenized`() {
        val tokens = tokenizeFiltered("'a' '\\n'")
        assertEquals(2, tokens.size)
        assertTrue(tokens.all { it.first == AzoraTokenTypes.CHAR_LITERAL })
    }

    // ── Comments ───────────────────────────────────────────────────────

    @Test
    fun `line comment is a single token`() {
        val tokens = tokenizeFiltered("// this is a comment")
        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.LINE_COMMENT, tokens[0].first)
    }

    @Test
    fun `block comment is a single token`() {
        val tokens = tokenizeFiltered("/* block */")
        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.BLOCK_COMMENT, tokens[0].first)
    }

    @Test
    fun `doc comment is a single token`() {
        val tokens = tokenizeFiltered("/** doc */")
        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.DOC_COMMENT, tokens[0].first)
    }

    @Test
    fun `nested block comments are handled`() {
        val tokens = tokenizeFiltered("/* outer /* inner */ end */")
        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.BLOCK_COMMENT, tokens[0].first)
    }

    // ── Operators and delimiters ───────────────────────────────────────

    @Test
    fun `arrow operator is tokenized`() {
        val tokens = tokenizeFiltered("->")
        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.ARROW, tokens[0].first)
    }

    @Test
    fun `multi-char operators are tokenized`() {
        for (op in listOf("==", "!=", "<=", ">=", "&&", "||", "..")) {
            val tokens = tokenizeFiltered(op)
            assertEquals(1, tokens.size, "Expected 1 token for '$op'")
            assertEquals(AzoraTokenTypes.OPERATOR, tokens[0].first, "Expected OPERATOR for '$op'")
        }
    }

    @Test
    fun `delimiters are tokenized correctly`() {
        val tokens = tokenizeFiltered("( ) { } [ ] , : . ;")
        assertEquals(10, tokens.size)
        assertEquals(AzoraTokenTypes.L_PAREN, tokens[0].first)
        assertEquals(AzoraTokenTypes.R_PAREN, tokens[1].first)
        assertEquals(AzoraTokenTypes.L_BRACE, tokens[2].first)
        assertEquals(AzoraTokenTypes.R_BRACE, tokens[3].first)
        assertEquals(AzoraTokenTypes.L_BRACKET, tokens[4].first)
        assertEquals(AzoraTokenTypes.R_BRACKET, tokens[5].first)
        assertEquals(AzoraTokenTypes.COMMA, tokens[6].first)
        assertEquals(AzoraTokenTypes.COLON, tokens[7].first)
        assertEquals(AzoraTokenTypes.DOT, tokens[8].first)
        assertEquals(AzoraTokenTypes.SEMICOLON, tokens[9].first)
    }

    // ── Decorators ─────────────────────────────────────────────────────

    @Test
    fun `decorators are tokenized`() {
        val tokens = tokenizeFiltered("@entry @test")
        assertEquals(2, tokens.size)
        assertTrue(tokens.all { it.first == AzoraTokenTypes.DECORATOR })
        assertEquals("@entry", tokens[0].second)
        assertEquals("@test", tokens[1].second)
    }

    // ── Type parameters ────────────────────────────────────────────────

    @Test
    fun `type parameters in generic func are reclassified`() {
        val tokens = tokenizeFiltered("func<T> printAll(items: T)")
        val typeParamTokens = tokens.filter { it.first == AzoraTokenTypes.TYPE_PARAMETER }
        assertTrue(typeParamTokens.isNotEmpty(), "Expected TYPE_PARAMETER tokens")
        assertTrue(typeParamTokens.all { it.second == "T" })
    }

    // ── Gap-free coverage ──────────────────────────────────────────────

    @Test
    fun `token stream covers entire input with no gaps`() {
        val source = "func main() {\n    println(\"hello\")\n}"
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)
        var lastEnd = 0
        while (lexer.tokenType != null) {
            assertEquals(lastEnd, lexer.tokenStart, "Gap detected at offset $lastEnd")
            lastEnd = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(source.length, lastEnd, "Tokens did not cover entire input")
    }

    // ── Edge cases ─────────────────────────────────────────────────────

    @Test
    fun `empty input produces no tokens`() {
        val tokens = tokenize("")
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `whitespace-only input produces whitespace token`() {
        val tokens = tokenize("   ")
        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.WHITE_SPACE, tokens[0].first)
    }

    // ── Full statement ─────────────────────────────────────────────────

    @Test
    fun `func declaration tokenizes correctly`() {
        val tokens = tokenizeFiltered("func main() {}")
        assertEquals(AzoraTokenTypes.DECLARATION_KEYWORD, tokens[0].first) // func
        assertEquals(AzoraTokenTypes.IDENTIFIER, tokens[1].first)          // main
        assertEquals(AzoraTokenTypes.L_PAREN, tokens[2].first)            // (
        assertEquals(AzoraTokenTypes.R_PAREN, tokens[3].first)            // )
        assertEquals(AzoraTokenTypes.L_BRACE, tokens[4].first)            // {
        assertEquals(AzoraTokenTypes.R_BRACE, tokens[5].first)            // }
    }
}
