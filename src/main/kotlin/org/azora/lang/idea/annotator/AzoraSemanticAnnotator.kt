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
import org.azora.lang.idea.AzoraTokenTypes
import org.azora.lang.idea.highlighting.AzoraSemanticModel
import org.azora.lang.idea.highlighting.AzoraSemanticSymbols
import org.azora.lang.idea.highlighting.AzoraSyntaxHighlighter
import org.azora.lang.idea.highlighting.AzoraToken
import org.azora.lang.idea.symbol.AzoraMacroIndex
import org.azora.lang.idea.symbol.AzoraSymbolService
import org.azora.lang.idea.symbol.SymbolInfo
import org.azora.lang.idea.symbol.SymbolKind
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

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
        val start = element.textRange.startOffset
        val classification = classification(file)

        // The lexer reads `${` as one token, so the `$` that opens a macro hole
        // has to be coloured by the character rather than by the token: the `{`
        // beside it is punctuation and keeps the interpolation colour.
        if (start in classification.macroHoleSigils) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(start, start + 1))
                .textAttributes(AzoraSyntaxHighlighter.MACRO_HOLE)
                .create()
            return
        }

        // A deprecated name is struck through wherever it is written. The
        // strike carries no colour of its own, so it layers over whatever the
        // name already reads as.
        if (element.node?.elementType == AzoraTokenTypes.IDENTIFIER &&
            element.text in classification.deprecated
        ) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(element.textRange)
                .textAttributes(AzoraSyntaxHighlighter.DEPRECATED)
                .create()
        }

        val key = classification.keys[start] ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element.textRange)
            .textAttributes(key)
            .create()
    }

    /**
     * A file's colours: one key per token, plus the offsets where only a single
     * character is coloured.
     */
    private data class Classification(
        val keys: Map<Int, TextAttributesKey>,
        val macroHoleSigils: Set<Int>,
        /** Names whose declaration carries `@Deprecated`, from anywhere visible. */
        val deprecated: Set<String> = emptySet(),
    )

    /** The file's token classification, recomputed only when the file changes. */
    private fun classification(file: PsiFile): Classification =
        CachedValuesManager.getCachedValue(file) {
            val macros = runCatching {
                AzoraMacroIndex.getInstance(file.project).macrosFor(file.text)
            }.getOrDefault(org.azora.lang.idea.symbol.AzoraMacros.EMPTY)
            val service = AzoraSymbolService.getInstance(file.project)
            val filePath = file.virtualFile?.path ?: file.name
            val tokens = tokensOf(file)
            val visible = runCatching {
                service.getAllVisibleSymbols(file.project, filePath, file.text)
            }.getOrDefault(emptyList())
            val symbols = runCatching { semanticSymbols(visible) }.getOrDefault(AzoraSemanticSymbols.EMPTY)
            val result = Classification(
                keys = AzoraSemanticModel.classify(tokens, macros, symbols),
                macroHoleSigils = AzoraSemanticModel.macroHoleSigils(tokens),
                deprecated = deprecatedNames(visible),
            )
            // The classification reads the whole file *and* the project index,
            // so a change anywhere can change a colour here: a `pack` declared
            // in another tab makes its name a type in this one. Depending on
            // this file alone left those colours behind until something forced
            // a reparse, which is what made them look like they were not live.
            CachedValueProvider.Result.create(result, file, PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** Every name a visible declaration deprecates, members included. */
    private fun deprecatedNames(symbols: List<SymbolInfo>): Set<String> {
        val result = linkedSetOf<String>()
        fun visit(symbol: SymbolInfo) {
            if (symbol.isDeprecated) result.add(symbol.name)
            symbol.members.forEach(::visit)
        }
        symbols.forEach(::visit)
        return result
    }

    /** Converts the source index into the compact name sets needed for coloring. */
    private fun semanticSymbols(symbols: List<SymbolInfo>): AzoraSemanticSymbols {
        val types = linkedSetOf<String>()
        val specs = linkedSetOf<String>()
        val functions = linkedSetOf<String>()
        val decorators = linkedSetOf<String>()
        val properties = linkedSetOf<String>()
        val computedProperties = linkedSetOf<String>()
        val enumCases = linkedSetOf<String>()
        val errorCases = linkedSetOf<String>()

        // A variant is spelled the same whether it belongs to an `enum` or to an
        // `error`; only the declaration it hangs off says which, so the parent
        // travels with it.
        fun visit(symbol: SymbolInfo, parent: SymbolKind? = null) {
            when (symbol.kind) {
                SymbolKind.VARIANT -> when (parent) {
                    SymbolKind.FAIL -> errorCases.add(symbol.name)
                    SymbolKind.ENUM -> enumCases.add(symbol.name)
                    else -> Unit
                }
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
                SymbolKind.PROPERTY -> {
                    properties.add(symbol.name)
                    computedProperties.add(symbol.name)
                }
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
            symbol.members.forEach { visit(it, symbol.kind) }
        }

        symbols.forEach { visit(it) }
        return AzoraSemanticSymbols(
            types = types,
            specTypes = specs,
            functions = functions,
            decorators = decorators,
            properties = properties,
            computedProperties = computedProperties,
            enumCases = enumCases,
            errorCases = errorCases,
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
