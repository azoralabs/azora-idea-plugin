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
import org.azora.lang.idea.symbol.AzoraMacros
import com.intellij.openapi.editor.colors.TextAttributesKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * An `import` clause is two things: a path saying where to look, and a
 * selection saying what to take.
 *
 * Only the path is a module chain, so only the path wears the module italic.
 * What is selected is a declaration and reads as one - `Display` is a type on
 * the import line exactly as it is at a use site. The brackets and the `*` are
 * punctuation and were never identifiers to begin with.
 */
class AzoraImportHighlightTest {

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
                ),
            )
            lexer.advance()
        }
        return tokens
    }

    private fun keyFor(
        source: String,
        word: String,
        symbols: AzoraSemanticSymbols = AzoraSemanticSymbols.EMPTY,
        occurrence: Int = 0,
    ): TextAttributesKey? {
        val tokens = tokensOf(source)
        val classified = AzoraSemanticModel.classify(tokens, AzoraMacros.EMPTY, symbols)
        val token = tokens.filter { it.text == word }.getOrNull(occurrence) ?: return null
        return classified[token.start]
    }

    private val path = AzoraSyntaxHighlighter.MODULE_PATH

    // -- the path ------------------------------------------------------------

    @Test
    fun `every segment of a plain path is path`() {
        assertEquals(path, keyFor("import std.math", "std"))
        assertEquals(path, keyFor("import std.math", "math"))
    }

    @Test
    fun `a starred import is all path`() {
        assertEquals(path, keyFor("import std.io::*", "std"))
        assertEquals(path, keyFor("import std.io::*", "io"))
    }

    @Test
    fun `the base of a group is path`() {
        val source = "import std.container.[list, map]"
        assertEquals(path, keyFor(source, "std"))
        assertEquals(path, keyFor(source, "container"))
    }

    @Test
    fun `a group written with braces reads the same`() {
        assertEquals(path, keyFor("import std.container.{list}", "container"))
    }

    // -- and the selection ---------------------------------------------------

    @Test
    fun `a selected type is not path`() {
        assertNotEquals(path, keyFor("import std.format::[Display]", "Display"))
    }

    @Test
    fun `a selected type in a plain import is not path`() {
        assertNotEquals(path, keyFor("import std.format::Display", "Display"))
    }

    @Test
    fun `an imported symbol reads as whatever it is`() {
        val symbols = AzoraSemanticSymbols(
            types = setOf("Queue"),
            functions = setOf("reflect"),
            decorators = setOf("Serializable"),
            specTypes = setOf("Display"),
        )
        val source = """
            import std.[
                reflection::reflect
                serializer::Serializable
            ]
            import std.format::Display
            import std.container::Queue
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.FUNCTION_CALL, keyFor(source, "reflect", symbols))
        assertEquals(AzoraSyntaxHighlighter.DECORATOR, keyFor(source, "Serializable", symbols))
        assertEquals(AzoraSyntaxHighlighter.SPEC_TYPE, keyFor(source, "Display", symbols))
        assertEquals(AzoraSyntaxHighlighter.TYPE_NAME, keyFor(source, "Queue", symbols))
        // The path is still a path.
        assertEquals(path, keyFor(source, "reflection", symbols))
        assertEquals(path, keyFor(source, "serializer", symbols))
    }

    @Test
    fun `a wildcard is not multiplication`() {
        assertEquals(AzoraSyntaxHighlighter.WILDCARD, keyFor("import std.io::*", "*"))
        assertEquals(AzoraSyntaxHighlighter.WILDCARD, keyFor("import std.io.*", "*"))
        assertEquals(
            AzoraSyntaxHighlighter.WILDCARD,
            keyFor("derive SerialIgnore for WildcardDecoratorFixture::*", "*"),
        )
        // An ordinary `*` still multiplies, and a pointer type still points.
        assertNotEquals(AzoraSyntaxHighlighter.WILDCARD, keyFor("fin n = width * height", "*"))
        assertNotEquals(AzoraSyntaxHighlighter.WILDCARD, keyFor("var data: T* = alloc T() * 8", "*"))
    }

    @Test
    fun `a module in a dotted group is path`() {
        // A `.` opened the group, so its members are modules under the path.
        assertEquals(path, keyFor("import std.container.[list, map]", "map"))
    }

    @Test
    fun `a lowercase symbol selected with colons is not path`() {
        // Nothing about `abs` looks different from a module; the `::` is what
        // says it is a name inside one, and that is now what decides.
        assertNotEquals(path, keyFor("import std.math::abs", "abs"))
    }

    @Test
    fun `a member selecting inside its own module keeps both readings`() {
        val source = "import std.[math::abs]"
        assertEquals(path, keyFor(source, "math"))
        assertNotEquals(path, keyFor(source, "abs"))
    }

    @Test
    fun `a module path leading to a type is still path`() {
        val source = "import std.format::Display"
        assertEquals(path, keyFor(source, "std"))
        assertEquals(path, keyFor(source, "format"))
    }

    // -- and nothing past the clause -----------------------------------------

    @Test
    fun `the clause ends at the line`() {
        assertNotEquals(path, keyFor("import std.math\nfunc main() {}", "main"))
    }

    @Test
    fun `a group may span lines without swallowing the file`() {
        val source = "import std.container.[\n    list\n    map\n]\nfunc main() {}"
        assertEquals(path, keyFor(source, "list"))
        assertEquals(path, keyFor(source, "map"))
        assertNotEquals(path, keyFor(source, "main"))
    }

    @Test
    fun `a name after the clause is untouched`() {
        val source = "import std.io.*\npack Point {\n    var x: Int = 0\n}"
        assertNotEquals(path, keyFor(source, "Point"))
        assertNotEquals(path, keyFor(source, "x"))
    }
}
