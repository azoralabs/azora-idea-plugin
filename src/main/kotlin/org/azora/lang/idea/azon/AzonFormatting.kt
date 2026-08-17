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

package org.azora.lang.idea.azon

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.SpacingBuilder
import com.intellij.formatting.Wrap
import com.intellij.formatting.WrapType
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.psi.formatter.common.AbstractBlock

/** Code-style defaults and the Settings preview for AZON manifests and data. */
class AzonCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage(): Language = AzonLanguage

    override fun getCodeSample(settingsType: SettingsType): String = """
        package: {
            name: "azora-app"
            version: "0.1.0"
            dependencies: {
                engine: { path: "../azora-engine" }
            }
        }

        targets: [
            "interpret"
            "native"
        ]
    """.trimIndent()

    override fun customizeDefaults(
        commonSettings: CommonCodeStyleSettings,
        indentOptions: CommonCodeStyleSettings.IndentOptions,
    ) {
        indentOptions.INDENT_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
        indentOptions.USE_TAB_CHARACTER = false
        commonSettings.KEEP_SIMPLE_BLOCKS_IN_ONE_LINE = true
    }
}

/** Supplies structural indentation when Enter is pressed in a `.azon` file. */
class AzonLineIndentProvider : LineIndentProvider {

    override fun isSuitableFor(language: Language?): Boolean = language?.isKindOf(AzonLanguage) == true

    override fun getLineIndent(
        project: Project,
        editor: Editor,
        language: Language?,
        offset: Int,
    ): String? {
        if (!isSuitableFor(language)) return null
        val indentSize = CodeStyle.getIndentOptions(project, editor.document).INDENT_SIZE.coerceAtLeast(1)
        return AzonIndentation.lineIndent(editor.document.charsSequence, offset, indentSize)
    }
}

/**
 * Computes AZON indentation from delimiter depth rather than from the existing
 * whitespace. Strings and both comment forms are skipped, so braces in data or
 * documentation never move the next line. A key whose value starts on the next
 * line receives one continuation level.
 */
internal object AzonIndentation {

    fun lineIndent(text: CharSequence, offset: Int, indentSize: Int): String {
        val safeOffset = offset.coerceIn(0, text.length)
        val lineStart = currentLineStart(text, safeOffset)
        val scan = scanPrefix(text, lineStart)
        val closesLevel = firstCodeCharacter(text, lineStart) in CLOSERS

        var levels = scan.depth
        if (closesLevel) {
            levels--
        } else if (scan.previousCodeLineLast == ':') {
            levels++
        }

        return " ".repeat(levels.coerceAtLeast(0) * indentSize.coerceAtLeast(1))
    }

    private fun currentLineStart(text: CharSequence, offset: Int): Int {
        var start = offset
        while (start > 0 && text[start - 1] != '\n') start--
        return start
    }

    private fun firstCodeCharacter(text: CharSequence, lineStart: Int): Char? {
        var i = lineStart
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++
        return text.getOrNull(i)
    }

    private fun scanPrefix(text: CharSequence, end: Int): ScanResult {
        var depth = 0
        var state = ScanState.CODE
        var blockCommentDepth = 0
        var escaped = false
        var lineLast: Char? = null
        var previousCodeLineLast: Char? = null
        var i = 0

        fun finishLine() {
            if (lineLast != null) previousCodeLineLast = lineLast
            lineLast = null
        }

        while (i < end) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when (state) {
                ScanState.CODE -> when {
                    c == '\n' -> finishLine()
                    c == '/' && next == '/' -> {
                        state = ScanState.LINE_COMMENT
                        i++
                    }
                    c == '/' && next == '*' -> {
                        state = ScanState.BLOCK_COMMENT
                        blockCommentDepth = 1
                        i++
                    }
                    c == '"' -> {
                        state = ScanState.STRING
                        escaped = false
                        lineLast = c
                    }
                    c in OPENERS -> {
                        depth++
                        lineLast = c
                    }
                    c in CLOSERS -> {
                        depth = (depth - 1).coerceAtLeast(0)
                        lineLast = c
                    }
                    !c.isWhitespace() -> lineLast = c
                }

                ScanState.STRING -> when {
                    c == '\n' -> {
                        finishLine()
                        state = ScanState.CODE
                        escaped = false
                    }
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> {
                        lineLast = c
                        state = ScanState.CODE
                    }
                }

                ScanState.LINE_COMMENT -> if (c == '\n') {
                    finishLine()
                    state = ScanState.CODE
                }

                ScanState.BLOCK_COMMENT -> when {
                    c == '\n' -> finishLine()
                    c == '/' && next == '*' -> {
                        blockCommentDepth++
                        i++
                    }
                    c == '*' && next == '/' -> {
                        blockCommentDepth--
                        i++
                        if (blockCommentDepth == 0) state = ScanState.CODE
                    }
                }
            }
            i++
        }

        if (lineLast != null) previousCodeLineLast = lineLast
        return ScanResult(depth, previousCodeLineLast)
    }

    private data class ScanResult(val depth: Int, val previousCodeLineLast: Char?)

    private enum class ScanState { CODE, STRING, LINE_COMMENT, BLOCK_COMMENT }

    private val OPENERS = setOf('{', '[')
    private val CLOSERS = setOf('}', ']')
}

/** Reformat Code support for AZON's flat token PSI. */
class AzonFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(context: FormattingContext): FormattingModel {
        val settings = context.codeStyleSettings
        val indentSize = settings.getIndentOptions(AzonFileType.INSTANCE).INDENT_SIZE.coerceAtLeast(1)
        val root = AzonRootBlock(context.node, spacingBuilder(settings), indentSize)
        return FormattingModelProvider.createFormattingModelForPsiFile(
            context.containingFile,
            root,
            settings,
        )
    }

    private fun spacingBuilder(settings: CodeStyleSettings): SpacingBuilder =
        SpacingBuilder(settings, AzonLanguage)
            .before(AzonTokenTypes.COLON).spaces(0)
            .after(AzonTokenTypes.COLON).spaces(1)
            .before(AzonTokenTypes.COMMA).spaces(0)
            .after(AzonTokenTypes.COMMA).spaces(1)
}

/** Root block that assigns an absolute code-style indent to each flat token. */
private class AzonRootBlock(
    node: ASTNode,
    private val spacing: SpacingBuilder,
    private val indentSize: Int,
) : AbstractBlock(node, Wrap.createWrap(WrapType.NONE, false), null) {

    override fun buildChildren(): List<Block> {
        val tokens = node.getChildren(null).filter { it.textLength > 0 && !it.text.isBlank() }
        var depth = 0
        return tokens.map { token ->
            val character = token.text.singleOrNull()
            if (character == '}' || character == ']') depth = (depth - 1).coerceAtLeast(0)
            val block = AzonLeafBlock(token, Indent.getSpaceIndent(depth * indentSize))
            if (character == '{' || character == '[') depth++
            block
        }
    }

    override fun getIndent(): Indent = Indent.getNoneIndent()

    override fun getSpacing(child1: Block?, child2: Block): Spacing? =
        spacing.getSpacing(this, child1, child2)

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
        ChildAttributes(Indent.getNoneIndent(), null)

    override fun isLeaf(): Boolean = false
}

private class AzonLeafBlock(
    node: ASTNode,
    private val ownIndent: Indent,
) :
    AbstractBlock(node, Wrap.createWrap(WrapType.NONE, false), null) {

    override fun buildChildren(): List<Block> = emptyList()

    override fun getIndent(): Indent = ownIndent

    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
        ChildAttributes(Indent.getNoneIndent(), null)

    override fun isLeaf(): Boolean = true
}
