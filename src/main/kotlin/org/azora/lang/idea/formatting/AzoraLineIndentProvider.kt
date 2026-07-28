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

package org.azora.lang.idea.formatting

import org.azora.lang.idea.AzoraLanguage
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider

/**
 * Computes the indent for a new line in an Azora file.
 *
 * The rule follows the shape of the code rather than a token grammar, which
 * keeps it correct for a language whose blocks are all brace-delimited:
 *
 * * start from the indent of the previous non-blank line;
 * * add one level when that line leaves a bracket open, or ends with `->`,
 *   `=>` or an operator that continues the expression;
 * * remove one level when the new line's first character closes a bracket;
 * * keep the extra level for a `{`-less single-statement body such as
 *   `if x` on its own line.
 *
 * Returning `null` hands the decision back to the platform, which then keeps
 * the previous line's indent — the right fallback for anything unusual.
 */
class AzoraLineIndentProvider : LineIndentProvider {

    override fun isSuitableFor(language: Language?): Boolean = language?.isKindOf(AzoraLanguage) == true

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        if (!isSuitableFor(language)) return null

        val text = editor.document.charsSequence
        val caret = offset.coerceIn(0, text.length)
        val indentSize = indentSize(project)

        val previous = previousNonBlankLine(text, caret) ?: return ""
        val previousText = text.subSequence(previous.first, previous.second).toString()
        val stripped = stripTrailingComment(previousText).trimEnd()
        if (stripped.isEmpty()) return ""

        var indent = leadingWidth(previousText, indentSize)

        if (opensBlock(stripped)) indent += indentSize
        if (closesFirst(text, caret)) indent -= indentSize

        return " ".repeat(indent.coerceAtLeast(0))
    }

    /** True when the line leaves something open that the next line continues. */
    private fun opensBlock(line: String): Boolean {
        if (bracketDelta(line) > 0) return true
        return CONTINUATION_ENDINGS.any { line.endsWith(it) }
    }

    /** True when the text at [caret] begins with a closing bracket. */
    private fun closesFirst(text: CharSequence, caret: Int): Boolean {
        var i = caret
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i < text.length && text[i] in "}])"
    }

    /** How many brackets the line opens, net of the ones it closes. */
    private fun bracketDelta(line: String): Int {
        var delta = 0
        var inString = false
        var inChar = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString -> when {
                    c == '\\' -> i++
                    c == '"' -> inString = false
                }
                inChar -> when {
                    c == '\\' -> i++
                    c == '\'' -> inChar = false
                }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '{' || c == '(' || c == '[' -> delta++
                c == '}' || c == ')' || c == ']' -> delta--
            }
            i++
        }
        return delta
    }

    /** Drops a trailing `//` comment so it cannot affect the bracket count. */
    private fun stripTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length - 1) {
            val c = line[i]
            when {
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '/' && line[i + 1] == '/' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    /** The visual width of a line's leading whitespace, expanding tabs. */
    private fun leadingWidth(line: String, tabWidth: Int): Int {
        var width = 0
        for (c in line) {
            when (c) {
                ' ' -> width++
                '\t' -> width += tabWidth
                else -> return width
            }
        }
        return width
    }

    /** The `[start, end)` offsets of the last non-blank line before [caret]. */
    private fun previousNonBlankLine(text: CharSequence, caret: Int): Pair<Int, Int>? {
        var end = caret
        while (end > 0) {
            // Step back over the line break that precedes this position.
            while (end > 0 && text[end - 1] != '\n') end--
            if (end == 0) return null
            val lineEnd = end - 1
            var lineStart = lineEnd
            while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
            val line = text.subSequence(lineStart, lineEnd)
            if (line.isNotBlank()) return lineStart to lineEnd
            end = lineStart
        }
        return null
    }

    private fun indentSize(project: Project): Int {
        val settings = CodeStyleSettingsManager.getInstance(project).currentSettings
        return settings.getIndentOptions(org.azora.lang.idea.AzoraFileType.INSTANCE)
            .INDENT_SIZE
            .coerceAtLeast(1)
    }

    private companion object {
        /** Line endings that mean the statement carries on to the next line. */
        val CONTINUATION_ENDINGS = listOf(
            "->", "=>", "=", "+", "-", "*", "/", "%", "&&", "||", "??", ",", ":",
        )
    }
}
