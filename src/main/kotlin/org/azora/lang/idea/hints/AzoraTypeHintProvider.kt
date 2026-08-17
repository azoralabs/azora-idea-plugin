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

package org.azora.lang.idea.hints

import org.azora.lang.idea.AzoraFile
import org.azora.lang.idea.AzoraTokenTypes
import org.azora.lang.idea.symbol.AzoraSymbolService
import org.azora.lang.idea.symbol.SymbolKind
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Shows the inferred type of a binding that does not write one down, as an
 * inline hint after its name:
 *
 * ```
 * fin origin: Point = Point(x: 0.0, y: 0.0)
 *           ^^^^^^^ shown by the IDE, not present in the source
 * ```
 *
 * The hint is off by default and is toggled under
 * **Settings | Editor | Inlay Hints | Azora**, so it never gets in the way of
 * someone who prefers the source as written. A type is only shown when it can
 * be determined with confidence — a literal, a constructor call, or a call to a
 * function whose return type is known — so a hint is never a guess.
 */
class AzoraTypeHintProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? =
        if (file is AzoraFile) AzoraTypeHintCollector() else null
}

/** Emits one hint per untyped binding. */
private class AzoraTypeHintCollector : SharedBypassCollector {

    override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
        if (element.node?.elementType != AzoraTokenTypes.IDENTIFIER) return

        val keyword = previousMeaningful(element) ?: return
        if (keyword.node?.elementType !in KEYWORD_TYPES) return
        if (keyword.text !in BINDING_KEYWORDS) return

        // An explicit annotation means there is nothing to infer.
        val next = nextMeaningful(element) ?: return
        if (next.node?.elementType == AzoraTokenTypes.COLON) return
        if (next.node?.elementType != AzoraTokenTypes.OPERATOR || next.text != "=") return

        val type = inferType(element, next) ?: return
        sink.addPresentation(
            InlineInlayPosition(element.textRange.endOffset, relatedToPrevious = true),
            hasBackground = false,
        ) {
            text(": $type")
        }
    }

    /** The type of the initializer that starts after [equals]. */
    private fun inferType(name: PsiElement, equals: PsiElement): String? {
        val first = nextMeaningful(equals) ?: return null
        val firstType = first.node?.elementType

        literalType(first)?.let { return it }

        if (firstType != AzoraTokenTypes.IDENTIFIER) return null

        val after = nextMeaningful(first)
        val isCall = after?.node?.elementType == AzoraTokenTypes.L_PAREN

        // `Point(…)` — a constructor call names its own type.
        if (isCall && first.text.firstOrNull()?.isUpperCase() == true) return first.text

        val file = name.containingFile ?: return null
        val service = AzoraSymbolService.getInstance(name.project)
        val symbols = service.getAllVisibleSymbols(
            name.project,
            file.virtualFile?.path ?: file.name,
            file.text,
        )

        if (isCall) {
            // A call to a known function: use its declared return type.
            return symbols.firstOrNull { it.name == first.text && it.kind in CALLABLE_KINDS }?.type
        }
        // A plain alias of another binding: carry its type across.
        return symbols.firstOrNull { it.name == first.text && it.kind in VALUE_KINDS }?.type
    }

    /** The type of a literal token, or `null` when the token is not one. */
    private fun literalType(element: PsiElement): String? = when (element.node?.elementType) {
        AzoraTokenTypes.INT_LITERAL -> "Int"
        AzoraTokenTypes.REAL_LITERAL -> "Real"
        AzoraTokenTypes.STRING_LITERAL, AzoraTokenTypes.RAW_STRING_LITERAL -> "String"
        AzoraTokenTypes.CHAR_LITERAL -> "Char"
        AzoraTokenTypes.KEYWORD -> if (element.text == "true" || element.text == "false") "Bool" else null
        else -> null
    }

    private fun previousMeaningful(element: PsiElement): PsiElement? {
        var sibling = element.prevSibling
        while (sibling != null && AzoraTokenTypes.IGNORABLE.contains(sibling.node?.elementType)) {
            sibling = sibling.prevSibling
        }
        return sibling
    }

    private fun nextMeaningful(element: PsiElement): PsiElement? {
        var sibling = element.nextSibling
        while (sibling != null && AzoraTokenTypes.IGNORABLE.contains(sibling.node?.elementType)) {
            sibling = sibling.nextSibling
        }
        return sibling
    }

    private companion object {
        val BINDING_KEYWORDS = setOf("var", "fin", "let", "val")
        val KEYWORD_TYPES = setOf(
            AzoraTokenTypes.DECLARATION_KEYWORD,
            AzoraTokenTypes.REACTIVE_KEYWORD,
        )
        val CALLABLE_KINDS = setOf(
            SymbolKind.FUNC, SymbolKind.METHOD, SymbolKind.TASK, SymbolKind.FLOW,
        )
        val VALUE_KINDS = setOf(
            SymbolKind.VAR, SymbolKind.FIN, SymbolKind.FIELD, SymbolKind.PARAM,
        )
    }
}
