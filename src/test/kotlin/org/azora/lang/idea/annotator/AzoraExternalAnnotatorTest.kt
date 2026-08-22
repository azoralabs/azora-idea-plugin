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

package org.azora.lang.idea.annotator

import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AzoraExternalAnnotator] diagnostics.
 *
 * Tests the bracket balance and unterminated string detection logic
 * by calling [AzoraExternalAnnotator.doAnnotate] directly.
 */
class AzoraExternalAnnotatorTest {

    private val annotator = AzoraExternalAnnotator()

    /**
     * The diagnostics a snippet raises, minus the unused-declaration ones.
     *
     * A snippet in a test has no callers by construction, so every declaration
     * in one is unused. Those findings have their own tests below; keeping them
     * out here lets each other test assert on the thing it is about.
     */
    private fun annotate(source: String): List<AzoraDiagnostic> =
        annotateAll(source).filterNot { it.message.endsWith(NEVER_USED) }

    /** Every diagnostic, including the unused-declaration ones. */
    private fun annotateAll(source: String): List<AzoraDiagnostic> {
        val info = AzoraAnnotationInfo(source, "test.az", "/test.az")
        return annotator.doAnnotate(info).diagnostics
    }

    private companion object {
        const val NEVER_USED = "is never used"
    }

    // ── Type computations are named like the type they produce ─────────

    private fun messages(source: String): List<String> = annotate(source).map { it.message }

    @Test
    fun `a func returning Type must be UpperCamelCase`() {
        val diags = annotate("deepinline func nullable<T>(b: Bool): Type { return T }")
        assertTrue(
            diags.any { "Type computation name 'nullable'" in it.message && "UpperCamelCase" in it.message },
            "Expected a naming diagnostic, got: $diags",
        )
        assertTrue(diags.any { it.fixes.any { fix -> fix.replacement == "Nullable" } }, "Expected a rename fix: $diags")
    }

    @Test
    fun `an UpperCamelCase type computation is accepted`() {
        assertTrue(
            messages("deepinline func Nullable<T>(b: Bool): Type { return T }").none { "Type computation" in it },
        )
    }

    @Test
    fun `a private type computation keeps its leading underscore`() {
        assertTrue(
            messages("deepinline func _Nullable<T>(b: Bool): Type { return T }").none { "Type computation" in it },
        )
    }

    @Test
    fun `a lowercase private type computation is still reported`() {
        val diags = annotate("deepinline func _nullable<T>(b: Bool): Type { return T }")
        assertTrue(diags.any { it.fixes.any { fix -> fix.replacement == "_Nullable" } }, "Expected '_Nullable': $diags")
    }

    @Test
    fun `a prop returning Type must be UpperCamelCase`() {
        val diags = annotate("deepinline prop widest<A, B>: Type { return A }")
        assertTrue(diags.any { "Type computation name 'widest'" in it.message }, "got: $diags")
    }

    @Test
    fun `a type computation is not also reported as a function`() {
        // It is named like a type on purpose, so the lowerCamelCase rule for
        // ordinary functions must not fire on the same declaration.
        val diags = annotate("deepinline func Nullable<T>(b: Bool): Type { return T }")
        assertTrue(diags.none { "Function name" in it.message }, "got: $diags")
    }

    @Test
    fun `an ordinary function returning a type named Type-something is unaffected`() {
        // `Type` must be the whole return type; `TypeName` is an ordinary type.
        assertTrue(messages("func describe(): TypeName { return x }").none { "Type computation" in it })
    }

    @Test
    fun `an ordinary function is still lowerCamelCase`() {
        val diags = annotate("func Describe(): Int { return 1 }")
        assertTrue(diags.any { "Function name" in it.message && "lowerCamelCase" in it.message }, "got: $diags")
    }

    // ── Balanced brackets ──────────────────────────────────────────────

    @Test
    fun `balanced brackets produce no diagnostics`() {
        val diags = annotate("func main() {\n    println(\"hello\")\n}")
        assertTrue(diags.isEmpty(), "Expected no diagnostics, got: $diags")
    }

    @Test
    fun `nested balanced brackets produce no diagnostics`() {
        val diags = annotate("func test() { if true { [1, 2, 3] } }")
        assertTrue(diags.isEmpty())
    }

    // ── Unmatched brackets ─────────────────────────────────────────────

    @Test
    fun `unmatched opening brace reports error`() {
        val diags = annotate("func main() {")
        assertTrue(diags.isNotEmpty())
        assertEquals(HighlightSeverity.ERROR, diags[0].severity)
    }

    @Test
    fun `unmatched closing paren reports error`() {
        val diags = annotate("func main )")
        assertTrue(diags.isNotEmpty())
        assertEquals(HighlightSeverity.ERROR, diags[0].severity)
    }

    // ── Brackets in strings and comments are ignored ───────────────────

    @Test
    fun `brackets inside strings are ignored`() {
        val diags = annotate("var s = \"({[\"")
        assertTrue(diags.isEmpty(), "Brackets in strings should be ignored")
    }

    @Test
    fun `brackets inside line comments are ignored`() {
        val diags = annotate("// { ( [")
        assertTrue(diags.isEmpty())
    }

    @Test
    fun `brackets inside block comments are ignored`() {
        val source = "/* { ( [ */ func main() {}"
        val diags = annotate(source)
        assertTrue(diags.isEmpty())
    }

    // ── Unterminated strings ───────────────────────────────────────────

    @Test
    fun `unterminated string reports error`() {
        val diags = annotate("var s = \"hello")
        assertTrue(diags.any { it.message.contains("Unterminated") })
    }

    @Test
    fun `properly terminated string produces no error`() {
        val diags = annotate("var s = \"hello world\"")
        assertTrue(diags.none { it.message.contains("Unterminated") })
    }

    @Test
    fun `string with escapes is not false positive`() {
        val diags = annotate("var s = \"hello \\\"world\\\"\"")
        assertTrue(diags.none { it.message.contains("Unterminated") })
    }

