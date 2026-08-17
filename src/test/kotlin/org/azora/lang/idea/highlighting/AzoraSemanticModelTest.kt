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
    fun `a keyword-named macro is colored only after its sigil`() {
        val macros = AzoraMacros(prefix = setOf("with"))

        // Statement position: `with (ctx) { … }` is the language's own keyword.
        assertNull(
            keyFor("with (context) {\n    run()\n}", "with", macros),
            "`with` opening a context block must keep its keyword color",
        )

        // Query-clause position: the sigil changes the contextual keyword into
        // a macro token without changing `with` globally.
        assertEquals(
            AzoraSyntaxHighlighter.MACRO,
            keyFor("fin q = @query [Position!] @with Player", "with", macros),
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
    fun `an identifier without an at sigil is never guessed to be a macro`() {
        assertNotEquals(AzoraSyntaxHighlighter.MACRO, keyFor("fin pair = a joinedWith b", "joinedWith"))
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
    fun `a declared prefix macro applied with at is colored`() {
        val macros = AzoraMacros(prefix = setOf("res"))
        assertEquals(
            AzoraSyntaxHighlighter.MACRO,
            keyFor("func system(world: @res World) {\n}", "res", macros),
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

            func compute(): Real {
                return 1.0
            }

            func distance(): Real {
                return compute()
            }

            func main() {
                distance()
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.TYPE_DECLARATION, keyFor(source, "Point"))
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_DECLARATION, keyFor(source, "distance"))
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_CALL, keyFor(source, "compute", occurrence = 1))
    }

    @Test
    fun `unknown calls and capitalized identifiers are not colored as known symbols`() {
        val source = "func main() { MissingType(unknownCall()) }"
        assertNull(keyFor(source, "MissingType"))
        assertNull(keyFor(source, "unknownCall"))
    }

    @Test
    fun `indexed functions and types are colored without hardcoded names`() {
        val source = "func main() { render(Widget()) }"
        val symbols = AzoraSemanticSymbols(
            types = setOf("Widget"),
            functions = setOf("render"),
        )
        val tokens = tokensOf(source)
        val classified = AzoraSemanticModel.classify(tokens, AzoraMacros.EMPTY, symbols)

        fun classifiedKey(word: String): TextAttributesKey? {
            val token = tokens.first { it.text == word }
            return classified[token.start]
        }

        assertEquals(AzoraSyntaxHighlighter.FUNCTION_CALL, classifiedKey("render"))
        assertEquals(AzoraSyntaxHighlighter.TYPE_NAME, classifiedKey("Widget"))
    }

    @Test
    fun `receiver identifiers are parameters rather than keywords`() {
        val source = """
            pack App { fin name: String }
            impl App {
                func greet[self: Self&](): String {
                    return self.name
                }
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.PARAMETER, keyFor(source, "self", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.PARAMETER, keyFor(source, "self", occurrence = 1))
    }

    @Test
    fun `spec and override members use the website semantic styles`() {
        val source = """
            spec PrettyPrint {
                prop pretty: String
                func render(): String
            }

            pack Report

            impl PrettyPrint for Report {
                prop pretty: String = "report"
                func render(): String { return pretty }
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.SPEC_TYPE, keyFor(source, "PrettyPrint", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.SPEC_TYPE, keyFor(source, "PrettyPrint", occurrence = 1))
        assertEquals(AzoraSyntaxHighlighter.SPEC_PROPERTY, keyFor(source, "pretty", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.UNUSED_SPEC_MEMBER, keyFor(source, "render", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.OVERRIDE_PROPERTY, keyFor(source, "pretty", occurrence = 1))
        assertEquals(AzoraSyntaxHighlighter.UNUSED_OVERRIDE_MEMBER, keyFor(source, "render", occurrence = 1))
    }

    @Test
    fun `import paths and complete realm paths are italic`() {
        val source = """
            import std.container.tuple
            realm ide::editor {
                func make() { std::println("ok") }
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.MODULE_PATH, keyFor(source, "std", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.ZONE_USAGE, keyFor(source, "ide", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.ZONE_USAGE, keyFor(source, "editor", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.ZONE_USAGE, keyFor(source, "std", occurrence = 1))
        assertNull(keyFor(source, "println", occurrence = 0))
    }

    @Test
    fun `generic parameters are blue semantic generics and unused declarations are dimmed`() {
        val source = """
            func identity<T>(value: T): T {
                fin unused = 1
                return value
            }

            func main() {
                identity(1)
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.TYPE_PARAMETER, keyFor(source, "T", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.TYPE_PARAMETER, keyFor(source, "T", occurrence = 1))
        assertEquals(AzoraSyntaxHighlighter.PARAMETER, keyFor(source, "value", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.UNUSED, keyFor(source, "unused"))
    }

    @Test
    fun `a member access is never colored as a macro`() {
        assertNotEquals(AzoraSyntaxHighlighter.MACRO, keyFor("fin v = point.with", "with", AzoraMacros(infix = setOf("with"))))
    }

    @Test
    fun `a local symbol wins over a same-named callable for coloring`() {
        val source = """
            func render(): Int { return 1 }
            func main() {
                fin render = 2
                render()
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.IDENTIFIER, keyFor(source, "render", occurrence = 2))
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
