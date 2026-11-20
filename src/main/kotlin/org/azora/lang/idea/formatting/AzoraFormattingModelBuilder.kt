/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.formatting

import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.SpacingBuilder
import com.intellij.lang.ASTNode
import com.intellij.psi.formatter.common.AbstractBlock
import org.azora.lang.idea.AzoraFileType
import org.azora.lang.idea.AzoraLanguage
import org.azora.lang.idea.AzoraTokenTypes as Tokens

/** Formats the flat Azora PSI using the same line structure as Enter. */
class AzoraFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(context: FormattingContext): FormattingModel {
        val settings = context.codeStyleSettings
        val file = context.containingFile
        val options = settings.getIndentOptions(AzoraFileType.INSTANCE)
        val indentation = AzoraIndentation(file.text, options)
        val spacing = SpacingBuilder(settings, AzoraLanguage)
            .after(Tokens.COMMA).spaces(1)
            .before(Tokens.COMMA).spaces(0)
            .after(Tokens.COLON).spaces(1)
            .before(Tokens.COLON).spaces(0)
            .before(Tokens.SEMICOLON).spaces(0)
            .around(Tokens.ARROW).spaces(1)
            .before(Tokens.L_BRACE).spaces(1)
            .after(Tokens.DOT).spaces(0)
        val root = object : AbstractBlock(file.node, null, null) {
            override fun buildChildren(): List<Block> = node.getChildren(null)
                .filter { it.textLength > 0 && !it.chars.isBlank() }
                .map { child -> Leaf(child, indentation.widthAtOffset(child.startOffset)) }

            override fun getIndent(): Indent = Indent.getNoneIndent()
            override fun getSpacing(child1: Block?, child2: Block): Spacing? = spacing.getSpacing(this, child1, child2)
            override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
                ChildAttributes(Indent.getNoneIndent(), null)
            override fun isLeaf(): Boolean = false
        }
        return FormattingModelProvider.createFormattingModelForPsiFile(file, root, settings)
    }

    private class Leaf(node: ASTNode, private val width: Int?) : AbstractBlock(node, null, null) {
        override fun buildChildren(): List<Block> = emptyList()
        override fun getIndent(): Indent? = width?.let { Indent.getSpaceIndent(it) }
        override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
        override fun isLeaf(): Boolean = true
    }
}