    @Test
    fun `string inside comment does not trigger unterminated`() {
        val diags = annotate("// var s = \"unterminated")
        assertTrue(diags.none { it.message.contains("Unterminated") })
    }

    @Test
    fun `a quote inside a char literal does not start a string`() {
        // The bug this guards: `'"'` used to be read as an opening quote, so
        // the rest of a file like serializer.az came back "Unterminated".
        val source = """
            func escape(c: Char): String {
                if c == '"' {
                    return "\\\""
                }
                if c == '\\' {
                    return "backslash"
                }
                return "plain"
            }
        """.trimIndent()

        val diags = annotate(source)
        assertTrue(diags.isEmpty(), "Expected no diagnostics, got: $diags")
    }

    @Test
    fun `a raw string may span lines and contain quotes`() {
        val source = "fin doc = \"\"\"line one\nline \"two\"\nline three\"\"\"\n"
        val diags = annotate(source)
        assertTrue(diags.isEmpty(), "Expected no diagnostics, got: $diags")
    }

    @Test
    fun `braces inside string interpolation do not unbalance the file`() {
        val diags = annotate("func f() {\n    println(\"n=\${count + 1}\")\n}")
        assertTrue(diags.isEmpty(), "Expected no diagnostics, got: $diags")
    }

    @Test
    fun `a local referenced by string interpolation is used`() {
        val diags = annotate(
            """
            func greet() {
                fin name = "Azora"
                println("Hello, ${'$'}name")
            }
            """.trimIndent(),
        )
        assertTrue(diags.none { it.message.contains("'name' is never used") }, "$diags")
    }

    @Test
    fun `bridge ABI functions keep their external spelling`() {
        val diags = annotate(
            """
            bridge .C {
                func CFRelease(value: std::Long)
            }
            """.trimIndent(),
        )
        assertTrue(diags.none { it.message.contains("CFRelease") }, "$diags")
    }

    @Test
    fun `an unknown escape is reported with a fix`() {
        val diags = annotate("fin s = \"bad \\q escape\"\n")
        val diagnostic = diags.single()

        assertTrue(diagnostic.message.contains("\\q"), "Expected the message to name the escape: ${diagnostic.message}")
        assertEquals(HighlightSeverity.ERROR, diagnostic.severity)
        assertTrue(diagnostic.fixes.isNotEmpty(), "Expected a quick fix for an unknown escape")
    }

    @Test
    fun `an unterminated string is reported on the string, not the whole line`() {
        val source = "fin s = \"hello\n"
        val diagnostic = annotate(source).first { it.message.contains("Unterminated string") }

        // The range starts at the opening quote, not at the start of the line.
        assertEquals(source.indexOf('"'), diagnostic.range.startOffset)
        assertTrue(diagnostic.range.endOffset <= source.indexOf('\n'))
    }

    @Test
    fun `an unclosed brace is reported at the brace itself`() {
        val source = "func main() {\n    println(1)\n"
        val diagnostic = annotate(source).first { it.message.contains("never closed") }
        assertEquals(source.indexOf('{'), diagnostic.range.startOffset)
    }

    @Test
    fun `an import of a module that exists is not flagged`() {
        val info = AzoraAnnotationInfo(
            "import std.io\n",
            "test.az",
            "/test.az",
            knownModules = listOf("std.io", "std.math"),
        )
        val diags = annotator.doAnnotate(info).diagnostics
        assertTrue(diags.isEmpty(), "Expected no diagnostics, got: $diags")
    }

    @Test
    fun `an import typo is reported with the closest module as a fix`() {
        val info = AzoraAnnotationInfo(
            "import std.oi\n",
            "test.az",
            "/test.az",
            knownModules = listOf("std.io", "std.math"),
        )
        val diagnostic = annotator.doAnnotate(info).diagnostics.first()

        assertEquals(HighlightSeverity.WARNING, diagnostic.severity)
        assertTrue(diagnostic.fixes.any { it.replacement == "std.io" }, "Expected a fix to 'std.io'")
    }

    @Test
    fun `imports are not flagged when the module index is empty`() {
        // An un-indexed project must not light up red.
        val diags = annotate("import anything.at.all\n")
        assertTrue(diags.isEmpty(), "Expected no diagnostics, got: $diags")
    }

    @Test
    fun `a wildcard import of a known package is accepted`() {
        val info = AzoraAnnotationInfo(
            "import std.container.*\n",
            "test.az",
            "/test.az",
            knownModules = listOf("std.container.map"),
        )
        assertTrue(annotator.doAnnotate(info).diagnostics.isEmpty())
    }

    // ── Complex cases ──────────────────────────────────────────────────

    @Test
    fun `complete program produces no diagnostics`() {
        val source = """
            module example

            func main() {
                var x = 42
                if x > 0 {
                    println("positive")
                }
            }
        """.trimIndent()
        val diags = annotate(source)
        assertTrue(diags.isEmpty(), "Expected no diagnostics, got: $diags")
    }

    @Test
    fun `unused local is warned while a used local is not`() {
        val source = """
            func calculate() {
                fin used = 1
                fin unusedValue = 2
                println(used)
            }
        """.trimIndent()

        val warnings = annotateAll(source).filter { it.message.endsWith(NEVER_USED) }

        assertEquals(
            listOf("Function 'calculate' is never used", "Binding 'unusedValue' is never used"),
            warnings.map { it.message },
        )
        val binding = warnings.last()
        assertEquals(HighlightSeverity.WARNING, binding.severity)
        assertEquals(
            "Remove unused binding 'unusedValue'",
            binding.fixes.single().title,
            "an unused binding is deleted, not renamed",
        )
    }

