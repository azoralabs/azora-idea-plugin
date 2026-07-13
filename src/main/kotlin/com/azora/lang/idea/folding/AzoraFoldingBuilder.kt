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

package com.azora.lang.idea.folding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.*
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * [FoldingBuilderEx] for the Azora language.
 *
 * Provides code folding for:
 * - `{ ... }` brace blocks that span multiple lines, with context-aware
 *   placeholder text (e.g. `{ ...5 lines }`).
 * - Multi-line block comments and doc comments.
 * - Import groups (3+ consecutive `use` declarations).
 * - Region comments (`// region Name` / `// endregion`).
 */
class AzoraFoldingBuilder : FoldingBuilderEx() {

    /**
     * Builds all fold regions for the given [root] element.
     *
     * Scans the document text for brace blocks, block comments, import groups,
     * and region comments, returning fold descriptors for each.
     *
     * @param root the root PSI element of the file.
     * @param document the document backing the file.
     * @param quick `true` if only quick folding (e.g. import groups) is needed.
     * @return an array of [FoldingDescriptor]s for all foldable regions.
     */
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()
        val text = document.text

        // --- 1. Brace blocks ---
        foldBraceBlocks(text, document, root, descriptors)

        // --- 2. Block comments and doc comments ---
        foldBlockComments(text, document, root, descriptors)

        // --- 3. Import groups ---
        foldImportGroups(text, document, root, descriptors)

        // --- 4. Region comments ---
        foldRegions(text, document, root, descriptors)

