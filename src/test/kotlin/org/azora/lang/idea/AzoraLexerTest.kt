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
        val keywords = listOf(
            "func", "pack", "enum", "variant", "error", "impl", "scope",
            "var", "val", "let", "fin", "spec", "annot", "graph", "macro",
        )
        for (kw in keywords) {
            val tokens = tokenizeFiltered(kw)
            assertEquals(1, tokens.size, "Expected 1 token for '$kw'")
            assertEquals(AzoraTokenTypes.DECLARATION_KEYWORD, tokens[0].first, "Expected DECLARATION_KEYWORD for '$kw'")
        }
    }

    @Test
    fun `scope is a current declaration keyword`() {
        val tokens = tokenizeFiltered("scope")
        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.DECLARATION_KEYWORD, tokens[0].first)
    }

    @Test
    fun `current language vocabulary is classified`() {
        val expected = mapOf(
            "val" to AzoraTokenTypes.DECLARATION_KEYWORD,
            "scope" to AzoraTokenTypes.DECLARATION_KEYWORD,
            "variant" to AzoraTokenTypes.DECLARATION_KEYWORD,
            "annot" to AzoraTokenTypes.DECLARATION_KEYWORD,
            "graph" to AzoraTokenTypes.DECLARATION_KEYWORD,
            "purge" to AzoraTokenTypes.MEMORY_KEYWORD,
            "take" to AzoraTokenTypes.MEMORY_KEYWORD,
            "remember" to AzoraTokenTypes.REACTIVE_KEYWORD,
            "react" to AzoraTokenTypes.MODIFIER_KEYWORD,
            "without" to AzoraTokenTypes.CONTROL_KEYWORD,
        )
        for ((word, type) in expected) {
            assertEquals(type, tokenizeFiltered(word).single().first, "wrong token for $word")
        }
    }

    @Test
    fun `the compiler's own primitives are keywords`() {
        // `__int` is a word of the language, not a type written in `.az`: no
        // declaration can describe it, and it reads as the keyword it is.
        for (word in listOf("__int", "__uint", "__float")) {
            assertEquals(AzoraTokenTypes.KEYWORD, tokenizeFiltered(word).single().first, "wrong token for $word")
        }
        val header = tokenizeFiltered("bridge pack Int<N: __uint = 32>(__int)")
        assertEquals(AzoraTokenTypes.KEYWORD, header.first { it.second == "__uint" }.first)
        assertEquals(AzoraTokenTypes.KEYWORD, header.first { it.second == "__int" }.first)
    }

    @Test
    fun `a keyword-spelled name is a name only where the parser admits one`() {
        // `Parser.consumeIdentifierLike` names the keywords that may also be a
        // name, and the editor reads exactly that list. Everything else is a
        // keyword in every position.
        assertEquals(AzoraTokenTypes.IDENTIFIER, tokenizeFiltered("value.union(other)").first { it.second == "union" }.first)
        assertEquals(AzoraTokenTypes.IDENTIFIER, tokenizeFiltered("cursor.take()").first { it.second == "take" }.first)
        assertEquals(AzoraTokenTypes.IDENTIFIER, tokenizeFiltered("module std.error").first { it.second == "error" }.first)
        assertEquals(AzoraTokenTypes.DECLARATION_KEYWORD, tokenizeFiltered("module demo.core").first { it.second == "module" }.first)
        assertEquals(AzoraTokenTypes.DECLARATION_KEYWORD, tokenizeFiltered("unsafe union Result {}").first { it.second == "union" }.first)
        assertEquals(AzoraTokenTypes.IDENTIFIER, tokenizeFiltered("mod std.math").first { it.second == "mod" }.first)

        // Not on the list: no position turns these back into names.
        assertEquals(AzoraTokenTypes.CONTROL_KEYWORD, tokenizeFiltered("value.derives(other)").first { it.second == "derives" }.first)
        assertEquals(AzoraTokenTypes.CONTROL_KEYWORD, tokenizeFiltered("fin where = 1").first { it.second == "where" }.first)
    }

    @Test
    fun `only the top of the file is a restart point`() {
        // The editor relexes from the last boundary whose state was the initial
        // one. Every token here is decided by what precedes it, so saying "zero"
        // anywhere else invited a relex from the middle of a doc comment - and
        // its lines came back coloured as code.
        val source = "/** Doc.\n * @param x A number.\n */\nfunc f(x: Int) {}"
        val lexer = AzoraLexerAdapter()
        lexer.start(source, 0, source.length, 0)

        assertEquals(0, lexer.state, "the first token restarts from the initial state")
        var insideDoc = 0
        while (lexer.tokenType != null) {
            if (lexer.tokenStart > 0) {
                assertNotEquals(0, lexer.state, "restarting at ${lexer.tokenStart} would lose context")
            }
            if (lexer.tokenType == AzoraTokenTypes.DOC_TAG) insideDoc++
            lexer.advance()
        }
        assertEquals(1, insideDoc, "the doc comment's tag is still lexed as a tag")
    }

    @Test
    fun `a doc comment relexed from the top keeps its prose`() {
        // The same text, asked for from an offset inside the comment: the
        // tokens come from a pass that saw the opening, not from the fragment.
        val source = "/** Doc.\n * @param x A number.\n */\nfunc f(x: Int) {}"
        val insideComment = source.indexOf("@param")
        val lexer = AzoraLexerAdapter()
        lexer.start(source, insideComment, source.length, 0)

        val first = lexer.tokenType
        assertTrue(
            first == AzoraTokenTypes.DOC_TAG || first == AzoraTokenTypes.DOC_COMMENT,
            "a tag inside a doc comment is doc, not code: was $first",
        )
    }

    @Test
    fun `a keyword before a splice is still a keyword`() {
        // `bridge oper$op` is the keyword and the name spliced after it, not
        // one long word.
        val tokens = tokenizeFiltered("bridge oper${'$'}op [self&](rhs: Self&): Self")

        assertEquals(AzoraTokenTypes.DECLARATION_KEYWORD, tokens.first { it.second == "oper" }.first)
        assertEquals(AzoraTokenTypes.IDENTIFIER, tokens.first { it.second == "${'$'}op" }.first)
    }

    @Test
    fun `binds is a keyword on a decorator header`() {
        // The header is `annot @Name`, sigil and all, and `binds` belongs to it.
        val source = "annot @AzonSerializable for .Pack binds AzonSerializer {"
        assertEquals(
            AzoraTokenTypes.CONTROL_KEYWORD,
            tokenizeFiltered(source).first { it.second == "binds" }.first,
        )
        // `binds` is not one of the keywords the parser admits as a name, so a
        // member written with it is a keyword there too - and a parse error.
        assertEquals(
            AzoraTokenTypes.CONTROL_KEYWORD,
            tokenizeFiltered("value.binds(other)").first { it.second == "binds" }.first,
        )
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
        val keywords = listOf(
            "exposed", "confined", "protected", "inline", "deepinline", "noinline",
            "threadlocal", "react", "bridge", "solo", "factory", "lazy", "derive", "out",
        )
        for (kw in keywords) {
            val tokens = tokenizeFiltered(kw)
            assertEquals(1, tokens.size, "Expected 1 token for '$kw'")
            assertEquals(AzoraTokenTypes.MODIFIER_KEYWORD, tokens[0].first, "Expected MODIFIER_KEYWORD for '$kw'")
        }
    }

    @Test
    fun `memory keywords are classified correctly`() {
        val keywords = listOf("alloc", "purge", "unsafe", "take", "inject", "preserve")
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

        // `use` was dropped from the language; `using` is the reserved word now.
        val dropped = tokenizeFiltered("func use(): String {}").first { it.second == "use" }
        assertEquals(AzoraTokenTypes.IDENTIFIER, dropped.first)

        val reserved = tokenizeFiltered("macro ${'$'}a @using ${'$'}b => a").first { it.second == "using" }
        assertEquals(AzoraTokenTypes.DECLARATION_KEYWORD, reserved.first)

        val setterName = tokenizeFiltered("func set(value: Int) {}").first { it.second == "set" }
        assertEquals(AzoraTokenTypes.IDENTIFIER, setterName.first)
    }

    @Test
    fun `reactive keywords are classified correctly`() {
        val keywords = listOf("remember", "retain", "effect")
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

    @Test
    fun `where is a keyword wherever it is written`() {
        val constrainedPack = tokenizeFiltered("pack Box<T> where T: Value")
        assertEquals(
            AzoraTokenTypes.CONTROL_KEYWORD,
            constrainedPack.first { it.second == "where" }.first,
        )

        val constrainedFunction = tokenizeFiltered(
            """
                func select<T>(
                    value: T
                ): T where T is Value { return value }
            """.trimIndent(),
        )
        assertEquals(
            AzoraTokenTypes.CONTROL_KEYWORD,
            constrainedFunction.first { it.second == "where" }.first,
        )
    }

    @Test
    fun `assoc is a keyword wherever it is written`() {
        assertTrue("assoc" in AzoraLanguageFacts.allCompletionKeywords)
        assertTrue("without" in AzoraLanguageFacts.allCompletionKeywords)

        for (source in listOf(
            "spec Iterator assoc Item {}",
            "spec Matrix assoc [Scalar Rows] {}",
            "impl Iterator for Rows assoc Item = Entity {}",
            "impl Matrix<T> for Grid<T> assoc [Scalar = T Rows = Int] {}",
        )) {
            assertEquals(
                AzoraTokenTypes.CONTROL_KEYWORD,
                tokenizeFiltered(source).single { it.second == "assoc" }.first,
                "`assoc` must be a keyword in: $source",
            )
        }
    }

    @Test
    fun `derives is a keyword on any header, however the list is written`() {
        assertTrue("derives" in AzoraLanguageFacts.allCompletionKeywords)
        for (source in listOf(
            "pack Player<T> derives [Copy, Hash] where T: Copy",
            "pack Point derives (Equal, Hash, Display) { fin x: Int = 0 }",
            "bridge pack Char derives [PartialEqual, Equal, Order, Hash]",
            "pack Vec2(Float, Float) derives [Copy]",
            "enum Compare derives [Hash] { Less }",
            "unsafe union Result derives [Copy] {}",
            "pack Wide\n    derives [Copy]",
        )) {
            assertEquals(
                AzoraTokenTypes.CONTROL_KEYWORD,
                tokenizeFiltered(source).single { it.second == "derives" }.first,
                "`derives` is a keyword in: $source",
            )
        }
    }

    @Test
    fun `where survives an arrow in the signature above it`() {
        // `(T) -> K` - the `>` of an arrow closes nothing. Counted as an angle
        // bracket it left `<T, K>` unbalanced, and `where` lost its colour on
        // every signature carrying a callable parameter.
        val source = "func sortBy<T, K>(arr: Array<T>, key: (T) -> K): Array<T> where K: Order"

        assertEquals(
            AzoraTokenTypes.CONTROL_KEYWORD,
            tokenizeFiltered(source).single { it.second == "where" }.first,
        )
    }

    @Test
    fun `derives follows a literal pack and may open its own line`() {
        // `std/primitive.az` writes every width this way: the declaration says
        // which literal it is written as, and the clause is too long to follow
        // on the same line.
        for (source in listOf(
            "bridge pack Int<N: __uint = 32>(__int) derives [Integer, SignedInteger]",
            "bridge pack Int<N: __uint = 32>(__int)\nderives [Integer, SignedInteger]",
            "@Since(\"0.1\")\nbridge pack Quad(__float)\n    derives [FloatingPoint]",
        )) {
            assertEquals(
                AzoraTokenTypes.CONTROL_KEYWORD,
                tokenizeFiltered(source).single { it.second == "derives" }.first,
                "`derives` must be the keyword in: $source",
            )
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

    @Test
    fun `self and it are identifiers rather than keywords`() {
        val tokens = tokenizeFiltered("self it")
        assertTrue(tokens.all { it.first == AzoraTokenTypes.IDENTIFIER })
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
        val tokens = tokenizeFiltered("3.14 1e-5 2.0f 3.0D")
        assertEquals(4, tokens.size)
        assertTrue(tokens.all { it.first == AzoraTokenTypes.REAL_LITERAL })
    }

    @Test
    fun `type-suffixed integers are tokenized`() {
        val spellings = listOf("42b", "200ub", "42s", "60000us", "42u", "42L", "42uL", "42c", "42uc")
        val tokens = tokenizeFiltered(spellings.joinToString(" "))
        assertEquals(spellings.size, tokens.size)
        assertTrue(tokens.all { it.first == AzoraTokenTypes.INT_LITERAL })
        assertEquals(spellings, tokens.map { it.second })
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
    fun `escape sequences are split out of the string so they can be colored`() {
        val tokens = tokenizeFiltered("\"hello \\\"world\\\"\"")

        // The literal runs stay STRING_LITERAL; each escape becomes its own token.
        assertEquals(2, tokens.count { it.first == AzoraTokenTypes.STRING_ESCAPE })
        assertEquals(listOf("\\\"", "\\\""), tokens.filter { it.first == AzoraTokenTypes.STRING_ESCAPE }.map { it.second })
        // Every character is still covered, with no gaps.
        assertEquals("\"hello \\\"world\\\"\"", tokens.joinToString("") { it.second })
    }

    @Test
    fun `interpolation holes are tokenized as code`() {
        val tokens = tokenizeFiltered("\"total: \${count + 1} for \$name\"")

        assertEquals(2, tokens.count { it.first == AzoraTokenTypes.INTERPOLATION_START })
        assertEquals(1, tokens.count { it.first == AzoraTokenTypes.INTERPOLATION_END })
        // The expression inside `${…}` is lexed as ordinary code.
        assertTrue(tokens.any { it.first == AzoraTokenTypes.IDENTIFIER && it.second == "count" })
        assertTrue(tokens.any { it.first == AzoraTokenTypes.INT_LITERAL && it.second == "1" })
        // `$name` binds the bare identifier after the marker.
        assertTrue(tokens.any { it.first == AzoraTokenTypes.IDENTIFIER && it.second == "name" })
    }

    @Test
    fun `a raw string is one token and does not interpolate`() {
        val source = "\"\"\"no \$escapes \\n here\"\"\""
        val tokens = tokenizeFiltered(source)

        assertEquals(1, tokens.size)
        assertEquals(AzoraTokenTypes.RAW_STRING_LITERAL, tokens[0].first)
        assertEquals(source, tokens[0].second)
    }

    @Test
    fun `the lexer covers every character of a string`() {
        val source = "fin s = \"a \${b} c\\n\"\n"
        val tokens = tokenize(source)
        assertEquals(source, tokens.joinToString("") { it.second })
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
        val tokens = tokenizeFiltered("@Stable @query")
        assertEquals(4, tokens.size)
        assertEquals(listOf("@", "Stable", "@", "query"), tokens.map { it.second })
        assertEquals(AzoraTokenTypes.DECORATOR, tokens[0].first)
        assertEquals(AzoraTokenTypes.IDENTIFIER, tokens[1].first)
        assertEquals(AzoraTokenTypes.DECORATOR, tokens[2].first)
        assertEquals(AzoraTokenTypes.IDENTIFIER, tokens[3].first)
    }

    // ── Type parameters ────────────────────────────────────────────────

    @Test
    fun `type parameters in generic func are reclassified`() {
        val tokens = tokenizeFiltered("func printAll<T>(items: T)")
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