    @Test
    fun `a spec member nothing implements or calls is warned about`() {
        // A spec whose members nobody writes anywhere is as dead as an uncalled
        // `func`; the project-wide check is what keeps the implemented ones
        // quiet. A spec *implementation* is excluded - it is required by the
        // spec it satisfies, so it is never dead.
        val source = """
            spec From<T> {
                func from(value: T): Self
                prop origin: String
            }

            pack Report

            impl From<Int> for Report {
                func from(value: Int): Self { return Report() }
                prop origin: String = "int"
            }
        """.trimIndent()

        val warnings = annotateAll(source)
            .filter { it.message.endsWith(NEVER_USED) }
            .map { it.message }

        assertTrue("Spec function 'from' is never used" in warnings, "$warnings")
        assertTrue("Spec property 'origin' is never used" in warnings, "$warnings")
        assertTrue(warnings.none { it.startsWith("Function 'from'") }, "$warnings")
        assertTrue(warnings.none { it.startsWith("Property 'origin'") }, "$warnings")
    }

    @Test
    fun `shadowed local use does not mark the outer symbol used`() {
        val source = """
            func calculate() {
                fin value = 1
                if true {
                    fin value = 2
                    println(value)
                }
            }
        """.trimIndent()

        val warnings = annotateAll(source)
            .filter { it.message.endsWith(NEVER_USED) && it.message.startsWith("Binding") }

        assertEquals(1, warnings.size)
        assertEquals(source.indexOf("value = 1"), warnings.single().range.startOffset)
    }

    @Test
    fun `a run of purges on one value collapses into one statement`() {
        val source = """
            impl HashMap<K, V> {
                dtor[self: Self&] {
                    purge self.keys
                    purge self.values
                    purge self.hashes
                }
            }
        """.trimIndent()

        val diagnostic = annotate(source).single { it.message.startsWith("Releasing what") }
        assertTrue("with self purge [keys, values, hashes]" in diagnostic.message, diagnostic.message)

        val fix = diagnostic.fixes.single()
        val rewritten = source.replaceRange(
            fix.range!!.startOffset,
            fix.range.endOffset,
            fix.replacement!!,
        )
        assertTrue("        with self purge [keys, values, hashes]" in rewritten, rewritten)
        assertTrue("purge self.keys" !in rewritten, rewritten)
    }

    @Test
    fun `a lone purge is left alone`() {
        val source = "func release(self: Buffers!) {\n    purge self.keys\n}"
        assertTrue(annotate(source).none { it.message.startsWith("Releasing what") })
    }

    @Test
    fun `purges of different receivers are different runs`() {
        val source = "func release() {\n    purge a.one\n    purge b.two\n}"
        assertTrue(annotate(source).none { it.message.startsWith("Releasing what") })
    }

    @Test
    fun `assigning a value from itself is written with the shorter operator`() {
        fun rewriteOf(line: String): String {
            val diagnostic = annotate("func main() {\n    $line\n}")
                .single { it.message.contains("is assigned from itself") }
            return diagnostic.fixes.single().replacement.orEmpty()
        }

        assertEquals("size++", rewriteOf("size = size + 1"))
        assertEquals("size--", rewriteOf("size = size - 1"))
        assertEquals("size += 2", rewriteOf("size = size + 2"))
        assertEquals("total *= factor", rewriteOf("total = total * factor"))
        assertEquals("self.size++", rewriteOf("self.size = self.size + 1"))
    }

    @Test
    fun `a rewrite keeps the literals and the indentation of the line it replaces`() {
        val source = "func main() {\n    while true {\n        text = text + \"0\"\n    }\n}"
        val diagnostic = annotate(source).single { it.message.contains("is assigned from itself") }
        val fix = diagnostic.fixes.single()

        // The rule matches on a copy with the literals masked out, so a fix
        // built from that copy hands back `text +=` with nothing after it.
        assertEquals("text += \"0\"", fix.replacement)
        // The indent is matched to find the statement, not to overwrite it.
        assertEquals(source.indexOf("text ="), fix.range!!.startOffset)
    }

    @Test
    fun `the shorthand is refused when it would change what binds to what`() {
        // `result = result * 10 + x` is `(result * 10) + x`; `result *= 10 + x`
        // multiplies by `10 + x`. Same characters, different statement.
        val unsound = listOf(
            "result = result * 10L + (charToCode(c) - 48) as Long",
            "total = total - count + 1",
        )
        for (line in unsound) {
            assertTrue(
                annotate("func main() {\n    $line\n}").none { it.message.contains("is assigned from itself") },
                line,
            )
        }
        // A single term is still offered, brackets and all.
        assertTrue(
            annotate("func main() {\n    total = total + (a + b)\n}")
                .any { it.message.contains("is assigned from itself") },
        )
    }

    @Test
    fun `a when arm loses its braces instead of gaining lines`() {
        val source = "func main() {\n    when value {\n        else -> { return .UnexpectedType }\n    }\n}"
        val diagnostic = annotate(source).single { it.message.contains("does not need braces") }
        val fix = diagnostic.fixes.single()

        assertEquals("        else -> return .UnexpectedType", fix.replacement)
    }

    @Test
    fun `an assignment from another value is not this rule`() {
        val untouched = listOf(
            "size = other + 1",
            "size = 1 + size",
            "size == size + 1",
        )
        for (line in untouched) {
            assertTrue(
                annotate("func main() {\n    $line\n}").none { it.message.contains("is assigned from itself") },
                line,
            )
        }
    }

