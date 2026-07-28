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
import com.intellij.openapi.util.TextRange

/** A bracket that has no partner, and where it is. */
data class AzoraBracket(val char: Char, val offset: Int)

/** What a single pass over the source found. */
data class AzoraScanResult(
    val diagnostics: List<AzoraDiagnostic>,
    val unclosedBrackets: List<AzoraBracket>,
    val unopenedBrackets: List<AzoraBracket>,
)

/**
 * A single left-to-right pass over Azora source that tracks the lexical states
 * — comments, char literals, raw strings, ordinary strings and their
 * interpolation holes — and reports what it finds wrong.
 *
 * Doing all the checks in one state machine is what keeps them honest. Checking
 * strings and brackets independently is how a `"` inside a char literal, a
 * brace inside a string, or a quote inside a `"""raw"""` block end up reported
 * as errors in code that is perfectly valid.
 */
class AzoraSourceScanner(private val source: String) {

    private val diagnostics = mutableListOf<AzoraDiagnostic>()
    private val openBrackets = ArrayDeque<AzoraBracket>()
    private val unopened = mutableListOf<AzoraBracket>()

    /** Runs the pass. */
    fun scan(): AzoraScanResult {
        var i = 0
        while (i < source.length) {
            i = when {
                startsWith(i, "///") || startsWith(i, "//") -> skipLineComment(i)
                startsWith(i, "/*") -> skipBlockComment(i)
                startsWith(i, "\"\"\"") -> scanRawString(i)
                source[i] == '"' -> scanString(i)
                source[i] == '\'' -> scanChar(i)
                else -> {
                    trackBracket(i)
                    i + 1
                }
            }
        }
        return AzoraScanResult(diagnostics.toList(), openBrackets.toList(), unopened.toList())
    }

    // ── Lexical states ─────────────────────────────────────────────────

    private fun skipLineComment(start: Int): Int {
        var i = start
        while (i < source.length && source[i] != '\n') i++
        return i
    }

    private fun skipBlockComment(start: Int): Int {
        var i = start + 2
        var depth = 1
        while (i < source.length && depth > 0) {
            when {
                startsWith(i, "/*") -> { depth++; i += 2 }
                startsWith(i, "*/") -> { depth--; i += 2 }
                else -> i++
            }
        }
        if (depth > 0) {
            report(start, start + 2, "Block comment is never closed", HighlightSeverity.ERROR)
        }
        return i
    }

    /** `"""…"""` — no escapes, no interpolation, may span lines. */
    private fun scanRawString(start: Int): Int {
        var i = start + 3
        while (i < source.length && !startsWith(i, "\"\"\"")) i++
        if (i >= source.length) {
            report(start, start + 3, "Raw string is never closed", HighlightSeverity.ERROR)
            return source.length
        }
        return i + 3
    }

    /**
     * An ordinary string. Escapes are validated against the set the compiler
     * accepts, and `${…}` holes have their braces balanced, but the string's
     * own braces and quotes never reach the bracket tracker.
     */
    private fun scanString(start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            when {
                source[i] == '"' -> return i + 1
                source[i] == '\n' -> {
                    report(
                        start, i,
                        "Unterminated string literal; Azora strings do not span lines — " +
                            "use a \"\"\"raw string\"\"\" for that",
                        HighlightSeverity.ERROR,
                        listOf(AzoraFix("Insert closing quote", source.substring(start, i) + "\"")),
                    )
                    return i
                }
                source[i] == '\\' -> i = scanEscape(i)
                startsWith(i, "\${") -> i = scanInterpolation(i)
                else -> i++
            }
        }
        report(
            start, source.length,
            "Unterminated string literal",
            HighlightSeverity.ERROR,
            listOf(AzoraFix("Insert closing quote", source.substring(start) + "\"")),
        )
        return source.length
    }

    /** Validates one escape sequence and returns the offset past it. */
    private fun scanEscape(start: Int): Int {
        if (start + 1 >= source.length) return start + 1
        val escaped = source[start + 1]
        if (escaped !in VALID_ESCAPES) {
            report(
                start, start + 2,
                "Unknown escape sequence '\\$escaped'",
                HighlightSeverity.ERROR,
                listOf(AzoraFix("Escape the backslash", "\\\\$escaped")),
            )
        }
        return start + 2
    }

    /** Balances the braces of a `${…}` hole and returns the offset past it. */
    private fun scanInterpolation(start: Int): Int {
        var i = start + 2
        var depth = 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
                '\n' -> {
                    report(start, i, "Unterminated interpolation in string", HighlightSeverity.ERROR)
                    return i
                }
            }
            i++
        }
        if (depth > 0) {
            report(start, source.length, "Unterminated interpolation in string", HighlightSeverity.ERROR)
        }
        return i
    }

    /**
     * A char literal. Skipping it as a unit is what stops `if c == '"' {` from
     * being read as the start of a string — the bug this scanner replaced.
     */
    private fun scanChar(start: Int): Int {
        var i = start + 1
        if (i < source.length && source[i] == '\\') {
            i = scanEscape(i)
            // `\uXXXX` continues past the two-character escape.
            if (i - 1 < source.length && source[i - 1] == 'u') {
                while (i < source.length && source[i].isLetterOrDigit()) i++
            }
        } else if (i < source.length && source[i] != '\'' && source[i] != '\n') {
            i++
        }
        if (i < source.length && source[i] == '\'') return i + 1

        report(
            start, i.coerceAtMost(source.length),
            "Unterminated character literal",
            HighlightSeverity.ERROR,
        )
        return i.coerceAtMost(source.length)
    }

    // ── Brackets ───────────────────────────────────────────────────────

    private fun trackBracket(offset: Int) {
        when (val c = source[offset]) {
            '(', '[', '{' -> openBrackets.addLast(AzoraBracket(c, offset))
            ')', ']', '}' -> {
                val expected = when (c) {
                    ')' -> '('
                    ']' -> '['
                    else -> '{'
                }
                if (openBrackets.lastOrNull()?.char == expected) openBrackets.removeLast()
                else unopened.add(AzoraBracket(c, offset))
            }
        }
    }

    // ── Reporting ──────────────────────────────────────────────────────

    private fun report(
        start: Int,
        end: Int,
        message: String,
        severity: HighlightSeverity,
        fixes: List<AzoraFix> = emptyList(),
    ) {
        diagnostics.add(AzoraDiagnostic(TextRange(start, end.coerceAtLeast(start)), message, severity, fixes))
    }

    private fun startsWith(offset: Int, text: String): Boolean = source.startsWith(text, offset)

    private companion object {
        /** The escapes the compiler's lexer accepts. */
        val VALID_ESCAPES = setOf('b', 'f', 'n', 't', 'r', '0', '\\', '"', '/', '$', '\'', 'u')
    }
}
