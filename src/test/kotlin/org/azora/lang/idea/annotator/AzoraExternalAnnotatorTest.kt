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

    private fun annotate(source: String): List<AzoraDiagnostic> {
        val info = AzoraAnnotationInfo(source, "test.az", "/test.az")
        return annotator.doAnnotate(info).diagnostics
    }

    // ── Balanced brackets ──────────────────────────────────────────────

    @Test
    fun `balanced brackets produce no diagnostics`() {
        val diags = annotate("func main() { println(\"hello\") }")
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
}