    @Test
    fun `a constructor that only restates the pack defaults is redundant`() {
        val source = """
            pack Queue<T> {
                var data: T* = alloc .() * 8
                var size: Int = 0
                var capacity: Int = 8
            }

            impl Queue<T> {
                /** Constructs an empty `Queue<T>` with default capacity. */
                @Experimental(since: "0.1")
                ctor[self: Self!]() {
                    self.data = alloc .() * 8
                    self.size = 0
                    self.capacity = 8
                }

                func clear[self: Self!]() {
                    self.size = 0
                }
            }
        """.trimIndent()

        val diagnostic = annotate(source).single { it.message.startsWith("This constructor") }
        val fix = diagnostic.fixes.single()
        val rewritten = source.replaceRange(fix.range!!.startOffset, fix.range.endOffset, fix.replacement!!)

        // The doc comment and the decorator belong to the ctor and go with it.
        assertTrue("ctor" !in rewritten, rewritten)
        assertTrue("@Experimental" !in rewritten, rewritten)
        assertTrue("Constructs an empty" !in rewritten, rewritten)
        // Everything else is untouched.
        assertTrue("func clear[self: Self!]()" in rewritten, rewritten)
        assertTrue("var capacity: Int = 8" in rewritten, rewritten)
    }

    @Test
    fun `a constructor that does anything else is kept`() {
        val doesMore = """
            pack Queue<T> {
                var size: Int = 0
            }

            impl Queue<T> {
                ctor[self: Self!]() {
                    self.size = 0
                    register(self)
                }
            }
        """.trimIndent()
        assertTrue(annotate(doesMore).none { it.message.startsWith("This constructor") }, "it calls something")

        val differentValue = """
            pack Queue<T> {
                var size: Int = 0
            }

            impl Queue<T> {
                ctor[self: Self!]() {
                    self.size = 16
                }
            }
        """.trimIndent()
        assertTrue(
            annotate(differentValue).none { it.message.startsWith("This constructor") },
            "16 is not the declared default",
        )

        val takesArguments = """
            pack Queue<T> {
                var size: Int = 0
            }

            impl Queue<T> {
                ctor[self: Self!](size: Int) {
                    self.size = 0
                }
            }
        """.trimIndent()
        assertTrue(
            annotate(takesArguments).none { it.message.startsWith("This constructor") },
            "a ctor with parameters is not this rule",
        )
    }

    @Test
    fun `a one-line property loses its braces but keeps its type`() {
        fun rewriteOf(line: String): String {
            val source = "impl Compare {\n$line\n}"
            val diagnostic = annotate(source).single { it.message.startsWith("This property") }
            return diagnostic.fixes.single().replacement.orEmpty().trim()
        }

        assertEquals(
            "prop isLess[self: Self&]: Bool = self == .Less",
            rewriteOf("    prop isLess[self: Self&]: Bool { return self == Compare.Less }"),
        )
        assertEquals(
            "exposed prop size[self: Self&]: Int = self._size",
            rewriteOf("    exposed prop size[self: Self&]: Int { return self._size }"),
        )
    }

    @Test
    fun `the rewrite keeps the property indented where it was`() {
        val source = "impl Compare {\n    prop isLess[self: Self&]: Bool { return self == Compare.Less }\n}"
        val fix = annotate(source).single { it.message.startsWith("This property") }.fixes.single()
        val rewritten = source.replaceRange(fix.range!!.startOffset, fix.range.endOffset, fix.replacement!!)

        assertEquals(
            "impl Compare {\n    prop isLess[self: Self&]: Bool = self == .Less\n}",
            rewritten,
        )
    }

    @Test
    fun `a property spanning lines keeps its braces`() {
        val source = """
            impl Compare {
                prop describe[self: Self&]: String {
                    if self == .Less { return "less" }
                    return "other"
                }
            }
        """.trimIndent()
        assertTrue(annotate(source).none { it.message.startsWith("This property") }, "multi-line props are left alone")
    }

    @Test
    fun `a property already in the expression form is left alone`() {
        val source = "impl Compare {\n    prop isLess[self: Self&]: Bool = self == .Less\n}"
        assertTrue(annotate(source).none { it.message.startsWith("This property") })
    }

    @Test
    fun `a case of the enclosing type is named without its qualifier`() {
        val source = "impl Compare {\n    prop isLess[self: Self&]: Bool = self == Compare.Less\n}"
        val diagnostic = annotate(source).single { it.message.contains("is inside 'impl Compare'") }

        assertEquals("Compare.Less", source.substring(diagnostic.range.startOffset, diagnostic.range.endOffset))
        assertEquals(".Less", diagnostic.fixes.single().replacement)
    }

    @Test
    fun `a case of another type keeps its qualifier`() {
        val source = "impl Compare {\n    prop isLess[self: Self&]: Bool = other == Colour.Red\n}"
        assertTrue(annotate(source).none { it.message.contains("is inside") }, "only the enclosing type is implied")
    }

    @Test
    fun `an impl for a spec belongs to the type it implements on`() {
        val source = "impl Order for Compare {\n    prop isLess[self: Self&]: Bool = self == Compare.Less\n}"
        assertTrue(
            annotate(source).any { it.message.contains("is inside 'impl Compare'") },
            "`impl Spec for Type` bodies belong to Type",
        )
    }

    @Test
    fun `a one-line statement block is opened up`() {
        val source = "func enqueue[self!]() {\n    if size >= capacity { self.growQueue() }\n}"
        val diagnostic = annotate(source).single { it.message.contains("belongs on its own line") }
        val fix = diagnostic.fixes.single()
        val rewritten = source.replaceRange(fix.range!!.startOffset, fix.range.endOffset, fix.replacement!!)

        assertEquals(
            "func enqueue[self!]() {\n    if size >= capacity {\n        self.growQueue()\n    }\n}",
            rewritten,
        )
    }

    @Test
    fun `an else arm is opened up with its if`() {
        val source = "func f() {\n    if a { one() } else { two() }\n}"
        val fix = annotate(source).single { it.message.contains("belongs on its own line") }.fixes.single()
        val rewritten = source.replaceRange(fix.range!!.startOffset, fix.range.endOffset, fix.replacement!!)

        assertEquals(
            "func f() {\n    if a {\n        one()\n    } else {\n        two()\n    }\n}",
            rewritten,
        )
    }