        return descriptors.toTypedArray()
    }

    /** Returns `"{...}"` as the default placeholder text for folded AST nodes. */
    override fun getPlaceholderText(node: ASTNode): String = "{...}"

    /** Returns `false` so that no regions are collapsed when the file is first opened. */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    // -----------------------------------------------------------------------
    // Brace block folding
    // -----------------------------------------------------------------------

    /**
     * Finds matching `{ }` pairs and creates fold regions for multi-line blocks.
     *
     * Skips braces inside string literals, character literals, line comments,
     * and block comments to avoid false matches. Each fold descriptor gets
     * a context-aware placeholder via [buildPlaceholder].
     *
     * @param text the full document text.
     * @param document the document for line number lookups.
     * @param root the root PSI element used as the fold descriptor's anchor.
     * @param descriptors the list to append fold descriptors to.
     */
    private fun foldBraceBlocks(
        text: String,
        document: Document,
        root: PsiElement,
        descriptors: MutableList<FoldingDescriptor>
    ) {
        val stack = mutableListOf<Int>()
        var i = 0
        // Track whether we're inside a string literal or comment to avoid false matches
        while (i < text.length) {
            val ch = text[i]
            when {
                // Skip string literals
                ch == '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++ // skip escaped char
                        i++
                    }
                    i++ // skip closing quote
                    continue
                }
                // Skip char literals
                ch == '\'' -> {
                    i++
                    while (i < text.length && text[i] != '\'') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++ // skip closing quote
                    continue
                }
                // Skip line comments
                ch == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                // Skip block comments
                ch == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    var depth = 1
                    while (i < text.length && depth > 0) {
                        if (i + 1 < text.length && text[i] == '/' && text[i + 1] == '*') { depth++; i += 2 }
                        else if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '/') { depth--; i += 2 }
                        else i++
                    }
                    continue
                }
                ch == '{' -> {
                    stack.add(i)
                    i++
                }
                ch == '}' -> {
                    if (stack.isNotEmpty()) {
                        val openOffset = stack.removeAt(stack.lastIndex)
                        val closeOffset = i
                        if (document.getLineNumber(openOffset) < document.getLineNumber(closeOffset)) {
                            val range = TextRange(openOffset, closeOffset + 1)
                            val placeholder = buildPlaceholder(text, openOffset, closeOffset)
                            descriptors.add(object : FoldingDescriptor(root.node, range) {
                                override fun getPlaceholderText(): String = placeholder
                            })
                        }
                    }
                    i++
                }
                else -> i++
            }
        }
    }

    /**
     * Builds a descriptive placeholder for a collapsed brace block.
     *
     * Examines the text before [openOffset] to detect the construct keyword
     * (pack, enum, func, view, etc.) and counts non-blank lines inside the
     * block to produce a placeholder like `{ ...5 lines }`.
     *
     * @param text the full document text.
     * @param openOffset the offset of the opening `{`.
     * @param closeOffset the offset of the closing `}`.
     * @return the placeholder string for the collapsed block.
     */
    private fun buildPlaceholder(text: String, openOffset: Int, closeOffset: Int): String {
        // Get the text on the line before the opening brace
        val lineStart = text.lastIndexOf('\n', openOffset - 1).let { if (it < 0) 0 else it + 1 }
        val preText = text.substring(lineStart, openOffset).trim()

        // Count the number of non-empty lines inside the block for context
        val innerText = text.substring(openOffset + 1, closeOffset)
        val innerLines = innerText.lines().count { it.isNotBlank() }

        // Detect the construct keyword and build placeholder
        val keyword = FOLDABLE_KEYWORDS.firstOrNull { preText.startsWith(it) || preText.contains(" $it ") || preText.contains(" $it(") }

        return when {
            keyword != null -> "{ ...$innerLines lines }"
            preText.startsWith("//") -> "{ ... }"  // shouldn't normally happen
            else -> "{ ...$innerLines lines }"
        }
    }

    // -----------------------------------------------------------------------
    // Block comment folding
    // -----------------------------------------------------------------------

    /**
     * Folds multi-line block comments and doc comments.
     *
     * Handles nested block comments correctly. Doc comments get a
     * doc-comment placeholder, regular block comments get a block-comment placeholder.
     *
     * @param text the full document text.
     * @param document the document for line number lookups.
     * @param root the root PSI element used as the fold descriptor's anchor.
     * @param descriptors the list to append fold descriptors to.
     */
    private fun foldBlockComments(
        text: String,
        document: Document,
        root: PsiElement,
        descriptors: MutableList<FoldingDescriptor>
    ) {
        var i = 0
        while (i < text.length - 1) {
            if (text[i] == '/' && text[i + 1] == '*') {
                val startOffset = i
                val isDoc = i + 2 < text.length && text[i + 2] == '*'

                // Find the matching close, handling nesting
                i += 2
                var depth = 1
                while (i < text.length - 1 && depth > 0) {
                    if (text[i] == '/' && text[i + 1] == '*') { depth++; i += 2 }
                    else if (text[i] == '*' && text[i + 1] == '/') { depth--; i += 2 }
                    else i++
                }
                val endOffset = i

                if (document.getLineNumber(startOffset) < document.getLineNumber(endOffset - 1)) {
                    val range = TextRange(startOffset, endOffset)
                    val placeholder = if (isDoc) "/** ... */" else "/* ... */"
                    descriptors.add(object : FoldingDescriptor(root.node, range) {
                        override fun getPlaceholderText(): String = placeholder
                    })
                }
                continue
            }
            i++
        }
    }

    // -----------------------------------------------------------------------
    // Import group folding
    // -----------------------------------------------------------------------

    /**
     * Folds consecutive `use` declarations (3+ lines) into a single region.
     *
     * Blank lines and comments within the import block are tolerated.
     * The placeholder shows the import count, e.g. `"use ... (5 imports)"`.
     *
     * @param text the full document text.
     * @param document the document for line number lookups.
     * @param root the root PSI element used as the fold descriptor's anchor.
     * @param descriptors the list to append fold descriptors to.
     */
    private fun foldImportGroups(
        text: String,
        document: Document,
        root: PsiElement,
        descriptors: MutableList<FoldingDescriptor>
    ) {
        val lines = text.lines()
        var importStart = -1
        var importEnd = -1
        var importCount = 0

        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("use ")) {
                if (importStart < 0) importStart = idx
                importEnd = idx
                importCount++
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("//")) {
                // Non-import, non-blank, non-comment line, flush group if we have one
                if (importCount >= 3) {
                    addImportFold(document, root, importStart, importEnd, importCount, descriptors)
                }
                importStart = -1
                importEnd = -1
                importCount = 0
            }
            // blank lines or comments within the import block are allowed
        }
        // Flush trailing group
        if (importCount >= 3) {
            addImportFold(document, root, importStart, importEnd, importCount, descriptors)
        }
    }

    /**
     * Creates a fold descriptor for an import group spanning [startLine] to [endLine].
     *
     * @param document the document for offset lookups.
     * @param root the root PSI element used as the fold descriptor's anchor.
     * @param startLine the 0-based index of the first import line.
     * @param endLine the 0-based index of the last import line.
     * @param count the number of `use` declarations in the group.
     * @param descriptors the list to append the fold descriptor to.
     */
    private fun addImportFold(
        document: Document,
        root: PsiElement,
        startLine: Int,
        endLine: Int,
        count: Int,
        descriptors: MutableList<FoldingDescriptor>
    ) {
        if (startLine < 0 || endLine < 0 || startLine >= document.lineCount || endLine >= document.lineCount) return
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(endLine)
        if (startOffset >= endOffset) return

        val range = TextRange(startOffset, endOffset)
        val placeholder = "use ... ($count imports)"
        descriptors.add(object : FoldingDescriptor(root.node, range) {
            override fun getPlaceholderText(): String = placeholder
        })
    }

    // -----------------------------------------------------------------------
    // Region comment folding
    // -----------------------------------------------------------------------

    /**
     * Folds `// region Name` ... `// endregion` blocks.
     *
     * Supports nested regions via a stack. The placeholder shows the region
     * name, e.g. `"// region Utilities ..."`.
     *
     * @param text the full document text.
     * @param document the document for offset lookups.
     * @param root the root PSI element used as the fold descriptor's anchor.
     * @param descriptors the list to append fold descriptors to.
     */
    private fun foldRegions(
        text: String,
        document: Document,
        root: PsiElement,
        descriptors: MutableList<FoldingDescriptor>
    ) {
        val lines = text.lines()
        val regionStack = mutableListOf<Pair<Int, String>>() // line index, region name

        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("// region ") || trimmed.startsWith("//region ")) {
                val name = trimmed.removePrefix("// region ").removePrefix("//region ").trim()
                regionStack.add(idx to name)
            } else if (trimmed.startsWith("// endregion") || trimmed.startsWith("//endregion")) {
                if (regionStack.isNotEmpty()) {
                    val (startLine, name) = regionStack.removeAt(regionStack.lastIndex)
                    val endLine = idx
                    if (startLine < document.lineCount && endLine < document.lineCount) {
                        val startOffset = document.getLineStartOffset(startLine)
                        val endOffset = document.getLineEndOffset(endLine)
                        if (startOffset < endOffset && document.getLineNumber(startOffset) < document.getLineNumber(endOffset)) {
                            val range = TextRange(startOffset, endOffset)
                            val placeholder = "// region $name ..."
                            descriptors.add(object : FoldingDescriptor(root.node, range) {
                                override fun getPlaceholderText(): String = placeholder
                            })
                        }
                    }
                }
            }
        }
    }

    companion object {

        /** Keywords whose brace blocks are candidates for folding with context. */
        private val FOLDABLE_KEYWORDS = listOf(
            "view", "pack", "enum", "slot", "fail", "zone", "impl",
            "solo", "wrap", "bridge", "func", "hook", "task", "flow",
            "test", "when", "for", "while", "loop", "if", "else",
            "spec", "deco", "unsafe", "defer",
        )
    }
}
