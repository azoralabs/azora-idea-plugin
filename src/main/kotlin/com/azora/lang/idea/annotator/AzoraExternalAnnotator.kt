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

package com.azora.lang.idea.annotator

import com.azora.lang.idea.symbol.AzoraSymbolService
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * [ExternalAnnotator] that provides lightweight error detection for Azora files.
 *
 * Detects:
 *  - Unmatched braces, brackets, and parentheses
 *  - Unterminated string literals
 *
 * Also invalidates the [AzoraSymbolService] cache so completions and
 * navigation stay up to date as the file is edited.
 */
class AzoraExternalAnnotator : ExternalAnnotator<AzoraAnnotationInfo, AzoraAnnotationResult>() {

    /**
     * Collects the source text and file metadata needed for annotation.
     *
     * Called on the EDT before the background annotation pass.
     *
     * @param file the PSI file being annotated.
     * @param editor the editor displaying the file.
     * @param hasErrors whether the file already has errors from other annotators.
     * @return an [AzoraAnnotationInfo] snapshot of the file's content and path.
     */
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): AzoraAnnotationInfo {
        return AzoraAnnotationInfo(file.text, file.name, file.virtualFile?.path)
    }

    /**
     * Performs the annotation analysis on a background thread.
     *
     * Runs bracket balance and unterminated string checks on the source text.
     * Exceptions from partial/invalid source during editing are silently swallowed.
     *
     * @param info the file information collected by [collectInformation].
     * @return the annotation result containing any detected diagnostics.
     */
    override fun doAnnotate(info: AzoraAnnotationInfo): AzoraAnnotationResult {
        val diagnostics = mutableListOf<AzoraDiagnostic>()

        try {
            checkBracketBalance(info.source, diagnostics)
            checkUnterminatedStrings(info.source, diagnostics)
        } catch (_: Exception) {
            // Swallow exceptions from partial/invalid source during editing
        }

        return AzoraAnnotationResult(diagnostics, info.filePath)
    }

    /**
     * Applies the annotation results to the editor.
     *
     * Invalidates the [AzoraSymbolService] cache for the file so that completions
     * and navigation reflect the latest edits, then creates error annotations
     * for each diagnostic at its source line.
     *
     * @param file the PSI file being annotated.
     * @param result the diagnostics produced by [doAnnotate].
     * @param holder the annotation holder to register annotations with.
     */
    override fun apply(file: PsiFile, result: AzoraAnnotationResult, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return

        // Invalidate the symbol service cache so completions reflect latest edits
        if (result.filePath != null) {
            try {
                val symbolService = AzoraSymbolService.getInstance(file.project)
                symbolService.invalidate(result.filePath)
            } catch (_: Exception) {
                // Service may not be available during indexing
            }
        }

        for (diag in result.diagnostics) {
            val line = (diag.line - 1).coerceIn(0, document.lineCount - 1)
            val startOffset = document.getLineStartOffset(line)
            val endOffset = document.getLineEndOffset(line)
            if (startOffset >= endOffset) continue

            holder.newAnnotation(diag.severity, diag.message)
                .range(TextRange(startOffset, endOffset))
                .create()
        }
    }

    /**
     * Checks for unmatched braces, brackets, and parentheses in [source].
     *
     * Uses a stack-based approach, skipping characters inside string literals,
     * character literals, line comments, and block comments. Reports an error
     * for each closing bracket without a matching opener and for each opener
     * left on the stack at the end.
     *
     * @param source the source text to check.
     * @param diagnostics the list to append error diagnostics to.
     */
    private fun checkBracketBalance(source: String, diagnostics: MutableList<AzoraDiagnostic>) {
        data class BracketInfo(val char: Char, val line: Int)

        val stack = mutableListOf<BracketInfo>()
        var line = 1
        var inString = false
        var inLineComment = false
        var inBlockComment = false
        var blockCommentDepth = 0
        var i = 0

        while (i < source.length) {
            val ch = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'

            when {
                ch == '\n' -> {
                    line++
                    inLineComment = false
                }
                inLineComment -> {}
                inBlockComment -> {
                    if (ch == '/' && next == '*') { blockCommentDepth++; i++ }
                    else if (ch == '*' && next == '/') {
                        blockCommentDepth--
                        if (blockCommentDepth == 0) inBlockComment = false
                        i++
                    }
                }
                inString -> {
                    if (ch == '\\') i++ // skip escape
                    else if (ch == '"') inString = false
                }
                ch == '/' && next == '/' -> inLineComment = true
                ch == '/' && next == '*' -> { inBlockComment = true; blockCommentDepth = 1; i++ }
                ch == '"' -> inString = true
                ch == '\'' -> {
                    // Skip char literal
                    i++
                    if (i < source.length && source[i] == '\\') i++
                    if (i < source.length) i++ // the char
                    // closing quote handled by next iteration
                }
                ch == '(' || ch == '[' || ch == '{' -> stack.add(BracketInfo(ch, line))
                ch == ')' || ch == ']' || ch == '}' -> {
                    val expected = when (ch) {
                        ')' -> '('
                        ']' -> '['
                        '}' -> '{'
                        else -> ch
                    }
                    if (stack.isNotEmpty() && stack.last().char == expected) {
                        stack.removeAt(stack.lastIndex)
                    } else {
                        diagnostics.add(
                            AzoraDiagnostic(
                                line = line,
                                message = "Unmatched '$ch'",
                                severity = HighlightSeverity.ERROR
                            )
                        )
                    }
                }
            }
            i++
        }

        for (unmatched in stack) {
            diagnostics.add(
                AzoraDiagnostic(
                    line = unmatched.line,
                    message = "Unmatched '${unmatched.char}'",
                    severity = HighlightSeverity.ERROR
                )
            )
        }
    }

    /**
     * Checks for unterminated string literals in [source].
     *
     * Scans for opening `"` characters (outside comments) and verifies each
     * string is terminated before a newline. Azora does not support multi-line
     * non-raw strings, so a newline inside a string is treated as unterminated.
     *
     * @param source the source text to check.
     * @param diagnostics the list to append error diagnostics to.
     */
    private fun checkUnterminatedStrings(source: String, diagnostics: MutableList<AzoraDiagnostic>) {
        var line = 1
        var i = 0
        var inLineComment = false
        var inBlockComment = false

        while (i < source.length) {
            val ch = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'

            when {
                ch == '\n' -> { line++; inLineComment = false }
                inLineComment -> {}
                inBlockComment -> {
                    if (ch == '*' && next == '/') { inBlockComment = false; i++ }
                }
                ch == '/' && next == '/' -> inLineComment = true
                ch == '/' && next == '*' -> { inBlockComment = true; i++ }
                // Char literal `'x'` / `'\n'` / `'"'` — skip it so a `"` *inside* a
                // char literal (e.g. `if c == '"' {`) is not mistaken for a string.
                ch == '\'' -> {
                    i++
                    if (i < source.length && source[i] == '\\' && i + 1 < source.length) i += 2
                    else if (i < source.length && source[i] != '\'' && source[i] != '\n') i++
                    // i now points at the closing quote (or past a bad literal); the
                    // outer `i++` advances past it.
                }
                ch == '"' -> {
                    val startLine = line
                    i++
                    var terminated = false
                    while (i < source.length) {
                        if (source[i] == '\\' && i + 1 < source.length) { i += 2; continue }
                        if (source[i] == '"') { terminated = true; break }
                        if (source[i] == '\n') {
                            // Azora doesn't have multi-line strings (non-raw)
                            break
                        }
                        i++
                    }
                    if (!terminated) {
                        diagnostics.add(
                            AzoraDiagnostic(
                                line = startLine,
                                message = "Unterminated string literal",
                                severity = HighlightSeverity.ERROR
                            )
                        )
                    }
                }
            }
            i++
        }
    }
}

/**
 * Input data collected from the file before annotation.
 *
 * @param source the full source text of the file.
 * @param fileName the file name (e.g. `"Main.az"`).
 * @param filePath the absolute file path, or `null` for in-memory files.
 */
data class AzoraAnnotationInfo(val source: String, val fileName: String, val filePath: String?)

/**
 * Output of the annotation pass, containing detected diagnostics.
 *
 * @param diagnostics the list of errors and warnings found in the source.
 * @param filePath the absolute file path, used to invalidate the symbol cache.
 */
data class AzoraAnnotationResult(val diagnostics: List<AzoraDiagnostic>, val filePath: String?)

/**
 * A single diagnostic message with its source location and severity.
 *
 * @param line the 1-based line number where the issue was detected.
 * @param message the human-readable error or warning message.
 * @param severity the [HighlightSeverity] (e.g. ERROR, WARNING).
 */
data class AzoraDiagnostic(val line: Int, val message: String, val severity: HighlightSeverity)
