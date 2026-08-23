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
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `every form that binds a name is a form that answers for it`() {
        // What binds without being coloured: a loop's row, a pattern's
        // capture, a lambda's parameter, a scope's name. Each is an answer to
        // "does this name resolve?", and a form missing from here is a red
        // underline under working code.
        val source = listOf(
            "scope fmt {",
            "    func go() {}",
            "}",
            "func f(rows: Array<Int>) {",
            "    for visited in 0..<3 { println(visited) }",
            "    when value {",
            "        SerialValue.Number(digits) -> { println(digits) }",
            "        else -> {}",
            "    }",
            "    fin lambda = { taken: Int -> taken + 1 }",
            "    for [head, tail] in rows { println(head) }",
            "    inline for name in @arr[\"a\"] with index { println(name) }",
            "    try { go() } catch failure { println(failure) }",
            "}",
        ).joinToString("\n")

        val bound = AzoraSemanticModel.boundNames(tokensOf(source))

        for (name in listOf("fmt", "visited", "digits", "taken", "head", "tail", "name", "index", "failure")) {
            assertTrue(name in bound, "'$name' is bound by the form that introduces it; bound = $bound")
        }
    }

    @Test
    fun `a variant payload's field names are bindings`() {
        // `Expr(source: String)` declares a slot called `source`. Read as a
        // reference it is a name nothing declares, which is what made a payload
        // field report itself undeclared.
        val source = listOf(
            "variant enum StringPart {",
            "    /** A literal text chunk of an interpolated string. */",
            "    Literal(text: String)",
            "    /** An embedded expression, as raw source text. */",
            "    Expr(source: String)",
            "}",
        ).joinToString("\n")

        val bound = AzoraSemanticModel.boundNames(tokensOf(source))

        for (name in listOf("text", "source")) {
            assertTrue(name in bound, "'$name' is a payload slot, not a reference; bound = $bound")
        }
    }

    @Test
    fun `a loop variable's declared type is not a binding`() {
        // `for span: Duration in spans` binds `span` and names `Duration`.
        // Counting the type as a binding would make it a name the file declares,
        // which silences every check that asks whether it is declared at all.
        val bound = AzoraSemanticModel.boundNames(
            tokensOf("func f(spans: Array<Duration>) {\n    for span: Duration in spans {}\n}"),
        )

        assertTrue("span" in bound, "the row is bound; bound = $bound")
        assertTrue("Duration" !in bound, "the row's type is named, not bound; bound = $bound")
    }

    @Test
    fun `every spec in a derives list reads as a spec`() {
        val source = "bridge pack Char derives [PartialEqual, Equal, Order, Hash]"
        val symbols = AzoraSemanticSymbols(
            specTypes = setOf("PartialEqual", "Equal", "Order", "Hash"),
        )
        val tokens = tokensOf(source)
        val classified = AzoraSemanticModel.classify(tokens, AzoraMacros.EMPTY, symbols)

        for (spec in listOf("PartialEqual", "Equal", "Order", "Hash")) {
            val token = tokens.first { it.text == spec }
            assertEquals(
                AzoraSyntaxHighlighter.SPEC_TYPE,
                classified[token.start],
                "'$spec' is a spec wherever it is named",
            )
        }
    }

    @Test
    fun `an unknown call still reads as a call, an unknown type does not`() {
        val source = "func main() { MissingType(unknownCall()) }"
        // Applying a name to `(` is a call whatever the name turns out to be;
        // that it does not resolve is the annotator's to report, not something
        // the colour should hide.
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_CALL, keyFor(source, "unknownCall"))
        // A capitalized one builds a type, and an unknown type is a guess.
        assertNull(keyFor(source, "MissingType"))
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
    fun `a property is italic white at its declaration and where it is read`() {
        val source = """
            pack Point {
                var x: Double
            }
            impl Point {
                prop magnitude[self: Self&]: Double = self.x

                func report[self: Self&](): Double {
                    return self.magnitude
                }
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.PROPERTY, keyFor(source, "magnitude", occurrence = 0))
        // A property's name is the ordinary identifier tone, italic and
        // underlined - not a brighter white than the field beside it.
        assertEquals(
            AzoraPalette.FOREGROUND,
            AzoraPalette.PROPERTY,
            "a 'prop' name reads as any other name",
        )
        assertEquals(AzoraSyntaxHighlighter.PROPERTY_CALL, keyFor(source, "magnitude", occurrence = 1))
        // A plain pack field is not a property and keeps its own key.
        assertEquals(AzoraSyntaxHighlighter.FIELD, keyFor(source, "x", occurrence = 1))
    }

    @Test
    fun `receiver identifiers are context parameters rather than keywords`() {
        val source = """
            pack App { fin name: String }
            impl App {
                func greet[self: Self&](): String {
                    return self.name
                }
            }
        """.trimIndent()

        // The receiver is the one thing the call site must supply, so it is
        // marked apart from the parameters the function invents for itself -
        // and it reads the same wherever it is written, because naming it in
        // the body is naming that same supplied value.
        assertEquals(AzoraSyntaxHighlighter.CONTEXT_PARAMETER, keyFor(source, "self", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.CONTEXT_PARAMETER, keyFor(source, "self", occurrence = 1))
    }

    @Test
    fun `a prop declares a receiver exactly as a func does`() {
        val source = """
            pack Cursor { var index: Int }
            impl Cursor {
                prop isAtEnd[self: Self&]: Bool = self.index >= 10
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.CONTEXT_PARAMETER, keyFor(source, "self", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.CONTEXT_PARAMETER, keyFor(source, "self", occurrence = 1))
    }

    @Test
    fun `a contracted callable keeps its receiver through every clause`() {
        // `in { … } scope { … }` is one declaration. Ending it at the first
        // brace ended it at the precondition, and the body that follows - the
        // part that actually names `self` - was read as if it were outside any
        // callable at all.
        val source = """
            impl Queue<T> {
                func dequeue[self: Self!](): T
                in {
                    assert self.size > 0 { "Queue is empty" }
                } scope {
                    self.size--
                    return self.front
                }
            }
        """.trimIndent()

        for (occurrence in 0..3) {
            assertEquals(
                AzoraSyntaxHighlighter.CONTEXT_PARAMETER,
                keyFor(source, "self", occurrence = occurrence),
                "self #$occurrence",
            )
        }
    }

    @Test
    fun `the types the compiler provides are types`() {
        // Nothing in `.az` declares `Array`, so no index can supply it however
        // fresh it is - it read as an unknown word beside the `List` next to it.
        val source = "func strSplit(s: String, delim: String): Array<String> { return [] }"

        assertEquals(AzoraSyntaxHighlighter.TYPE_NAME, keyFor(source, "Array", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.TYPE_NAME, keyFor(source, "String", occurrence = 0))
    }

    @Test
    fun `the receiver shorthand is the same receiver written shorter`() {
        // Inside an `impl` the receiver's type is never in question, so `self`
        // may leave it out. Dropping the type drops nothing about what the name
        // means, so it must not drop the colour either - a reader scanning an
        // `impl` sees one receiver colour whichever spelling a member chose.
        val source = """
            enum Ordering { case Less, Greater }
            impl Ordering {
                prop reversed[self]: Ordering {
                    return when self {
                        .Less -> .Greater
                        else -> .Less
                    }
                }

                func flip[self&](): Ordering = self.reversed

                func consume[self](): Ordering = self
            }
        """.trimIndent()

        for (occurrence in 0..5) {
            assertEquals(
                AzoraSyntaxHighlighter.CONTEXT_PARAMETER,
                keyFor(source, "self", occurrence = occurrence),
                "self #$occurrence",
            )
        }
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
        // Nothing in this file calls `render`, and that changes none of its
        // colours: a colour says what a name is, and "nobody calls it" is a
        // claim about the project that the annotator's warning makes instead.
        assertEquals(AzoraSyntaxHighlighter.SPEC_FUNCTION, keyFor(source, "render", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.OVERRIDE_PROPERTY, keyFor(source, "pretty", occurrence = 1))
        assertEquals(AzoraSyntaxHighlighter.OVERRIDE_FUNCTION, keyFor(source, "render", occurrence = 1))
    }

    @Test
    fun `a declaration nothing calls keeps the colour of what it is`() {
        val source = """
            @Experimental(since: "0.1")
            spec From<T> {
                func from(value: T): Self
            }

            impl Ordering {
                prop isLessOrEqual[self]: Bool = true

                func unheard(): Int { return 1 }
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.SPEC_FUNCTION, keyFor(source, "from", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.PROPERTY, keyFor(source, "isLessOrEqual", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_DECLARATION, keyFor(source, "unheard", occurrence = 0))
    }

    @Test
    fun `import paths and complete scope paths are italic`() {
        val source = """
            import std.container.tuple
            scope ide::editor {
                func make() { std::println("ok") }
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.MODULE_PATH, keyFor(source, "std", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.ZONE_USAGE, keyFor(source, "ide", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.ZONE_USAGE, keyFor(source, "editor", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.ZONE_USAGE, keyFor(source, "std", occurrence = 1))
        // The owning scope is italic and the name it reaches is the call it is.
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_CALL, keyFor(source, "println", occurrence = 0))
    }

    @Test
    fun `generic parameters are their own color and an unused binding keeps its own`() {
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
        // Being unused is reported as a warning, not painted on: a binding
        // nothing reads is still a binding and reads like one.
        assertEquals(AzoraSyntaxHighlighter.IDENTIFIER, keyFor(source, "unused"))
    }

    @Test
    fun `a grouped binding binds every name in the group`() {
        // `fin [a, b] = …` is the lines it stands for, so each name is a
        // binding of the block it was written in - not an unknown word.
        val source = """
            func rehash[self!]() {
                fin [oldKeys, oldValues] = with self { [keys, values] }
                let [newKeys: K*, newValues: V*] = alloc .() * 8
                use(oldKeys, oldValues, newKeys, newValues)
            }
        """.trimIndent()

        for (name in listOf("oldKeys", "oldValues", "newKeys", "newValues")) {
            assertEquals(AzoraSyntaxHighlighter.IDENTIFIER, keyFor(source, name), name)
        }
    }

    @Test
    fun `a grouped target names members, not the parameters beside them`() {
        // `parent` is both a parameter of this function and a field of the
        // pack. Inside `self.[…]` it is the field, and the index beside it is
        // the parameter it is written with.
        val source = """
            impl TreeMap<K, V> {
                func _allocateNode[self!](key: K, value: V, parent: Int): Int {
                    self.[keys[elem], parent[elem]] = [key, parent]
                    return elem
                }
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.FIELD, keyFor(source, "parent", occurrence = 1))
        assertEquals(AzoraSyntaxHighlighter.FIELD, keyFor(source, "keys"))
        // The value beside it really is the parameter.
        assertEquals(AzoraSyntaxHighlighter.PARAMETER, keyFor(source, "parent", occurrence = 2))
        // And the parameter's own declaration is unaffected.
        assertEquals(AzoraSyntaxHighlighter.PARAMETER, keyFor(source, "parent", occurrence = 0))
    }

    @Test
    fun `an enum case is italic where it is declared and where it is named`() {
        val source = """
            enum Compare {
                Less
                Equal
            }

            func pick(): Compare { return Compare.Less }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.ENUM_CASE, keyFor(source, "Less", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.ENUM_CASE, keyFor(source, "Less", occurrence = 1))
        assertEquals(AzoraSyntaxHighlighter.ENUM_CASE, keyFor(source, "Equal", occurrence = 0))
    }

    @Test
    fun `an error case wears the failure red rather than the enum colour`() {
        val source = """
            variant error IndexError {
                OutOfBounds(index: Int, size: Int)
            }

            func at(i: Int): Int ?! IndexError { return .OutOfBounds(i, 0) }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.ERROR_CASE, keyFor(source, "OutOfBounds", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.ERROR_CASE, keyFor(source, "OutOfBounds", occurrence = 1))
        // `error` here heads the declaration; `IndexError` is the name it takes.
        assertEquals(AzoraSyntaxHighlighter.TYPE_DECLARATION, keyFor(source, "IndexError", occurrence = 0))
    }

    @Test
    fun `a keyword spelled in a name position is a name`() {
        // `error` names the last segment of a module path, `take` names a method
        // - neither can be the keyword it is spelled like.
        assertEquals(AzoraSyntaxHighlighter.MODULE_PATH, keyFor("module std.error", "error"))

        val source = """
            func take(): Int { return 1 }
            func main() { take() }
        """.trimIndent()
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_DECLARATION, keyFor(source, "take", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_CALL, keyFor(source, "take", occurrence = 1))
    }

    @Test
    fun `an impl header declares type parameters for its whole body`() {
        val source = """
            impl Clone for Shared<T> {
                func duplicate[self: Self&](): T { return self.value }
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.TYPE_PARAMETER, keyFor(source, "T", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.TYPE_PARAMETER, keyFor(source, "T", occurrence = 1))
    }

    @Test
    fun `a macro hole is neither a name nor a call`() {
        val source = """
            macro @vec {
                [...${'$'}items] => std.container.list::ArrayList(...${'$'}items)
            }
        """.trimIndent()

        assertEquals(AzoraSyntaxHighlighter.MACRO_HOLE, keyFor(source, "${'$'}items", occurrence = 0))
        assertEquals(AzoraSyntaxHighlighter.MACRO_HOLE, keyFor(source, "${'$'}items", occurrence = 1))
    }

    @Test
    fun `a braced hole in a macro is gold, braces and all`() {
        val source = """
            macro @map {
                [...${'$'}{key: value}] => mutableMapOf(...mapEntry(${'$'}key, ${'$'}value))
            }
        """.trimIndent()

        val tokens = tokensOf(source)
        val classified = AzoraSemanticModel.classify(tokens, AzoraMacros.EMPTY)
        fun keyAt(text: String, occurrence: Int = 0): TextAttributesKey? =
            tokens.filter { it.text == text }.getOrNull(occurrence)?.let { classified[it.start] }

        // The whole hole: the names it binds and the braces around them. The
        // braces are not the string world's - nothing here interpolates - and
        // leaving them blue put the one blue thing in a macro body around the
        // one gold thing.
        assertEquals(AzoraSyntaxHighlighter.MACRO_HOLE, keyAt("key"))
        assertEquals(AzoraSyntaxHighlighter.MACRO_HOLE, keyAt("value"))
        assertEquals(setOf(source.indexOf("${'$'}{")), AzoraSemanticModel.macroHoleSigils(tokens))
        for (part in listOf("${'$'}{", "}")) {
            assertEquals(AzoraSyntaxHighlighter.MACRO_HOLE, keyAt(part), part)
        }
        // And the `$name` holes in the expansion.
        assertEquals(AzoraSyntaxHighlighter.MACRO_HOLE, keyAt("${'$'}key"))
        assertEquals(AzoraSyntaxHighlighter.MACRO_HOLE, keyAt("${'$'}value"))
        // What the macro expands *to* is ordinary code.
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_CALL, keyAt("mutableMapOf"))
        assertEquals(AzoraSyntaxHighlighter.FUNCTION_CALL, keyAt("mapEntry"))
    }

    @Test
    fun `a braced splice outside a macro is not a hole`() {
        // `${…}` elsewhere splices a value in and keeps the interpolation blue,
        // which the lexer assigns; the semantic pass leaves it alone.
        val source = "inline prop to${'$'}{T.typeName}: T = into<T>"
        val tokens = tokensOf(source)
        val classified = AzoraSemanticModel.classify(tokens, AzoraMacros.EMPTY)
        val brace = tokens.first { it.text == "${'$'}{" }

        assertNull(classified[brace.start])
    }

    @Test
    fun `every segment of a qualified path is the module it names`() {
        val source = "fin values = std.container.list::ArrayList()"

        for (segment in listOf("std", "container", "list")) {
            assertEquals(AzoraSyntaxHighlighter.ZONE_USAGE, keyFor(source, segment), segment)
        }
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
