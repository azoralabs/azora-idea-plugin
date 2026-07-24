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

import com.azora.lang.idea.AzoraTokenTypes
import com.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import com.azora.lang.idea.symbol.AzoraSymbolService
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Colors user-defined and built-in **infix macro operators** (`a op b`) as
 * macros (purple), matching the prefix-macro coloring done by the lexer.
 *
 * The lexer already colors prefix macros (`vec@[…]`) and the built-in infix
 * operators `with`/`by`/`reverse`. This annotator additionally colors any
 * identifier used in infix position whose name is a `meta .Infix("op")` operator
 * declared in the current file or the standard library — the lexer can't know
 * those names, but the symbol service does.
 */
class AzoraInfixMacroAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.node?.elementType != AzoraTokenTypes.IDENTIFIER) return
        val name = element.text
        if (name.isEmpty()) return

        val operators = infixOperatorsFor(element)
        if (name !in operators) return
        if (!isInfixPosition(element)) return

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element.textRange)
            .textAttributes(AzoraSyntaxHighlighter.MACRO)
            .create()
    }

    /** The infix operator names in scope for [element]'s file, cached per file. */
    private fun infixOperatorsFor(element: PsiElement): Set<String> {
        val file = element.containingFile ?: return emptySet()
        return CachedValuesManager.getCachedValue(file) {
            val service = AzoraSymbolService.getInstance(file.project)
            val names = service.infixOperatorNames(
                file.virtualFile?.path ?: file.name,
                file.text,
            )
            CachedValueProvider.Result.create(names, file)
        }
    }

    /**
     * True when [element] sits in an infix operator position: the previous
     * meaningful token ends an operand and the next one starts an operand.
     */
    private fun isInfixPosition(element: PsiElement): Boolean {
        val prev = meaningfulSibling(element, forward = false) ?: return false
        val next = meaningfulSibling(element, forward = true) ?: return false
        return prev.node?.elementType in OPERAND_ENDERS && next.node?.elementType in OPERAND_STARTERS
    }

    private fun meaningfulSibling(element: PsiElement, forward: Boolean): PsiElement? {
        var sib = if (forward) element.nextSibling else element.prevSibling
        while (sib != null && sib.node?.elementType in SKIP) {
            sib = if (forward) sib.nextSibling else sib.prevSibling
        }
        return sib
    }

    private companion object {
        val SKIP = setOf(
            AzoraTokenTypes.WHITE_SPACE,
            AzoraTokenTypes.LINE_COMMENT,
            AzoraTokenTypes.BLOCK_COMMENT,
            AzoraTokenTypes.DOC_COMMENT,
        )
        /** Token types that can END an operand (so a following identifier is infix). */
        val OPERAND_ENDERS = setOf(
            AzoraTokenTypes.IDENTIFIER, AzoraTokenTypes.MACRO,
            AzoraTokenTypes.INT_LITERAL, AzoraTokenTypes.REAL_LITERAL,
            AzoraTokenTypes.STRING_LITERAL, AzoraTokenTypes.CHAR_LITERAL,
            AzoraTokenTypes.R_PAREN, AzoraTokenTypes.R_BRACKET,
        )
        /** Token types that can START an operand (so the identifier is infix, not a decl). */
        val OPERAND_STARTERS = setOf(
            AzoraTokenTypes.IDENTIFIER, AzoraTokenTypes.MACRO,
            AzoraTokenTypes.INT_LITERAL, AzoraTokenTypes.REAL_LITERAL,
            AzoraTokenTypes.STRING_LITERAL, AzoraTokenTypes.CHAR_LITERAL,
            AzoraTokenTypes.L_PAREN, AzoraTokenTypes.L_BRACKET,
        )
    }
}