    @Test
    fun `every block-bodied statement head is covered`() {
        for (head in listOf("if a", "for i in items", "while a", "loop", "when v")) {
            val source = "func f() {\n    $head { act() }\n}"
            assertTrue(
                annotate(source).any { it.message.contains("belongs on its own line") },
                head,
            )
        }
    }

    @Test
    fun `an expression form stays on its line`() {
        val expressions = listOf(
            "    return if a { 1 } else { 2 }",
            "    fin x = if a { 1 } else { 2 }",
            "    fin y = when v { .One -> 1 else -> 2 }",
        )
        for (line in expressions) {
            assertTrue(
                annotate("func f() {\n$line\n}").none { it.message.contains("belongs on its own line") },
                line,
            )
        }
    }

    @Test
    fun `a declaration is not a statement block`() {
        // A declaration's body is asked to open up as its own kind of thing
        // (see the one-line member check); what it is never told is that it is
        // an `if`.
        val declarations = listOf(
            "func z() { act() }",
            "impl A { }",
            "prop p[self&]: Int = 1",
        )
        for (line in declarations) {
            assertTrue(
                annotate(line).none { it.message.contains("statement's body") },
                line,
            )
        }
    }

    // ── contracts ──────────────────────────────────────────────────────

    private fun contractFix(source: String): String {
        val diagnostic = annotate(source).single { it.message.contains("this member's contract") }
        val fix = diagnostic.fixes.single()
        return source.replaceRange(fix.range!!.startOffset, fix.range.endOffset, fix.replacement!!)
    }

    @Test
    fun `a leading assert over the inputs becomes a precondition`() {
        val source = """
            pack Queue<T> {
                var size: Int = 0
            }

            impl Queue<T> {
                func peek[self&](): T {
                    assert size > 0 { "Queue is empty" }
                    return data[0]
                }
            }
        """.trimIndent()

        assertTrue(
            contractFix(source).contains(
                "    func peek[self&](): T\n" +
                    "    in {\n" +
                    "        assert size > 0 { \"Queue is empty\" }\n" +
                    "    } scope {\n" +
                    "        return data[0]\n" +
                    "    }"
            ),
            contractFix(source),
        )
    }

    @Test
    fun `an assert over the result becomes a postcondition bound to it`() {
        val source = """
            pack Queue<T> {
                var size: Int = 0
            }

            impl Queue<T> {
                func count[self&](): Int {
                    fin total = size * 2
                    assert total >= 0 { "count is never negative" }
                    return total
                }
            }
        """.trimIndent()
        val fixed = contractFix(source)

        assertTrue("\n    out {" in fixed, "a lone 'out' opens its own line: $fixed")
        assertTrue("assert it >= 0 { \"count is never negative\" }" in fixed, fixed)
        assertTrue("return total" in fixed, "the result is still returned by its own name: $fixed")
    }

    @Test
    fun `a member with both gets both clauses`() {
        val source = """
            pack Queue<T> {
                var size: Int = 0
            }

            impl Queue<T> {
                func take[self!](n: Int): Int {
                    assert n > 0 { "n must be positive" }
                    fin taken = n
                    assert taken > 0 { "took nothing" }
                    return taken
                }
            }
        """.trimIndent()
        val fixed = contractFix(source)

        // The first clause opens a line; the second chains off the brace.
        assertTrue("\n    in {" in fixed, fixed)
        assertTrue("} out {" in fixed, fixed)
        assertTrue("} scope {" in fixed, fixed)
        assertTrue("assert n > 0" in fixed, fixed)
        assertTrue("assert it > 0" in fixed, fixed)
    }

    @Test
    fun `an assert over a local is not a contract`() {
        val source = """
            impl Queue<T> {
                func work[self&]() {
                    fin scratch = compute()
                    assert scratch > 0 { "bad scratch" }
                    use(scratch)
                }
            }
        """.trimIndent()
        assertTrue(
            annotate(source).none { it.message.contains("this member's contract") },
            "a check on the work stays in the body",
        )
    }

    @Test
    fun `a member that already states a contract is left alone`() {
        val source = """
            impl Queue<T> {
                func peek[self&](): T in {
                    assert size > 0 { "Queue is empty" }
                } scope {
                    return data[0]
                }
            }
        """.trimIndent()
        assertTrue(annotate(source).none { it.message.contains("this member's contract") })
    }

    @Test
    fun `a member whose body is only asserts keeps them`() {
        // Moving every statement out would leave an empty body.
        val source = """
            impl Queue<T> {
                func check[self&](n: Int) {
                    assert n > 0 { "n must be positive" }
                }
            }
        """.trimIndent()
        assertTrue(annotate(source).none { it.message.contains("this member's contract") })
    }

    // ── receivers ──────────────────────────────────────────────────────

    private fun queue(vararg members: String): String = """
        pack Queue<T> {
            var data: T* = alloc T() * 8
            var size: Int = 0
            var capacity: Int = 8
        }

        impl Queue<T> {
        ${members.joinToString("\n")}
        }
    """.trimIndent()

    @Test
    fun `a receiver inside an impl need not repeat the type`() {
        val source = queue("    func peek[self: Self&](): T { return self.data[0] }")
        val diagnostic = annotate(source).single { it.message.contains("receiver's type") }

        assertEquals("[self: Self&]", source.substring(diagnostic.range.startOffset, diagnostic.range.endOffset))
        assertEquals("[self&]", diagnostic.fixes.single().replacement)
    }

    @Test
    fun `a bodyless member is told its written Self says nothing`() {
        // A `spec` member has no body, which is where the receiver check reads
        // from - and it is exactly where the long form gets written.
        val source = """
            spec SortedMap<K, V> {
                func firstKey[self: Self&](): K?
            }
        """.trimIndent()

        val diagnostic = annotate(source).single { it.message.contains("receiver's type") }

        assertEquals("[self: Self&]", source.substring(diagnostic.range.startOffset, diagnostic.range.endOffset))
        assertEquals("[self&]", diagnostic.fixes.single().replacement)
    }

