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

import org.azora.lang.idea.AzoraFile
import org.azora.lang.idea.highlighting.AzoraSemanticModel
import org.azora.lang.idea.highlighting.AzoraSemanticSymbols
import org.azora.lang.idea.highlighting.AzoraToken
import org.azora.lang.idea.symbol.AzoraMacroIndex
import org.azora.lang.idea.symbol.AzoraSymbolService
import org.azora.lang.idea.symbol.SymbolInfo
import org.azora.lang.idea.symbol.SymbolKind
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Applies Azora's semantic colors on top of the lexer's: macros, function
 * calls, type references, declaration names and smart casts.
 *
 * The lexer cannot make these calls — it does not know which names a `macro`
 * declaration introduced, nor which names are types — so the work
 * happens here, against the project's real macro and symbol indexes. The whole
 * file is classified once and cached; annotating a token is then a lookup.
 */
class AzoraSemanticAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return // leaves only
        val file = element.containingFile as? AzoraFile ?: return

        val key = classification(file)[element.textRange.startOffset] ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element.textRange)
            .textAttributes(key)
            .create()
    }

    /** The file's token classification, recomputed only when the file changes. */
    private fun classification(file: PsiFile): Map<Int, TextAttributesKey> =
        CachedValuesManager.getCachedValue(file) {
            val macros = runCatching {
                AzoraMacroIndex.getInstance(file.project).macrosFor(file.text)
            }.getOrDefault(org.azora.lang.idea.symbol.AzoraMacros.EMPTY)
            val service = AzoraSymbolService.getInstance(file.project)
            val filePath = file.virtualFile?.path ?: file.name
            val symbols = runCatching {
                semanticSymbols(service.getAllVisibleSymbols(file.project, filePath, file.text))
            }.getOrDefault(AzoraSemanticSymbols.EMPTY)
            val result = AzoraSemanticModel.classify(tokensOf(file), macros, symbols)
            CachedValueProvider.Result.create(result, file)
        }

    /** Converts the source index into the compact name sets needed for coloring. */
    private fun semanticSymbols(symbols: List<SymbolInfo>): AzoraSemanticSymbols {
        val types = linkedSetOf<String>()
        val specs = linkedSetOf<String>()
        val functions = linkedSetOf<String>()
        val decorators = linkedSetOf<String>()
        val properties = linkedSetOf<String>()

        fun visit(symbol: SymbolInfo) {
            when (symbol.kind) {
                SymbolKind.SPEC -> specs.add(symbol.name)
                SymbolKind.PACK,
                SymbolKind.ENUM,
                SymbolKind.FAIL,
                SymbolKind.SLOT,
                SymbolKind.SOLO,
                SymbolKind.WRAP,
                SymbolKind.TYPEALIAS,
                SymbolKind.GRAPH -> types.add(symbol.name)
                SymbolKind.ANNOT -> decorators.add(symbol.name)
                SymbolKind.PROPERTY,
                SymbolKind.FIELD -> properties.add(symbol.name)
                SymbolKind.FUNC,
                SymbolKind.METHOD,
                SymbolKind.BRIDGE_FUNC,
                SymbolKind.TASK,
                SymbolKind.FLOW,
                SymbolKind.HOOK,
                SymbolKind.INFX,
                SymbolKind.OPERATOR,
                SymbolKind.CTOR,
                SymbolKind.DTOR -> functions.add(symbol.name)
                else -> Unit
            }
            symbol.members.forEach(::visit)
        }

        symbols.forEach(::visit)
        return AzoraSemanticSymbols(
            types = types,
            specTypes = specs,
            functions = functions,
            decorators = decorators,
            properties = properties,
        )
    }

    /** Flattens the file's leaf tokens into the form the model works on. */
    private fun tokensOf(file: PsiFile): List<AzoraToken> {
        val node = file.node ?: return emptyList()
        return node.getChildren(null).map { child ->
            AzoraToken(child.elementType, child.startOffset, child.startOffset + child.textLength, child.text)
        }
    }
}
