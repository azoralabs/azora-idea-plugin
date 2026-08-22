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

import org.azora.lang.idea.AzoraTokenTypes
import com.intellij.formatting.Alignment
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
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CodeStyleSettings

/**
 * Formatting for Azora.
 *
 * Azora's PSI is a flat token stream — semantic structure comes from the
 * compiler, not from this plugin — so the formatter recovers block structure
 * from bracket nesting: a `{`, `[` or `(` opens a level and its partner closes
 * it, and everything between becomes one nested block indented a step further
 * than its parent. That is enough for *Reformat Code* and for the reindent that
 * runs on paste, and it can never mis-indent code it does not understand,
 * because an unclosed bracket simply stops nesting.
 *
 * The nesting has to be a real tree. An [Indent.getNormalIndent] is one step
 * *relative to the enclosing block*, so a model that hands every token to the
 * file block, however deep the token really is, can only ever produce a single
 * step of indentation — which is what a pasted block used to collapse to.
 */
class AzoraFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(context: FormattingContext): FormattingModel {
        val settings = context.codeStyleSettings
        val root = AzoraBlock(
            significantTokens(context.node),
            context.node.textRange,
            Indent.getNoneIndent(),
            spacingBuilder(settings),
        )
        return FormattingModelProvider.createFormattingModelForPsiFile(
            context.containingFile,
            root,
            settings,
        )
    }

    /** Spacing rules: one space around binary operators and after separators. */
    private fun spacingBuilder(settings: CodeStyleSettings): SpacingBuilder =
        SpacingBuilder(settings, org.azora.lang.idea.AzoraLanguage)
            .after(AzoraTokenTypes.COMMA).spaces(1)
            .before(AzoraTokenTypes.COMMA).spaces(0)
            .after(AzoraTokenTypes.COLON).spaces(1)
            .before(AzoraTokenTypes.COLON).spaces(0)
            .before(AzoraTokenTypes.SEMICOLON).spaces(0)
            .around(AzoraTokenTypes.ARROW).spaces(1)
            .before(AzoraTokenTypes.L_BRACE).spaces(1)
            .before(AzoraTokenTypes.DOT).spaces(0)
            .after(AzoraTokenTypes.DOT).spaces(0)

    /**
     * The file's tokens minus whitespace.
     *
     * Whitespace is what the formatter decides, so it must not be inside a
     * block; everything else is, so the blocks tile the code exactly.
     */
    private fun significantTokens(file: ASTNode): List<ASTNode> =
        file.getChildren(null).filter { it.textLength > 0 && it.text.isNotBlank() }
}

/**
 * One bracket nesting level, or one token within it.
 *
 * A block holds the tokens of its own level in order. Each bracketed run
 * becomes a single child block carrying [Indent.getNormalIndent], so depth
 * accumulates: a token three brackets deep sits inside three nested blocks and
 * is indented three steps.
 */
private class AzoraBlock(
    private val tokens: List<ASTNode>,
    private val range: TextRange,
    private val ownIndent: Indent,
    private val spacing: SpacingBuilder,
) : Block {

    private val children: List<Block> by lazy { buildChildren() }

    override fun getTextRange(): TextRange = range

    override fun getSubBlocks(): List<Block> = children

    override fun getIndent(): Indent = ownIndent

    override fun getWrap(): Wrap? = null

    override fun getAlignment(): Alignment? = null

    override fun getSpacing(child1: Block?, child2: Block): Spacing? =
        spacing.getSpacing(this, child1, child2)

    override fun isIncomplete(): Boolean = false

    /** A single token has nothing inside it to lay out. */
    override fun isLeaf(): Boolean = tokens.size == 1 && tokens.single().firstChildNode == null

    /**
     * A line typed inside this block belongs to this block's level.
     *
     * The step for the level itself is already carried by [ownIndent], so a new
     * child adds nothing on top of it. Pressing Enter is answered before this by
     * `AzoraLineIndentProvider`, which reads the line above rather than the tree.
     */
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
        ChildAttributes(Indent.getNoneIndent(), null)

    /**
     * Splits this level into leaf blocks and one nested block per bracketed run.
     *
     * An opener with no partner - a half-typed line, or a fragment cut out of a
     * larger file - still opens a level; it simply runs to the end of this one,
     * which keeps the code that follows indented as the author wrote it instead
     * of snapping it back to the margin.
     */
    private fun buildChildren(): List<Block> {
        if (isLeaf) return emptyList()
        val blocks = mutableListOf<Block>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.elementType !in OPENERS) {
                blocks.add(leaf(token))
                index++
                continue
            }
            blocks.add(leaf(token))
            val close = matchingCloser(index)
            val innerEnd = close ?: tokens.size
            if (innerEnd > index + 1) {
                val inner = tokens.subList(index + 1, innerEnd)
                blocks.add(
                    AzoraBlock(
                        inner,
                        TextRange(inner.first().startOffset, inner.last().textRange.endOffset),
                        Indent.getNormalIndent(),
                        spacing,
                    )
                )
            }
            index = if (close != null) {
                blocks.add(leaf(tokens[close]))
                close + 1
            } else {
                innerEnd
            }
        }
        return blocks
    }

    private fun leaf(token: ASTNode): Block =
        AzoraBlock(listOf(token), token.textRange, Indent.getNoneIndent(), spacing)

    /** The index of the bracket closing the one at [open], or `null` if it is unclosed. */
    private fun matchingCloser(open: Int): Int? {
        var depth = 0
        for (index in open until tokens.size) {
            when (tokens[index].elementType) {
                in OPENERS -> depth++
                in CLOSERS -> {
                    depth--
                    if (depth == 0) return index
                }
                else -> Unit
            }
        }
        return null
    }

    private companion object {
        val OPENERS = setOf(
            AzoraTokenTypes.L_BRACE, AzoraTokenTypes.L_BRACKET, AzoraTokenTypes.L_PAREN
        )
        val CLOSERS = setOf(
            AzoraTokenTypes.R_BRACE, AzoraTokenTypes.R_BRACKET, AzoraTokenTypes.R_PAREN
        )
    }
}