    @Test
    fun `an exclusive borrow that never writes is narrowed to a shared one`() {
        val source = queue("    func peek[self!](): T { return self.data[0] }")
        val diagnostic = annotate(source).single { it.message.contains("never written through") }

        assertEquals("[self!]", source.substring(diagnostic.range.startOffset, diagnostic.range.endOffset))
        assertEquals("[self&]", diagnostic.fixes.single().replacement)
    }

    @Test
    fun `a grouped target writes through the receiver`() {
        // `self.[a, b] = …` is the members written one per line, so the borrow
        // it needs is the one those lines would need.
        val source = queue("    func reset[self!]() {\n        self.[size, data] = 0\n    }")

        assertTrue(
            annotate(source).none { it.message.contains("never written through") },
            annotate(source).toString(),
        )
    }

    @Test
    fun `calling a member that mutates is mutating`() {
        // `self.grow()` writes whatever `grow` writes, so a shared borrow is
        // not enough for the body that calls it.
        val source = queue(
            "    func grow[self!]() {\n        self.size = self.size + 1\n    }",
            "    func push[self&]() {\n        self.grow()\n    }",
        )
        val diagnostic = annotate(source).single { it.severity == HighlightSeverity.ERROR }

        assertTrue(
            source.substring(diagnostic.range.startOffset, diagnostic.range.endOffset).contains("self.grow"),
            "the error sits on the call",
        )
        assertEquals("[self!]", diagnostic.fixes.single().replacement)
    }

    @Test
    fun `calling a member that only reads leaves the borrow alone`() {
        val source = queue(
            "    func peek[self&](): Int {\n        return self.size\n    }",
            "    func report[self&]() {\n        self.peek()\n    }",
        )

        assertTrue(annotate(source).none { it.severity == HighlightSeverity.ERROR }, annotate(source).toString())
    }

    @Test
    fun `a write through a shared borrow is an error on the writing line`() {
        val source = queue("    func bump[self&]() {\n        self.size = self.size + 1\n    }")
        val diagnostic = annotate(source).single { it.severity == HighlightSeverity.ERROR }

        assertTrue(
            source.substring(diagnostic.range.startOffset, diagnostic.range.endOffset).contains("self.size"),
            "the error sits on the write, not on the signature",
        )
        assertEquals("[self!]", diagnostic.fixes.single().replacement)
    }

    @Test
    fun `a write through an implicit field is the same write`() {
        val source = queue("    func bump[self&]() {\n        size = size + 1\n    }")
        assertTrue(
            annotate(source).any { it.severity == HighlightSeverity.ERROR },
            "a field reached without 'self' is still written through the receiver",
        )
    }

    @Test
    fun `an exclusive borrow that writes is left alone`() {
        val source = queue("    func bump[self!]() {\n        self.size = self.size + 1\n    }")
        assertTrue(annotate(source).none { it.severity == HighlightSeverity.ERROR })
        assertTrue(annotate(source).none { it.message.contains("never written through") })
    }

    @Test
    fun `a receiver the body never reaches for loses its name`() {
        val source = queue("    func answer[self&](): Int {\n        return 42\n    }")
        val diagnostic = annotate(source).single { it.message.contains("needs no name") }

        assertEquals("[&]", diagnostic.fixes.single().replacement)
    }

    @Test
    fun `a receiver outside an impl keeps its written type`() {
        val source = "func peek[value: Queue&](): Int { return value.size }"
        assertTrue(
            annotate(source).none { it.message.contains("the receiver's type is") },
            "there is no enclosing impl to imply the type",
        )
    }

    @Test
    fun `known expected type prefers inferred constructor syntax`() {
        val source = "func render(modifier: Modifier! = Modifier()) {}"
        val diagnostic = annotate(source).single { it.message.contains("Expected type 'Modifier'") }

        assertEquals("Modifier()", source.substring(diagnostic.range.startOffset, diagnostic.range.endOffset))
        assertTrue(diagnostic.fixes.any { it.replacement == ".()" })
    }

    @Test
    fun `explicit bool annotation and inferred bool are both accepted`() {
        val source = """
            var composed: std::Bool = false
            var inferred = false
        """.trimIndent()

        assertTrue(annotate(source).isEmpty())
    }

    @Test
    fun `explicit enum type prefers inferred member`() {
        val source = "fin animal: Animal = Animal.Wolf"
        val diagnostic = annotate(source).single { it.message.contains("Expected enum type") }

        assertEquals("Animal.", source.substring(diagnostic.range.startOffset, diagnostic.range.endOffset))
        assertTrue(diagnostic.fixes.any { it.replacement == "." })
    }

    @Test
    fun `inferred enum binding offers type plus inferred member fix`() {
        val source = """
            enum Animal {
                Wolf
                Fox
            }

            func choose() {
                fin animal = Animal.Wolf
            }
        """.trimIndent()
        val diagnostic = annotate(source).single { it.message.contains("explicit enum type") }
        val fix = diagnostic.fixes.single()

        assertEquals("fin animal: Animal = .Wolf", fix.replacement)
        assertNotNull(fix.range)
    }

    // ── A signature has nowhere to use what it declares ────────────────

    @Test
    fun `a bodyless declaration never reports its parameters`() {
        // A `spec` member names its parameters for the caller to read and the
        // implementer to honour. No code in it could name them.
        val source = """
            spec SortedMap<K, V> {
                func tailMap[self: Self&](fromKey: K&, inclusive: Bool = true): SortedMap<K, V>
            }
        """.trimIndent()

        val warnings = annotateAll(source).filter { it.message.endsWith(NEVER_USED) }.map { it.message }

        assertTrue(warnings.none { it.startsWith("Parameter") || it.startsWith("Receiver") }, "$warnings")
    }

