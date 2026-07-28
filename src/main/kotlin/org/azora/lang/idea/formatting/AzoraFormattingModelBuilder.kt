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
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.codeStyle.CodeStyleSettings

/**
 * Formatting for Azora.
 *
 * Azora's PSI is a flat token stream — semantic structure comes from the
 * compiler, not from this plugin — so the formatter recovers block structure
 * from brace nesting: a `{`, `[` or `(` opens a level and its partner closes
 * it, and everything between is indented one step. That is enough for
 * *Reformat Code* to normalize indentation without a full grammar, and it can
 * never mis-indent code it does not understand, because unbalanced input simply
 * stops nesting.
 */
class AzoraFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(context: FormattingContext): FormattingModel {
        val settings = context.codeStyleSettings
        val root = AzoraBlock(context.node, Indent.getNoneIndent(), spacingBuilder(settings))
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
}

/**
 * A formatting block covering one bracket nesting level.
 *
 * Children are the leaf tokens at this level, plus one nested block per
 * bracketed run. Tokens inside a level get a normal indent; the closing
 * bracket returns to the enclosing level's indent.
 */
private class AzoraBlock(
    node: ASTNode,
    private val ownIndent: Indent,
    private val spacing: SpacingBuilder,
) : AbstractBlock(node, Wrap.createWrap(com.intellij.formatting.WrapType.NONE, false), null) {

    override fun buildChildren(): List<Block> {
        val children = node.getChildren(null).filter { it.textLength > 0 && it.text.isNotBlank() }
        if (children.isEmpty()) return emptyList()
        return buildLevel(children, 0).first
    }

    /**
     * Groups [children] from [start] into blocks until the level's closing
     * bracket, returning the blocks and the index just past that bracket.
     */
    private fun buildLevel(children: List<ASTNode>, start: Int): Pair<List<Block>, Int> {
        val blocks = mutableListOf<Block>()
        var i = start
        while (i < children.size) {
            val child = children[i]
            when (child.elementType) {
                in OPENERS -> {
                    blocks.add(AzoraBlock(child, Indent.getNoneIndent(), spacing))
                    val (nested, next) = buildLevel(children, i + 1)
                    blocks.addAll(nested)
                    i = next
                    // The closing bracket sits at the enclosing indent.
                    if (i < children.size && children[i].elementType in CLOSERS) {
                        blocks.add(AzoraBlock(children[i], Indent.getNoneIndent(), spacing))
                        i++
                    }
                }
                in CLOSERS -> return blocks.map { indented(it) } to i
                else -> {
                    blocks.add(AzoraBlock(child, Indent.getNoneIndent(), spacing))
                    i++
                }
            }
        }
        return blocks to i
    }

    /** Re-wraps a block so its content is indented one level. */
    private fun indented(block: Block): Block =
        if (block is AzoraBlock) AzoraBlock(block.node, Indent.getNormalIndent(), spacing) else block

    override fun getIndent(): Indent = ownIndent

    override fun getSpacing(child1: Block?, child2: Block): Spacing? = spacing.getSpacing(this, child1, child2)

    override fun isLeaf(): Boolean = node.firstChildNode == null

    /** Pressing Enter inside a block indents; before a closing brace it does not. */
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
        ChildAttributes(Indent.getNormalIndent(), null)

    private companion object {
        val OPENERS = setOf(
            AzoraTokenTypes.L_BRACE, AzoraTokenTypes.L_BRACKET, AzoraTokenTypes.L_PAREN
        )
        val CLOSERS = setOf(
            AzoraTokenTypes.R_BRACE, AzoraTokenTypes.R_BRACKET, AzoraTokenTypes.R_PAREN
        )
    }
}