    @Test
    fun `a body that ignores a parameter still reports it`() {
        val source = """
            func tailMap(fromKey: Int, inclusive: Bool) {
                println(inclusive)
            }
        """.trimIndent()

        val warnings = annotateAll(source).filter { it.message.endsWith(NEVER_USED) }.map { it.message }

        assertTrue(warnings.any { it == "Parameter 'fromKey' is never used" }, "$warnings")
    }

    @Test
    fun `a one line property still answers for its receiver`() {
        // No braces, but the `=` is an implementation and can name what the
        // declaration declares.
        val source = """
            pack Bag {
                prop isEmpty[self: Self&]: Bool = 0 == 0
            }
        """.trimIndent()

        val warnings = annotateAll(source).filter { it.message.endsWith(NEVER_USED) }.map { it.message }

        assertTrue(warnings.any { it == "Receiver 'self' is never used" }, "$warnings")
    }

    // ── The compiler's own primitives ──────────────────────────────────

    @Test
    fun `the primitive words are not reserved symbols`() {
        // `__int` and its two siblings are written where a pack cannot describe
        // one; reading them as a symbol nobody was allowed to write has it
        // backwards.
        val source = "bridge pack Int<N: __uint = 32>(__int)\nbridge pack Half(__float)"

        assertTrue(annotate(source).none { it.message.contains("reserved") }, annotate(source).toString())
    }

    @Test
    fun `a name one character from a primitive is refused`() {
        for (name in listOf("int", "_int", "uint", "_uint", "float", "_float")) {
            val diagnostics = annotate("func f() {\n    fin $name = 1\n}")
            assertTrue(
                diagnostics.any { it.message.contains("too close to the compiler's own") },
                "$name should be refused: $diagnostics",
            )
        }
    }

    // ── Saying one thing one way ───────────────────────────────────────

    @Test
    fun `stepping by one is offered the operator for it`() {
        val source = "func f() {\n    i += 1\n}"
        val diagnostic = annotate(source).single { it.message.contains("Stepping by one") }

        assertEquals("i += 1", source.substring(diagnostic.range.startOffset, diagnostic.range.endOffset))
        assertEquals("i++", diagnostic.fixes.single().replacement)
    }

    @Test
    fun `stepping down by one is offered its own operator`() {
        val source = "func f() {\n    self.count -= 1\n}"
        val diagnostic = annotate(source).single { it.message.contains("Stepping by one") }

        assertEquals("self.count--", diagnostic.fixes.single().replacement)

        val local = annotate("func f() {\n    x -= 1\n}").single { it.message.contains("Stepping by one") }
        assertEquals("x--", local.fixes.single().replacement)
    }

    @Test
    fun `a step of something other than one is left alone`() {
        assertTrue(annotate("func f() {\n    i += 2\n    x += 1.0\n    n += 1L\n}").none {
            it.message.contains("Stepping by one")
        })
    }

    @Test
    fun `a one line function body is asked to open up`() {
        val source = "func containsKey[self&](key: K&): Bool { return self._findIndex(key) >= 0 }"
        val diagnostic = annotate(source).single { it.message.contains("belongs on its own line") }

        assertEquals(
            "func containsKey[self&](key: K&): Bool {\n    return self._findIndex(key) >= 0\n}",
            diagnostic.fixes.single().replacement,
        )
    }

    @Test
    fun `an empty body is left on its line`() {
        assertTrue(annotate("func noop() {}").none { it.message.contains("belongs on its own line") })
    }

    @Test
    fun `a one line prop keeps its own suggestion`() {
        // The `=` form is the better answer for a property, and two suggestions
        // on one line is one too many.
        val source = "pack Bag {\n    prop size[self&]: Int { return self._size }\n}"
        val messages = annotate(source).map { it.message }

        assertTrue(messages.none { it.contains("belongs on its own line") }, "$messages")
    }

    @Test
    fun `a group states one shared type once`() {
        val diagnostic = annotate("func f() {\n    fin [a: Int, b: Int, c: Int] = read()\n}")
            .single { it.message.contains("is a 'Int'") }

        assertEquals("[a, b, c]: Int", diagnostic.fixes.single().replacement)
    }

    @Test
    fun `a group writing one value says it once`() {
        val diagnostic = annotate("func f() {\n    self.[a, b, c] = [0, 0, 0]\n}")
            .single { it.message.contains("Every name takes") }

        assertEquals("0", diagnostic.fixes.single().replacement)
    }

    @Test
    fun `a group of different types or values is left alone`() {
        val messages = annotate(
            "func f() {\n    fin [a: Int, b: Real] = read()\n    self.[c, d] = [1, 2]\n}",
        ).map { it.message }

        assertTrue(messages.none { it.contains("states that once") || it.contains("Every name takes") }, "$messages")
    }

    @Test
    fun `a run of purges on names is offered as one group`() {
        val source = """
            func f() {
                purge oldKeys
                purge oldValues
                purge oldHashes
            }
        """.trimIndent()

        val diagnostic = annotate(source).single { it.message.contains("Releasing what one value owns") }

        assertEquals("purge [oldKeys, oldValues, oldHashes]", diagnostic.fixes.single().replacement?.trim())
    }

    @Test
    fun `a single purge is not a run`() {
        assertTrue(annotate("func f() {\n    purge only\n}").none {
            it.message.contains("Releasing what one value owns")
        })
    }

    // ── @Supress(kind: .Unused) ────────────────────────────────────────

    @Test
    fun `a suppressed declaration is not reported as unused`() {
        val source = """
            @Supress(kind: .Unused)
            func calculate() {
                fin unusedValue = 2
            }
        """.trimIndent()

        val warnings = annotateAll(source).filter { it.message.endsWith(NEVER_USED) }

        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun `a module sweep leaves the locals inside a function alone`() {
        // A module-wide sweep answers for the names the module publishes; a
        // local nobody reads is still the author's own business.
        val source = """
            @Supress(kind: .Unused)
            module app.main

            func calculate() {
                fin unusedValue = 2
            }
        """.trimIndent()

        val warnings = annotateAll(source).filter { it.message.endsWith(NEVER_USED) }.map { it.message }

        assertEquals(listOf("Binding 'unusedValue' is never used"), warnings)
    }

    // ── Decorator declarations ─────────────────────────────────────────

    @Test
    fun `for every target is reported as the noise it is`() {
        val diagnostics = annotate("annot @Marker for .*")

        val redundant = diagnostics.single { it.message.contains("'for .*'") }
        assertEquals(HighlightSeverity.WARNING, redundant.severity)
        assertEquals("Remove 'for .*'", redundant.fixes.single().title)
        assertEquals("", redundant.fixes.single().replacement)
    }

    @Test
    fun `a decorator declared with its sigil is a known decorator`() {
        // The declaration is what makes the use site known; spelled `annot
        // @Name`, both halves have to read it the same way.
        val source = """
            annot @Marker for .Pack
            @Marker pack Point { fin x: Int = 0 }
        """.trimIndent()

        assertTrue(annotate(source).none { it.message.contains("Marker") }, annotate(source).toString())
    }

    @Test
    fun `function macro and annotation names get role correct camel case fixes`() {
        val source = """
            func Bad_function() {}
            macro @Bad_macro { [] => 1 }
            annot @bad_annotation
        """.trimIndent()
        val diagnostics = annotate(source)

        assertTrue(diagnostics.any { it.fixes.any { fix -> fix.replacement == "badFunction" } })
        assertTrue(diagnostics.any { it.fixes.any { fix -> fix.replacement == "badMacro" } })
        assertTrue(diagnostics.any { it.fixes.any { fix -> fix.replacement == "BadAnnotation" } })
    }

    @Test
    fun `compiler reserved and internal underscores are errors with fixes`() {
        val source = """
            fin __generated = 1
            fin bad_name = 2
        """.trimIndent()
        val diagnostics = annotate(source)

        assertTrue(diagnostics.any { it.message.contains("compiler-generated") && it.severity == HighlightSeverity.ERROR })
        assertTrue(diagnostics.any { it.message.contains("Internal underscores") && it.fixes.any { fix -> fix.replacement == "badName" } })
    }

    @Test
    fun `one leading private underscore is retained`() {
        val diagnostics = annotate("func _privateHelper(): Unit {}")
        assertTrue(diagnostics.none { it.message.contains("Function name") || it.message.contains("underscore") })
    }

    @Test
    fun `a width suffix on a literal is an error with both ways out`() {
        val diagnostic = annotate("fin big = 4L").single { "width suffix" in it.message }

        assertEquals(HighlightSeverity.ERROR, diagnostic.severity)
        assertTrue(diagnostic.fixes.any { it.replacement == "4" }, "${diagnostic.fixes}")
        assertTrue(diagnostic.fixes.any { it.replacement == "Long(4)" }, "${diagnostic.fixes}")
    }

    @Test
    fun `a hex literal keeps its digits`() {
        // `b`, `c` and `f` are hex digits, so `0xbeef` is one number.
        assertTrue(annotate("fin colour = 0xbeef").none { "width suffix" in it.message })
        assertTrue(annotate("fin plain = 42").none { "width suffix" in it.message })
    }

    @Test
    fun `a prop takes only a shared borrow`() {
        for (receiver in listOf("[self!]", "[self]", "[self: Self!]", "[self: Self]")) {
            val diagnostic = annotate("impl Cursor {\n    prop next$receiver: Int = self.index\n}")
                .single { "only observes" in it.message }

            assertEquals(HighlightSeverity.ERROR, diagnostic.severity)
            assertTrue(diagnostic.fixes.any { it.replacement == "[self&]" }, "$receiver: ${diagnostic.fixes}")
        }
    }

    @Test
    fun `a shared borrow and a func are both left alone`() {
        val source = """
            impl Cursor {
                prop peek[self&]: Int = self.index
                prop scaled[self: Self&]: Int = self.index
                func advance[self!]() { self.index = self.index + 1 }
                func into[self](): Int { return self.index }
            }
        """.trimIndent()

        assertTrue(annotate(source).none { "only observes" in it.message }, "${annotate(source)}")
    }

    @Test
    fun `a for used as a value is not read as a one-line loop`() {
        // The `else` closes an answer, not a loop body: the line begins with
        // `}` and the value is what the loop is worth, so neither the one-line
        // rule nor anything else has a complaint.
        val source = """
            func any(xs: Array<Int>): Bool {
                return for i in 0..<xs.length {
                    if xs[i] > 0 {
                        true
                    }
                } else { false }
            }
        """.trimIndent()

        assertTrue(annotate(source).isEmpty(), "expected no diagnostics, got: ${annotate(source)}")
    }

    @Test
    fun `integer literal for Double gets decimal fix`() {
        val source = "var x: Double = 5"
        val diagnostic = annotate(source).single()

        assertEquals(HighlightSeverity.ERROR, diagnostic.severity)
        assertTrue(diagnostic.fixes.any { it.replacement == "5." })
    }

    @Test
    fun `every float width takes the same decimal fix`() {
        // No suffix says which float; the declared type does.
        for (type in listOf("Half", "Float", "Quad")) {
            val diagnostic = annotate("var x: $type = 5").single()
            assertTrue(
                diagnostic.fixes.any { it.replacement == "5." },
                "$type should be fixed to '5.', got ${diagnostic.fixes.map { it.replacement }}",
            )
        }
    }

    @Test
    fun `real literal for integer gets explicit cast fix`() {
        val source = "var x: Int = 5.0"
        val diagnostic = annotate(source).single()

        assertEquals(HighlightSeverity.ERROR, diagnostic.severity)
        assertTrue(diagnostic.fixes.any { it.replacement == "5.0 as Int" })
    }
}
